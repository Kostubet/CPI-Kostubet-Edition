/*
 * Copyright 2025 Kyant
 * Licensed under the Apache License, Version 2.0
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * CPI adaptation of AndroidLiquidGlass's rounded-rectangle distance field,
 * circular lens profile and directional highlight for Android View/RenderNode.
 */
package com.cosmod.launcherglass.util

import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Parameter as P
import com.cosmod.launcherglass.ui.ShadeGlassSettings.Profile
import com.cosmod.launcherglass.xposed.KyantGlassEngine
import kotlin.math.ceil
import kotlin.math.roundToInt

@TargetApi(33)
internal class ShadeGlassRenderer {
    class Frame(val image: Bitmap, val area: Rect)

    private data class Geometry(val width: Int, val height: Int, val scale: Float, val density: Float,
        val radius: Float, val light: Boolean, val active: Boolean, val backdrop: Boolean)

    private val node = RenderNode("CPI.ShadeGlass")
    private val shader by lazy { RuntimeShader(SHADER) }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val recordedAt = RectF()
    private var profile: Profile? = null
    private var geometry: Geometry? = null
    private var frame: Frame? = null
    private var bitmapShader: BitmapShader? = null
    private var margin = 0

    fun clear() {
        node.discardDisplayList()
        node.setRenderEffect(null)
        geometry = null
        frame = null
        bitmapShader = null
    }

    fun draw(canvas: Canvas, bounds: RectF, screenBounds: RectF, source: Frame, material: Profile,
        density: Float, light: Boolean, active: Boolean = false, circular: Boolean = false,
        backdrop: Boolean = false): Boolean {
        if (!material.enabled || !canvas.isHardwareAccelerated || bounds.isEmpty || screenBounds.isEmpty ||
            !screenBounds.intersects(source.area.left.toFloat(),source.area.top.toFloat(),
                source.area.right.toFloat(),source.area.bottom.toFloat())) return false
        val scale = (source.image.width.toFloat() / source.area.width()).coerceIn(.22f, 1f)
        val w = (bounds.width() * scale).roundToInt().coerceAtLeast(1)
        val h = (bounds.height() * scale).roundToInt().coerceAtLeast(1)
        val corner = if (circular) minOf(w,h)*.5f else minOf(minOf(w,h)*.5f, material[P.CORNER]*density*scale)
        val next = Geometry(w,h,scale,density,corner,light,active,backdrop)
        if (profile !== material || geometry != next) {
            configure(next, material)
            node.discardDisplayList()
            geometry = next
            profile = material
        }
        val sx = w / screenBounds.width()
        val sy = h / screenBounds.height()
        val left = margin + (source.area.left - screenBounds.left) * sx
        val top = margin + (source.area.top - screenBounds.top) * sy
        val right = left + source.area.width() * sx
        val bottom = top + source.area.height() * sy
        if (frame !== source || !node.hasDisplayList() ||
            recordedAt.left != left || recordedAt.top != top || recordedAt.right != right || recordedAt.bottom != bottom) {
            if (frame !== source || bitmapShader == null) {
                bitmapShader = BitmapShader(source.image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                }
            }
            matrix.setScale((right-left)/source.image.width,(bottom-top)/source.image.height)
            matrix.postTranslate(left,top)
            val sampler = checkNotNull(bitmapShader)
            sampler.setLocalMatrix(matrix)
            val recording = node.beginRecording()
            try {
                paint.shader = sampler
                recording.drawRect(0f,0f,node.width.toFloat(),node.height.toFloat(),paint)
            } finally {
                paint.shader = null
                node.endRecording()
            }
            frame = source
            recordedAt.set(left,top,right,bottom)
        }
        val save = canvas.save()
        try {
            canvas.translate(bounds.left,bounds.top)
            canvas.scale(bounds.width()/w,bounds.height()/h)
            canvas.translate(-margin.toFloat(),-margin.toFloat())
            canvas.drawRenderNode(node)
        } finally {
            canvas.restoreToCount(save)
        }
        return true
    }

