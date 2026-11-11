package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Build
import android.view.View
import com.cosmod.launcherglass.ui.LockscreenSettings.Profile
import com.cosmod.launcherglass.ui.LockscreenSettings.Surface
import kotlin.math.roundToInt

/** One retained material per visible target. Stock content stays above the plate. */
internal class LockscreenMaterial {
    private data class Sample(val image:Bitmap,val source:RectF,val width:Int,val height:Int,val corner:Float,val profile:Profile,val glyphs:Boolean,val neutral:Boolean)
    private val node=RenderNode("CPI.lockscreenGlass")
    private var current:Sample?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val tint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE}
    private val rimBounds=RectF()
    private val nativeMix=Paint().apply {blendMode=BlendMode.SRC_ATOP}
    private val path=Path()
    private val screenMatrix=Matrix()
    private val local=RectF()
    private val mapped=RectF()
    private val source=RectF()
    private val location=IntArray(2)
    private val origin=FloatArray(2)
    private val outline=Outline()
    private val outlineRect=Rect()
    private val glyphColors=ColorMatrixColorFilter(ColorMatrix().apply {
        setSaturation(.55f)
        postConcat(ColorMatrix(floatArrayOf(
            .64f,0f,0f,0f,76f, 0f,.64f,0f,0f,76f, 0f,0f,.64f,0f,80f, 0f,0f,0f,1f,0f
        )))
    })
    private var appliedSaturation=100
    private var saturationFilter:ColorMatrixColorFilter?=null
    private var clockVeil:LinearGradient?=null
    private var clockVeilHeight=0f
    private var masked:RuntimeShader?=null
    private var maskWidth=0
    private var maskHeight=0
    private var maskCorner=0f

    fun discard(){node.discardDisplayList();node.setRenderEffect(null);current=null}

    fun draw(canvas:Canvas,view:View,surface:Surface,profile:Profile,frame:LockscreenBackdrop.Frame?,
        native:android.graphics.drawable.Drawable?=null,glyphs:Boolean=false,bounds:RectF?=null,amount:Float=1f,
        nativeRadius:Float?=null,replaceBackground:Boolean=false,useNativeMask:Boolean=true,drawRim:Boolean=true,
        exactPath:Path?=null,maskOnly:Boolean=false){
        val image=frame?.images?.get(profile.sourceRadius(surface)) ?: return
        val w=view.width.toFloat()
        val h=view.height.toFloat()
        if(w<=0f || h<=0f || !canvas.isHardwareAccelerated)return
        val round=surface==Surface.PIN || surface==Surface.SHORTCUTS
        val diameter=minOf(w,h)
        if(round)local.set((w-diameter)*.5f,(h-diameter)*.5f,(w+diameter)*.5f,(h+diameter)*.5f)
        else local.set(0f,0f,w,h)
        val fraction=if(round).5f else (if(profile.corner<0)32 else profile.corner)/100f
        var corner=diameter*fraction
        if(bounds!=null){
            local.set(bounds)
            corner=minOf(local.width(),local.height())*fraction
        }else if(native!=null && !native.bounds.isEmpty){
            local.set(native.bounds)
            outline.setEmpty()
            native.getOutline(outline)
            if(outline.getRect(outlineRect) && !outlineRect.isEmpty)local.set(outlineRect)
            if(outline.radius>0f)corner=outline.radius
        }
        if(local.isEmpty)return
        corner=if(glyphs || maskOnly)0f else (nativeRadius ?: corner).coerceIn(0f,minOf(local.width(),local.height())*.5f)
        path.rewind()
        if(exactPath!=null)path.set(exactPath)
        else path.addRoundRect(local,corner,corner,Path.Direction.CW)
        val neutral=surface.adjustableSaturation
        if(appliedSaturation!=profile.saturation){
            appliedSaturation=profile.saturation
            saturationFilter=if(appliedSaturation==100)null else ColorMatrixColorFilter(
                ColorMatrix().apply {setSaturation(appliedSaturation*.01f)})
        }
        val maskToNative=useNativeMask && (glyphs || surface==Surface.NOTIFICATIONS && !replaceBackground)
        val mixed=if(maskToNative)canvas.saveLayer(local,nativeMix) else canvas.save()
        try {
            val backdropAlpha=when {
                glyphs->if(profile.liquid).90f else .72f
                view.isPressed && !replaceBackground->.63f
                replaceBackground->.88f
                else->.96f
            }
            if(exactPath!=null)canvas.clipPath(path)
            drawBackdrop(canvas,view,image,profile,corner,backdropAlpha*amount,glyphs,neutral)
            val base=frame.color
            val dark=androidx.core.graphics.ColorUtils.calculateLuminance(base)<.40
            val tone=if(neutral){
                val luma=(Color.red(base)*.21f+Color.green(base)*.72f+Color.blue(base)*.07f).roundToInt()
                androidx.core.graphics.ColorUtils.blendARGB(Color.rgb(luma,luma,luma),base,(profile.saturation*.01f).coerceAtMost(1f))
            }else base
            tint.color=androidx.core.graphics.ColorUtils.blendARGB(tone,if(dark)Color.WHITE else Color.BLACK,.19f)
            if(glyphs){
                if(clockVeilHeight!=local.height()){
                    clockVeilHeight=local.height()
                    clockVeil=LinearGradient(0f,0f,0f,clockVeilHeight,
                        intArrayOf(0xfff3f4ff.toInt(),0x16ebefff,0xb5eef0ff.toInt()),
                        floatArrayOf(0f,.58f,1f),Shader.TileMode.CLAMP)
                }
                tint.shader=clockVeil
            }
            val opacity=if(glyphs && profile.liquid)profile.opacity.coerceAtLeast(9) else profile.opacity
            tint.alpha=(opacity*2.55f*amount).roundToInt().coerceIn(0,255)
            canvas.drawPath(path,tint)
            tint.shader=null
            if(!glyphs){
                if(corner>0f && !maskToNative && drawRim && surface!=Surface.CAPSULE){
                    rim.strokeWidth=(view.resources.displayMetrics.density*.68f).coerceAtLeast(1f)
                    val inset=rim.strokeWidth*.5f
                    val radius=(corner-inset).coerceAtLeast(0f)
                    rimBounds.set(local)
                    rimBounds.inset(inset,inset)
                    rim.color=Color.WHITE
                    rim.alpha=((16+profile.opacity/3).coerceAtMost(50)*amount).roundToInt()
                    canvas.drawRoundRect(rimBounds,radius,radius,rim)
                }
                if(!maskOnly)EdgeLight.drawPath(canvas,path,local,view,amount,surface.edgeSurface,profile.liquid)
            }
        }finally {canvas.restoreToCount(mixed)}
    }

    private fun drawBackdrop(canvas:Canvas,view:View,image:Bitmap,profile:Profile,corner:Float,alpha:Float,glyphs:Boolean,neutral:Boolean){
        screenMatrix.reset()
        view.transformMatrixToGlobal(screenMatrix)
        mapped.set(local)
        screenMatrix.mapRect(mapped)
        origin.fill(0f)
        screenMatrix.mapPoints(origin)
        view.getLocationOnScreen(location)
        mapped.offset(location[0]-origin[0].roundToInt().toFloat(),location[1]-origin[1].roundToInt().toFloat())
        val metrics=view.resources.displayMetrics
        val factor=maxOf(metrics.widthPixels.toFloat()/image.width,metrics.heightPixels.toFloat()/image.height)
        val x=(image.width-metrics.widthPixels/factor)*.5f
        val y=(image.height-metrics.heightPixels/factor)*.5f
        source.set(x+mapped.left/factor,y+mapped.top/factor,x+mapped.right/factor,y+mapped.bottom/factor)
        val width=local.width().roundToInt().coerceAtLeast(1)
        val height=local.height().roundToInt().coerceAtLeast(1)
        val filter=if(glyphs)glyphColors else if(neutral)saturationFilter else null
        if(!profile.liquid || Build.VERSION.SDK_INT<33 || !lens(image,profile,corner,width,height,glyphs,neutral,filter)){
            val shader=GlassGeometry.wallpaperShader(image)
            shader.setLocalMatrix(Matrix().apply {setRectToRect(source,local,Matrix.ScaleToFit.FILL)})
            paint.shader=shader
            paint.colorFilter=filter
            paint.alpha=(alpha*255).roundToInt()
            canvas.drawPath(path,paint)
            paint.shader=null
            paint.colorFilter=null
            return
        }
        val save=canvas.save()
        try {
            canvas.translate(local.left,local.top)
            node.alpha=alpha
            canvas.drawRenderNode(node)
        }finally {canvas.restoreToCount(save)}
    }

    @androidx.annotation.RequiresApi(33)
    private fun lens(image:Bitmap,profile:Profile,corner:Float,width:Int,height:Int,glyphs:Boolean,neutral:Boolean,filter:ColorMatrixColorFilter?):Boolean {
        if(current?.let {it.image===image && it.source==source && it.width==width && it.height==height &&
                it.corner==corner && it.profile==profile && it.glyphs==glyphs && it.neutral==neutral}==true && node.hasDisplayList())return true
        val lens=LiquidGlassShader.shared() ?: return false
        val mask=masked ?: RuntimeShader("""
            uniform float2 size;
            uniform float radius;
            half4 main(float2 p) {
                float2 q=abs(p-size*.5)-(size*.5-radius);
                float d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
                return half4(1.0-smoothstep(-.85,.45,d));
            }
        """).also {masked=it}
        if(maskWidth!=width || maskHeight!=height || maskCorner!=corner){
            mask.setFloatUniform("size",width.toFloat(),height.toFloat())
            mask.setFloatUniform("radius",corner)
            maskWidth=width
            maskHeight=height
            maskCorner=corner
        }
        // A thin digit cannot use the displacement of a plate the size of its bounding box.
        val strength=if(glyphs)(profile.distortion*.30f).roundToInt() else profile.distortion
        val dispersion=if(glyphs)(profile.dispersion*.12f).roundToInt() else profile.dispersion
        val style=GlassStyle(true,profile.width,strength,profile.blur,0,null,extendedLens=!glyphs,dispersion=dispersion)
        val shader=lens.update(image,mask,source,width,height,filter,
            corner/minOf(width,height),style)
        node.setRenderEffect(RenderEffect.createShaderEffect(shader))
        if(node.width!=width || node.height!=height || !node.hasDisplayList()){
            node.setPosition(0,0,width,height)
            val recording=node.beginRecording()
            try {recording.drawColor(Color.BLACK)}finally {node.endRecording()}
        }
        current=Sample(image,RectF(source),width,height,corner,profile,glyphs,neutral)
        return true
    }
}

internal fun Profile.sourceRadius(surface:Surface):Int {
    val radius=(blur*(if(liquid).40f else 1f)).roundToInt()
    return when(surface){
        Surface.CLOCK->blur
        Surface.WIDGETS->if(liquid)radius.coerceAtLeast(2) else radius
        else->radius
    }
}
