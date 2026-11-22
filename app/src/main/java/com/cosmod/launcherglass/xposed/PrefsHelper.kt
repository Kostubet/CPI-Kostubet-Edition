package com.cosmod.launcherglass.xposed

import android.app.WallpaperManager
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Trace
import com.cosmod.launcherglass.ui.GlassDispersionSettings
import com.cosmod.launcherglass.ui.DesktopAppearance
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.LiveBlurEngine
import com.cosmod.launcherglass.util.WallpaperHelper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object PrefsHelper {
    @Volatile var iconsFeatureEnabled=true
    @Volatile internal var nativeClassicIcons = false
        private set
    @Volatile var nativeIconsSupported=true
        private set

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
    @Volatile var dockColorFilter:android.graphics.ColorMatrixColorFilter?=null
        private set
    private var lastDockSaturation = 100
    private var lastDockDarkness = 0
    @Volatile var dockBlur=14
    @Volatile var dockStyle=0
    @Volatile var dockOpacity=0
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
    val dockGlassEnabled get() = enabled && dockStyle > 0
    internal val shortcutGlassEnabled get() = enabled && blurEnabled && iconsFeatureEnabled
    val wallpaperEffectsEnabled get()=(blurEnabled && (iconsFeatureEnabled || folderBlurEnabled)) ||
        widgetGlassEnabled || dockGlassEnabled || PageIndicatorGlass.profile.mode>0
    @Volatile var drawerBlurEnabled: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_DRAWER_BLUR_ENABLED)
    @Volatile var blurRadius: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_RADIUS)
    @Volatile var blurMode: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_MODE)
    @Volatile var blurStyle: Int = SettingsDefaults.integer(SettingsProvider.KEY_BLUR_STYLE)
    @Volatile var blurQuality=SettingsDefaults.integer(SettingsProvider.KEY_BLUR_QUALITY)
    @Volatile var liquidWidth=SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_WIDTH)
    @Volatile var liquidDistortion=SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_DISTORTION)
    val liquidGlass:Boolean get()=blurStyle==SettingsProvider.BLUR_STYLE_LIQUID && android.os.Build.VERSION.SDK_INT>=33
    val liveFps get()=com.cosmod.launcherglass.util.PerformancePolicy.glassFps()
    @Volatile var idlePause: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_IDLE_PAUSE)
    @Volatile var iconStroke: Boolean = SettingsDefaults.boolean(SettingsProvider.KEY_ICON_STROKE)
    @Volatile var strokeOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_STROKE_OPACITY)
    @Volatile var glyphStroke = false
    @Volatile var glyphStrokeOpacity = 40
    @Volatile var glyphStrokeWidth = 35
    @Volatile var glyphStrokeBrightness = 45
    @Volatile var gradientOpacity: Int = SettingsDefaults.integer(SettingsProvider.KEY_GRADIENT_OPACITY)

    @Volatile var iconPalette = com.cosmod.launcherglass.util.IconPalette.from(0, 0, 0, 0)
    @Volatile var launcherIconSize=100
        private set
    @Volatile var desktopLargeIcons = false
        private set
    @Volatile var desktopLightIconGlass = false
        private set
    @Volatile var desktopDimWallpaper = false
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
    private val preferenceReader by lazy { Executors.newSingleThreadExecutor() }
    private val synchronousLoads = AtomicLong()
    private var initialized = false
    @Volatile private var loaded=false
    @Volatile private var currentDesktopAppearance: DesktopAppearance.Profile? = null
    fun usesDesktopAppearance() = enabled && currentDesktopAppearance?.let { it.mode != DesktopAppearance.Mode.MANUAL } == true
    private var followsWallpaper=false
    private var wallpaperTone:IconPalette.WallpaperTone?=null
    private var wallpaperToneLoaded = false

    internal fun backdropFilter(vibrancy:Int,palette:IconPalette)=
        com.cosmod.launcherglass.util.IconBackdropFilter.create(vibrancy,palette)
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        com.cosmod.launcherglass.util.RuntimeReport.loaded(context,"launcher")

        loadPrefs(context)
        runCatching {
            val manager=WallpaperManager.getInstance(context)
            manager.addOnColorsChangedListener({ colors,which ->
                if ((which and WallpaperManager.FLAG_SYSTEM) != 0) {
                    val tone = IconPalette.wallpaperTone(colors)
                    val changed = !wallpaperToneLoaded || tone != wallpaperTone
                    wallpaperTone = tone
                    wallpaperToneLoaded = true
                    if (enabled && followsWallpaper && changed) loadPrefs(context)
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
            val main = Handler(Looper.getMainLooper())
            var requested = false
            var reading = false
            val reload = object : Runnable {
                override fun run() {
                    if (reading || !requested) return
                    requested = false
                    reading = true
                    val lastSynchronousLoad = synchronousLoads.get()
                    preferenceReader.execute {
                        val stored = readStoredPrefs(context)
                        main.post {
                            reading = false
                            // A newer request can supersede the bundle while Binder is reading it.
                            if (!requested && lastSynchronousLoad == synchronousLoads.get() && stored != null) {
                                applyPrefs(context, stored)
                            }
                            if (requested && !main.hasCallbacks(this)) main.post(this)
                        }
                    }
                }
            }
            val observer = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    requested = true
                    if (!main.hasCallbacks(reload)) main.post(reload)
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

    fun retryOnResume(context: Context) {
        if (!loaded) loadPrefs(context)
    }

    fun loadPrefs(context: Context, systemDark: Boolean? = null) {
        synchronousLoads.incrementAndGet()
        val stored = readStoredPrefs(context) ?: return
        applyPrefs(context, stored, systemDark)
    }

    private fun readStoredPrefs(context: Context): Bundle? = runCatching {
        context.contentResolver.call(SettingsProvider.CONTENT_URI, SettingsProvider.METHOD_GET_ALL, null, null)
    }.onFailure {
        GlassLogger.e("PrefsHelper", "Failed to read preferences", it)
    }.getOrNull()

    private fun applyPrefs(
        context: Context,
        stored: Bundle,
        systemDark: Boolean? = null,
    ) {
        Trace.beginSection("CherryPick.applyLauncherPreferences")
        try {
            val actualSystemDark = systemDark ?: IconPalette.isSystemDark(context)
            val appearance = DesktopAppearance.read(stored, actualSystemDark)
            val previousAppearance = currentDesktopAppearance
            val appearanceChanged = appearance.mode != (previousAppearance?.mode ?: DesktopAppearance.Mode.MANUAL)
            val updatingDesktopProfile = previousAppearance != null && previousAppearance != appearance &&
                appearance.mode != DesktopAppearance.Mode.MANUAL
            val lightIconGlass = appearance.usesLightGlass(actualSystemDark)
            val iconPlateFinishChanged = desktopLightIconGlass != lightIconGlass
            val bundle = if (appearance.mode == DesktopAppearance.Mode.MANUAL) stored else Bundle(stored)
            DesktopAppearance.apply(bundle, appearance, actualSystemDark)

            val previousWallpaperEffects=wallpaperEffectsEnabled
            val newIconsFeatureEnabled=bundle.getBoolean(com.cosmod.launcherglass.ui.FeatureSections.key("ICONS"),true)
            val iconsSectionChanged=iconsFeatureEnabled!=newIconsFeatureEnabled
            iconsFeatureEnabled=newIconsFeatureEnabled
            nativeIconsSupported=bundle.getInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,0)!=2
            val performanceChanged=com.cosmod.launcherglass.util.PerformancePolicy.update(context,bundle)
            Ba31kAppTransitions.update(bundle)
            DockMaterial.updateUnlock(bundle)
            launcherIconSize=bundle.getInt("launcher_icon_size",100).coerceIn(70,130)
            desktopLargeIcons = bundle.getInt(DesktopAppearance.LARGE_ICONS) == 1
            val previousWallpaperDim = desktopDimWallpaper
            desktopDimWallpaper = bundle.getBoolean(SettingsProvider.KEY_ENABLED, true) &&
                bundle.getInt(DesktopAppearance.DIM_WALLPAPER) == 1
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
            followsWallpaper=iconFollowsWallpaper || newEnabled && (WidgetMaterialSettings.followsWallpaper(bundle) || SurfaceTints.followsWallpaper(bundle))
            if (followsWallpaper && !wallpaperToneLoaded) {
                wallpaperTone = IconPalette.wallpaperTone(context)
                wallpaperToneLoaded = true
            }
            val tintChanged=SurfaceTints.update(bundle,wallpaperTone)
            val widgetElementsChanged=WidgetElementColors.update(bundle)
            val newWidgets=WidgetMaterialSettings.read(bundle,wallpaperTone)
            val photoSourceChanged=newWidgets.backdropChanged(widgets)
            val widgetChanged=newWidgets!=widgets
            val glowChanged=WallpaperGlow.update(bundle)
            val edgeChanged=EdgeLight.update(bundle)
            val glassEngineChanged=KyantGlassEngine.update(bundle)
            PageIndicatorGlass.update(bundle)
            val depthChanged=newColorDepth!=colorDepthBits || newDepthStage!=colorDepthStage
            val processingDepthChanged=depthChanged && ((colorDepthBits<24 && colorDepthStage<=ColorDepthEffect.AFTER_BLUR) ||
                (newColorDepth<24 && newDepthStage<=ColorDepthEffect.AFTER_BLUR))
            val newDisableEdgeOptimization=bundle.getBoolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION,SettingsDefaults.boolean(SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION))
            val newOptimizeMiniIcons=bundle.getBoolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS,SettingsDefaults.boolean(SettingsProvider.KEY_OPTIMIZE_MINI_ICONS))
            // Experimental: effective only while developer mode is on.
            val newLiftedBackdrop=false
            val newBlurRadius = bundle.getInt(SettingsProvider.KEY_BLUR_RADIUS, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_RADIUS)).coerceIn(0,50)
            val newBlurMode = SettingsProvider.BLUR_MODE_LIVE
            val newBlurStyle = bundle.getInt(SettingsProvider.KEY_BLUR_STYLE, SettingsDefaults.integer(SettingsProvider.KEY_BLUR_STYLE)).coerceIn(0,1)
            val blurStyleChanged=newBlurStyle!=blurStyle
            val newBlurQuality=0
            val newLiquidWidth=bundle.getInt(SettingsProvider.KEY_LIQUID_WIDTH,SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_WIDTH)).coerceIn(0,100)
            val newLiquidDistortion=bundle.getInt(SettingsProvider.KEY_LIQUID_DISTORTION,SettingsDefaults.integer(SettingsProvider.KEY_LIQUID_DISTORTION)).coerceIn(0,300)
            val geometryChanged=newLiquidWidth!=liquidWidth || newLiquidDistortion!=liquidDistortion
            val qualityChanged=newBlurQuality!=blurQuality
            val newIdlePause = true
            val designEnabled = bundle.getBoolean(SettingsProvider.KEY_DESIGN_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_DESIGN_ENABLED))
            val newIconStroke = false
            val newStrokeOpacity = bundle.getInt(SettingsProvider.KEY_STROKE_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_STROKE_OPACITY))
            val newGlyphStroke = designEnabled && bundle.getBoolean(SettingsProvider.KEY_GLYPH_STROKE, false)
            val newGlyphStrokeOpacity = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_OPACITY, 40).coerceIn(0,100)
            val newGlyphStrokeWidth = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_WIDTH,35).coerceIn(0,100)
            val newGlyphStrokeBrightness = bundle.getInt(SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,45).coerceIn(0,100)
            val glyphOutlineChanged = newGlyphStroke != glyphStroke || newGlyphStrokeOpacity != glyphStrokeOpacity ||
                newGlyphStrokeWidth != glyphStrokeWidth || newGlyphStrokeBrightness != glyphStrokeBrightness
            val newGradientOpacity = if (designEnabled && bundle.getBoolean(SettingsProvider.KEY_VOLUME_ENABLED, SettingsDefaults.boolean(SettingsProvider.KEY_VOLUME_ENABLED)))
                bundle.getInt(SettingsProvider.KEY_GRADIENT_OPACITY, SettingsDefaults.integer(SettingsProvider.KEY_GRADIENT_OPACITY)) else 0
            val configuration = android.content.res.Configuration(context.resources.configuration)
            if (systemDark != null) {
                configuration.uiMode = (configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (systemDark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
            }
            val nativeDarkIcons = NativeIconAppearance.read(context, configuration) ?: actualSystemDark
            val classicIcons = NativeIconAppearance.isClassic(context)
            val classicChanged = classicIcons != nativeClassicIcons
            nativeClassicIcons = classicIcons
            val darkIcons = if (appearance.mode == DesktopAppearance.Mode.MANUAL ||
                appearance.mode == DesktopAppearance.Mode.STOCK) nativeDarkIcons else appearance.iconDark(actualSystemDark)
            val fluxSupport=FluxIconSupport.read(context)
            val reportSupport = fluxSupport != FluxIconSupport.UNKNOWN &&
                bundle.getInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT, 0) != fluxSupport
            val reportTheme = !bundle.containsKey(SettingsProvider.KEY_NATIVE_ICON_DARK) ||
                bundle.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK) != nativeDarkIcons
            if (reportSupport || reportTheme) {
                val report = Bundle().apply {
                    if (reportSupport) putInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT, fluxSupport)
                    if (reportTheme) putBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK, nativeDarkIcons)
                }
                context.contentResolver.call(SettingsProvider.CONTENT_URI, SettingsProvider.METHOD_REPORT_ICON_APPEARANCE, null, report)
            }
            val colorKeys = SettingsProvider.colorKeys(darkIcons)
            val hue=bundle.getInt(colorKeys[0],SettingsDefaults.integer(colorKeys[0]))
            val saturation=bundle.getInt(colorKeys[1],SettingsDefaults.integer(colorKeys[1]))
            val brightness=bundle.getInt(colorKeys[2],SettingsDefaults.integer(colorKeys[2]))
            val tone=wallpaperTone.takeIf {iconFollowsWallpaper}
            val newPalette=IconPalette.from(colorMode,tone?.hue ?: hue,tone?.saturationAt(saturation) ?: saturation,
                if(clearIcons)bundle.getInt(SettingsProvider.KEY_CLEAR_BRIGHTNESS,SettingsDefaults.integer(SettingsProvider.KEY_CLEAR_BRIGHTNESS))
                else brightness,darkIcons,clearIcons).withLogo(bundle.getBoolean("icon_logo_tint"),
                    bundle.getInt("icon_logo_hue",30),bundle.getInt("icon_logo_saturation",0),
                    bundle.getInt("icon_logo_brightness",90))
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

            val blurRadiusChanged=newBlurRadius!=blurRadius
            val blurModeChanged=newBlurMode!=blurMode
            val blurEnabledChanged=newBlurEnabled!=blurEnabled
            val drawerChanged=newDrawerBlurEnabled!=drawerBlurEnabled
            val folderEnabledChanged=newFolderBlurEnabled!=folderBlurEnabled
            val materialChanged=iconsSectionChanged || classicChanged || iconPlateFinishChanged || accentChanged || edgeChanged || opticsChanged || glassEngineChanged || performanceChanged || backendChanged || newBgOpacity!=bgOpacity || newBlurEnabled!=blurEnabled ||
                newFolderBlurEnabled!=folderBlurEnabled || newFolderIconsBlurEnabled!=folderIconsBlurEnabled || newDrawerBlurEnabled!=drawerBlurEnabled ||
                newIconStroke != iconStroke || newStrokeOpacity != strokeOpacity ||
                newGradientOpacity != gradientOpacity || glyphOutlineChanged || paletteChanged ||
                blurColorsChanged || blurStyleChanged || blurRadiusChanged || geometryChanged || vibrancyChanged || widgetChanged || widgetElementsChanged || tintChanged || depthChanged || glowChanged || newDisableEdgeOptimization!=disableEdgeOptimization || newOptimizeMiniIcons!=optimizeMiniIcons || newLiftedBackdrop!=liftedBackdrop

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
            desktopLightIconGlass = lightIconGlass
            blurEnabled = newBlurEnabled
            colorDepthBits=newColorDepth
            colorDepthStage=newDepthStage
            disableEdgeOptimization=newDisableEdgeOptimization
            optimizeMiniIcons=newOptimizeMiniIcons
            liftedBackdrop=newLiftedBackdrop
            folderBlurEnabled = newFolderBlurEnabled
            folderIconsBlurEnabled=newFolderIconsBlurEnabled
            drawerBlurEnabled = newDrawerBlurEnabled
            val dockSaturation=bundle.getInt("dock_backdrop_saturation",100).coerceIn(0,200)
            val dockDarkness=bundle.getInt("dock_backdrop_darkness",0).coerceIn(0,100)
            if (lastDockSaturation != dockSaturation || lastDockDarkness != dockDarkness) {
                dockColorFilter = if (dockSaturation == 100 && dockDarkness == 0) null
                else android.graphics.ColorMatrixColorFilter(
                    com.cosmod.launcherglass.util.DockToneMatrix.values(dockSaturation, dockDarkness))
                lastDockSaturation = dockSaturation
                lastDockDarkness = dockDarkness
            }
            dockBlur=bundle.getInt(SettingsProvider.KEY_DOCK_BLUR,14).coerceIn(0,40)
            dockStyle=bundle.getInt(SettingsProvider.KEY_DOCK_STYLE,0).coerceIn(0,2)
            dockOpacity=com.cosmod.launcherglass.ui.GlobalGlassSettings.opacity(bundle).coerceIn(0,100)
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
            idlePause = newIdlePause
            iconStroke = newIconStroke
            strokeOpacity = newStrokeOpacity
            glyphStroke = newGlyphStroke
            glyphStrokeOpacity = newGlyphStrokeOpacity
            glyphStrokeWidth = newGlyphStrokeWidth
            glyphStrokeBrightness = newGlyphStrokeBrightness
            gradientOpacity = newGradientOpacity
            val folderChanged=FolderSettings.update(bundle)
            WallpaperHelper.onProcessingSettingsChanged()
            if (!NeutralBackdrop.onSettingsChanged() && (blurEnabledChanged || blurRadiusChanged ||
                performanceChanged || blurStyleChanged || qualityChanged || vibrancyChanged ||
                processingDepthChanged || backendChanged || folderChanged || folderEnabledChanged ||
                shadowToneChanged || photoSourceChanged || masterChanged || glassEngineChanged)) {
                if (updatingDesktopProfile && !masterChanged && !performanceChanged && !backendChanged) {
                    WallpaperHelper.invalidateForAppearance()
                } else {
                    WallpaperHelper.invalidate()
                }
            }

            // Publish all new values before invalidating recorded materials.
            // In particular, a contour toggle must rebuild the overlay itself,
            // not only update the wallpaper sampling RenderNode.
            if (materialChanged || folderChanged) {
                if (updatingDesktopProfile && !masterChanged && !backendChanged && !performanceChanged &&
                    !geometryChanged && !edgeChanged && !opticsChanged) {
                    // GlassStyle keys each sampler by the selected engine and its optics.
                    // Switching engines here can reuse the existing icon materials.
                    IconGlassHook.invalidateFolderPreviews()
                } else IconGlassHook.invalidateShaders()
            }
            val redrawAppearance = updatingDesktopProfile && !masterChanged && !backendChanged && !performanceChanged
            val redrawIcons = materialChanged || folderChanged || paletteChanged
            val redrawWidgets = masterChanged || widgetChanged || widgetElementsChanged || photoSourceChanged ||
                edgeChanged || glassEngineChanged
            val fade = DesktopAppearanceTransition.finishWhenRedrawn(stored)
            val batchWidgets = redrawAppearance && redrawWidgets && fade != null
            if (redrawIcons && !redrawAppearance) IconGlassHook.invalidateActiveIcons()
            // Quick appearance profiles use the same native drawables. Preserve
            // their source layers and skip a native model reload when entering or leaving Stock.
            if (masterChanged || backendChanged || (iconsSectionChanged && !appearanceChanged)) {
                IconGlassHook.onIconThemeChanged()
                IconGlassHook.reloadNativeIcons()
            }

            if(enabled){
                ModuleHooks.setEnabled(true)
                com.cosmod.launcherglass.util.LauncherWallpaperCapture.onModuleEnabled()
            }
            if(!loaded || masterChanged || blurModeChanged || drawerChanged || performanceChanged ||
                wallpaperEffectsEnabled!=previousWallpaperEffects)LiveBlurEngine.onPrefsChanged()
            NativeSurfaceLive.onSettingsChanged()
            if(masterChanged || iconsSectionChanged || folderEnabledChanged || materialChanged || folderChanged)FolderMaterialHook.onSettingsChanged()
            LiftedBackdrop.onSettingsChanged()
            if(masterChanged || iconsSectionChanged || materialChanged || paletteChanged)MultiIconMaterialHook.onSettingsChanged()
            if (redrawWidgets) PhotoWidgetMaterial.onSettingsChanged(deferRedraw = batchWidgets)
            DockMaterial.onSettingsChanged()
            LauncherIconSize.onSettingsChanged()
            if (previousWallpaperDim != desktopDimWallpaper) DesktopAppearanceToolbar.updateWallpaperShade()
            if(!enabled){
                IconGlassHook.onModuleDisabled()
                com.cosmod.launcherglass.util.LauncherWallpaperCapture.onModuleDisabled()
                ModuleHooks.setEnabled(false)
            }
            loaded=true
            currentDesktopAppearance = appearance
            if (redrawAppearance && (redrawIcons || batchWidgets)) {
                IconGlassHook.invalidateAppearanceItems(redrawIcons, batchWidgets, fade)
            } else fade?.invoke()
            if (enabled && (appearanceChanged || masterChanged) && appearance.mode != DesktopAppearance.Mode.MANUAL) {
                NativeFluxIconStyle.request(context)
            }

            if (!redrawAppearance) GlassLogger.i(
                "PrefsHelper",
                "Preferences loaded: enabled=$enabled, bgOpacity=$bgOpacity%, blur=$blurEnabled(mode=$blurMode, fps=$liveFps, idlePause=$idlePause, r=$blurRadius), stroke=$iconStroke($strokeOpacity%), gradient=$gradientOpacity%, palette=$iconPalette"
            )
        } catch (t: Throwable) {
            GlassLogger.e("PrefsHelper", "Failed to load preferences", t)
        } finally {
            Trace.endSection()
        }
    }
}
