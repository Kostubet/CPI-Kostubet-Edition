package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Bundle
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method

/** Preserves the compositor backdrop until a complete live lens is ready. */
internal object NativeAlertMaterial {
    private data class BlurAccess(val radius:Field,val setter:Method)
    private val access=HashMap<Class<*>,BlurAccess?>()
    
    private val nativeRadius=java.util.WeakHashMap<View,Int>()
    fun adjustBlur(view:View,amount:Int,fromNative:Boolean=true){
        val background=view.background ?: return
        try {
            val cls=background.javaClass
            val entry=if(access.containsKey(cls))access[cls] else {
                var type:Class<*>?=cls;var radius:Field?=null
                while(type!=null && radius==null){
                    radius=type.declaredFields.firstOrNull {it.name=="mBlurRadius" && it.type==Int::class.javaPrimitiveType}
                    type=type.superclass
                }
                val value=radius?.let {it.isAccessible=true;BlurAccess(it,cls.getMethod("setBlurRadius",Int::class.javaPrimitiveType))}
                access[cls]=value;value
            }
            // Called only immediately after the native alpha/radius animator.
            if(entry!=null){
                val original=if(fromNative)entry.radius.getInt(background).also {nativeRadius[view]=it}
                    else nativeRadius[view] ?: return
                entry.setter.invoke(background,((original*amount.coerceIn(0,50))/12).coerceIn(0,1000))
            }
        }catch(_:Exception){/* OEM radius remains in control. */}
    }
    private val alphas=java.util.WeakHashMap<View,Float>()
    private val managers=HashMap<Class<*>,Field?>()
    private var rendered=0L
    private val shadePaint=Paint(Paint.ANTI_ALIAS_FLAG)
    fun visibleAlpha(view:View)=alphas[view] ?: view.alpha
    fun nativeAlpha(view:View,value:Float){alphas[view]=value.coerceIn(0f,1f)}
    private fun material(view:View):android.graphics.drawable.Drawable? {
        val background=view.background
        if(background!=null && background.javaClass.name.contains("BackgroundBlur"))return background
        return try {
            val cls=view.javaClass
            val field=if(managers.containsKey(cls))managers[cls] else {
                var type:Class<*>?=cls;var found:Field?=null
                while(type!=null && found==null){found=type.declaredFields.firstOrNull {it.type.name=="com.oplus.view.ViewRootManager"};type=type.superclass}
                found?.isAccessible=true;managers[cls]=found;found
            }
            val manager=field?.get(view)
            (manager?.javaClass?.getMethod("getBackgroundBlurDrawable")?.invoke(manager) as? android.graphics.drawable.Drawable) ?: background
        }catch(_:Exception){background}
    }
    fun describe(view:View):String="contour=${if(view.width>0)AlertContour.resolve(view,material(view)).let { "nativePath=${it.nativePath}, bounds=${it.bounds}, revision=${it.revision}" }else "unlaid"}; "+"${view.javaClass.simpleName} ${view.width}x${view.height}, shown=${view.isShown}, skip=${view.willNotDraw()}, hw=${view.isHardwareAccelerated}, background=${view.background?.javaClass?.name}, material=${material(view)?.javaClass?.name}, nativeAlpha=${alphas[view]}, rendered=$rendered"
    fun contour(canvas:Canvas,view:View):Path? {
        if(!canvas.isHardwareAccelerated || view.width<=0 || view.height<=0 || !view.isShown ||
            AlertBackdrop.frame(view)==null)return null
        return AlertContour.resolve(view,material(view)).path.takeUnless {it.isEmpty}
    }

    fun draw(canvas:Canvas,view:View,prefs:Bundle,replacing:Boolean=false):Boolean {
        if(view.width<1 || view.height<1 || !view.isShown)return false
        if(replacing && (view.isLayoutRequested || prefs.getInt("island_glass_mix",100)<100))return false
        val native=material(view)
        val opacity=((alphas[view] ?: ((view.background?.alpha ?: native?.alpha ?: 255).takeIf {it>0} ?: 255)/255f)*255).toInt().coerceIn(0,255)
        if(opacity==0)return false
        val shape=AlertContour.resolve(view,native)
        val bounds=shape.bounds
        val drawn=android.os.Build.VERSION.SDK_INT>=33 && canvas.isHardwareAccelerated && AlertGlassLens.draw(canvas,view,prefs,shape,opacity)
        if(!drawn)return false
        rendered++
        if(rendered<=3)com.cosmod.launcherglass.util.GlassLogger.i("LiveAlertGlass","GPU lens rendered: ${view.width}x${view.height}, alpha=$opacity, material=${native?.javaClass?.name}")
        val shade=prefs.getInt("island_glass_opacity",25).coerceIn(0,100)*opacity/100
        if(shade>0){
            shadePaint.color=Color.argb(shade,0,0,0)
            canvas.drawPath(shape.path,shadePaint)
        }
        EdgeLight.drawPath(canvas,shape.path,bounds,view,opacity/255f,GlassSurface.LIVE)
        return true
    }
}
