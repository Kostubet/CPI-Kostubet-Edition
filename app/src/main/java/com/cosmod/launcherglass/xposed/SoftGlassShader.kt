// AGSL optics adapted from ONextBox 17.0.1.200 by MiToverG422 and contributors.
// SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.xposed

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import com.cosmod.launcherglass.ui.SoftGlassSettings.Parameter as P
import com.cosmod.launcherglass.ui.SoftGlassSettings.Profile
import kotlin.math.cos
import kotlin.math.sin

/** Extends only the ColorOS post-effect shader with its capture, mask and geometry intact. */
internal object SoftGlassShader {
    const val INPUT = "uniBDFBitmap"
    const val UNIFORM = "onbSoftGlass"
    private const val DECLARATION = "uniform float3 $UNIFORM;"
    private const val MAIN = "half4 main(float2 position)"
    private const val SAMPLE = "half4 outputCol = half4(uniBDFBitmap.eval(mapCoords).rgb, 1.0);"

    fun owns(source: String) = source.startsWith(DECLARATION)

    fun extend(source: String): String? {
        if (owns(source)) return null
        val main = source.indexOf(MAIN)
        if (main < 0 || "uniform shader $INPUT;" !in source || "uniform float u_commonArray[8];" !in source) return null
        val sample = source.indexOf(SAMPLE, main)
        if (sample < 0 || source.indexOf("ShapeResult shapeResult =", sample) < 0) return null
        val masked = source.lastIndexOf("return outputCol*shape;") > sample
        val finish = source.lastIndexOf(if (masked) "return outputCol*shape;" else "return outputCol;")
        if (finish <= sample) return null
        return buildString(source.length + OPTICS.length + 900) {
            appendLine(DECLARATION)
            appendLine("""
                uniform float4 onbOptics;
                uniform float3 onbToneControls;
                uniform float onbKeepWhite;
                uniform float2 onbTint, onbLampOffset;
                uniform float onbDimming, onbSelectionState;
                uniform float2 onbPanelSurface;
                const bool onbLauncher = false;
                const float onbPreviewFolder = 0.0;
            """.trimIndent())
            append(source, 0, main)
            appendLine(OPTICS)
            append(source, main, sample)
            appendLine("half4 onbCapture = uniBDFBitmap.eval(mapCoords);")
            appendLine("half4 outputCol = half4(onbCapture.rgb, 1.0);")
            append(source, sample + SAMPLE.length, finish)
            val shape = if (masked) "u_commonArray[6] > .5" else "false"
            appendLine("""
                if (onbSoftGlass.x > 0.0 && onbOptics.x > 0.0 && outputCol.a > .001 &&
                    min(u_size.x,u_size.y)>3.0 && ($shape || onbPanelSurface.x>=2.0) &&
                    (onbPanelSurface.x==4.0 || onbPanelSurface.x==5.0 || u_size.x<.94*onbSoftGlass.y || u_size.y<.82*onbSoftGlass.z)) {
                    outputCol.rgb = outputCol.a * onbMaterial(outputCol.rgb/outputCol.a, onbCapture,
                        position, mapCoords, matrixScale, shapeResult.sdf, $masked);
                }
            """.trimIndent())
            append(source, finish, source.length)
        }
    }

    /** Retains the input and uniforms while ColorOS redraws the same sampled geometry. */
    @RequiresApi(33)
    class Plate {
        val shader = RuntimeShader(activitySource())
        private var bitmap: Bitmap? = null
        private var input: BitmapShader? = null
        private val mapping = Matrix()
        private val shape = FloatArray(8)
        private var profile: Profile? = null
        private var role = 0f
        private var dark = false
        private var density = 0f
        private var screenWidth = 0
        private var screenHeight = 0
        private var lightX = 0f
        private var lightY = 0f

