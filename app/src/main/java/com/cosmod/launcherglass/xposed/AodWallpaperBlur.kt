package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.SurfaceControl
import android.view.animation.PathInterpolator
import com.cosmod.launcherglass.util.GlassLogger
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import kotlin.math.roundToInt

/** A compositor blur child of the native wallpaper layer, behind the AOD content. */
internal object AodWallpaperBlur {
    private const val CONTROLLER="com.oplus.systemui.aod.display.SmoothTransitionController"
    private const val WALLPAPER="com.oplus.systemui.keyguard.anim.OplusWallpaperAnimControllerImpl"
    private val main=Handler(Looper.getMainLooper())
    private val hooks=ArrayList<XC_MethodHook.Unhook>()
    private var context:Context?=null
    private var controller:Any?=null
    private var state=""
    private var radiusDp=0
    private var layer:SurfaceControl?=null
    private var animator:ValueAnimator?=null
    private var applied=0
    private var destination=0
    private val retry=Runnable {refresh()}
    private val finish=Runnable {
        animator?.cancel()
        animator=null
        setRadius(destination)
    }

    private class Compositor {
        val builder=Class.forName("android.view.SurfaceControl"+'$'+"Builder")
        val transaction=Class.forName("android.view.SurfaceControl"+'$'+"Transaction")
        private val surface=SurfaceControl::class.java
        private val integer=Int::class.javaPrimitiveType!!
        private val blur=transaction.getMethod("setBackgroundBlurRadius",surface,integer)
        private val crop=transaction.getMethod("setWindowCrop",surface,integer,integer)
        private val layerOrder=transaction.getMethod("setLayer",surface,integer)
        private val show=transaction.getMethod("show",surface)
        private val remove=transaction.getMethod("remove",surface)
        private val apply=transaction.getMethod("apply")

        fun create(parent:SurfaceControl,width:Int,height:Int):SurfaceControl {
            val value=builder.getConstructor().newInstance()
            builder.getMethod("setName",String::class.java).invoke(value,"CPI AOD wallpaper blur")
            builder.getMethod("setParent",surface).invoke(value,parent)
            builder.getMethod("setEffectLayer").invoke(value)
            val child=builder.getMethod("build").invoke(value) as SurfaceControl
            try {
                transact {
                    crop.invoke(it,child,width,height)
                    layerOrder.invoke(it,child,1)
                    blur.invoke(it,child,0)
                    show.invoke(it,child)
                }
            }catch(t:Throwable){
                runCatching {destroy(child)}
                throw t
            }
            return child
        }
        private fun transact(action:(Any)->Unit){
            val tx=transaction.getConstructor().newInstance()
            try {action(tx);apply.invoke(tx)}finally {(tx as? AutoCloseable)?.close()}
        }
        fun radius(child:SurfaceControl,pixels:Int)=transact {blur.invoke(it,child,pixels)}
        fun destroy(child:SurfaceControl){
            try {transact {remove.invoke(it,child)}}finally {child.release()}
        }
    }
    private val api by lazy {runCatching {Compositor()}}

    fun configure(context:Context,loader:ClassLoader?,radius:Int){
        this.context=context
        val next=radius.coerceIn(0,36)
        val changed=radiusDp!=next
        radiusDp=next
        if(next==0){
            if(!changed && hooks.isEmpty())return
            hooks.forEach {it.unhook()}
            hooks.clear()
            controller=null
            state=""
            stop()
            return
        }
        if(hooks.isEmpty() && loader!=null)install(context,loader)
        if(changed)refresh(retryMissing=true)
    }

