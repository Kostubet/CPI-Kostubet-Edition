package com.cosmod.launcherglass.util



import android.app.WallpaperManager

import android.content.Context

import android.graphics.Bitmap

import android.graphics.BitmapFactory

import android.graphics.BitmapShader

import android.graphics.Canvas

import java.util.WeakHashMap

import android.graphics.Point

import android.os.Handler

import android.os.Looper

import android.os.SystemClock

import android.os.ParcelFileDescriptor

import android.provider.Settings

import java.util.concurrent.Executors

import android.graphics.Matrix

import android.graphics.Shader

import com.cosmod.launcherglass.xposed.PrefsHelper
import com.cosmod.launcherglass.xposed.FolderSettings
import com.cosmod.launcherglass.xposed.GlassSurface
import com.cosmod.launcherglass.xposed.KyantGlassEngine
import com.cosmod.launcherglass.xposed.NeutralBackdrop
import com.cosmod.launcherglass.xposed.PhotoWidgetMaterial
import com.cosmod.launcherglass.xposed.WidgetMaterialSettings
import com.cosmod.launcherglass.xposed.ColorDepthEffect
import com.cosmod.launcherglass.xposed.DrawerGlass

import java.io.File

import java.lang.ref.WeakReference

import kotlin.math.roundToInt



object WallpaperHelper {



    @Volatile

    var appContext: Context? = null



    @Volatile private var folderWallpaper:Bitmap?=null
    @Volatile private var widgetWallpaper:Bitmap?=null
    private data class PhotoSource(val raw:WeakReference<Bitmap>,val generation:Int,val height:Int,
                                   val radius:Float,val opacity:Int,val gpu:Boolean,val image:Bitmap)
    private var widgetSource:PhotoSource?=null
    @Volatile private var largeFolderWallpaper:Bitmap?=null
    private data class DrawerBlur(val revision: Long, val height: Int, val radius: Float, val opacity: Int)
    private var drawerBlur: DrawerBlur? = null
    private var drawerBackdrop: Bitmap? = null
    private var drawerRequestPending = false

    @Synchronized
    internal fun getDrawerBackdrop(context: Context, radiusPx: Float, opacity: Int): Bitmap? {
        val metrics = context.resources.displayMetrics
        val height = metrics.heightPixels.coerceIn(1, 1080)
        val blur = DrawerBlur(sourceRevision, height, radiusPx * height / metrics.heightPixels.coerceAtLeast(1), opacity)
        if (drawerBlur == blur) return drawerBackdrop
        if (drawerRequestPending) return drawerBackdrop
        drawerRequestPending = true
        val ctx = context.applicationContext ?: context
        loader.execute {
            val prepared = runCatching {
                val raw = synchronized(this) { wallpaperInput?.let { it.reuse(it.key) } ?: systemSnapshot }?.takeUnless { it.isRecycled }
                    ?: loadRawWallpaper(ctx) ?: return@runCatching null
                val h = minOf(raw.height, blur.height)
                val w = (raw.width.toLong() * h / raw.height).toInt().coerceAtLeast(1)
                val radius = blur.radius * h / blur.height
                if (radius <= 0f || blur.opacity == 0) return@runCatching Bitmap.createScaledBitmap(raw, w, h, true)
                runCatching { GpuWallpaperBlur.blurPhoto(raw, w, h, radius, blur.opacity) }.getOrElse {
                    val scaled = Bitmap.createScaledBitmap(raw, w, h, true)
                    GlassBlur.bitmap(forSoftwareCanvas(scaled) ?: return@runCatching null, radius, blur.opacity)
                }
            }.onFailure {
                GlassLogger.limited("DrawerGlass", "source") { "Drawer card sample unavailable: ${it.message}" }
            }.getOrNull()
            synchronized(this) {
                drawerRequestPending = false
                if (drawerBlur != blur && blur.revision == sourceRevision && DrawerGlass.categoriesEnabled) {
                    drawerBlur = blur
                    drawerBackdrop = prepared
                }
            }
            mainHandler.post { DrawerGlass.invalidateCards() }
        }
        return drawerBackdrop
    }

    @Synchronized
    internal fun clearDrawerBackdrop() {
        drawerBlur = null
        drawerBackdrop = null
    }

    private data class Backdrops(val icons:Bitmap?,val folder:Bitmap?,val widgets:Bitmap?,val largeFolder:Bitmap?,
        val neutral:NeutralBackdrop.Images){
        fun discard(){
            icons?.recycle()
            folder?.recycle()
            if(largeFolder!==folder)largeFolder?.recycle()
            neutral.discard()
        }
    }

