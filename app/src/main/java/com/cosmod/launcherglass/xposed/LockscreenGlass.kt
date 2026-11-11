package com.cosmod.launcherglass.xposed

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cosmod.launcherglass.ui.LockscreenSettings
import com.cosmod.launcherglass.ui.LockscreenSettings.Profile
import com.cosmod.launcherglass.ui.LockscreenSettings.Surface
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.widgetTintFilter
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** System UI render hooks. Authentication and notification contents stay in their native views. */
internal object LockscreenGlass {
    private const val KEYGUARD=1
    private const val STATUS="com.android.systemui.statusbar.StatusBarStateControllerImpl"
    private const val WALLPAPER="com.oplus.systemui.keyguard.wallpaper.OplusLockScreenWallpaperController"
    private const val WALLPAPER_ANIM="com.oplus.systemui.keyguard.anim.OplusWallpaperAnimControllerImpl"
    private const val SCRIM="com.oplus.systemui.statusbar.phone.ScrimBlurStateUtilsExImpl"
    private const val NOTIFICATION="com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val KEYGUARD_STATE="com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
    private const val PIN_ROOT="com.android.keyguard.KeyguardPINView"
    private const val COUI_PIN_ROOT="com.oplus.keyguard.security.view.OplusKeyguardPinBaseInputView"
    private const val PIN_KEYS="com.coui.appcompat.passworddial.key."
    private const val NUMERIC_KEYBOARD="com.coui.appcompat.lockview.COUINumericKeyboard"
    private const val NUMERIC_PIN="com.oplus.keyguard.security.widget.NumericKeyboardWidget"
    private const val OPLUS_SHORTCUT="com.oplus.systemui.keyguard.view.OplusLaunchableImageView"
    private const val PLUGIN_SHORTCUT="com.oplus.keyguard.ui.base.widget.OplusLaunchableImageView"
    private const val PLUGIN_SHORTCUTS="com.oplus.keyguard.ui.base.view.BottomAffordanceContainer"
    private const val WIDGET_CARD="com.oplus.keyguard.style.widgets.container.view.CardView"
    private const val INSTANT_CARD="com.oplus.keyguard.style.widgets.container.view.InstantCardView"
    private const val CAPSULE_BG="com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundView"
    private const val CAPSULE_ITEM="com.oplus.systemui.notification.lockscreen.capsule.CapsuleItemView"
    private const val CLOCK_PLUGIN="com.oplus.keyguard.personality.clocks"
    private const val BASE_CLOCK="com.oplus.keyguard.clock.base.ui.view.ClockTimeView"
    private val clockGroups=setOf(BASE_CLOCK,"com.oplus.keyguard.clock.digital.ui.view.ClockTimeView")
    private val clockGlyphViews=setOf(
        "com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView",
        "com.oplus.keyguard.clock.gallery.widget.MyCustomizedTextView",
        "com.oplus.keyguard.clock.text.widget.MyCustomizedTextView")
    private val stockClockDigits=setOf("tv_hour","tv_minute","tv_colon")
    private val clockBounds=RectF()
    private val widgetLeafOwners=HashSet<Class<*>>()
    private val clockText=setOf("com.android.systemui.shared.clocks.AnimatableClockView","com.android.keyguard.AnimatableClockView")
    private val main=Handler(Looper.getMainLooper())
    private class Target {
        val material=LockscreenMaterial()
        var transitionMaterial:LockscreenMaterial?=null
        var pinKeys:Array<LockscreenMaterial?>?=null
        var glyph:ClockGlyphGlass?=null
        var clockParent:WeakReference<ViewGroup>?=null
        private var widgetPaint:Paint?=null
        private var widgetTone:IconPalette.WallpaperTone?=null
        private var widgetTint:LockscreenSettings.WidgetTint?=null

        fun tintPaint(tone:IconPalette.WallpaperTone,tint:LockscreenSettings.WidgetTint):Paint? {
            if(tint.transparency>=100)return null
            if(tone!=widgetTone || tint!=widgetTint){
                val filter=widgetTintFilter(tone.hue,tone.saturationAt(tint.saturation),0,tint.transparency)
                widgetPaint=(widgetPaint ?: Paint()).apply {colorFilter=filter}
                widgetTone=tone
                widgetTint=tint
            }
            return widgetPaint
        }

        fun discard(){
            material.discard()
            transitionMaterial?.discard()
            pinKeys?.forEach {it?.discard()}
            if(android.os.Build.VERSION.SDK_INT>=33){glyph?.discard();glyph=null}
        }
    }
    private val views=WeakHashMap<View,Target>()
    private val hooks=ArrayList<XC_MethodHook.Unhook>()
    private val knownTargets=HashSet<Surface>()
    private val rendered=HashSet<Surface>()
    private var context:Context?=null
    private var loader:ClassLoader?=null
    private var keyguard:KeyguardManager?=null
    private var interactive=false
    private var keyguardState:Int?=null
    private var keyguardShowing:Boolean?=null
    private var dozing=false
    private var profiles:Map<Surface,Profile> = emptyMap()
    private var clearBouncer=false
    private var visible=false
    private var controller=WeakReference<Any>(null)
    private var wallpaperAnimation=WeakReference<Any>(null)
    private var pinClass:Class<*>?=null
    private var pinRootClass:Class<*>?=null
    private var startId=View.NO_ID
    private var endId=View.NO_ID
    private var rowId=View.NO_ID
    private val shortcutDrawTypes=HashSet<Class<*>>()
    private val shortcutContainers=HashSet<Class<*>>()
    private val shortcutBounds=RectF()
    private val pinBounds=RectF()
    private val capsuleBounds=RectF()
    private val notificationBounds=RectF()
    private val groupOverrides=HashSet<Class<*>>()
    private val inspectedGroups=HashSet<Class<*>>()
    private val hookedGroupOwners=HashSet<Class<*>>()
    private val activeGroupOverrides=HashSet<ViewGroup>()
    private val movingTargets=WeakHashMap<View,Boolean>()
    private var positionRefreshPending=false
    private val capsuleMotion=WeakHashMap<View,CapsuleMotion>()
    private val rowMotion=WeakHashMap<View,CapsuleMotion>()
    private val transitionAdd=Paint().apply {blendMode=BlendMode.PLUS}
    private val widgetRoots=WeakHashMap<ViewGroup,WeakReference<View>>()
    private val widgetPlates=WeakHashMap<View,Boolean>()
    private var notificationId=View.NO_ID
    private var capsuleTitleId=View.NO_ID
    private var clockHostId=View.NO_ID
    private var wallpaperRetry=false
    private var wallpaperSource="waiting"
    private var unlockSpeed=100
    private var unlockSmoothness=0
    private var unlockImpulse=0
    private var unlockHooksInstalled=false
    private var baseUnlockCurve:android.animation.TimeInterpolator?=null
    private var smootherUnlockCurve:android.animation.TimeInterpolator?=null

