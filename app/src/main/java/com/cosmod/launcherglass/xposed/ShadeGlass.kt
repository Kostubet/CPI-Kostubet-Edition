package com.cosmod.launcherglass.xposed

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.ui.GlassEngineSettings
import com.cosmod.launcherglass.ui.ShadeGlassSettings
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Component as C
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Panel
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Profile
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.ShadeGlassRenderer
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** ColorOS 17 notification shade only. No persistent mutations to the host views or drawables. */
internal object ShadeGlass {
    private val main=Handler(Looper.getMainLooper())
    private var guard:SharedPreferences?=null
    private var backdrop:ShadeBackdrop?=null
    private var nativeBlur:ShadeNativeBlur?=null
    private var initialized=false
    private var blocked=false
    private var guardStarted=false
    private var prefs=mapOf<String,Any>()
    private var profiles=mapOf<C,Profile>()
    private val plates=WeakHashMap<View,ShadeGlassRenderer>()
    private val awakeViews=WeakHashMap<View,Boolean>()
    private val position=IntArray(2)
    private val transform=Matrix()
    private val axes=FloatArray(4)
    private val origin=FloatArray(2)
    private val box=RectF()
    private val screenBox=RectF()
    private var rowClass:Class<*>?=null
    private var stackClass:Class<*>?=null
    private var expanded:Field?=null
    private var status:Field?=null
    private var panelClass:Class<*>?=null
    private var separatePanelClass:Class<*>?=null
    private var qsRootClass:Class<*>?=null
    private var controlBackground:Profile?=null
    private var standardBackgroundId=0
    private var tileBackgroundId=0
    private var anyShade=false
    private var anyControl=false
    private var anyNotifications=false
    private val drawnComponents=java.util.EnumSet.noneOf(C::class.java)
    private val sliderOwner=ThreadLocal<View>()
    private val qsVisible:(View)->Boolean={ view->
        ancestors(view).any { qsRootClass?.isInstance(it)==true || panelClass?.isInstance(it)==true ||
            separatePanelClass?.isInstance(it)==true }
    }
    private val notificationsVisible:(View)->Boolean={ view->
        val stack=ancestors(view).firstOrNull { stackClass?.isInstance(it)==true }
        stack!=null && expanded?.getBoolean(stack)==true && status?.getInt(stack)==0
    }
    private val clearVisible:(View)->Boolean={ view->
        view.isShown && (view.parent as? View)?.isShown==true
    }

    private fun ancestors(view:View)=generateSequence(view){it.parent as? View}
    private fun active(component:C)=profiles[component]?.enabled==true && !blocked
    private fun panelActive(panel:Panel)=!blocked && if(panel==Panel.CONTROL)anyControl else anyNotifications

    fun diagnostics() = "ShadeGlass enabled=${profiles.filterValues { it.enabled }.keys}, blocked=$blocked, " +
        "drawn=$drawnComponents, ${backdrop?.diagnostics() ?: "capture inactive"}"

