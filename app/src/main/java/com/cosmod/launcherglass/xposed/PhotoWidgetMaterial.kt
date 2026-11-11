package com.cosmod.launcherglass.xposed

import android.appwidget.AppWidgetHostView
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.*
import java.util.WeakHashMap

/** Wallpaper glass below remote photo content; contour above; no volume. */
internal object PhotoWidgetMaterial {
    private val identities=WeakHashMap<View,ColorDrawable>()
    private val hosts=WeakHashMap<View,Boolean>()
    private val returnStarts=WeakHashMap<View,Long>()
    private val transitionDraw=ThreadLocal<Boolean>()
    private val pendingReturns=java.util.Collections.newSetFromMap(WeakHashMap<View,Boolean>())
    private var homeReturnAt=0L
    private const val RETURN_WINDOW_MS=1100L
    private const val RETURN_FADE_MS=420f
    private var returnTintBase:android.graphics.ColorMatrixColorFilter?=null
    private val returnTintSteps=arrayOfNulls<android.graphics.ColorMatrixColorFilter>(16)

    private fun returnTint(filter:android.graphics.ColorMatrixColorFilter?,progress:Float):android.graphics.ColorMatrixColorFilter? {
        if(filter==null || progress<=0f)return null
        if(progress>=1f)return filter
        if(returnTintBase!==filter){
            returnTintBase=filter
            returnTintSteps.fill(null)
        }
        val step=kotlin.math.round(progress*16f).toInt()
        if(step==0)return null
        if(step==16)return filter
        return returnTintSteps[step] ?: run {
            val values=android.graphics.ColorMatrix().also(filter::getColorMatrix).array
            val mix=step/16f
            for(index in values.indices){
                val identity=if(index==0 || index==6 || index==12 || index==18)1f else 0f
                values[index]=identity+(values[index]-identity)*mix
            }
            android.graphics.ColorMatrixColorFilter(values).also {returnTintSteps[step]=it}
        }
    }

    fun onHomeReturn(){
        if(!PrefsHelper.enabled || !PrefsHelper.widgetGlassEnabled ||
            !android.animation.ValueAnimator.areAnimatorsEnabled())return
        val now=android.os.SystemClock.uptimeMillis()
        if(now-homeReturnAt<280L)return
        homeReturnAt=now
        returnStarts.clear()
        onBackdropChanged()
    }

    /** Arm at the native surface-to-view handoff, before the first visible draw. */
    fun onNativeReturned(source:View?){
        if(source==null || !PrefsHelper.enabled || !PrefsHelper.widgetGlassEnabled ||
            !android.animation.ValueAnimator.areAnimatorsEnabled())return
        var current:View?=source
        repeat(8){
            val candidate=current ?: return@repeat
            if(styled(candidate,PrefsHelper.widgets)){
                pendingReturns.add(candidate)
                returnStarts.remove(candidate)
                candidate.invalidate()
                return
            }
            current=candidate.parent as? View
        }
    }

