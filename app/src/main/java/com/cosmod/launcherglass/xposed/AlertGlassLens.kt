package com.cosmod.launcherglass.xposed

import android.graphics.*
import android.os.Bundle
import android.view.View
import java.util.WeakHashMap
import kotlin.math.*

/** The same GPU lens as icons/folders, with a live app image as its input. */
@android.annotation.TargetApi(33)
internal object AlertGlassLens {
    private class Plate {
        val node=RenderNode("CPI.liveAlertLens")
        val mask=RuntimeShader("""uniform float2 size;uniform float radius;
            half4 main(float2 p){float2 q=abs(p-size*.5)-(size*.5-radius);
            float d=length(max(q,0.0))+min(max(q.x,q.y),0.0)-radius;
            return half4(1.0)*half(1.0-smoothstep(-.7,.7,d));}""")
        var key:List<Any>?=null
        var generation=Long.MIN_VALUE
        var image:Bitmap?=null
        val sourceRect=RectF()
        var recordedWidth=0
        var recordedHeight=0
        val paint=Paint(Paint.FILTER_BITMAP_FLAG)
        val sourceMatrix=Matrix()
        var sourceShader:BitmapShader?=null
        val screenMatrix=Matrix()
        val screenAxes=FloatArray(4)
        val screenOrigin=FloatArray(2)
        val position=IntArray(2)
        var shapeMask:Shader?=null
        val cornerRadii=FloatArray(4)
        var shapeVersion=-1
        var shapeSize=0L
    }
    private val plates=WeakHashMap<View,Plate>()
    private val haze by lazy {
        RuntimeShader("""
            uniform shader scene;
            uniform float spreadRadius;
            uniform float strength;
            uniform float threshold;
            uniform float chroma;
            half4 main(float2 p) {
                half4 center=scene.eval(p);
                if(center.a<.01)return center;
                float2 d=float2(spreadRadius,spreadRadius*.78);
                float3 fog=(float3(scene.eval(p+d).rgb)+float3(scene.eval(p-d).rgb)
                    +float3(scene.eval(p+float2(d.x,-d.y)).rgb)+float3(scene.eval(p+float2(-d.x,d.y)).rgb))*.25;
                float y=dot(fog,float3(.2126,.7152,.0722));
                float weight=smoothstep(threshold-.09,threshold+.12,y);
                float3 rgb=float3(center.rgb);
                rgb+=max(mix(float3(y),fog,chroma)-rgb*.42,0.0)*(1.0-rgb)*weight*strength;
                return half4(half3(min(rgb,float3(center.a))),center.a);
            }
        """)
    }
    fun forget(view:View){plates.remove(view)}
    fun draw(canvas:Canvas,view:View,prefs:Bundle,shape:AlertContour.Shape,opacity:Int):Boolean{
        if(!canvas.isHardwareAccelerated)return false
        val frame=AlertBackdrop.frame(view) ?: return false
        return renderInternal(canvas,view,prefs,frame.bitmap,frame.area,shape.bounds,shape.radius,opacity,shape,frame.generation)
    }
    fun render(canvas:Canvas,view:View,prefs:Bundle,image:Bitmap,area:Rect,bounds:RectF,corner:Float,opacity:Int):Boolean=
        renderInternal(canvas,view,prefs,image,area,bounds,corner,opacity,null,image.generationId.toLong())
    fun renderShape(canvas:Canvas,view:View,prefs:Bundle,image:Bitmap,area:Rect,shape:AlertContour.Shape,opacity:Int):Boolean=
        renderInternal(canvas,view,prefs,image,area,shape.bounds,shape.radius,opacity,shape,image.generationId.toLong())
    fun renderBanner(canvas: Canvas, view: View, prefs: Bundle, frame: AlertBackdrop.Frame, bounds: RectF, corner: Float): Boolean =
        renderInternal(canvas, view, prefs, frame.bitmap, frame.area, bounds, corner, 255, null, frame.generation, true)