    fun configure(context:Context,loader:ClassLoader?,bundle:Bundle,master:Boolean) {
        if(Build.VERSION.SDK_INT!=37 || loader==null)return
        val softControl=SoftGlassNative.replaces(com.cosmod.launcherglass.ui.SoftGlassSettings.Surface.CONTROL)
        val softShade=SoftGlassNative.replaces(com.cosmod.launcherglass.ui.SoftGlassSettings.Surface.SHADE)
        val values=ShadeGlassSettings.booleans.mapValues {(k,v)->master && bundle.getBoolean(k,v) &&
            !(k==Panel.CONTROL.enabled && softControl) && !(k==Panel.NOTIFICATIONS.enabled && softShade)} +
            (ShadeGlassSettings.integers+GlassEngineSettings.integers.filterKeys {it.startsWith("shade_glass_")})
                .mapValues {(k,v)->bundle.getInt(k,v)}
        if(prefs==values)return
        prefs=values
        profiles=C.entries.associateWith { component->
            ShadeGlassSettings.profile(component.panel,component,values[component.panel.enabled]==true,
                values[component.panel.highRefresh]==true,values[component.panel.newEngine]==true,
                GlassEngineSettings.optics(bundle,component.panel.newEngine)) {
                values.getValue(it) as Int
            }
        }
        anyControl=Panel.CONTROL.components.any(::active)
        anyNotifications=Panel.NOTIFICATIONS.components.any(::active)
        controlBackground=ShadeGlassSettings.controlBackground(values[ShadeGlassSettings.CONTROL_BACKGROUND]==true,
            values[Panel.CONTROL.highRefresh]==true) {
            values.getValue(it) as Int
        }.takeIf { it.enabled }
        anyShade=anyControl || anyNotifications || controlBackground!=null
        if(!anyShade){
            reset()
            return
        }
        if(!guardStarted){
            guardStarted=true
            val store=context.createDeviceProtectedStorageContext().getSharedPreferences("cpi_shade_glass_guard",Context.MODE_PRIVATE)
            guard=store
            val now=SystemClock.elapsedRealtime()
            if(!AlertStartupGuard.begin(store,now,BuildConfig.VERSION_CODE)){
                blocked=true
                GlassLogger.i("ShadeGlass","Previous initialization was interrupted. Stock shade retained.")
                return
            }
            main.postDelayed({AlertStartupGuard.stable(store,now)},30_000L)
        }
        if(!initialized){
            initialized=true
            standardBackgroundId=context.resources.getIdentifier("quick_settings_background","id",context.packageName)
            tileBackgroundId=context.resources.getIdentifier("qs_tile_bg","id",context.packageName)
            backdrop=ShadeBackdrop(context,{ view->
                plates.remove(view)?.clear()
                view.invalidate()
            },::disable)
            nativeBlur=ShadeNativeBlur(context,loader,{controlBackground.takeUnless {blocked}},
                {true})
            install(loader)
        }
        reset()
    }

    private fun reset() {
        backdrop?.clear()
        plates.values.forEach { it.clear() }
        plates.clear()
        awakeViews.keys.toList().forEach { it.invalidate() }
    }

    private fun disable(error:Throwable){
        if(blocked)return
        blocked=true
        anyShade=false
        reset()
        guard?.let { AlertStartupGuard.block(it) }
        GlassLogger.e("ShadeGlass","Rendering disabled; stock shade retained",error)
    }

    private fun field(type:Class<*>,name:String):Field = generateSequence(type) { it.superclass }
        .firstNotNullOfOrNull { runCatching { it.getDeclaredField(name).apply { isAccessible=true } }.getOrNull() }
        ?: throw NoSuchFieldException("${type.name}.$name")

    private fun install(loader:ClassLoader) {
        fun optional(section:String,contract:()->Unit) {
            runCatching(contract).onFailure { GlassLogger.e("ShadeGlass","Unsupported $section contract; stock retained",it) }
        }
        optional("panel"){
            panelClass=loader.loadClass("com.oplus.systemui.qs.OplusQSPanelContainer")
            separatePanelClass=loader.loadClass("com.oplus.systemui.separate.OplusPanelViewPager")
        }
        optional("control center background") { installControlBackground(loader) }
        optional("notification rows") { installRows(loader) }
        optional("tile and button backgrounds") { installBackgrounds(loader) }
        optional("slider tracks") { installSliders(loader) }
    }

    private fun installControlBackground(loader:ClassLoader) {
        val root=loader.loadClass("com.oplus.systemui.plugins.qs.OplusQSRootView")
        qsRootClass=root
        val dispatch=ViewGroup::class.java.getDeclaredMethod("dispatchDraw",Canvas::class.java)
        XposedBridge.hookMethod(dispatch,object:XC_MethodHook() {
            override fun beforeHookedMethod(p:MethodHookParam) {
                if(controlBackground!=null && !blocked && root.isInstance(p.thisObject))
                    nativeBlur?.observe(p.thisObject as View)
            }
        })
    }