    fun getWidgetWallpaper(context:Context):Bitmap? {
        val shared=getBlurredWallpaper(context)
        return widgetWallpaper?.takeUnless {it.isRecycled} ?: shared
    }

    private fun widgetBackdrop(raw:Bitmap,settings:WidgetMaterialSettings,heightLimit:Int):Bitmap? {
        if(!settings.enabled)return null
        val h=minOf(raw.height,PerformancePolicy.sourceHeight,1080,heightLimit)
        val w=(raw.width.toFloat()*h/raw.height).roundToInt().coerceAtLeast(1)
        val radius=settings.radius*(h/800f)*(if(settings.liquid).28f else 1f)
        val opacity=KyantGlassEngine.optics(GlassSurface.WIDGETS).blur.opacity
        val gpu=PrefsHelper.gpuEffects
        val generation=raw.generationId
        widgetSource?.takeIf {it.raw.get()===raw && it.generation==generation && it.height==h &&
            it.radius==radius && it.opacity==opacity && it.gpu==gpu && !it.image.isRecycled}?.let {return it.image}
        val accelerated=if(gpu)runCatching {GpuWallpaperBlur.blurPhoto(raw,w,h,radius,opacity)}.getOrNull()else null
        val image=accelerated ?: run {
            val scaled=Bitmap.createScaledBitmap(raw,w,h,true)
            val input=if(scaled.config==Bitmap.Config.HARDWARE)scaled.copy(Bitmap.Config.ARGB_8888,false)else scaled
            try {
                if(input==null)return null
                GlassBlur.bitmap(input,radius,opacity)
            }finally{
                if(input!=null && input!==raw && input!==scaled)input.recycle()
                if(scaled!==raw)scaled.recycle()
            }
        }
        widgetSource=PhotoSource(WeakReference(raw),generation,h,radius,opacity,gpu,image)
        return image
    }
    fun getFolderWallpaper(context:Context?=null,large:Boolean=false):Bitmap? {
        val shared=getBlurredWallpaper(context)
        if(large)largeFolderWallpaper?.takeUnless {it.isRecycled}?.let {return it}
        return if(FolderSettings.own)folderWallpaper?.takeUnless {it.isRecycled} ?: shared else shared
    }
    private fun publishBackdrops(candidate:Backdrops){
        largeFolderWallpaper=candidate.largeFolder
        blurredWallpaper=candidate.icons
        folderWallpaper=candidate.folder
        NeutralBackdrop.publish(candidate.neutral)
        if(candidate.widgets!==widgetWallpaper){
            widgetWallpaper=candidate.widgets
            mainHandler.post {PhotoWidgetMaterial.onBackdropChanged()}
        }
    }
    fun getNeutralWallpaper(context:Context?=null):Bitmap? {
        getBlurredWallpaper(context)
        return NeutralBackdrop.image
    }
    fun getIndicatorWallpaper(context:Context):Bitmap? {
        getBlurredWallpaper(context)
        return NeutralBackdrop.indicatorImage?.takeUnless {it.isRecycled}
    }

    private fun folderBackdrop(raw:Bitmap,heightLimit:Int,options:FolderSettings.Processing,opacity:Int):Bitmap? {
        val baseHeight=if(PerformancePolicy.economy)720 else 1080
        val h=minOf(baseHeight+options.quality*12,raw.height,PerformancePolicy.sourceHeight,heightLimit)
        val w=(raw.width.toFloat()*h/raw.height).roundToInt().coerceAtLeast(1)
        val radius=options.radius*(h/800f)*(if(options.liquid).28f else 1f)
        val gpuImage=if(PrefsHelper.gpuEffects)try {
            val input=if(android.os.Build.VERSION.SDK_INT<33)BackdropVibrancy.applyCpu(raw,options.vibrancy)else raw
            val gpu=try {GpuWallpaperBlur.blurFolder(input,w,h,radius,options.lightening,options.vibrancy,opacity)}finally{if(input!==raw)input.recycle()}
            if(ShadowTone.gpuSupported)gpu else ShadowTone.applyCpu(gpu,options.lightening).also {if(it!==gpu)gpu.recycle()}
        }catch(t:Throwable){
            GlassLogger.e("FolderMaterial","Folder GPU processing unavailable; using CPU",t)
            null
        }else null
        val image=gpuImage ?: run {
            val scaled=Bitmap.createScaledBitmap(raw,w,h,true)
            val software=if(scaled.config==Bitmap.Config.HARDWARE)scaled.copy(Bitmap.Config.ARGB_8888,false) ?: return null else scaled
            val vivid=BackdropVibrancy.applyCpu(software,options.vibrancy)
            val input=ColorDepthEffect.atCpu(vivid,ColorDepthEffect.BEFORE_BLUR)
            val actualRadius=radius
            val blurred=GlassBlur.bitmap(input,actualRadius,opacity)
            val toned=ShadowTone.applyCpu(blurred,options.lightening)
            val output=ColorDepthEffect.atCpu(toned,ColorDepthEffect.AFTER_BLUR)
            if(toned!==blurred && output!==toned)toned.recycle()
            if(output!==blurred)blurred.recycle()
            if(input!==vivid)input.recycle()
            if(vivid!==software)vivid.recycle()
            if(software!==raw)software.recycle()
            if(scaled!==raw && scaled!==software)scaled.recycle()
            output
        }
        return image
    }
    @Volatile private var blurredWallpaper: Bitmap? = null

