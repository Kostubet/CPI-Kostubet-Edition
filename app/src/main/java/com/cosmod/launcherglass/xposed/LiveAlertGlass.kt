package com.cosmod.launcherglass.xposed

import android.app.Application
import com.cosmod.launcherglass.BuildConfig
import android.content.Context
import android.database.ContentObserver
import android.graphics.*
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.ui.LockscreenSettings
import com.cosmod.launcherglass.util.*
import de.robv.android.xposed.*
import java.util.WeakHashMap

/** Hooks the plugin's background-only view, leaving alert content and touch handling native. */
internal object LiveAlertGlass {
    private var prefs=Bundle();private var context:Context?=null
    private var hostLoader:ClassLoader?=null
    private val main=Handler(Looper.getMainLooper())
    private val views=WeakHashMap<View,Boolean>()
    private var uiHooksInstalled=false
    private var allowed=false
    private var guardEvaluated=false
    private var guard:android.content.SharedPreferences?=null
    private val hooked=HashSet<Class<*>>()
    private val clippedCards=HashSet<Class<*>>()
    private val cardClip=Path()
    private val cardMatrix=Matrix()
    private val originalSkip=WeakHashMap<View,Boolean>()
    fun diagnostics():String=PerformancePolicy.describe()+"\nallowed=$allowed, hooks=$uiHooksInstalled, views=${views.size}\n"+NotificationBannerGlass.diagnostics()+"\n"+AlertBackdrop.diagnostics()+"\n"+AodWallpaperBlur.diagnostics()+"\n"+views.keys.weakSnapshot().take(8).joinToString("\n"){NativeAlertMaterial.describe(it)}
    private fun islandActive()=allowed && prefs.getBoolean(SettingsProvider.KEY_ENABLED,false) &&
        prefs.getBoolean("island_glass_enabled",false)