    private fun installRows(loader:ClassLoader) {
        val row=loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow")
        val stack=loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout")
        val background=loader.loadClass("com.android.systemui.statusbar.notification.row.NotificationBackgroundView")
        val isGroup=row.getMethod("isSummaryWithChildren")
        val isChild=row.getMethod("isChildInGroup")
        val isExpanded=row.getMethod("isShowingExpanded")
        val isHeadsUp=row.getMethod("isHeadsUpState")
        val normalBackground=field(row,"mBackgroundNormal")
        val expansion=field(background,"mExpandAnimationRunning")
        val top=field(background,"mClipTopAmount")
        val bottom=field(background,"mClipBottomAmount")
        val topOverlap=field(background,"mTopOverlap")
        val bottomOverlap=field(background,"mBottomOverlap")
        val alpha=field(background,"mDrawableAlpha")
        val actualWidth=background.getMethod("getActualWidth")
        val actualHeight=background.getMethod("getActualHeight")
        val align=background.getDeclaredMethod("isAlignedToRight").apply { isAccessible=true }
        expanded=field(stack,"mIsExpanded")
        status=field(stack,"mStatusBarState")
        rowClass=row
        stackClass=stack
        XposedBridge.hookMethod(background.getDeclaredMethod("onDraw",Canvas::class.java),object:XC_MethodHook() {
            override fun beforeHookedMethod(p:MethodHookParam) {
                if(!panelActive(Panel.NOTIFICATIONS))return
                val view=p.thisObject as View
                val parent=view.parent as? View ?: return
                if(!row.isInstance(parent) || parent.isPressed || view.isPressed)return
                runCatching {
                    if(normalBackground.get(parent)!==view || expansion.getBoolean(view) ||
                        isHeadsUp.invoke(parent)==true || NotificationBannerGlass.isBanner(view) || !notificationsVisible(view))return
                    val component=when {
                        isGroup.invoke(parent)==true || isChild.invoke(parent)==true->C.GROUP
                        isExpanded.invoke(parent)==true->C.EXPANDED
                        else->C.COMPACT
                    }
                    if(!active(component))return
                    val h=(actualHeight.invoke(view) as Int).coerceAtMost(view.height)
                    val w=(actualWidth.invoke(view) as Int).coerceAtMost(view.width)
                    val left=if(align.invoke(view)==true)view.width-w else 0
                    val clipTop=maxOf(top.getInt(view),topOverlap.getInt(view),0)
                    val clipBottom=h-maxOf(bottom.getInt(view),bottomOverlap.getInt(view),0)
                    val opacity=alpha.getInt(view)
                    if(clipBottom<=clipTop || w<=0 || opacity<=0)return
                    val canvas=p.args[0] as Canvas
                    val save=canvas.save()
                    try {
                        canvas.clipRect(0,clipTop,view.width,clipBottom)
                        if(opacity<255)canvas.saveLayerAlpha(left.toFloat(),clipTop.toFloat(),(left+w).toFloat(),clipBottom.toFloat(),opacity)
                        if(draw(canvas,view,component,left.toFloat(),0f,(left+w).toFloat(),h.toFloat(),notificationsVisible))p.result=null
                    } finally {canvas.restoreToCount(save)}
                }.onFailure(::disable)
            }
        })
    }