    @Volatile

    private var cachedBlurRadius: Int = -1

    private val loader = Executors.newSingleThreadExecutor { runnable ->

        Thread(runnable, "CosmodWallpaper").apply { isDaemon = true }

    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var isReloading = false

    private var sourceRevision = 0L

    private var cachedRevision = -1L
    private var appearanceReloadAt = 0L

    private data class WallpaperInputKey(
        val wallpaperId: Int, val width: Int, val height: Int, val scroll: Float, val systemEffect: Boolean,
    )
    private class WallpaperInput(val key: WallpaperInputKey, bitmap: Bitmap) {
        private val image = WeakReference(bitmap)
        private val generation = bitmap.generationId

        fun reuse(key: WallpaperInputKey?): Bitmap? = image.get()?.takeIf {
            this.key == key && !it.isRecycled && generation == it.generationId
        }
    }
    private var wallpaperInput: WallpaperInput? = null
    private val reloadAppearanceBackdrop = Runnable {
        synchronized(this) { appContext?.let(::requestLoad) }
    }

    private var retryAfterMs = 0L

    private var launcherWallpaper: WeakReference<Bitmap>? = null

    @Volatile private var systemSnapshot: Bitmap? = null

    @Volatile private var snapshotTimeMs = 0L

    private var logCount = 0

    @Volatile private var liveFrameSignature: Long? = null

    private var livePublishSerial=0L

    private val softwareSnapshots = object:LinkedHashMap<Bitmap,Bitmap>(3,.75f,true){
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<Bitmap,Bitmap>?)=size>3
    }

    private var gpuFailureLogged = false



    @Volatile private var lastLiveFingerprint = 0L
    fun resetLiveFrameSignature() {
        lastLiveFingerprint = 0L
        liveFrameSignature = null
    }



    fun processLiveFrame(frame: Bitmap, valid:()->Boolean, finished: (Long?) -> Unit) {

        loader.execute {

            var signature: Long? = null

            var retained = false

            try {

                if (!valid() || !PrefsHelper.enabled || !PrefsHelper.wallpaperEffectsEnabled || (!LiveBlurEngine.isResumed && !com.cosmod.launcherglass.xposed.NativeSurfaceLive.active) ||

                    (LiveBlurEngine.isAppLaunchInProgress && !com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) return@execute

                val now = SystemClock.uptimeMillis()
                // The thumbnail reads GPU pixels back to the CPU. Only motion
                // detection needs that readback; rendering uses the current frame.
                if (now - lastLiveFingerprint >= 180L) {
                    lastLiveFingerprint = now
                    signature = runCatching {GpuWallpaperBlur.fingerprint(frame)}.onFailure {
                        GlassLogger.limited("WallpaperHelper","live-fingerprint") {"Live wallpaper motion probe unavailable"}
                    }.getOrNull()
                }

                val radius = PrefsHelper.blurRadius.coerceIn(0, 50)

                val revision = synchronized(this) { sourceRevision }

                if (signature == null || signature != liveFrameSignature || cachedBlurRadius != radius || cachedRevision != revision) {

                    val candidate = if (appContext != null) loadAndBlurWallpaper(radius, frame, live = true) else null

                    if (candidate != null) synchronized(this) {

                        if (valid() && revision == sourceRevision && radius == PrefsHelper.blurRadius.coerceIn(0, 50) &&

                            PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) {

                            systemSnapshot = frame

                            snapshotTimeMs = SystemClock.uptimeMillis()

                            retained = true

                            publishBackdrops(candidate)

                            cachedBlurRadius = radius

                            cachedRevision = revision

                            if (signature != null) liveFrameSignature = signature

                            livePublishSerial++

                            mainHandler.post {

                                if (PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || com.cosmod.launcherglass.xposed.NativeSurfaceLive.active)) {

                                    refreshLiveFrame()

                                }

                            }

                        } else candidate.discard()

                    }

                }

            } catch (t: Throwable) {

                GlassLogger.e("WallpaperHelper", "Failed to process live wallpaper frame", t)

            } finally {

                if (!retained && !frame.isRecycled) frame.recycle()

                finished(signature)

            }

        }

    }