        fun apply(profile: Profile, role: Float, dark: Boolean, metrics: DisplayMetrics, x: Float, y: Float) {
            if (this.profile == profile && this.role == role && this.dark == dark &&
                density == metrics.density && screenWidth == metrics.widthPixels && screenHeight == metrics.heightPixels) {
                if (lightX != x || lightY != y) applyLight(shader, profile, x, y)
            } else {
                applyProfile(shader, profile, role, dark, metrics.density,
                    metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat(), x, y, fullMix = true)
                this.profile = profile
                this.role = role
                this.dark = dark
                density = metrics.density
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
            }
            lightX = x
            lightY = y
        }

        fun bindImage(image: Bitmap, transform: Matrix) {
            if (bitmap === image && mapping == transform) return
            val source = if (bitmap === image) checkNotNull(input) else GlassGeometry.wallpaperShader(image)
            source.setLocalMatrix(transform)
            shader.setInputShader(INPUT, source)
            bitmap = image
            input = source
            mapping.set(transform)
        }

        fun clearImage() {
            if (bitmap == null) return
            shader.setInputShader(INPUT, emptyInput)
            input = null
            bitmap = null
        }

        fun setShape(width: Float, height: Float, radius: Float) {
            val halfWidth = width * .5f
            val halfHeight = height * .5f
            if (shape[2] == halfWidth && shape[3] == halfHeight && shape[6] == radius) return
            shape[2] = halfWidth
            shape[3] = halfHeight
            shape[4] = halfWidth
            shape[5] = halfHeight
            shape[6] = radius
            shader.setFloatUniform("u_size", width, height)
            shader.setFloatUniform("u_commonArray", shape)
        }
    }