    private fun installBackgrounds(loader:ClassLoader) {
        val round=loader.loadClass("com.oplus.systemui.qs.base.tile.OplusQSTileBaseView")
        val large=loader.loadClass("com.oplus.systemui.qs.base.tile.OplusQSHighlightTileView")
        val media=loader.loadClass("com.oplus.systemui.qs.media.OplusQsBaseMediaPanelView")
        // Scenario and detailed tiles share the stock background ImageView.
        val detail=runCatching { loader.loadClass("com.oplus.systemui.qs.slider.detail.widget.QsDetailTileBaseView") }.getOrNull()
        val detailBackground=detail?.let { runCatching { it.getMethod("getIvBg") }.getOrNull() }
        val scenario=runCatching { loader.loadClass("com.oplus.systemui.qs.scenario.ui.template.ScenarioTileViewOneXOne") }.getOrNull()
        val resizeable=runCatching {
            val prefix="com.oplus.systemui.plugins.qs.customize.view.tile.OplusQSResizeableTileView"
            val type=loader.loadClass(prefix)
            val tileState=type.getMethod("getTileState")
            ResizeableTile(type,loader.loadClass(prefix+"OneXOne"),type.getMethod("getBg"),
                type.getMethod("isEditMode"),tileState,field(tileState.returnType,"state"))
        }.onFailure { GlassLogger.e("ShadeGlass","Resizable tile contract unavailable",it) }.getOrNull()
        val standard=runCatching {
            loader.loadClass("com.oplus.systemui.qs.OplusQSContainerImpl").let { it to field(it,"mBackground") }
        }.getOrNull()
        val clear=loader.loadClass("com.oplus.systemui.notification.clearall.OplusClearAllButton")
        val tileDrawable=loader.loadClass("com.oplus.systemui.qs.base.res.drawable.TileDrawableWrapper")
        val roundBg=field(round,"mBg")
        val largeBg=field(large,"mBg")
        val roundState=field(round,"mTileIconState")
        val customizer=field(round,"mIsCustomizer")
        val deform=tileDrawable.getMethod("isDeforming")
        val mediaBg=media.getMethod("getBg")
        val actionId=android.content.res.Resources.getSystem().getIdentifier("action0","id","android")
        val drawBackground=View::class.java.getDeclaredMethod("drawBackground",Canvas::class.java)
        XposedBridge.hookMethod(drawBackground,object:XC_MethodHook() {
            override fun beforeHookedMethod(p:MethodHookParam) {
                if(!anyShade || blocked)return
                val view=p.thisObject as View
                val stock=view.background ?: return
                if(view.isPressed || view.width<=0 || view.height<=0)return
                runCatching {
                    val full=controlBackground
                    if(full!=null && standard!=null && standardBackgroundId!=0 && view.id==standardBackgroundId) {
                        val ancestry=ancestors(view)
                        val container=ancestry.firstOrNull(standard.first::isInstance)
                        if(container!=null && standard.second.get(container)===view &&
                            ancestry.none { qsRootClass?.isInstance(it)==true })
                            nativeBlur?.observe(container)
                    }
                    val target=when {
                        panelActive(Panel.CONTROL) && (stock.javaClass.name.startsWith("com.oplus.systemui.") ||
                            stock.javaClass.name.startsWith("com.oplusos.systemui.") ||
                            tileBackgroundId!=0 && view.id==tileBackgroundId)->
                            ancestors(view).firstOrNull {
                                round.isInstance(it)||large.isInstance(it)||media.isInstance(it)||
                                    resizeable?.type?.isInstance(it)==true||detail?.isInstance(it)==true
                            }
                        else->null
                    }
                    val component=when {
                        target!=null && round.isInstance(target) && roundBg.get(target)===view && !customizer.getBoolean(target)->C.ROUND
                        target!=null && large.isInstance(target) && largeBg.get(target)===view->C.LARGE
                        target!=null && media.isInstance(target) && mediaBg.invoke(target)===view->C.MEDIA
                        target!=null && resizeable!=null && resizeable.type.isInstance(target) &&
                            resizeable.background.invoke(target)===view && resizeable.editing.invoke(target)==false->
                            if(resizeable.small.isInstance(target)) C.ROUND else C.LARGE
                        target!=null && detail?.isInstance(target)==true && detailBackground?.invoke(target)===view->C.OTHER_TILE
                        clear.isInstance(view)->C.CLEAR
                        view is Button && actionId!=0 && view.id==actionId &&
                            ancestors(view).any { rowClass?.isInstance(it)==true }->C.ACTION
                        else->return
                    }
                    if(!active(component) || target?.isPressed==true ||
                        tileDrawable.isInstance(stock) && deform.invoke(stock)==true)return
                    val reader=when(component.panel){
                        Panel.CONTROL->qsVisible
                        Panel.NOTIFICATIONS->if(component==C.CLEAR)clearVisible else notificationsVisible
                    }
                    val selected=target!=null && component==C.ROUND && when {
                        round.isInstance(target)->roundState.getInt(target)==2
                        resizeable!=null && resizeable.type.isInstance(target)->
                            resizeable.state.invoke(target)?.let { resizeable.value.getInt(it)==2 }==true
                        else->false
                    }
                    val canvas=p.args[0] as Canvas
                    val w=view.width.toFloat()
                    val h=view.height.toFloat()
                    val circle=component==C.ROUND || scenario?.isInstance(target)==true
                    if(draw(canvas,view,component,0f,0f,w,h,reader,selected,circle)){
                        if(stock.bounds.left!=0 || stock.bounds.top!=0 ||
                            stock.bounds.right!=view.width || stock.bounds.bottom!=view.height){
                            stock.setBounds(0,0,view.width,view.height)
                        }
                        p.result=null
                    }
                }.onFailure(::disable)
            }
        })
    }

    private data class ResizeableTile(val type:Class<*>,val small:Class<*>,
        val background:java.lang.reflect.Method,val editing:java.lang.reflect.Method,
        val state:java.lang.reflect.Method,val value:Field)