    private fun wrapUnlockCurve(curve:android.animation.TimeInterpolator):android.animation.TimeInterpolator {
        if(curve===smootherUnlockCurve)return curve
        if(curve===baseUnlockCurve)smootherUnlockCurve?.let {return it}
        baseUnlockCurve=curve
        return android.animation.TimeInterpolator {fraction->
            val time=fraction.coerceIn(0f,1f)
            val t=time+.70f*unlockImpulse*.01f*time*(1f-time)*(1f-time)
            val native=curve.getInterpolation(t)
            val eased=t*t*t*(t*(t*6f-15f)+10f)
            native+(eased-native)*unlockSmoothness*.01f
        }.also {smootherUnlockCurve=it}
    }

    private fun installUnlockTuning(){
        if(unlockHooksInstalled)return
        val type=XposedHelpers.findClassIfExists(
            "com.oplus.keyguard.OplusKeyguardUnlockAnimationControllerExImpl\$OplusUnlockAnimRule",loader) ?: return
        val duration=type.getDeclaredMethod("getBehindAnimDuration")
        val curve=type.getDeclaredMethod("getAnimInterpolator")
        XposedBridge.hookMethod(duration,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable() || unlockSpeed==100)return
                val native=p.result as? Int ?: return
                if(native>0)p.result=((native*100L+unlockSpeed/2)/unlockSpeed).coerceAtLeast(1).toInt()
            }
        })
        XposedBridge.hookMethod(curve,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable() || unlockSmoothness==0 && unlockImpulse==0)return
                // ColorOS uses null to select its default curve during transition setup.
                val native=p.result as? android.animation.TimeInterpolator ?: return
                p.result=wrapUnlockCurve(native)
            }
        })
        unlockHooksInstalled=true
        GlassLogger.i("LockscreenGlass","Native unlock motion settings installed")
    }

    fun bind(context:Context,loader:ClassLoader){
        if(this.context!=null)return
        this.context=context
        this.loader=loader
        keyguard=context.getSystemService(KeyguardManager::class.java)
        interactive=context.getSystemService(PowerManager::class.java)?.isInteractive==true
        fun id(name:String)=context.resources.getIdentifier(name,"id",context.packageName).takeIf {it!=0} ?: View.NO_ID
        startId=id("start_button")
        endId=id("end_button")
        notificationId=id("backgroundNormal")
        capsuleTitleId=id("tv_capsule_title")
        clockHostId=context.resources.getIdentifier("clock_time_view_parent","id",CLOCK_PLUGIN)
            .takeIf {it!=0} ?: View.NO_ID
        rowId=id("expandableNotificationRow")
        pinClass=XposedHelpers.findClassIfExists("com.android.keyguard.NumPadKey",loader)
        pinRootClass=XposedHelpers.findClassIfExists(PIN_ROOT,loader)
        XposedHelpers.findClassIfExists(STATUS,loader)?.let {type->
            val state=object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val next=runCatching {NativeIconAccess.call(p.thisObject,"getState") as? Int}.getOrNull()
                    val asleep=runCatching {NativeIconAccess.call(p.thisObject,"isDozing")==true}.getOrDefault(false)
                    onMain {keyguardState=next;dozing=asleep;refreshVisibility()}
                }
            }
            XposedBridge.hookAllMethods(type,"setState",state)
            XposedBridge.hookAllMethods(type,"setIsDozing",state)
        }
        XposedHelpers.findClassIfExists(KEYGUARD_STATE,loader)?.let {type->
            XposedBridge.hookAllMethods(type,"notifyKeyguardState",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable() || p.args.size!=2)return
                    val showing=p.args[0] as? Boolean ?: return
                    val occluded=p.args[1] as? Boolean ?: return
                    onMain {keyguardShowing=showing && !occluded;refreshVisibility()}
                }
            })
        }
        XposedHelpers.findClassIfExists(WALLPAPER,loader)?.let {type->
            XposedBridge.hookAllConstructors(type,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val value=p.thisObject
                    onMain {
                        controller=WeakReference(value)
                        if(visible)prepareWallpaper(refresh=true)
                    }
                }
            })
            XposedBridge.hookAllMethods(type,"setMWallpaperHelper",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val value=p.thisObject
                    onMain {
                        controller=WeakReference(value)
                        if(visible)prepareWallpaper(refresh=true)
                    }
                }
            })
        }
        XposedHelpers.findClassIfExists(WALLPAPER_ANIM,loader)?.let {type->
            XposedBridge.hookAllMethods(type,"getLockScreenWallpaperAnimController",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val animation=p.result ?: return
                    onMain {
                        if(wallpaperAnimation.get()===animation)return@onMain
                        wallpaperAnimation=WeakReference(animation)
                        if(visible)prepareWallpaper()
                    }
                }
            })
        }
        val filter=IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_WALLPAPER_CHANGED)
        }
        ContextCompat.registerReceiver(context,object:BroadcastReceiver(){
            override fun onReceive(c:Context?,intent:Intent?){
                when(intent?.action){
                    Intent.ACTION_SCREEN_OFF->interactive=false
                    Intent.ACTION_SCREEN_ON->interactive=true
                    Intent.ACTION_USER_PRESENT->{keyguardState=0;keyguardShowing=false}
                    Intent.ACTION_WALLPAPER_CHANGED->{if(visible)prepareWallpaper(refresh=true);return}
                }
                refreshVisibility()
            }
        },filter,ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun configure(prefs:Bundle,enabled:Boolean){
        val tune=enabled && prefs.getBoolean(LockscreenSettings.UNLOCK_ENABLED)
        unlockSpeed=if(tune)prefs.getInt(LockscreenSettings.UNLOCK_SPEED,100)
            .coerceIn(LockscreenSettings.MIN_UNLOCK_SPEED,LockscreenSettings.MAX_UNLOCK_SPEED) else 100
        unlockSmoothness=if(tune)prefs.getInt(LockscreenSettings.UNLOCK_SMOOTHNESS,0).coerceIn(0,100) else 0
        unlockImpulse=if(tune)prefs.getInt(LockscreenSettings.UNLOCK_IMPULSE,0).coerceIn(0,100) else 0
        if(tune)runCatching {installUnlockTuning()}.onFailure {
            GlassLogger.limited("LockscreenGlass","unlockTuning"){"Native unlock tuning unavailable: "+it}
        }
        val next=if(enabled)Surface.entries.associateWith {LockscreenSettings.read(prefs,it)}.filterValues {it.enabled} else emptyMap()
        val clear=enabled && prefs.getBoolean(LockscreenSettings.CLEAR_BOUNCER_BLUR,false)
        if(next==profiles && clear==clearBouncer){refreshVisibility();return}
        profiles=next
        if(next[Surface.NOTIFICATIONS]?.corner?.let {it>=0}!=true)restoreNotificationCorners()
        clearBouncer=clear
        if(profiles.isEmpty() && !clear){
            hooks.forEach {it.unhook()}
            hooks.clear()
            shortcutDrawTypes.clear()
            shortcutContainers.clear()
            groupOverrides.clear()
            inspectedGroups.clear()
            hookedGroupOwners.clear()
            widgetRoots.clear()
            widgetPlates.clear()
            widgetLeafOwners.clear()
        }else if(hooks.isEmpty()){
            try {installDrawing()}
            catch(t:Throwable){
                hooks.forEach {it.unhook()}
                hooks.clear()
                shortcutDrawTypes.clear()
                shortcutContainers.clear()
                groupOverrides.clear()
                inspectedGroups.clear()
                hookedGroupOwners.clear()
                widgetRoots.clear()
                widgetPlates.clear()
                widgetLeafOwners.clear()
                profiles=emptyMap()
                clearBouncer=false
                GlassLogger.e("LockscreenGlass","Lockscreen hooks unavailable",t)
            }
        }
        refreshVisibility()
        if(visible)prepareWallpaper()
        repaint()
    }

    private fun onMain(action:()->Unit){if(Looper.myLooper()===Looper.getMainLooper())action()else main.post(action)}

    private fun refreshVisibility(){
        val locked=keyguardShowing ?: (keyguardState==KEYGUARD || keyguard?.isKeyguardLocked==true)
        val show=profiles.isNotEmpty() && interactive && !dozing && locked
        if(show==visible)return
        visible=show
        wallpaperRetry=false
        if(show)prepareWallpaper()else{
            LockscreenBackdrop.stop()
            restoreNotificationCorners()
            capsuleMotion.clear()
            rowMotion.clear()
            movingTargets.clear()
            views.values.forEach {it.discard()}
        }
        repaint()
    }

    private fun prepareWallpaper(refresh:Boolean=false){
        val context=context ?: return
        LockscreenBackdrop.prepare(context,controller.get(),wallpaperAnimation.get(),
            profiles.entries.mapTo(linkedSetOf()) {(surface,profile)->profile.sourceRadius(surface)},refresh)
    }

    fun wallpaperChanged(){
        val frame=LockscreenBackdrop.frame
        wallpaperSource=frame?.source ?: "unavailable"
        GlassLogger.limited("LockscreenGlass","wallpaper-"+wallpaperSource) {"Wallpaper source: "+wallpaperSource}
        if(visible && frame?.images.isNullOrEmpty() && !wallpaperRetry){
            wallpaperRetry=true
            main.postDelayed({if(visible && LockscreenBackdrop.frame?.images.isNullOrEmpty())prepareWallpaper(refresh=true)},750L)
        }
        repaint()
    }
    private fun repaint(){
        views.strongSnapshot().forEach { (view,target)->
            if(view.isAttachedToWindow)view.invalidate()
            target.clockParent?.get()?.takeIf {it.isAttachedToWindow}?.invalidate()
        }
    }
    fun diagnostics()="lockscreen profiles="+profiles.keys+", visible="+visible+", targets="+knownTargets+", rendered="+rendered+", wallpaper="+wallpaperSource

    private fun installDrawing(){
        val loader=loader ?: return
        hook(ViewGroup::class.java,"dispatchDraw",arrayOf(Canvas::class.java),object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                val view=p.thisObject as ViewGroup
                if(visible && view.javaClass !in groupOverrides)beginGroup(p,view)
            }
            override fun afterHookedMethod(p:MethodHookParam){endGroup(p)}
        })
        hook(ViewGroup::class.java,"drawChild",
            arrayOf(Canvas::class.java,View::class.java,Long::class.javaPrimitiveType!!),object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(!visible || drawingClock || Surface.CLOCK !in profiles)return
                    val child=p.args[1] as View
                    val parent=p.thisObject as ViewGroup
                    val name=child.javaClass.name
                    if(name in clockGroups || name in clockText ||
                        name in clockGlyphViews && belongsToClock(child) ||
                        child is TextView && isStockClockDigit(child)){
                        beginClockChild(p,parent,child)
                    }
                }
                override fun afterHookedMethod(p:MethodHookParam){endClockChild(p)}
            })
        val imageHook=object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(!visible)return
                val view=p.thisObject as View
                val name=view.javaClass.name
                if(name==OPLUS_SHORTCUT || name==PLUGIN_SHORTCUT)return
                if(name==PIN_KEYS+"PasswordDialIconKeyView"){
                    if(isInPinLayout(view))draw(p,view,Surface.PIN)
                }else affordance(view)?.let {draw(p,view,it)}
            }
        }
        hook(ImageView::class.java,"onDraw",arrayOf(Canvas::class.java),imageHook)
        hook(TextView::class.java,"onDraw",arrayOf(Canvas::class.java),object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(!visible)return
                val view=p.thisObject as View
                val name=view.javaClass.name
                when {
                    name==PIN_KEYS+"PasswordDialKeyView" && isInPinLayout(view)->draw(p,view,Surface.PIN)
                    else->affordance(view)?.let {draw(p,view,it)}
                }
            }
        })
        XposedHelpers.findClassIfExists(OPLUS_SHORTCUT,loader)?.let(::hookShortcut)
        XposedHelpers.findClassIfExists(NUMERIC_KEYBOARD,loader)?.let {type->
            val int=Int::class.javaPrimitiveType!!
            hook(type,"drawCell",arrayOf(Canvas::class.java,int,int),object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){drawNumericKey(p)}
            })
        }
        XposedHelpers.findClassIfExists(NOTIFICATION,loader)?.let {type->
            hook(type,"onDraw",arrayOf(Canvas::class.java),object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){beginNotification(p)}
                override fun afterHookedMethod(p:MethodHookParam){endNotification(p)}
            })
        }
        XposedHelpers.findClassIfExists(CAPSULE_ITEM,loader)?.let {type->
            hooks+=XposedBridge.hookAllMethods(type,"setCapsuleProgress",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){updateCapsuleProgress(p)}
            })
        }
        XposedHelpers.findClassIfExists(CAPSULE_BG,loader)?.let {type->
            hook(type,"onDraw",arrayOf(Canvas::class.java),object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){drawCapsule(p)}
            })
        }
        XposedHelpers.findClassIfExists(SCRIM,loader)?.let {type->
            hook(type,"getBouncerScrimMaxBlurRadius",emptyArray(),object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){if(clearBouncer)p.result=0}
            })
            hook(type,"setFrontScrimBlurAmount",arrayOf(Float::class.javaPrimitiveType!!,String::class.java),
                object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        if(!clearBouncer)return
                        val bouncer=runCatching {
                            val lazy=NativeIconAccess.field(p.thisObject,"scrimController") ?: return@runCatching false
                            val scrim=NativeIconAccess.call(lazy,"get") ?: return@runCatching false
                            val state=(NativeIconAccess.call(scrim,"getState") as? Enum<*>)?.name
                            state=="BOUNCER" || state=="BOUNCER_SCRIMMED"
                        }.getOrDefault(false)
                        if(bouncer)p.args[0]=0f
                    }
                })
        }
    }

    private fun hook(type:Class<*>,name:String,parameters:Array<Class<*>>,callback:XC_MethodHook){
        val method=type.declaredMethods.firstOrNull {it.name==name && it.parameterTypes.contentEquals(parameters)} ?: return
        hooks+=XposedBridge.hookMethod(method,callback)
    }

    private fun hookShortcut(type:Class<*>){
        if(!shortcutDrawTypes.add(type))return
        runCatching {
            hook(type,"onDraw",arrayOf(Canvas::class.java),object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(!visible)return
                    val view=p.thisObject as View
                    val surface=affordance(view) ?: return
                    if(!profiles.containsKey(surface))return
                    val circle=NativeIconAccess.call(view,"getCircleRadius") as? Float ?: 0f
                    if(circle>minOf(view.width,view.height)*.5f)return
                    val x=(view.paddingLeft+view.width-view.paddingRight)*.5f
                    val y=(view.paddingTop+view.height-view.paddingBottom)*.5f
                    val nativeRadius=NativeIconAccess.field(view,"mMinBackgroundRadius") as? Int ?: 0
                    val radius=if(nativeRadius>0)minOf(nativeRadius.toFloat(),x,y,view.width-x,view.height-y)
                        else minOf(view.width,view.height)*.5f
                    if(radius<=0f)return
                    shortcutBounds.set(x-radius,y-radius,x+radius,y+radius)
                    draw(p,view,surface,bounds=shortcutBounds)
                }
            })
        }.onFailure {GlassLogger.limited("LockscreenGlass","shortcut-hook") {"Shortcut hook unavailable: "+it.javaClass.simpleName}}
    }

    private fun affordance(view:View):Surface? {
        if(view.id!=View.NO_ID && (view.id==startId || view.id==endId))return Surface.SHORTCUTS
        if(view.javaClass.name!=PLUGIN_SHORTCUT)return null
        val parent=view.parent as? View ?: return null
        if(parent.javaClass.name!=PLUGIN_SHORTCUTS)return null
        fun matches(getter:String):Boolean {
            val proxy=NativeIconAccess.call(parent,getter) ?: return false
            return NativeIconAccess.field(proxy,"e")===view
        }
        return if(matches("getStartAffordanceView") || matches("getEndAffordanceView"))Surface.SHORTCUTS else null
    }

    private fun isInPinLayout(view:View):Boolean {
        var parent=view.parent as? View
        repeat(8){
            val group=parent ?: return false
            if(generateSequence(group.javaClass as Class<*>?) {it.superclass}.any {it.name==COUI_PIN_ROOT})return true
            parent=group.parent as? View
        }
        return false
    }

    private fun drawNumericKey(p:XC_MethodHook.MethodHookParam){
        if(!visible)return
        val profile=profiles[Surface.PIN] ?: return
        val view=p.thisObject as View
        if(view.javaClass.name!=NUMERIC_PIN || !isInPinLayout(view))return
        val column=p.args[1] as Int
        val row=p.args[2] as Int
        if(column !in 0..2 || row !in 0..3 || row==3 && column!=1)return
        val canvas=p.args[0] as Canvas
        if(!renderable(view,canvas))return
        val target=target(view,Surface.PIN)
        val frame=LockscreenBackdrop.frame ?: return
        if(!frame.images.containsKey(profile.sourceRadius(Surface.PIN)))return
        try {
            val rows=NativeIconAccess.field(view,"sCells") as? Array<*> ?: return
            val cells=rows.getOrNull(row) as? Array<*> ?: return
            val cell=cells.getOrNull(column) ?: return
            val opacity=(NativeIconAccess.field(cell,"cellNumberAlpha") as? Float ?: 1f).coerceIn(0f,1f)
            if(opacity<=.01f)return
            val size=NativeIconAccess.field(view,"mNumberBackgroundRadius") as? Int ?: return
            val scale=NativeIconAccess.field(cell,"mButtonScale") as? Float ?: 1f
            val radius=size*scale
            if(radius<=0f)return
            fun offset(name:String)=NativeIconAccess.field(cell,name) as? Int ?: 0
            val x=(XposedHelpers.callMethod(view,"getCenterXForColumn",column) as Float)+offset("cellNumberTranslateX")
            val y=(XposedHelpers.callMethod(view,"getCenterYForRow",row) as Float)+offset("cellNumberTranslateY")
            pinBounds.set(x-radius,y-radius,x+radius,y+radius)
            val keys=target.pinKeys ?: arrayOfNulls<LockscreenMaterial>(12).also {target.pinKeys=it}
            val index=row*3+column
            val material=keys[index] ?: LockscreenMaterial().also {keys[index]=it}
            material.draw(canvas,view,Surface.PIN,profile,frame,bounds=pinBounds,amount=opacity)
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","numeric-pin") {"Native PIN drawing kept: "+t.javaClass.simpleName}
        }
    }

    private data class NotificationCorners(val stockTop:Float,val stockBottom:Float,val top:Float,val bottom:Float)
    private val notificationCorners=WeakHashMap<View,NotificationCorners>()

    private fun restoreNotificationCorners(){
        val entries=notificationCorners.strongSnapshot()
        notificationCorners.clear()
        for((view,old) in entries)restoreNotificationCorners(view,old)
    }

    private fun restoreNotificationCorners(view:View,old:NotificationCorners){
        val current=NativeIconAccess.field(view,"mCornerRadii") as? FloatArray ?: return
        if(current.size==8 && current[0]==old.top && current[4]==old.bottom)runCatching {
            XposedHelpers.callMethod(view,"setRadius",old.stockTop,old.stockBottom,false)
        }
    }

    private fun updateNotificationCorners(view:View,row:View,corner:Int,height:Int){
        val previous=notificationCorners[view]
        fun overlap(name:String)=(NativeIconAccess.field(view,name) as? Int ?: 0)>0
        if(corner<0 || NativeIconAccess.call(row,"isSummaryWithChildren")==true ||
            NativeIconAccess.call(row,"isChildInGroup")==true || row.scaleX<.99f || row.scaleY<.99f ||
            overlap("mTopOverlap") || overlap("mBottomOverlap") ||
            overlap("mClipTopAmount") || overlap("mClipBottomAmount")){
            if(previous!=null){
                notificationCorners.remove(view)
                restoreNotificationCorners(view,previous)
            }
            return
        }
        val current=NativeIconAccess.field(view,"mCornerRadii") as? FloatArray ?: return
        if(current.size!=8)return
        val same=previous!=null && current[0]==previous.top && current[4]==previous.bottom
        val stockTop=if(same)previous!!.stockTop else current[0]
        val stockBottom=if(same)previous!!.stockBottom else current[4]
        val radius=minOf(view.width,height)*corner/100f
        val top=if(stockTop>0f)radius else 0f
        val bottom=if(stockBottom>0f)radius else 0f
        if(current[0]==top && current[4]==bottom)return
        XposedHelpers.callMethod(view,"setRadius",top,bottom,false)
        notificationCorners[view]=NotificationCorners(stockTop,stockBottom,top,bottom)
    }

    private fun beginNotification(p:XC_MethodHook.MethodHookParam){
        if(!visible)return
        val profile=profiles[Surface.NOTIFICATIONS] ?: return
        val view=p.thisObject as View
        if(notificationId!=View.NO_ID && view.id!=notificationId)return
        val row=view.parent as? View ?: return
        if(row.id!=rowId && !row.javaClass.name.endsWith("ExpandableNotificationRow"))return
        val canvas=p.args[0] as Canvas
        if(!renderable(view,canvas))return
        try {
            if(NativeIconAccess.call(row,"isHeadsUp")==true ||
                NativeIconAccess.field(view,"mExpandAnimationRunning")==true)return
            target(view,Surface.NOTIFICATIONS)
            if(LockscreenBackdrop.frame?.images?.containsKey(profile.sourceRadius(Surface.NOTIFICATIONS))!=true)return
            val actual=NativeIconAccess.call(view,"getActualHeight") as? Int ?: view.height
            if(actual<=0)return
            updateNotificationCorners(view,row,profile.corner,actual)
            fun inset(clip:String,overlap:String)=maxOf((NativeIconAccess.field(view,clip) as? Int ?: 0),
                (NativeIconAccess.field(view,overlap) as? Int ?: 0),0)
            val top=inset("mClipTopAmount","mTopOverlap")
            val bottom=actual-inset("mClipBottomAmount","mBottomOverlap")
            if(bottom<=top)return
            val width=(NativeIconAccess.call(view,"getActualWidth") as? Int ?: view.width).coerceIn(1,view.width)
            val left=if(NativeIconAccess.call(view,"isAlignedToRight")==true)view.width-width else 0
            val transition=rowMotion[row]?.progress ?: 0f
            if(transition>=.999f)return
            p.setObjectExtra("cpiLockNotificationLayer",NotificationLayer(left,width,actual,top,bottom,transition))
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","notification") {"Native notification kept: "+t.javaClass.simpleName}
        }
    }

    private data class NotificationLayer(val left:Int,val width:Int,val actual:Int,val top:Int,val bottom:Int,val progress:Float)
    private class CapsuleMotion(var progress:Float,var row:WeakReference<View>?=null)

    private fun updateCapsuleProgress(p:XC_MethodHook.MethodHookParam){
        if(!visible || p.hasThrowable() || Surface.NOTIFICATIONS !in profiles || Surface.CAPSULE !in profiles)return
        val progress=(p.args.firstOrNull() as? Float)?.takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: return
        val item=p.thisObject as View
        try {
            val state=capsuleMotion.getOrPut(item){CapsuleMotion(-1f)}
            val previous=state.row?.get()
            val row=NativeIconAccess.field(item,"bindStackView") as? View
            if(previous!==row){
                if(previous!=null && rowMotion[previous]===state){
                    rowMotion.remove(previous)
                    if(notificationId!=View.NO_ID)previous.findViewById<View>(notificationId)?.invalidate()
                }
                state.row=row?.let(::WeakReference)
            }
            if(state.progress==progress && previous===row)return
            state.progress=progress
            if(row!=null){
                if(progress>0f)rowMotion[row]=state
                else if(rowMotion[row]===state)rowMotion.remove(row)
                if(notificationId!=View.NO_ID)row.findViewById<View>(notificationId)?.invalidate()
            }
            (NativeIconAccess.call(item,"getBgView") as? View)?.invalidate()
            if(!positionRefreshPending && movingTargets.isNotEmpty()){
                positionRefreshPending=true
                Choreographer.getInstance().postFrameCallback {
                    positionRefreshPending=false
                    if(visible)movingTargets.keys.weakSnapshot().forEach {if(it.isShown)it.invalidate()}
                }
            }
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","capsule-motion") {"Native transition kept: "+t.javaClass.simpleName}
        }
    }

    private fun drawHandoff(canvas:Canvas,view:View,bounds:RectF,radius:Float,progress:Float,
        onCapsule:Boolean,path:Path?){
        val notes=profiles[Surface.NOTIFICATIONS] ?: return
        val capsule=profiles[Surface.CAPSULE] ?: return
        val frame=LockscreenBackdrop.frame ?: return
        val target=target(view,if(onCapsule)Surface.CAPSULE else Surface.NOTIFICATIONS)
        val second=target.transitionMaterial ?: LockscreenMaterial().also {target.transitionMaterial=it}
        val mix=progress*progress*(3f-2f*progress)
        // Native row and capsule can both be visible during the morph. Their
        // combined module contribution stays at one plate throughout.
        val visibility=if(onCapsule)mix else 1f-mix
        if(visibility<=0f)return
        val save=canvas.saveLayerAlpha(bounds,(visibility*255).toInt())
        try {
            target.material.draw(canvas,view,Surface.NOTIFICATIONS,notes,frame,bounds=bounds,amount=1f-mix,
                nativeRadius=radius,useNativeMask=false,drawRim=false,exactPath=path,maskOnly=!onCapsule && path==null)
            val add=canvas.saveLayer(bounds,transitionAdd)
            try {
                second.draw(canvas,view,Surface.CAPSULE,capsule,frame,bounds=bounds,amount=mix,nativeRadius=radius,
                    drawRim=false,exactPath=path,maskOnly=!onCapsule && path==null)
            }finally {canvas.restoreToCount(add)}
        }finally {canvas.restoreToCount(save)}
    }

    private val notificationPath=Path()
    private val notificationRadii=FloatArray(8)

    private fun endNotification(p:XC_MethodHook.MethodHookParam){
        val layer=p.getObjectExtra("cpiLockNotificationLayer") as? NotificationLayer ?: return
        if(p.hasThrowable())return
        val canvas=p.args[0] as Canvas
        val clip=canvas.save()
        try {
            val profile=profiles[Surface.NOTIFICATIONS] ?: return
            val view=p.thisObject as View
            val frame=LockscreenBackdrop.frame ?: return
            if(!canvas.clipRect(layer.left,layer.top,layer.left+layer.width,layer.bottom))return
            notificationBounds.set(layer.left.toFloat(),0f,(layer.left+layer.width).toFloat(),layer.actual.toFloat())
            // ColorOS may draw the background through its compositor, outside
            // a Canvas layer. Use the live geometry from the view, rather
            // than assuming the native backdrop supplies alpha to SRC_ATOP.
            val visibleHeight=(layer.bottom-layer.top).toFloat()
            val maxRadius=minOf(layer.width.toFloat(),visibleHeight)*.30f
            val nativeRadii=NativeIconAccess.field(view,"mCornerRadii") as? FloatArray
            val fallback=minOf(layer.width.toFloat(),visibleHeight)*.22f
            for(i in notificationRadii.indices){
                val value=nativeRadii?.getOrNull(i)?.takeIf {it.isFinite()} ?: fallback
                notificationRadii[i]=value.coerceIn(0f,maxRadius)
            }
            val radius=notificationRadii.maxOrNull() ?: fallback
            notificationPath.rewind()
            val base=NativeIconAccess.call(view,"getBaseBackgroundLayer") as? Drawable
            val nativeShape=base?.let {AlertContour.resolve(view,it)}
            val compatible=nativeShape?.nativePath==true &&
                kotlin.math.abs(nativeShape.bounds.left-notificationBounds.left)<2f &&
                kotlin.math.abs(nativeShape.bounds.right-notificationBounds.right)<2f &&
                kotlin.math.abs(nativeShape.bounds.top-notificationBounds.top)<2f &&
                kotlin.math.abs(nativeShape.bounds.bottom-notificationBounds.bottom)<2f
            if(compatible)notificationPath.set(nativeShape!!.path)
            else notificationPath.addRoundRect(notificationBounds,notificationRadii,Path.Direction.CW)
            if(layer.progress>0f && layer.progress<1f && Surface.CAPSULE in profiles){
                drawHandoff(canvas,view,notificationBounds,radius,layer.progress,onCapsule=false,path=notificationPath)
            }else target(view,Surface.NOTIFICATIONS).material.draw(canvas,view,Surface.NOTIFICATIONS,profile,frame,
                bounds=notificationBounds,nativeRadius=radius,useNativeMask=false,drawRim=false,exactPath=notificationPath)
            rendered.add(Surface.NOTIFICATIONS)
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","notification") {"Native notification kept: "+t.javaClass.simpleName}
        }finally {canvas.restoreToCount(clip)}
    }

    private fun drawCapsule(p:XC_MethodHook.MethodHookParam){
        if(!visible || p.hasThrowable() || Surface.CAPSULE !in profiles)return
        val view=p.thisObject as View
        val item=view.parent as? View ?: return
        if(item.javaClass.name!=CAPSULE_ITEM)return
        val canvas=p.args[0] as Canvas
        if(!renderable(view,canvas))return
        try {
            val progress=capsuleMotion[item]?.progress ?: 1f
            val bound=NativeIconAccess.call(item,"isBindToStackView")==true
            if(bound && (progress<=0f || capsuleMotion[item]==null))return
            val container=NativeIconAccess.call(item,"getCapsuleContainer") ?: return
            if(NativeIconAccess.call(container,"getBaseCard")!==item &&
                (capsuleTitleId==View.NO_ID || item.findViewById<View>(capsuleTitleId)==null))return
            target(view,Surface.CAPSULE)
            val provider=NativeIconAccess.call(item,"getPathProvider") ?: return
            val bounds=NativeIconAccess.call(provider,"getClipRect") as? Rect ?: return
            if(bounds.isEmpty)return
            capsuleBounds.set(bounds)
            val radius=NativeIconAccess.call(item,"getActualRadius") as? Float
            val path=NativeIconAccess.call(provider,"getClipPath") as? Path
            val clip=canvas.save()
            try {
                if(path!=null && !path.isEmpty)canvas.clipPath(path)
                if(progress>0f && progress<1f && Surface.NOTIFICATIONS in profiles){
                    val r=radius ?: minOf(bounds.width(),bounds.height())*.5f
                    drawHandoff(canvas,view,capsuleBounds,r,progress,onCapsule=true,path=path)
                }else {
                    val profile=profiles[Surface.CAPSULE] ?: return
                    target(view,Surface.CAPSULE).material.draw(canvas,view,Surface.CAPSULE,profile,LockscreenBackdrop.frame,
                        bounds=capsuleBounds,nativeRadius=radius,exactPath=path)
                }
            }finally {canvas.restoreToCount(clip)}
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","capsule") {"Native capsule kept: "+t.javaClass.simpleName}
        }
    }

    private fun beginGroup(p:XC_MethodHook.MethodHookParam,view:ViewGroup){
        val name=view.javaClass.name
        when {
            name==PLUGIN_SHORTCUTS->{
                if(Surface.SHORTCUTS in profiles && shortcutContainers.add(view.javaClass)){
                    XposedHelpers.findClassIfExists(PLUGIN_SHORTCUT,view.javaClass.classLoader)?.let(::hookShortcut)
                }
            }
            name==INSTANT_CARD || name==WIDGET_CARD->{
                if(Surface.WIDGETS in profiles && widgetPlate(view)===view)beginWidget(p,view)
            }
            widgetPlates.containsKey(view)->beginWidget(p,view)
            pinClass?.isInstance(view)==true->{
                var parent=view.parent
                repeat(7){
                    val group=parent as? View ?: return
                    if(pinRootClass?.isInstance(group)==true){
                        draw(p,view,Surface.PIN)
                        return
                    }
                    parent=group.parent
                }
            }

        }
    }

    private fun beginWidget(p:XC_MethodHook.MethodHookParam,view:View){
        val profile=profiles[Surface.WIDGETS] ?: return
        val canvas=p.args[0] as Canvas
        if(!renderable(view,canvas))return
        val frame=LockscreenBackdrop.frame
        val target=target(view,Surface.WIDGETS)
        if(frame?.images?.containsKey(profile.sourceRadius(Surface.WIDGETS))==true){
            try {
                target.material.draw(canvas,view,Surface.WIDGETS,profile,frame)
                rendered.add(Surface.WIDGETS)
            }catch(t:Throwable){GlassLogger.limited("LockscreenGlass","widgetDraw"){"Native widget kept: "+t.javaClass.simpleName}}
        }
        movingTargets[view]=true
        val tint=profile.widgetTint ?: return
        val tone=frame?.wallpaperTone ?: return
        val paint=target.tintPaint(tone,tint) ?: return
        p.setObjectExtra("cpiLockWidgetLayer",canvas.saveLayer(0f,0f,view.width.toFloat(),view.height.toFloat(),paint))
    }

    private fun endGroup(p:XC_MethodHook.MethodHookParam){
        val saved=p.getObjectExtra("cpiLockWidgetLayer") as? Int ?: return
        (p.args[0] as Canvas).restoreToCount(saved)
    }

    private fun isClockHost(view:ViewGroup):Boolean {
        if(view.id==View.NO_ID)return false
        if(clockHostId!=View.NO_ID)return view.id==clockHostId
        if(!view.javaClass.name.startsWith("com.oplus.keyguard.clock."))return false
        val isHost=runCatching {view.resources.getResourceEntryName(view.id)=="clock_time_view_parent"}.getOrDefault(false)
        if(isHost)clockHostId=view.id
        return isHost
    }

    private fun widgetPlate(root:ViewGroup):View {
        val tolerance=(root.resources.displayMetrics.density*3).toInt()
        val provider=runCatching {
            NativeIconAccess.field(root,"instantCardWidget")?.let {NativeIconAccess.call(it,"getView")} as? View
        }.getOrNull()?.takeIf {it.parent!=null && it.width>=root.width*.70f && it.height>=root.height*.70f}
        var plate:View=provider ?: root
        var deepestBackground:View?=plate.takeIf {it.background!=null}
        for(depth in 0 until 12){
            val wrapper=plate as? ViewGroup ?: break
            var fullSize:View?=null
            for(index in 0 until wrapper.childCount){
                val child=wrapper.getChildAt(index)
                if(child.visibility==View.VISIBLE && child.width>0 && child.height>0 &&
                    kotlin.math.abs(child.width-wrapper.width)<=tolerance &&
                    kotlin.math.abs(child.height-wrapper.height)<=tolerance &&
                    kotlin.math.abs(child.left)<=tolerance && kotlin.math.abs(child.top)<=tolerance &&
                    (child is ViewGroup || child.background!=null)){
                    fullSize=child
                    break
                }
            }
            plate=fullSize ?: break
            if(plate.background!=null)deepestBackground=plate
        }
        plate=deepestBackground ?: plate
        val old=widgetRoots[root]?.get()
        if(old!==plate){
            if(old!=null)widgetPlates.remove(old)
            if(plate!==root){
                if(plate is ViewGroup)hookGroupOverride(plate.javaClass)
                else if(!hookWidgetLeaf(plate.javaClass))plate=root
                if(plate!==root)widgetPlates[plate]=true
            }
            widgetRoots[root]=WeakReference(plate)
            GlassLogger.limited("LockscreenGlass","widget-plate") {"Widget glass layer: "+plate.javaClass.simpleName}
        }
        return plate
    }

    private fun hookWidgetLeaf(type:Class<*>):Boolean {
        val owner=generateSequence(type){it.superclass}.firstOrNull {candidate->
            candidate.declaredMethods.any {it.name=="onDraw" && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))}
        } ?: return false
        if(owner==View::class.java)return false
        return runCatching {
            if(owner !in widgetLeafOwners){
                hook(owner,"onDraw",arrayOf(Canvas::class.java),object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        val view=p.thisObject as View
                        if(visible && view in widgetPlates)beginWidget(p,view)
                    }
                    override fun afterHookedMethod(p:MethodHookParam){endGroup(p)}
                })
                widgetLeafOwners.add(owner)
            }
            true
        }.getOrDefault(false)
    }

    private fun belongsToClock(view:View):Boolean {
        var parent=view.parent as? View
        repeat(10){
            val group=parent ?: return false
            if(group.javaClass.name in clockGroups || group is ViewGroup && isClockHost(group) ||
                group.javaClass.name.startsWith("com.oplus.keyguard.clock.") &&
                group.javaClass.name.endsWith(".ClockViewRoot"))return true
            parent=group.parent as? View
        }
        return false
    }

    private fun hookGroupOverride(type:Class<*>){
        if(!inspectedGroups.add(type))return
        runCatching {
            val owner=generateSequence(type) {it.superclass}.firstOrNull {candidate->
                candidate.declaredMethods.any {it.name=="dispatchDraw" &&
                    it.parameterCount==1 && it.parameterTypes[0]==Canvas::class.java}
            } ?: return
            if(owner==ViewGroup::class.java || owner==View::class.java)return
            if(hookedGroupOwners.add(owner)){
                hook(owner,"dispatchDraw",arrayOf(Canvas::class.java),object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){
                        val view=p.thisObject as ViewGroup
                        if(!visible || view.javaClass !in groupOverrides || !activeGroupOverrides.add(view))return
                        p.setObjectExtra("cpiLockGroupOverride",true)
                        beginGroup(p,view)
                    }
                    override fun afterHookedMethod(p:MethodHookParam){
                        if(p.getObjectExtra("cpiLockGroupOverride")!=true)return
                        try {endGroup(p)}finally {activeGroupOverrides.remove(p.thisObject as ViewGroup)}
                    }
                })
            }
            groupOverrides.add(type)
        }.onFailure {GlassLogger.limited("LockscreenGlass","plugin-draw") {"Plugin draw hook unavailable: "+it.javaClass.simpleName}}
    }

    private fun renderable(view:View,canvas:Canvas)=canvas.isHardwareAccelerated &&
        view.width>0 && view.height>0 && view.isShown && view.alpha>.01f &&
        view.display?.displayId==Display.DEFAULT_DISPLAY

    private fun target(view:View,surface:Surface):Target {
        val target=views.getOrPut(view){Target()}
        if(knownTargets.add(surface))GlassLogger.i("LockscreenGlass","Found "+surface.id+" on "+view.javaClass.simpleName)
        return target
    }

    private fun draw(p:XC_MethodHook.MethodHookParam,view:View,surface:Surface,background:Drawable?=null,
        bounds:RectF?=null,nativeRadius:Float?=null){
        val profile=profiles[surface] ?: return
        val canvas=p.args[0] as? Canvas ?: return
        if(!renderable(view,canvas))return
        try {
            target(view,surface).material.draw(canvas,view,surface,profile,LockscreenBackdrop.frame,background,
                bounds=bounds,nativeRadius=nativeRadius)
        }catch(t:Throwable){GlassLogger.limited("LockscreenGlass","draw-"+surface.id) {"Native rendering kept: "+t.javaClass.simpleName}}
    }

    private var drawingClock=false

    private fun isStockClockDigit(view:View):Boolean {
        if(view.id==View.NO_ID ||
            (view.parent?.parent as? View)?.javaClass?.name!="com.oplus.systemui.shared.clocks.RedHorizontalSingleClockView")return false
        return runCatching {view.resources.getResourceEntryName(view.id) in stockClockDigits}.getOrDefault(false)
    }

    private fun beginClockChild(p:XC_MethodHook.MethodHookParam,parent:ViewGroup,child:View){
        val profile=profiles[Surface.CLOCK] ?: return
        val canvas=p.args[0] as Canvas
        if(!renderable(child,canvas))return
        val target=target(child,Surface.CLOCK)
        if(target.clockParent?.get()!==parent)target.clockParent=WeakReference(parent)
        movingTargets[parent]=true
        val image=LockscreenBackdrop.frame?.images?.get(profile.sourceRadius(Surface.CLOCK)) ?: return
        clockBounds.set(0f,0f,child.width.toFloat(),child.height.toFloat())
        child.matrix.mapRect(clockBounds)
        clockBounds.offset(child.left.toFloat(),child.top.toFloat())
        val padding=parent.resources.displayMetrics.density*8f
        clockBounds.inset(-padding,-padding)
        if(clockBounds.isEmpty)return

        if(android.os.Build.VERSION.SDK_INT>=33){
            runCatching {
                val glyph=target.glyph ?: ClockGlyphGlass().also {target.glyph=it}
                val recording=glyph.record(parent,clockBounds,image,profile)
                p.setObjectExtra("cpiClockCanvas",canvas)
                p.setObjectExtra("cpiClockGlyph",glyph)
                p.args[0]=recording
                drawingClock=true
                return
            }.onFailure {
                GlassLogger.limited("LockscreenGlass","glyph-effect") {
                    "Clock glyph shader unavailable: "+(it.message ?: it.javaClass.simpleName).take(300)
                }
            }
        }
        p.setObjectExtra("cpiClockLayer",canvas.saveLayer(clockBounds,null))
        p.setObjectExtra("cpiClockBounds",RectF(clockBounds))
        drawingClock=true
    }

    private fun endClockChild(p:XC_MethodHook.MethodHookParam){
        val original=p.getObjectExtra("cpiClockCanvas") as? Canvas
        val saved=p.getObjectExtra("cpiClockLayer") as? Int
        if(original==null && saved==null)return
        val child=p.args[1] as View
        try {
            if(original!=null && android.os.Build.VERSION.SDK_INT>=33){
                val glyph=p.getObjectExtra("cpiClockGlyph") as ClockGlyphGlass
                glyph.finish(original,!p.hasThrowable())
            }else if(saved!=null && !p.hasThrowable()){
                val parent=p.thisObject as ViewGroup
                val bounds=p.getObjectExtra("cpiClockBounds") as RectF
                val profile=profiles[Surface.CLOCK] ?: return
                views[child]?.material?.draw(p.args[0] as Canvas,parent,Surface.CLOCK,profile,
                    LockscreenBackdrop.frame,glyphs=true,bounds=bounds)
            }
            if(!p.hasThrowable() && rendered.add(Surface.CLOCK)){
                GlassLogger.i("LockscreenGlass","Clock glass composited on "+child.javaClass.simpleName)
            }
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","clock-composite") {"Clock render failed: "+t.javaClass.simpleName}
        }finally {
            if(original!=null)p.args[0]=original
            if(saved!=null)(p.args[0] as Canvas).restoreToCount(saved)
            drawingClock=false
        }
    }
}
