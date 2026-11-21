package com.cosmod.launcherglass.xposed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.graphics.RenderEffect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Choreographer
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cosmod.launcherglass.ui.EdgeSettings
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
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** System UI render hooks. Authentication and notification contents stay in their native views. */
internal object LockscreenGlass {
    private const val KEYGUARD=1
    private const val STATUS="com.android.systemui.statusbar.StatusBarStateControllerImpl"
    private const val WALLPAPER="com.oplus.systemui.keyguard.wallpaper.OplusLockScreenWallpaperController"
    private const val WALLPAPER_ANIM="com.oplus.systemui.keyguard.anim.OplusWallpaperAnimControllerImpl"
    private const val SCRIM="com.oplus.systemui.statusbar.phone.ScrimBlurStateUtilsExImpl"
    private const val NOTIFICATION="com.android.systemui.statusbar.notification.row.NotificationBackgroundView"
    private const val NOTIFICATION_ROW="com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
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
    private const val EMPTY_CARD="com.oplus.keyguard.style.widgets.container.view.EmptyCardView"
    private const val CAPSULE_BG="com.oplus.systemui.notification.lockscreen.capsule.CapsuleBackgroundView"
    private const val CAPSULE_ITEM="com.oplus.systemui.notification.lockscreen.capsule.CapsuleItemView"
    private const val CLOCK_PLUGIN="com.oplus.keyguard.personality.clocks"
    private const val BASE_CLOCK="com.oplus.keyguard.clock.base.ui.view.ClockTimeView"
    private const val GALLERY_CLOCK="com.oplus.keyguard.clock.gallery.view.BaseSingleClockView"
    private const val TEXT_CLOCK_TIME="com.oplus.keyguard.clock.text.widget.TextTimeTextView"
    private const val CLOCK_EFFECT_HELPER="com.oplus.keyguard.clock.common.view.livecontent.effect.shader.glass.GlassEffectHelper"
    private const val CARD_CSS_BACKGROUND="org.hapjs.component.view.drawable.CSSBackgroundDrawable"
    private val clockGroups=setOf(BASE_CLOCK,"com.oplus.keyguard.clock.digital.ui.view.ClockTimeView")
    private val clockGlyphViews=setOf(
        "com.oplus.keyguard.clock.base.widget.CustomizedTextView",
        "com.oplus.keyguard.clock.common.widget.CustomizedTextView",
        "com.oplus.keyguard.clock.digital.widget.MyCustomizedTextView",
        "com.oplus.keyguard.clock.gallery.widget.MyCustomizedTextView",
        "com.oplus.keyguard.clock.text.widget.MyCustomizedTextView")
    private val stockClockDigits=setOf("tv_hour","tv_minute","tv_colon")
    private val clockBounds=RectF()
    private val clockGlyphOwners=HashSet<Pair<Class<*>,String>>()
    private val clockGlyphTypes=HashMap<Class<*>,Boolean>()
    private val clockGlyphs=WeakHashMap<View,Boolean>()
    private val clockEffectLoaders=HashSet<ClassLoader>()
    private val nativeClockEffects=WeakHashMap<View,RenderEffect>()
    private val compositedClocks=WeakHashMap<View,RenderEffect>()
    private val clockPosition=IntArray(2)
    private val mountPosition=IntArray(2)
    private var clockRefreshPending=false
    private var clockAnimator:ValueAnimator?=null
    private var clockAmount=0f
    private var clockDestination=0f
    private var surfaceAnimator:ValueAnimator?=null
    private var surfaceAmount=0f
    private var surfaceEntrances=0
    private var clockHandoff:Any?=null
    private var clockHandoffs=0
    private val releasedClockMounts=WeakHashMap<View,Boolean>()
    private val finishClockHandoff=Runnable {endClockHandoff()}
    private var clockContour=ClockDarkContour()
    private val clockTransitionCurve=PathInterpolator(.28f,0f,.36f,1f)
    private var clockEntrances=0
    private var clockExits=0
    private val clockLayoutChanged=View.OnLayoutChangeListener { _,_,_,_,_,_,_,_,_->scheduleClockRefresh() }
    private val widgetCardTypes=HashMap<Class<*>,Boolean>()
    private val clockText=setOf("com.android.systemui.shared.clocks.AnimatableClockView","com.android.keyguard.AnimatableClockView")
    private val main=Handler(Looper.getMainLooper())
    private class Target(val surface:Surface) {
        val material=LockscreenMaterial()
        var transitionMaterial:LockscreenMaterial?=null
        var pinKeys:Array<LockscreenMaterial?>?=null
        var glyph:ClockGlyphGlass?=null
        val widgetBackgrounds=ArrayList<WeakReference<View>>()
        val nextWidgetBackgrounds=ArrayList<View>()
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
    private var keyguardOccluded=false
    private var dozing=false
    private var profiles:Map<Surface,Profile> = emptyMap()
    private var clearBouncer=false
    private var visible=false
    private var softActivities=false
    fun activityWallpaper() = if(visible && softActivities) LockscreenBackdrop.frame?.images?.get(0) else null
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
    private val widgetBackgrounds=WeakHashMap<View,WeakReference<ViewGroup>>()
    private val nativeWidgetEffects=WeakHashMap<View,RenderEffect>()
    private val suppressedWidgetEffects=WeakHashMap<View,Boolean>()
    private var widgetEffectSetter:Method?=null
    private var restoringWidgetEffect=false
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
                    onMain {
                        keyguardOccluded=occluded
                        keyguardShowing=showing && !occluded
                        refreshVisibility()
                    }
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
                    Intent.ACTION_WALLPAPER_CHANGED->{
                        if(visible)prepareWallpaper(refresh=true)
                        else if(clockAnimator==null)LockscreenBackdrop.stop()
                        return
                    }
                }
                refreshVisibility()
            }
        },filter,ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun configure(prefs:Bundle,enabled:Boolean){
        val clockSurface=Surface.CLOCK.edgeSurface
        fun contourKey(field:String)=EdgeSettings.key(clockSurface,field)
        val contour=ClockDarkContour(
            opacity=if(prefs.getBoolean(contourKey("dark_enabled")))
                prefs.getInt(contourKey("dark_opacity"),EdgeSettings.DEFAULT_DARK_OPACITY).coerceIn(0,100) else 0,
            widthQuarters=prefs.getInt(contourKey("dark_width_qdp"),EdgeSettings.DEFAULT_DARK_QUARTERS)
                .coerceIn(1,EdgeSettings.MAX_DARK_QUARTERS),
            softness=prefs.getInt(contourKey("dark_softness")).coerceIn(0,100),
            lightOverlaps=prefs.getBoolean(contourKey(EdgeSettings.DARK_OVERLAP),true)
        )
        val contourChanged=clockContour!=contour
        clockContour=contour
        val tune=enabled && prefs.getBoolean(LockscreenSettings.UNLOCK_ENABLED)
        unlockSpeed=if(tune)prefs.getInt(LockscreenSettings.UNLOCK_SPEED,100)
            .coerceIn(LockscreenSettings.MIN_UNLOCK_SPEED,LockscreenSettings.MAX_UNLOCK_SPEED) else 100
        unlockSmoothness=if(tune)prefs.getInt(LockscreenSettings.UNLOCK_SMOOTHNESS,0).coerceIn(0,100) else 0
        unlockImpulse=if(tune)prefs.getInt(LockscreenSettings.UNLOCK_IMPULSE,0).coerceIn(0,100) else 0
        if(tune)runCatching {installUnlockTuning()}.onFailure {
            GlassLogger.limited("LockscreenGlass","unlockTuning"){"Native unlock tuning unavailable: "+it}
        }
        val softNotifications=enabled && SoftGlassNative.replaces(com.cosmod.launcherglass.ui.SoftGlassSettings.Surface.LOCK)
        val activityChange=softActivities!=softNotifications
        softActivities=softNotifications
        if(android.os.Build.VERSION.SDK_INT>=33 && activityChange && !softActivities)SoftGlassActivityMaterial.clear()
        val next=if(enabled)Surface.entries.filterNot {it==Surface.NOTIFICATIONS && softNotifications}
            .associateWith {LockscreenSettings.read(prefs,it)}.filterValues {it.enabled} else emptyMap()
        val clear=enabled && prefs.getBoolean(LockscreenSettings.CLEAR_BOUNCER_BLUR,false)
        if(next==profiles && clear==clearBouncer && !activityChange){
            refreshVisibility()
            if(contourChanged)refreshClockMaterial()
            return
        }
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
            clockGlyphOwners.clear()
            clockGlyphTypes.clear()
            clearClockGlyphs()
            clockEffectLoaders.clear()
            restoreNativeClocks()
            nativeClockEffects.clear()
            clearWidgetBackgrounds()
            nativeWidgetEffects.clear()
            widgetEffectSetter=null
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
                clockGlyphOwners.clear()
                clockGlyphTypes.clear()
                clearClockGlyphs()
                clockEffectLoaders.clear()
                restoreNativeClocks()
                nativeClockEffects.clear()
                clearWidgetBackgrounds()
                nativeWidgetEffects.clear()
                widgetEffectSetter=null
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
        val show=(profiles.isNotEmpty() || softActivities) && interactive && !dozing && locked
        if(show==visible){
            if((!interactive || dozing || keyguardOccluded || Surface.CLOCK !in profiles) &&
                (clockAmount>0f || clockAnimator!=null)){
                finishClockTransition()
            }
            if(!show && (!locked || profiles.isEmpty() && !softActivities) && clockAnimator==null && clockHandoff==null)LockscreenBackdrop.stop()
            return
        }
        visible=show
        wallpaperRetry=false
        if(show){
            prepareWallpaper()
            showClockWhenReady()
            showSurfacesWhenReady()
        }else{
            val fade=surfaceAnimator
            surfaceAnimator=null
            fade?.cancel()
            surfaceAmount=0f
            if(interactive && !dozing && !keyguardOccluded && !locked &&
                Surface.CLOCK in profiles && clockAmount>0f && ValueAnimator.areAnimatorsEnabled()){
                clockExits++
                if(clockHandoff==null)animateClock(0f,(32000L/unlockSpeed).coerceIn(220L,460L))
            }else finishClockTransition()
            restoreNotificationCorners()
            capsuleMotion.clear()
            rowMotion.clear()
            movingTargets.clear()
            clearWidgetBackgrounds()
            if(android.os.Build.VERSION.SDK_INT>=33)SoftGlassActivityMaterial.clear()
        }
        repaint()
    }

    private fun showSurfacesWhenReady(){
        if(!visible || surfaceAmount>=1f || surfaceAnimator!=null)return
        val images=LockscreenBackdrop.frame?.images ?: return
        if(profiles.none {(surface,profile)->surface!=Surface.CLOCK && profile.sourceRadius(surface) in images})return
        if(!ValueAnimator.areAnimatorsEnabled()){
            surfaceAmount=1f
            repaint()
            return
        }
        surfaceEntrances++
        surfaceAnimator=ValueAnimator.ofFloat(surfaceAmount,1f).apply {
            duration=440L
            interpolator=clockTransitionCurve
            addUpdateListener {
                surfaceAmount=it.animatedValue as Float
                for((view,target) in views)if(target.surface!=Surface.CLOCK && view.isShown)view.invalidate()
                for(plate in widgetBackgrounds.keys)if(plate.isShown)plate.invalidate()
            }
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:Animator){
                    if(surfaceAnimator!==animation)return
                    surfaceAnimator=null
                    refreshWidgetEffects()
                }
            })
        }
        surfaceAnimator?.start()
    }

    private fun showClockWhenReady(){
        val profile=profiles[Surface.CLOCK] ?: return
        if(!visible || clockHandoff!=null || LockscreenBackdrop.frame?.images?.containsKey(profile.sourceRadius(Surface.CLOCK))!=true)return
        // A relock can interrupt the clock's exit. Reverse from the current material.
        if(clockAnimator!=null && clockDestination==1f || clockAnimator==null && clockAmount>=1f)return
        if(ValueAnimator.areAnimatorsEnabled()){
            clockEntrances++
            animateClock(1f,440L)
        }else{
            clockAmount=1f
            refreshClockMaterial()
        }
    }

    private fun animateClock(destination:Float,duration:Long){
        val previous=clockAnimator
        clockAnimator=null
        previous?.cancel()
        clockDestination=destination
        clockAnimator=ValueAnimator.ofFloat(clockAmount,destination).apply {
            this.duration=duration
            interpolator=clockTransitionCurve
            addUpdateListener {
                clockAmount=it.animatedValue as Float
                refreshClockMaterial()
            }
            addListener(object:AnimatorListenerAdapter(){
                override fun onAnimationEnd(animation:Animator){
                    if(clockAnimator!==animation)return
                    clockAnimator=null
                    if(destination==0f)finishClockTransition()
                }
            })
        }
        clockAnimator?.start()
    }

    private fun finishClockTransition(){
        main.removeCallbacks(finishClockHandoff)
        clockHandoff=null
        val previous=clockAnimator
        clockAnimator=null
        previous?.cancel()
        clockAmount=0f
        restoreNativeClocks()
        releasedClockMounts.keys.weakSnapshot().forEach(nativeClockEffects::remove)
        releasedClockMounts.clear()
        clockGlyphs.keys.weakSnapshot().forEach {if(it.isAttachedToWindow)it.invalidate()}
        if(!visible){
            // Keep a bounded static backdrop ready for the next wake.
            // Live wallpaper capture and blur jobs stop in AOD.
            val sleeping=(!interactive || dozing) && !keyguardOccluded &&
                (keyguardShowing ?: (keyguard?.isKeyguardLocked==true))
            val clockRadius=profiles[Surface.CLOCK]?.sourceRadius(Surface.CLOCK)
            if(!LockscreenBackdrop.stop(keepStatic=sleeping,clockRadius=clockRadius)){
                views.values.forEach {it.discard()}
            }
        }
    }

    private fun prepareWallpaper(refresh:Boolean=false){
        val context=context ?: return
        val radii=profiles.entries.mapTo(linkedSetOf()) {(surface,profile)->profile.sourceRadius(surface)}
        if(softActivities)radii.add(0)
        LockscreenBackdrop.prepare(context,controller.get(),wallpaperAnimation.get(),radii,refresh)
    }

    fun wallpaperChanged(){
        val frame=LockscreenBackdrop.frame
        wallpaperSource=frame?.source ?: "unavailable"
        GlassLogger.limited("LockscreenGlass","wallpaper-"+wallpaperSource) {"Wallpaper source: "+wallpaperSource}
        if(visible && frame?.images.isNullOrEmpty() && !wallpaperRetry){
            wallpaperRetry=true
            main.postDelayed({if(visible && LockscreenBackdrop.frame?.images.isNullOrEmpty())prepareWallpaper(refresh=true)},750L)
        }
        showClockWhenReady()
        showSurfacesWhenReady()
        if(softActivities)LiveAlertGlass.lockWallpaperChanged()
        repaint()
    }
    private fun repaint(){
        if(Surface.WIDGETS !in profiles)clearWidgetBackgrounds()
        else if(visible)refreshWidgetEffects()
        refreshClockMaterial()
        views.keys.weakSnapshot().forEach { view->
            if(view.isAttachedToWindow)view.invalidate()
        }
    }
    fun diagnostics()="lockscreen profiles="+profiles.keys+", visible="+visible+", targets="+knownTargets+", rendered="+rendered+
        ", wallpaper="+wallpaperSource+", clock mounts="+compositedClocks.size+", widget plates="+widgetBackgrounds.size+
        ", widget compositor="+suppressedWidgetEffects.size+", clock amount="+clockAmount+
        ", clock wake="+clockEntrances+", clock exit="+clockExits+", clock handoff="+clockHandoffs+
        ", surface amount="+surfaceAmount+", surface wake="+surfaceEntrances+", wake cache hits="+LockscreenBackdrop.cacheHits

    private fun installDrawing(){
        val loader=loader ?: return
        installWidgetEffectHook(loader)
        hook(ViewGroup::class.java,"dispatchDraw",arrayOf(Canvas::class.java),object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(!visible || Looper.myLooper()!==Looper.getMainLooper())return
                val view=p.thisObject as ViewGroup
                if(view.javaClass !in groupOverrides)beginGroup(p,view)
            }
            override fun afterHookedMethod(p:MethodHookParam){endGroup(p)}
        })
        hook(View::class.java,"onAttachedToWindow",emptyArray(),object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(p.hasThrowable() || Looper.myLooper()!==Looper.getMainLooper())return
                val view=p.thisObject as View
                if(Surface.CLOCK in profiles)discoverClockGlyph(view)
                if(Surface.WIDGETS in profiles && view is ViewGroup && isWidgetCard(view))hookGroupOverride(view.javaClass)
            }
        })
        hook(ViewGroup::class.java,"drawChild",
            arrayOf(Canvas::class.java,View::class.java,Long::class.javaPrimitiveType!!),object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(!visible || Looper.myLooper()!==Looper.getMainLooper())return
                    val child=p.args[1] as View
                    if(Surface.CLOCK in profiles)discoverClockGlyph(child)
                    if(Surface.WIDGETS in profiles && child is ViewGroup && isWidgetCard(child))hookGroupOverride(child.javaClass)
                }
            })
        hook(View::class.java,"drawBackground",arrayOf(Canvas::class.java),object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(!visible || surfaceAmount<=0f || Looper.myLooper()!==Looper.getMainLooper())return
                val view=p.thisObject as View
                val card=widgetBackgrounds[view]?.get() ?: return
                val profile=profiles[Surface.WIDGETS] ?: return
                if(LockscreenBackdrop.frame?.images?.containsKey(profile.sourceRadius(Surface.WIDGETS))!=true)return
                val canvas=p.args[0] as Canvas
                if(!renderable(card,canvas) || !isWidgetBackground(view,card))return
                if(surfaceAmount>=1f)p.result=null
                else p.setObjectExtra("cpiLockWidgetBackground",canvas.saveLayerAlpha(
                    view.scrollX.toFloat(),view.scrollY.toFloat(),(view.width+view.scrollX).toFloat(),
                    (view.height+view.scrollY).toFloat(),((1f-surfaceAmount)*255).toInt()))
            }
            override fun afterHookedMethod(p:MethodHookParam){
                val saved=p.getObjectExtra("cpiLockWidgetBackground") as? Int ?: return
                (p.args[0] as Canvas).restoreToCount(saved)
            }
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
            runCatching {
                val centerX=type.getDeclaredMethod("getCenterXForColumn",int).apply {isAccessible=true}
                val centerY=type.getDeclaredMethod("getCenterYForRow",int).apply {isAccessible=true}
                check(centerX.returnType==Float::class.javaPrimitiveType && centerY.returnType==Float::class.javaPrimitiveType)
                hook(type,"drawCell",arrayOf(Canvas::class.java,int,int),object:XC_MethodHook(){
                    override fun beforeHookedMethod(p:MethodHookParam){drawNumericKey(p,centerX,centerY)}
                })
            }.onFailure {
                GlassLogger.limited("LockscreenGlass","pin-geometry") {"Numeric keyboard geometry unavailable: "+it.javaClass.simpleName}
            }
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

    private fun drawNumericKey(p:XC_MethodHook.MethodHookParam,centerX:Method,centerY:Method){
        if(!visible || surfaceAmount<=0f)return
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
            val x=(centerX.invoke(view,column) as Float)+offset("cellNumberTranslateX")
            val y=(centerY.invoke(view,row) as Float)+offset("cellNumberTranslateY")
            pinBounds.set(x-radius,y-radius,x+radius,y+radius)
            val keys=target.pinKeys ?: arrayOfNulls<LockscreenMaterial>(12).also {target.pinKeys=it}
            val index=row*3+column
            val material=keys[index] ?: LockscreenMaterial().also {keys[index]=it}
            material.draw(canvas,view,Surface.PIN,profile,frame,bounds=pinBounds,amount=opacity*surfaceAmount)
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
        // A zero-radius compositor update is ignored by ColorOS.
        val radius=(minOf(view.width,height)*corner/100f).coerceAtLeast(.01f)
        val top=if(stockTop>0f)radius else 0f
        val bottom=if(stockBottom>0f)radius else 0f
        if(current[0]==top && current[4]==bottom)return
        XposedHelpers.callMethod(view,"setRadius",top,bottom,false)
        notificationCorners[view]=NotificationCorners(stockTop,stockBottom,top,bottom)
    }

    private fun beginNotification(p:XC_MethodHook.MethodHookParam){
        if(!visible || surfaceAmount<=0f)return
        val profile=profiles[Surface.NOTIFICATIONS] ?: return
        val view=p.thisObject as View
        if(notificationId!=View.NO_ID && view.id!=notificationId)return
        val row=view.parent as? View ?: return
        if((rowId==View.NO_ID || row.id!=rowId) &&
            generateSequence(row.javaClass as Class<*>?) {it.superclass}.none {it.name==NOTIFICATION_ROW})return
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
            val bottomInset=inset("mClipBottomAmount","mBottomOverlap")
            val bottom=actual-bottomInset
            if(bottom<=top)return
            val width=(NativeIconAccess.call(view,"getActualWidth") as? Int ?: view.width).coerceIn(1,view.width)
            val left=if(NativeIconAccess.call(view,"isAlignedToRight")==true)view.width-width else 0
            val transition=rowMotion[row]?.progress ?: 0f
            if(transition>=.999f)return
            val base=if(NativeIconAccess.field(view,"mBottomIsRounded")==true &&
                NativeIconAccess.field(view,"mBottomAmountClips")==true)bottom else actual
            p.setObjectExtra("cpiLockNotificationLayer",NotificationLayer(left,width,base,top,bottom,transition))
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","notification") {"Native notification kept: "+t.javaClass.simpleName}
        }
    }

    private data class NotificationLayer(val left:Int,val width:Int,val height:Int,val top:Int,val bottom:Int,val progress:Float)
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
        val visibility=(if(onCapsule)mix else 1f-mix)*surfaceAmount
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
    private val nativeNotificationRect=RectF()

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
            notificationBounds.set(layer.left.toFloat(),0f,(layer.left+layer.width).toFloat(),layer.height.toFloat())
            val ext=NativeIconAccess.field(view,"mExt")
            val base=NativeIconAccess.call(view,"getBaseBackgroundLayer") as? Drawable
            val stackedMask=ext?.let {NativeIconAccess.call(it,"getStackedNotificationMaskColor") as? Int} ?: 0
            val native=ext?.takeIf {stackedMask!=0}?.let {NativeIconAccess.call(it,"getOplusColorBg") as? Drawable}
                ?.takeUnless {it.bounds.isEmpty} ?: base
            native?.bounds?.let {bounds->
                nativeNotificationRect.set(bounds)
                if(!nativeNotificationRect.isEmpty && nativeNotificationRect.width()>=layer.width*.5f &&
                    nativeNotificationRect.intersect(layer.left.toFloat(),0f,
                        (layer.left+layer.width).toFloat(),layer.bottom.toFloat())){
                    // Preserve the actual bounds, including the unshown portion
                    // above a card clipped by the rest of the stack.
                    notificationBounds.set(bounds)
                }
            }
            val diameter=minOf(notificationBounds.width(),notificationBounds.height())
            val maxRadius=diameter*.5f
            val nativeRadii=NativeIconAccess.field(view,"mCornerRadii") as? FloatArray
            val fallback=diameter*.22f
            for(i in notificationRadii.indices){
                val value=nativeRadii?.getOrNull(i)?.takeIf {it.isFinite()} ?: fallback
                notificationRadii[i]=value.coerceIn(0f,maxRadius)
            }
            val radius=notificationRadii.maxOrNull() ?: fallback
            notificationPath.rewind()
            val nativeShape=native?.let {AlertContour.resolve(view,it)}
            val compatible=nativeShape?.nativePath==true &&
                kotlin.math.abs(nativeShape.bounds.left-notificationBounds.left)<2f &&
                kotlin.math.abs(nativeShape.bounds.right-notificationBounds.right)<2f &&
                kotlin.math.abs(nativeShape.bounds.top-notificationBounds.top)<2f &&
                kotlin.math.abs(nativeShape.bounds.bottom-notificationBounds.bottom)<2f
            if(compatible)notificationPath.set(nativeShape.path)
            else notificationPath.addRoundRect(notificationBounds,notificationRadii,Path.Direction.CW)
            // The OEM contour is already rounded. Use it as the single alpha
            // boundary instead of multiplying it by another rounded mask.
            if(layer.progress>0f && layer.progress<1f && Surface.CAPSULE in profiles){
                drawHandoff(canvas,view,notificationBounds,radius,layer.progress,onCapsule=false,path=notificationPath)
            }else target(view,Surface.NOTIFICATIONS).material.draw(canvas,view,Surface.NOTIFICATIONS,profile,frame,
                bounds=notificationBounds,nativeRadius=radius,useNativeMask=false,drawRim=false,
                exactPath=notificationPath,amount=surfaceAmount)
            rendered.add(Surface.NOTIFICATIONS)
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","notification") {"Native notification kept: "+t.javaClass.simpleName}
        }finally {canvas.restoreToCount(clip)}
    }

    private fun drawCapsule(p:XC_MethodHook.MethodHookParam){
        if(!visible || surfaceAmount<=0f || p.hasThrowable() || Surface.CAPSULE !in profiles)return
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
                        bounds=capsuleBounds,nativeRadius=radius,exactPath=path,amount=surfaceAmount)
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
            Surface.WIDGETS in profiles && isWidgetCard(view)->beginWidget(p,view)
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

    private fun beginWidget(p:XC_MethodHook.MethodHookParam,view:ViewGroup){
        val profile=profiles[Surface.WIDGETS] ?: return
        val canvas=p.args[0] as Canvas
        if(!renderable(view,canvas))return
        val frame=LockscreenBackdrop.frame
        val target=target(view,Surface.WIDGETS)
        if(frame?.images?.containsKey(profile.sourceRadius(Surface.WIDGETS))==true){
            try {
                updateWidgetBackground(view,target)
                if(surfaceAmount>0f){
                    target.material.draw(canvas,view,Surface.WIDGETS,profile,frame,amount=surfaceAmount)
                    rendered.add(Surface.WIDGETS)
                }
            }catch(t:Throwable){GlassLogger.limited("LockscreenGlass","widgetDraw"){"Native widget kept: "+t.javaClass.simpleName}}
        }
        movingTargets[view]=true
        val tint=profile.widgetTint ?: return
        val tone=frame?.wallpaperTone ?: return
        val transparency=100-((100-tint.transparency)*surfaceAmount).roundToInt()
        val paint=target.tintPaint(tone,if(transparency==tint.transparency)tint else tint.copy(transparency=transparency)) ?: return
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

    private fun isWidgetCard(view:View)=widgetCardTypes.getOrPut(view.javaClass){
        view.javaClass.name.startsWith("com.oplus.keyguard.style.widgets.") &&
            generateSequence(view.javaClass as Class<*>?) {it.superclass}
                .takeWhile {it.name!=EMPTY_CARD}.any {it.name==WIDGET_CARD}
    }

    private fun isWidgetBackground(view:View,card:ViewGroup):Boolean {
        val background=view.background ?: return false
        if(view !is ViewGroup)return false
        val className=background.javaClass.name
        if(background !is ColorDrawable && background !is GradientDrawable &&
            className!=CARD_CSS_BACKGROUND && !className.startsWith("com.oplus.posteffect.") &&
            className!="com.android.internal.graphics.drawable.BackgroundBlurDrawable")return false
        var child:View=view
        while(child!==card){
            if(child.visibility!=View.VISIBLE || !fillsWidget(child,card))return false
            child=child.parent as? View ?: return false
        }
        return true
    }

    private fun fillsWidget(view:View,card:ViewGroup):Boolean {
        val tolerance=card.resources.displayMetrics.density*2
        return view.width>0 && view.height>0 &&
            kotlin.math.abs(view.width-card.width)<=tolerance &&
            kotlin.math.abs(view.height-card.height)<=tolerance &&
            kotlin.math.abs(view.left)<=tolerance && kotlin.math.abs(view.top)<=tolerance
    }

    private fun updateWidgetBackground(card:ViewGroup,target:Target){
        val next=target.nextWidgetBackgrounds
        next.clear()
        collectWidgetBackgrounds(card,card,next,0)
        val previous=target.widgetBackgrounds
        if(previous.size==next.size && previous.indices.all {previous[it].get()===next[it]}){
            next.clear()
            return
        }
        for(reference in previous){
            val old=reference.get() ?: continue
            if(old !in next && widgetBackgrounds[old]?.get()===card){
                widgetBackgrounds.remove(old)
                if(old!==card)old.invalidate()
            }
        }
        previous.clear()
        for(plate in next){
            previous.add(WeakReference(plate))
            if(widgetBackgrounds[plate]?.get()!==card){
                widgetBackgrounds[plate]=WeakReference(card)
                plate.postInvalidateOnAnimation()
                val background=plate.background?.javaClass?.name
                GlassLogger.limited("LockscreenGlass","widget-bg-"+background){
                    "Replacing widget background "+background
                }
            }
        }
        next.clear()
        refreshWidgetEffects()
    }

    private fun collectWidgetBackgrounds(
        wrapper:ViewGroup,card:ViewGroup,result:MutableList<View>,depth:Int
    ){
        if(isWidgetBackground(wrapper,card))result.add(wrapper)
        if(depth>=12)return
        for(index in 0 until wrapper.childCount){
            val child=wrapper.getChildAt(index) as? ViewGroup ?: continue
            if(child.visibility==View.VISIBLE && fillsWidget(child,card)){
                collectWidgetBackgrounds(child,card,result,depth+1)
            }
        }
    }

    private fun clearWidgetBackgrounds(){
        val backgrounds=widgetBackgrounds.keys.weakSnapshot()
        widgetBackgrounds.clear()
        views.values.forEach {it.widgetBackgrounds.clear()}
        backgrounds.forEach {it.invalidate()}
        suppressedWidgetEffects.keys.weakSnapshot().forEach { setWidgetEffectSuppressed(it,false) }
    }

    private fun installWidgetEffectHook(loader:ClassLoader){
        runCatching {
            val type=XposedHelpers.findClassIfExists("com.oplus.view.OplusViewBackgroundRenderEffect",loader) ?: return
            val method=type.getDeclaredMethod("setBackgroundRenderEffect",RenderEffect::class.java,View::class.java)
            hooks+=XposedBridge.hookMethod(method,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(restoringWidgetEffect || Looper.myLooper()!==Looper.getMainLooper())return
                    val view=p.args[1] as? View ?: return
                    val card=findWidgetCard(view) ?: return
                    val effect=p.args[0] as? RenderEffect
                    if(effect==null){
                        nativeWidgetEffects.remove(view)
                        suppressedWidgetEffects.remove(view)
                    }else{
                        nativeWidgetEffects[view]=effect
                        if(shouldReplaceWidgetEffect(view,card)){
                            p.args[0]=null
                            suppressedWidgetEffects[view]=true
                            logWidgetEffect(view)
                        }
                    }
                }
            })
            widgetEffectSetter=method
        }.onFailure {
            GlassLogger.limited("LockscreenGlass","widget-effect-hook") {"Native widget effect kept: "+it.javaClass.simpleName}
        }
    }

    private fun findWidgetCard(view:View):ViewGroup? {
        var parent:View?=view
        repeat(12){
            val node=parent ?: return null
            if(node is ViewGroup && isWidgetCard(node))return node
            parent=node.parent as? View
        }
        return null
    }

    private fun shouldReplaceWidgetEffect(view:View,card:ViewGroup):Boolean {
        if(!visible || surfaceAmount<1f || !card.isShown)return false
        val profile=profiles[Surface.WIDGETS] ?: return false
        if(LockscreenBackdrop.frame?.images?.containsKey(profile.sourceRadius(Surface.WIDGETS))!=true)return false
        var child=view
        while(child!==card){
            if(!fillsWidget(child,card))return false
            child=child.parent as? View ?: return false
        }
        return true
    }

    private fun refreshWidgetEffects(){
        for(view in nativeWidgetEffects.keys.weakSnapshot()){
            val card=findWidgetCard(view)
            val suppress=card!=null && shouldReplaceWidgetEffect(view,card)
            if(suppress!=(view in suppressedWidgetEffects))setWidgetEffectSuppressed(view,suppress)
        }
    }

    private fun setWidgetEffectSuppressed(view:View,suppress:Boolean){
        val setter=widgetEffectSetter ?: return
        val native=nativeWidgetEffects[view] ?: return
        restoringWidgetEffect=true
        try {
            setter.invoke(null,if(suppress)null else native,view)
            if(suppress){
                suppressedWidgetEffects[view]=true
                logWidgetEffect(view)
            }else suppressedWidgetEffects.remove(view)
        }catch(t:Throwable){
            GlassLogger.limited("LockscreenGlass","widget-effect-update") {"Native widget effect kept: "+t.javaClass.simpleName}
        }finally {restoringWidgetEffect=false}
    }

    private fun logWidgetEffect(view:View){
        GlassLogger.limited("LockscreenGlass","widget-native-effect") {"Replacing widget compositor material on "+view.javaClass.simpleName}
    }

    private fun belongsToClock(view:View):Boolean {
        if(view.javaClass.name==TEXT_CLOCK_TIME)return true
        var parent=view.parent as? View
        repeat(10){
            val group=parent ?: return false
            if(group.javaClass.name in clockGroups || group is ViewGroup && isClockHost(group))return true
            if(group.javaClass.name.startsWith("com.oplus.keyguard.clock.gallery.view.") &&
                generateSequence(group.javaClass as Class<*>?) {it.superclass}.any {it.name==GALLERY_CLOCK}){
                // Gallery clocks use the same text class for time, date and
                // the week label. Resolve the actual time view by identity.
                return runCatching {
                    NativeIconAccess.call(group,"getRealTimeView")===view ||
                        NativeIconAccess.call(group,"getTimeTextView")===view ||
                        NativeIconAccess.call(group,"getTimeTextViewExt")===view
                }.getOrDefault(false)
            }
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
                        if(!visible || Looper.myLooper()!==Looper.getMainLooper() ||
                            view.javaClass !in groupOverrides || !activeGroupOverrides.add(view))return
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
        val target=views.getOrPut(view){Target(surface)}
        if(knownTargets.add(surface))GlassLogger.i("LockscreenGlass","Found "+surface.id+" on "+view.javaClass.simpleName)
        return target
    }

    private fun draw(p:XC_MethodHook.MethodHookParam,view:View,surface:Surface,background:Drawable?=null,
        bounds:RectF?=null,nativeRadius:Float?=null){
        val profile=profiles[surface] ?: return
        val canvas=p.args[0] as? Canvas ?: return
        if(!renderable(view,canvas))return
        val target=target(view,surface)
        if(surfaceAmount<=0f)return
        try {
            target.material.draw(canvas,view,surface,profile,LockscreenBackdrop.frame,background,
                bounds=bounds,nativeRadius=nativeRadius,amount=surfaceAmount)
        }catch(t:Throwable){GlassLogger.limited("LockscreenGlass","draw-"+surface.id) {"Native rendering kept: "+t.javaClass.simpleName}}
    }

    private var drawingClock=false

    private fun scheduleClockRefresh(){
        if(clockRefreshPending || clockAmount<=0f || Surface.CLOCK !in profiles)return
        clockRefreshPending=true
        Choreographer.getInstance().postFrameCallback {
            clockRefreshPending=false
            refreshClockMaterial()
        }
    }

    private fun refreshClockMaterial(){
        if(Surface.CLOCK !in profiles || clockAmount<=0f){
            restoreNativeClocks()
            return
        }
        if(android.os.Build.VERSION.SDK_INT>=33)nativeClockEffects.strongSnapshot().forEach { (view,effect)->
            if(view.isAttachedToWindow)applyClockEffect(view,effect)
        }
        clockGlyphs.keys.weakSnapshot().forEach {
            if(it.isAttachedToWindow && !hasNativeClockEffect(it))it.invalidate()
        }
    }

    private fun hookClockEffects(view:View){
        if(android.os.Build.VERSION.SDK_INT<33)return
        val classLoader=view.javaClass.classLoader ?: return
        if(!clockEffectLoaders.add(classLoader))return
        hookClockHandoff(classLoader)
        val helper=XposedHelpers.findClassIfExists(CLOCK_EFFECT_HELPER,classLoader) ?: return
        runCatching {
            hook(helper,"setRenderEffectImpl",emptyArray(),object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable() || Looper.myLooper()!==Looper.getMainLooper())return
                    runCatching {
                        val mounts=NativeIconAccess.field(p.thisObject,"effectMap") as? Map<*,*> ?: return
                        for((mount,builder) in mounts){
                            val view=mount as? View ?: continue
                            if(builder==null)continue
                            val native=NativeIconAccess.call(builder,"getRenderEffect") as? RenderEffect ?: continue
                            releasedClockMounts.remove(view)
                            nativeClockEffects[view]=native
                            if(clockAmount>0f)applyClockEffect(view,native,force=true)
                        }
                    }.onFailure {
                        GlassLogger.limited("LockscreenGlass","native-clock-mount") {"Native clock effect kept: "+it.javaClass.simpleName}
                    }
                }
            })
            hooks+=XposedBridge.hookAllMethods(helper,"releaseBuilder",object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(Looper.myLooper()!==Looper.getMainLooper())return
                    val view=p.args.firstOrNull() as? View ?: return
                    if(clockHandoff!=null && compositedClocks.containsKey(view)){
                        releasedClockMounts[view]=true
                        runCatching {view.setRenderEffect(compositedClocks[view])}
                        return
                    }
                    nativeClockEffects.remove(view)
                    compositedClocks.remove(view)
                }
            })
        }.onFailure {
            GlassLogger.limited("LockscreenGlass","native-clock-hook") {"Native clock reversal unavailable: "+it.javaClass.simpleName}
        }
    }

    private fun hookClockHandoff(classLoader:ClassLoader){
        val container=XposedHelpers.findClassIfExists("com.oplus.keyguard.clock.digital.ui.view.ClockContainer",classLoader) ?: return
        fun targetIsUnlock(scene:Any?)=(scene as? Enum<*>)?.name=="UNLOCK"
        fun start(p:XC_MethodHook.MethodHookParam,scene:Any?){
            if(Looper.myLooper()!==Looper.getMainLooper() || !targetIsUnlock(scene) || dozing || !interactive ||
                keyguardOccluded || clockAmount<=0f)return
            if(clockHandoff===p.thisObject)return
            clockHandoff=p.thisObject
            clockHandoffs++
            animateClock(0f,(42000L/unlockSpeed).coerceIn(300L,600L))
            main.removeCallbacks(finishClockHandoff)
            main.postDelayed(finishClockHandoff,900L)
        }
        val finish=object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(Looper.myLooper()===Looper.getMainLooper() && targetIsUnlock(p.args.getOrNull(1)) &&
                    clockHandoff===p.thisObject)endClockHandoff()
            }
        }
        runCatching {
            val prefix="dispatchLayoutTransition"
            val suffix="\$KeyguardPersonalityClocks_release"
            hooks+=XposedBridge.hookAllMethods(container,prefix+"Prepared"+suffix,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){start(p,p.args.getOrNull(1))}
            })
            hooks+=XposedBridge.hookAllMethods(container,prefix+"Start"+suffix,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    val anchor=p.args.firstOrNull() ?: return
                    start(p,NativeIconAccess.call(anchor,"getToScene"))
                }
            })
            hooks+=XposedBridge.hookAllMethods(container,prefix+"End"+suffix,finish)
            hooks+=XposedBridge.hookAllMethods(container,prefix+"Cancel"+suffix,finish)
        }.onFailure {GlassLogger.limited("LockscreenGlass","clock-handoff") {"Clock transition hook unavailable: "+it.javaClass.simpleName}}
    }

    private fun endClockHandoff(){
        if(clockHandoff==null)return
        main.removeCallbacks(finishClockHandoff)
        clockHandoff=null
        for(view in releasedClockMounts.keys.weakSnapshot()){
            nativeClockEffects.remove(view)
            if(compositedClocks.remove(view)!=null)runCatching {view.setRenderEffect(null)}
        }
        releasedClockMounts.clear()
        if(visible)showClockWhenReady()
        else if(clockAnimator==null)finishClockTransition()
    }

    private fun applyClockEffect(view:View,native:RenderEffect,force:Boolean=false){
        if(android.os.Build.VERSION.SDK_INT<33 || clockAmount<=0f)return
        val profile=profiles[Surface.CLOCK] ?: return
        if(view.width<=0 || view.height<=0 || view.display?.displayId!=Display.DEFAULT_DISPLAY)return
        val frame=LockscreenBackdrop.frame ?: return
        // Colorful Engine already refracts its own live backdrop at native
        // resolution. Sampling its final frame into the glyph would feed the
        // mirrored digit texture back into the clock.
        if(frame.livePackage==LockscreenBackdrop.COLORFUL_ENGINE){
            if(compositedClocks.remove(view)!=null)view.setRenderEffect(native)
            return
        }
        val wallpaper=frame.images[profile.sourceRadius(Surface.CLOCK)] ?: return
        runCatching {
            view.getLocationOnScreen(mountPosition)
            var top=Int.MAX_VALUE
            var bottom=Int.MIN_VALUE
            for(digit in clockGlyphs.keys.weakSnapshot()){
                if(!digit.isShown || digit.alpha<=.01f || digit.width<=0 || digit.height<=0)continue
                var parent:View?=digit
                while(parent!=null && parent!==view)parent=parent.parent as? View
                if(parent!==view)continue
                digit.getLocationOnScreen(clockPosition)
                val y=clockPosition[1]-mountPosition[1]
                top=minOf(top,y)
                bottom=maxOf(bottom,y+digit.height)
            }
            if(bottom<=top)return
            val target=target(view,Surface.CLOCK)
            val glyph=target.glyph ?: ClockGlyphGlass().also {target.glyph=it}
            val effect=glyph.afterNative(view,wallpaper,profile,native,
                top.coerceAtLeast(0).toFloat(),bottom.coerceAtMost(view.height).toFloat(),clockAmount,clockContour)
            if(force || compositedClocks[view]!==effect){
                view.setRenderEffect(effect)
                compositedClocks[view]=effect
            }
            if(rendered.add(Surface.CLOCK)){
                GlassLogger.i("LockscreenGlass","Clock glass chained after ColorOS on "+view.javaClass.simpleName)
            }
        }.onFailure {
            GlassLogger.limited("LockscreenGlass","native-clock-chain") {"Native clock effect kept: "+it.javaClass.simpleName}
        }
    }

    private fun restoreNativeClocks(){
        if(android.os.Build.VERSION.SDK_INT<33)return
        val mounts=compositedClocks.keys.weakSnapshot()
        compositedClocks.clear()
        for(view in mounts){
            val effect=nativeClockEffects[view]?.takeUnless {view in releasedClockMounts}
            runCatching {view.setRenderEffect(effect)}
        }
    }

    private fun hasNativeClockEffect(view:View):Boolean {
        var parent:View?=view
        while(parent!=null){
            if(parent in nativeClockEffects)return true
            parent=parent.parent as? View
        }
        return false
    }

    private fun isStockClockDigit(view:View):Boolean {
        if(view !is TextView || view.id==View.NO_ID)return false
        val inStockClock=generateSequence(view.parent as? View) {it.parent as? View}.take(5)
            .any {it.javaClass.name=="com.oplus.systemui.shared.clocks.RedHorizontalSingleClockView"}
        return inStockClock && runCatching {
            view.resources.getResourceEntryName(view.id) in stockClockDigits
        }.getOrDefault(false)
    }

    private fun isClockGlyph(view:View):Boolean {
        val type=view.javaClass
        val custom=clockGlyphTypes.getOrPut(type){
            type.name.startsWith("com.oplus.keyguard.clock.") &&
                generateSequence(type as Class<*>?) {it.superclass}.any {it.name in clockGlyphViews}
        }
        return type.name in clockText || (if(custom)belongsToClock(view) else isStockClockDigit(view))
    }

    private fun discoverClockGlyph(view:View){
        if(view.javaClass.name in clockGroups)hookClockEffects(view)
        if(view !is TextView || view in clockGlyphs || !isClockGlyph(view))return
        if(view.javaClass.name.startsWith("com.oplus.keyguard.clock."))hookClockEffects(view)
        clockGlyphs[view]=true
        view.removeOnLayoutChangeListener(clockLayoutChanged)
        view.addOnLayoutChangeListener(clockLayoutChanged)
        if(hasNativeClockEffect(view))scheduleClockRefresh()
        val name=view.javaClass.name
        // These plug-ins draw text from their own draw override, including
        // animated drawable digits which need the same coverage as text.
        val methodName=if(name.startsWith("com.oplus.keyguard.clock.gallery.widget.") ||
            name.startsWith("com.oplus.keyguard.clock.text.widget."))"draw" else "onDraw"
        val parameters=arrayOf<Class<*>>(Canvas::class.java)
        runCatching {
            val owner=generateSequence(view.javaClass as Class<*>?) {it.superclass}.firstOrNull {type->
                type.declaredMethods.any {it.name==methodName && it.parameterTypes.contentEquals(parameters)}
            } ?: return
            if(owner==View::class.java)return
            if(clockGlyphOwners.add(owner to methodName))hook(owner,methodName,parameters,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(clockAmount<=0f || drawingClock || Looper.myLooper()!==Looper.getMainLooper())return
                    val glyph=p.thisObject as View
                    if(isClockGlyph(glyph))beginClockGlyph(p,glyph)
                }
                override fun afterHookedMethod(p:MethodHookParam){endClockGlyph(p)}
            })
            view.invalidate()
        }.onFailure {
            GlassLogger.limited("LockscreenGlass","glyph-hook") {"Clock draw hook unavailable: "+it.javaClass.simpleName}
        }
    }

    private fun beginClockGlyph(p:XC_MethodHook.MethodHookParam,view:View){
        val profile=profiles[Surface.CLOCK] ?: return
        if(LockscreenBackdrop.frame?.livePackage==LockscreenBackdrop.COLORFUL_ENGINE)return
        // ColorOS encodes digit regions in the original glyph colors.
        // Leave them available to its shader and apply CPI after decoding.
        if(hasNativeClockEffect(view))return
        val canvas=p.args[0] as Canvas
        if(!renderable(view,canvas))return
        val target=target(view,Surface.CLOCK)
        val image=LockscreenBackdrop.frame?.images?.get(profile.sourceRadius(Surface.CLOCK)) ?: return
        clockBounds.set(0f,0f,view.width.toFloat(),view.height.toFloat())

        if(android.os.Build.VERSION.SDK_INT>=33){
            runCatching {
                val glyph=target.glyph ?: ClockGlyphGlass().also {target.glyph=it}
                val recording=glyph.record(view,clockBounds,image,profile,clockAmount,clockContour)
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

    private fun clearClockGlyphs(){
        clockGlyphs.keys.weakSnapshot().forEach {it.removeOnLayoutChangeListener(clockLayoutChanged)}
        clockGlyphs.clear()
    }

    private fun endClockGlyph(p:XC_MethodHook.MethodHookParam){
        val original=p.getObjectExtra("cpiClockCanvas") as? Canvas
        val saved=p.getObjectExtra("cpiClockLayer") as? Int
        if(original==null && saved==null)return
        val view=p.thisObject as View
        try {
            if(original!=null && android.os.Build.VERSION.SDK_INT>=33){
                val glyph=p.getObjectExtra("cpiClockGlyph") as ClockGlyphGlass
                glyph.finish(original,!p.hasThrowable())
            }else if(saved!=null && !p.hasThrowable()){
                val bounds=p.getObjectExtra("cpiClockBounds") as RectF
                val profile=profiles[Surface.CLOCK] ?: return
                views[view]?.material?.draw(p.args[0] as Canvas,view,Surface.CLOCK,profile,
                    LockscreenBackdrop.frame,glyphs=true,bounds=bounds,amount=clockAmount)
            }
            if(!p.hasThrowable() && rendered.add(Surface.CLOCK)){
                GlassLogger.i("LockscreenGlass","Clock glass applied to "+view.javaClass.simpleName)
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