    private fun configure(g: Geometry, m: Profile) {
        val px = g.density*g.scale
        val isLiquid = !m.on(P.MATERIAL) && !g.backdrop
        val newEngine = isLiquid && m.on(P.NEW_ENGINE)
        val blur = if (m.on(P.BLUR)) m[P.RADIUS]*px else 0f
        val liquid = isLiquid && m.on(P.REFRACTION)
        val bevel = if (liquid) minOf(g.height*.5f,g.width*.5f,(2f+28*m.fraction(P.LENS))*px) else 0f
        val heightScale=m.engineOptics.heightScale
        val amount = if (liquid) heightScale*24*px*m.fraction(P.STRENGTH)*m.fraction(P.EDGE)*
            (if(newEngine)1.55f*m.engineOptics.power*.01f else 1f) else 0f
        val depth = m.fraction(P.DEPTH)
        margin = ceil(blur*3 + amount*2 + (6 + 8*depth)*px).toInt().coerceAtLeast(2)
        val width = g.width+margin*2
        val height = g.height+margin*2
        node.setPosition(0,0,width,height)
        shader.setFloatUniform("size",g.width.toFloat(),g.height.toFloat())
        shader.setFloatUniform("margin",margin.toFloat())
        shader.setFloatUniform("radius",g.radius)
        shader.setFloatUniform("aa",g.scale*.7f)
        shader.setFloatUniform("lens",bevel,amount,depth,
            if (liquid && heightScale>0f && m.on(P.DISPERSION)) m.fraction(P.DISPERSION_STRENGTH)*.48f else 0f)
        shader.setFloatUniform("tone",m.fraction(P.SATURATION),m.fraction(P.CONTRAST),m.fraction(P.BRIGHTNESS),m.fraction(P.OPACITY))
        shader.setFloatUniform("tintWeight",m.fraction(P.TINT))
        shader.setFloatUniform("light",if (g.light || g.active) 1f else 0f)
        shader.setFloatUniform("active",if (g.active) 1f else 0f)
        shader.setFloatUniform("backdropOnly",if (g.backdrop) 1f else 0f)
        shader.setFloatUniform("kyantEngine",if (newEngine) 1f else 0f)
        shader.setFloatUniform("engineGleam",m.engineOptics.edgeWidth*.01f,
            if(m.on(P.HIGHLIGHT))m.engineOptics.edgeLight*.01f else 0f)
        val color = if (m.on(P.AUTO_THEME)) {
            if (g.light || g.active) 0xffe7edfa.toInt() else 0xff202738.toInt()
        } else m[P.COLOR] or 0xff000000.toInt()
        shader.setColorUniform("tint",color)
        shader.setColorUniform("rimColor",m[P.HIGHLIGHT_COLOR] or 0xff000000.toInt())
        val highlights = if (m.on(P.HIGHLIGHT)) 1f else 0f
        shader.setFloatUniform("glint",highlights*m.fraction(P.HIGHLIGHT_BRIGHTNESS)*m.fraction(P.HIGHLIGHT_OPACITY),
            (.45f+3.6f*m.fraction(P.HIGHLIGHT_WIDTH))*px,highlights*m.fraction(P.RIM),highlights*m.fraction(P.REFLECTION))
        shader.setFloatUniform("shadow",m.fraction(P.INNER_SHADOW),m.fraction(P.OUTER_SHADOW),maxOf(1f,px*(2+6*depth)))
        var effect = RenderEffect.createRuntimeShaderEffect(shader,"scene")
        GlassBlur.effect(blur,m[P.BLUR_OPACITY])?.let { blurred ->
            effect = RenderEffect.createChainEffect(effect,blurred)
        }
        node.setRenderEffect(effect)
    }

