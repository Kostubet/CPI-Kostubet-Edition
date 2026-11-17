package com.cosmod.launcherglass.xposed
import android.graphics.*
import android.os.Bundle
import com.cosmod.launcherglass.ui.SurfaceTintSettings as Keys
import com.cosmod.launcherglass.util.ColorToneMath
import com.cosmod.launcherglass.util.IconPalette
/** Immutable colors rebuilt only when preferences/wallpaper change; no per-frame HSV allocation. */
internal object SurfaceTints {
    data class Tint(val color:Int,val opacity:Int)
    @Volatile private var profiles=emptyMap<Int,Tint>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    fun followsWallpaper(bundle:Bundle)=Keys.surfaces.any {bundle.getBoolean(Keys.key(it,"enabled")) && bundle.getBoolean(Keys.key(it,"wallpaper"))}
    fun update(bundle:Bundle,tone:IconPalette.WallpaperTone?=null):Boolean {
        val previous=profiles
        profiles=Keys.surfaces.mapNotNull {s->
            fun n(field:String)=bundle.getInt(Keys.key(s,field),Keys.integers.getValue(Keys.key(s,field)))
            if(!bundle.getBoolean(Keys.key(s,"enabled")))null else {
                val wallpaper=tone?.takeIf {bundle.getBoolean(Keys.key(s,"wallpaper"))}
                s to Tint(color(wallpaper?.hue ?: n("hue"),wallpaper?.saturationAt(n("saturation")) ?: n("saturation"),
                    n("brightness"),n("lightness")),n("opacity").coerceIn(0,100))
            }
        }.toMap()
        return profiles!=previous
    }
    fun get(surface:Int)=profiles[surface]
    fun color(hue:Int,saturation:Int,brightness:Int,lightness:Int)=ColorToneMath.argb(
        Color.HSVToColor(floatArrayOf(hue.coerceIn(0,360).toFloat(),saturation.coerceIn(0,100)/100f,brightness.coerceIn(0,100)/100f)),lightness)
    fun draw(canvas:Canvas,path:Path,bounds:RectF,surface:Int,alpha:Float=1f){
        val tint=get(surface) ?: return
        paint.color=tint.color;paint.alpha=(tint.opacity*2.55f*alpha).toInt().coerceIn(0,255)
        canvas.drawPath(path,paint)
    }
    fun draw(canvas:Canvas,bounds:RectF,radius:Float,surface:Int,alpha:Float=1f){
        val tint=get(surface) ?: return
        paint.color=tint.color;paint.alpha=(tint.opacity*2.55f*alpha).toInt().coerceIn(0,255)
        canvas.drawRoundRect(bounds,radius,radius,paint)
    }
}
