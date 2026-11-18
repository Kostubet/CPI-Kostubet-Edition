package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Component
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Panel
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Profile
import com.cosmod.launcherglass.util.ShadeGlassRenderer

/** Built from live View geometry with the same material renderer as SystemUI. */
internal class ShadeGlassPreview(context: Context, private val panel: Panel, private val dark: Boolean,
    private val readBackground:(()->Profile)?,
    private val read: (Component) -> Profile) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bounds=RectF()
    private val clip=Path()
    private val scene=Rect(0,0,320,212)
    private var source:ShadeGlassRenderer.Frame?=null
    private val renderers=mutableMapOf<Int,ShadeGlassRenderer>()
    private var profiles=panel.components.associateWith(read)
    private var backdrop=readBackground?.invoke()
    private var hasRenderer=Build.VERSION.SDK_INT>=33
    private var playing=false
    private var progress=.48f
    private var index=0

    init {
        contentDescription=context.getString(R.string.shade_glass_preview)
        setOnClickListener { playing=!playing; progress=if(playing).74f else .48f; invalidate() }
    }

    fun refresh() {
        profiles=panel.components.associateWith(read)
        backdrop=readBackground?.invoke()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    fun release() {
        renderers.values.forEach { it.clear() }
        renderers.clear()
        source=null
    }

    override fun onDraw(canvas:Canvas) {
        if(width==0 || height==0)return
        val frame=source ?: backdrop().also {source=it}
        val restore=canvas.save()
        canvas.scale(width/320f,height/212f)
        bounds.set(0f,0f,320f,212f)
        clip.rewind()
        clip.addRoundRect(bounds,19f,19f,Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawBitmap(frame.image,null,scene,paint)
        val background=backdrop
        if(hasRenderer && background?.enabled==true){
            runCatching {
                renderers.getOrPut(-1){ShadeGlassRenderer()}.draw(canvas,bounds,bounds,frame,background,1f,!dark,backdrop=true)
            }.onFailure { android.util.Log.w("CPI.ShadeGlassPreview","Background renderer unavailable",it); hasRenderer=false }
        }
        index=0
        if(panel==Panel.CONTROL)control(canvas,frame) else notifications(canvas,frame)
        canvas.restoreToCount(restore)
    }

    private fun backdrop():ShadeGlassRenderer.Frame {
        val image=Bitmap.createBitmap(scene.width(),scene.height(),Bitmap.Config.ARGB_8888)
        val c=Canvas(image)
        paint.shader=LinearGradient(0f,0f,260f,212f,
            if(dark)0xff10152a.toInt() else 0xffa3b9df.toInt(),
            if(dark)0xff244353.toInt() else 0xffd5c7df.toInt(),Shader.TileMode.CLAMP)
        c.drawPaint(paint)
        paint.shader=RadialGradient(270f,122f,145f,0xff4e72c8.toInt(),Color.TRANSPARENT,Shader.TileMode.CLAMP)
        c.drawPaint(paint)
        paint.shader=RadialGradient(40f,48f,100f,0xff73518d.toInt(),Color.TRANSPARENT,Shader.TileMode.CLAMP)
        c.drawPaint(paint)
        paint.shader=null
        paint.color=if(dark)0x427ac7ff else 0x70f1f7ff
        c.drawRoundRect(82f,-8f,132f,152f,20f,20f,paint)
        c.drawRoundRect(177f,96f,308f,168f,26f,26f,paint)
        return ShadeGlassRenderer.Frame(image,scene)
    }

    private fun plate(c:Canvas,frame:ShadeGlassRenderer.Frame,kind:Component,
        l:Float,t:Float,r:Float,b:Float,radius:Float,active:Boolean=false,circle:Boolean=false) {
        val id=index++
        val profile=profiles.getValue(kind)
        bounds.set(l,t,r,b)
        if(hasRenderer && profile.enabled){
            val drawn=runCatching {
                renderers.getOrPut(id){ShadeGlassRenderer()}.draw(c,bounds,bounds,frame,profile,1f,!dark,active,circle)
            }.getOrElse {
                android.util.Log.w("CPI.ShadeGlassPreview","Material renderer unavailable",it)
                hasRenderer=false
                false
            }
            if(drawn)return
        }
        paint.color=if(active || !dark)0xffe9ebf0.toInt() else 0xff282b36.toInt()
        c.drawRoundRect(bounds,radius,radius,paint)
    }

    private fun label(c:Canvas,value:String,x:Float,y:Float,size:Float=12f,solid:Boolean=false,center:Boolean=false) {
        paint.color=if(dark && !solid)Color.WHITE else 0xff20232e.toInt()
        paint.textSize=size
        paint.textAlign=if(center)Paint.Align.CENTER else Paint.Align.LEFT
        c.drawText(value,x,y,paint)
    }

    private fun control(c:Canvas,f:ShadeGlassRenderer.Frame) {
        plate(c,f,Component.MEDIA,14f,14f,148f,100f,20f)
        label(c,"♫",26f,43f,24f)
        label(c,context.getString(R.string.shade_glass_preview_music),26f,65f)
        label(c,if(playing)"◀    Ⅱ    ▶" else "◀    ▶    ▶",26f,87f)
        for((i,symbol) in listOf("◉","✧").withIndex()){
            val x=164f+73*i
            plate(c,f,Component.ROUND,x,14f,x+51f,65f,26f,circle=true)
            label(c,symbol,x+25f,47f,25f,center=true)
        }
        plate(c,f,Component.LARGE,16f,108f,148f,148f,20f)
        label(c,context.getString(R.string.shade_glass_preview_wifi),30f,133f)
        plate(c,f,Component.LARGE,16f,158f,148f,198f,20f)
        label(c,context.getString(R.string.shade_glass_preview_data),30f,183f)
        for((i,component) in listOf(Component.BRIGHTNESS,Component.VOLUME).withIndex()){
            val x=164f+57*i
            plate(c,f,component,x,77f,x+44f,194f,22f)
            val level=if(i==0)progress else .35f
            val save=c.save()
            c.clipRect(x,194f-117*level,x+44f,194f)
            paint.color=0xfff2f6ff.toInt()
            c.drawRoundRect(x,77f,x+44f,194f,22f,22f,paint)
            c.restoreToCount(save)
            label(c,if(i==0)"☀" else "♪",x+22f,180f,19f,solid=true,center=true)
        }
        plate(c,f,Component.OTHER_TILE,274f,99f,309f,134f,18f,circle=true)
        label(c,"⋯",291f,122f,18f,center=true)
    }

    private fun notifications(c:Canvas,f:ShadeGlassRenderer.Frame) {
        plate(c,f,Component.EXPANDED,13f,13f,307f,97f,18f)
        label(c,context.getString(R.string.shade_glass_preview_expanded),27f,36f,13f)
        label(c,context.getString(R.string.shade_glass_preview_body),27f,55f,11f)
        plate(c,f,Component.ACTION,26f,65f,105f,88f,12f)
        label(c,context.getString(R.string.shade_glass_preview_reply),66f,81f,11f,center=true)
        plate(c,f,Component.ACTION,114f,65f,222f,88f,12f)
        label(c,context.getString(R.string.shade_glass_preview_read),168f,81f,11f,center=true)
        plate(c,f,Component.COMPACT,13f,103f,307f,143f,16f)
        label(c,context.getString(R.string.shade_glass_preview_message),27f,128f,13f)
        plate(c,f,Component.GROUP,13f,149f,307f,189f,16f)
        label(c,context.getString(R.string.shade_glass_preview_group),27f,174f,13f)
        plate(c,f,Component.CLEAR,138f,181f,182f,205f,12f)
        label(c,"×",160f,200f,20f,center=true)
    }
}