    private fun returnProgress(view:View,canvas:Canvas):Float {
        if(transitionDraw.get()==true || !view.isShown || !canvas.isHardwareAccelerated)return 1f
        if(pendingReturns.remove(view))returnStarts[view]=android.os.SystemClock.uptimeMillis()
        if(homeReturnAt==0L && !returnStarts.containsKey(view))return 1f
        val now=android.os.SystemClock.uptimeMillis()
        val start=returnStarts[view] ?: run {
            if(now-homeReturnAt>=RETURN_WINDOW_MS)return 1f
            now.also {returnStarts[view]=it}
        }
        val t=((now-start)/RETURN_FADE_MS).coerceIn(0f,1f)
        if(t<1f)view.postInvalidateOnAnimation()
        return t*t*(3f-2f*t)
    }
    fun onBackdropChanged(){
        for((view,active) in hosts)if(active)view.postInvalidateOnAnimation()
    }
    fun onSettingsChanged(){
        PhotoFade.clear()
        hosts.keys.weakSnapshot().forEach {host->content(host).invalidate();host.postInvalidateOnAnimation()}
        if(!PrefsHelper.enabled || !PrefsHelper.widgetGlassEnabled){
            homeReturnAt=0L
            returnStarts.clear()
            pendingReturns.clear()
            for((root,listener) in roots.strongSnapshot())if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(listener)
            roots.clear();stamps.clear()
        }
    }
    private fun style(settings:WidgetMaterialSettings)=GlassStyle(
        settings.liquid,settings.width,settings.distortion,settings.radius,0,null,
        plateColor=Color.WHITE,extendedLens=true,neutral=true,surfaceScope=GlassSurface.WIDGETS,dispersion=settings.dispersion
    )
    private val logged=HashSet<String>()
    private data class WidgetType(val photo:Boolean=false,val clock:Boolean=false)
    private data class PhotoHost(val info:java.lang.ref.WeakReference<Any>,val type:WidgetType)
    private val photoHosts=WeakHashMap<View,PhotoHost>()
    private val cardClasses=HashMap<Class<*>,Boolean>()
    private val contentPaint=ThreadLocal.withInitial {Paint()}
    private fun isCard(view:View)=cardClasses.getOrPut(view.javaClass){
        generateSequence(view.javaClass as Class<*>?){it.superclass}.any {it.name=="com.android.launcher3.card.LauncherCardView"}
    }
    private val drawMethods=HashSet<java.lang.reflect.Method>()
    private val drawing=ThreadLocal<MutableSet<View>>()
    private val roots=WeakHashMap<View,android.view.ViewTreeObserver.OnPreDrawListener>()
    private val stamps=WeakHashMap<View,Int>()
    private fun content(view:View):View {
        val child=if(view is AppWidgetHostView)(view as? ViewGroup)?.getChildAt(0)
            else try {XposedHelpers.callMethod(view,"getSmartView") as? View}catch(_:Throwable){null}
        // Global Gallery can draw mSnapshot before its smart subtree is laid out.
        return child?.takeIf {it.width>0 && it.height>0} ?: view
    }
    private fun transform(host:View,child:View)=Matrix().apply {
        if(child!==host){setTranslate((child.left-host.scrollX).toFloat(),(child.top-host.scrollY).toFloat());preConcat(child.matrix)}
        if(host !is AppWidgetHostView)try {
            // Match clipSmoothRoundCorner's mask transform, including the OEM
            // press/hover spring, which is not necessarily a View scale property.
            val bounds=XposedHelpers.callMethod(host,"getCardBounds") as Rect
            reset()
            if(child!==host)setTranslate(bounds.left.toFloat(),bounds.top.toFloat())
            if(XposedHelpers.callMethod(host,"hasHoverFlag")==true && child!==host){
                val manager=XposedHelpers.callMethod(host,"getFocusFrameAnimManager")
                if(manager!=null)postConcat(XposedHelpers.callMethod(manager,"getHoverBitmapMatrix",RectF(bounds)) as Matrix)
            }
        }catch(_:Throwable){}
    }
    private fun watch(view:View){
        IconGlassHook.registerMaterialView(view)
        if(view is AppWidgetHostView)return
        val root=view.rootView
        if(roots.containsKey(root))return
        val weak=java.lang.ref.WeakReference(root)
        val listener=android.view.ViewTreeObserver.OnPreDrawListener {
            val current=weak.get()
            if(current!=null && PrefsHelper.enabled && PrefsHelper.widgetGlassEnabled)for(host in hosts.keys.weakSnapshot()){
                if(hosts[host]!=true || host is AppWidgetHostView || host.rootView!==current || !host.isShown)continue
                val child=content(host)
                val values=FloatArray(9);transform(host,child).getValues(values)
                val stamp=31*(31*values.contentHashCode()+child.width)+child.height
                if(stamps.put(host,stamp)!=stamp)host.invalidate()
            }
            true
        }
        roots[root]=listener;root.viewTreeObserver.addOnPreDrawListener(listener)
    }
    private fun widgetType(view:View):WidgetType {
        if(view is AppWidgetHostView){
            val info=view.appWidgetInfo ?: return WidgetType()
            photoHosts[view]?.takeIf {it.info.get()===info}?.let {return it.type}
            val provider=info.provider
            val type=WidgetType(PhotoProviders.supports(provider?.packageName,provider?.className,null,false),
                PhotoProviders.isClock(provider?.packageName,provider?.className))
            photoHosts[view]=PhotoHost(java.lang.ref.WeakReference(info),type)
            return type
        }
        val info=view.tag
        if(info!=null)photoHosts[view]?.takeIf {it.info.get()===info}?.let {return it.type}
        val resolved=NativeIconAccess.call(view,"getItemInfo") ?: info
        val provider=try {resolved?.let {XposedHelpers.getObjectField(it,"mProviderName")} as? android.content.ComponentName}catch(_:Throwable){null}
            ?: try {resolved?.let {XposedHelpers.callMethod(it,"getTargetComponent")} as? android.content.ComponentName}catch(_:Throwable){null}
            ?: try {resolved?.let {XposedHelpers.getObjectField(it,"providerName")} as? android.content.ComponentName}catch(_:Throwable){null}
        val cardType=try {XposedHelpers.callMethod(view,"getCardType") as? Int}catch(_:Throwable){null}
        val type=WidgetType(PhotoProviders.supports(provider?.packageName,provider?.className,cardType,true),
            PhotoProviders.isClock(provider?.packageName,provider?.className) ||
                content(view).javaClass.simpleName.contains("clock",ignoreCase=true))
        if(info!=null)photoHosts[view]=PhotoHost(java.lang.ref.WeakReference(info),type)
        return type
    }