    private fun edgeFogRadius(prefs:Bundle,edge:Int):Float {
        val radius=prefs.getInt("wallpaper_glow_radius",55).coerceIn(0,100)
        val diffusion=prefs.getInt("wallpaper_glow_diffusion",50).coerceIn(0,100)
        val feather=prefs.getInt("wallpaper_glow_softness",0).coerceIn(0,100)
        return edge*(.014f+radius*.001f+feather*.0006f)*(.35f+diffusion*.013f)
    }

    private fun renderInternal(canvas:Canvas,view:View,prefs:Bundle,image:Bitmap,area:Rect,bounds:RectF,corner:Float,opacity:Int,shape:AlertContour.Shape?,generation:Long,transformed:Boolean=false):Boolean{
        if(!canvas.isHardwareAccelerated || bounds.isEmpty)return false
        val plate=plates.getOrPut(view){Plate()}
        val limit=image.width
        val scale=minOf(1f,limit/bounds.width())
        val w=max(1,(bounds.width()*scale).roundToInt());val h=max(1,(bounds.height()*scale).roundToInt())
        val position=plate.position;view.getLocationOnScreen(position)
        val lensWidth=prefs.getInt("island_glass_width",40)
        val distortion=prefs.getInt("island_glass_distortion",80)
        val blur=prefs.getInt("island_glass_blur",12)
        val hazeStyle=GlassStyle(true,0,0,0,0,null,surfaceScope=if(transformed)GlassSurface.BANNERS else GlassSurface.LIVE)
        val fogStrength=WallpaperGlow.amount(hazeStyle)
        val economy=com.cosmod.launcherglass.util.PerformancePolicy.economy
        val fogRadius=if(fogStrength>0 && !economy)edgeFogRadius(prefs,min(w,h))else 0f
        val rounding=prefs.getInt("island_glass_corner",30).coerceIn(0,50)/100f
        val edge=min(w,h)
        val density=view.resources.displayMetrics.density
        val radius=blur.coerceIn(0,50)*scale*density*.6f
        val hue=com.cosmod.launcherglass.ui.GlassDispersionSettings.strength(prefs,com.cosmod.launcherglass.ui.GlassDispersionSettings.ALERTS)
        val warpReach=edge*(if(shape!=null).25f
            else .22f*distortion.coerceIn(0,300)/50f)
        // Blur support is three radii. The lens also needs room to bend the
        // source and sample its sharpness kernel without hitting the node edge.
        val margin=ceil(warpReach+3f*radius+fogRadius+maxOf(4f,3f*scale*density)).toInt()
        val wrapperW=w+margin*2;val wrapperH=h+margin*2
        val key=listOf<Any>(w,h,margin,corner,lensWidth,distortion,blur,rounding,WallpaperGlow.revision,economy,
            shape?.revision ?: -1,hue)
        if(plate.key!=key){
            plate.mask.setFloatUniform("size",w.toFloat(),h.toFloat());plate.mask.setFloatUniform("radius",corner*scale)
            val style=GlassStyle(true,lensWidth,distortion,0,0,null,extendedLens=true,dispersion=hue)
            val mask=if(shape==null)plate.mask else {
                val size=(w.toLong() shl 32) or h.toLong()
                if(plate.shapeVersion!=shape.revision || plate.shapeSize!=size){
                    val bitmap=Bitmap.createBitmap(w*2,h*2,Bitmap.Config.ALPHA_8)
                    val c=Canvas(bitmap);c.scale(2f*w/bounds.width(),2f*h/bounds.height());c.translate(-bounds.left,-bounds.top)
                    c.drawPath(shape.path,Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE})
                    for(cornerIndex in plate.cornerRadii.indices){
                        val right=cornerIndex==1 || cornerIndex==2
                        val bottom=cornerIndex>=2
                        var low=0
                        var high=min(w,h)-1
                        while(low<high){
                            val mid=(low+high)/2
                            val x=if(right)bitmap.width-1-mid else mid
                            val y=if(bottom)bitmap.height-1-mid else mid
                            if(Color.alpha(bitmap.getPixel(x,y))>=128)high=mid else low=mid+1
                        }
                        plate.cornerRadii[cornerIndex]=(low*1.7071068f).coerceAtMost(min(w,h)*.5f)
                    }
                    plate.shapeMask=BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
                        setFilterMode(BitmapShader.FILTER_MODE_LINEAR);setLocalMatrix(Matrix().apply {setScale(.5f,.5f)})
                    }
                    plate.shapeVersion=shape.revision;plate.shapeSize=size
                }
                plate.shapeMask!!
            }
            val shader=LiquidGlassShader.lifted()?.updateLifted(mask,w,h,margin.toFloat(),wrapperW,wrapperH,null,rounding,style,
                contourRadii=plate.cornerRadii.takeIf {shape!=null}) ?: return false
            var effect=RenderEffect.createRuntimeShaderEffect(shader,"backdrop")
            var sourceEffect=if(radius>0)RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP)else null
            if(fogStrength>0 && !economy){
                haze.setFloatUniform("spreadRadius",fogRadius)
                haze.setFloatUniform("strength",fogStrength)
                haze.setFloatUniform("threshold",prefs.getInt("wallpaper_glow_threshold",35).coerceIn(0,100)*.01f)
                haze.setFloatUniform("chroma",prefs.getInt("wallpaper_glow_saturation",100).coerceIn(0,200)*.01f)
                val veil=RenderEffect.createRuntimeShaderEffect(haze,"scene")
                sourceEffect=sourceEffect?.let {RenderEffect.createChainEffect(veil,it)} ?: veil
            }
            if(sourceEffect!=null)effect=RenderEffect.createChainEffect(effect,sourceEffect)
            plate.node.setRenderEffect(effect);plate.node.setPosition(0,0,wrapperW,wrapperH);plate.key=key
        }
        plate.screenMatrix.reset()
        view.transformMatrixToGlobal(plate.screenMatrix)
        val axes=plate.screenAxes
        axes[0]=1f;axes[1]=0f;axes[2]=0f;axes[3]=1f
        plate.screenMatrix.mapVectors(axes)
        if(axes[0]<=0f || axes[3]<=0f || abs(axes[1])>.001f || abs(axes[2])>.001f)return false
        val origin=plate.screenOrigin
        origin.fill(0f)
        plate.screenMatrix.mapPoints(origin)
        // getLocationOnScreen includes the window offset, but rounds to whole pixels.
        val screenX=position[0]+(origin[0]-origin[0].roundToInt())
        val screenY=position[1]+(origin[1]-origin[1].roundToInt())
        val left=margin+((area.left-screenX)/axes[0]-bounds.left)*scale
        val top=margin+((area.top-screenY)/axes[3]-bounds.top)*scale
        val right=left+area.width()*scale/axes[0]
        val bottom=top+area.height()*scale/axes[3]
        val source=plate.sourceRect
        if(plate.generation!=generation || plate.image!==image || plate.recordedWidth!=wrapperW ||
            plate.recordedHeight!=wrapperH || source.left!=left || source.top!=top ||
            source.right!=right || source.bottom!=bottom || !plate.node.hasDisplayList()){
            source.set(left,top,right,bottom)
            if(plate.image!==image || plate.sourceShader==null){
                plate.sourceShader=BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                }
            }
            plate.sourceMatrix.setScale(source.width()/image.width,source.height()/image.height)
            plate.sourceMatrix.postTranslate(source.left,source.top)
            plate.sourceShader?.setLocalMatrix(plate.sourceMatrix)
            val recording=plate.node.beginRecording(wrapperW,wrapperH)
            try {
                plate.paint.shader=plate.sourceShader
                recording.drawRect(0f,0f,wrapperW.toFloat(),wrapperH.toFloat(),plate.paint)
            }finally {
                plate.paint.shader=null
                plate.node.endRecording()
            }
            plate.generation=generation
            plate.image=image
            plate.recordedWidth=wrapperW
            plate.recordedHeight=wrapperH
        }
        plate.node.alpha=opacity/255f*prefs.getInt("island_glass_mix",100).coerceIn(0,100)/100f
        val save=canvas.save()
        try {canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/w,bounds.height()/h);canvas.translate(-margin.toFloat(),-margin.toFloat());canvas.drawRenderNode(plate.node)}
        finally {canvas.restoreToCount(save)}
        return true
    }
}
