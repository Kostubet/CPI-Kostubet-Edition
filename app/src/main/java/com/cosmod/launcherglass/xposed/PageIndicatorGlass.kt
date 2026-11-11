package com.cosmod.launcherglass.xposed

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/** The home indicator supplies the capsule bounds, fade, and dot layout. */
internal object PageIndicatorGlass {
    data class Profile(val mode:Int=0,val blur:Int=12,val opacity:Int=26,val corner:Int=50,
        val width:Int=42,val distortion:Int=80,val dispersion:Int=0){
        val style=GlassStyle(mode==2,width,distortion,blur,opacity,null,plateColor=0x181818,
            extendedLens=true,neutral=true,rimOpacity=12,dispersion=dispersion,surfaceScope=GlassSurface.PAGE_INDICATOR)
    }

    @Volatile var profile=Profile()
        private set
    private val views=WeakHashMap<View,Boolean>()
    private val workspaceIndicators=WeakHashMap<View,Boolean>()
    private var keepVisible=true
    private var refreshVisibility:((View)->Unit)?=null
    private val bounds=Rect()
    private val identity=ColorDrawable(Color.TRANSPARENT)

    fun update(bundle:Bundle){
        val next=Profile(bundle.getInt("indicator_glass_style").coerceIn(0,2),
            bundle.getInt("indicator_glass_blur",12).coerceIn(0,50),
            bundle.getInt("indicator_glass_opacity",26).coerceIn(0,100),
            bundle.getInt("indicator_glass_corner",50).coerceIn(0,50),
            bundle.getInt("indicator_glass_width",42).coerceIn(0,100),
            bundle.getInt("indicator_glass_distortion",80).coerceIn(0,300),
            bundle.getInt("indicator_glass_dispersion",0).coerceIn(0,100))
        val nextKeepVisible=bundle.getBoolean("indicator_keep_visible",true)
        if(next!=profile){
            profile=next
            views.keys.weakSnapshot().forEach {it.invalidate()}
        }
        if(nextKeepVisible!=keepVisible){
            keepVisible=nextKeepVisible
            workspaceIndicators.keys.weakSnapshot().forEach {view->
                view.post {refreshVisibility?.invoke(view)}
            }
        }
    }

    fun onBackdropChanged(){views.keys.weakSnapshot().forEach {it.invalidate()}}

    fun install(loader:ClassLoader){
        runCatching {
            val type=XposedHelpers.findClassIfExists("com.android.launcher.pageindicators.OplusPageIndicator",loader) ?: return
            runCatching { installVisibility(type,loader) }.onFailure {
                GlassLogger.limited("PageIndicatorGlass","visibility") {"Native indicator visibility kept: "+it.javaClass.simpleName}
            }
            val draw=type.getDeclaredMethod("drawBackgroundIfNeeded",Canvas::class.java,Int::class.javaPrimitiveType)
            val childDraw=ViewGroup::class.java.getDeclaredMethod("drawChild",Canvas::class.java,View::class.java,Long::class.javaPrimitiveType)
            val background=type.getDeclaredField("mBlurBgView").apply {isAccessible=true}
            val nativeBounds=type.getDeclaredField("mBackgroundRect").apply {isAccessible=true}
            val folder=type.getDeclaredMethod("isFolderHost").apply {isAccessible=true}
            fun active(view:View)=PrefsHelper.enabled && profile.mode>0 && folder.invoke(view)==false
            ModuleHooks.hookMethod(childDraw,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    if(!PrefsHelper.enabled || profile.mode==0 || NeutralBackdrop.indicatorImage==null)return
                    val view=param.thisObject as View
                    if(type.isInstance(view) && active(view) && param.args[1]===background.get(view) &&
                        (param.args[0] as Canvas).isHardwareAccelerated &&
                        (nativeBounds.get(view) as? RectF)?.isEmpty==false)param.result=false
                }
            })
            ModuleHooks.hookMethod(draw,object:XC_MethodHook(){
                override fun beforeHookedMethod(param:MethodHookParam){
                    val view=param.thisObject as View
                    val canvas=param.args[0] as Canvas
                    val opacity=(param.args[1] as Int).coerceIn(0,255)
                    if(opacity==0 || !canvas.isHardwareAccelerated || !active(view))return
                    val rect=nativeBounds.get(view) as? RectF ?: return
                    if(rect.isEmpty)return
                    views[view]=true
                    val settings=profile
                    rect.roundOut(bounds)
                    val radius=minOf(bounds.width(),bounds.height())*settings.corner*.01f
                    if(SurfaceMaterial.draw(canvas,bounds,view,identity,radius,alpha=opacity,style=settings.style)){
                        param.result=null
                    }
                }
            })
        }.onFailure {GlassLogger.limited("PageIndicatorGlass","hook") {"Native indicator kept: "+it.javaClass.simpleName}}
    }

    private fun installVisibility(type:Class<*>,loader:ClassLoader){
        val launcher=type.getDeclaredField("mLauncher").apply {isAccessible=true}
        val folder=type.getDeclaredMethod("isFolderHost").apply {isAccessible=true}
        val state=XposedHelpers.findClass("com.android.launcher3.LauncherState",loader)
        // Reading NORMAL triggers LauncherState initialization. Resolve it only
        // after the OEM application and its LauncherUtilProvider are ready.
        val normal by lazy {XposedHelpers.getStaticObjectField(state,"NORMAL")}
        val activity=XposedHelpers.findClass("com.android.launcher3.statemanager.StatefulActivity",loader)
        val baseState=XposedHelpers.findClass("com.android.launcher3.statemanager.BaseState",loader)
        val inState=activity.getMethod("isInState",baseState)
        val begin=type.getDeclaredMethod("onPageBeginTransition").apply {isAccessible=true}
        val end=type.getDeclaredMethod("onPageEndTransition").apply {isAccessible=true}
        val cancel=type.getDeclaredMethod("cancelShowExitAnimator").apply {isAccessible=true}

        fun onHome(view:View):Boolean {
            if(!view.isAttachedToWindow)return false
            return runCatching {
                val host=launcher.get(view) ?: return false
                folder.invoke(view)==false && inState.invoke(host,normal)==true
            }.getOrDefault(false)
        }
        fun show(view:View){
            cancel.invoke(view)
            view.visibility=View.VISIBLE
            view.alpha=1f
        }
        fun refresh(view:View){
            if(!view.isAttachedToWindow || !onHome(view))return
            if(PrefsHelper.enabled && keepVisible)show(view) else end.invoke(view)
        }
        refreshVisibility=::refresh

        ModuleHooks.hookMethod(end,object:XC_MethodHook(){
            override fun beforeHookedMethod(param:MethodHookParam){
                if(!PrefsHelper.enabled || !keepVisible)return
                val view=param.thisObject as View
                if(!onHome(view))return
                show(view)
                param.result=null
            }
        })
        for(method in listOf(type.getDeclaredMethod("onAttachedToWindow"),begin)){
            ModuleHooks.hookMethod(method,object:XC_MethodHook(){
                override fun afterHookedMethod(param:MethodHookParam){
                    if(param.hasThrowable())return
                    val view=param.thisObject as View
                    if(folder.invoke(view)==true)return
                    workspaceIndicators[view]=true
                    if(PrefsHelper.enabled && keepVisible){
                        view.post {if(PrefsHelper.enabled && keepVisible)refresh(view)}
                    }
                }
            })
        }
    }

    fun wallpaper(view:View)=WallpaperHelper.getIndicatorWallpaper(view.context)
}