    private val emptyInput by lazy {
        LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    @RequiresApi(33)
    fun applyProfile(shader: RuntimeShader, profile: Profile, role: Float, dark: Boolean, density: Float,
        screenWidth: Float, screenHeight: Float, x: Float = 0f, y: Float = 0f, fullMix: Boolean = false) {
        shader.setFloatUniform("onbPanelSurface", role, if (dark) .30f else .63f)
        shader.setFloatUniform("onbOptics", if (fullMix) 1f else profile[P.STRENGTH], profile[P.BLUR], profile[P.WIDTH], profile[P.SHINE])
        shader.setFloatUniform("onbToneControls", profile[P.REFRACTION], profile[P.SATURATION], profile[P.EXPOSURE])
        shader.setFloatUniform("onbTint", profile[P.WARMTH], profile[P.CONTRAST])
        shader.setFloatUniform("onbDimming", profile[P.DIMMING])
        shader.setFloatUniform("onbKeepWhite", if (profile.white) 1f else 0f)
        shader.setFloatUniform("onbSelectionState", if (role == 1f) 1f else 0f)
        applyLight(shader, profile, x, y)
        shader.setFloatUniform(UNIFORM, density, screenWidth, screenHeight)
    }

    @RequiresApi(33)
    private fun applyLight(shader: RuntimeShader, profile: Profile, x: Float, y: Float) {
        val angle = Math.toRadians(profile.value(P.ANGLE).toDouble())
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        shader.setFloatUniform("onbLampOffset", .4f * (1f - c) + .6f * s + x * profile[P.RESPONSE],
            .6f * (1f - c) - .4f * s + y * profile[P.RESPONSE])
    }

    // Older expanded cards use the compositor's BackgroundBlurDrawable instead of the
    // post-effect drawable. Sample CPI's wallpaper layer with the same optical function.
    fun activitySource() = """
        uniform float3 onbSoftGlass;
        uniform float4 onbOptics;
        uniform float3 onbToneControls;
        uniform float onbKeepWhite;
        uniform float2 onbTint, onbLampOffset;
        uniform float onbDimming, onbSelectionState;
        uniform float2 onbPanelSurface;
        uniform shader uniBDFBitmap;
        uniform float2 u_size;
        uniform float u_commonArray[8];
        const float3x3 uMatrix = float3x3(1.0);
        const int uEnableMetaBall = 0;
        const bool onbLauncher = false;
        const float onbPreviewFolder = 0.0;
        $OPTICS
        half4 main(float2 p) {
            float r = u_commonArray[6];
            float2 q = abs(p - u_size * .5) - u_size * .5 + r;
            float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            half4 capture = uniBDFBitmap.eval(p);
            half3 stock = capture.rgb / max(.001, capture.a);
            return half4(onbMaterial(stock, capture, p, p, float2(1.0), sdf, true), 1.0);
        }
    """.trimIndent()

    const val HEIGHT_PROFILE = """
        float onbHeight(float x, float power) {
            float t = .5+.5*sqrt(clamp(x,0.0,1.0));
            float smoothHeight = 2.0*t*t*t*(t*(t*6.0-15.0)+10.0)-1.0;
            return 1.0-pow(clamp(1.0-smoothHeight,0.0,1.0),power);
        }

    """
    private const val OPTICS = HEIGHT_PROFILE + """
        float3 onbTone(float3 color, float4 curve, float amount, float saturation, float brightness) {
            float3 rgb = toLinearSrgb(clamp(color,0.0,1.0));
            float3 cone = pow(max(float3(dot(rgb,float3(.412221,.536333,.051446)),
                dot(rgb,float3(.211903,.680700,.107397)),dot(rgb,float3(.088302,.281719,.629979))),0.0),
                float3(1.0/3.0));
            float l = dot(cone,float3(.210454,.793618,-.004072));
            float t = clamp(l,0.0,1.0);
            float3 m = mix(curve.xyz,curve.yzw,t);
            float2 n = mix(m.xy,m.yz,t);
            cone += amount*(mix(n.x,n.y,t)-l);
            cone = cone*cone*cone;
            rgb = fromLinearSrgb(max(float3(dot(cone,float3(4.076742,-3.307712,.230970)),
                dot(cone,float3(-1.268438,2.609757,-.341319)),dot(cone,float3(-.004196,-.703419,1.707615))),0.0));
            return mix(float3(dot(rgb,float3(.2126,.7152,.0722))),rgb,saturation)+brightness;
        }

        float3 onbSample(float2 uv, float2 scale, float2 offset, float3 center) {
            float2 sampleAt = uv+(uMatrix*float3(offset/scale/scale,0.0)).xy;
            half4 sampleColor = uniBDFBitmap.eval(sampleAt);
            return mix(center, sampleColor.rgb/max(.001,sampleColor.a),smoothstep(.0,.8,sampleColor.a));
        }

        half3 onbMaterial(half3 stock, half4 capture, float2 p, float2 uv, float2 scale, float sdf, bool rounded) {
            float density = onbSoftGlass.x;
            float shortSide = min(u_size.x,u_size.y);
            bool workspace = onbLauncher && onbPreviewFolder > .5;
            bool widget = workspace && onbPreviewFolder > 1.5;
            bool folder = workspace && !widget;
            bool slider = !onbLauncher && u_size.y > u_size.x*1.65 && u_size.x < 110.0*density;
            bool volumeTrack = onbPanelSurface.x == 2.0 && slider;
            bool volumeMore = onbPanelSurface.x == 6.0;
            bool sideVolumeGlass = volumeTrack || volumeMore;
            bool externalPanel = onbPanelSurface.x == 4.0 || onbPanelSurface.x == 5.0 || onbPanelSurface.x == 7.0;
            float fill = min(min(stock.r,stock.g),stock.b);
            if (onbKeepWhite > .5 && (onbSelectionState > 1.5 ||
                (onbSelectionState > .5 && fill > .70 && max(max(stock.r,stock.g),stock.b)-fill < .17))) return stock;
            float showMaterial = (1.0-smoothstep(.82,.97,fill))*smoothstep(.0,.85,capture.a);
            if (showMaterial < .001) return stock;
            float3 color = capture.rgb/max(.001,capture.a);
            bool diffused = onbPanelSurface.x > .5 || widget;
            float diffusion = diffused ? onbOptics.y : onbOptics.y-1.0;
            if (diffusion > 0.0) {
                float radius = widget ? 12.0 : (onbPanelSurface.x == 2.0 || volumeMore ? 23.0 : (onbPanelSurface.x == 4.0 ? 26.0 : 17.0));
                float spread = min(.28*shortSide,radius*density)*diffusion;
                float2 axis = float2(.895,.447)*spread;
                float2 across = float2(-axis.y,axis.x);
                color = .20*(color + onbSample(uv,scale,axis,color) + onbSample(uv,scale,-axis,color)
                    + onbSample(uv,scale,across,color) + onbSample(uv,scale,-across,color));
            }
            float3 original = color;
            float edge = min((workspace?16.7:(slider?20.0:24.0))*density,.45*shortSide);
            if (sideVolumeGlass) edge = min(9.0*density,.18*shortSide);
            edge = min(edge*onbOptics.z,.49*shortSide);
            float thickness = min((workspace?33.3:26.7)*density,.5*shortSide);
            float inset = -sdf;
            float shine = 0.0;
            float stockShine = 0.0;
            if (rounded && uEnableMetaBall == 0 && inset >= 0.0 && inset < edge &&
                u_commonArray[6] > 0.0 && (onbLauncher || onbToneControls.x > 0.0 || onbOptics.w > 0.0)) {
                float2 halfSize = max(float2(u_commonArray[4],u_commonArray[5]),float2(1.0));
                float radius = min(u_commonArray[6],min(halfSize.x,halfSize.y));
                float2 pos = p-float2(u_commonArray[2],u_size.y-u_commonArray[3]);
                float2 q = abs(pos)-halfSize+radius;
                float2 corner = max(q,0.0);
                float2 direction = length(corner) > .001 ? normalize(corner) :
                    (q.x>q.y ? float2(1,0) : float2(0,1));
                float depth = onbHeight(inset/edge,3.8);
                float slope = .5*edge*(onbHeight((inset+1.0)/edge,3.8)-onbHeight((inset-1.0)/edge,3.8));
                float3 normal = normalize(float3(direction*sign(pos)*slope,1.0));
                float2 reflectAt = normal.xy*(2.0*normal.z) * min((workspace?175.0:266.7)*density,2.25*shortSide)*(1.0-depth);
                float3 reflection = onbSample(uv,scale,reflectAt,original);
                if (onbToneControls.x > 0.0) {
                    float2 ray = refract(float3(0,0,-1),normal,slider||workspace ? .3333:.25).xy;
                    float2 transmit = ray*2.0*mix(max(thickness-edge,0.0),thickness,depth);
                    float reflectionWeight = pow(max(1.0-depth,0.0),1.0/3.8);
                    float3 centerSample = capture.rgb/max(.001,capture.a);
                    float3 refracted = onbSample(uv,scale,transmit,centerSample);
                    color += onbToneControls.x*(mix(refracted,reflection,(workspace?.38:.68)*reflectionWeight) - centerSample);
                }
                color = clamp(color,0.0,1.0);
                color += onbOptics.w * reflection * ((workspace?.13:.21)*(1.0-depth)) *
                    (.3+.7*clamp(1.9-2.15*dot(color,float3(.2126,.7152,.0722)),0.0,1.0));
                float3 lamp = normalize(float3(float2(-.4,-.6) + onbLampOffset,-.8));
                float2 light = max(float2(dot(normal,lamp),dot(normal,float3(-lamp.xy,lamp.z))),0.0);
                float2 arc = max(1.0-.602*sqrt(max(1.0-light,0.0)),0.0);
                shine = dot(light*arc,workspace?float2(1.8,1.0):float2(1.4,.7));
                if (onbLauncher && !workspace) {
                    float3 baseLamp = normalize(float3(-.4,-.6,-.8));
                    float2 baseLight = max(float2(dot(normal,baseLamp),dot(normal,float3(-baseLamp.xy,baseLamp.z))),0.0);
                    stockShine = dot(baseLight*(1.0-.602*sqrt(max(1.0-baseLight,0.0))),float2(1.4,.7));
                }
            }
            float luminance = dot(color,float3(.2126,.7152,.0722));
            if (workspace) {
                // Wallpaper-backed cards and folder previews share a neutral transmission profile.
                float3 transmitted = mix(float3(luminance),color,.90);
                float neutral = mix(.82,.32,smoothstep(.18,.80,luminance));
                color = mix(transmitted,float3(neutral),.13);
            } else if (onbPanelSurface.x == 3.0 || onbPanelSurface.x == 8.0) {
                float3 glass = onbTone(color,float4(0.0,2.0,.3,.4),.15,1.4,.08);
                color = mix(glass,float3(stock),.32);
            } else if (sideVolumeGlass || externalPanel) {
                // The side-key track already has a dark platform tint. Light the diffused
                // backdrop directly so the center keeps the wallpaper's hue.
                color += .10*(1.0-luminance)*(1.0-luminance);
            } else {
                // Control-center sliders share the panel's exposure, including their unfilled track.
                color = onbTone(color,float4(.67,.16,.09,0.0),.24,1.4,-.02);
                float peak = max(max(color.r,color.g),color.b);
                color *= 1.0-.12*smoothstep(.34,.82,peak);
            }
            // Keep state tints, including filled sliders, while replacing the neutral vendor blend.
            float stockChroma = max(max(stock.r,stock.g),stock.b)-min(min(stock.r,stock.g),stock.b);
            float captureChroma = max(max(original.r,original.g),original.b)-min(min(original.r,original.g),original.b);
            float stateTint = smoothstep(.18,.50,stockChroma-captureChroma);
            if (widget) {
                float3 weights = float3(.2126,.7152,.0722);
                color += .70*(dot(stock,weights)-dot(color,weights));
            }
            float3 body = externalPanel ? color : mix(color,float3(stock),(folder ? 0.0 : (slider || volumeMore ? .18 : .10))+.52*stateTint);
            if (onbPanelSurface.x > .5) {
                float localLuma = dot(body,float3(.2126,.7152,.0722));
                float diffuse = mix(onbPanelSurface.y,localLuma,.52);
                float coverage = onbPanelSurface.x == 2.0 || volumeMore ? .48 : (onbPanelSurface.x == 3.0 || onbPanelSurface.x == 8.0 ? .22 :
                    (externalPanel ? .20 : .34));
                float3 diffuseColor = float3(diffuse);
                // Keep the exposure of the frosted surface while transmitting its blurred backdrop hue.
                if (sideVolumeGlass || externalPanel) diffuseColor += .84*(body-localLuma);
                body = mix(body,diffuseColor,coverage*(1.0-.65*stateTint));
            }
            float y = clamp(dot(body,float3(.2126,.7152,.0722)),0.0,1.0);
            float ambient = smoothstep(0.0,.5,y);
            float weight = smoothstep(0.0,1.0,shine)*mix(.75,.4,ambient);
            float3 highlight = min((body+mix(1.0-y,y,ambient))*weight, float3(workspace?.115:.16));
            float3 baseHighlight = min((body+mix(1.0-y,y,ambient))*(smoothstep(0.0,1.0,stockShine)*mix(.75,.4,ambient)),float3(.16));
            body += onbOptics.w*highlight;
            float bottom = smoothstep(0.0,1.0,p.y/max(1.0,u_size.y));
            body += (workspace?.009:.018)*bottom*bottom;
            float3 weights = float3(.2126,.7152,.0722);
            if (onbLauncher && !workspace) {
                // Preserve the adaptive tint on search/menu surfaces at the default settings.
                body = mix(float3(stock),dot(stock,weights)+original-dot(original,weights),.08);
                body += (onbOptics.w-1.0)*highlight + (onbToneControls.x-1.0)*(color-original)*.12;
                body += highlight-baseHighlight;
            }
            body = mix(float3(dot(body,weights)),body,onbToneControls.y)+onbToneControls.z;
            body += (body-.5)*onbTint.y*.6;
            body += float3(.10,.015,-.10)*onbTint.x*(1.0-abs(2.0*y-1.0));
            body = mix(body, float3(onbDimming < 0.0 ? 1.0 : 0.0),
                abs(onbDimming) * (onbDimming < 0.0 ? .24 : .50));
            return half3(mix(stock,clamp(body,0.0,1.0),showMaterial*onbOptics.x));
        }
    """
}