    private fun installSliders(loader:ClassLoader) {
        val slider=loader.loadClass("com.oplus.systemui.qs.base.seek.OplusQsVerticalSeekBar")
        val layout=loader.loadClass("com.oplus.systemui.qs.base.seek.OplusQsBaseToggleSliderLayout")
        val brightness=loader.loadClass("com.oplus.systemui.qs.base.seek.ClipBrightnessView")
        val volume=loader.loadClass("com.oplus.systemui.qs.base.seek.OplusQsVolumeIconView")
        val detail=field(slider,"isDetailToggle")
        val base=field(slider,"baseMixColorDrawable")
        val toggle=layout.getMethod("getToggleView")
        val blur=loader.loadClass("com.oplusos.systemui.common.blurability.drawable.AutoBlurDrawable")
        XposedBridge.hookMethod(slider.getDeclaredMethod("onDraw",Canvas::class.java),object:XC_MethodHook() {
            override fun beforeHookedMethod(p:MethodHookParam) {
                if(active(C.BRIGHTNESS) || active(C.VOLUME))sliderOwner.set(p.thisObject as View)
            }
            override fun afterHookedMethod(p:MethodHookParam) { sliderOwner.remove() }
        })
        XposedBridge.hookMethod(blur.getDeclaredMethod("draw",Canvas::class.java),object:XC_MethodHook() {
            override fun beforeHookedMethod(p:MethodHookParam) {
                val view=sliderOwner.get() ?: return
                runCatching {
                    if(detail.getBoolean(view) || base.get(view)!==p.thisObject)return
                    val parent=ancestors(view).firstOrNull(layout::isInstance) ?: return
                    val icon=toggle.invoke(parent) as? View ?: return
                    val component=when {
                        brightness.isInstance(icon)->C.BRIGHTNESS
                        volume.isInstance(icon)->C.VOLUME
                        else->return
                    }
                    val bounds=(p.thisObject as Drawable).bounds
                    if(draw(p.args[0] as Canvas,view,component,bounds.left.toFloat(),bounds.top.toFloat(),
                        bounds.right.toFloat(),bounds.bottom.toFloat(),qsVisible))p.result=null
                }.onFailure(::disable)
            }
        })
    }

    private fun draw(canvas:Canvas,view:View,component:C,left:Float,top:Float,right:Float,bottom:Float,
        visible:(View)->Boolean,selected:Boolean=false,circle:Boolean=false):Boolean {
        val material=profiles[component] ?: return false
        val drawn=drawMaterial(canvas,view,material,left,top,right,bottom,visible,selected,circle)
        if(drawn && drawnComponents.add(component))GlassLogger.i("ShadeGlass","First draw: $component (${view.javaClass.name})")
        return drawn
    }

    private fun drawMaterial(canvas:Canvas,view:View,material:Profile,left:Float,top:Float,right:Float,bottom:Float,
        visible:(View)->Boolean,selected:Boolean=false,circle:Boolean=false,backdropOnly:Boolean=false):Boolean {
        if(blocked || !material.enabled || !canvas.isHardwareAccelerated || !visible(view) || right<=left || bottom<=top)return false
        if(awakeViews.put(view,true)==null)view.addOnAttachStateChangeListener(detach)
        view.getLocationOnScreen(position)
        transform.reset()
        view.transformMatrixToGlobal(transform)
        axes[0]=1f;axes[1]=0f;axes[2]=0f;axes[3]=1f
        transform.mapVectors(axes)
        if(axes[0]<=0f || axes[3]<=0f || abs(axes[1])>.001f || abs(axes[2])>.001f)return false
        origin.fill(0f)
        transform.mapPoints(origin)
        val x=position[0]+origin[0]-origin[0].roundToInt()
        val y=position[1]+origin[1]-origin[1].roundToInt()
        box.set(left,top,right,bottom)
        screenBox.set(x+left*axes[0],y+top*axes[3],x+right*axes[0],y+bottom*axes[3])
        val frame=backdrop?.frameFor(view,screenBox,material,visible) ?: return false
        val light=(view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_NO
        return plates.getOrPut(view){ShadeGlassRenderer()}.draw(canvas,box,screenBox,frame,material,
            view.resources.displayMetrics.density,light,selected,circle,backdropOnly)
    }

    private val detach=object:View.OnAttachStateChangeListener{
        override fun onViewAttachedToWindow(v:View)=Unit
        override fun onViewDetachedFromWindow(v:View) {
            v.removeOnAttachStateChangeListener(this)
            awakeViews.remove(v)
            backdrop?.forget(v)
            plates.remove(v)?.clear()
        }
    }
}