    private companion object {
        const val SHADER = KyantGlassEngine.AGSL + """
            uniform shader scene;
            uniform float2 size;
            uniform float margin, radius, aa, tintWeight, light, active, backdropOnly, kyantEngine;
            uniform float4 lens, tone, glint;
            uniform float3 shadow;
            uniform float2 engineGleam;
            layout(color) uniform half4 tint, rimColor;

            float sdRoundedRect(float2 p,float2 extent,float r) {
                float2 q=abs(p)-(extent-r);
                return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r;
            }
            float2 gradient(float2 p,float2 extent,float r) {
                float2 q=abs(p)-(extent-r);
                float2 outwards=max(q,0.0);
                if (length(outwards)>.0001) return sign(p)*normalize(outwards);
                float x=step(q.y,q.x);
                return sign(p)*float2(x,1.0-x);
            }
            half4 main(float2 coord) {
                if (backdropOnly>.5) {
                    float2 local=coord-margin;
                    if (local.x<0.0 || local.y<0.0 || local.x>=size.x || local.y>=size.y) return half4(0.0);
                    float3 sceneColor=float3(scene.eval(coord).rgb);
                    sceneColor=tone.z<=1.0 ? sceneColor*tone.z : mix(sceneColor,float3(1.0),tone.z-1.0);
                    return half4(half3(sceneColor*(1.0-tone.w)),1.0);
                }
                float2 halfSize=size*.5;
                float2 p=coord-margin-halfSize;
                float d=sdRoundedRect(p,halfSize,radius);
                if (d>aa) {
                    float s=sdRoundedRect(p-float2(0.0,shadow.z*.45),halfSize,radius);
                    float shade=shadow.y*.34*exp(-max(0.0,s)/shadow.z);
                    return half4(0.0,0.0,0.0,half(shade));
                }
                float2 normal=gradient(p,halfSize,min(radius*1.5,min(halfSize.x,halfSize.y)));
                float bend=1.0-clamp(-d/max(lens.x,.01),0.0,1.0);
                float fall=kyantCircularProfile(bend);
                float2 face=normal+lens.z*p/max(length(p),1.0);
                if(kyantEngine>.5)face/=max(length(face),.0001);
                float2 shift=-lens.y*fall*face;
                float2 r=coord+shift;
                half3 rgb=scene.eval(r).rgb;
                if (lens.w>.0 && bend>.0) {
                    float2 spread=shift*lens.w*p.x*p.y/max(halfSize.x*halfSize.y,1.0);
                    rgb.r=scene.eval(r+spread).r;
                    rgb.b=scene.eval(r-spread).b;
                }
                float3 color=float3(rgb);
                float luma=dot(color,float3(.2126,.7152,.0722));
                color=mix(float3(luma),color,tone.x);
                color=clamp(((color-.5)*tone.y+.5)*tone.z,0.0,1.0);
                color=mix(color,float3(tint.rgb),tintWeight*tone.w);
                color=mix(color,mix(float3(.045,.052,.066),float3(.97),light),tone.w*.72);
                if (active>.5) color=mix(color,float3(.94),.74);
                float y=dot(color,float3(.2126,.7152,.0722));
                if (light<.5 && y>.34) color*=.34/y;
                if (light>.5 && y<.73) color=mix(color,float3(1.0),(.73-y)/max(.01,1.0-y));
                float edge=exp(min(0.0,d)/max(glint.y,.1));
                float direction=dot(normal,float2(-.48,-.877));
                float specular=kyantDirectionalHighlight(normal,1.36,1.25);
                float inner=exp(min(0.0,d)/shadow.z)*max(0.0,-direction);
                color*=1.0-shadow.x*.32*inner;
                if(kyantEngine>.5) {
                    float highlightWidth=max(aa,.3*glint.y+aa*3.0*engineGleam.x);
                    float boundary=1.0-smoothstep(.0,highlightWidth,-d);
                    float cap=max(0.0,-normal.y);
                    float floorLight=max(0.0,normal.y);
                    float reflection=(.09+.42*specular)*(.28+.72*cap+.28*floorLight);
                    color+=float3(rimColor.rgb)*boundary*engineGleam.y*(reflection*glint.x+glint.z*.15);
                    color+=float3(rimColor.rgb)*fall*cap*glint.w*engineGleam.y*.08;
                } else {
                    color=mix(color,float3(rimColor.rgb),clamp(edge*glint.z*.21,0.0,1.0));
                    color+=float3(rimColor.rgb)*edge*glint.x*specular*.65;
                }
                color+=float3(.14)*glint.w*inner*(1.0-color);
                float cover=1.0-smoothstep(-aa,aa,d);
                return half4(half3(clamp(color,0.0,1.0)*cover),half(cover));
            }
        """
    }
}