    private fun refreshLiveFrame() {

        LiveBlurEngine.onWallpaperFrameReady()

    }



    fun forCanvas(bitmap: Bitmap, canvas: Canvas?): Bitmap {

        if (canvas == null || canvas.isHardwareAccelerated) return bitmap
        return forSoftwareCanvas(bitmap) ?: bitmap
    }

    internal fun forSoftwareCanvas(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled) return null
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        synchronized(softwareSnapshots){softwareSnapshots[bitmap]?.takeUnless {it.isRecycled}?.let {return it}}
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        synchronized(softwareSnapshots){softwareSnapshots[bitmap]=copy}
        return copy

    }



    @Volatile private var processingActive=false
    private var photoProcessingActive=false
    fun onProcessingSettingsChanged() {

        val active=PrefsHelper.enabled && PrefsHelper.wallpaperEffectsEnabled
        val photoActive=PrefsHelper.enabled && PrefsHelper.widgetGlassEnabled
        if(photoProcessingActive && !photoActive){
            widgetWallpaper=null
            loader.execute {
                widgetSource=null
                GpuWallpaperBlur.closePhoto()
            }
        }
        photoProcessingActive=photoActive
        if(processingActive && !active){
            synchronized(this){
                sourceRevision++
                cachedRevision=-1
                blurredWallpaper=null
                folderWallpaper=null
                largeFolderWallpaper=null
                widgetWallpaper=null
                systemSnapshot=null
                snapshotTimeMs=0L
                wallpaperInput=null
            }
            synchronized(softwareSnapshots){softwareSnapshots.clear()}
            loader.execute { GpuWallpaperBlur.close() }
        }
        processingActive=active

    }



    @Synchronized

    fun invalidate() {

        // Keep the last completed bitmap until its replacement is ready. Display

        // lists may still reference it, so never recycle a published wallpaper.

        sourceRevision++

        retryAfterMs = 0L
        appearanceReloadAt = 0L
        wallpaperInput = null
        mainHandler.removeCallbacks(reloadAppearanceBackdrop)

        appContext?.let { requestLoad(it) }
        if (android.os.Build.VERSION.SDK_INT >= 33 && DrawerGlass.categoriesEnabled)
            mainHandler.post { DrawerGlass.invalidateCards() }

    }



    @Synchronized
    fun invalidateForAppearance() {
        // Keep the currently published blur available during a burst of style changes.
        sourceRevision++
        retryAfterMs = 0L
        appearanceReloadAt = SystemClock.uptimeMillis() + 96L
        mainHandler.removeCallbacks(reloadAppearanceBackdrop)
        mainHandler.postAtTime(reloadAppearanceBackdrop, appearanceReloadAt)
    }



    @Synchronized

    fun onSystemWallpaperChanged() {

        launcherWallpaper = null

        systemSnapshot = null

        snapshotTimeMs = 0L

        invalidate()

        mainHandler.post { LauncherWallpaperCapture.startMotionWatch() }

    }



    fun hasSystemWallpaperEffect(context: Context): Boolean = try {

        Settings.Secure.getInt(context.contentResolver, "is_desktop_wallpaper_blur_enable", 0) != 0 ||

            Settings.Secure.getInt(context.contentResolver, "com.oplus.wallpapers.isLauncherEffectWallpaper", 0) != 0 ||

            WallpaperManager.getInstance(context).wallpaperInfo != null

    } catch (_: Throwable) { false }



    // This API opens the system's wallpaper-only screenshot. It neither hides

    // launcher surfaces nor captures icons, unlike a full-screen screenshot.

    fun readSystemSnapshot(context: Context, fresh: Boolean = false, maxSide: Int = 0): Bitmap? {

        val cached = systemSnapshot?.takeUnless { it.isRecycled }

        if (Looper.myLooper() == Looper.getMainLooper()) {

            return cached?.copy(Bitmap.Config.ARGB_8888, false)

        }

        if (!fresh && cached != null && SystemClock.uptimeMillis() - snapshotTimeMs < 500L) {

            return cached.copy(Bitmap.Config.ARGB_8888, false)

        }

        return try {

            val managerClass = Class.forName("android.app.OplusWallpaperManager")

            val manager = managerClass.getDeclaredConstructor().newInstance()

            val userClass = Class.forName("android.os.UserHandle")

            val userId = userClass.getDeclaredMethod("myUserId").invoke(null) as Int

            val descriptor = managerClass.getMethod("getWallpaperScreenShot", Int::class.javaPrimitiveType)

                .invoke(manager, userId) as? ParcelFileDescriptor

            descriptor?.use {

                val options = BitmapFactory.Options()
                if (maxSide > 0) {
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
                    options.inSampleSize = Integer.highestOneBit((maxOf(options.outWidth, options.outHeight) / maxSide).coerceAtLeast(1))
                    options.inJustDecodeBounds = false
                }
                BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
                    ?.takeUnless { bitmap -> bitmap.isRecycled }

            } ?: LauncherWallpaperCapture.capture(context)

        } catch (t: Throwable) {

            if (logCount < 3) GlassLogger.i("WallpaperHelper", "System screenshot unavailable: ${t.javaClass.simpleName}")

            LauncherWallpaperCapture.capture(context)

        }

    }



    @Synchronized

    fun onLauncherWallpaper(bitmap: Bitmap) {

        if (bitmap.isRecycled) return

        launcherWallpaper = WeakReference(bitmap)

        invalidate()

        mainHandler.post { LauncherWallpaperCapture.startMotionWatch() }

    }



    @Synchronized

    fun onLauncherWindowReady() {

        if (!hasBackdropForActiveEffects() || cachedRevision != sourceRevision ||

            cachedBlurRadius != PrefsHelper.blurRadius.coerceIn(0, 50)) {

            sourceRevision++

            retryAfterMs = 0L

            appContext?.let { requestLoad(it) }

        }

    }



    @Synchronized

    fun getBlurredWallpaper(context: Context? = null): Bitmap? {

        val ctx = context ?: appContext ?: return blurredWallpaper

        if (appContext == null) appContext = ctx.applicationContext ?: ctx

        requestLoad(ctx)

        return blurredWallpaper?.takeUnless { it.isRecycled }

    }



    private fun requestLoad(context: Context) {

        if (!PrefsHelper.enabled || !PrefsHelper.wallpaperEffectsEnabled) return

        val radius = PrefsHelper.blurRadius.coerceIn(0, 50)

        if (isReloading || SystemClock.uptimeMillis() < maxOf(retryAfterMs, appearanceReloadAt) ||

            (cachedRevision == sourceRevision && cachedBlurRadius == radius && hasBackdropForActiveEffects())) return

        isReloading = true

        val revision = sourceRevision

        val liveSerial=livePublishSerial
        val appearanceReload = appearanceReloadAt != 0L
        val previousInput = wallpaperInput.takeIf { appearanceReload }
        val scroll = LiveBlurEngine.wallpaperScrollRatio

        val ctx = context.applicationContext ?: context

        loader.execute {
            val systemEffect = hasSystemWallpaperEffect(ctx)
            val key = staticWallpaperInputKey(ctx, scroll, systemEffect)
            // ColorOS enables the effect flag for static depth wallpapers too. After
            // a fresh wallpaper frame or while the desktop is moving, recapture it.
            val retained = previousInput?.reuse(key)?.takeIf {
                !systemEffect || it === systemSnapshot && !LiveBlurEngine.hasRecentMotion
            }
            val raw = retained ?: runCatching { loadRawWallpaper(ctx, systemEffect) }
                .onFailure { GlassLogger.limited("WallpaperHelper", "wallpaper-input") { "Wallpaper capture failed: $it" } }
                .getOrNull()
            // A rapid style change can supersede this request during wallpaper capture.
            val current = synchronized(this) { revision == sourceRevision && liveSerial == livePublishSerial }
            val candidate = if (raw != null && current) loadAndBlurWallpaper(radius, raw, live = false,
                reuseStatic = key != null) else null

            var published = false

            synchronized(this) {

                isReloading = false

                if (PrefsHelper.enabled && PrefsHelper.wallpaperEffectsEnabled && revision == sourceRevision &&
                    radius == PrefsHelper.blurRadius.coerceIn(0, 50) && liveSerial==livePublishSerial) {

                    if (candidate != null) {

                        publishBackdrops(candidate)
                        wallpaperInput = if (key != null && raw != null) WallpaperInput(key, raw) else null
                        appearanceReloadAt = 0L
                        retryAfterMs=if(hasBackdropForActiveEffects())0L else SystemClock.uptimeMillis()+2000L

                        cachedBlurRadius = radius

                        cachedRevision = revision

                        published = true

                    } else {

                        retryAfterMs = SystemClock.uptimeMillis() + 2000L

                    }

                } else {

                    candidate?.discard()

                    requestLoad(ctx)

                }

            }

            if (published) mainHandler.post {

                val streaming = com.cosmod.launcherglass.xposed.NativeSurfaceLive.active
                if (PrefsHelper.enabled && ((LiveBlurEngine.isResumed && !LiveBlurEngine.isAppLaunchInProgress) || streaming)) {
                    if (appearanceReload && PrefsHelper.gpuEffects && !streaming &&
                        !LiveBlurEngine.deferWorkspaceSampling) {
                        // Refresh the sampler textures after a style change without forcing all
                        // launcher items to re-record their display lists again.
                        LiveBlurEngine.onWallpaperFrameReady()
                    } else {
                        LiveBlurEngine.invalidate()
                    }
                }

            }

        }

    }

    private fun hasBackdropForActiveEffects()=
        (!PrefsHelper.blurEnabled || blurredWallpaper?.isRecycled==false) &&
            (!PrefsHelper.widgetGlassEnabled || widgetWallpaper?.isRecycled==false) &&
            (!PrefsHelper.dockGlassEnabled || NeutralBackdrop.image?.isRecycled==false) &&
            (com.cosmod.launcherglass.xposed.PageIndicatorGlass.profile.mode==0 ||
                NeutralBackdrop.indicatorImage?.isRecycled==false)


    // The launcher uses its own wallpaper access. If Android rejects it, use the snapshot below.
    @android.annotation.SuppressLint("MissingPermission")
    internal fun getPickerWallpaper(context: Context): Bitmap? {
        val maxSide = maxOf(context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels).coerceIn(1920, 3072)
        val manager = WallpaperManager.getInstance(context)
        // ColorOS also sets its wallpaper-effect flag for static depth wallpapers.
        // The editor's effect frame is often lower resolution than the static crop.
        if (runCatching { manager.wallpaperInfo == null }.getOrDefault(false)) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching {
                manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use {
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
                    if (options.outWidth > 0 && options.outHeight > 0) {
                        options.inSampleSize = Integer.highestOneBit(
                            (maxOf(options.outWidth, options.outHeight) / maxSide).coerceAtLeast(1))
                        options.inJustDecodeBounds = false
                        BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options)
                    } else null
                }
            }.getOrNull()?.let(::forSoftwareCanvas)?.let { image ->
                image.prepareToDraw()
                GlassLogger.i("DesktopAppearance", "Eyedropper uses static wallpaper ${image.width}x${image.height}")
                return image
            }
        }
        val bitmap = readSystemSnapshot(context, fresh = true, maxSide = maxSide)
            ?: loadRawWallpaper(context) ?: return null
        return forSoftwareCanvas(bitmap)?.also {
            it.prepareToDraw()
            GlassLogger.i("DesktopAppearance", "Eyedropper uses wallpaper snapshot ${it.width}x${it.height}")
        }
    }

    fun getRawScaledWallpaper(context: Context): Bitmap? {

        val raw = loadRawWallpaper(context) ?: return null

        val targetH = 800

        val targetW = ((raw.width.toFloat() / raw.height.toFloat()) * targetH).roundToInt().coerceAtLeast(360)

        return Bitmap.createScaledBitmap(raw, targetW, targetH, true)

    }



    @Synchronized

    fun getShader(context: Context, screenWidth: Int, screenHeight: Int, screenX: Int, screenY: Int): BitmapShader? {

        var blurred = getBlurredWallpaper(context) ?: return null



        val shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)

        val matrix = Matrix()

        val scaleX = screenWidth.toFloat() / blurred.width

        val scaleY = screenHeight.toFloat() / blurred.height

        matrix.postScale(scaleX, scaleY)

        matrix.postTranslate(-screenX.toFloat(), -screenY.toFloat())

        shader.setLocalMatrix(matrix)

        return shader

    }



    private fun staticWallpaperInputKey(
        context: Context, scroll: Float, systemEffect: Boolean,
    ): WallpaperInputKey? = runCatching {
        val manager = WallpaperManager.getInstance(context)
        val id = manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
        if (id <= 0 || manager.wallpaperInfo != null) return null
        val metrics = context.resources.displayMetrics
        WallpaperInputKey(id, metrics.widthPixels, metrics.heightPixels, scroll, systemEffect)
    }.getOrNull()

    private fun loadAndBlurWallpaper(radius: Int, rawBitmap: Bitmap, live: Boolean, reuseStatic: Boolean = false): Backdrops? {

        try {

            val heightLimit=when {
                !live->Int.MAX_VALUE
                PerformancePolicy.economy->(PerformancePolicy.sourceHeight*.70f).roundToInt().coerceAtLeast(360)
                else->PerformancePolicy.sourceHeight
            }
            val widgets=runCatching {widgetBackdrop(rawBitmap,PrefsHelper.widgets,heightLimit)}.onFailure {
                GlassLogger.limited("PhotoWidget","backdrop") {"Widget wallpaper processing failed: $it"}
            }.getOrNull()
            val neutral=NeutralBackdrop.prepare(rawBitmap,heightLimit,reuseStatic)
            if(!PrefsHelper.blurEnabled)return Backdrops(null,null,widgets,null,neutral).takeIf {
                widgets!=null || neutral.indicator!=null || neutral.dock!=null
            }

            fun withFolders(icons:Bitmap):Backdrops {
                if(!PrefsHelper.folderBlurEnabled || !PrefsHelper.enabled)return Backdrops(icons,null,widgets,null,neutral)
                val folder=FolderSettings.backdrop()
                val separate=FolderSettings.own
                val opacity=KyantGlassEngine.optics(if(separate)GlassSurface.FOLDERS else 0).blur.opacity
                fun render(settings:FolderSettings.Processing,alpha:Int)=runCatching {
                    folderBackdrop(rawBitmap,heightLimit,settings,alpha)
                }.onFailure {
                    GlassLogger.limited("FolderMaterial","wallpaper-processing"){"Folder backdrop unavailable: $it"}
                }.getOrNull()
                val normal=if(separate)render(folder,opacity) else null
                val large=FolderSettings.largeBackdrop()?.let { settings ->
                    val largeOpacity=KyantGlassEngine.largeFolderOptics().blur.opacity
                    if(settings==folder && largeOpacity==opacity)normal ?: icons.takeUnless { separate }
                        ?: render(settings,largeOpacity)
                    else render(settings,largeOpacity)
                }
                return Backdrops(icons,normal,widgets,large,neutral)
            }

            // Never upscale the already downsampled live capture just to blur it.

            val baseHeight=if(PerformancePolicy.economy)720 else 1080
            val targetH = minOf(baseHeight+PrefsHelper.blurQuality*12,rawBitmap.height,PerformancePolicy.sourceHeight,heightLimit)

            val targetW = ((rawBitmap.width.toFloat() / rawBitmap.height.toFloat()) * targetH).roundToInt().coerceAtLeast(1)

            val effectRadius = radius * (targetH / 800f) * (if(PrefsHelper.liquidGlass).28f else 1f)

            if(PrefsHelper.gpuEffects)try {

                val input=if(android.os.Build.VERSION.SDK_INT<33)BackdropVibrancy.applyCpu(rawBitmap,PrefsHelper.blurVibrancy)else rawBitmap
                val gpu=try {GpuWallpaperBlur.blur(input, targetW, targetH, effectRadius)}finally{if(input!==rawBitmap)input.recycle()}

                return (if(ShadowTone.gpuSupported)gpu else ShadowTone.applyCpu(gpu,PrefsHelper.blurLightening).also {

                    if(it !== gpu)gpu.recycle()

                }).let(::withFolders)

            } catch (t: Throwable) {

                if (!gpuFailureLogged) {

                    GlassLogger.e("GpuBlur", "GPU blur failed; using compatibility CPU path", t)

                    gpuFailureLogged = true

                }

            }



            val scaled = Bitmap.createScaledBitmap(rawBitmap, targetW, targetH, true)

            val software = if (scaled.config == Bitmap.Config.HARDWARE) {

                scaled.copy(Bitmap.Config.ARGB_8888, false) ?: return null

            } else scaled

            val vibrant=BackdropVibrancy.applyCpu(software,PrefsHelper.blurVibrancy)
            val input=ColorDepthEffect.atCpu(vibrant,ColorDepthEffect.BEFORE_BLUR)
            val actualRadius=effectRadius

            var blurred = try {

                GlassBlur.bitmap(input,actualRadius,KyantGlassEngine.optics(0).blur.opacity)

            } finally {

                if(input!==vibrant)input.recycle()
                if (vibrant !== software)vibrant.recycle()
                if (software !== rawBitmap) software.recycle()

                if (scaled !== rawBitmap && scaled !== software) scaled.recycle()

            }



            if (logCount < 5) {

                GlassLogger.i("WallpaperHelper", "Blurred wallpaper ready: ${blurred.width}x${blurred.height}, radius=$radius")

                logCount++

            }

            val toned=ShadowTone.applyCpu(blurred,PrefsHelper.blurLightening)
            val processed=ColorDepthEffect.atCpu(toned,ColorDepthEffect.AFTER_BLUR)
            if(toned!==blurred && processed!==toned)toned.recycle()
            if(processed!==blurred)blurred.recycle()
            return withFolders(processed)

        } catch (t: Throwable) {

            GlassLogger.e("WallpaperHelper", "Failed to blur wallpaper", t)

            return null

        }

    }



    // Wallpaper access uses the hooked launcher's permissions; denied fallback calls are caught.
    @android.annotation.SuppressLint("MissingPermission")
    private fun loadRawWallpaper(context: Context, systemEffect: Boolean = hasSystemWallpaperEffect(context)): Bitmap? {

        // A screen-sized screenshot has already discarded the sides that
        // become visible during a page swipe. Keep the full crop when the
        // static wallpaper file still contains that horizontal travel.
        if(!systemEffect){
            val manager=WallpaperManager.getInstance(context)
            val metrics=context.resources.displayMetrics
            val dimensions=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            val full=runCatching {
                manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use {
                    BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,dimensions)
                }
                if(dimensions.outHeight>0 && dimensions.outWidth.toLong()*metrics.heightPixels*100L >
                    dimensions.outHeight.toLong()*metrics.widthPixels*103L){
                    val scale=(dimensions.outHeight/(PerformancePolicy.sourceHeight*2).coerceAtLeast(1)).coerceAtLeast(1)
                    val options=BitmapFactory.Options().apply {inSampleSize=Integer.highestOneBit(scale)}
                    manager.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use {
                        BitmapFactory.decodeFileDescriptor(it.fileDescriptor,null,options)
                    }
                }else null
            }.getOrNull()
            if(full!=null)return full
        }

        val captureRevision=synchronized(this){sourceRevision}
        readSystemSnapshot(context)?.let {

            synchronized(this){
                if(captureRevision==sourceRevision){
                    systemSnapshot=it
                    snapshotTimeMs=SystemClock.uptimeMillis()
                }
            }

            GlassLogger.i("WallpaperHelper", "Loaded wallpaper-only system screenshot (${it.width}x${it.height})")

            return it

        }

        synchronized(this) { launcherWallpaper?.get()?.takeUnless { it.isRecycled } }?.let {

            GlassLogger.i("WallpaperHelper", "Using processed wallpaper delivered to launcher (${it.width}x${it.height})")

            return it

        }

        if (systemEffect) {

            // If we already have a valid blurred wallpaper, retain it and retry the screenshot.

            // If blurredWallpaper is null, allow initial static fallback so icons don't lose blur completely!

            if (blurredWallpaper?.isRecycled == false) {

                return null

            }

        }

        // ColorOS effects are rendered by a separate wallpaper service. Its

        // processed bitmap is exposed through the same OEM API the launcher uses;

        // WallpaperManager.drawable can return an unrelated static fallback.

        try {

            val managerClass = Class.forName("android.app.OplusWallpaperManager")

            val manager = managerClass.getDeclaredConstructor().newInstance()

            val userClass = Class.forName("android.os.UserHandle")

            val userId = userClass.getDeclaredMethod("myUserId").invoke(null) as Int

            val metrics = context.resources.displayMetrics

            val sizes = arrayListOf(Point(metrics.widthPixels, metrics.heightPixels))

            val method = managerClass.getMethod("getWallpaperBitmap", Context::class.java,

                java.util.List::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

            val bitmap = method.invoke(manager, context, sizes, WallpaperManager.FLAG_SYSTEM, userId) as? Bitmap

            if (bitmap != null && !bitmap.isRecycled) {

                GlassLogger.i("WallpaperHelper", "Loaded processed system wallpaper from ColorOS (${bitmap.width}x${bitmap.height})")

                return bitmap

            }

        } catch (t: Throwable) {

            if (logCount < 3) GlassLogger.i("WallpaperHelper", "ColorOS wallpaper source unavailable: ${t.javaClass.simpleName}")

        }



        // Compatibility fallback for launchers without the ColorOS API.

        try {

            val wm = WallpaperManager.getInstance(context)

            val d = wm.drawable ?: wm.peekDrawable()

            if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {

                val bmp = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)

                val c = Canvas(bmp)

                d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)

                d.draw(c)

                GlassLogger.i("WallpaperHelper", "Loaded raw wallpaper from WallpaperManager (${bmp.width}x${bmp.height})")

                return bmp

            }

        } catch (t: Throwable) {

            GlassLogger.i("WallpaperHelper", "WallpaperManager.drawable fallback: ${t.message}")

        }



        // Source 2: Dynamic system wallpaper file / fallback cache

        try {

            val cacheFiles = listOf(

                File("/data/system/users/0/wallpaper"),

                File(context.cacheDir, "cosmod_wallpaper.png"),

                File(context.filesDir, "cosmod_wallpaper.png")

            )

            for (file in cacheFiles) {

                if (file.canRead() && file.length() > 1000) {

                    val bmp = BitmapFactory.decodeFile(file.absolutePath)

                    if (bmp != null) {

                        GlassLogger.i("WallpaperHelper", "Loaded raw wallpaper from ${file.absolutePath} (${bmp.width}x${bmp.height})")

                        return bmp

                    }

                }

            }

        } catch (_: Throwable) {}



        return null

    }

}
