package com.cosmod.launcherglass.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.cosmod.launcherglass.util.BackdropVibrancy
import com.cosmod.launcherglass.util.GlassBlur
import com.cosmod.launcherglass.util.ShadowTone
import com.cosmod.launcherglass.util.VolumeRim
import com.cosmod.launcherglass.util.WallpaperHelper
import com.cosmod.launcherglass.util.PreviewIcons
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.FolderPreviewKind
import com.cosmod.launcherglass.xposed.GlassGeometry
import com.cosmod.launcherglass.xposed.GlassStyle
import com.cosmod.launcherglass.xposed.LiquidGlassShader
import java.util.concurrent.Executors

/** A small sample scene rendered with the same lens shader as the launcher. */
internal class GlassPreview(
    context: Context,
    private val dark: Boolean,
    useDeviceWallpaperColor: Boolean = true,
    private val showWallpaper: Boolean = true,
) : View(context) {
    data class Material(val enabled: Boolean, val liquid: Boolean, val blur: Int, val opacity: Int,
        val width: Int, val distortion: Int, val dispersion: Int, val corner: Int,
        val saturation: Int = 100, val vibrancy: Int = 0, val brightness: Int = 50,
        val tiles: Int = 1, val vertical: Boolean = false, val rimStrength: Int = 100,
        val iconPalette: IconPalette? = null, val plateColor: Int? = null, val backgroundFilter:ColorMatrixColorFilter? = null,
        val contentOpacity:Int=100,val fade:Int=0,val fadeHeight:Int=0,val fadeSmoothness:Int=90,
        val shade:Int=0,val banner:Boolean=false,val plateTint:Int=Color.TRANSPARENT,val plateFade:Int=0,
        val topDarkness:Int=0,val shadowStrength:Int=0,val shadowSize:Int=0,val elementFilter:android.graphics.ColorFilter?=null,
        val iconScale:Int=100,val rimWidth:Int=25,val rimSoftness:Int=65,val elementsFollowBackground:Boolean=false,
        val volumeHeight:Int=100)

    private var material: Material? = null
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF()
    private val volumeRim = VolumeRim()
    private val shadowNode=android.graphics.RenderNode("CPI.previewShadow").apply {setClipToBounds(false)}
    private val shadowOutline=android.graphics.Outline()
    private var shadowKey=""
    private val crop = RectF()
    private val source = RectF()
    private val bitmapMatrix = Matrix()
    private var icons: List<PreviewIcons.Icon> = emptyList()
    private var contentScene:NativePreviewScenes.Scene?=null
    private var folderScenes = emptyMap<FolderPreviewKind, NativePreviewScenes.Scene>()
    private var largeFolderMaterial: Material? = null
    private var widgetScene=false
    private var edgeScope=-1
    private var newEngine=false
    private var engineOptics=GlassEngineSettings.Optics()
    private var largeFolderEngine=false
    private var largeFolderOptics=GlassEngineSettings.Optics()
    private var shape:Bitmap?=null
    private var shapeKey=""
    private val bounds=android.graphics.Rect()
    fun setContentScene(scene:NativePreviewScenes.Scene?,widget:Boolean){contentScene=scene;widgetScene=widget;invalidate()}
    fun setFolderScenes(scenes: Map<FolderPreviewKind, NativePreviewScenes.Scene>) {
        if (folderScenes == scenes) return
        folderScenes = scenes
        invalidate()
    }

    fun setLargeFolderMaterial(profile: Material?) {
        if (largeFolderMaterial == profile) return
        largeFolderMaterial = profile
        if (profile == null) largeFolderSample.clear()
        invalidate()
    }
    fun configure(bundle:android.os.Bundle,scope:Int,opticsOverride:GlassEngineSettings.Optics?=null){
        edgeScope=scope
        val sourceScope=if(scope==com.cosmod.launcherglass.xposed.GlassSurface.FOLDERS &&
            !bundle.getBoolean(FolderSettingsKeys.OWN))0 else scope
        newEngine=com.cosmod.launcherglass.xposed.KyantGlassEngine.enabled(sourceScope,bundle)
        engineOptics=opticsOverride ?: com.cosmod.launcherglass.xposed.KyantGlassEngine.optics(sourceScope,bundle)
        largeFolderEngine=bundle.getBoolean(GlassEngineSettings.LARGE_FOLDERS,false)
        largeFolderOptics=GlassEngineSettings.optics(bundle,GlassEngineSettings.LARGE_FOLDERS)
        com.cosmod.launcherglass.xposed.EdgeLight.update(bundle)
        invalidate()
    }
    private var wallpaper: Bitmap? = null
    private val clip = android.graphics.Path()
    private val wallpaperColor = (if(useDeviceWallpaperColor)runCatching {
        WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
    }.getOrNull() else null) ?: if(useDeviceWallpaperColor)0xff3e4687.toInt()
        else if(dark)0xff333942.toInt() else 0xffbcc4d0.toInt()
    private var backdrop: Bitmap? = null
    private data class BlurSample(val radius:Int,val vibrancy:Int,val brightness:Int,val opacity:Int)
    private data class SampleRequest(val base: Bitmap, val sample: BlurSample, val generation: Int)
    private class SampleSlot {
        var desired: SampleRequest? = null
        var available: Bitmap? = null
        var failed: BlurSample? = null

        fun clear() {
            desired = null
            available = null
            failed = null
        }
    }

    private val samples=android.util.LruCache<BlurSample,Bitmap>(3)
    private val regularSample = SampleSlot()
    private val largeFolderSample = SampleSlot()
    private var generation = 0
    private var renderingSample: SampleRequest? = null
    private val optics by lazy { if (Build.VERSION.SDK_INT >= 33) Optics() else null }

    fun show(next: Material) {
        if (next == material) return
        material = next
        invalidate()
    }

    fun setScene(wallpaper: Bitmap?, icons: List<PreviewIcons.Icon>) {
        setWallpaper(wallpaper)
        if(this.icons===icons)return
        this.icons = icons
        invalidate()
    }

    fun setWallpaper(wallpaper: Bitmap?) {
        if (this.wallpaper === wallpaper) return
        this.wallpaper = wallpaper
        rebuildBackdrop()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 18 * density, 18 * density,
            android.graphics.Path.Direction.CW)
        rebuildBackdrop()
    }

    private fun rebuildBackdrop() {
        if (width <= 0 || height <= 0) return
        val sw = width.coerceAtMost(540)
        val sh = (height * sw / width).coerceAtLeast(1)
        backdrop = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val image = wallpaper?.takeUnless(Bitmap::isRecycled)?.let(WallpaperHelper::forSoftwareCanvas)
            if (image != null) {
                val scale = maxOf(sw.toFloat() / image.width, sh.toFloat() / image.height)
                val insetX = (sw - image.width * scale) * .5f
                val insetY = (sh - image.height * scale) * .5f
                paint.color = Color.WHITE
                canvas.drawBitmap(image, null, RectF(insetX, insetY, sw - insetX, sh - insetY), paint)
            } else {
                canvas.drawColor(ColorUtils.blendARGB(wallpaperColor, 0xff101828.toInt(), .4f))
            }
        }
        clearSamples()
    }

    private fun clearSamples() {
        generation++
        regularSample.clear()
        largeFolderSample.clear()
        samples.evictAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clearSamples()
    }

    override fun onDraw(canvas: Canvas) {
        val base = backdrop ?: return
        val current = material ?: return
        target.set(0f, 0f, width.toFloat(), height.toFloat())
        val saved = canvas.save()
        canvas.clipPath(clip)
        paint.color = Color.WHITE
        if (showWallpaper) canvas.drawBitmap(base, null, target, paint)
        if (folderScenes.isNotEmpty()) {
            drawFolders(canvas, base, current)
            canvas.restoreToCount(saved)
            return
        }
        val input = blurred(base, current)
        val filter = current.backgroundFilter
        val iconScene = icons.isNotEmpty()
        val tiles = if (iconScene) minOf(current.tiles, 3, icons.size) else current.tiles
        val slotWidth=if(iconScene)(width-24*density)/tiles else width*.72f
        val gap=12*density
        val ratios=if(iconScene)List(tiles){i->icons.getOrNull(i)?.source?.let {it.width.toFloat()/it.height}?.coerceIn(.33f,3f) ?: 1f}else emptyList()
        val iconInset = if (showWallpaper) 24 * density else 12 * density
        val baseHeight = if (ratios.isEmpty()) 0f else
            minOf(52 * density, height * (if (showWallpaper) .68f else .90f),
                (width - iconInset - gap * (tiles - 1)) / (ratios.sum() * 1.3f))
                .coerceAtLeast(0f)
        val iconHeight=baseHeight*current.iconScale/100f
        var iconLeft=(width-iconHeight*ratios.sum()-gap*(tiles-1))*.5f
        for(index in 0 until tiles){
            val icon=if(iconScene)icons.getOrNull(index)else null
            val ratio=(icon?.source ?: contentScene?.content)?.let {it.width.toFloat()/it.height}?.coerceIn(.08f,10f) ?: 1f
            val maxHeight=if(current.vertical)height*.52f*current.volumeHeight/100f else minOf(height*.60f,52*density)
            val sceneWidth = if (current.banner) width * .94f else width * .91f
            val itemW=if(iconScene)iconHeight*ratio else if(current.vertical)minOf(30*density,width*.55f)
                else if(contentScene!=null)minOf(sceneWidth,height*.84f*ratio) else slotWidth
            val itemH=if(iconScene)iconHeight else if(current.vertical)maxHeight
                else if(contentScene!=null)itemW/ratio else minOf(maxHeight,itemW)
            val centerX=if(iconScene)iconLeft+itemW*.5f else width*.5f
            iconLeft+=itemW+gap
            val top=(height-itemH)*.5f
            crop.set(centerX-itemW*.5f,top,centerX+itemW*.5f,top+itemH)
            source.set(crop.left*input.width/width,crop.top*input.height/height,crop.right*input.width/width,crop.bottom*input.height/height)
            if(icon==null){
                val scene = contentScene
                if (scene?.shape != null) {
                    drawNativeSurface(canvas, current, input, scene, scene.shape)
                    continue
                }
                if(current.shadowStrength>0 && current.shadowSize>0){
                    val radius=minOf(crop.width(),crop.height())*current.corner*.01f
                    if(canvas.isHardwareAccelerated){
                        val key="${crop.width()}:${crop.height()}:$radius:${current.shadowStrength}:${current.shadowSize}"
                        if(key!=shadowKey || !shadowNode.hasDisplayList()){
                            shadowNode.setPosition(crop.left.toInt(),crop.top.toInt(),crop.right.toInt(),crop.bottom.toInt())
                            shadowOutline.setRoundRect(0,0,shadowNode.width,shadowNode.height,radius);shadowOutline.alpha=1f;shadowNode.setOutline(shadowOutline)
                            shadowNode.elevation=current.shadowSize*density
                            val color=Color.argb(current.shadowStrength*255/100,0,0,0)
                            shadowNode.ambientShadowColor=color;shadowNode.spotShadowColor=color
                            shadowNode.beginRecording();shadowNode.endRecording();shadowKey=key
                        }
                        canvas.enableZ()
                        try {canvas.drawRenderNode(shadowNode)}finally {canvas.disableZ()}
                    }
                }
                drawPlate(canvas,current,input,filter)
                contentScene?.let {drawContent(canvas,current,it,filter)}
                drawEdges(canvas,current,null)
                if(widgetScene && !current.elementsFollowBackground)contentScene?.let {scene->
                    paint.color=Color.WHITE;paint.colorFilter=current.elementFilter
                    scene.text?.let {canvas.drawBitmap(it,null,crop,paint)}
                    scene.logos?.let {canvas.drawBitmap(it,null,crop,paint)}
                    paint.colorFilter=null
                }
            }else drawIcon(canvas,current,input,filter,icon)
        }
        canvas.restoreToCount(saved)
    }

    private fun blurred(base: Bitmap, current: Material): Bitmap {
        val large = current === largeFolderMaterial
        val slot = if (large) largeFolderSample else regularSample
        if (!current.enabled) {
            slot.clear()
            return base
        }
        val radius = if (current.banner) {
            (current.blur * base.width / resources.displayMetrics.widthPixels.toFloat()).toInt().coerceIn(0, 40)
        } else {
            (current.blur / if (current.liquid) 5 else 3).coerceIn(0, 18)
        }
        val optics=if(large)largeFolderOptics else engineOptics
        val opacity=optics.blur.opacity
        if ((radius == 0 || opacity == 0) && current.vibrancy == 0 && current.brightness == 50) {
            slot.clear()
            return base
        }
        val key=BlurSample(radius,current.vibrancy,current.brightness,opacity)
        if (slot.desired?.sample != key) {
            slot.desired = SampleRequest(base, key, generation)
            slot.failed = null
        }
        samples.get(key)?.let {
            slot.available = it
            return it
        }
        startSample()
        return slot.available ?: base
    }

    private fun startSample() {
        if (renderingSample != null || !isAttachedToWindow) return
        val regular = pendingSample(regularSample)
        val slot = if (regular != null) regularSample else largeFolderSample
        val request = regular ?: pendingSample(largeFolderSample) ?: return
        renderingSample = request
        sampleExecutor.execute {
            val bitmap = runCatching { prepareSample(request.base, request.sample) }.getOrNull()
            main.post {
                renderingSample = null
                if (generation == request.generation) {
                    if (bitmap == null) {
                        if (regularSample.desired == request) regularSample.failed = request.sample
                        if (largeFolderSample.desired == request) largeFolderSample.failed = request.sample
                    } else {
                        samples.put(request.sample, bitmap)
                        if (slot.desired != null) slot.available = bitmap
                        if (regularSample.desired == request) regularSample.available = bitmap
                        if (largeFolderSample.desired == request) largeFolderSample.available = bitmap
                        invalidate()
                    }
                    // Resolve the next recipe from a new draw, after that frame has read both profiles.
                    if (bitmap == null && (pendingSample(regularSample) != null ||
                            pendingSample(largeFolderSample) != null)) invalidate()
                } else {
                    if (bitmap != null && bitmap !== request.base) bitmap.recycle()
                    if (isAttachedToWindow) invalidate()
                }
            }
        }
    }

    private fun pendingSample(slot: SampleSlot): SampleRequest? = slot.desired?.takeIf {
        it.sample != slot.failed && samples.get(it.sample) == null
    }

    private fun prepareSample(base: Bitmap, key: BlurSample): Bitmap {
        val vivid=BackdropVibrancy.applyCpu(base,key.vibrancy)
        var blurred=vivid
        var prepared:Bitmap?=null
        try {
            if(key.radius>0 && key.opacity>0)blurred=GlassBlur.bitmap(vivid,key.radius.toFloat(),key.opacity)
            return ShadowTone.applyCpu(blurred,key.brightness).also { prepared=it }
        } finally {
            if(blurred!==base && blurred!==vivid && blurred!==prepared)blurred.recycle()
            if(vivid!==base && vivid!==prepared)vivid.recycle()
        }
    }

    private companion object {
        val main = Handler(Looper.getMainLooper())
        val sampleExecutor = Executors.newSingleThreadExecutor { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "CPI preview blur")
        }
    }

    private fun drawFolders(canvas: Canvas, base: Bitmap, current: Material) {
        val gap = 10 * density
        val totalWidth = folderScenes.values.sumOf { it.content.width }
        val maxHeight = folderScenes.values.maxOf { it.content.height }
        val available = (width - gap * (folderScenes.size + 1)).coerceAtLeast(1f)
        val scale = minOf(available / totalWidth, height * .8f / maxHeight)
        var left = (width - (totalWidth * scale + gap * (folderScenes.size - 1))) / 2f
        for (kind in FolderPreviewKind.entries) {
            val scene = folderScenes[kind] ?: continue
            val shape = scene.shape ?: continue
            val profile = if (kind == FolderPreviewKind.LARGE) largeFolderMaterial ?: current else current
            val itemWidth = scene.content.width * scale
            val itemHeight = scene.content.height * scale
            val top = (height - itemHeight) / 2f
            crop.set(left, top, left + itemWidth, top + itemHeight)
            val input = blurred(base, profile)
            source.set(crop.left * input.width / width, crop.top * input.height / height,
                crop.right * input.width / width, crop.bottom * input.height / height)
            drawNativeSurface(canvas, profile, input, scene, shape)
            left += itemWidth + gap
        }
    }

    private fun drawNativeSurface(
        canvas: Canvas, current: Material, input: Bitmap, scene: NativePreviewScenes.Scene, mask: Bitmap,
    ) {
        val plate = canvas.saveLayer(crop, null)
        try {
            drawPlate(canvas, current, input, current.backgroundFilter, mask)
            paint.color = Color.WHITE
            paint.colorFilter = null
            canvas.drawBitmap(scene.content, null, crop, paint)
            paint.blendMode = android.graphics.BlendMode.DST_IN
            canvas.drawBitmap(mask, null, crop, paint)
        } finally {
            paint.blendMode = null
            canvas.restoreToCount(plate)
        }
        drawEdges(canvas, current, mask)
    }

    private fun drawIcon(canvas: Canvas, current: Material, input: Bitmap,
                         filter: ColorMatrixColorFilter?, icon: PreviewIcons.Icon) {
        val palette = current.iconPalette
        paint.color = Color.WHITE
        if (!current.enabled && palette?.custom != true && palette?.whiteGlyph != true) {
            canvas.drawBitmap(icon.source, null, crop, paint)
            return
        }
        val mask=if(current.enabled && current.liquid)GlassGeometry.refinedMask(icon.layers.plateMaskBitmap)
            else icon.layers.plateMaskBitmap
        val saved = canvas.saveLayer(crop, null)
        try {
            if (current.enabled) drawPlate(canvas, current, input, filter, mask)
            else {
                paint.color = palette?.background ?: icon.layers.bgColor
                canvas.drawRect(crop, paint)
            }
            paint.color = Color.WHITE
            paint.blendMode = android.graphics.BlendMode.DST_IN
            canvas.drawBitmap(mask, null, crop, paint)
            paint.blendMode = null
        } finally {
            paint.blendMode = null
            canvas.restoreToCount(saved)
        }
        paint.colorFilter = if (palette?.custom == true || palette?.whiteGlyph == true) palette.filter else null
        canvas.drawBitmap(icon.layers.glyphBitmap, null, crop, paint)
        paint.colorFilter = null
        drawEdges(canvas,current,mask)
    }

    private fun drawPlate(canvas: Canvas, current: Material, input: Bitmap, filter: ColorMatrixColorFilter?,
                          nativeMask: Bitmap? = null) {
        val rounding = minOf(crop.width(), crop.height()) *
            (nativeMask?.let(GlassGeometry::rounding) ?: (current.corner * .01f))
        val tint = current.plateColor ?: current.iconPalette?.takeIf { it.custom }?.background ?: if (dark) Color.BLACK else Color.WHITE
        paint.color = Color.WHITE
        if (current.enabled) {
            val large=current===largeFolderMaterial
            val selectedOptics=if(large)largeFolderOptics else engineOptics
            val backdropFilter=com.cosmod.launcherglass.xposed.BackdropBrightness.filter(filter,selectedOptics.backdropBrightness)
            val shader = if (current.liquid && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated) {
                optics?.shader(input,source,crop.width().toInt(),crop.height().toInt(),rounding,backdropFilter,current,nativeMask,
                    if(large)largeFolderEngine else newEngine,selectedOptics)
            } else null
            if (shader != null) {
                val save = canvas.save()
                canvas.translate(crop.left, crop.top)
                paint.shader = shader
                canvas.drawRect(0f, 0f, crop.width(), crop.height(), paint)
                canvas.restoreToCount(save)
            } else {
                paint.shader = GlassGeometry.wallpaperShader(input).apply {
                    bitmapMatrix.setRectToRect(source, crop, Matrix.ScaleToFit.FILL)
                    setLocalMatrix(bitmapMatrix)
                }
                paint.colorFilter = backdropFilter
                if (nativeMask == null) canvas.drawRoundRect(crop, rounding, rounding, paint)
                else canvas.drawRect(crop, paint)
            }
            paint.shader = null
            paint.colorFilter = null
            paint.color = ColorUtils.setAlphaComponent(tint, current.opacity * 255 / 100)
            if (nativeMask == null) canvas.drawRoundRect(crop, rounding, rounding, paint) else canvas.drawRect(crop, paint)

            if(current.banner){
                val alpha=Color.alpha(current.plateTint);val rgb=current.plateTint and 0x00ffffff
                if(alpha>0){
                    paint.alpha=255
                    paint.shader=android.graphics.LinearGradient(0f,crop.top,0f,crop.bottom,
                        intArrayOf(current.plateTint,current.plateTint,rgb or ((alpha*(200-current.plateFade)/200) shl 24),rgb or ((alpha*(100-current.plateFade)/100) shl 24)),
                        floatArrayOf(0f,.45f,.725f,1f),Shader.TileMode.CLAMP)
                    canvas.drawRoundRect(crop,rounding,rounding,paint);paint.shader=null
                }
                if(current.topDarkness>0){
                    paint.shader=android.graphics.LinearGradient(0f,crop.top,0f,crop.top+crop.height()*.64f,
                        Color.argb(current.topDarkness*255/100,0,0,0),Color.TRANSPARENT,Shader.TileMode.CLAMP)
                    canvas.drawRoundRect(crop,rounding,rounding,paint);paint.shader=null
                }
                paint.color=if(current.plateColor==0xff121214.toInt())0x38000000 else 0x20ffffff
                canvas.drawRoundRect(crop,rounding,rounding,paint)
                volumeRim.draw(canvas,crop,rounding,density*(.35f+current.rimWidth*.018f),current.rimStrength,current.rimSoftness)
            }

        } else {
            paint.color = if(dark)0xff333333.toInt() else 0xfff0f0f0.toInt()
            canvas.drawRoundRect(crop, rounding, rounding, paint)
        }
        if (current.vertical && current.enabled) {
            volumeRim.draw(canvas, crop, rounding, (density * .55f).coerceAtLeast(1f), current.rimStrength)
            return
        }
    }

    private fun drawContent(canvas:Canvas,current:Material,scene:NativePreviewScenes.Scene,filter:ColorMatrixColorFilter?){
        val save=canvas.saveLayer(crop,null)
        try {
            val clipping=android.graphics.Path().apply {val r=minOf(crop.width(),crop.height())*current.corner*.01f;addRoundRect(crop,r,r,android.graphics.Path.Direction.CW)}
            canvas.clipPath(clipping)
            paint.color=Color.WHITE;paint.colorFilter=if(widgetScene)filter else null
            canvas.drawBitmap(scene.content,null,crop,paint);paint.colorFilter=null
            if(widgetScene && !current.elementsFollowBackground){
                paint.blendMode=android.graphics.BlendMode.DST_OUT
                scene.text?.let {canvas.drawBitmap(it,null,crop,paint)}
                scene.logos?.let {canvas.drawBitmap(it,null,crop,paint)}
                paint.blendMode=null
            }
            if(widgetScene){
                paint.color=Color.WHITE;paint.alpha=current.contentOpacity*255/100
                // Text and glyphs are restored above the background-only effects.
                paint.blendMode=android.graphics.BlendMode.DST_IN;canvas.drawRect(crop,paint);paint.blendMode=null;paint.alpha=255
                bounds.set(crop.left.toInt(),crop.top.toInt(),crop.right.toInt(),crop.bottom.toInt())
                com.cosmod.launcherglass.xposed.PhotoFade.apply(canvas,bounds,current.fadeHeight,current.fade,current.fadeSmoothness)
            }
        }finally {paint.colorFilter=null;paint.blendMode=null;paint.alpha=255;canvas.restoreToCount(save)}
    }
    private fun drawEdges(canvas:Canvas,current:Material,mask:Bitmap?){
        if(edgeScope<0 || !current.enabled)return
        val w=crop.width().toInt().coerceAtLeast(1);val h=crop.height().toInt().coerceAtLeast(1)
        val key="$w:$h:${current.corner}"
        val image=mask ?: if(shapeKey==key)shape else Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also {
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE};val r=minOf(w,h)*current.corner*.01f
            Canvas(it).drawRoundRect(0f,0f,w.toFloat(),h.toFloat(),r,r,p);shape=it;shapeKey=key
        }
        image ?: return
        bounds.set(crop.left.toInt(),crop.top.toInt(),crop.right.toInt(),crop.bottom.toInt())
        if(widgetScene)com.cosmod.launcherglass.xposed.GlassFrame.draw(canvas,bounds,minOf(w,h)*current.corner*.01f,current.shade,0,current.rimStrength)
        if(edgeScope in 0 until EdgeSettings.count)
            com.cosmod.launcherglass.xposed.EdgeLight.draw(canvas,bounds,image,null,current.liquid,surface=edgeScope)
    }

    @RequiresApi(33)
    private class Optics {
        private val lens = LiquidGlassShader.create()
        private val mask = RuntimeShader("""
            uniform float2 size;
            uniform float radius;
            half4 main(float2 p) {
                float2 q = abs(p - size * .5) - (size * .5 - radius);
                float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
                return half4(1.0 - smoothstep(-.8, .4, d));
            }
        """.trimIndent())

        fun shader(bitmap: Bitmap, source: RectF, width: Int, height: Int, radius: Float,
            filter: ColorMatrixColorFilter?, material: Material, nativeMask: Bitmap?, newEngine:Boolean,
            engineOptics:GlassEngineSettings.Optics): Shader? {
            if (width <= 0 || height <= 0) return null
            mask.setFloatUniform("size", width.toFloat(), height.toFloat())
            mask.setFloatUniform("radius", radius)
            val style = GlassStyle(true, material.width, material.distortion, material.blur, material.opacity,
                filter, extendedLens = true, dispersion = material.dispersion, newEngine = newEngine,engineOptics=engineOptics)
            val shape = nativeMask?.let { image ->
                BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
                    setLocalMatrix(Matrix().apply { setScale(width.toFloat() / image.width, height.toFloat() / image.height) })
                }
            } ?: mask
            return lens?.update(bitmap, shape, source, width, height, filter, radius / minOf(width, height), style)
        }
    }
}