    private fun install(context:Context,loader:ClassLoader){
        runCatching {
            api.getOrThrow()
            val type=XposedHelpers.findClass(CONTROLLER,loader)
            val set=type.declaredMethods.single {it.name=="setCurrentUiState" && it.parameterCount==1}
            hooks+=XposedBridge.hookMethod(set,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val next=(p.args[0] as? Enum<*>)?.name ?: return
                    main.post {
                        if(radiusDp==0)return@post
                        controller=p.thisObject
                        state=next
                        GlassLogger.limited("AodWallpaperBlur","state-"+next) {"Native state: "+next}
                        refresh(retryMissing=true)
                    }
                }
            })
            XposedHelpers.findClassIfExists(WALLPAPER,loader)?.declaredMethods
                ?.firstOrNull {it.name=="onWallpaperSurfaceChanged" && it.parameterCount==2}?.let {method->
                    hooks+=XposedBridge.hookMethod(method,object:XC_MethodHook(){
                        override fun afterHookedMethod(p:MethodHookParam){
                            if(!p.hasThrowable())main.post {
                                if(radiusDp>0 && fullscreen()){
                                    stop()
                                    refresh(retryMissing=true)
                                }
                            }
                        }
                    })
                }
            // Never initialize vendor singletons from Application.attach: AodData's
            // application context has not been installed yet. Observe OEM ownership.
            hooks+=XposedBridge.hookAllConstructors(type,object:XC_MethodHook(){
                override fun afterHookedMethod(p:MethodHookParam){
                    if(p.hasThrowable())return
                    val instance=p.thisObject
                    main.post {
                        if(radiusDp<=0)return@post
                        controller=instance
                        state=(NativeIconAccess.call(instance,"getCurrentUiState") as? Enum<*>)?.name.orEmpty()
                        refresh(retryMissing=true)
                    }
                }
            })
            // This field read does not call getInstance or start initialization.
            val existing=type.declaredFields.firstOrNull {
                java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type==type
            }?.let {it.isAccessible=true;it.get(null)}
            if(existing!=null){
                controller=existing
                state=(NativeIconAccess.call(existing,"getCurrentUiState") as? Enum<*>)?.name.orEmpty()
            }
        }.onFailure {
            if(hooks.isEmpty())stop()
            GlassLogger.limited("AodWallpaperBlur","hook") {"Fullscreen AOD unavailable: "+it.javaClass.simpleName}
        }
    }

    fun diagnostics()="aod state="+state+", radius="+radiusDp+", layer="+(layer?.isValid==true)+", applied="+applied

    private fun fullscreen()=state=="PANORAMIC_AOD" || state=="WORKSHOP_AOD"

    private fun refresh(retryMissing:Boolean=false){
        val context=context ?: return
        if(radiusDp<=0)return
        if(!fullscreen()){
            if(state=="AOD_OFF" || state=="NORMAL_AOD")stop() else animateTo(0)
            return
        }
        val metrics=context.resources.displayMetrics
        if(layer?.isValid!=true){
            stop()
            val wallpaper=runCatching {LockscreenBackdrop.wallpaperSurface(context,null,null)}.getOrNull()
            if(wallpaper==null){
                GlassLogger.limited("AodWallpaperBlur","wallpaper-missing") {"Waiting for native wallpaper surface"}
                if(retryMissing){main.removeCallbacks(retry);main.postDelayed(retry,150L)}
                return
            }
            layer=runCatching {api.getOrThrow().create(wallpaper,metrics.widthPixels,metrics.heightPixels)}
                .onFailure {GlassLogger.limited("AodWallpaperBlur","layer") {"Wallpaper blur layer unavailable: "+it.javaClass.simpleName}}
                .getOrNull() ?: return
        }
        animateTo((radiusDp*metrics.density).roundToInt())
    }

    private fun animateTo(target:Int){
        if(layer==null || target==destination && (animator!=null || target==applied))return
        main.removeCallbacks(finish)
        animator?.cancel()
        animator=null
        destination=target
        if(target==applied || !ValueAnimator.areAnimatorsEnabled()){
            setRadius(target)
            return
        }
        animator=ValueAnimator.ofInt(applied,target).apply {
            duration=320L
            interpolator=PathInterpolator(.25f,.1f,.25f,1f)
            addUpdateListener {setRadius(it.animatedValue as Int,removeAtZero=false)}
            start()
        }
        main.postDelayed(finish,350L)
    }

    private fun setRadius(value:Int,removeAtZero:Boolean=true){
        val current=layer ?: return
        if(value==0 && removeAtZero){stop();return}
        if(value==applied)return
        runCatching {api.getOrThrow().radius(current,value)}.onSuccess {applied=value}.onFailure {stop()}
    }

    private fun stop(){
        main.removeCallbacks(retry)
        main.removeCallbacks(finish)
        animator?.cancel()
        animator=null
        val old=layer
        layer=null
        if(old!=null)runCatching {api.getOrThrow().destroy(old)}
        applied=0
        destination=0
    }
}