    @Synchronized private fun hookBackground(cls:Class<*>){
        if(cls.name!=LiveAlertTargets.CARD)return
        if(hooked.contains(cls))return
        val method=LiveAlertTargets.drawMethod(cls) ?: return
        XposedBridge.hookMethod(method,object:XC_MethodHook(){
            override fun beforeHookedMethod(p:MethodHookParam){
                if(!islandActive())return
                val view=p.thisObject as View
                val canvas=p.args[0] as Canvas
                try {
                    val replaced=NativeAlertMaterial.draw(canvas,view,prefs,replacing=true)
                    val path=if(replaced)null else NativeAlertMaterial.contour(canvas,view)
                    if(!replaced && path==null)return
                    val save=canvas.save()
                    p.setObjectExtra("cpiAlertNativeClip",save)
                    p.setObjectExtra("cpiAlertReplaced",replaced)
                    if(replaced)canvas.clipRect(0,0,0,0)else canvas.clipPath(path!!)
                }catch(t:Throwable){GlassLogger.limited("LiveAlertGlass","nativeUnderlay"){"Native alert retained: "+t}}
            }
            override fun afterHookedMethod(p:MethodHookParam){
                val view=p.thisObject as View;views[view]=true
                val canvas=p.args[0] as Canvas
                val save=p.getObjectExtra("cpiAlertNativeClip") as? Int
                if(save!=null)canvas.restoreToCount(save)
                if(p.getObjectExtra("cpiAlertReplaced")==true)return
                if(!islandActive() || p.hasThrowable())return
                try {NativeAlertMaterial.draw(canvas,view,prefs)}
                catch(t:Throwable){GlassLogger.limited("LiveAlertGlass","nativeOverlay"){"Native alert retained: "+t}}
            }
        })
        if(cls.name==LiveAlertTargets.CARD){
            val follow=cls.declaredMethods.firstOrNull {it.name=="setBlurRadiusFollowAlpha" && it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))}
            if(follow!=null)XposedBridge.hookMethod(follow,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    NativeAlertMaterial.nativeAlpha(p.thisObject as View,(p.args[0] as Number).toFloat())
                    if(!allowed)return
                    // Scale the radius the OEM just computed; never change its alpha.
                    val active=prefs.getBoolean(SettingsProvider.KEY_ENABLED,false) && prefs.getBoolean("island_glass_enabled",false)
                    NativeAlertMaterial.adjustBlur(p.thisObject as View,if(active)prefs.getInt("island_glass_blur",12)else 12)
                }
            })
        }
        hooked.add(cls)
        GlassLogger.i("LiveAlertGlass","Background hook installed: ${cls.name}.${method.name}")
    }
    private fun hookCard(view:View){
        val type=generateSequence(view.javaClass as Class<*>?) {it.superclass}
            .firstOrNull {it.name==LiveAlertTargets.EXPANDED_CARD} ?: return
        if(!clippedCards.add(type))return
        try {
            val background=type.getDeclaredMethod("getBgView")
            val draw=type.getDeclaredMethod("dispatchDraw",Canvas::class.java)
            XposedBridge.hookMethod(draw,object:XC_MethodHook(){
                override fun beforeHookedMethod(p:MethodHookParam){
                    if(!islandActive())return
                    val canvas=p.args[0] as Canvas
                    val card=p.thisObject as View
                    try {
                        val plate=background.invoke(card) as? View ?: return
                        if(plate.isLayoutRequested || plate.width<card.width*.85f || plate.height<card.height*.85f)return
                        val path=NativeAlertMaterial.contour(canvas,plate) ?: return
                        cardMatrix.reset()
                        plate.transformMatrixToGlobal(cardMatrix)
                        card.transformMatrixToLocal(cardMatrix)
                        path.transform(cardMatrix,cardClip)
                        val saved=canvas.save()
                        p.setObjectExtra("cpiLiveCardClip",saved)
                        canvas.clipPath(cardClip)
                    }catch(t:Throwable){
                        GlassLogger.limited("LiveAlertGlass","cardClip"){"Native card clip retained: "+t.javaClass.simpleName}
                    }
                }
                override fun afterHookedMethod(p:MethodHookParam){
                    val saved=p.getObjectExtra("cpiLiveCardClip") as? Int ?: return
                    (p.args[0] as Canvas).restoreToCount(saved)
                }
            })
        }catch(t:Throwable){
            GlassLogger.limited("LiveAlertGlass","cardClipHook"){"Native card layout retained: "+t.javaClass.simpleName}
        }
    }

    private fun discover(root:View?){
        if(!allowed || root==null)return
        try {LiveAlertTargets.visitViews(root){view->
            hookBackground(view.javaClass)
            var parent=view.parent as? View
            repeat(4){
                parent?.let(::hookCard)
                parent=parent?.parent as? View
            }
            if(!views.containsKey(view)){
                views[view]=true;originalSkip[view]=view.willNotDraw()
                view.setWillNotDraw(false);view.postInvalidateOnAnimation()
                GlassLogger.i("LiveAlertGlass","Expanded background discovered: "+NativeAlertMaterial.describe(view))
            }
        }}
        catch(t:Throwable){disable(t)}
    }
    private fun disable(t:Throwable){
        allowed=false
        guardEvaluated=true
        NotificationBannerGlass.configure(false,prefs,null)
        AlertBackdrop.configure(false)
        LockscreenGlass.configure(Bundle(),false)
        context?.let {AodWallpaperBlur.configure(it,null,0)}
        VolumeGlass.configure(false,prefs,null)
        guard?.let {AlertStartupGuard.block(it)}
        views.keys.weakSnapshot().forEach {NativeAlertMaterial.adjustBlur(it,12,false);it.invalidate()}
        GlassLogger.e("LiveAlertGlass","Alert material disabled; native rendering retained",t)
    }
    private fun installViewHooks(){
        if(uiHooksInstalled || !allowed)return
        // Layout inflation has finished before inspection: plugin classes and
        // their dependencies are fully loaded before reflection or Kotlin code runs.
        XposedHelpers.findAndHookMethod(LayoutInflater::class.java,"inflate",org.xmlpull.v1.XmlPullParser::class.java,
            ViewGroup::class.java,Boolean::class.javaPrimitiveType,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){discover(p.result as? View)}
            })
        XposedHelpers.findAndHookMethod(ViewGroup::class.java,"addView",View::class.java,Int::class.javaPrimitiveType,
            ViewGroup.LayoutParams::class.java,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){discover(p.args[0] as? View)}
            })
        uiHooksInstalled=true
        GlassLogger.i("LiveAlertGlass","View discovery active; no ClassLoader hooks")
        scanRoots()
    }
    private fun scanRoots(){
        try {
            val global=XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"),"getInstance")
            val names=XposedHelpers.callMethod(global,"getViewRootNames") as? Array<*> ?: return
            for(name in names)discover(XposedHelpers.callMethod(global,"getRootView",name) as? View)
        }catch(t:Throwable){GlassLogger.limited("LiveAlertGlass","existingRoots"){"Existing roots unavailable; awaiting view inflation: $t"}}
    }
    private fun load(){
        val c=context ?: return
        prefs=try {c.contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_GET_ALL,null,null) ?: Bundle()}catch(_:Throwable){Bundle()}
        PerformancePolicy.update(c,prefs)
        EdgeLight.update(prefs)
        WallpaperGlow.update(prefs)
        val master=prefs.getBoolean(SettingsProvider.KEY_ENABLED,false)
        val wantIsland=master && prefs.getBoolean("island_glass_enabled",false)
        val wantBanners=master && prefs.getBoolean("banner_glass_enabled",false)
        val wantLock=master && LockscreenSettings.requested(prefs)
        val wantVolume=master && prefs.getBoolean("volume_glass_enabled")
        if((wantIsland || wantBanners || wantLock || wantVolume) && !guardEvaluated){
            guardEvaluated=true
            guard?.let {store->
                val started=SystemClock.elapsedRealtime()
                allowed=AlertStartupGuard.begin(store,started,BuildConfig.VERSION_CODE)
                if(allowed)main.postDelayed({AlertStartupGuard.stable(store,started)},30000L)
                else GlassLogger.i("LiveAlertGlass","Native fallback: previous alert startup was interrupted")
            }
        }
        val active=allowed && wantIsland
        val banners=NotificationBannerGlass.configure(
            allowed && wantBanners,
            prefs,hostLoader
        )
        AlertBackdrop.configure(active || banners || allowed && wantVolume && prefs.getInt("volume_glass_style",1)==1,c)
        LockscreenGlass.configure(prefs,allowed && wantLock)
        AodWallpaperBlur.configure(c,hostLoader,if(allowed && wantLock && prefs.getBoolean(LockscreenSettings.AOD_BLUR_ENABLED))
            prefs.getInt(LockscreenSettings.AOD_BLUR_RADIUS,16) else 0)
        VolumeGlass.configure(allowed && wantVolume,prefs,hostLoader)
        views.keys.weakSnapshot().forEach {it.setWillNotDraw(if(active)false else originalSkip[it] ?: false);NativeAlertMaterial.adjustBlur(it,if(active)prefs.getInt("island_glass_blur",12)else 12,false);it.postInvalidateOnAnimation()}
        if(active)try {installViewHooks()}catch(t:Throwable){disable(t)}
    }
    fun install(loader:ClassLoader){
        hostLoader=loader
        XposedHelpers.findAndHookMethod(Application::class.java,"attach",Context::class.java,object:XC_MethodHook(){
            override fun afterHookedMethod(p:MethodHookParam){
                if(context!=null)return
                try {
                    val c=LiveAlertTargets.bootstrapContext(p.thisObject as Application)
                    context=c
                    RuntimeReport.loaded(c,"systemui")
                    runCatching {LockscreenGlass.bind(c,loader)}.onFailure {
                        GlassLogger.e("LockscreenGlass","Lockscreen setup unavailable",it)
                    }
                    val store=c.createDeviceProtectedStorageContext().getSharedPreferences("cherrypick_alert_guard_v2",Context.MODE_PRIVATE)
                    guard=store
                    HostControl.install(c,"systemui",{diagnostics()+"\n"+LockscreenGlass.diagnostics()},retry@{
                        AlertBackdrop.configure(false)
                        if(!AlertStartupGuard.clear(store))return@retry
                        guardEvaluated=false
                        allowed=false
                        load()
                        if(allowed)scanRoots()
                    })
                    val restart=object:android.content.BroadcastReceiver(){
                        override fun onReceive(context:Context?,intent:android.content.Intent?){
                            if(intent?.action!=SystemUiControl.ACTION)return
                            if(!AlertStartupGuard.clear(store))return
                            resultCode=SystemUiControl.ACCEPTED
                            // A normal process exit, after the ordered reply reaches the app.
                            main.postDelayed({android.os.Process.killProcess(android.os.Process.myPid())},400L)
                        }
                    }
                    val filter=android.content.IntentFilter(SystemUiControl.ACTION)
                    androidx.core.content.ContextCompat.registerReceiver(c,restart,filter,SystemUiControl.PERMISSION,main,
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
                    c.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI,true,object:ContentObserver(main){override fun onChange(selfChange:Boolean){try {load()}catch(t:Throwable){disable(t)}}})
                    load()
                }catch(t:Throwable){disable(t)}
            }
        })
    }
}