    private fun styled(view:View,settings:WidgetMaterialSettings):Boolean {
        if(!PrefsHelper.enabled || !settings.enabled || (view !is AppWidgetHostView && !isCard(view)))return false
        val type=widgetType(view)
        return !type.clock && (settings.allWidgets || type.photo)
    }

    private fun installTransitionDraws(loader:ClassLoader){
        val namespace="com.android.launcher3.dragndrop"
        val card=XposedHelpers.findClassIfExists("$namespace.LauncherCardDrawable",loader)
        if(card!=null){
            ModuleHooks.hookAllMethods(card,"draw",object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(NativeIconAccess.field(param.thisObject,"mOnlyDrawSmartView")!=true)return
                    val host=NativeIconAccess.field(param.thisObject,"mCardView") as? View ?: return
                    if(!styled(host,PrefsHelper.widgets))return
                    val child=NativeIconAccess.call(host,"getSmartView") as? View ?: return
                    val crop=NativeIconAccess.call(host,"getCardBounds") as? Rect ?: return
                    drawTransitionHost(param,host,crop,child.width,child.height)
                }
            })
            ModuleHooks.hookAllMethods(card,"drawCardView",object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(param.args.getOrNull(2)!=true)return
                    val host=NativeIconAccess.field(param.thisObject,"mCardView") as? View ?: return
                    if(!styled(host,PrefsHelper.widgets))return
                    if(NativeIconAccess.call(host,"getSmartView") !is View)return
                    // The alternate OEM branch draws the full card with its title hidden.
                    // Drawing only getSmartView() bypasses the host's glass compositor.
                    param.args[2]=false
                }
            })
        }
        val widget=XposedHelpers.findClassIfExists("$namespace.LauncherWidgetDrawable",loader)
        if(widget!=null)ModuleHooks.hookAllMethods(widget,"drawCardView",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val host=NativeIconAccess.field(param.thisObject,"mCustomLauncherAppWidgetHostView") as? AppWidgetHostView ?: return
                if(!styled(host,PrefsHelper.widgets))return
                val child=NativeIconAccess.call(host,"getWidgetView") as? View ?: return
                val crop=Rect(0,0,child.width,child.height)
                if(crop.isEmpty || child===host)return
                // Preserve the OEM preview's child-sized crop and scale.
                try {host.offsetDescendantRectToMyCoords(child,crop)}catch(_:IllegalArgumentException){return}
                drawTransitionHost(param,host,crop)
            }
        })
        val group=XposedHelpers.findClassIfExists("$namespace.LauncherUSCardDrawable",loader)
        if(group!=null)ModuleHooks.hookAllMethods(group,"drawCardView",object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(NativeIconAccess.field(param.thisObject,"isSeedlingCard")!=true)return
                val host=NativeIconAccess.call(param.thisObject,"getCardView") as? View ?: return
                if(!styled(host,PrefsHelper.widgets))return
                val bitmap=(NativeIconAccess.call(param.thisObject,"getCardViewScreenShot") as? Bitmap)
                    ?.takeUnless {it.isRecycled}
                val crop=(NativeIconAccess.call(host,"getCardBounds") as? Rect)
                    ?.takeUnless {it.isEmpty} ?: Rect(0,0,host.width,host.height)
                // The seedling bitmap is the provider's unstyled screenshot.
                // Keep its destination size while drawing the styled card.
                drawTransitionHost(param,host,crop,bitmap?.width ?: crop.width(),bitmap?.height ?: crop.height())
            }
        })
    }

    private fun drawTransitionHost(param:XC_MethodHook.MethodHookParam,host:View,crop:Rect,
        width:Int=crop.width(),height:Int=crop.height()){
        if(crop.isEmpty || width<=0 || height<=0 || host.width<=0 || host.height<=0)return
        val canvas=param.args.firstOrNull() as? Canvas ?: return
        val scale=(param.args.getOrNull(1) as? Number)?.toFloat() ?: 1f
        if(!scale.isFinite() || scale<=0f)return
        val save=canvas.save()
        try {
            canvas.scale(scale*width/crop.width(),scale*height/crop.height())
            canvas.translate(-crop.left.toFloat(),-crop.top.toFloat())
            canvas.clipRect(crop)
            val previous=transitionDraw.get()
            transitionDraw.set(true)
            try {host.draw(canvas)}finally {
                if(previous==null)transitionDraw.remove() else transitionDraw.set(previous)
            }
            param.result=null
        }catch(t:Throwable){
            GlassLogger.limited("PhotoWidget","transitionDraw") {"Widget transition draw failed: $t"}
        }finally{canvas.restoreToCount(save)}
    }

    fun install(loader:ClassLoader){
        // Independent of optional live SurfaceControl streaming. getClickView is
        // the OEM source, including widget children whose host was hidden.
        for(name in listOf("com.android.launcher3.anim.iconsurfacemanager.surface.IconSurfaceRecord",
            "com.android.launcher3.anim.iconsurfacemanager.IconSurfaceRecord")){
            val record=XposedHelpers.findClassIfExists(name,loader) ?: continue
            ModuleHooks.hookAllMethods(record,"showOriginalIcon",object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    val source=NativeIconAccess.call(p.thisObject,"getClickView") as? View ?: return
                    onNativeReturned(source)
                }
            })
        }
        val contentHook=object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                val view=param.thisObject as? View ?: return
                if(!PrefsHelper.enabled)return
                if(view !is AppWidgetHostView && !isCard(view))return
                val settings=PrefsHelper.widgets
                val enabled=styled(view,settings)
                hosts[view]=enabled
                if(!enabled)return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                if(canvas.quickReject(0f,0f,view.width.toFloat(),view.height.toFloat()))return
                watch(view)
                val child=content(view)
                if(child.width<=0 || child.height<=0)return
                if(logged.add("host:"+view.javaClass.name))GlassLogger.i("PhotoWidget","Registered host: "+view.javaClass.name)
                val active=drawing.get() ?: HashSet<View>().also {drawing.set(it)}
                if(!active.add(view))return
                param.setObjectExtra("photoActive",true)
                val bounds=if(child!==view)Rect(0,0,child.width,child.height) else {
                    val native=NativeIconAccess.call(view,"getCardContentBounds") as? Rect
                    native?.takeIf {!it.isEmpty && it.left>=0 && it.top>=0 && it.right<=view.width && it.bottom<=view.height}
                        ?.let(::Rect) ?: Rect(view.paddingLeft,view.paddingTop,view.width-view.paddingRight,view.height-view.paddingBottom)
                }
                var radius=minOf(bounds.width(),bounds.height())*.08f
                val outline=Outline()
                try {child.outlineProvider?.getOutline(child,outline);if(outline.radius>0f)radius=outline.radius}catch(_:Throwable){}
                // Use the exact radius AND weight used by createClipSmoothRoundBitmap,
                // rather than the differently shaped drag-preview outline.
                var nativePath=if(view is AppWidgetHostView)null else try {
                    val info=XposedHelpers.callMethod(view,"getItemInfo")
                    val rightAngle=try {info!=null && XposedHelpers.getBooleanField(info,"mIsUseRightAngleCard")}catch(_:Throwable){false}
                    if(rightAngle)Path().apply {addRect(RectF(bounds),Path.Direction.CCW)} else {
                    val pair=XposedHelpers.callMethod(view,"getCardRadiusAndWeight",view)!!
                    radius=(XposedHelpers.callMethod(pair,"a") as Number).toFloat()
                    val weight=(XposedHelpers.callMethod(pair,"b") as Number).toFloat()
                    Path().also {path ->
                        val manager=XposedHelpers.findClass("com.android.launcher3.util.SmoothRoundedManager",loader)
                        XposedHelpers.callStaticMethod(manager,"createSmoothPath",path,RectF(bounds),radius,weight,Path.Direction.CCW)
                    }
                    }
                }catch(_:Throwable){null}
                var nativeMask=if(view is AppWidgetHostView)null else try {
                    val info=XposedHelpers.callMethod(view,"getItemInfo")
                    val rightAngle=try {info!=null && XposedHelpers.getBooleanField(info,"mIsUseRightAngleCard")}catch(_:Throwable){false}
                    if(rightAngle)null else {
                        val stored=XposedHelpers.getObjectField(view,"mBitmap") as? Bitmap
                        val cardBounds=XposedHelpers.callMethod(view,"getCardBounds") as Rect
                        (stored?.takeIf {!it.isRecycled && it.width==cardBounds.width()+2 && it.height==cardBounds.height()+2}
                            ?: (XposedHelpers.callMethod(view,"createClipSmoothRoundBitmap") as? Bitmap)?.also {XposedHelpers.setObjectField(view,"mBitmap",it)})
                            ?.takeIf {!it.isRecycled && it.width>2 && it.height>2}
                    }
                }catch(_:Throwable){null}
                val customFrame=settings.customFrame
                if(customFrame){
                    radius=minOf(bounds.width(),bounds.height())*settings.corner/100f
                    nativePath=Path().apply {addRoundRect(RectF(bounds),radius,radius,Path.Direction.CW)}
                    nativeMask=null
                }
                val identity=identities.getOrPut(child){ColorDrawable(Color.TRANSPARENT)}
                try {
                    val style=style(settings)
                    val matrix=transform(view,child)
                    val progress=returnProgress(view,canvas)
                    val glassAlpha=kotlin.math.round(255f*progress).toInt()
                    val save=canvas.save()
                    try {
                        if(glassAlpha>0){
                            canvas.concat(matrix)
                            SurfaceMaterial.draw(canvas,bounds,child,identity,radius,nativePath,decorate=false,
                                alpha=glassAlpha,style=style,nativeMask=nativeMask)
                        }
                    }finally {canvas.restoreToCount(save)}
                    val paint=contentPaint.get()!!.apply {
                        alpha=kotlin.math.round(255f-(255f-settings.opacity*2.55f)*progress).toInt().coerceIn(0,255)
                        colorFilter=returnTint(settings.tintFilter,progress)
                    }
                    param.setObjectExtra("photoReturnProgress",progress)
                    // Enclose the COMPLETE OEM compositor: live children, cached
                    // Picture, preload image and mSnapshot. Treating only the
                    // smart subtree leaves an opaque snapshot above the glass.
                    val contentSave=canvas.save()
                    param.setObjectExtra("photoSave",contentSave)
                    if(customFrame){
                        // Cut only the photo's corners; keep OEM titles outside its footprint.
                        val footprint=Path().apply {addRect(RectF(bounds),Path.Direction.CW);transform(matrix)}
                        val rounded=Path(nativePath!!).apply {transform(matrix)}
                        val clip=Path().apply {
                            fillType=Path.FillType.EVEN_ODD
                            addRect(RectF(canvas.clipBounds),Path.Direction.CW)
                            addPath(footprint);addPath(rounded)
                        }
                        canvas.clipPath(clip)
                    }
                    if(paint.alpha!=255 || paint.colorFilter!=null || settings.hasFade){
                        val layerBounds=RectF(bounds).apply {matrix.mapRect(this);union(0f,0f,view.width.toFloat(),view.height.toFloat())}
                        canvas.saveLayer(layerBounds,paint)
                    }
                    param.setObjectExtra("widgetProfile",settings)
                    param.setObjectExtra("photoBounds",bounds);param.setObjectExtra("photoRadius",radius);param.setObjectExtra("photoPath",nativePath)
                    param.setObjectExtra("photoMask",nativeMask)
                    param.setObjectExtra("photoTransform",matrix)
                }catch(t:Throwable){
                    (param.getObjectExtra("photoSave") as? Int)?.let {saved ->
                        try {canvas.restoreToCount(saved)}catch(_:Throwable){}
                        param.setObjectExtra("photoSave",null)
                    }
                    GlassLogger.e("PhotoWidget","Photo material failed",t)
                }
            }
            override fun afterHookedMethod(param:MethodHookParam){
                if(param.getObjectExtra("photoActive")!=true)return
                drawing.get()?.remove(param.thisObject as? View)
                val save=param.getObjectExtra("photoSave") as? Int ?: return
                val canvas=param.args.firstOrNull() as? Canvas ?: return
                try {
                    val bounds=param.getObjectExtra("photoBounds") as Rect
                    val settings=param.getObjectExtra("widgetProfile") as WidgetMaterialSettings
                    val progress=param.getObjectExtra("photoReturnProgress") as? Float ?: 1f
                    // Fade only the isolated widget content, above the glass.
                    try {if(settings.hasFade){
                        val maskSave=canvas.save()
                        try {
                            canvas.concat(param.getObjectExtra("photoTransform") as Matrix)
                            PhotoFade.apply(canvas,bounds,settings.fadeHeight,kotlin.math.round(settings.fade*progress).toInt(),settings.fadeSmoothness)
                        }finally{canvas.restoreToCount(maskSave)}
                    }}finally{canvas.restoreToCount(save)}
                    if(progress<=0f)return
                    val radius=param.getObjectExtra("photoRadius") as Float
                    val outer=canvas.save()
                    try {
                        canvas.concat(param.getObjectExtra("photoTransform") as Matrix)
                        if(progress<1f)canvas.saveLayerAlpha(RectF(bounds),kotlin.math.round(255f*progress).toInt())
                        if(settings.customFrame)
                            GlassFrame.draw(canvas,bounds,radius,settings.shade,
                                0,settings.rim,param.getObjectExtra("photoPath") as? Path)
                        else SurfaceMaterial.contour(canvas,bounds,radius,param.getObjectExtra("photoPath") as? Path,
                            param.getObjectExtra("photoMask") as? Bitmap,settings.rim)
                        SurfaceMaterial.edge(canvas,bounds,radius,param.getObjectExtra("photoPath") as? Path,
                            param.getObjectExtra("photoMask") as? Bitmap,param.thisObject as View,settings.liquid)
                    }finally{canvas.restoreToCount(outer)}
                }catch(t:Throwable){GlassLogger.e("PhotoWidget","Photo contour failed",t)}
            }
        }
        for(name in listOf("android.appwidget.AppWidgetHostView","com.android.launcher3.card.groupcard.GroupCardView","com.android.launcher3.card.TitleCardView","com.android.launcher3.card.CommonCardView","com.android.launcher3.card.EngineCardView","com.android.launcher3.card.LauncherCardView","com.android.launcher3.widget.LauncherAppWidgetHostView","com.android.launcher3.widget.CustomLauncherAppWidgetHostView")){
                var cls:Class<*>?=XposedHelpers.findClassIfExists(name,loader)
                while(cls!=null){
                    val method=cls.declaredMethods.firstOrNull {it.name=="dispatchDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))}
                    if(method!=null){
                        if(method.declaringClass==ViewGroup::class.java)break
                        if(drawMethods.add(method))ModuleHooks.hookMethod(method,contentHook)
                        break
                    }
                    cls=cls.superclass
                }
        }
        installTransitionDraws(loader)
    }
}
