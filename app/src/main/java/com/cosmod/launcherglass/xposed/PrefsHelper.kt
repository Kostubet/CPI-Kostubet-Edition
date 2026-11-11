package com.cosmod.launcherglass.xposed

import android.app.WallpaperManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.cosmod.launcherglass.ui.GlassDispersionSettings
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.LiveBlurEngine
import com.cosmod.launcherglass.util.WallpaperHelper

object PrefsHelper {

    @Volatile var enabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED)
    @Volatile var gpuEffects: Boolean = true
        private set
    @Volatile var bgOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_BG_OPACITY)
    @Volatile var blurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_BLUR_ENABLED)
    @Volatile var colorDepthBits=24
        private set
    @Volatile var colorDepthStage=ColorDepthEffect.FINISHED_PLATE
        private set
    @Volatile var disableEdgeOptimization=SettingsDefaults.boolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION)
        private set
    @Volatile var optimizeMiniIcons=SettingsDefaults.boolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS)
        private set
    @Volatile var liftedBackdrop=false
        private set
    @Volatile var folderBlurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)
    @Volatile var folderIconsBlurEnabled=true
    @Volatile var iconDispersion=0
    @Volatile var dockDispersion=0
    @Volatile var dockBlur=14
    @Volatile var dockStyle=0
    @Volatile var dockOpacity=40
    @Volatile var dockCorner=35
    @Volatile var dockInset=8
    @Volatile var dockWidth=100
    @Volatile var dockHeight=100
    @Volatile var dockLiquidWidth=40
    @Volatile var dockLiquidDistortion=50
    @Volatile var ltpoIdle=true
    @Volatile internal var widgets=WidgetMaterialSettings.read(Bundle())
        private set
    val widgetGlassEnabled get()=widgets.enabled
    val wallpaperEffectsEnabled get()=blurEnabled || widgetGlassEnabled || PageIndicatorGlass.profile.mode>0
    @Volatile var drawerBlurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED)
    @Volatile var blurRadius: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_RADIUS)
    @Volatile var blurMode: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_MODE)
    @Volatile var blurStyle: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_STYLE)
    @Volatile var blurQuality=SettingsDefaults.integer(SettingsProvider.KEY_BLUR_QUALITY)
    @Volatile var liquidWidth=SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_WIDTH)
    @Volatile var liquidDistortion=SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_DISTORTION)
    val liquidGlass:Boolean get()=blurStyle==SettingsProvider.BLUR_STYLE_LIQUID && android.os.Build.VERSION.SDK_INT>=33
    @Volatile private var requestedLiveFps=SettingsDefaults.integer(SettingsProvider.KEY_LIVE_FPS)
    var liveFps:Int
        get()=com.cosmod.launcherglass.util.PerformancePolicy.fps(requestedLiveFps)
        private set(value){requestedLiveFps=value}
    @Volatile var idlePause: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_IDLE_PAUSE)
    @Volatile var iconStroke: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_ICON_STROKE)
    @Volatile var strokeOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_STROKE_OPACITY)
    @Volatile var glyphStroke = false
    @Volatile var glyphStrokeOpacity = 40
    @Volatile var glyphStrokeWidth = 35
    @Volatile var glyphStrokeBrightness = 45
    @Volatile var glyphShadow: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_GLYPH_SHADOW)
    @Volatile var shadowOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_SHADOW_OPACITY)
    @Volatile var gradientOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_GRADIENT_OPACITY)

    @Volatile var iconPalette = com.cosmod.launcherglass.util.IconPalette.from(0, 0, 0, 0)
    @Volatile var launcherIconSize=100
        private set

    @Volatile var blurVibrancy = 0
        private set
    private var blurSaturation = 100
    @Volatile private var neutralIconAccent:Int?=null
    internal fun iconPlateColor(color:Int=iconPalette.background)=
        com.cosmod.launcherglass.util.PlateTone.iconSaturation(color,blurSaturation,neutralIconAccent)
    @Volatile var blurLightening = 50
        private set
    @Volatile var blurColorFilter: android.graphics.ColorMatrixColorFilter? = null
    private var initialized = false
    @Volatile private var loaded=false
    private var followsWallpaper=false
    private var wallpaperTone:IconPalette.WallpaperTone?=null

    internal fun backdropFilter(vibrancy:Int,palette:com.cosmod.launcherglass.util.IconPalette):android.graphics.ColorMatrixColorFilter? {
        val colorize=palette.custom && !palette.clear
        return if (!colorize || vibrancy>=100) null else {
                    val matrix = android.graphics.ColorMatrix()
                    if (colorize) {
                        // Replace source chroma instead of tinting a colored
                        // wallpaper. Plate saturation changes the overlay color
                        // separately, independently of this chroma filter.
                        matrix.setSaturation(0f)
                        val hue = android.graphics.Color.HSVToColor(floatArrayOf(palette.tintHue.toFloat(),1f,1f))
                        val strength = palette.tintSaturation / 100f
                        val tint = android.graphics.ColorMatrix().apply { setScale(
                            1f-strength+strength*android.graphics.Color.red(hue)/255f,
                            1f-strength+strength*android.graphics.Color.green(hue)/255f,
                            1f-strength+strength*android.graphics.Color.blue(hue)/255f,1f) }
                        matrix.postConcat(tint)
                    }
                    // Vibrancy restores source chroma that a custom tint may
                    // otherwise replace. Blend the complete legacy transform
                    // toward identity, preserving its old behavior at zero.
                    val keep=(vibrancy/100f).coerceIn(0f,1f)
                    if(keep>0f){
                        val identity=android.graphics.ColorMatrix().array
                        val values=matrix.array
                        for(i in values.indices)values[i]=values[i]*(1f-keep)+identity[i]*keep
                        matrix.set(values)
                    }
                    android.graphics.ColorMatrixColorFilter(matrix)
                }
    }
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        com.cosmod.launcherglass.util.RuntimeReport.loaded(context,"launcher")

        loadPrefs(context)
        runCatching {
            val manager=WallpaperManager.getInstance(context)
            manager.addOnColorsChangedListener({ colors,which ->
                if(enabled && followsWallpaper && (which and WallpaperManager.FLAG_SYSTEM)!=0){
                    val tone=IconPalette.wallpaperTone(colors)
                    if(tone!=wallpaperTone)loadPrefs(context,wallpaperOverride=tone)
                }
            },Handler(Looper.getMainLooper()))
        }
        for(delay in listOf(250L,750L,1500L,3000L))Handler(Looper.getMainLooper()).postDelayed({if(!loaded)loadPrefs(context)},delay)
        context.registerComponentCallbacks(object : android.content.ComponentCallbacks {
            override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
                val dark = configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                loadPrefs(context, dark)
            }
            override fun onLowMemory() {}
        })

        try {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    loadPrefs(context)
                }
            }
            context.contentResolver.registerContentObserver(SettingsProvider.CONTENT_URI, true, observer)
            GlassLogger.i(
                "PrefsHelper",
                "Initialized & ContentObserver registered: bgOpacity=$bgOpacity%, blur=$blurEnabled(mode=$blurMode, fps=$liveFps, idlePause=$idlePause, r=$blurRadius)"
            )
        } catch (t: Throwable) {
            GlassLogger.e("PrefsHelper", "Failed to register ContentObserver", t)
        }
    }

    fun loadPrefs(context: Context, systemDark: Boolean? = null,wallpaperOverride:IconPalette.WallpaperTone?=null) {
        try {
            val bundle = context.contentResolver.call(
                SettingsProvider.CONTENT_URI,
                SettingsProvider.METHOD_GET_ALL,
                null,
                null
            ) ?: return

            val performanceChanged=com.cosmod.launcherglass.util.PerformancePolicy.update(context,bundle)
            AppWindowTilt.update(bundle)
            DockMaterial.updateUnlock(bundle)
            PageIndicatorGlass.update(bundle)
            launcherIconSize=bundle.getInt("launcher_icon_size",100).coerceIn(70,130)
            val newEnabled = bundle.getBoolean(SettingsProvider.KEY_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_ENABLED))
            val toneEnabled=bundle.getBoolean(SettingsProvider.KEY_BLUR_TONE_ENABLED,true)
            val newBgOpacity = if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BG_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_BG_OPACITY)).coerceIn(0,100) else 0
            val newBlurEnabled = bundle.getBoolean(SettingsProvider.KEY_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_BLUR_ENABLED))
            val locationBlurEnabled = bundle.getBoolean(SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED,true)
            val newFolderBlurEnabled = bundle.getBoolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_FOLDER_BLUR_ENABLED))
            val newFolderIconsBlurEnabled=locationBlurEnabled && bundle.getBoolean(SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED,true)
            val newDrawerBlurEnabled = locationBlurEnabled && bundle.getBoolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED))
            ltpoIdle=bundle.getBoolean("performance_ltpo_idle",true)
            val developer=bundle.getBoolean(SettingsProvider.KEY_DEVELOPER_MODE,false)
            val newGpuEffects=!developer || bundle.getBoolean(SettingsProvider.KEY_GPU_EFFECTS,true)
            val backendChanged=newGpuEffects!=gpuEffects
            val newColorDepth=if(developer && android.os.Build.VERSION.SDK_INT>=33 && bundle.getBoolean(SettingsProvider.KEY_COLOR_DEPTH_ENABLED,false))bundle.getInt(SettingsProvider.KEY_COLOR_DEPTH_BITS,8).coerceIn(8,24)else 24
            val newDepthStage=bundle.getInt(SettingsProvider.KEY_COLOR_DEPTH_STAGE,ColorDepthEffect.FINISHED_PLATE).coerceIn(0,3)
            val nextIconDispersion=GlassDispersionSettings.strength(bundle,GlassDispersionSettings.ICONS)
            val nextDockDispersion=GlassDispersionSettings.strength(bundle,GlassDispersionSettings.DOCK)
            val opticsChanged=nextIconDispersion!=iconDispersion || nextDockDispersion!=dockDispersion
            iconDispersion=nextIconDispersion
            dockDispersion=nextDockDispersion
            val clearIcons=bundle.getInt(SettingsProvider.KEY_ICON_APPEARANCE,1)==0
            val colorMode=bundle.getInt(SettingsProvider.KEY_COLOR_MODE,0)
            val iconFollowsWallpaper=newEnabled && colorMode==1 && !clearIcons &&
                bundle.getBoolean(IconPalette.FOLLOW_WALLPAPER,true)
            followsWallpaper=iconFollowsWallpaper || newEnabled && WidgetMaterialSettings.followsWallpaper(bundle)
            wallpaperTone=if(followsWallpaper)wallpaperOverride ?: IconPalette.wallpaperTone(context) else null
            val newWidgets=WidgetMaterialSettings.read(bundle,wallpaperTone)
            val photoSourceChanged=newWidgets.backdropChanged(widgets)
            val widgetChanged=newWidgets!=widgets
            val glowChanged=WallpaperGlow.update(bundle)
            val edgeChanged=EdgeLight.update(bundle)
            val depthChanged=newColorDepth!=colorDepthBits || newDepthStage!=colorDepthStage
            val processingDepthChanged=depthChanged && ((colorDepthBits<24 && colorDepthStage<=ColorDepthEffect.AFTER_BLUR) ||
                (newColorDepth<24 && newDepthStage<=ColorDepthEffect.AFTER_BLUR))
            val newDisableEdgeOptimization=bundle.getBoolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION,SettingsDefaults.boolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION))
            val newOptimizeMiniIcons=bundle.getBoolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS,SettingsDefaults.boolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS))
            // Experimental: effective only while developer mode is on.
            val newLiftedBackdrop=false
            val newBlurRadius = if (bundle.getBoolean(SettingsProvider.KEY_BLUR_RADIUS_ENABLED,true))
                bundle.getInt(SettingsProvider.KEY_BLUR_RADIUS, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_RADIUS)).coerceIn(0,50) else 0
            val newBlurMode = bundle.getInt(SettingsProvider.KEY_BLUR_MODE, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_MODE))
            val newBlurStyle = bundle.getInt(SettingsProvider.KEY_BLUR_STYLE, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_STYLE)).coerceIn(0,1)
            val blurStyleChanged=newBlurStyle!=blurStyle
            val newBlurQuality=if(developer)bundle.getInt(SettingsProvider.KEY_BLUR_QUALITY,SettingsDefaults.integer(SettingsProvider.KEY_BLUR_QUALITY)).coerceIn(0,100)else 0
            val newLiquidWidth=bundle.getInt(SettingsProvider.KEY_LIQUID_WIDTH,SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_WIDTH)).coerceIn(0,100)
            val newLiquidDistortion=bundle.getInt(SettingsProvider.KEY_LIQUID_DISTORTION,SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_DISTORTION)).coerceIn(0,300)
            val geometryChanged=newLiquidWidth!=liquidWidth || newLiquidDistortion!=liquidDistortion
            val qualityChanged=newBlurQuality!=blurQuality
            val newLiveFps = bundle.getInt(SettingsProvider.KEY_LIVE_FPS, SettingsDefaults.integer(SettingsProvider.KEY_LIVE_FPS))
            val newIdlePause = bundle.getBoolean(SettingsProvider.KEY_IDLE_PAUSE, SettingsDefaults.boolean(SettingsProvider.KEY_IDLE_PAUSE))
            val designEnabled = bundle.getBoolean(SettingsProvider.KEY_DESIGN_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_DESIGN_ENABLED))
            val newIconStroke = false
            val newStrokeOpacity = bundle.getInt(SettingsProvider.KEY_STROKE_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_STROKE_OPACITY))
            val newGlyphStroke = designEnabled && bundle.getBoolean(SettingsProvider.KEY_GLYPH_STROKE, false)
            val newGlyphStrokeOpacity = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_OPACITY, 40).coerceIn(0,100)
            val newGlyphStrokeWidth = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_WIDTH,35).coerceIn(0,100)
            val newGlyphStrokeBrightness = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,45).coerceIn(0,100)
            val glyphOutlineChanged = newGlyphStroke != glyphStroke || newGlyphStrokeOpacity != glyphStrokeOpacity ||
                newGlyphStrokeWidth != glyphStrokeWidth || newGlyphStrokeBrightness != glyphStrokeBrightness
            val newGlyphShadow = designEnabled && bundle.getBoolean(SettingsProvider.KEY_GLYPH_SHADOW, SettingsDefaults.boolean(SettingsProvider.KEY_GLYPH_SHADOW))
            val newShadowOpacity = bundle.getInt(SettingsProvider.KEY_SHADOW_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_SHADOW_OPACITY))
            val newGradientOpacity = if (designEnabled && bundle.getBoolean(SettingsProvider.KEY_VOLUME_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_VOLUME_ENABLED)))
                bundle.getInt(SettingsProvider.KEY_GRADIENT_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_GRADIENT_OPACITY)) else 0
            val configuration = android.content.res.Configuration(context.resources.configuration)
            if (systemDark != null) {
                configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (systemDark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            }
            val darkIcons = NativeIconAppearance.read(context, configuration)
                ?: (systemDark ?: com.cosmod.launcherglass.util.IconPalette.isSystemDark(context))
            val fluxSupport=FluxIconSupport.read(context)
            if(fluxSupport!=FluxIconSupport.UNKNOWN && bundle.getInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,0)!=fluxSupport) {
                context.contentResolver.call(SettingsProvider.CONTENT_URI,SettingsProvider.METHOD_REPORT_ICON_APPEARANCE,
                    null,android.os.Bundle().apply {putInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,fluxSupport)})
            }
            if (!bundle.containsKey(SettingsProvider.KEY_NATIVE_ICON_DARK) ||
                bundle.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK) != darkIcons) {
                context.contentResolver.call(SettingsProvider.CONTENT_URI, SettingsProvider.METHOD_REPORT_ICON_APPEARANCE,
                    null, android.os.Bundle().apply { putBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK, darkIcons) })
            }
            val colorKeys = SettingsProvider.colorKeys(darkIcons)
            val hue=bundle.getInt(colorKeys[0],SettingsDefaults.integer(colorKeys[0]))
            val saturation=bundle.getInt(colorKeys[1],SettingsDefaults.integer(colorKeys[1]))
            val brightness=bundle.getInt(colorKeys[2],SettingsDefaults.integer(colorKeys[2]))
            val tone=wallpaperTone.takeIf {iconFollowsWallpaper}
            val newPalette=IconPalette.from(colorMode,tone?.hue ?: hue,tone?.saturationAt(saturation) ?: saturation,
                if(clearIcons)bundle.getInt(SettingsProvider.KEY_CLEAR_BRIGHTNESS,SettingsDefaults.integer(SettingsProvider.KEY_CLEAR_BRIGHTNESS))
                else brightness,darkIcons,clearIcons)
            val accent=if(colorMode==0 && clearIcons)
                com.cosmod.launcherglass.util.IconPalette.from(0,hue,saturation,brightness,darkIcons).background
                else if(colorMode==0)newPalette.background else null
            val accentChanged=accent!=neutralIconAccent
            val newBlurVibrancy=if(toneEnabled)bundle.getInt(SettingsProvider.KEY_BLUR_VIBRANCY,0).coerceIn(0,200)else 0
            val vibrancyChanged=newBlurVibrancy!=blurVibrancy
            val newBlurSaturation = if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BLUR_SATURATION,100).coerceIn(0,200) else 100
            val newBlurLightening = if (toneEnabled)
                bundle.getInt(SettingsProvider.KEY_BLUR_LIGHTENING,50).coerceIn(0,100) else 50
            val shadowToneChanged = newBlurLightening != blurLightening
            val blurColorsChanged = newBlurSaturation != blurSaturation || newBlurLightening != blurLightening
            val paletteChanged = newPalette != iconPalette
            val masterChanged = newEnabled != enabled

            if (newBgOpacity != bgOpacity || newEnabled != enabled) {
                IconBitmapProcessor.clearCache()
                com.cosmod.launcherglass.util.GlyphShadowHelper.clearCache()
            }
            if (newBlurRadius != blurRadius) {
                WallpaperHelper.invalidate()
                LiveBlurEngine.invalidate()
            }
            if (newBlurMode != blurMode) {
                LiveBlurEngine.invalidate()
            }
            val folderEnabledChanged=newFolderBlurEnabled!=folderBlurEnabled
            val materialChanged=accentChanged || edgeChanged || opticsChanged || performanceChanged || backendChanged || newBgOpacity!=bgOpacity || newBlurEnabled!=blurEnabled ||
                newFolderBlurEnabled!=folderBlurEnabled || newFolderIconsBlurEnabled!=folderIconsBlurEnabled || newDrawerBlurEnabled!=drawerBlurEnabled ||
                newIconStroke != iconStroke || newStrokeOpacity != strokeOpacity ||
                newGlyphShadow != glyphShadow || newShadowOpacity != shadowOpacity ||
                newGradientOpacity != gradientOpacity || glyphOutlineChanged || paletteChanged ||
                blurColorsChanged || blurStyleChanged || geometryChanged || vibrancyChanged || widgetChanged || depthChanged || glowChanged || newDisableEdgeOptimization!=disableEdgeOptimization || newOptimizeMiniIcons!=optimizeMiniIcons || newLiftedBackdrop!=liftedBackdrop

            if (blurColorsChanged || paletteChanged || vibrancyChanged) {
                blurSaturation = newBlurSaturation
                blurLightening = newBlurLightening
                blurColorFilter=backdropFilter(newBlurVibrancy,newPalette)
            }
            neutralIconAccent=accent
            iconPalette = newPalette
            enabled = newEnabled
            gpuEffects=newGpuEffects
            bgOpacity = newBgOpacity
            blurEnabled = newBlurEnabled
            colorDepthBits=newColorDepth
            colorDepthStage=newDepthStage
            disableEdgeOptimization=newDisableEdgeOptimization
            optimizeMiniIcons=newOptimizeMiniIcons
            liftedBackdrop=newLiftedBackdrop
            folderBlurEnabled = newFolderBlurEnabled
            folderIconsBlurEnabled=newFolderIconsBlurEnabled
            drawerBlurEnabled = newDrawerBlurEnabled
            dockBlur=bundle.getInt(SettingsProvider.KEY_DOCK_BLUR,14).coerceIn(0,40)
            dockStyle=bundle.getInt(SettingsProvider.KEY_DOCK_STYLE,0).coerceIn(0,2)
            dockOpacity=bundle.getInt(SettingsProvider.KEY_DOCK_OPACITY,40).coerceIn(0,100)
            dockCorner=bundle.getInt(SettingsProvider.KEY_DOCK_CORNER,45).coerceIn(0,50)
            dockInset=bundle.getInt(SettingsProvider.KEY_DOCK_INSET,8).coerceIn(0,32)
            dockWidth=bundle.getInt("dock_width_percent",112).coerceIn(55,160)
            dockHeight=bundle.getInt("dock_height_percent",105).coerceIn(55,160)
            dockLiquidWidth=bundle.getInt(SettingsProvider.KEY_DOCK_LIQUID_WIDTH,40).coerceIn(0,100)
            dockLiquidDistortion=bundle.getInt(SettingsProvider.KEY_DOCK_LIQUID_DISTORTION,50).coerceIn(0,300)
            if(widgetChanged)widgets=newWidgets
            blurRadius = newBlurRadius
            blurMode = newBlurMode
            blurStyle = newBlurStyle
            blurVibrancy=newBlurVibrancy
            blurQuality=newBlurQuality;liquidWidth=newLiquidWidth;liquidDistortion=newLiquidDistortion
            if(performanceChanged || blurStyleChanged || qualityChanged || vibrancyChanged || processingDepthChanged || backendChanged)WallpaperHelper.invalidate()
            liveFps = newLiveFps
            idlePause = newIdlePause
            iconStroke = newIconStroke
            strokeOpacity = newStrokeOpacity
            glyphStroke = newGlyphStroke
            glyphStrokeOpacity = newGlyphStrokeOpacity
            glyphStrokeWidth = newGlyphStrokeWidth
            glyphStrokeBrightness = newGlyphStrokeBrightness
            glyphShadow = newGlyphShadow
            shadowOpacity = newShadowOpacity
            gradientOpacity = newGradientOpacity
            val folderChanged=FolderSettings.update(bundle)
            if(folderChanged || (FolderSettings.own && folderEnabledChanged))WallpaperHelper.invalidate()
            WallpaperHelper.onProcessingSettingsChanged()
            if(shadowToneChanged || photoSourceChanged || masterChanged) WallpaperHelper.invalidate()

            // Publish all new values before invalidating recorded materials.
            // In particular, a contour toggle must rebuild the overlay itself,
            // not only update the wallpaper sampling RenderNode.
            if (materialChanged || folderChanged) {
                IconGlassHook.invalidateShaders()
            }
            if (paletteChanged) {
                IconBitmapProcessor.clearTintCache()
                IconMaterialRenderer.clear()
            }
            if(materialChanged || folderChanged || paletteChanged){
                IconGlassHook.invalidateFolderPreviews()
                IconGlassHook.invalidateActiveIcons()
            }
            if (masterChanged || backendChanged) {
                IconGlassHook.onIconThemeChanged()
                IconGlassHook.reloadNativeIcons()
            }

            if(enabled){
                ModuleHooks.setEnabled(true)
                com.cosmod.launcherglass.util.LauncherWallpaperCapture.onModuleEnabled()
            }
            LiveBlurEngine.onPrefsChanged()
            NativeSurfaceLive.onSettingsChanged()
            if(masterChanged || folderEnabledChanged || materialChanged || folderChanged)FolderMaterialHook.onSettingsChanged()
            LiftedBackdrop.onSettingsChanged()
            if(masterChanged || materialChanged || paletteChanged)MultiIconMaterialHook.onSettingsChanged()
            if(masterChanged || widgetChanged || photoSourceChanged || edgeChanged)PhotoWidgetMaterial.onSettingsChanged()
            DockMaterial.onSettingsChanged()
            LauncherIconSize.onSettingsChanged()
            NeutralBackdrop.onSettingsChanged()
            if(!enabled){
                IconGlassHook.onModuleDisabled()
                com.cosmod.launcherglass.util.LauncherWallpaperCapture.onModuleDisabled()
                ModuleHooks.setEnabled(false)
            }
            loaded=true

            GlassLogger.i(
                "PrefsHelper",
                "Preferences loaded: enabled=$enabled, bgOpacity=$bgOpacity%, blur=$blurEnabled(mode=$blurMode, fps=$liveFps, idlePause=$idlePause, r=$blurRadius), stroke=$iconStroke($strokeOpacity%), glyphShadow=$glyphShadow($shadowOpacity%), gradient=$gradientOpacity%, palette=$iconPalette"
            )
        } catch (t: Throwable) {
            GlassLogger.e("PrefsHelper", "Failed to load preferences", t)
        }
    }
}
