package com.cosmod.launcherglass.ui

import android.content.*
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import com.cosmod.launcherglass.ui.onextbox.ColorOsAppBarBlurView
import io.github.suqi8.coui.kmp.preference.WindowDropdownPreference
import io.github.suqi8.coui.kmp.basic.BasicComponent


import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp as couiDp
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.DropdownDefaults
import io.github.suqi8.coui.kmp.theme.defaultTextStyles
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.SuUtil
import com.cosmod.launcherglass.util.LsposedScopeStatus
import com.cosmod.launcherglass.util.FolderPreviewKind
import com.cosmod.launcherglass.util.CatalogSearchGeometry
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.SurfacePreviewKind
import com.cosmod.launcherglass.util.WidgetPreviewKind
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var page: FrameLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val previewExecutor = Executors.newSingleThreadExecutor { task -> Thread(task,"CPI-Preview") }
    private var pendingConfigExport:String?=null
    private data class ImportRequest(val anchor:View,val dialog:CouiAnchoredDialog)
    private var pendingConfigImport:ImportRequest?=null
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json=pendingConfigExport
        pendingConfigExport=null
        if(uri!=null && json!=null)saveConfig(uri,json)
    }
    private val importSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val request=pendingConfigImport
        pendingConfigImport=null
        if(uri!=null)readConfig(uri,request)
    }
    private val bannerPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) BannerTestNotification.post(this)
        else Toast.makeText(this,R.string.banner_test_permission,Toast.LENGTH_SHORT).show()
    }
    private val previewPhotoPicker=registerForActivityResult(ActivityResultContracts.PickVisualMedia()){uri->
        if(uri!=null)previewExecutor.execute {
            val result=runCatching {com.cosmod.launcherglass.util.PreviewPhotoStore.replace(applicationContext,uri)}
            runOnUiThread {
                if(!isDestroyed)result.fold({image->setPreviewPhoto(image);previewWidgetRefresh?.invoke()},
                    {Toast.makeText(this,R.string.ui_preview_photo_error,Toast.LENGTH_LONG).show()})
            }
        }
    }
    private var previewPhoto:android.graphics.Bitmap?=null
    private val previewPhotoScenes = mutableMapOf<WidgetPreviewKind, NativePreviewScenes.Scene>()
    private val previewWidgets=mutableMapOf<Int,NativePreviewScenes.Scene>()
    private val previewWidgetPending=mutableSetOf<Int>()
    private val previewWidgetAttempted=mutableSetOf<Int>()
    private val previewFolders=mutableMapOf<Int,NativePreviewScenes.Scene>()
    private val previewFolderPending=mutableSetOf<Int>()
    private val previewFolderAttempted=mutableSetOf<Int>()
    private var previewFolderRefresh:(()->Unit)?=null
    private val previewMaterials=mutableMapOf<com.cosmod.launcherglass.util.SurfacePreviewKind,NativePreviewScenes.Scene>()
    private val previewMaterialPending=mutableSetOf<com.cosmod.launcherglass.util.SurfacePreviewKind>()
    private val previewMaterialAttempted=mutableSetOf<com.cosmod.launcherglass.util.SurfacePreviewKind>()
    private var previewMaterialRefresh:(()->Unit)?=null

    private data class Gate(val view: View, val allowed: () -> Boolean)
    private class SectionControls {
        val gates=mutableListOf<Gate>()
        val keys=linkedSetOf<String>()
        val refreshers=mutableListOf<()->Unit>()
    }
    private var pageControls=SectionControls()
    private var previewControls:SectionControls?=null
    private var previewSection:Screen?=null
    private val sectionScreen get()=previewSection ?: screen
    private val gates get()=(previewControls ?: pageControls).gates
    private val featureKeys get()=(previewControls ?: pageControls).keys
    private val refreshers get()=(previewControls ?: pageControls).refreshers
    private val previewDraft=mutableMapOf<String,Int>()
    private var glassPreview:GlassPreview?=null
    private var glassPreviewScreen:Screen?=null
    private var lockPreviewSurface=LockscreenSettings.Surface.NOTIFICATIONS
    private var previewWallpaper: android.graphics.Bitmap? = null
    private var previewWallpaperId: Int? = null
    private var previewIcons: List<com.cosmod.launcherglass.util.PreviewIcons.Icon> = emptyList()
    private var previewLoading = false
    private var previewLastAttempt = 0L
    private var previewLastSurface:Screen?=null
    private var previewSettingsEpoch=0
    private var previewStatusRefresh:(()->Unit)?=null
    private var previewBannerDark:Boolean?=null
    private var previewBannerRefresh: (() -> Unit)? = null
    private var previewWidgetRefresh:(()->Unit)?=null
    private val refreshPreview=Runnable {glassPreview?.let {previewLastAttempt=0L;loadPreviewScene(it)}}

    private var pageLayer:FrameLayout?=null
    private var backDestination:FrameLayout?=null
    private var backHeaderInset:((Int)->Unit)?=null
    private var backBottomInset:((Int)->Unit)?=null
    private var backPreview:View?=null
    private var backSwipeSide=0
    private var topInset=0
    private var bottomInset=0
    private var applyHeaderInset:((Int)->Unit)?=null
    private var applyBottomInset:((Int)->Unit)?=null
    private var currentScroll: androidx.core.widget.NestedScrollView? = null
    private enum class Screen(val title: Int, val icon: Int, val tint: Int, val keywords: Int) {
        OVERVIEW(R.string.ui_cpi_home, R.drawable.ic_cpi_home, 0, 0),
        HOME(R.string.ui_cpi_features, R.drawable.ic_cpi_features, 0, 0),
        GLASS(R.string.ui_global_glass, R.drawable.ic_nav_glass, 0x498DFF, R.string.ui_global_glass_keywords),
        ICONS(R.string.ui_surface_icons, R.drawable.ic_nav_glass, 0x498DFF, R.string.ui_catalog_keywords_icons),
        FOLDERS(R.string.ui_surface_folders, R.drawable.ic_catalog_folder, 0xF49B46, R.string.ui_catalog_keywords_folders),
        PHOTO(R.string.ui_catalog_photo, R.drawable.ic_catalog_photo, 0xF46785, R.string.ui_catalog_keywords_photo),
        DOCK(R.string.ui_surface_dock, R.drawable.ic_catalog_dock, 0x6D85F4, R.string.ui_catalog_keywords_dock),
        BANNERS(R.string.ui_catalog_banners, R.drawable.ic_catalog_bell, 0x49A0FF, R.string.ui_catalog_keywords_banners),
        LIVE(R.string.ui_catalog_live, R.drawable.ic_catalog_island, 0x926DF5, R.string.ui_catalog_keywords_live),
        VOLUME(R.string.ui_volume_glass, R.drawable.ic_catalog_bell, 0x628EF1, R.string.ui_volume_glass_hint),
        SHADE(R.string.shade_glass_title, R.drawable.ic_cpi_features, 0x628EF1, R.string.shade_glass_keywords),
        LOCKSCREEN(R.string.lock_glass_title, R.drawable.ic_catalog_lock, 0x7665EF, R.string.lock_glass_keywords),
        LIGHT(R.string.ui_catalog_light, R.drawable.ic_nav_light, 0xE5AA27, R.string.ui_catalog_keywords_light),
        MOTION(R.string.ui_ba31k_title, R.drawable.ic_catalog_motion, 0x55A4ED, R.string.ui_catalog_keywords_motion),
        CAMERA_ZOOM(R.string.camera_zoom_title, R.drawable.ic_cpi_camera, 0x6793F2, R.string.camera_zoom_keywords),
        PERFORMANCE(R.string.ui_performance, R.drawable.ic_catalog_speed, 0x31B881, R.string.ui_catalog_keywords_performance),
        TOOLS(R.string.ui_about_module, R.drawable.ic_cpi_module, 0, 0),
        SETTINGS(R.string.ui_cpi_settings, R.drawable.ic_settings, 0x969AA8, R.string.ui_catalog_keywords_tools),
        DEVICE(R.string.ui_device_details, R.drawable.ic_cpi_module, 0, 0),
        CONTRIBUTORS(R.string.ui_about_contributors, R.drawable.ic_cpi_people, 0, 0),
    }
    private var rootNavigation:CouiRootNavigation?=null
    private var shadePanel=ShadeGlassSettings.Panel.CONTROL
    private var shadeComponent=0
    private var shadePreview:ShadeGlassPreview?=null
    private var rootNavigationAppearance: Triple<Boolean, Int, Boolean>? = null
    private val rootNavigationIndex=mutableIntStateOf(0)
    private val floatingTitles=java.util.WeakHashMap<TextView,java.lang.ref.WeakReference<TextView>>()
    private var screen=Screen.OVERVIEW
    private var detailParent=Screen.HOME
    private val tabScroll=mutableMapOf<Screen,Int>()
    private var searchQuery = ""
    private var searchInput: CouiSearchHost? = null
    private var glowExpanded = false
    private var islandExpanded = false
    private var bannerExpanded = false
    private var folderExpanded = false
    private var blurExpanded = false
    private var designExpanded = false
    private var hero:View?=null
    private var changingTheme=false
    private var rootStatus:TextView?=null
    private var rootVersion:String?=null
    private var rootChecked=false
    private var lsposedText:TextView?=null
    private var fluxWarning:View?=null
    private var frameworkWarning:View?=null
    private val supportListener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key==SettingsProvider.KEY_NATIVE_FLUX_SUPPORT)runOnUiThread {updateFluxWarning()}
        if(key?.startsWith("runtime_")==true)runOnUiThread {updateStatus()}
    }
    private var scopeState:LsposedScopeStatus.State?=null
    private var statusEpoch=0
    private var restartBusy = false
    private var updatingUi = false
    private val background get() = ContextCompat.getColor(this, R.color.cpi_ui_background)
    private val panel get() = ContextCompat.getColor(this, R.color.cpi_ui_panel)
    private val raised get() = ContextCompat.getColor(this, R.color.cpi_ui_raised)
    private val primary get() = ContextCompat.getColor(this,R.color.cpi_ui_accent)
    private val onSurface get() = ContextCompat.getColor(this,R.color.cpi_ui_text)
    private val secondaryText get() = ContextCompat.getColor(this,R.color.cpi_ui_muted)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val darkUi get()=androidx.core.graphics.ColorUtils.calculateLuminance(background)<.5
    private val coloredFeatures get()=sectionScreen==Screen.HOME && bool("coloros_ui_effects") && number("coloros_feature_background")!=0
    private val rootTabs=listOf(Screen.OVERVIEW,Screen.HOME,Screen.TOOLS)
    private var menuOverlay:CouiPopup?=null
    private var restartOverlay: View? = null
    private val restartDialogVisible = mutableStateOf(false)
    private var restartButtonBackdrop:ColorosHeaderBlur?=null
    private var tabRelease:OnextBarRelease?=null
    private var lastRestartPlacement:Boolean?=null
    private var headerBody:LinearLayout?=null
    private var atmosphere:ColorosAtmosphere?=null
    private var celebration:BannerCelebration?=null
    private fun View.settingsPress(){if(this !is CouiControl && bool("coloros_ui_effects"))springPress()}
    private fun glass(color:Int,radius:Int,material:Boolean=true,legacyEdge:Boolean=false)=ColorosGlass(color,dp(radius).toFloat(),darkUi,
        resources.displayMetrics.density,material && bool("coloros_ui_effects"),legacyEdge)
    private fun outline(radius:Int,opacity:Float=1f)=ColorosOutline(resources.displayMetrics.density,
        dp(radius).toFloat(),darkUi,opacity=opacity)
    private fun glassRipple(color:Int,radius:Int)=RippleDrawable(
        ColorStateList.valueOf(withAlpha(if(darkUi)Color.WHITE else Color.BLACK,if(darkUi)62 else 28)),
        glass(color,radius,false),panelShape(Color.WHITE,radius))
    private fun bool(key:String):Boolean = if(key.startsWith("folder_") && key.removePrefix("folder_") in FolderSettingsKeys.booleans)
        prefs.getBoolean(key,prefs.getBoolean(key.removePrefix("folder_"),SettingsDefaults.boolean(key)))else prefs.getBoolean(key,SettingsDefaults.boolean(key))
    private fun number(key:String):Int = if(key.startsWith("folder_") && key.removePrefix("folder_") in FolderSettingsKeys.integers)
        prefs.getInt(key,prefs.getInt(key.removePrefix("folder_"),SettingsDefaults.integer(key)))else prefs.getInt(key,SettingsDefaults.integer(key))
    private fun withAlpha(color:Int,alpha:Int)=androidx.core.graphics.ColorUtils.setAlphaComponent(color,alpha)
    private fun darkIcons() = prefs.getBoolean(SettingsProvider.KEY_NATIVE_ICON_DARK, com.cosmod.launcherglass.util.IconPalette.isSystemDark(this))
    private fun master() = bool(SettingsProvider.KEY_ENABLED) && FeatureSections.enabled(prefs,sectionScreen.name)
    private fun blur() = master() && bool(SettingsProvider.KEY_BLUR_ENABLED)
    private fun folderOwn()=blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED) && bool(FolderSettingsKeys.OWN)
    private fun design() = master() && bool(SettingsProvider.KEY_DESIGN_ENABLED)

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(UiLanguage.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme=getSharedPreferences(SettingsProvider.PREFS_NAME,MODE_PRIVATE).getInt("ui_theme",0)
        delegate.localNightMode=nightMode(theme)
        super.onCreate(savedInstanceState)
        pendingConfigExport=savedInstanceState?.getString("pendingConfigExport")
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences(SettingsProvider.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(supportListener)
        SettingsDefaults.initialize(this, prefs)
        screen = Screen.entries.firstOrNull { it.name == savedInstanceState?.getString("catalogScreen") } ?: if(savedInstanceState?.getString("catalogScreen")=="BA31K_MOTION")Screen.MOTION else Screen.OVERVIEW
        detailParent=Screen.entries.firstOrNull {it.name==savedInstanceState?.getString("catalogParent") && it in rootTabs} ?: Screen.HOME
        for(tab in rootTabs)tabScroll[tab]=savedInstanceState?.getInt("catalogScroll_"+tab.name)
            ?: if(tab==Screen.HOME)savedInstanceState?.getInt("catalogHomeScroll") ?: 0 else 0
        searchQuery = savedInstanceState?.getString("catalogQuery").orEmpty()
        blurExpanded = savedInstanceState?.getBoolean("blurExpanded") ?: false
        designExpanded = savedInstanceState?.getBoolean("designExpanded") ?: false
        root = column().apply { clipChildren=false;clipToPadding=false;setBackgroundColor(this@MainActivity.background) }
        setContentView(root)
        updateSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // The scene fills the window; only controls and the scroll end avoid navigation.
            view.setPadding(bars.left, 0, bars.right, 0)
            if(topInset!=bars.top){
                topInset=bars.top
                applyHeaderInset?.invoke(topInset)
                backHeaderInset?.invoke(topInset)
            }
            if(bottomInset!=bars.bottom){
                bottomInset=bars.bottom
                applyBottomInset?.invoke(bottomInset)
                backBottomInset?.invoke(bottomInset)
            }
            insets
        }
        page = FrameLayout(this).apply { isFocusableInTouchMode=true;clipChildren=false;clipToPadding=false }
        root.addView(page, LinearLayout.LayoutParams(-1, -1))
        showPageWithDestination()
        currentScroll?.post { currentScroll?.scrollTo(0,savedInstanceState?.getInt("catalogScroll") ?: 0) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackProgressed(backEvent:androidx.activity.BackEventCompat){
                if(screen in rootTabs || restartOverlay!=null || menuOverlay!=null || !canAnimate())return
                backSwipeSide=if(backEvent.swipeEdge==androidx.activity.BackEventCompat.EDGE_LEFT)1 else -1
                val t=kotlin.math.sin(backEvent.progress.coerceIn(0f,1f)*Math.PI.toFloat()*.5f)
                showBackPreview(t)
                pageLayer?.apply {
                    animate().withEndAction(null).cancel()
                    translationX=width*.34f*t*backSwipeSide
                }
            }
            override fun handleOnBackCancelled(){
                backSwipeSide=0
                pageLayer?.animate()?.translationX(0f)
                    ?.setDuration(320L)?.setInterpolator(colorosEase)
                    ?.withEndAction {removeBackPreview()}?.start()
            }
            override fun handleOnBackPressed() {
                if (previewTouch!=null)previewTouch?.dialog?.cancel()
                else if (restartOverlay!=null)closeRestartConfirmation()
                else if (menuOverlay!=null) closeChoicePopup()
                else if(screen !in rootTabs)backToCatalog()
                else if(screen==Screen.TOOLS)openTab(Screen.OVERVIEW)
                else if (screen==Screen.HOME && (searchQuery.isNotEmpty() || searchInput?.isInputFocused()==true)) {
                    searchInput?.setQuery("")
                    hideSearchKeyboard()
                    page.requestFocus()
                } else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if(::prefs.isInitialized && number("coloros_feature_background")==2)atmosphere?.start()
        glassPreview?.let(::loadPreviewScene)
        if(::prefs.isInitialized) {
            refreshControls()
            updateStatus()
            val epoch=++statusEpoch
            executor.execute {
                val state=LsposedScopeStatus.read(this)
                val version=if(rootChecked)rootVersion else OnextDeviceInfo.rootVersion()
                runOnUiThread {
                    if(epoch==statusEpoch && !isDestroyed){
                        scopeState=state;rootVersion=version;rootChecked=true
                        updateStatus()
                    }
                }
            }
        }
    }
    override fun onPause(){
        atmosphere?.stop()
        celebration?.stop()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingConfigExport",pendingConfigExport)
        outState.putString("catalogScreen", screen.name)
        outState.putString("catalogQuery", searchQuery)
        outState.putString("catalogParent",detailParent.name)
        if(screen in rootTabs)tabScroll[screen]=currentScroll?.scrollY ?: 0
        for((tab,y) in tabScroll)outState.putInt("catalogScroll_"+tab.name,y)
        outState.putInt("catalogScroll", currentScroll?.scrollY ?: 0)
        outState.putBoolean("blurExpanded", blurExpanded)
        outState.putBoolean("designExpanded", designExpanded)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        previewTouch?.velocity?.recycle()
        previewTouch=null
        settingsDialogOwner?.dispose()
        if(::page.isInitialized)page.removeCallbacks(refreshPreview)
        backDestination=null
        if(::prefs.isInitialized)prefs.unregisterOnSharedPreferenceChangeListener(supportListener)
        previewExecutor.shutdownNow()
        executor.shutdown()
        super.onDestroy()
    }
    private fun refreshControls() {
        updatingUi = true
        try {
            refreshers.forEach { it() }
        } finally { updatingUi=false }
        updateGates()
    }
    private fun reveal(view:View,open:Boolean) {
        val visibility=if(open)View.VISIBLE else View.GONE
        if(view.visibility==visibility)return
        val container=view.parent as? ViewGroup
        if(container!=null && canAnimate()){
            val transition=android.transition.TransitionSet()
                .addTransition(android.transition.ChangeBounds())
                .addTransition(android.transition.Fade().addTarget(view))
                .setDuration(if(open)320L else 245L)
                .setInterpolator(colorosEase)
            android.transition.TransitionManager.beginDelayedTransition(container,transition)
        }
        view.visibility=visibility
        view.animate().cancel()
        view.scaleX=1f;view.scaleY=1f
        if(open && canAnimate()){
            view.pivotX=view.width*.5f
            view.pivotY=0f
            view.scaleX=.989f;view.scaleY=.94f
            view.animate().scaleX(1f).scaleY(1f).setDuration(400L)
                .setInterpolator(colorosEase).start()
        }
    }

    private val colorosEase=android.view.animation.PathInterpolator(.22f,.75f,.20f,1f)
    private fun canAnimate()=bool("coloros_ui_effects") && android.animation.ValueAnimator.areAnimatorsEnabled()

    private fun column() = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL
        clipChildren=false
        clipToPadding=false
    }
    private fun row() = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false }
    private val couiTextStyles = defaultTextStyles()
    private fun text(value: String, size: Float = 16f, muted: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(if (muted) secondaryText else onSurface)
        val style = if (bold) couiTextStyles.headlineL else if (muted) couiTextStyles.bodyM else couiTextStyles.bodyL
        val weight = style.fontWeight?.weight ?: if (bold) 500 else 400
        typeface = Typeface.create(Typeface.DEFAULT, weight, false)
        if (style.letterSpacing.isEm) letterSpacing = style.letterSpacing.value
        includeFontPadding = false
    }
    private fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) })
    }
    private fun space(parent: LinearLayout, height: Int) { parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(height))) }
    private fun haptic(view:View,tick:Boolean=false,confirm:Boolean=false){
        val effect=when {
            tick->HapticFeedbackConstants.CLOCK_TICK
            confirm->HapticFeedbackConstants.CONFIRM
            android.os.Build.VERSION.SDK_INT>=34->HapticFeedbackConstants.SEGMENT_TICK
            else->HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(effect)
    }
    private fun panelShape(color:Int,radius:Int=16)=GradientDrawable().apply {
        setColor(color)
        cornerRadius=dp(radius).toFloat()
    }

    private fun card(content:View,inner:Boolean=false):FrameLayout {
        val color=if(coloredFeatures)withAlpha(Color.WHITE,if(darkUi)27 else 154)else if(inner)raised else panel
        val radius=if(inner)16 else 20
        val edge=if(bool("coloros_ui_effects") && CouiAppearance.read(this)==CouiAppearance.MODERN)
            withAlpha(onSurface,if(darkUi)33 else 23) else Color.TRANSPARENT
        return CouiCardHost(this,color,radius,edge,content)
    }

    private fun chromeSurface() = when {
        coloredFeatures -> androidx.core.graphics.ColorUtils.blendARGB(panel, primary, if(darkUi) .24f else .10f)
        else -> panel
    }

    private fun button():CouiButton {
        val fill=androidx.core.graphics.ColorUtils.compositeColors(withAlpha(primary,22),panel)
        return CouiButton(this,darkUi,primary,onSurface,fill)
    }

    private fun closeChoicePopup(immediate:Boolean=false){
        val popup=menuOverlay ?: return
        menuOverlay=null
        if(immediate)page.removeView(popup) else popup.close()
    }

    private fun choicePopup(anchor:View,options:List<Pair<Int,Int>>,selected:Int,onPick:(Int)->Unit)=
        showChoicePopup(anchor,options.map {(id,label)->id to getString(label)},selected,onPick=onPick)

    private fun showChoicePopup(anchor:View,options:List<Pair<Int,String>>,selected:Int,widthDp:Int=198,onPick:(Int)->Unit){
        if(page.width<=0 || options.isEmpty())return
        closeChoicePopup(immediate=true)
        val anchorPos=IntArray(2);val pagePos=IntArray(2)
        anchor.getLocationInWindow(anchorPos);page.getLocationInWindow(pagePos)
        lateinit var popup:CouiPopup
        popup=CouiPopup(this,darkUi,primary,chromeSurface(),options,selected,{
            page.removeView(popup)
            if(menuOverlay===popup)menuOverlay=null
        },onPick)
        menuOverlay=popup
        page.addView(popup,FrameLayout.LayoutParams(anchor.width.coerceAtLeast(1),anchor.height.coerceAtLeast(1)).apply {
            leftMargin=anchorPos[0]-pagePos[0];topMargin=anchorPos[1]-pagePos[1]
        })
    }

    private fun optionRow(title: String, value: String) = CouiSelection(this, darkUi, primary, title, value)

    private fun divider(parent:LinearLayout,startInset:Int=16){
        val line = View(this).apply { setBackgroundColor(withAlpha(onSurface, 19)) }
        parent.addView(line, LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(startInset); rightMargin = dp(16) })
    }
    private fun gate(view:View,allowed:()->Boolean){gates.add(Gate(view,allowed))}
    private fun enableTree(view:View,enabled:Boolean){
        view.isEnabled=enabled
        if(view is ViewGroup)for(i in 0 until view.childCount)enableTree(view.getChildAt(i),enabled)
    }
    private fun updateGates() {
        gates.forEach { enableTree(it.view, it.allowed()) }
        gates.forEach {
            var ancestor = it.view.parent as? View
            var dimmedParent = false
            while (ancestor != null && ancestor !== page) {
                if (!ancestor.isEnabled) { dimmedParent = true; break }
                ancestor = ancestor.parent as? View
            }
            it.view.alpha = if (!it.allowed() && !dimmedParent) .38f else 1f
        }
    }

    private fun save(key: String, value: Any, engine:Pair<String,Boolean>?=null) {
        if(updatingUi)return
        prefs.edit().apply {
            if(key==FolderSettingsKeys.OWN && value==true){
                FolderSettingsKeys.integers.forEach {base -> val own=FolderSettingsKeys.key(base);if(!prefs.contains(own))putInt(own,number(base))}
                FolderSettingsKeys.booleans.forEach {base -> val own=FolderSettingsKeys.key(base);if(!prefs.contains(own))putBoolean(own,bool(base))}
                if(!prefs.contains(GlassDispersionSettings.FOLDERS))
                    putInt(GlassDispersionSettings.FOLDERS,number(GlassDispersionSettings.ICONS))
            }
            when(value) { is Boolean -> putBoolean(key, value); is Int -> putInt(key, value) }
            engine?.let { putBoolean(it.first,it.second) }
        }.apply()
        if (value == false && (key == com.cosmod.launcherglass.camera.CameraZoomSettings.DIAGNOSTICS ||
                key == SettingsProvider.KEY_ENABLED)) {
            com.cosmod.launcherglass.camera.CameraZoomSettings.clearRatios(this)
        }
        if(key!="coloros_ui_effects" && key!="coloros_feature_background")
            contentResolver.notifyChange(SettingsProvider.CONTENT_URI, null)
        updateGates();updateStatus()
        shadePreview?.refresh()
        if(screen==Screen.ICONS){
            previewSettingsEpoch++
            page.removeCallbacks(refreshPreview);page.postDelayed(refreshPreview,160L)
        }
        if(key==FolderSettingsKeys.OWN || key==SettingsProvider.KEY_DEVELOPER_MODE ||
            key==WidgetSettings.HUE || key==com.cosmod.launcherglass.util.IconPalette.FOLLOW_WALLPAPER ||
            key in setOf("banner_plate_hue","banner_plate_saturation","banner_plate_brightness"))refreshControls()
        if(key=="coloros_ui_effects" || key=="coloros_feature_background")refreshCatalogStyle()
    }
    private fun refreshCatalogStyle(){
        val y=currentScroll?.scrollY ?: 0
        showPage()
        currentScroll?.let {scroll->scroll.post {scroll.scrollTo(0,y)}}
    }
    private fun applySavedSettings(keys:Set<String>,values:Map<String,Any>){
        SettingsTransfer.write(prefs.edit(),values,keys).apply()
        contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null)
        refreshCatalogStyle()
    }

    private var settingsDialogOwner:CouiAnchoredDialog?=null
    private data class PreviewTouch(val id:Int,val startX:Float,val startY:Float,val dialog:CouiAnchoredDialog,
        val velocity:DialogSwipeVelocity=DialogSwipeVelocity(),var cancelSource:Boolean=true)
    private var previewTouch:PreviewTouch?=null
    private var touchId=-1
    private var touchX=0f
    private var touchY=0f

    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        val action=event.actionMasked
        val held=previewTouch
        if(held!=null){
            held.velocity.track(event,held.id)
            if(held.cancelSource){
                held.cancelSource=false
                val cancel=MotionEvent.obtain(event)
                cancel.action=MotionEvent.ACTION_CANCEL
                try {super.dispatchTouchEvent(cancel)} finally {cancel.recycle()}
            }
            val index=event.findPointerIndex(held.id)
            if(index>=0 && action!=MotionEvent.ACTION_CANCEL){
                held.dialog.dragFromAnchor(event.getRawY(index)-held.startY,event.getRawX(index)-held.startX)
            }
            if(index<0 || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_UP ||
                (action==MotionEvent.ACTION_POINTER_UP && event.actionIndex==index)){
                previewTouch=null
                touchId=-1
                val speed=held.velocity.atRelease(event.eventTime,held.id)
                held.velocity.recycle()
                held.dialog.releaseAnchor(action!=MotionEvent.ACTION_CANCEL && index>=0,speed)
            }
            return true
        }
        if(action==MotionEvent.ACTION_DOWN)touchId=event.getPointerId(0)
        val index=event.findPointerIndex(touchId)
        if(index>=0){
            touchX=event.getRawX(index)
            touchY=event.getRawY(index)
        }
        if(action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_UP ||
            (action==MotionEvent.ACTION_POINTER_UP && event.actionIndex==index))touchId=-1
        return super.dispatchTouchEvent(event)
    }

    private fun savedSettingsDialog(title:String,content:LinearLayout,anchor:View?=null):CouiAnchoredDialog {
        val body=column().apply {setPadding(dp(22),dp(19),dp(22),dp(16))}
        add(body,text(title,19f,bold=true),bottom=10)
        add(body,content)
        return anchoredDialog(body,anchor)
    }

    private fun anchoredDialog(content:View,anchor:View?,actions:CouiPreviewActions?=null,
        holdTouch:Boolean=false,swipeHeader:Boolean=false,onDismiss:()->Unit={}):CouiAnchoredDialog {
        val pointer=if(holdTouch)touchId else -1
        val pressX=touchX
        val pressY=touchY
        settingsDialogOwner?.dispose()
        val effects=arrayOfNulls<android.graphics.RenderEffect>(29)
        var last=0
        val dialog=CouiAnchoredDialog(this,content,darkUi,
            minOf(dp(420),page.width.takeIf {it>0}?.minus(dp(32)) ?: (resources.displayMetrics.widthPixels-dp(32))),
            anchor,canAnimate(),actions,pointer>=0,swipeHeader){amount->
            val step=(amount*effects.lastIndex).roundToInt().coerceIn(0,effects.lastIndex)
            if(step!=last){
                last=step
                val effect=if(step==0)null else effects[step] ?: run {
                    val radius=dp(if(actions==null)12 else 9)*step.toFloat()/effects.lastIndex
                    android.graphics.RenderEffect.createBlurEffect(radius,radius,
                        android.graphics.Shader.TileMode.CLAMP).also {effects[step]=it}
                }
                page.setRenderEffect(effect)
            }
        }
        settingsDialogOwner=dialog
        dialog.setOnDismissListener {
            if(previewTouch?.dialog===dialog){
                previewTouch?.velocity?.recycle()
                previewTouch=null
                touchId=-1
            }
            if(settingsDialogOwner===dialog){
                settingsDialogOwner=null
                page.setRenderEffect(null)
            }
            onDismiss()
        }
        dialog.show()
        if(pointer>=0){
            previewTouch?.velocity?.recycle()
            previewTouch=PreviewTouch(pointer,pressX,pressY,dialog)
        }
        return dialog
    }

    private fun nameSettings(keys:Set<String>,slot:String,legacySlot:String,profiles:Map<String,String>,onSaved:()->Unit){
        val form=column()
        val input=CouiField(this,darkUi,primary,getString(R.string.ui_saved_settings_name),"",
            onSurface,maxLength=48)
        form.addView(input,LinearLayout.LayoutParams(-1,-2))
        val actions=row().apply {setPadding(0,dp(12),0,0)}
        val cancel=button().apply {setText(android.R.string.cancel)}
        val saveButton=button().apply {setText(R.string.ui_preset_save)}
        actions.addView(cancel,LinearLayout.LayoutParams(0,dp(44),1f).apply {marginEnd=dp(8)})
        actions.addView(saveButton,LinearLayout.LayoutParams(0,dp(44),1f))
        add(form,actions)
        val dialog=savedSettingsDialog(getString(R.string.ui_preset_save),form)
        cancel.setOnClickListener {dialog.dismiss()}
        fun saveSettings(){
            val name=input.value.trim()
            if(name.isEmpty()){input.error=getString(R.string.ui_saved_settings_name);return}
            if(profiles.keys.any {it.equals(name,ignoreCase=true)}){
                input.error=getString(R.string.ui_saved_settings_duplicate)
                return
            }
            val payload=org.json.JSONObject(profiles).put(name,SettingsTransfer.export(prefs,keys))
            prefs.edit().putString(slot,payload.toString()).remove(legacySlot).apply()
            Toast.makeText(this,R.string.ui_preset_saved,Toast.LENGTH_SHORT).show()
            dialog.close(onSaved)
        }
        saveButton.setOnClickListener {saveSettings()}
        input.onSubmit={saveSettings()}
        dialog.animateWithKeyboard()
    }

    private fun openSavedSettings(keys:Set<String>,section:String,anchor:View?=null,holdTouch:Boolean=false){
        val slot="named_settings_"+section
        val legacySlot="saved_preset_"+section
        val profiles=linkedMapOf<String,String>()
        prefs.getString(legacySlot,null)?.let {profiles[getString(R.string.ui_saved_settings_legacy)]=it}
        prefs.getString(slot,null)?.let {json->
            runCatching {
                val stored=org.json.JSONObject(json)
                stored.keys().forEach {name->profiles[name]=stored.getString(name)}
            }.onFailure {Toast.makeText(this,R.string.ui_preset_error,Toast.LENGTH_SHORT).show()}
        }
        val parsed=profiles.mapValues {(_,payload)->runCatching {SettingsTransfer.parse(payload)}.getOrNull()}
        val active=parsed.entries.firstOrNull {(_,values)->values!=null && SettingsTransfer.matches(prefs,values,keys)}?.key
        val changed=active==null && !SettingsTransfer.matches(prefs,emptyMap(),keys)
        val form=column().apply {setPadding(dp(14),dp(10),dp(14),dp(16))}
        lateinit var dialog:CouiAnchoredDialog

        fun reopen(){openSavedSettings(keys,section,anchor)}
        fun saveCurrent(onSaved:()->Unit){nameSettings(keys,slot,legacySlot,profiles,onSaved)}
        fun writeProfiles(){
            prefs.edit().apply {
                if(profiles.isEmpty())remove(slot)
                else putString(slot,org.json.JSONObject(profiles).toString())
                remove(legacySlot)
            }.apply()
        }
        fun updateOrder(order:List<String>){
            if(order==profiles.keys.toList())return
            val sorted=order.associateWith {profiles.getValue(it)}
            profiles.clear()
            profiles.putAll(sorted)
            writeProfiles()
        }

        fun confirmDelete(names:Set<String>){
            val form=column()
            val message=if(names.size==1)getString(R.string.ui_saved_settings_delete_confirm,names.first())
                else resources.getQuantityString(R.plurals.ui_saved_settings_delete_multiple,names.size,names.size)
            add(form,text(message,15f))
            val actions=row().apply {setPadding(0,dp(12),0,0)}
            val cancel=button().apply {setText(android.R.string.cancel)}
            val delete=button().apply {setText(R.string.ui_saved_settings_delete)}
            actions.addView(cancel,LinearLayout.LayoutParams(0,dp(44),1f).apply {marginEnd=dp(8)})
            actions.addView(delete,LinearLayout.LayoutParams(0,dp(44),1f))
            add(form,actions)
            val confirmation=savedSettingsDialog(getString(R.string.ui_saved_settings_delete),form)
            confirmation.setOnCancelListener {reopen()}
            cancel.setOnClickListener {confirmation.close(::reopen)}
            delete.setOnClickListener {
                runCatching {
                    profiles.keys.removeAll(names)
                    writeProfiles()
                }.onSuccess {
                    Toast.makeText(this,R.string.ui_saved_settings_deleted,Toast.LENGTH_SHORT).show()
                    confirmation.close(::reopen)
                }.onFailure {Toast.makeText(this,R.string.ui_preset_error,Toast.LENGTH_SHORT).show()}
            }
        }

        fun select(name:String){
            val values=parsed[name] ?: run {
                Toast.makeText(this,R.string.ui_preset_error,Toast.LENGTH_SHORT).show();return
            }
            if(name==active)return
            dialog.close {
                if(!changed)applySavedSettings(keys,values)
                else confirmApplySavedSettings(name,keys,values,::reopen,::saveCurrent)
            }
        }
        val listHeight=((page.height/2-dp(60))/resources.displayMetrics.density).roundToInt().coerceIn(112,270)
        val names=profiles.keys.toList()
        add(form,CouiSlot(this,darkUi,primary){
            SavedProfileList(names,active,listHeight,ComposeColor(onSurface),bool("coloros_ui_effects"),::select,
                onSave={dialog.close {saveCurrent(::reopen)}},
                onClose={dialog.dismiss()},
                onOrder=::updateOrder,onDelete={selection->dialog.close {confirmDelete(selection)}})
        })
        dialog=anchoredDialog(form,anchor,holdTouch=holdTouch,swipeHeader=true)
    }

    private fun confirmApplySavedSettings(name:String,keys:Set<String>,values:Map<String,Any>,
        reopen:()->Unit,saveCurrent:(()->Unit)->Unit){
            val prompt=column()
            add(prompt,text(getString(R.string.ui_saved_settings_save_before_apply,name),15f),bottom=10)
            val buttons=column()
            add(prompt,buttons)
            val confirm=savedSettingsDialog(getString(R.string.ui_saved_settings_unsaved),prompt)
            confirm.setOnCancelListener {reopen()}
            add(buttons,CouiSlot(this,darkUi,primary){
                androidx.compose.foundation.layout.Column(verticalArrangement=Arrangement.spacedBy(8.couiDp)){
                    CouiAction(getString(R.string.ui_saved_settings_save_apply),primary=true,
                        modifier=Modifier.fillMaxWidth(),onClick={confirm.close {saveCurrent {applySavedSettings(keys,values)}}})
                    CouiAction(getString(R.string.ui_saved_settings_apply_without),primary=false,
                        modifier=Modifier.fillMaxWidth(),onClick={confirm.close {applySavedSettings(keys,values)}})
                }
            })
    }

    private fun addSavedSettings(body:LinearLayout,all:Boolean=false,position:Int=body.childCount){
        val portable=SettingsDefaults.portable().keys
        if(sectionScreen==Screen.FOLDERS){
            featureKeys.addAll((FolderSettingsKeys.integers+FolderSettingsKeys.booleans).map(FolderSettingsKeys::key))
        }
        val keys=if(all)portable else (featureKeys intersect portable)
        if(keys.isEmpty())return
        val section=if(all)"ALL" else sectionScreen.name
        val controls=column()
        add(controls,dialogPreference(R.string.ui_presets){anchor,held->openSavedSettings(keys,section,anchor,holdTouch=held)})
        if(!all){
            divider(controls)
            add(controls,preferenceLink(getString(R.string.ui_reset_section)){anchor->
                choicePopup(anchor,listOf(0 to R.string.ui_cancel,1 to R.string.ui_reset_section_confirm),-1){
                    if(it==1){
                        val defaults=if(section==Screen.FOLDERS.name)mapOf(
                            SettingsProvider.KEY_FOLDER_BLUR_ENABLED to bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED),
                            FeatureSections.key(Screen.FOLDERS.name) to bool(FeatureSections.key(Screen.FOLDERS.name)),
                            FolderSettingsKeys.OWN to true,
                            FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_TONE_ENABLED) to true,
                            FolderSettingsKeys.key(SettingsProvider.KEY_BG_OPACITY) to 16,
                            FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_SATURATION) to 110
                        ) else emptyMap()
                        applySavedSettings(keys,defaults)
                    }
                }
            })
        }
        val card=card(controls)
        if(all)add(body,card,bottom=8)
        else body.addView(card,position,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
    }
    private fun showLanguageDialog(view:View){
        val current=if(UiLanguage.tag(this)=="ru")0 else 1
        showChoicePopup(view,listOf(0 to "Русский",1 to "English"),current){next->
            if(next!=current){
                UiLanguage.choose(this,if(next==0)"ru" else "en")
                recreate()
            }
        }
    }

    private fun smallIcon(icon:Int,label:String,chrome:Boolean=false,action:(View)->Unit): CouiIconButton =
        CouiIconButton(this,darkUi,primary,onSurface,if(chrome)chromeSurface() else panel,
            icon,label,bool("coloros_ui_effects"),chrome).apply {
            contentDescription=label
            layoutParams=LinearLayout.LayoutParams(dp(46),dp(48))
            setOnClickListener { haptic(it);action(it) }
        }

    private fun saveTabScroll(){
        if(screen in rootTabs)tabScroll[screen]=currentScroll?.scrollY ?: 0
    }

    private fun openTab(next:Screen,release:OnextBarRelease?=null){
        if(next==screen)return
        saveTabScroll()
        hideSearchKeyboard()
        val direction=if(rootTabs.indexOf(next)>rootTabs.indexOf(screen))1 else -1
        tabRelease=release
        lastRestartPlacement=screen==Screen.HOME
        screen=next
        showPage(direction)
    }

    private fun openScreen(next:Screen){
        if(screen in rootTabs){
            saveTabScroll()
            detailParent=screen
            captureBackDestination()
        }
        hideSearchKeyboard()
        screen=next
        showPage(1)
    }

    private fun captureBackDestination(){
        backDestination?.takeIf {it!==pageLayer}?.let(page::removeView)
        backDestination=pageLayer?.takeIf {canAnimate()}
        backHeaderInset=applyHeaderInset
        backBottomInset=applyBottomInset
    }

    private fun showPageWithDestination(){
        if(screen !in rootTabs && backDestination==null && canAnimate()){
            val restored=screen
            screen=detailParent
            showPage()
            val scroll=currentScroll
            val position=tabScroll[screen] ?: 0
            scroll?.doOnLayout {scroll.scrollTo(0,position)}
            captureBackDestination()
            backDestination?.visibility=View.INVISIBLE
            screen=restored
        }
        showPage()
    }

    private fun showBackPreview(progress:Float){
        val view=backDestination ?: return
        view.animate().withEndAction(null).cancel()
        view.visibility=View.VISIBLE
        view.translationZ=-dp(2).toFloat()
        view.alpha=1f
        view.translationX = -view.width * .025f * (1f - progress) * backSwipeSide
        backPreview=view
        rootNavigation?.visibility=View.VISIBLE
        rootNavigation?.translationZ=-dp(6).toFloat()
        pageLayer?.let(page::bringChildToFront)
    }

    private fun removeBackPreview(){
        backPreview?.visibility=View.INVISIBLE
        backPreview?.translationZ=0f
        rootNavigation?.translationZ=0f
        rootNavigation?.visibility=if(screen in rootTabs)View.VISIBLE else View.GONE
        backPreview=null
    }

    private fun backToCatalog(){
        val side=backSwipeSide.takeIf {it!=0} ?: if(page.layoutDirection==View.LAYOUT_DIRECTION_RTL)-1 else 1
        backSwipeSide=0
        val destination=backDestination
        backPreview = null
        rootNavigation?.translationZ = 0f
        backHeaderInset=null;backBottomInset=null
        screen=detailParent
        showPage(-1,returningSide=side)
        backDestination = null
        if (destination != null) pageLayer?.doOnPreDraw { page.removeView(destination) }
    }

    private fun hideSearchKeyboard() = searchInput?.closeKeyboard()

    private val pageEase=android.view.animation.PathInterpolator(.22f,.68f,.24f,1f)

    private fun showPage(direction:Int=0,returningSide:Int=0){
        closeRestartConfirmation(immediate=true)
        closeChoicePopup(immediate=true)
        atmosphere?.stop()
        celebration?.stop()
        celebration=null
        pageControls=SectionControls()
        clearGlassPreview()
        hero=null;rootStatus=null;lsposedText=null;fluxWarning=null;frameworkWarning=null
        searchInput=null
        restartButtonBackdrop=null
        applyBottomInset=null
        val previous=pageLayer
        for(i in page.childCount-1 downTo 0)if(page.getChildAt(i)!==previous && page.getChildAt(i)!==rootNavigation && page.getChildAt(i)!==backDestination)page.removeViewAt(i)
        previous?.animate()?.withEndAction(null)?.cancel()
        val animate=direction!=0 && previous!=null && canAnimate() && page.isLaidOut
        if(!animate)for(i in page.childCount-1 downTo 0)if(page.getChildAt(i)!==rootNavigation && page.getChildAt(i)!==backDestination)page.removeViewAt(i)
        rootNavigation?.visibility=if(screen in rootTabs)View.VISIBLE else View.GONE
        val scene=if(coloredFeatures)ColorosAtmosphere(background,darkUi)else null
        atmosphere=scene
        val layer=FrameLayout(this).apply {
            clipChildren=false;clipToPadding=false
            if(scene!=null)background=scene else setBackgroundColor(this@MainActivity.background)
        }
        pageLayer=layer
        layer.tag=screen
        page.addView(layer,FrameLayout.LayoutParams(-1,-1))
        val shell=column()
        val scroll=ColorosScrollView(this,canAnimate()).apply {
            isFillViewport=true
            clipToPadding=false
            setPadding(0,topInset,0,bottomInset)
            isVerticalScrollBarEnabled=true
            isScrollbarFadingEnabled=true
        }
        val body=column().apply { clipChildren=false;clipToPadding=false;setPadding(dp(16),0,dp(16),dp(12)) }
        currentScroll=scroll
        headerBody=body
        val pinnedToolbar = if (screen !in rootTabs) {
            space(body, 62)
            CouiDetailToolbar(this, darkUi, primary, onSurface, getString(screen.title), getString(R.string.ui_back)) {
                backToCatalog()
            }
        } else null
        val catalog=screen==Screen.HOME
        val heading=text(getString(screen.title),32f,bold=true).apply {
            minHeight=dp(if(catalog)64 else if(pinnedToolbar!=null)47 else 104)
            gravity=if(catalog)Gravity.CENTER_VERTICAL else Gravity.BOTTOM
            setPadding(dp(if(catalog)5 else 0),0,dp(5),dp(if(catalog)0 else 9))
            ViewCompat.setAccessibilityHeading(this,true)
        }
        if(pinnedToolbar==null)add(body,heading)
        else heading.visibility=View.GONE
        heading.setTextColor(Color.TRANSPARENT)
        heading.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val floatingHeading=text(getString(screen.title),32f,bold=true).apply {
            gravity=heading.gravity
            setPadding(heading.paddingLeft,heading.paddingTop,heading.paddingRight,heading.paddingBottom)
            minHeight=heading.minHeight
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            ViewCompat.setAccessibilityHeading(this,true)
            translationZ=dp(4).toFloat()
            alpha=0f
        }
        floatingTitles[heading]=java.lang.ref.WeakReference(floatingHeading)
        heading.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->
            floatingHeading.layoutParams=FrameLayout.LayoutParams(heading.width,heading.height,Gravity.TOP).apply {leftMargin=heading.left}
        }
        scroll.addView(body,ViewGroup.LayoutParams(-1,-2))
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        layer.addView(shell,FrameLayout.LayoutParams(-1,-1))
        val headerBlur=if(bool("coloros_ui_effects"))ColorOsAppBarBlurView(this,darkUi,background).apply {updateAppearance(darkUi,this@MainActivity.background,0f)}else null
        val blurHeight=dp(if(screen==Screen.HOME)105 else 62)
        headerBlur?.let {layer.addView(it,FrameLayout.LayoutParams(-1,topInset+blurHeight,Gravity.TOP))}
        layer.addView(floatingHeading,FrameLayout.LayoutParams(-1,heading.minHeight,Gravity.TOP))
        val bottomFade=View(this).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(withAlpha(this@MainActivity.background,0),withAlpha(this@MainActivity.background,20),
                    withAlpha(this@MainActivity.background,if(darkUi)112 else 73)))
        }
        layer.addView(bottomFade,FrameLayout.LayoutParams(-1,dp(81)+bottomInset,Gravity.BOTTOM))
        applyBottomInset={inset->
            scroll.setPadding(0,topInset,0,inset)
            bottomFade.layoutParams=bottomFade.layoutParams.apply {height=dp(81)+inset}
        }
        applyHeaderInset={inset->
            scroll.setPadding(0,inset,0,bottomInset)
            headerBlur?.let {header->
                header.layoutParams=header.layoutParams.apply {height=inset+blurHeight}
            }
            pinnedToolbar?.let {toolbar->
                toolbar.layoutParams=(toolbar.layoutParams as FrameLayout.LayoutParams).apply {topMargin=inset+dp(2)}
            }
        }
        pinnedToolbar?.let {
            layer.addView(it,FrameLayout.LayoutParams(-1,dp(58),Gravity.TOP).apply {
                setMargins(dp(16),topInset+dp(2),dp(16),0)
            })
        }
        val navigation=if(screen in rootTabs)addRootTabs(body,scene)else null
        if(catalog)addRootRestart(layer)
        when(screen){
            Screen.OVERVIEW->buildOverview(body)
            Screen.HOME->buildCatalog(body,scroll,heading,headerBlur,layer,navigation)
            Screen.TOOLS->buildModule(body)
            Screen.DEVICE->buildDeviceDetails(body)
            Screen.CONTRIBUTORS->buildContributors(body)
            else->buildFeaturePage(body,screen)
        }
        if(screen in rootTabs)space(body,136)
        pinnedToolbar?.actions = featureRestartActions()
        if(screen!=Screen.HOME){
            fun updateHeader(y:Int){
                headerBlur?.updateAppearance(darkUi,background,(y/dp(30).toFloat()).coerceIn(0f,1f))
                restartButtonBackdrop?.showScroll(y)
                navigation?.second?.showScroll(y)
                if(pinnedToolbar==null)fadeRootTitle(heading,y)
            }
            val headerInset=applyHeaderInset
            applyHeaderInset={inset->headerInset?.invoke(inset);layer.doOnPreDraw {updateHeader(scroll.scrollY)}}
            scroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener {_,_,y,_,_->updateHeader(y)})
            layer.doOnPreDraw {updateHeader(scroll.scrollY)}
        }
        scroll.onPullChanged={
            restartButtonBackdrop?.refreshScene()
            navigation?.second?.refreshScene()
        }
        if(navigation!=null){
            val rootInset=applyHeaderInset
            applyHeaderInset={inset->rootInset?.invoke(inset);navigation.second?.topInset=inset}
        }
        if(scene!=null && number("coloros_feature_background")==2){
            scene.onMotion={navigation?.second?.refreshScene();restartButtonBackdrop?.refreshScene()}
            if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))scene.start()
        }
        if (screen in rootTabs && direction!=0) {
            val scrollPosition=tabScroll[screen] ?: 0
            scroll.doOnLayout {scroll.scrollTo(0,scrollPosition)}
        }
        page.requestFocus()
        updateGates();updateStatus()
        if(animate && previous!=null && returningSide!=0){
            // The real destination stays behind the moving page, including when
            // Android delivers only the final back callback.
            page.bringChildToFront(previous)
            previous.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            previous.animate().translationX(page.width*returningSide.toFloat())
                .setDuration(if(previous.translationX==0f)365L else 285L).setInterpolator(pageEase)
                .withEndAction {if(pageLayer!==previous){if(previous===backDestination)previous.visibility=View.INVISIBLE else page.removeView(previous)}}.start()
        }else if(animate && previous!=null){
            val tabs = screen in rootTabs && previous.tag in rootTabs
            val rtl=if(resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL)-1f else 1f
            previous.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val duration = if (tabs) 325L else 380L
            val offset = (if (tabs) page.width.toFloat() else page.width * .22f) * direction * rtl
            previous.alpha = 1f
            previous.animate().translationX(if(tabs)-offset else -offset*.24f)
                .setDuration(duration).setInterpolator(pageEase).start()
            layer.translationX = offset
            layer.alpha = if(tabs)1f else 0f
            layer.animate().translationX(0f).alpha(1f).setDuration(duration).setInterpolator(pageEase)
                .withEndAction {
                    if (pageLayer === layer) {
                        if (previous === backDestination) {
                            previous.visibility = View.INVISIBLE
                            previous.translationX = 0f
                        } else {
                            page.removeView(previous)
                        }
                    }
                }.start()
        }
    }

    private fun fadeRootTitle(large:TextView,y:Int){
        val floating=floatingTitles[large]?.get() ?: return
        floating.visibility=large.visibility
        if(large.visibility!=View.VISIBLE || large.height<=0)return
        val travel=large.height.coerceAtLeast(dp(48)).toFloat()
        val fade=(y/travel).coerceIn(0f,1f)
        floating.alpha=1f-fade*fade*(3f-2f*fade)
        floating.translationX=0f
        floating.translationY=topInset+large.top-y.toFloat()
        floating.scaleX=1f;floating.scaleY=1f
    }

    private fun addRootTabs(body:LinearLayout,scene:android.graphics.drawable.Drawable?):Pair<View,ColorosHeaderBlur?> {
        val appearance = Triple(darkUi, primary, bool("coloros_ui_effects"))
        if(rootNavigationAppearance!=appearance){
            rootNavigation?.let(page::removeView)
            rootNavigation=null
        }
        rootNavigationIndex.intValue=rootTabs.indexOf(screen)
        val host=rootNavigation ?: CouiRootNavigation(this,darkUi,primary,
                rootTabs.map {it.icon},listOf(R.drawable.ic_cpi_home_outline,R.drawable.ic_cpi_features_outline,
                    R.drawable.ic_cpi_module_outline),
                rootTabs.map {getString(if(it==Screen.TOOLS)R.string.ui_cpi_module else it.title)},
                rootNavigationIndex,chromeSurface()){index->haptic(page);openTab(rootTabs[index])}
            .also {navigation->
                rootNavigation=navigation;rootNavigationAppearance=appearance
                navigation.elevation=dp(5).toFloat()
                page.addView(navigation,FrameLayout.LayoutParams(-1,dp(176),Gravity.BOTTOM))
        }
        host.surfaceColor=chromeSurface()
        host.visibility=View.VISIBLE
        avoidNavigationBar(host,0)
        page.bringChildToFront(host)
        return host to null
    }

    private fun addRootRestart(layer:FrameLayout){
        val control=smallIcon(R.drawable.ic_restart,getString(R.string.ui_restart_menu),chrome=true){anchor->
            choicePopup(anchor,listOf(0 to R.string.ui_restart_launcher,1 to R.string.ui_restart_systemui,2 to R.string.ui_soft_restart),-1){
                when(it){0->confirmLauncherRestart(anchor);1->restartSystemUi(anchor);2->confirmSoftRestart(anchor)}
            }
        }
        control.tag="root_restart"
        control.elevation=dp(5).toFloat()
        layer.addView(control,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.TOP or Gravity.END).apply {
            topMargin=topInset+dp(6);marginEnd=dp(16)
        })
        val previous=applyHeaderInset
        applyHeaderInset={inset->previous?.invoke(inset)
            control.layoutParams=(control.layoutParams as FrameLayout.LayoutParams).apply {topMargin=inset+dp(6)}}
    }

    private fun avoidNavigationBar(control:View,baseMargin:Int=(control.layoutParams as FrameLayout.LayoutParams).bottomMargin){
        fun place(inset:Int){
            control.layoutParams=(control.layoutParams as FrameLayout.LayoutParams).apply {bottomMargin=baseMargin+inset}
        }
        place(bottomInset)
        val preceding=applyBottomInset
        applyBottomInset={inset->preceding?.invoke(inset);place(inset)}
    }

    private fun buildCatalog(body:LinearLayout,scroll:androidx.core.widget.NestedScrollView,heading:TextView,headerBlur:ColorOsAppBarBlurView?,layer:FrameLayout,navigation:Pair<View,ColorosHeaderBlur?>?){
        space(body,51)
        val transfer=column()
        val configRows=settingsTransferRows()
        val savedSettings=column().also {addSavedSettings(it,true)}
        add(body,transfer)
        val entries=column()
        add(body,entries)
        val groups=listOf(
            R.string.ui_catalog_launcher to listOf(Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK,
                Screen.MOTION),
            R.string.ui_catalog_systemui to listOf(Screen.LOCKSCREEN,Screen.SHADE,Screen.LIVE,Screen.BANNERS,Screen.VOLUME),
            R.string.camera_tab to listOf(Screen.CAMERA_ZOOM),
            R.string.ui_catalog_general to listOf(Screen.GLASS,Screen.LIGHT,Screen.PERFORMANCE)
        )
        val alternateLanguage=if(UiLanguage.tag(this)=="ru")"en" else "ru"
        val alternate=resources.configuration.let {current->
            createConfigurationContext(android.content.res.Configuration(current).apply {
                setLocale(java.util.Locale.forLanguageTag(alternateLanguage))
            })
        }
        fun translations(id:Int)=listOf(getString(id),alternate.getString(id)).distinct()
        val searchIndex=(groups.flatMap {it.second}+Screen.SETTINGS).associateWith {item->
            FeatureSearch.Entry(translations(item.title),translations(item.keywords),
                FeatureSearch.labels(item.name).distinct().map {it to translations(it)})
        }
        var rendered:List<Pair<Screen,Int?>>?=null
        var renderedTransfer:List<Int>?=null
        var renderedCatalog=false
        fun renderResults(){
            val query=FeatureSearch.Query(searchQuery)
            val ranked=if(query.isEmpty)groups.flatMap {(_,items)->items.map {it to null}}
            else searchIndex.mapNotNull {(item,index)->
                query.match(index)?.let {item to it}
            }.sortedByDescending {it.second.score}.map {(item,match)->item to match.label}
            val transferMatches=(configRows.keys+R.string.ui_presets).filter {id->
                translations(id).any {query.score(it)!=null}
            }
            if(transferMatches!=renderedTransfer){
                transfer.removeAllViews()
                val visibleConfigs=transferMatches.filter {it in configRows}
                val rows=column()
                for((index,id) in visibleConfigs.withIndex()){
                    if(index>0)divider(rows)
                    val child=configRows.getValue(id)
                    (child.parent as? ViewGroup)?.removeView(child)
                    add(rows,child)
                }
                if(visibleConfigs.isNotEmpty())add(transfer,card(rows),bottom=10)
                if(R.string.ui_presets in transferMatches)add(transfer,savedSettings)
            }
            if(ranked==rendered && transferMatches==renderedTransfer && renderedCatalog==query.isEmpty)return
            rendered=ranked
            renderedTransfer=transferMatches
            renderedCatalog=query.isEmpty
            entries.removeAllViews()
            val sections=if(query.isEmpty)groups.map {(heading,items)->heading to items.map {it to null}}
                else if(ranked.isEmpty())emptyList() else listOf(0 to ranked)
            for((heading,matches) in sections){
                if(heading!=0)add(entries,text(getString(heading),13f,true).apply {
                    setPadding(dp(16),dp(10),dp(16),dp(8))
                    ViewCompat.setAccessibilityHeading(this,true)
                })
                val rows=column()
                matches.forEachIndexed { index,(item,label) ->
                    if(index!=0)divider(rows,56)
                    add(rows,catalogRow(item,label?.let(::getString)))
                }
                add(entries,card(rows),bottom=11)
            }
            if(ranked.isEmpty() && transferMatches.isEmpty())add(entries,text(getString(R.string.ui_catalog_no_results),15f,true).apply {
                gravity=Gravity.CENTER;setPadding(0,dp(36),0,0)
            })
        }
        var syncSearchMode: (() -> Unit)? = null
        val searchPanel = CouiSearchHost(this, darkUi, primary, chromeSurface(), onSurface, getString(R.string.ui_catalog_search), searchQuery,
            onQueryChanged = { query ->
                searchQuery = query
                renderResults()
                syncSearchMode?.invoke()
            },
            onSearch = {
                val match = rendered?.singleOrNull()?.first
                val transfer = renderedTransfer?.singleOrNull()?.let(configRows::get)
                when {
                    searchQuery.trim().equals("kostuhaptic", ignoreCase = true) -> {
                        searchInput?.setQuery("")
                        hideSearchKeyboard()
                        startActivity(Intent(this, com.cosmod.launcherglass.haptic.HapticStudioActivity::class.java))
                    }
                    !renderedCatalog && match != null && renderedTransfer.isNullOrEmpty() -> openScreen(match)
                    !renderedCatalog && rendered.isNullOrEmpty() && transfer != null -> {
                        hideSearchKeyboard()
                        transfer.performClick()
                    }
                    else -> {
                        hideSearchKeyboard()
                        page.requestFocus()
                    }
                }
            },
            onFocusChanged = { syncSearchMode?.invoke() },
        ).apply { translationY = (topInset + dp(64)).toFloat() }
        searchInput = searchPanel
        layer.addView(searchPanel,FrameLayout.LayoutParams(-1,dp(45),Gravity.TOP or Gravity.START).apply {
            marginStart=dp(16); marginEnd=dp(16)
        })
        val restart=layer.findViewWithTag<View>("root_restart")
        restart?.bringToFront()
        var searchTarget=-1f
        var searchProgress=0f
        var searchPositionReady=false
        fun placeSearch(){
            val expanded=maxOf(dp(64),heading.bottom).toFloat()
            val desired=topInset+CatalogSearchGeometry.top(searchProgress,expanded,resources.displayMetrics.density)
            val y=if(restart==null)desired else {
                val horizontalGap=if(!restart.isLaidOut || !searchPanel.isLaidOut)0
                    else if(layer.layoutDirection==View.LAYOUT_DIRECTION_RTL)searchPanel.right-searchPanel.capsuleWidth-restart.right
                    else restart.left-searchPanel.left-searchPanel.capsuleWidth
                val bottom=maxOf(restart.bottom,topInset+dp(52))+dp(2)
                CatalogSearchGeometry.topWithoutOverlap(desired,bottom.toFloat(),horizontalGap,dp(6))
            }
            searchPanel.translationY=y
        }
        searchPanel.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->placeSearch()}
        restart?.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->placeSearch()}
        fun desiredSearchWidth(progress:Float):Int{
            val windowWidth=layer.width.takeIf {it>0} ?: page.width
            if(windowWidth<=0)return ViewGroup.LayoutParams.MATCH_PARENT
            val reserved=(CatalogSearchGeometry.reservedWidth(progress)*resources.displayMetrics.density).roundToInt()
            return (windowWidth-dp(16)*2-reserved).coerceAtLeast(1)
        }
        fun layoutSearch(progress:Float){
            searchPanel.resizeCapsule(desiredSearchWidth(progress))
            placeSearch()
            val titleTravel=maxOf(dp(64),heading.bottom)*progress
            fadeRootTitle(heading,maxOf(scroll.scrollY,titleTravel.roundToInt()))
        }
        val searchMotion=androidx.dynamicanimation.animation.SpringAnimation(androidx.dynamicanimation.animation.FloatValueHolder(0f)).apply {
            spring=androidx.dynamicanimation.animation.SpringForce(0f).setStiffness(165f).setDampingRatio(.52f)
            minimumVisibleChange=.001f
            addUpdateListener {_,value,_->
                searchProgress=value
                layoutSearch(value)
            }
        }
        searchPanel.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view:View){}
            override fun onViewDetachedFromWindow(view:View){searchMotion.cancel()}
        })
        fun positionSearch(y:Int){
            val searching=searchPanel.isInputFocused() || searchQuery.isNotEmpty()
            val expanded=maxOf(dp(64),heading.bottom).toFloat()
            val target=if(searching)1f else (y/expanded).coerceIn(0f,1f)
            if (!searchPositionReady || !canAnimate()) {
                searchMotion.cancel()
                searchMotion.setStartValue(target)
                searchTarget = target
                searchProgress = target
                layoutSearch(target)
                return
            }
            if(kotlin.math.abs(searchTarget-target)>.0001f){
                searchTarget=target
                searchMotion.animateToFinalPosition(target)
            }
            layoutSearch(searchProgress)
        }
        val updateSearchMode={
            val searching=searchPanel.isInputFocused() || searchQuery.isNotEmpty()
            heading.visibility=if(searching)View.GONE else View.VISIBLE
            if(pageLayer===layer)navigation?.first?.visibility=if(searchPanel.isInputFocused())View.INVISIBLE else View.VISIBLE
            if(searching && searchPositionReady && pageLayer===layer && searchPanel.isInputFocused())scroll.scrollTo(0,0)
            positionSearch(scroll.scrollY)
        }
        syncSearchMode=updateSearchMode
        val updateInset=applyHeaderInset
        applyHeaderInset={inset->updateInset?.invoke(inset);positionSearch(scroll.scrollY)}
        heading.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->positionSearch(scroll.scrollY)}
        scroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _,_,y,_,_ ->
            headerBlur?.updateAppearance(darkUi,background,(y/dp(30).toFloat()).coerceIn(0f,1f))
            restartButtonBackdrop?.showScroll(y)
            navigation?.second?.showScroll(y)
            positionSearch(y)
        })
        val initialSearchLayout=object:ViewTreeObserver.OnPreDrawListener {
            private var retries=0
            override fun onPreDraw():Boolean{
                positionSearch(scroll.scrollY)
                if(searchPanel.capsuleWidth!=desiredSearchWidth(searchProgress) && retries++<2)return false
                if(layer.viewTreeObserver.isAlive)layer.viewTreeObserver.removeOnPreDrawListener(this)
                searchPositionReady=true
                return true
            }
        }
        layer.viewTreeObserver.addOnPreDrawListener(initialSearchLayout)
        layer.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(view:View)=Unit
            override fun onViewDetachedFromWindow(view:View){
                if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnPreDrawListener(initialSearchLayout)
                view.removeOnAttachStateChangeListener(this)
            }
        })
        renderResults()
        updateSearchMode()
    }

    private fun catalogRow(item:Screen,matchedSetting:String?=null):View =
        CouiPreference(this,darkUi,primary,getString(item.title),matchedSetting,item.icon,Color.BLACK or item.tint,
            catalog=true,onPreview={showFeaturePreview(item,it)}){haptic(it);openScreen(item)}

    private fun buildCameraZoom(body: LinearLayout) {
        val zoom = com.cosmod.launcherglass.camera.CameraZoomSettings
        val custom = { number(zoom.MODE) == com.cosmod.launcherglass.camera.ZoomMode.CUSTOM.ordinal }
        val bezier = { custom() && number(zoom.CURVE) == com.cosmod.launcherglass.camera.ZoomCurve.BEZIER.ordinal }
        val basics = column()
        add(basics, switchRow(getString(R.string.camera_zoom_enable), zoom.ENABLED, getString(R.string.camera_zoom_verify_hint)))
        divider(basics)
        modeChoice(basics, R.string.camera_zoom_mode, zoom.MODE,
            listOf("iPhone Style", "Cinematic", "Responsive", "Adaptive", "Custom")) { true }
        add(body, card(basics), bottom = 12)

        val preview = column()
        add(preview, informationRow(R.string.camera_zoom_preview, getString(R.string.camera_zoom_preview_hint)))
        val graph = CameraZoomCurveView(this, onSurface, primary).apply {
            setPadding(dp(16), dp(5), dp(16), dp(4))
            contentDescription = getString(R.string.camera_zoom_preview)
        }
        preview.addView(graph, LinearLayout.LayoutParams(-1, dp(128)))
        var target = 30
        fun updateCurve() = graph.preview(target / 10.0, zoom.tuning(::number))
        val targetSelector = CouiSlider(this, darkUi, primary, getString(R.string.camera_zoom_preview_target), 54, 24, null) {
            String.format(java.util.Locale.getDefault(), "%.1f×", (it + 6) / 10.0)
        }
        targetSelector.onChange = { target = it + 6; updateCurve() }
        add(preview, targetSelector)
        refreshers.add(::updateCurve)
        updateCurve()
        add(body, card(preview), bottom = 12)

        val parameters = column()
        val labels = listOf(
            R.string.camera_zoom_speed_in, R.string.camera_zoom_speed_out, R.string.camera_zoom_speed_max,
            R.string.camera_zoom_damping, R.string.camera_zoom_acceleration, R.string.camera_zoom_braking,
            R.string.camera_zoom_jerk, R.string.camera_zoom_inertia, R.string.camera_zoom_response,
            R.string.camera_zoom_sensitivity, R.string.camera_zoom_bezier_first, R.string.camera_zoom_bezier_second
        )
        modeChoice(parameters, R.string.camera_zoom_curve, zoom.CURVE,
            listOf("Linear", "Ease In", "Ease Out", "Ease In Out", "S-Curve", "Spring", "Custom Bézier")) { custom() }
        for ((index, knob) in zoom.controls.withIndex()) {
            val suffix = when (knob.key) {
                zoom.INERTIA, zoom.RESPONSE -> " ms"
                zoom.BRAKING, zoom.ACCELERATION, zoom.JERK, zoom.SMOOTHING -> ""
                else -> "%"
            }
            val control = slider(getString(labels[index]), knob.key, knob.range.first, knob.range.last, suffix,
                formatter = if (knob.key in setOf(zoom.BRAKING, zoom.ACCELERATION, zoom.SMOOTHING)) {
                    { value -> String.format(java.util.Locale.getDefault(), "%.1f", value / 10.0) }
                } else null,
                onPreview = { draft ->
                    graph.preview(target / 10.0, zoom.tuning { key -> if (key == knob.key) draft else number(key) })
                })
            gate(control, if (knob.key == zoom.BEZIER_FIRST || knob.key == zoom.BEZIER_SECOND) bezier else custom)
            add(parameters, control)
        }
        accordionCard(body, R.string.camera_zoom_custom, parameters)

        val controls = column()
        add(controls, switchRow(getString(R.string.camera_zoom_pinch), zoom.PINCH))
        divider(controls)
        add(controls, switchRow(getString(R.string.camera_zoom_slider), zoom.SLIDER))
        divider(controls)
        add(controls, switchRow(getString(R.string.camera_zoom_steps), zoom.STEPS))
        accordionCard(body, R.string.camera_zoom_inputs, controls)

        val diagnostics = column()
        val metrics = column()
        val version = runCatching { packageManager.getPackageInfo(zoom.PACKAGE, 0).versionName }.getOrNull()
        fun updateStatusRows() {
            val report = zoom.status(this)
            val state = when (report?.getString("state", "")) {
                zoom.STATE_READY -> R.string.camera_zoom_status_ready
                zoom.STATE_OBSERVING -> R.string.camera_zoom_status_observing
                zoom.STATE_UNSUPPORTED -> R.string.camera_zoom_status_unsupported
                zoom.STATE_ACTIVE -> R.string.camera_zoom_status_active
                zoom.STATE_ERROR -> R.string.camera_zoom_status_error
                else -> R.string.camera_zoom_status_wait
            }
            metrics.removeAllViews()
            add(metrics, informationRow(R.string.camera_zoom_firmware, version ?: getString(R.string.camera_zoom_not_present)))
            add(metrics, informationRow(R.string.camera_zoom_state, getString(state)))
            report?.getString("mechanism", "")?.takeIf { it.isNotBlank() }?.let {
                add(metrics, informationRow(R.string.camera_zoom_mechanism, it))
            }
            for ((key, label) in listOf("target" to R.string.camera_zoom_target, "applied" to R.string.camera_zoom_applied)) {
                val ratio = report?.takeIf { bool(zoom.DIAGNOSTICS) && bool(SettingsProvider.KEY_ENABLED) }
                    ?.getFloat(key, Float.NaN)
                if (ratio != null && ratio.isFinite()) add(metrics,
                    informationRow(label, String.format(java.util.Locale.getDefault(), "%.2f×", ratio)))
            }
        }
        add(diagnostics, switchRow(getString(R.string.camera_zoom_collect), zoom.DIAGNOSTICS,
            getString(R.string.camera_zoom_collect_hint), afterSave = ::updateStatusRows))
        divider(diagnostics)
        add(diagnostics, metrics)
        updateStatusRows()
        add(diagnostics, preferenceLink(getString(R.string.camera_zoom_refresh), getString(R.string.camera_zoom_scope_hint)) {
            updateStatusRows()
        })
        accordionCard(body, R.string.camera_zoom_diagnostics, diagnostics)
    }

    private data class FeatureGroups(val general:LinearLayout,val colors:LinearLayout,val style:LinearLayout)
    private val featureTabSelection=mutableMapOf<Screen,Int>()

    private fun buildFeaturePage(pageBody:LinearLayout,section:Screen){
        val separated=section in setOf(Screen.GLASS,Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK,
            Screen.BANNERS,Screen.LIVE,Screen.VOLUME,Screen.SHADE,Screen.LOCKSCREEN)
        val groups=if(separated)FeatureGroups(column(),column(),column())
            else FeatureGroups(pageBody,pageBody,pageBody)
        val (general,colors,body)=groups
        if(separated){
            val pages=listOf(general,colors,body)
            val selected=featureTabSelection[section] ?: if(section==Screen.ICONS)0 else 2
            val titles=listOf(R.string.ui_feature_tab_general,R.string.ui_feature_tab_colors,
                R.string.ui_feature_tab_style).map(::getString)
            add(pageBody,CouiSectionTabs(this,darkUi,primary,titles,selected){index->
                featureTabSelection[section]=index
                pages.forEachIndexed {i, tab->tab.visibility=if(i==index)View.VISIBLE else View.GONE}
            },bottom=12)
            addGlassPreview(pageBody)
            pages.forEachIndexed {i,tab->tab.visibility=if(i==selected)View.VISIBLE else View.GONE}
        }
        val common=if(section==Screen.ICONS)body else general
        if(section.name in FeatureSections.sections){
            add(common,card(switchRow(getString(R.string.ui_section_enabled),FeatureSections.key(section.name))),bottom=8)
        }
        val savedPosition=common.childCount
        when(section){
            Screen.GLASS->globalGlassSection(body,general,colors)
            Screen.ICONS->iconsSection(body,general,colors)
            Screen.FOLDERS->folderSection(body,general,colors)
            Screen.PHOTO->photoSection(body,general,colors)
            Screen.DOCK->dockSection(body,general,colors)
            Screen.BANNERS->bannerSection(body,general,colors)
            Screen.LIVE->liveAlertSection(body,colors)
            Screen.VOLUME->volumeSection(body,general,colors)
            Screen.SHADE->buildShadeGlass(body,general,colors,pageBody)
            Screen.LOCKSCREEN->lockscreenSection(body,general,colors)
            Screen.LIGHT->buildLight(body)
            Screen.MOTION->buildBa31kMotion(body)
            Screen.CAMERA_ZOOM->buildCameraZoom(body)
            Screen.PERFORMANCE->buildPerformance(body)
            Screen.SETTINGS->buildTools(body)
            else->return
        }
        if(section !in setOf(Screen.SETTINGS,Screen.MOTION,Screen.SHADE,Screen.CAMERA_ZOOM))
            addSavedSettings(common,position=savedPosition)
        if(separated){
            for(tab in listOf(general,colors,body))add(pageBody,tab)
        }
    }

    private fun accordionCard(parent:LinearLayout,title:Int,children:LinearLayout){
        val accordion=SettingsAccordion(children,false)
        val container=column()
        add(container,CouiDisclosure(this,darkUi,primary,getString(title)){
            accordion.setOpen(it,android.animation.ValueAnimator.areAnimatorsEnabled())
        })
        add(container,accordion)
        add(parent,card(container),bottom=12)
    }

    private fun buildShadeGlass(body:LinearLayout,general:LinearLayout,colors:LinearLayout,pageBody:LinearLayout) {
        val panel=shadePanel
        val components=panel.components
        val panelTitles=listOf(getString(R.string.shade_glass_control),getString(R.string.shade_glass_notifications))
        val titles=listOf(R.string.shade_glass_round,R.string.shade_glass_large,R.string.shade_glass_media,
            R.string.shade_glass_slider_brightness,R.string.shade_glass_volume,R.string.shade_glass_other_tile,
            R.string.shade_glass_compact,R.string.shade_glass_expanded,R.string.shade_glass_group,
            R.string.shade_glass_action,R.string.shade_glass_clear)
        fun choose(label:Int,items:List<String>,selected:Int,pick:(Int)->Unit):View {
            return optionRow(getString(label),items[selected]).apply {
                setOnClickListener { showChoicePopup(this,items.mapIndexed { i,value->i to value },selected,onPick=pick) }
            }
        }
        add(pageBody,CouiSectionTabs(this,darkUi,primary,panelTitles,panel.ordinal,commitOnRelease = true) { index->
            if(index!=panel.ordinal){
                shadePanel=ShadeGlassSettings.Panel.entries[index]
                shadeComponent=0
                refreshCatalogStyle()
            }
        },bottom=10)
        val navigation=column()
        add(navigation,switchRow(getString(R.string.shade_glass_enabled),panel.enabled,getString(R.string.shade_glass_system_hint)))
        val presets=listOf("iOS Liquid Glass","Clear Glass","Dark Glass","Frosted Glass","Custom")
        val presetIndex=ShadeGlassSettings.Preset.entries.firstOrNull {
            it!=ShadeGlassSettings.Preset.CUSTOM && it.values().all { (parameter,value)->number(panel.key(parameter))==value }
        }?.ordinal ?: ShadeGlassSettings.Preset.CUSTOM.ordinal
        add(navigation,choose(R.string.shade_glass_preset,presets,presetIndex) { index->
            val preset=ShadeGlassSettings.Preset.entries[index].values()
            if(preset.isNotEmpty()){
                prefs.edit().apply {preset.forEach {(parameter,value)->putInt(panel.key(parameter),value)}}.apply()
                contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null)
                refreshCatalogStyle()
            }
        })
        add(navigation,optionRow(getString(R.string.shade_glass_reset),"").apply {
            setOnClickListener {
                prefs.edit().apply { ShadeGlassSettings.panelKeys(panel).forEach { remove(it) } }.apply()
                contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null)
                refreshCatalogStyle()
            }
        })
        accordionCard(general,R.string.shade_glass_preset,navigation)
        add(pageBody,text(getString(R.string.shade_glass_preview),16f),bottom=5)
        val sectionEnabled={bool(FeatureSections.key(Screen.SHADE.name))}
        val backdrop={ ShadeGlassSettings.controlBackground(
            sectionEnabled() && bool(ShadeGlassSettings.CONTROL_BACKGROUND),bool(panel.highRefresh)) {
            key->previewDraft[key] ?: number(key)
        } }
        val preview=ShadeGlassPreview(this,panel,darkUi,backdrop.takeIf { panel==ShadeGlassSettings.Panel.CONTROL }) { component->
            ShadeGlassSettings.profile(panel,component,sectionEnabled() && bool(panel.enabled),
                bool(panel.highRefresh),bool(panel.newEngine),
                GlassEngineSettings.read(panel.newEngine) { key,_->
                    val effective=if(key==GlassEngineSettings.key(panel.newEngine,GlassEngineSettings.Parameter.LENS_HEIGHT) &&
                        bool(GlobalGlassSettings.ENABLED)) GlobalGlassSettings.LENS_HEIGHT else key
                    previewDraft[effective] ?: number(effective)
                }) {
                key->previewDraft[key] ?: number(key)
            }
        }
        shadePreview=preview
        pageBody.addView(preview,LinearLayout.LayoutParams(-1,dp(235)))
        space(pageBody,12)
        if(panel==ShadeGlassSettings.Panel.CONTROL) {
            val background=column()
            val enabled=ShadeGlassSettings.CONTROL_BACKGROUND
            add(background,switchRow(getString(R.string.shade_glass_control_background),enabled,
                getString(R.string.shade_glass_control_background_hint)))
            addGlassBlur(background,ShadeGlassSettings.CONTROL_BACKGROUND_RADIUS,
                ShadeGlassSettings.CONTROL_BACKGROUND_BLUR_OPACITY,60,{master() && bool(enabled)})
            accordionCard(body,R.string.shade_glass_control_background,background)
            val backdropTone=column()
            add(backdropTone,slider(getString(R.string.shade_glass_control_background_darkness),
                ShadeGlassSettings.CONTROL_BACKGROUND_DARKNESS,0,100,"%").apply {gate(this){master() && bool(enabled)}})
            add(backdropTone,slider(getString(R.string.shade_glass_control_background_brightness),
                ShadeGlassSettings.CONTROL_BACKGROUND_BRIGHTNESS,0,200,"%").apply {gate(this){master() && bool(enabled)}})
            accordionCard(colors,R.string.shade_glass_control_background,backdropTone)
        }

        val labels=intArrayOf(
            R.string.shade_glass_material,
            R.string.shade_glass_blur,
            R.string.shade_glass_radius,
            R.string.shade_glass_blur_strength,
            R.string.shade_glass_blur_opacity,
            R.string.shade_glass_quality,
            R.string.shade_glass_live,
            R.string.shade_glass_economy,
            R.string.shade_glass_refraction,
            R.string.shade_glass_strength,
            R.string.shade_glass_lens,
            R.string.shade_glass_edge,
            R.string.shade_glass_dispersion,
            R.string.shade_glass_dispersion_strength,
            R.string.shade_glass_highlight,
            R.string.shade_glass_highlight_brightness,
            R.string.shade_glass_highlight_opacity,
            R.string.shade_glass_highlight_width,
            R.string.shade_glass_highlight_color,
            R.string.shade_glass_rim,
            R.string.shade_glass_reflection,
            R.string.shade_glass_color,
            R.string.shade_glass_opacity,
            R.string.shade_glass_saturation,
            R.string.shade_glass_contrast,
            R.string.shade_glass_brightness,
            R.string.shade_glass_tint,
            R.string.shade_glass_auto_theme,
            R.string.shade_glass_corner,
            R.string.shade_glass_inner_shadow,
            R.string.shade_glass_outer_shadow,
            R.string.shade_glass_depth
        )
        val parameters=ShadeGlassSettings.Parameter.entries
        val switches=listOf(getString(R.string.shade_glass_off),getString(R.string.shade_glass_on))
        val groups=listOf(0..0 to R.string.ui_feature_tab_style,8..13 to R.string.shade_glass_refraction,
            14..20 to R.string.shade_glass_highlight,21..27 to R.string.ui_feature_tab_colors,
            28..31 to R.string.shade_glass_depth)
        val highlightColors=column()
        for((range,label) in groups){
            val controls=column()
            for(index in range){
                val parameter=parameters[index]
                val key=panel.key(parameter)
                val title=labels[index]
                when(parameter){
                    ShadeGlassSettings.Parameter.MATERIAL->glassTypeChoice(controls,key,panel.newEngine,
                        listOf("Liquid Glass","Frosted Glass"),liquid=0,title=title,allowed=::master) { blur ->
                        addGlassBlur(blur,panel.key(ShadeGlassSettings.Parameter.RADIUS),
                            panel.key(ShadeGlassSettings.Parameter.BLUR_OPACITY),60,::master)
                    }
                    ShadeGlassSettings.Parameter.COLOR,ShadeGlassSettings.Parameter.HIGHLIGHT_COLOR->{
                        fun hex()=String.format(java.util.Locale.ROOT,"#%06X",number(key))
                        val row=optionRow(getString(title),hex())
                        refreshers.add { row.value=hex() }
                        row.setOnClickListener { editShadeColor(row,title,key,hex()) }
                        gate(row,::master)
                        add(if(parameter==ShadeGlassSettings.Parameter.HIGHLIGHT_COLOR)highlightColors else controls,row)
                    }
                    else->if(parameter.range==0..1)modeChoice(controls,title,key,switches,allowed=::master)
                        else add(controls,slider(getString(title),key,parameter.range.first,parameter.range.last,
                            if(parameter in setOf(ShadeGlassSettings.Parameter.RADIUS,ShadeGlassSettings.Parameter.CORNER))" dp" else "%").apply {gate(this,::master)})
                }
            }
            accordionCard(if(range.first==21)colors else body,label,controls)
        }
        accordionCard(colors,R.string.shade_glass_highlight_color,highlightColors)
        val component=components[shadeComponent]
        val overrides=column()
        add(overrides,choose(R.string.shade_glass_component,components.map { getString(titles[it.ordinal]) },shadeComponent) {
            shadeComponent=it
            refreshCatalogStyle()
        })
        modeChoice(overrides,R.string.shade_glass_mode,component.mode,
            listOf(getString(R.string.shade_glass_inherit),getString(R.string.shade_glass_custom),getString(R.string.shade_glass_stock)),
            allowed=::master)
        val allowed={master() && number(component.mode)==1}
        addGlassBlur(overrides,component.radius,component.blurOpacity,60,allowed,inherited=true)
        add(overrides,slider(getString(R.string.shade_glass_opacity),component.opacity,-1,100,"%",
            formatter={ if(it<0)getString(R.string.shade_glass_inherit) else "$it%" }).apply {gate(this,allowed)})
        accordionCard(body,R.string.shade_glass_component,overrides)
    }

    private fun editShadeColor(anchor:View,title:Int,key:String,initial:String) {
        val form=column()
        val input = CouiField(this, darkUi, primary, getString(R.string.shade_glass_hex),
            initial, onSurface, maxLength = 7, autofocus = false)
        add(form,input,bottom=8)
        val swatches=LinearLayout(this)
        for(value in listOf(0x252938,0x4f70ac,0x667d58,0xd99985,0xffffff)){
            val hex=String.format(java.util.Locale.ROOT,"#%06X",value)
            val swatch=button().apply {
                text="●"
                contentDescription=hex
                setTextColor(value or 0xff000000.toInt())
                setOnClickListener { input.replaceText(hex) }
            }
            swatches.addView(swatch,LinearLayout.LayoutParams(0,dp(44),1f))
        }
        add(form,swatches,bottom=8)
        val apply=button().apply { text=getString(R.string.shade_glass_apply) }
        add(form,apply)
        val dialog=savedSettingsDialog(getString(title),form,anchor)
        apply.setOnClickListener {
            val digits = input.value.trim().removePrefix("#")
            val value=digits.takeIf { it.length==6 }?.toIntOrNull(16)
            if(value==null || value !in 0..0xffffff) input.error=getString(R.string.shade_glass_hex)
            else {
                save(key,value)
                refreshControls()
                dialog.dismiss()
            }
        }
    }

    private fun showFeaturePreview(item:Screen,anchor:View){
        hideSearchKeyboard()
        settingsDialogOwner?.dispose()
        clearGlassPreview()
        val body=column().apply {
            setPadding(dp(12),dp(51),dp(12),dp(12))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        previewSection=item
        previewControls=SectionControls()
        try {
            buildFeaturePage(body,item)
            updateGates()
        } finally {
            previewControls=null
            previewSection=null
        }
        val visualPreview=glassPreview
        val maxHeight=(page.height*.64f).roundToInt().coerceAtLeast(dp(96))
        val scroll=object:androidx.core.widget.NestedScrollView(this){
            private var viewportHeight=0

            // A preview can be scrolled without invoking the real controls inside it.
            override fun onInterceptTouchEvent(event:MotionEvent):Boolean=true

            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                val available=if(View.MeasureSpec.getMode(heightMeasureSpec)==View.MeasureSpec.UNSPECIFIED)
                    maxHeight else minOf(maxHeight,View.MeasureSpec.getSize(heightMeasureSpec))
                val height=if(viewportHeight==0)available else minOf(viewportHeight,available)
                val mode=if(viewportHeight==0)View.MeasureSpec.AT_MOST else View.MeasureSpec.EXACTLY
                super.onMeasure(widthMeasureSpec,View.MeasureSpec.makeMeasureSpec(height,mode))
                if(viewportHeight==0)viewportHeight=measuredHeight
            }
        }.apply {
            descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS
            isVerticalScrollBarEnabled=true
            clipChildren=true
            clipToPadding=true
            overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body,ViewGroup.LayoutParams(-1,-2))
        }
        lateinit var dialog:CouiAnchoredDialog
        val viewport=FrameLayout(this).apply {
            clipChildren=true
            clipToPadding=true
            clipToOutline=true
            outlineProvider=object:ViewOutlineProvider(){
                override fun getOutline(view:View,outline:android.graphics.Outline){
                    outline.setRoundRect(0,0,view.width,view.height,dp(28).toFloat())
                }
            }
            addView(scroll,FrameLayout.LayoutParams(-1,-2))
            if(bool("coloros_ui_effects")){
                val headerBlur=ColorOsAppBarBlurView(this@MainActivity,darkUi,panel,tintOverlay=false,maxRadius=22f).apply {
                    updateAppearance(darkUi,panel,0f)
                }
                addView(headerBlur,FrameLayout.LayoutParams(-1,dp(58),Gravity.TOP))
                scroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener {_,_,y,_,_->
                    headerBlur.updateAppearance(darkUi,panel,(y/dp(36).toFloat()).coerceIn(0f,1f))
                })
            }
            foreground=CouiCardEdgeShade(darkUi,resources.displayMetrics.density)
            val header=text(getString(item.title),17f,bold=true).apply {
                gravity=Gravity.CENTER
                maxLines=2
                ellipsize=android.text.TextUtils.TruncateAt.END
                ViewCompat.setAccessibilityHeading(this,true)
            }
            addView(header,
                FrameLayout.LayoutParams(-1,dp(46),Gravity.TOP).apply {
                    setMargins(dp(16),dp(2),dp(16),0)
                })
        }
        val actions=CouiPreviewActions(this,darkUi,primary,
            open={dialog.close {openScreen(item)}},close={dialog.dismiss()})
        dialog=anchoredDialog(viewport,anchor,actions=actions,holdTouch=true,swipeHeader=true){
            if(visualPreview!=null && glassPreview===visualPreview)clearGlassPreview()
        }
    }

    // View layout adapted from ONextBox HomeHeroCard, HomeRuntimeStatusCards and HomeInfoCard.
    private fun buildOverview(body:LinearLayout){
        val identity=column().apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(22),dp(10),dp(22),dp(10))
        }
        val name=text(getString(R.string.app_name),31f,bold=true).apply {
            setTextColor(Color.WHITE);maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(20,31,1,android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        add(identity,name)
        add(identity,text(getString(R.string.ui_about_version,BuildConfig.VERSION_NAME),15f).apply {
            setTextColor(0xebffffff.toInt())
        },top=10)
        add(identity,text(OnextDeviceInfo.marketName,15f).apply {setTextColor(0xebffffff.toInt())},top=4)
        val heroCard=object:FrameLayout(this){
            override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
                val target=(MeasureSpec.getSize(widthMeasureSpec)*430f/1128f).roundToInt().coerceAtLeast(dp(113))
                super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec(target,MeasureSpec.EXACTLY))
            }
        }.apply {
            background=panelShape(0xff64758a.toInt(),20)
            foreground=outline(20,.82f)
            clipToOutline=true
            addView(identity,FrameLayout.LayoutParams(-1,-1))
        }
        hero=heroCard
        decorateCelebration(heroCard)
        add(body,heroCard,bottom=12)

        val warning=column().apply {setPadding(dp(16),dp(14),dp(16),dp(4))}
        add(warning,text(getString(R.string.ui_framework_warning_title),17f,bold=true))
        add(warning,text(getString(R.string.ui_framework_warning_text),14f,true),top=8,bottom=4)
        add(warning,preferenceLink(getString(R.string.ui_framework_download)){openLink("https://lsposed.zip")})
        frameworkWarning=card(warning).also {
            it.visibility=if(isVectorFramework())View.VISIBLE else View.GONE
            add(body,it,bottom=12)
        }

        val status=row()
        fun statusCard(title:Int,detail:TextView):View {
            val inside=column().apply {
                minimumHeight=dp(90)
                setPadding(dp(14),dp(13),dp(14),dp(12))
            }
            add(inside,text(getString(title),16f,bold=true))
            add(inside,detail,top=6)
            return card(inside)
        }
        rootStatus=text(getString(R.string.ui_status_checking),13f,true).apply {maxLines=2}
        lsposedText=text(getString(R.string.ui_status_checking),13f,true).apply {maxLines=2}
        status.addView(statusCard(R.string.ui_root_status,rootStatus!!),LinearLayout.LayoutParams(0,-1,1f).apply {marginEnd=dp(12)})
        status.addView(statusCard(R.string.ui_lsposed_status,lsposedText!!),LinearLayout.LayoutParams(0,-1,1f))
        status.minimumHeight=dp(90)
        add(body,status,bottom=12)

        val info=column()
        val details=listOf(
            R.string.ui_device_region to OnextDeviceInfo.region(this),
            R.string.ui_device_android to getString(R.string.ui_device_android_value,Build.VERSION.RELEASE,Build.VERSION.SDK_INT),
            R.string.ui_device_model to Build.MODEL,
            R.string.ui_device_firmware to OnextDeviceInfo.systemVersion,
            R.string.ui_device_abi to OnextDeviceInfo.architecture,
            R.string.ui_device_fingerprint to Build.FINGERPRINT,
            R.string.ui_device_kernel to OnextDeviceInfo.kernelVersion
        )
        for((index,entry) in details.withIndex()){
            if(index!=0)divider(info)
            add(info,informationRow(entry.first,entry.second))
        }
        add(body,card(info),bottom=10)
        add(body,card(preferenceLink(getString(R.string.ui_device_details),getString(R.string.ui_device_details_hint)){
            openScreen(Screen.DEVICE)
        }))
    }

    private fun informationRow(label: Int, value: String): View =
        CouiInformation(this, darkUi, primary, getString(label),
            value.ifBlank { getString(R.string.ui_device_unknown) })

    private fun preferenceLink(title:String,summary:String?=null,icon:Int?=null,
        iconTint:Int=onSurface,action:(View)->Unit):View =
        CouiPreference(this,darkUi,primary,title,summary,icon,iconTint) {
            haptic(it)
            action(it)
        }

    private fun buildDeviceDetails(body:LinearLayout){
        val manager=getSystemService(android.app.ActivityManager::class.java)
        val memory=android.app.ActivityManager.MemoryInfo().also {manager?.getMemoryInfo(it)}
        val storage=runCatching {android.os.StatFs(filesDir.absolutePath)}.getOrNull()
        val mode=display?.mode
        fun size(bytes:Long)=if(bytes>0)android.text.format.Formatter.formatFileSize(this,bytes)else ""
        val entries=listOf(
            R.string.ui_device_soc to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim(),
            R.string.ui_device_board to Build.BOARD,
            R.string.ui_device_memory to size(memory.totalMem),
            R.string.ui_device_storage to (storage?.let {size(it.totalBytes)} ?: ""),
            R.string.ui_device_display to (mode?.let {"${it.physicalWidth} × ${it.physicalHeight} · ${it.refreshRate.roundToInt()} Hz"} ?: ""),
            R.string.ui_device_patch to Build.VERSION.SECURITY_PATCH
        )
        val content=column()
        for((index,entry) in entries.withIndex()){
            if(index!=0)divider(content)
            add(content,informationRow(entry.first,entry.second))
        }
        add(body,card(content))
    }

    private fun buildModule(body:LinearLayout){
        // COUI groups already supply the page-side inset.
        val about=CouiAboutPage(this,darkUi,primary,master(),
            onEnabled={save(SettingsProvider.KEY_ENABLED,it)},
            onSettings={openScreen(Screen.SETTINGS)},
            onContributors={openScreen(Screen.CONTRIBUTORS)},onLink=::openLink)
        body.addView(about,LinearLayout.LayoutParams(-1,-2).apply {marginStart=-dp(16);marginEnd=-dp(16)})
        buildSupport(body)
    }

    private fun buildContributors(body: LinearLayout) {
        val testers = listOf("espcode", "encoren", "тима", "Foby", "Амбассадор Табуретки")
        val people = CouiSlot(this, darkUi, primary) {
            androidx.compose.foundation.layout.Column {
                CouiGroup {
                    BasicComponent(title = getString(R.string.ui_cpi_edition), summary = "Kostubet Topics")
                    HorizontalDivider()
                    BasicComponent(title = getString(R.string.ui_contributor_help), summary = "@anime_killer2009")
                    HorizontalDivider()
                    BasicComponent(title = getString(R.string.ui_contributor_sources), summary = "Mahmud0808 · MiToverG422")
                }
                CouiGroup {
                    BasicComponent(title=getString(R.string.ui_contributor_beta),summary=testers.joinToString(", "))
                }
            }
        }
        // COUI groups already supply the page-side inset.
        body.addView(people, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = -dp(16)
            marginEnd = -dp(16)
        })
    }

    private fun decorateCelebration(anchor:View){
        val overlay=BannerCelebration(this)
        celebration=overlay
        pageLayer?.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        anchor.isHapticFeedbackEnabled=false
        anchor.isClickable=true
        anchor.springPress(.945f){event->overlay.onBannerTouch(anchor,event)}
    }

    private fun openLink(url:String){
        try {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}
        catch(_:ActivityNotFoundException){Toast.makeText(this,getString(R.string.ui_link_error),Toast.LENGTH_SHORT).show()}
    }

    private fun iconsSection(body:LinearLayout,general:LinearLayout,colors:LinearLayout){
        val colorControls=column()
        section(body, getString(R.string.ui_background_blur), "", SettingsProvider.KEY_BLUR_ENABLED, blurExpanded,
            { blurExpanded = it }, ::master, ::blur) { content ->
            glassStyleSelector(content,folder=false,colors=colorControls)
            add(content,glassDispersion(GlassDispersionSettings.ICONS){blur() && number(SettingsProvider.KEY_BLUR_STYLE)==1})
        }
        val iconSize=slider(getString(R.string.ui_icon_size),"launcher_icon_size",70,130,"%",
            getString(R.string.ui_icon_size_hint))
        gate(iconSize,::master)
        add(general,card(iconSize),bottom=8)
        val warning=column().apply {setPadding(dp(20),dp(16),dp(20),dp(16))}
        add(warning,text(getString(R.string.ui_flux_only_title),16f,bold=true).apply {
            setTextColor(onSurface)
        })
        add(warning,text(getString(R.string.ui_flux_only_warning),14f).apply {
            setTextColor(onSurface)
        },6)
        val warningCard=card(warning).apply {background=panelShape(raised)}
        fluxWarning=warningCard
        add(body,warningCard,bottom=16)
        updateFluxWarning()
        coloringSection(colorControls)
        logoColors(colorControls,"icon",::master)
        blurToneGroup(colorControls)
        accordionCard(colors,R.string.ui_feature_tab_colors,colorControls)
        section(body, getString(R.string.ui_icon_design), getString(R.string.ui_icon_design_hint), SettingsProvider.KEY_DESIGN_ENABLED, designExpanded,
            { designExpanded = it }, ::master, ::design) { content ->
            glyphOutlineGroup(content)
            effectGroup(content, getString(R.string.ui_icon_depth), SettingsProvider.KEY_VOLUME_ENABLED,
                getString(R.string.ui_depth_strength), SettingsProvider.KEY_GRADIENT_OPACITY)
        }
    }
    private fun glassDispersion(key:String,allowed:()->Boolean={true})=
        slider(getString(R.string.ui_glass_dispersion),key,0,100,"%").also {
            gate(it){Build.VERSION.SDK_INT>=33 && allowed()}
        }

    private fun photoSection(body:LinearLayout,general:LinearLayout,colors:LinearLayout){
        val enabledKey=SettingsProvider.KEY_PHOTO_WIDGET_BLUR
        val enabled={master() && bool(enabledKey)}
        val frameKey=SettingsProvider.KEY_PHOTO_WIDGET_STYLE
        val materialKey=SettingsProvider.KEY_PHOTO_WIDGET_MATERIAL
        val fadeKey=SettingsProvider.KEY_PHOTO_WIDGET_GLOW
        val tintKey=SettingsProvider.KEY_PHOTO_WIDGET_TINT
        val scope=column()
        modeChoice(scope,R.string.ui_widget_scope,WidgetSettings.SCOPE,
            listOf(getString(R.string.ui_widget_scope_photo),getString(R.string.ui_widget_scope_all)),allowed=enabled)
        accordionCard(general,R.string.ui_widget_scope,scope)
        val tint=column()
        effectGroup(body,getString(R.string.ui_widget_glass),enabledKey,
            getString(R.string.ui_widget_content_transparency),
            SettingsProvider.KEY_PHOTO_WIDGET_OPACITY,allowed=::master){details->
            gate(details,enabled)
            glassTypeChoice(details,materialKey,GlassEngineSettings.WIDGETS,allowed=enabled,colors=tint) {
                addGlassBlur(it,WidgetSettings.RADIUS,GlassEngineSettings.blurOpacityKey(GlassEngineSettings.WIDGETS),50,enabled)
            }
            modeChoice(details,R.string.ui_widget_frame,frameKey,
                listOf(getString(R.string.ui_photo_original),
                    getString(R.string.ui_widget_custom_frame)),allowed=enabled)
            val frame=column()
            add(frame,slider(getString(R.string.ui_glass_corner),SettingsProvider.KEY_PHOTO_WIDGET_CORNER,0,50,"%"))
            add(frame,slider(getString(R.string.ui_glass_shade),SettingsProvider.KEY_PHOTO_WIDGET_SHADE,0,100,"%"))
            gate(frame){enabled() && number(frameKey)==1}
            add(details,frame)
            add(details,slider(getString(R.string.ui_glass_rim),SettingsProvider.KEY_PHOTO_WIDGET_RIM,0,100,"%"))
            add(details,slider(getString(R.string.ui_glass_glow),fadeKey,0,100,"%"))
            val fadeHeight=slider(getString(R.string.ui_photo_fade_height),WidgetSettings.FADE_HEIGHT,0,100,"%")
            gate(fadeHeight){enabled() && number(fadeKey)>0}
            add(details,fadeHeight)
            val fadeSmoothness=slider(getString(R.string.ui_photo_fade_smoothness),WidgetSettings.FADE_SMOOTHNESS,0,100,"%")
            gate(fadeSmoothness){enabled() && number(fadeKey)>0}
            add(details,fadeSmoothness)
            val liquid=column()
            add(liquid,glassDispersion(GlassDispersionSettings.PHOTOS,enabled))
            add(liquid,slider(getString(R.string.ui_liquid_width),SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH,0,100,"%"))
            add(liquid,slider(getString(R.string.ui_liquid_distortion),SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION,0,300,"%"))
            gate(liquid){enabled() && number(materialKey)==1}
            liquid.visibility=if(number(materialKey)==1)View.VISIBLE else View.GONE
            refreshers.add {reveal(liquid,number(materialKey)==1)}
            add(details,liquid)
        }
        add(tint,switchRow(getString(R.string.ui_widget_tint),tintKey,
            getString(R.string.ui_widget_tint_hint)))
        add(tint,switchRow(getString(R.string.ui_coloring_wallpaper),WidgetSettings.WALLPAPER_TINT).also {
            gate(it){enabled() && bool(tintKey)}
        })
        colorControls(tint,WidgetSettings.HUE,WidgetSettings.TINT_SATURATION,"photo_widget_tint_brightness",
            {enabled() && bool(tintKey)}, {enabled() && bool(tintKey) && !bool(WidgetSettings.WALLPAPER_TINT)})
        add(tint,slider(getString(R.string.ui_tint_transparency),WidgetSettings.TINT_TRANSPARENCY,0,100,"%").also {
            gate(it){enabled() && bool(tintKey)}
        })
        add(tint,slider(getString(R.string.ui_tint_lightness),WidgetSettings.LIGHTNESS,-100,100,"%").also {
            gate(it){enabled() && bool(tintKey)}
        })
        widgetElementColors(tint,enabled)
        accordionCard(colors,R.string.ui_feature_tab_colors,tint)
    }

    private fun colorControls(parent:LinearLayout,hue:String,saturation:String,brightness:String,allowed:()->Boolean,
        hueAllowed:()->Boolean=allowed){
        val keys=listOf(hue,saturation,brightness)
        val values=keys.map {previewDraft[it] ?: number(it)}.toMutableList()
        val bands=mutableListOf<ColorBand>();val labels=mutableListOf<TextView>()
        fun repaint(){
            bands.forEachIndexed {i,band->
                band.setColor(values[0],values[1],values[2])
                labels[i].text="${values[i]}${if(i==0)"°" else "%"}"
            }
        }
        listOf(R.string.ui_hue,R.string.ui_saturation,R.string.ui_brightness).forEachIndexed {i,title->
            featureKeys.add(keys[i])
            val group=column();val heading=row().apply {setPadding(dp(16),dp(10),dp(16),0)}
            heading.addView(text(getString(title),15f),LinearLayout.LayoutParams(0,-2,1f))
            val value=text("",14f,true);labels.add(value);heading.addView(value)
            add(group,heading)
            val band=ColorBand(this,i,darkUi,primary).apply {
                contentDescription=getString(title)
                onChange={next,commit->
                    values[i]=next;previewDraft[keys[i]]=next;repaint();updateGlassPreview()
                    if(commit){previewDraft.remove(keys[i]);save(keys[i],next)}
                }
            }
            bands.add(band);add(group,band);gate(group,if(i==0)hueAllowed else allowed);add(parent,group)
        }
        refreshers.add {keys.forEachIndexed {i,k->values[i]=number(k)};repaint()}
        repaint()
    }

    private fun materialTint(parent:LinearLayout,surface:Int,allowed:()->Boolean){
        fun key(field:String)=SurfaceTintSettings.key(surface,field)
        divider(parent)
        add(parent,switchRow(getString(R.string.ui_background_color),key("enabled")))
        val active={allowed() && bool(key("enabled"))}
        add(parent,switchRow(getString(R.string.ui_coloring_wallpaper),key("wallpaper")).also {gate(it,active)})
        colorControls(parent,key("hue"),key("saturation"),key("brightness"),active,
            {active() && !bool(key("wallpaper"))})
        add(parent,slider(getString(R.string.ui_tint_lightness),key("lightness"),-100,100,"%").also {gate(it,active)})
        add(parent,slider(getString(R.string.ui_plate_opacity),key("opacity"),0,100,"%").also {gate(it,active)})
    }

    private fun logoColors(parent:LinearLayout,kind:String,allowed:()->Boolean){
        val key="${kind}_logo_tint"
        divider(parent)
        add(parent,switchRow(getString(R.string.ui_logo_colors),key,getString(R.string.ui_logo_colors_hint)))
        val active={allowed() && bool(key)}
        colorControls(parent,"${kind}_logo_hue","${kind}_logo_saturation","${kind}_logo_brightness",active)
    }

    private fun widgetElementColors(parent:LinearLayout,allowed:()->Boolean){
        divider(parent)
        add(parent,switchRow(getString(R.string.ui_widget_elements_follow_background),"widget_elements_follow_background",
            getString(R.string.ui_widget_elements_follow_background_hint)).also {gate(it,allowed)})
        val independent={allowed() && !bool("widget_elements_follow_background")}
        add(parent,switchRow(getString(R.string.ui_widget_text_colors),"widget_logo_tint",getString(R.string.ui_widget_text_colors_hint)).also {gate(it,independent)})
        val active={independent() && bool("widget_logo_tint")}
        colorControls(parent,"widget_logo_hue","widget_logo_saturation","widget_logo_brightness",active)
        add(parent,slider(getString(R.string.ui_widget_elements_tint_transparency),"widget_logo_transparency",0,100,"%").also {gate(it,active)})
    }

    private fun widgetTintSliders(body:LinearLayout,saturation:String,transparency:String,enabled:()->Boolean){
        add(body,slider(getString(R.string.ui_saturation),saturation,0,100,"%").also {gate(it,enabled)})
        add(body,slider(getString(R.string.ui_tint_transparency),transparency,0,100,"%").also {gate(it,enabled)})
    }

    private fun lockscreenSection(body:LinearLayout,general:LinearLayout,colors:LinearLayout){
        fun heading(title:Int){
            body.addView(CouiSlot(this,darkUi,primary){SmallTitle(getString(title))},
                LinearLayout.LayoutParams(-1,-2).apply {marginStart=-dp(16);marginEnd=-dp(16)})
        }
        add(body,card(informationRow(R.string.ui_lock_setup,getString(R.string.lock_glass_hint))),bottom=6)
        heading(R.string.ui_lock_appearance)
        val appearance=listOf(LockscreenSettings.Surface.CLOCK,LockscreenSettings.Surface.WIDGETS,
            LockscreenSettings.Surface.SHORTCUTS,LockscreenSettings.Surface.NOTIFICATIONS,LockscreenSettings.Surface.CAPSULE)
        for(surface in appearance)lockscreenMaterialGroup(body,colors,surface)
        lockscreenMaterialGroup(body,colors,LockscreenSettings.Surface.PIN)
        add(general,card(switchRow(getString(R.string.lock_glass_clear_bouncer),LockscreenSettings.CLEAR_BOUNCER_BLUR))
            .also {gate(it,::master)},top=8,bottom=4)
        effectGroup(general,getString(R.string.lock_unlock_animation),LockscreenSettings.UNLOCK_ENABLED,
            getString(R.string.lock_unlock_speed),LockscreenSettings.UNLOCK_SPEED,
            min=LockscreenSettings.MIN_UNLOCK_SPEED,max=LockscreenSettings.MAX_UNLOCK_SPEED,allowed=::master){details->
            val active={master() && bool(LockscreenSettings.UNLOCK_ENABLED)}
            divider(details)
            add(details,slider(getString(R.string.lock_unlock_smoothness),LockscreenSettings.UNLOCK_SMOOTHNESS,0,100,"%")
                .also {gate(it,active)})
            divider(details)
            add(details,slider(getString(R.string.lock_unlock_travel),LockscreenSettings.UNLOCK_TRAVEL,0,150,"%")
                .also {gate(it,active)})
            divider(details)
            add(details,slider(getString(R.string.lock_unlock_impulse),LockscreenSettings.UNLOCK_IMPULSE,0,100,"%",
                getString(R.string.lock_unlock_impulse_hint)).also {gate(it,active)})
        }
        add(general,card(switchRow(getString(R.string.lock_aod_blur),LockscreenSettings.AOD_BLUR_ENABLED))
            .also {gate(it,::master)},top=8,bottom=4)
    }

    private fun globalGlassSection(body:LinearLayout,general:LinearLayout,colors:LinearLayout){
        val keys=GlobalGlassSettings
        val style=column()
        add(general,card(switchRow(getString(R.string.ui_global_glass_enabled),keys.ENABLED,
            getString(R.string.ui_global_glass_hint))),bottom=8)
        val active={bool(keys.ENABLED)}
        modeChoice(style,R.string.ui_blur_style,keys.STYLE,listOf("Frosted","Liquid Glass"),
            valid={it==0 || Build.VERSION.SDK_INT>=33},allowed=active)
        fun control(group:LinearLayout,label:Int,key:String,unit:String,allowed:()->Boolean){
            divider(group)
            add(group,slider(getString(label),key,0,OpticalSettings.maximum(key),unit).also {gate(it,allowed)})
        }
        addGlassBlur(style,keys.BLUR,keys.BLUR_OPACITY,OpticalSettings.maximum(keys.BLUR),active)
        val tone=column()
        control(tone,R.string.island_opacity,keys.OPACITY,"%",active)
        accordionCard(colors,R.string.ui_feature_tab_colors,tone)
        val lens=column()
        val showLens={number(keys.STYLE)==1 && Build.VERSION.SDK_INT>=33}
        val liquid={active() && showLens()}
        control(lens,R.string.glass_lens_height,keys.LENS_HEIGHT,"%",liquid)
        control(lens,R.string.ui_liquid_width,keys.WIDTH,"%",liquid)
        control(lens,R.string.ui_liquid_distortion,keys.DISTORTION,"%",liquid)
        control(lens,R.string.ui_glass_dispersion,keys.DISPERSION,"%",liquid)
        lens.visibility=if(showLens())View.VISIBLE else View.GONE
        refreshers.add {reveal(lens,showLens())}
        add(style,lens)
        accordionCard(body,R.string.ui_feature_tab_style,style)
    }

    private fun lockscreenMaterialGroup(body:LinearLayout,colors:LinearLayout,surface:LockscreenSettings.Surface){
            val enabled=surface.key("enabled")
            val title=getString(surface.title)
            val active={master() && bool(enabled)}
            val tone=column()
            effectGroup(body,title,enabled,getString(R.string.island_opacity),surface.key("opacity"),allowed=::master){details->
                val style=surface.key("style")
                divider(details)
                glassTypeChoice(details,style,surface.key("new_engine"),allowed=active,colors=tone) {
                    addGlassBlur(it,surface.key("blur"),GlassEngineSettings.blurOpacityKey(surface.key("new_engine")),50,active)
                }
                if(surface.adjustableSaturation){
                    val saturation=surface.key("saturation")
                    add(tone,slider(getString(R.string.ui_saturation),saturation,0,OpticalSettings.maximum(saturation),"%").also {gate(it,active)})
                }
                if(surface.adjustableCorner){
                    val corner=surface.key("corner")
                    add(details,slider(getString(R.string.ui_glass_corner),corner,OpticalSettings.minimum(corner),50,"%",
                        formatter={if(it<0)getString(R.string.ui_system) else "$it%"}))
                }
                if(surface==LockscreenSettings.Surface.WIDGETS){
                    val wallpaper=LockscreenSettings.WIDGET_WALLPAPER_TINT
                    add(tone,switchRow(getString(R.string.ui_coloring_wallpaper),wallpaper).also {gate(it,active)})
                    widgetTintSliders(tone,LockscreenSettings.WIDGET_TINT_SATURATION,
                        LockscreenSettings.WIDGET_TINT_TRANSPARENCY){active() && bool(wallpaper)}
                }
                val liquid=column().apply {visibility=if(number(style)==1)View.VISIBLE else View.GONE}
                for((label,field) in listOf(R.string.ui_liquid_width to "width",
                    R.string.ui_liquid_distortion to "distortion",R.string.ui_glass_dispersion to "dispersion")){
                    val key=surface.key(field)
                    add(liquid,slider(getString(label),key,0,OpticalSettings.maximum(key),"%"))
                }
                refreshers.add {reveal(liquid,number(style)==1)}
                add(details,liquid)
                materialTint(tone,surface.edgeSurface,active)
                accordionCard(colors,surface.title,tone)
                if(surface!=LockscreenSettings.Surface.CLOCK){
                    opticalSection(details,surface.edgeSurface,getString(R.string.edge_title))
                }
                darkContourSection(details,surface.edgeSurface,getString(R.string.ui_dark_contour))
            }
    }

    private fun buildLight(body:LinearLayout){
        wallpaperGlowSection(body)
        val surfaces=listOf(Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK,Screen.BANNERS,Screen.LIVE)
        fun heading(label:Int)=add(body,text(getString(label),18f,bold=true).apply {
            setPadding(dp(15),dp(12),dp(15),dp(5))
        })
        heading(R.string.edge_title)
        for((surface,screen) in surfaces.withIndex())opticalSection(body,surface,getString(screen.title))
        heading(R.string.ui_dark_contour)
        for((surface,screen) in surfaces.withIndex())darkContourSection(body,surface,getString(screen.title))
    }

    private fun darkContourSection(body:LinearLayout,surface:Int,title:String){
        val format=java.text.NumberFormat.getNumberInstance(resources.configuration.locales[0])
        fun key(field:String)=EdgeSettings.key(surface,field)
        effectGroup(body,title,key("dark_enabled"),getString(R.string.ui_dark_contour_strength),
            key("dark_opacity"),allowed=::master){details->
            divider(details)
            add(details,slider(getString(R.string.ui_dark_contour_width),key("dark_width_qdp"),1,EdgeSettings.MAX_DARK_QUARTERS," dp",
                formatter={width->format.format(width*.25)+" dp"}))
            divider(details)
            add(details,slider(getString(R.string.ui_dark_contour_softness),key("dark_softness"),0,100,"%"))
            divider(details)
            add(details,switchRow(getString(R.string.ui_dark_contour_overlap),key(EdgeSettings.DARK_OVERLAP)))
        }
    }

    private fun opticalSection(body:LinearLayout,surface:Int,title:String) {
        fun key(field:String)=EdgeSettings.key(surface,field)
        effectGroup(body,title,key("enabled"),getString(R.string.edge_strength),
            key("strength"),allowed=::master){details->
            divider(details)
            add(details,switchRow(getString(R.string.edge_motion),key("motion")))
            divider(details)
            add(details,slider(getString(R.string.ui_edge_direction),key("direction"),0,360,"°",
                getString(R.string.ui_edge_direction_hint)))
            for((label,field) in listOf(R.string.edge_width to "width",R.string.ui_edge_softness to "softness",
                R.string.ui_edge_spread to "spread")){
                divider(details)
                val setting=key(field)
                add(details,slider(getString(label),setting,0,OpticalSettings.maximum(setting),"%"))
            }
        }
    }
    private fun featureRestartActions(): List<View> {
        val launcher=screen in setOf(Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK,Screen.MOTION)
        val systemUi=screen in setOf(Screen.BANNERS,Screen.LIVE,Screen.VOLUME,Screen.LOCKSCREEN,Screen.SHADE)
        if (!launcher && !systemUi) return emptyList()
        val actions = mutableListOf<View>()
        if(screen==Screen.BANNERS){
            val test=smallIcon(R.drawable.ic_catalog_bell,getString(R.string.ui_banner_test_short)){
                if(Build.VERSION.SDK_INT>=33 && !BannerTestNotification.hasPermission(this))
                    bannerPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else BannerTestNotification.post(this)
            }
            actions.add(test)
            gate(test){master() && bool("banner_glass_enabled")}
        }
        val label=getString(if(launcher)R.string.ui_restart_launcher else R.string.ui_restart_systemui)
        val control=smallIcon(R.drawable.ic_restart,label,chrome=true,
            action=if(launcher)::confirmLauncherRestart else ::restartSystemUi).apply {tooltipText=label}
        actions.add(control)
        return actions
    }

    private fun bannerSection(body:LinearLayout,general:LinearLayout,colors:LinearLayout){
        val enabled={master() && bool("banner_glass_enabled")}
        val motionEnabled={enabled() && bool("banner_glass_motion")}
        val plateEnabled={enabled() && bool("banner_plate_enabled")}
        section(body,getString(R.string.banner_title),getString(R.string.banner_hint),"banner_glass_enabled",
            bannerExpanded,{bannerExpanded=it},::master,enabled) {details->
            fun group(parent:LinearLayout,title:Int,controls:List<View>){
                val entries=column()
                controls.forEachIndexed {index,control->
                    if(index>0)divider(entries)
                    add(entries,control)
                }
                gate(entries,enabled)
                accordionCard(parent,title,entries)
            }
            fun range(label:Int,key:String,min:Int=0,unit:String="%",allowed:(()->Boolean)?=null):View {
                return slider(getString(label),key,min,OpticalSettings.maximum(key),unit).also {
                    if(allowed!=null)gate(it,allowed)
                }
            }
            val glassType=column()
            val plateColor=column()
            glassTypeChoice(glassType,null,GlassEngineSettings.BANNERS,allowed=enabled,colors=plateColor) {
                addGlassBlur(it,"banner_glass_blur",GlassEngineSettings.blurOpacityKey(GlassEngineSettings.BANNERS),50,enabled)
            }
            listOf(
                glassType,
                range(R.string.ui_liquid_width,"banner_glass_width"),
                range(R.string.ui_liquid_distortion,"banner_glass_distortion"),
                range(R.string.island_opacity,"banner_glass_opacity"),
                range(R.string.island_lens_corner,"banner_glass_corner"),
                glassDispersion(GlassDispersionSettings.BANNERS,enabled)
            ).forEach {add(details,it)}
            add(general,card(preferenceLink(getString(R.string.banner_test_channel_settings),getString(R.string.banner_test_hint)){
                BannerTestNotification.openSettings(this)
            }),bottom=8)
            group(general,R.string.ui_banner_group_motion,listOf(
                switchRow(getString(R.string.banner_motion),"banner_glass_motion"),
                switchRow(getString(R.string.ui_soft_motion),"banner_motion_soft").also {gate(it,motionEnabled)},
                range(R.string.banner_open_speed,"banner_open_speed",min=50,allowed=motionEnabled),
                range(R.string.banner_close_speed,"banner_close_speed",min=50,allowed=motionEnabled)
            ))
            group(details,R.string.ui_banner_group_shading,listOf(
                range(R.string.banner_shadow,"banner_shadow_strength"),
                range(R.string.banner_shadow_size,"banner_shadow_size",unit=" dp"),
                range(R.string.banner_top_darkness,"banner_top_darkness")
            ))
            colorControls(plateColor,"banner_plate_hue","banner_plate_saturation","banner_plate_brightness",plateEnabled)
            group(colors,R.string.ui_banner_group_plate,listOf(
                switchRow(getString(R.string.banner_plate),"banner_plate_enabled"),
                range(R.string.ui_tint_lightness,"banner_plate_lightness",min=-100,allowed=plateEnabled),
                range(R.string.banner_plate_transparency,"banner_plate_opacity",allowed=plateEnabled),
                range(R.string.banner_plate_fade,"banner_plate_bottom_fade",allowed=plateEnabled),
                plateColor
            ))

        }
    }

    private fun liveAlertSection(body:LinearLayout,colors:LinearLayout) {
        val enabled={master() && bool("island_glass_enabled")}
        val tone=column()
        section(body,getString(R.string.island_title),getString(R.string.island_hint),"island_glass_enabled",
            islandExpanded,{islandExpanded=it},::master,enabled){details->
            glassTypeChoice(details,null,GlassEngineSettings.LIVE,allowed=enabled,colors=tone) {
                addGlassBlur(it,"island_glass_blur",GlassEngineSettings.blurOpacityKey(GlassEngineSettings.LIVE),50,enabled)
            }
            materialTint(tone,5,enabled)
            accordionCard(colors,R.string.ui_feature_tab_colors,tone)
            add(details,glassDispersion(GlassDispersionSettings.ALERTS))
            for((label,key) in listOf(R.string.ui_liquid_width to "island_glass_width",
                R.string.ui_liquid_distortion to "island_glass_distortion",
                R.string.island_mix to "island_glass_mix",R.string.island_opacity to "island_glass_opacity",
                R.string.island_lens_corner to "island_glass_corner"))
                add(details,slider(getString(label),key,0,OpticalSettings.maximum(key),if(key.endsWith("blur"))"" else "%"))
        }
    }

    private fun volumeSection(body:LinearLayout,general:LinearLayout,colors:LinearLayout){
        val content=column()
        val enabled={master() && bool("volume_glass_enabled")}
        add(general,card(switchRow(getString(R.string.ui_volume_glass),"volume_glass_enabled",
            getString(R.string.ui_volume_glass_hint)).also {gate(it,::master)}),bottom=8)
        val tone=column()
        glassTypeChoice(content,"volume_glass_style",GlassEngineSettings.VOLUME,allowed=enabled) {
            addGlassBlur(it,"volume_glass_blur",GlassEngineSettings.blurOpacityKey(GlassEngineSettings.VOLUME),32,enabled)
        }
        for((label,key) in listOf(R.string.ui_volume_background_brightness to "volume_glass_brightness",
            R.string.ui_volume_height to "volume_glass_height",
            R.string.ui_volume_rim to "volume_glass_rim")){
            val control=slider(getString(label),key,OpticalSettings.minimum(key),OpticalSettings.maximum(key),"%")
            gate(control,enabled)
            if(key=="volume_glass_brightness")add(tone,control)
            else if(key=="volume_glass_height")add(general,card(control),bottom=8)
            else add(content,control)
        }
        val optics=column()
        for((label,key) in listOf(
            R.string.ui_liquid_width to "volume_glass_width",
            R.string.ui_liquid_distortion to "volume_glass_distortion",
            R.string.island_opacity to "volume_glass_opacity",
            R.string.ui_glass_dispersion to "volume_glass_dispersion")){
            add(optics,slider(getString(label),key,0,OpticalSettings.maximum(key),"%"))
        }
        gate(optics){enabled() && number("volume_glass_style")==1}
        add(content,optics)
        materialTint(tone,13,enabled)
        accordionCard(colors,R.string.ui_feature_tab_colors,tone)
        gate(content,::master)
        accordionCard(body,R.string.ui_volume_glass,content)
    }

    private fun wallpaperGlowSection(body:LinearLayout) {
        section(body,getString(R.string.ui_soft_light_title),getString(R.string.ui_soft_light_hint),WallpaperGlowSettings.ENABLED,
            glowExpanded,{glowExpanded=it},::master,{master() && bool(WallpaperGlowSettings.ENABLED)}){details->
            for((label,key) in listOf(R.string.ui_haze_strength to WallpaperGlowSettings.STRENGTH,
                R.string.ui_haze_feather to WallpaperGlowSettings.SOFTNESS,R.string.ui_diffusion to WallpaperGlowSettings.DIFFUSION,
                R.string.ui_wallpaper_glow_radius to WallpaperGlowSettings.RADIUS,R.string.ui_wallpaper_glow_threshold to WallpaperGlowSettings.THRESHOLD,
                R.string.ui_wallpaper_glow_saturation to WallpaperGlowSettings.SATURATION,
                R.string.ui_haze_icons to WallpaperGlowSettings.ICONS,
                R.string.ui_haze_folders to WallpaperGlowSettings.FOLDERS,
                R.string.ui_haze_widgets to WallpaperGlowSettings.WIDGETS,R.string.ui_haze_dock to WallpaperGlowSettings.DOCK,
                R.string.ui_haze_banners to WallpaperGlowSettings.BANNERS,R.string.ui_haze_live to WallpaperGlowSettings.LIVE))
                {
                    val control=slider(getString(label),key,0,WallpaperGlowSettings.maximum(key),"%")
                    add(details,control)
                }
        }
    }

    private fun modeChoice(parent:LinearLayout,title:Int,key:String,labels:List<String>,
        valid:(Int)->Boolean={true},allowed:()->Boolean){
        featureKeys.add(key)
        fun selected()=number(key).coerceIn(labels.indices)
        val row=optionRow(getString(title),labels[selected()])
        refreshers.add { row.value=labels[selected()] }
        row.setOnClickListener {
            haptic(it)
            val choices=labels.mapIndexed {id,label->id to label}.filter {valid(it.first)}
            showChoicePopup(row,choices,selected()){next->
                save(key,next)
                refreshControls()
            }
        }
        gate(row,allowed)
        add(parent,row,bottom=4)
    }

    private fun glassTypeChoice(parent:LinearLayout,styleKey:String?,engineKey:String,
        base:List<String> = listOf("Frosted","Liquid Glass"), liquid:Int=1,
        title:Int=R.string.ui_blur_style, allowed:()->Boolean,colors:LinearLayout?=null,blurControls:((LinearLayout)->Unit)?=null) {
        if(styleKey!=null)featureKeys.add(styleKey)
        featureKeys.add(engineKey)
        val labels=(if(styleKey==null)listOf("Liquid Glass") else base)+getString(R.string.glass_engine_new)
        fun selected():Int {
            val style=styleKey?.let {number(it).coerceIn(base.indices)} ?: 0
            return if((styleKey==null || style==liquid) && Build.VERSION.SDK_INT>=33 && bool(engineKey))
                labels.lastIndex else style
        }
        val row=optionRow(getString(title),labels[selected()])
        refreshers.add {row.value=labels[selected()]}
        row.setOnClickListener {
            haptic(it)
            val choices=labels.mapIndexed {id,label->id to label}.filter { (id,_) ->
                Build.VERSION.SDK_INT>=33 || id!=labels.lastIndex && (styleKey==null || id!=liquid)
            }
            showChoicePopup(row,choices,selected()) {next->
                val newEngine=next==labels.lastIndex
                if(styleKey==null)save(engineKey,newEngine)
                else save(styleKey,if(newEngine)liquid else next,engineKey to newEngine)
                refreshControls()
            }
        }
        gate(row,allowed)
        add(parent,row,bottom=4)
        if(blurControls!=null)add(parent,column().also(blurControls),bottom=4)
        val shade=ShadeGlassSettings.Panel.entries.any {it.newEngine==engineKey}
        if(Build.VERSION.SDK_INT>=33){
            val height=GlassEngineSettings.Parameter.LENS_HEIGHT
            val heightControl=slider(getString(R.string.glass_lens_height),GlassEngineSettings.key(engineKey,height),
                0,height.max,"%",getString(R.string.glass_lens_height_hint))
            val visible={styleKey==null || number(styleKey)==liquid}
            heightControl.visibility=if(visible())View.VISIBLE else View.GONE
            refreshers.add {reveal(heightControl,visible())}
            gate(heightControl,allowed)
            add(parent,heightControl)
        }
        if(!shade && engineKey!=GlassEngineSettings.VOLUME){
            val brightness=GlassEngineSettings.Parameter.BACKDROP_BRIGHTNESS
            add(colors ?: parent,slider(getString(R.string.glass_backdrop_brightness),GlassEngineSettings.key(engineKey,brightness),
                0,brightness.max,"%").apply {gate(this,allowed)})
        }
        val advanced=column()
        if(Build.VERSION.SDK_INT<33)return
        add(advanced,text(getString(R.string.glass_optics_group),14f,true),bottom=4)
        val opticalControls=listOf(
            GlassEngineSettings.Parameter.POWER to R.string.glass_engine_power,
            GlassEngineSettings.Parameter.EDGE_WIDTH to R.string.glass_engine_edge_width,
            GlassEngineSettings.Parameter.EDGE_LIGHT to R.string.glass_engine_edge_light)
        for((parameter,label) in opticalControls){
            add(advanced,slider(getString(label),GlassEngineSettings.key(engineKey,parameter),
                0,parameter.max,"%"))
        }
        val details=SettingsAccordion(advanced,selected()==labels.lastIndex)
        add(parent,details,bottom=4)
        gate(advanced,allowed)
        refreshers.add {
            val newEngine=selected()==labels.lastIndex
            val animate=canAnimate()
            details.setOpen(newEngine,animate)
        }
    }

    private fun addGlassBlur(parent:LinearLayout,radius:String,opacity:String,max:Int,allowed:()->Boolean,
        inherited:Boolean=false) {
        val minimum=if(inherited)-1 else 0
        val inherit=getString(R.string.shade_glass_inherit)
        add(parent,slider(getString(R.string.glass_blur_group),radius,minimum,max," dp",
            formatter={if(it<0)inherit else "$it dp"}).apply {gate(this,allowed)})
        add(parent,slider(getString(R.string.shade_glass_blur_opacity),opacity,minimum,100,"%",
            formatter={if(it<0)inherit else "$it%"},inverseOpacity=true).apply {gate(this,allowed)})
    }

    private fun dockSection(body:LinearLayout,general:LinearLayout,colors:LinearLayout) {
        val content=column().apply {setPadding(0,dp(3),0,dp(5))}
        add(content,text(getString(R.string.ui_dock_glass_hint),14f,true).apply {
            setPadding(dp(15),dp(9),dp(15),dp(5))
        },bottom=3)
        val dockEnabled={master() && number(SettingsProvider.KEY_DOCK_STYLE)>0}
        val tone=column()
        glassTypeChoice(content,SettingsProvider.KEY_DOCK_STYLE,GlassEngineSettings.DOCK,
            listOf(getString(R.string.ui_glass_off),"Frosted","Liquid Glass"),
            liquid=2,allowed=::master,colors=tone) { addGlassBlur(it,SettingsProvider.KEY_DOCK_BLUR,GlassEngineSettings.blurOpacityKey(GlassEngineSettings.DOCK),40,dockEnabled) }
        val controls=column()
        add(controls,slider(getString(R.string.ui_dock_width),"dock_width_percent",55,160,"%"))
        add(controls,slider(getString(R.string.ui_dock_height),"dock_height_percent",55,160,"%"))
        add(tone,slider(getString(R.string.ui_dock_backdrop_saturation),"dock_backdrop_saturation",0,200,"%"))
        add(tone,slider(getString(R.string.ui_dock_backdrop_darkness),"dock_backdrop_darkness",0,100,"%"))
        gate(tone,dockEnabled)
        materialTint(tone,3,dockEnabled)
        accordionCard(colors,R.string.ui_dock_glass,tone)
        add(controls,slider(getString(R.string.ui_dock_rounding),SettingsProvider.KEY_DOCK_CORNER,0,50,"%"))
        add(controls,slider(getString(R.string.ui_dock_inset),SettingsProvider.KEY_DOCK_INSET,0,32," dp"))
        gate(controls,dockEnabled)
        accordionCard(general,R.string.ui_dock_glass,controls)
        val liquid=column()
        add(liquid,slider(getString(R.string.ui_liquid_width),SettingsProvider.KEY_DOCK_LIQUID_WIDTH,0,100,"%"))
        add(liquid,slider(getString(R.string.ui_liquid_distortion),SettingsProvider.KEY_DOCK_LIQUID_DISTORTION,0,300,"%"))
        add(liquid,glassDispersion(GlassDispersionSettings.DOCK))
        gate(liquid){master() && number(SettingsProvider.KEY_DOCK_STYLE)==2}
        add(content,liquid)
        accordionCard(body,R.string.ui_dock_glass,content)
        val indicator=column().apply {setPadding(0,dp(3),0,dp(5))}
        val keepIndicator=switchRow(getString(R.string.ui_page_indicator_keep_visible),"indicator_keep_visible")
        gate(keepIndicator,::master)
        add(general,card(keepIndicator),bottom=8)
        val indicatorEnabled={master() && number("indicator_glass_style")>0}
        val indicatorTone=column()
        glassTypeChoice(indicator,"indicator_glass_style",GlassEngineSettings.INDICATOR,
            listOf(getString(R.string.ui_glass_off),"Frosted","Liquid Glass"),
            liquid=2,title=R.string.ui_page_indicator_glass,allowed=::master,colors=indicatorTone) {
            addGlassBlur(it,"indicator_glass_blur",GlassEngineSettings.blurOpacityKey(GlassEngineSettings.INDICATOR),OpticalSettings.maximum("indicator_glass_blur"),indicatorEnabled)
        }
        val indicatorControls=column()
        for((label,key) in listOf(R.string.island_opacity to "indicator_glass_opacity",
            R.string.ui_glass_corner to "indicator_glass_corner"))
            add(indicatorControls,slider(getString(label),key,0,OpticalSettings.maximum(key),"%"))
        gate(indicatorControls,indicatorEnabled)
        materialTint(indicatorTone,12,indicatorEnabled)
        accordionCard(colors,R.string.ui_page_indicator_glass,indicatorTone)
        add(indicator,indicatorControls)
        val indicatorLens=column()
        for((label,key) in listOf(R.string.ui_liquid_width to "indicator_glass_width",
            R.string.ui_liquid_distortion to "indicator_glass_distortion",R.string.ui_glass_dispersion to "indicator_glass_dispersion"))
            add(indicatorLens,slider(getString(label),key,0,OpticalSettings.maximum(key),"%"))
        gate(indicatorLens){master() && number("indicator_glass_style")==2}
        add(indicator,indicatorLens)
        accordionCard(body,R.string.ui_page_indicator_glass,indicator)
    }

    private fun settingsTransferRows()=linkedMapOf(
        R.string.ui_export_settings to dialogPreference(R.string.ui_export_settings){anchor,held->showConfigExport(anchor,holdTouch=held)},
        R.string.ui_import_settings to dialogPreference(R.string.ui_import_settings,::showConfigImport),
        R.string.ui_share_settings to dialogPreference(R.string.ui_share_settings){anchor,held->showConfigExport(anchor,holdTouch=held)}
    )

    private fun dialogPreference(label:Int,open:(View,Boolean)->Unit)=
        CouiPreference(this,darkUi,primary,getString(label),null,null,onSurface,
            onPreview={open(it,true)},longPressLabel=label){
            haptic(it)
            open(it,false)
        }

    private fun showConfigImport(anchor:View,holdTouch:Boolean){
        val form=column().apply {setPadding(dp(17),dp(11),dp(17),dp(16))}
        lateinit var dialog:CouiAnchoredDialog
        add(form,CouiSlot(this,darkUi,primary){
            FloatingDialogHeader(getString(R.string.ui_import_settings),ComposeColor(onSurface),
                onClose={dialog.dismiss()})
        },bottom=12)
        add(form,CouiSlot(this,darkUi,primary){
            CouiAction(getString(R.string.ui_config_pick_file),primary=true,modifier=Modifier.fillMaxWidth(),
                onClick={
                    pendingConfigImport=ImportRequest(anchor,dialog)
                    openConfigPicker {importSettings.launch(arrayOf("application/json","text/plain","application/octet-stream"))}
                })
        })
        dialog=anchoredDialog(form,anchor,holdTouch=holdTouch,swipeHeader=true)
    }

    private fun openConfigPicker(open:()->Unit){
        try {open()} catch(_:ActivityNotFoundException){
            pendingConfigExport=null
            pendingConfigImport=null
            Toast.makeText(this,R.string.ui_file_picker_error,Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConfigExport(anchor:View?,draft:SettingsTransfer.Metadata=SettingsTransfer.Metadata(),
        snapshot:String=SettingsTransfer.export(prefs),holdTouch:Boolean=false){
        val form=column().apply {setPadding(dp(17),dp(11),dp(17),dp(16))}
        val fields=column()
        val title=CouiField(this,darkUi,primary,getString(R.string.ui_config_title),draft.title,onSurface,
            SettingsTransfer.TITLE_LIMIT,autofocus=false)
        val description=CouiField(this,darkUi,primary,getString(R.string.ui_config_description),draft.description,
            onSurface,SettingsTransfer.DESCRIPTION_LIMIT,autofocus=false,multiline=true)
        val author=CouiField(this,darkUi,primary,getString(R.string.ui_config_author),draft.author,onSurface,
            SettingsTransfer.AUTHOR_LIMIT,autofocus=false)
        fun metadata()=SettingsTransfer.Metadata(title.value.trim(),description.value.trim(),author.value.trim())
        lateinit var dialog:CouiAnchoredDialog
        add(form,CouiSlot(this,darkUi,primary){
            FloatingDialogHeader(getString(R.string.ui_export_settings),ComposeColor(onSurface),
                onClose={dialog.dismiss()},actionIcon=R.drawable.ic_cpi_preview,
                actionDescription=getString(R.string.ui_config_import_preview),onAction={
                    val data=metadata()
                    runCatching {SettingsTransfer.parseDocument(SettingsTransfer.withMetadata(snapshot,data))}
                        .onSuccess {document->dialog.close {showConfigPreview(document,anchor,returnToExport={
                            showConfigExport(anchor,data,snapshot)
                        })}}
                        .onFailure(::configError)
                })
        },bottom=12)
        add(fields,title,bottom=10)
        add(fields,description,bottom=10)
        add(fields,author)
        form.addView(ScrollView(this).apply {
            isFillViewport=false
            addView(fields)
        },LinearLayout.LayoutParams(-1,-2,1f))
        add(form,CouiSlot(this,darkUi,primary){
            androidx.compose.foundation.layout.Column(verticalArrangement=Arrangement.spacedBy(8.couiDp)){
                CouiAction(getString(R.string.ui_config_save_file),primary=false,modifier=Modifier.fillMaxWidth(),
                    onClick={
                        val data=metadata()
                        pendingConfigExport=SettingsTransfer.withMetadata(snapshot,data)
                        openConfigPicker {exportSettings.launch(configFilename(data.title))}
                    })
                CouiAction(getString(R.string.ui_share_settings),primary=true,modifier=Modifier.fillMaxWidth(),
                    onClick={val data=metadata();shareSettings(SettingsTransfer.withMetadata(snapshot,data),data.title)})
            }
        },top=12)
        dialog=anchoredDialog(form,anchor,holdTouch=holdTouch,swipeHeader=true)
        dialog.animateWithKeyboard()
    }

    private fun showConfigPreview(document:SettingsTransfer.Document,anchor:View?=null,returnToExport:(()->Unit)?=null){
        val config=column().apply {setPadding(dp(17),dp(11),dp(17),dp(16))}
        val body=column()
        val title=document.metadata.title.ifBlank {getString(R.string.ui_config_legacy_title)}
        lateinit var dialog:CouiAnchoredDialog
        fun cancel(){dialog.close {returnToExport?.invoke()}}
        add(config,CouiSlot(this,darkUi,primary){
            FloatingDialogHeader(getString(R.string.ui_config_preview_title),ComposeColor(onSurface),onClose=::cancel)
        },bottom=5)
        val details=listOf(R.string.ui_config_title to title,R.string.ui_config_description to document.metadata.description,
            R.string.ui_config_author to document.metadata.author)
        add(body,card(CouiSlot(this,darkUi,primary){
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().padding(vertical=4.couiDp)){
                val colors=io.github.suqi8.coui.kmp.theme.COUITheme.colorScheme
                details.forEachIndexed {index,(label,value)->
                    if(index>0)HorizontalDivider()
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth()
                        .padding(horizontal=16.couiDp,vertical=12.couiDp),
                        horizontalArrangement=Arrangement.spacedBy(12.couiDp),
                        verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                        val heading=getString(label)
                        val content=value.ifBlank {"—"}
                        io.github.suqi8.coui.kmp.basic.Text(heading,
                            modifier=Modifier.weight(.42f),
                            color=colors.labelSecondary,
                            style=couiTextStyles.bodyM)
                        io.github.suqi8.coui.kmp.basic.Text(content,
                            modifier=Modifier.weight(.58f),
                            color=ComposeColor(onSurface),
                            style=couiTextStyles.bodyM,textAlign=androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }),bottom=9)
        addConfigScenes(body,document)
        config.addView(ScrollView(this).apply {addView(body)},LinearLayout.LayoutParams(-1,-2,1f))
        add(config,CouiSlot(this,darkUi,primary){
            androidx.compose.foundation.layout.Row(horizontalArrangement=Arrangement.spacedBy(8.couiDp)){
                CouiAction(getString(android.R.string.cancel),primary=false,modifier=Modifier.weight(1f),onClick=::cancel)
                CouiAction(getString(R.string.ui_config_apply),primary=true,modifier=Modifier.weight(1f),
                    enabled=returnToExport==null,onClick={dialog.close {applyConfig(document)}})
            }
        },top=12)
        dialog=anchoredDialog(config,anchor,swipeHeader=true)
        if(returnToExport!=null)dialog.setOnCancelListener {returnToExport()}
    }



    private fun addConfigScenes(body:LinearLayout,document:SettingsTransfer.Document){
        val snapshot=Bundle()
        for((key,value) in SettingsTransfer.previewValues(document))when(value){
            is Boolean->snapshot.putBoolean(key,value)
            is Int->snapshot.putInt(key,value)
        }
        val sections=listOf(Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK,Screen.BANNERS,Screen.LIVE,Screen.VOLUME)
        val options=sections.flatMap {screen->
            if(screen==Screen.PHOTO)WidgetPreviewKind.entries.map {screen to it}
            else listOf(screen to null)
        }
        val widgetNames=NativePreviewScenes.widgetOptions(this)
        val labels=options.map {(screen,widget)->if(widget!=null)widgetNames[widget.ordinal] else getString(screen.title)}
        val preview=GlassPreview(this,darkUi,useDeviceWallpaperColor=false)
        val note=mutableStateOf<Int?>(R.string.ui_config_loading)
        val sceneStatus=mutableStateOf<Int?>(R.string.ui_config_loading)
        val sceneResults=mutableMapOf<Int,NativePreviewScenes.Scene?>()
        val pending=mutableSetOf<Int>()
        val context=applicationContext
        val packages=prefs.getString("ui_preview_apps","").orEmpty().split('|').filter {it.isNotBlank()}
        var wallpaper:android.graphics.Bitmap?=null
        var wallpaperReady=false
        var icons=emptyList<com.cosmod.launcherglass.util.PreviewIcons.Icon>()
        var folders=emptyMap<FolderPreviewKind,NativePreviewScenes.Scene>()
        val photoSamples=mutableMapOf<WidgetPreviewKind,NativePreviewScenes.Scene>()
        var selection=0
        fun render(){
            val (screen,kind)=options[selection]
            val loaded=selection in sceneResults
            val scene=sceneResults[selection] ?: kind?.takeIf { it.family=="photo" }?.let {photoKind->
                wallpaper?.let {image->photoSamples.getOrPut(photoKind){NativePreviewScenes.photo(image,photoKind)}}
            }
            val available=when(screen){
                Screen.ICONS->icons.isNotEmpty()
                Screen.FOLDERS->folders.isNotEmpty()
                else->scene!=null
            }
            preview.contentDescription=if(available)labels[selection] else null
            preview.visibility=if(available)View.VISIBLE else View.INVISIBLE
            preview.setScene(wallpaper,if(screen==Screen.ICONS)icons else emptyList())
            preview.setFolderScenes(if(screen==Screen.FOLDERS)folders else emptyMap())
            preview.setContentScene(scene,kind!=null)
            if(available)renderGlassPreview(preview,screen,Bundle(snapshot))
            sceneStatus.value=if(available)null else if(loaded)R.string.ui_config_preview_unavailable else R.string.ui_config_loading
            note.value=if(wallpaperReady && wallpaper==null)R.string.ui_config_wallpaper_unavailable else null
        }
        fun select(index:Int){
            selection=index
            render()
            if(index in sceneResults || !pending.add(index))return
            val (screen,widget)=options[index]
            val bannerDark=if(screen==Screen.BANNERS)
                renderGlassPreview(preview,screen,Bundle(snapshot)) ?: darkUi else darkUi
            previewExecutor.execute {
                val sourceIcons=when(screen){
                    Screen.ICONS->readPreviewIcons(context,packages)
                    Screen.FOLDERS->runCatching {
                        com.cosmod.launcherglass.util.PreviewIcons.readInstalled(context,packages,limit=9)
                    }.getOrDefault(emptyList())
                    else->null
                }
                val folderScenes=if(screen==Screen.FOLDERS)FolderPreviewKind.entries.mapNotNull {kind->
                    (com.cosmod.launcherglass.util.NativeWidgetPreviews.folder(context,kind)
                        ?: runCatching {NativePreviewScenes.folder(kind,sourceIcons.orEmpty())}.getOrNull())?.let {kind to it}
                }.toMap() else null
                val scene=when(screen){
                    Screen.PHOTO->widget?.let {com.cosmod.launcherglass.util.NativeWidgetPreviews.read(context,it)}
                    Screen.DOCK->com.cosmod.launcherglass.util.NativeWidgetPreviews.material(context,SurfacePreviewKind.DOCK)
                    Screen.LIVE->com.cosmod.launcherglass.util.NativeWidgetPreviews.material(context,SurfacePreviewKind.LIVE)
                    Screen.VOLUME->com.cosmod.launcherglass.util.NativeWidgetPreviews.material(context,SurfacePreviewKind.VOLUME)
                    else->null
                }
                val photo=if(widget?.family=="photo" && scene==null)
                    com.cosmod.launcherglass.util.PreviewPhotoStore.read(context) else null
                runOnUiThread {
                    if(isDestroyed || isFinishing || !body.isAttachedToWindow)return@runOnUiThread
                    pending.remove(index)
                    if(sourceIcons!=null)icons=sourceIcons
                    if(folderScenes!=null)folders=folderScenes
                    sceneResults[index]=scene ?: when{
                        screen==Screen.BANNERS->NativePreviewScenes.notification(this,0,bannerDark)
                        screen==Screen.VOLUME->NativePreviewScenes.volume(this,darkUi)
                        screen==Screen.LIVE->NativePreviewScenes.liveAlert(this,darkUi)
                        widget==null->null
                        photo!=null->NativePreviewScenes.photo(photo,widget)
                        else->NativePreviewScenes.widget(this,widget)
                            ?: widget.takeUnless {it.family=="photo"}?.let {NativePreviewScenes.widgetSample(this,it)}
                    }
                    if(index==selection)render()
                }
            }
        }
        val sampleCard=column().apply {setPadding(dp(10),0,dp(10),dp(10))}
        add(sampleCard,CouiSlot(this,darkUi,primary){
            val option=remember {mutableIntStateOf(0)}
            WindowDropdownPreference(labels,option.intValue,labels[option.intValue],
                dropdownColors=DropdownDefaults.dropdownColors(),maxHeight=260.couiDp,showValue=false,
                onSelectedIndexChange={index->
                    option.intValue=index
                    select(index)
                })
        })
        val frame=FrameLayout(this).apply {
            background=panelShape(raised,18)
            addView(preview,FrameLayout.LayoutParams(-1,-1))
            addView(CouiSlot(this@MainActivity,darkUi,primary){
                sceneStatus.value?.let {label->
                    io.github.suqi8.coui.kmp.basic.Text(getString(label),
                        modifier=Modifier.fillMaxWidth().padding(horizontal=12.couiDp),
                        color=ComposeColor(onSurface),style=couiTextStyles.bodyM,
                        textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
            },FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))
        }
        sampleCard.addView(frame,LinearLayout.LayoutParams(-1,dp(144)))
        add(sampleCard,CouiSlot(this,darkUi,primary){
            note.value?.let {label->
                io.github.suqi8.coui.kmp.basic.Text(getString(label),modifier=Modifier.padding(top=6.couiDp),
                    color=io.github.suqi8.coui.kmp.theme.COUITheme.colorScheme.labelSecondary,
                    style=couiTextStyles.bodyM)
            }
        })
        add(body,CouiSlot(this,darkUi,primary){SmallTitle(getString(R.string.ui_config_local_preview))},bottom=3)
        add(body,card(sampleCard))
        watchPreviewWallpaper(preview) {frame->
            val hadWallpaper=wallpaper!=null
            wallpaper=frame
            if(hadWallpaper)preview.setWallpaper(frame) else render()
        }
        body.doOnLayout {
            previewExecutor.execute {
                val local=runCatching {com.cosmod.launcherglass.util.PreviewWallpaper.read(context)}.getOrNull()
                runOnUiThread {
                    if(isDestroyed || isFinishing || !body.isAttachedToWindow)return@runOnUiThread
                    if(wallpaper==null)wallpaper=local
                    wallpaperReady=true
                    render()
                }
            }
            select(0)
        }
    }

    private fun configFilename(title:String):String {
        val base=title.replace(Regex("[^\\p{L}\\p{N} _-]"),"_").trim().take(60)
        return "${base.ifBlank {"CPI-config"}}.json"
    }

    private fun shareSettings(json:String,title:String){
        executor.execute {
            runCatching {
                val directory=java.io.File(cacheDir,"shared-settings").apply {mkdirs()}
                val oldest=System.currentTimeMillis()-86_400_000L
                directory.listFiles()?.filter {it.lastModified()<oldest}?.forEach {it.deleteRecursively()}
                val shareDirectory=java.nio.file.Files.createTempDirectory(directory.toPath(),"CPI-").toFile()
                val file=java.io.File(shareDirectory,configFilename(title))
                file.writeText(json)
                val uri=androidx.core.content.FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.logs",file)
                runOnUiThread {
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    val intent=Intent(Intent.ACTION_SEND).apply {
                        type="application/json"
                        putExtra(Intent.EXTRA_STREAM,uri)
                        clipData=ClipData.newUri(contentResolver,file.name,uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    openConfigPicker {startActivity(Intent.createChooser(intent,getString(R.string.ui_share_settings)))}
                }
            }.onFailure(::configError)
        }
    }

    private fun readConfig(uri:Uri,request:ImportRequest?){
        executor.execute {
            runCatching {
                contentResolver.openInputStream(uri)?.use(SettingsTransfer::readDocument) ?: error("Cannot open config")
            }.onSuccess {document->runOnUiThread {
                if(isFinishing || isDestroyed)return@runOnUiThread
                if(request==null)showConfigPreview(document)
                else if(settingsDialogOwner===request.dialog)
                    request.dialog.close {showConfigPreview(document,request.anchor)}
            }}.onFailure(::configError)
        }
    }

    private fun saveConfig(uri:Uri,json:String){
        executor.execute {
            runCatching {
                val output=contentResolver.openOutputStream(uri,"wt") ?: error("Cannot create config")
                output.bufferedWriter(Charsets.UTF_8).use {it.write(json)}
            }.onSuccess {runOnUiThread {
                if(!isFinishing && !isDestroyed)Toast.makeText(this,R.string.ui_settings_exported,Toast.LENGTH_SHORT).show()
            }}.onFailure(::configError)
        }
    }

    private fun applyConfig(document:SettingsTransfer.Document){
        executor.execute {
            runCatching {
                SettingsTransfer.apply(prefs,document.values)
                contentResolver.notifyChange(SettingsProvider.CONTENT_URI,null)
            }.onSuccess {runOnUiThread {
                if(!isFinishing && !isDestroyed){
                    refreshCatalogStyle()
                    Toast.makeText(this,R.string.ui_settings_imported,Toast.LENGTH_LONG).show()
                }
            }}.onFailure(::configError)
        }
    }

    private fun configError(error:Throwable){
        runOnUiThread {
            if(!isFinishing && !isDestroyed)
                Toast.makeText(this,getString(R.string.ui_settings_transfer_error,error.message.orEmpty()),Toast.LENGTH_LONG).show()
        }
    }

    private fun folderSection(body:LinearLayout,general:LinearLayout,colors:LinearLayout){
        section(body,getString(R.string.ui_folders),"",SettingsProvider.KEY_FOLDER_BLUR_ENABLED,folderExpanded,
            {folderExpanded=it},::blur,{blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)}) {content ->
            add(content,switchRow(getString(R.string.ui_folder_own),FolderSettingsKeys.OWN,getString(R.string.ui_folder_mode_hint)))
            val mode=text(getString(if(bool(FolderSettingsKeys.OWN))R.string.ui_folder_mode_own else R.string.ui_folder_mode_icons),14f,true)
            mode.setPadding(dp(15),0,dp(15),0)
            add(content,mode,bottom=10)
            val controls=column().apply {visibility=if(bool(FolderSettingsKeys.OWN))View.VISIBLE else View.GONE}
            refreshers.add {
                controls.visibility=if(bool(FolderSettingsKeys.OWN))View.VISIBLE else View.GONE
                mode.text=getString(if(bool(FolderSettingsKeys.OWN))R.string.ui_folder_mode_own else R.string.ui_folder_mode_icons)
            }
            gate(controls){blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED) && bool(FolderSettingsKeys.OWN)}
            val colorControls=column()
            glassStyleSelector(controls,folder=true,colors=colorControls)
            val tone=column()
            val details=column()
            val accordion=SettingsAccordion(details,false)
            add(tone,switchRow(getString(R.string.ui_blur_tone),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_TONE_ENABLED),expanded=false){
                accordion.setOpen(it,android.animation.ValueAnimator.areAnimatorsEnabled())
            })
            gate(details){folderOwn() && bool(FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_TONE_ENABLED))}
            for((label,key) in listOf(R.string.ui_plate_opacity to SettingsProvider.KEY_BG_OPACITY,
                R.string.ui_blur_saturation to SettingsProvider.KEY_BLUR_SATURATION,
                R.string.ui_blur_vibrancy to SettingsProvider.KEY_BLUR_VIBRANCY,
                R.string.ui_blur_brightness to SettingsProvider.KEY_BLUR_LIGHTENING)){
                divider(details)
                add(details,slider(getString(label),FolderSettingsKeys.key(key),0,if(key==SettingsProvider.KEY_BLUR_SATURATION || key==SettingsProvider.KEY_BLUR_VIBRANCY)200 else 100,"%",
                    null))
            }
            add(tone,accordion)
            add(colorControls,tone)
            materialTint(colorControls,1){blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)}
            accordionCard(colors,R.string.ui_feature_tab_colors,colorControls)
            add(content,controls)
        }
        val available={blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)}
        val folderIcons=switchRow(getString(R.string.ui_folder_icons_blur),SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED)
        gate(folderIcons,::blur)
        add(general,card(folderIcons),bottom=8)
        val largeColors=column()
        effectGroup(body,getString(R.string.ui_large_folder_glass),"large_folder_glass_own",
            getString(R.string.ui_plate_opacity),"large_folder_glass_opacity",allowed=available){controls->
            val custom={available() && bool("large_folder_glass_own")}
            glassTypeChoice(controls,"large_folder_glass_style",GlassEngineSettings.LARGE_FOLDERS,allowed=custom,colors=largeColors) {
                addGlassBlur(it,GlassEngineSettings.key(GlassEngineSettings.LARGE_FOLDERS,GlassEngineSettings.Parameter.BLUR_RADIUS),
                    GlassEngineSettings.blurOpacityKey(GlassEngineSettings.LARGE_FOLDERS),60,custom)
            }
            for((label,key,max) in listOf(
                Triple(R.string.ui_liquid_width,"large_folder_glass_width",100),
                Triple(R.string.ui_liquid_distortion,"large_folder_glass_strength",300))){
                divider(controls)
                val setting=slider(getString(label),key,0,max,"%")
                gate(setting){custom() && number("large_folder_glass_style")==1}
                add(controls,setting)
            }
        }
        accordionCard(colors,R.string.ui_large_folder_glass,largeColors)
    }

    private fun glassStyleSelector(parent:LinearLayout,folder:Boolean,colors:LinearLayout){
        fun key(base:String)=if(folder)FolderSettingsKeys.key(base)else base
        val style=key(SettingsProvider.KEY_BLUR_STYLE)
        val allowed=if(folder)::folderOwn else ::blur
        glassTypeChoice(parent,style,if(folder)GlassEngineSettings.FOLDERS else GlassEngineSettings.ICONS,
            allowed=allowed,colors=colors) {
            addGlassBlur(it,key(SettingsProvider.KEY_BLUR_RADIUS),
                GlassEngineSettings.blurOpacityKey(if(folder)GlassEngineSettings.FOLDERS else GlassEngineSettings.ICONS),50,allowed)
        }
        val parameters=column()
        add(parameters,slider(getString(R.string.ui_liquid_width),key(SettingsProvider.KEY_LIQUID_WIDTH),0,100,"%"))
        add(parameters,slider(getString(R.string.ui_liquid_distortion),key(SettingsProvider.KEY_LIQUID_DISTORTION),0,300,"%"))
        if(folder)add(parameters,glassDispersion(GlassDispersionSettings.FOLDERS){folderOwn() && number(style)==1})
        parameters.visibility=if(number(style)==1)View.VISIBLE else View.GONE
        refreshers.add {reveal(parameters,number(style)==1)}
        gate(parameters){allowed() && number(style)==1}
        add(parent,parameters,bottom=8)
    }

    private fun coloringSection(parent: LinearLayout) {
        val wallpaperKey=com.cosmod.launcherglass.util.IconPalette.FOLLOW_WALLPAPER
        var wallpaperTone=com.cosmod.launcherglass.util.IconPalette.wallpaperTone(this)
        featureKeys.addAll(listOf(SettingsProvider.KEY_COLOR_MODE,SettingsProvider.KEY_CLEAR_BRIGHTNESS,wallpaperKey)+
            SettingsProvider.colorKeys(false)+SettingsProvider.colorKeys(true))
        fun activeKeys(): List<String> {
            val selected=SettingsProvider.colorKeys(darkIcons())
            return if(number(SettingsProvider.KEY_ICON_APPEARANCE)==0) selected.take(2)+SettingsProvider.KEY_CLEAR_BRIGHTNESS else selected
        }
        var keys = activeKeys()
        val group = column()
        val header=optionRow(getString(R.string.ui_coloring),"")
        add(group,header)
        val details=column().apply { setPadding(dp(20),0,dp(20),dp(16)); visibility=if(number(SettingsProvider.KEY_COLOR_MODE)==1)View.VISIBLE else View.GONE }
        add(group,details)
        var shown=number(SettingsProvider.KEY_COLOR_MODE)==1
        fun updateMode() {
            val detailed=number(SettingsProvider.KEY_COLOR_MODE)==1
            header.value=if(detailed)getString(R.string.ui_detailed) else getString(R.string.ui_system)
            if(shown!=detailed) { shown=detailed;reveal(details,detailed) }
        }
        header.setOnClickListener {
            haptic(it)
            choicePopup(header,listOf(0 to R.string.ui_system,1 to R.string.ui_detailed),
                number(SettingsProvider.KEY_COLOR_MODE)) { choice ->
                save(SettingsProvider.KEY_COLOR_MODE,choice)
                updateMode()
            }
        }
        val previewRow=row()
        val preview=FrameLayout(this)
        val previewShape=GradientDrawable().apply { cornerRadius=dp(18).toFloat() }
        preview.background=previewShape
        val glyph=AppCompatImageView(this).apply { setImageResource(R.drawable.ic_check);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        preview.addView(glyph,FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
        previewRow.addView(preview,LinearLayout.LayoutParams(dp(64),dp(64)))
        val previewLabel=text("",13f,true).apply { setPadding(dp(16),0,dp(8),0) }
        previewRow.addView(previewLabel,LinearLayout.LayoutParams(0,-2,1f))
        add(details,previewRow,4,16)
        modeChoice(details,R.string.ui_icon_color_style,SettingsProvider.KEY_ICON_APPEARANCE,listOf("CLEAR","COLOR"),allowed=::master)
        val wallpaperSwitch=switchRow(getString(R.string.ui_coloring_wallpaper),wallpaperKey)
        gate(wallpaperSwitch){master() && number(SettingsProvider.KEY_ICON_APPEARANCE)==1}
        add(details,wallpaperSwitch)
        var values=keys.map { number(it) }.toMutableList()
        val bands=mutableListOf<ColorBand>()
        val labels=mutableListOf<TextView>()
        fun paintPreview() {
            val clear=number(SettingsProvider.KEY_ICON_APPEARANCE)==0
            val tone=wallpaperTone?.takeIf {!clear && bool(wallpaperKey)}
            val hue=tone?.hue ?: values[0]
            val saturation=tone?.saturationAt(values[1]) ?: values[1]
            val palette=com.cosmod.launcherglass.util.IconPalette.from(1,hue,saturation,values[2],darkIcons(),clear)
            previewShape.setColor(palette.background)
            glyph.imageTintList=ColorStateList.valueOf(palette.foreground)
            val accent=Color.HSVToColor(floatArrayOf(hue.toFloat(),if(clear)0f else saturation/100f,values[2]/100f))
            previewLabel.text=String.format("#%06X",accent and 0xffffff)
            preview.contentDescription=getString(R.string.ui_color_preview,previewLabel.text)
            bands.forEachIndexed { index,band ->
                labels[index].setTextColor(secondaryText)
                band.monochrome=clear
                band.setColor(hue,if(clear)0 else if(index==1)values[1] else saturation,values[2])
                val value=if(index==0)hue else values[index]
                labels[index].text="$value${if(index==0) "°" else "%"}"
            }
        }
        listOf(getString(R.string.ui_hue),getString(R.string.ui_saturation),getString(R.string.ui_brightness)).forEachIndexed { index,label ->
            val line=row()
            line.addView(text(label,14f),LinearLayout.LayoutParams(0,-2,1f))
            val value=text("",14f,bold=true).apply {setTextColor(onSurface)}
            labels.add(value);line.addView(value)
            val bandGroup=column()
            add(bandGroup,line,10)
            add(details,bandGroup)
            if(index<2)gate(bandGroup) {
                master() && number(SettingsProvider.KEY_ICON_APPEARANCE)==1 &&
                    (index!=0 || !bool(wallpaperKey) || wallpaperTone==null)
            }
            val band=ColorBand(this,index,darkUi,primary).apply {
                contentDescription=label
                onChange={ next,commit ->
                    values[index]=next
                    previewDraft[keys[index]]=next
                    paintPreview()
                    updateGlassPreview()
                    if(commit){previewDraft.remove(keys[index]);save(keys[index],next)}
                }
            }
            bands.add(band);add(bandGroup,band)
        }
        refreshers.add {
            keys=activeKeys()
            values=keys.map {number(it)}.toMutableList()
            if(bool(wallpaperKey))wallpaperTone=com.cosmod.launcherglass.util.IconPalette.wallpaperTone(this)
            updateMode();paintPreview()
        }
        updateMode();paintPreview()
        val section=card(group)
        gates.add(0,Gate(section,::master))
        add(parent,section,bottom=12)
    }

    private fun section(parent: LinearLayout, title: String, subtitle: String, key: String,
        expanded: Boolean, onExpand: (Boolean) -> Unit, headerAllowed: () -> Boolean,
        contentAllowed: () -> Boolean, content: (LinearLayout) -> Unit) {
        val group = column()
        val children = column().apply { setPadding(0,0,0,dp(6)) }
        val accordion = SettingsAccordion(children, expanded)
        add(group, switchRow(title, key, expanded = expanded) { open ->
            onExpand(open)
            accordion.setOpen(open, android.animation.ValueAnimator.areAnimatorsEnabled())
        })
        add(group, accordion)
        val sectionCard = card(group)
        gate(sectionCard, headerAllowed)
        gate(children, contentAllowed)
        if(subtitle.isNotBlank()){
            divider(children)
            add(children,text(subtitle,12f,true).apply {setPadding(dp(15),dp(8),dp(15),dp(8))})
        }
        content(children)
        add(parent, sectionCard, bottom = 12)
    }

    private fun switchRow(label: String, key: String, subtitle: String? = null,
        expanded: Boolean = false, afterSave: (() -> Unit)? = null, onExpand: ((Boolean) -> Unit)? = null): View {
        featureKeys.add(key)
        val row = CouiSwitchRow(this, darkUi, primary, label, subtitle, bool(key), expanded, onExpand) {
            save(key, it)
            afterSave?.invoke()
        }
        refreshers.add { row.checked = bool(key) }
        return row
    }

    private fun clearGlassPreview(){
        shadePreview?.release()
        shadePreview=null
        previewDraft.clear()
        previewStatusRefresh=null;previewBannerDark=null;previewWidgetRefresh=null;previewFolderRefresh=null
        previewMaterialRefresh=null
        previewBannerRefresh = null
        glassPreview=null
        glassPreviewScreen=null
    }

    private fun addGlassPreview(body:LinearLayout){
        val screen=sectionScreen
        if(screen !in setOf(Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK,Screen.LIVE,Screen.BANNERS,Screen.VOLUME,Screen.LOCKSCREEN,Screen.GLASS))return
        val preview=GlassPreview(this,darkUi).apply {
            contentDescription=getString(R.string.ui_glass_preview)
        }
        glassPreview=preview
        glassPreviewScreen=screen
        loadPreviewScene(preview)
        watchPreviewWallpaper(preview) {frame->
            if(glassPreview===preview){
                val hadWallpaper=previewWallpaper!=null
                previewWallpaper=frame
                preview.setWallpaper(frame)
                if(!hadWallpaper)previewWidgetRefresh?.invoke()
            }
        }
        val section=column().apply {setPadding(dp(12),dp(10),dp(12),dp(12))}
        add(section,text(getString(R.string.ui_glass_preview),14f,bold=true),bottom=8)
        section.addView(preview,LinearLayout.LayoutParams(-1,dp(if(screen==Screen.VOLUME)144 else 108)))
        if(screen==Screen.LOCKSCREEN){
            val surfaces=LockscreenSettings.Surface.entries
            val picker=optionRow(getString(R.string.ui_preview_style),getString(lockPreviewSurface.title))
            picker.setOnClickListener {anchor->
                choicePopup(anchor,surfaces.map {it.ordinal to it.title},lockPreviewSurface.ordinal){index->
                    lockPreviewSurface=surfaces[index]
                    picker.value=getString(lockPreviewSurface.title)
                    updateGlassPreview()
                }
            }
            add(section,picker)
        }
        if(screen==Screen.PHOTO){
            preview.layoutParams=LinearLayout.LayoutParams(-1,dp(166))
            val options=NativePreviewScenes.widgetOptions(this)
            val legacy=WidgetPreviewKind.fromLegacy(prefs.getInt("ui_preview_widget_kind",0)).ordinal
            var selected=prefs.getInt("ui_preview_widget_kind_v2",legacy).coerceIn(options.indices)
            val picker=CouiSelection(this,darkUi,primary,getString(R.string.ui_preview_widget),options[selected])
            val status=text("",12f,true).apply {setPadding(dp(4),dp(6),dp(4),0)}
            val photoButton=preferenceLink(getString(R.string.ui_preview_pick_photo)){
                previewPhotoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            val samples=mutableMapOf<WidgetPreviewKind,NativePreviewScenes.Scene>()
            fun display(){
                val kind=WidgetPreviewKind.entries[selected]
                picker.value=options[selected]
                photoButton.visibility=if(kind.family=="photo")View.VISIBLE else View.GONE
                val image=if(kind.family=="photo")previewPhoto?.let { photo ->
                    previewPhotoScenes.getOrPut(kind) { NativePreviewScenes.photo(photo, kind) }
                }else null
                val scene=image ?: previewWidgets[selected] ?: samples[kind] ?: when {
                    kind.family!="photo"->NativePreviewScenes.widgetSample(this,kind)
                    else->previewWallpaper?.let {NativePreviewScenes.photo(it,kind)}
                }?.also {samples[kind]=it}
                preview.visibility=if(scene==null)View.GONE else View.VISIBLE
                preview.setContentScene(scene,true)
                status.visibility=if(scene==null)View.VISIBLE else View.GONE
                status.setText(if(selected in previewWidgetPending)R.string.ui_preview_widget_loading else
                    if(kind.family=="photo")R.string.ui_preview_photo_choose_hint else R.string.ui_preview_widget_missing)
                if(image==null && selected !in previewWidgetAttempted)loadWidgetPreview(kind)
            }
            previewWidgetRefresh=::display
            picker.setOnClickListener {anchor->showChoicePopup(anchor,options.mapIndexed {i,label->i to label},selected,280){index->
                selected=index;prefs.edit().putInt("ui_preview_widget_kind_v2",index).apply();display()
            }}
            divider(section);add(section,picker);add(section,photoButton);add(section,status)
            add(section,preferenceLink(getString(R.string.ui_preview_widget_refresh)){
                previewWidgetAttempted.remove(selected)
                previewWidgets.remove(selected)
                display()
            })
            display()
        }else if(screen==Screen.FOLDERS){
            preview.layoutParams = LinearLayout.LayoutParams(-1, dp(128))
            val labels = listOf(R.string.ui_preview_folder_standard, R.string.ui_preview_folder_stretched, R.string.ui_preview_folder_large)
            val status = text("", 12f, muted = true)
            var sampleIcons = previewIcons
            val samples = mutableMapOf<FolderPreviewKind, NativePreviewScenes.Scene>()
            add(section, status, top = 6)
            fun display() {
                if(sampleIcons!==previewIcons){sampleIcons=previewIcons;samples.clear()}
                val scenes = FolderPreviewKind.entries.mapNotNull { kind ->
                    val scene=previewFolders[kind.ordinal] ?: samples[kind] ?: NativePreviewScenes.folder(kind,sampleIcons)
                        ?.also {samples[kind]=it}
                    scene?.let { kind to it }
                }.toMap()
                preview.setFolderScenes(scenes)
                preview.visibility = if (scenes.isEmpty()) View.GONE else View.VISIBLE
                val missing = FolderPreviewKind.entries.filterNot(scenes::containsKey)
                status.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
                status.text = if (previewFolderPending.isNotEmpty()) getString(R.string.ui_preview_widget_loading) else {
                    getString(R.string.ui_preview_folder_missing) + "\n" +
                        missing.joinToString(" · ") { getString(labels[it.ordinal]) }
                }
            }
            previewFolderRefresh=::display
            add(section,preferenceLink(getString(R.string.ui_preview_widget_refresh)){
                FolderPreviewKind.entries.forEach(::loadFolderPreview)
            })
            FolderPreviewKind.entries.filter { it.ordinal !in previewFolderAttempted }.forEach(::loadFolderPreview)
            display()
        }else if(screen==Screen.BANNERS){
            preview.layoutParams=LinearLayout.LayoutParams(-1,dp(110))
            val styles=listOf(0 to R.string.ui_preview_sms,1 to R.string.ui_preview_mail,2 to R.string.ui_preview_music)
            var selected=prefs.getInt("ui_preview_banner_style",0).coerceIn(0,2)
            val picker=CouiSelection(this,darkUi,primary,getString(R.string.ui_preview_style),getString(styles[selected].second))
            var fallbackKey: Pair<Int, Boolean>? = null
            var fallback: NativePreviewScenes.Scene? = null
            fun display() {
                picker.value = getString(styles[selected].second)
                val kind = SurfacePreviewKind.banner(selected)
                val captured = previewMaterials[kind]
                val key = selected to (previewBannerDark ?: darkUi)
                if (captured == null && fallbackKey != key) {
                    fallback = NativePreviewScenes.notification(this, key.first, key.second)
                    fallbackKey = key
                }
                val scene = captured ?: fallback
                preview.setContentScene(scene, false)
                preview.visibility = if (scene == null) View.GONE else View.VISIBLE
            }
            previewBannerRefresh = ::display
            previewMaterialRefresh = ::display
            picker.setOnClickListener {anchor->choicePopup(anchor,styles,selected){index->
                selected=index;prefs.edit().putInt("ui_preview_banner_style",index).apply()
                display()
                loadMaterialPreview(SurfacePreviewKind.banner(index))
            }}
            display();divider(section);add(section,picker)
            add(section, preferenceLink(getString(R.string.ui_preview_widget_refresh)) {
                loadMaterialPreview(SurfacePreviewKind.banner(selected))
            })
            loadMaterialPreview(SurfacePreviewKind.banner(selected))
        }else{
            val kind=when(screen){
                Screen.DOCK->com.cosmod.launcherglass.util.SurfacePreviewKind.DOCK
                Screen.LIVE->com.cosmod.launcherglass.util.SurfacePreviewKind.LIVE
                Screen.VOLUME->com.cosmod.launcherglass.util.SurfacePreviewKind.VOLUME
                else->null
            }
            if(kind!=null){
                val hint=when(kind){
                    com.cosmod.launcherglass.util.SurfacePreviewKind.DOCK->R.string.ui_preview_dock_missing
                    com.cosmod.launcherglass.util.SurfacePreviewKind.LIVE->R.string.ui_preview_live_missing
                    com.cosmod.launcherglass.util.SurfacePreviewKind.VOLUME->R.string.ui_preview_volume_missing
                    else -> error("Banner previews are handled by the banner section")
                }
                val status=text("",12f,true).apply {setPadding(dp(4),dp(6),dp(4),0)}
                val sample by lazy {
                    when(kind){
                        SurfacePreviewKind.VOLUME->NativePreviewScenes.volume(this,darkUi)
                        SurfacePreviewKind.LIVE->NativePreviewScenes.liveAlert(this,darkUi)
                        else->null
                    }
                }
                fun display(){
                    val scene=previewMaterials[kind] ?: sample
                    preview.visibility=if(scene==null)View.GONE else View.VISIBLE
                    preview.setContentScene(scene,false)
                    status.visibility=if(scene==null)View.VISIBLE else View.GONE
                    status.setText(if(kind in previewMaterialPending)R.string.ui_preview_widget_loading else hint)
                    if(kind !in previewMaterialAttempted)loadMaterialPreview(kind)
                }
                previewMaterialRefresh=::display
                add(section,status)
                add(section,preferenceLink(getString(R.string.ui_preview_widget_refresh)){
                    previewMaterialAttempted.remove(kind);display()
                })
                display()
            }
        }
        if(screen==Screen.ICONS){
            add(section,preferenceLink(getString(R.string.ui_preview_apps)){showPreviewApps()})
            val status=text(getString(R.string.ui_preview_icons_missing),12f,true).apply {setPadding(dp(4),dp(6),dp(4),0)}
            fun sync(){
                status.visibility=if(previewIcons.isEmpty())View.VISIBLE else View.GONE
            }
            previewStatusRefresh=::sync
            sync();refreshers.add(::sync);add(section,status)
        }
        add(body,card(section),bottom=8)
        updateGlassPreview()
        refreshers.add(::updateGlassPreview)
    }

    private fun setPreviewPhoto(image: android.graphics.Bitmap?) {
        if (previewPhoto === image) return
        previewPhoto = image
        previewPhotoScenes.clear()
    }

    private fun loadWidgetPreview(kind:WidgetPreviewKind){
        if(previewExecutor.isShutdown || !previewWidgetPending.add(kind.ordinal))return
        previewWidgetAttempted.add(kind.ordinal)
        previewWidgetRefresh?.invoke()
        val context=applicationContext
        previewExecutor.execute {
            val scene=com.cosmod.launcherglass.util.NativeWidgetPreviews.read(context,kind)
            runOnUiThread {
                if(isDestroyed)return@runOnUiThread
                previewWidgetPending.remove(kind.ordinal)
                val actual=scene ?: NativePreviewScenes.widget(this,kind)
                if(actual!=null)previewWidgets[kind.ordinal]=actual
                previewWidgetRefresh?.invoke()
            }
        }
    }

    private fun loadFolderPreview(kind:FolderPreviewKind){
        if(previewExecutor.isShutdown || !previewFolderPending.add(kind.ordinal))return
        previewFolderAttempted.add(kind.ordinal)
        previewFolderRefresh?.invoke()
        val context=applicationContext
        previewExecutor.execute {
            val scene=com.cosmod.launcherglass.util.NativeWidgetPreviews.folder(context,kind)
            runOnUiThread {
                if(isDestroyed)return@runOnUiThread
                previewFolderPending.remove(kind.ordinal)
                if(scene!=null)previewFolders[kind.ordinal]=scene
                previewFolderRefresh?.invoke()
            }
        }
    }

    private fun loadMaterialPreview(kind:com.cosmod.launcherglass.util.SurfacePreviewKind){
        if(previewExecutor.isShutdown || !previewMaterialPending.add(kind))return
        previewMaterialAttempted.add(kind)
        previewMaterialRefresh?.invoke()
        val context=applicationContext
        previewExecutor.execute {
            val scene=com.cosmod.launcherglass.util.NativeWidgetPreviews.material(context,kind)
            runOnUiThread {
                if(isDestroyed)return@runOnUiThread
                previewMaterialPending.remove(kind)
                if(scene!=null)previewMaterials[kind]=scene
                previewMaterialRefresh?.invoke()
            }
        }
    }

    private var previewAppsOverlay:CouiPreviewApps?=null
    private fun showPreviewApps(){
        if(previewAppsOverlay!=null)return
        val chosen=prefs.getString("ui_preview_apps","").orEmpty().split("|").filter {it.isNotBlank()}
        val overlay=CouiPreviewApps(this,darkUi,primary,chosen,onDismiss={
            previewAppsOverlay?.let(page::removeView);previewAppsOverlay=null
        }){selection->
            prefs.edit().putString("ui_preview_apps",selection.joinToString("|")).apply()
            previewLastAttempt=0;previewSettingsEpoch++;glassPreview?.let(::loadPreviewScene)
        }
        previewAppsOverlay=overlay;page.addView(overlay,FrameLayout.LayoutParams(-1,-1))
    }

    private fun loadPreviewScene(preview: GlassPreview) {
        if(isDestroyed || isFinishing || previewExecutor.isShutdown)return
        val screen=glassPreviewScreen ?: return
        val wallpaperId = runCatching {
            android.app.WallpaperManager.getInstance(this).getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        if (previewWallpaperId != wallpaperId) {
            previewWallpaper = null
            previewWallpaperId = wallpaperId
            previewLastAttempt = 0L
        }
        preview.setScene(previewWallpaper, previewIcons)
        if (previewLoading) return
        val now = SystemClock.uptimeMillis()
        if (previewLastSurface==screen && previewLastAttempt != 0L && now - previewLastAttempt < 5000) return
        previewLastAttempt = now
        previewLastSurface=screen
        previewLoading = true
        val epoch=previewSettingsEpoch
        val needsIcons=screen==Screen.ICONS || screen==Screen.FOLDERS
        val needsPhoto = screen == Screen.PHOTO && previewPhoto == null
        val needsWallpaper = previewWallpaper == null
        val context = applicationContext
        val packages=prefs.getString("ui_preview_apps","").orEmpty().split('|').filter {it.isNotBlank()}
        previewExecutor.execute {
            val wallpaper = if (needsWallpaper) runCatching {
                com.cosmod.launcherglass.util.PreviewWallpaper.read(context)
            }.getOrNull() else null
            val icons = if(needsIcons){
                if(screen==Screen.FOLDERS)runCatching {
                    com.cosmod.launcherglass.util.PreviewIcons.readInstalled(context,packages,limit=9)
                }.getOrDefault(emptyList())
                else readPreviewIcons(context,packages)
            }else null
            val photo = if (needsPhoto) com.cosmod.launcherglass.util.PreviewPhotoStore.read(context) else null
            runOnUiThread {
                previewLoading = false
                if (isDestroyed || previewWallpaperId != wallpaperId || glassPreview !== preview || epoch != previewSettingsEpoch) {
                    wallpaper?.recycle()
                    photo?.recycle()
                    previewLastAttempt=0L
                    if (!isDestroyed) glassPreview?.let(::loadPreviewScene)
                    return@runOnUiThread
                }
                if (previewWallpaper == null) previewWallpaper = wallpaper else wallpaper?.recycle()
                if(icons!=null)previewIcons = icons
                if (photo != null) {
                    if (previewPhoto == null) setPreviewPhoto(photo) else photo.recycle()
                }
                preview.setScene(previewWallpaper, previewIcons)
                previewStatusRefresh?.invoke()
                previewWidgetRefresh?.invoke()
                previewFolderRefresh?.invoke()
            }
        }
    }

    private fun readPreviewIcons(context:Context,packages:List<String>) = runCatching {
        com.cosmod.launcherglass.util.PreviewIcons.read(context,packages)
    }.getOrNull().orEmpty().ifEmpty {
        runCatching {com.cosmod.launcherglass.util.PreviewIcons.readInstalled(context,packages)}.getOrNull().orEmpty()
    }

    private val liveWallpaperPreviews=mutableSetOf<GlassPreview>()
    private var liveWallpaperCapturePending=false
    private var previewSetWallpaperFlag=false

    private fun watchPreviewWallpaper(preview:GlassPreview,onFrame:(android.graphics.Bitmap)->Unit){
        val context=applicationContext
        val wallpaper=com.cosmod.launcherglass.util.PreviewWallpaper
        val visible=android.graphics.Rect()
        val flag=android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER
        val capture=object:Runnable{
            private fun schedule(delay:Long){if(preview.isAttachedToWindow)preview.postDelayed(this,delay)}
            override fun run(){
                if(isDestroyed || isFinishing || previewExecutor.isShutdown || !preview.isAttachedToWindow)return
                val container=preview.parent as? View ?: return
                val target=if(preview.visibility==View.VISIBLE)preview else container
                if(preview.windowVisibility!=View.VISIBLE || !target.isShown || !target.getGlobalVisibleRect(visible) ||
                    !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) || liveWallpaperCapturePending){
                    schedule(1500L)
                    return
                }
                if(!wallpaper.isLive(context)){schedule(4000L);return}
                if(liveWallpaperPreviews.add(preview) && liveWallpaperPreviews.size==1 && window.attributes.flags and flag==0){
                    window.addFlags(flag)
                    previewSetWallpaperFlag=true
                }
                liveWallpaperCapturePending=true
                previewExecutor.execute {
                    val frame=wallpaper.readLive(context)
                    runOnUiThread {
                        liveWallpaperCapturePending=false
                        if(preview.isAttachedToWindow && !isDestroyed && frame!=null)onFrame(frame)
                        else frame?.recycle()
                        schedule(if(frame!=null)1500L else 3500L)
                    }
                }
            }
        }
        preview.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){v.postDelayed(capture,1500L)}
            override fun onViewDetachedFromWindow(v:View){
                v.removeCallbacks(capture)
                if(liveWallpaperPreviews.remove(preview) && liveWallpaperPreviews.isEmpty() && previewSetWallpaperFlag){
                    window.clearFlags(flag)
                    previewSetWallpaperFlag=false
                }
            }
        })
        if(preview.isAttachedToWindow)preview.postDelayed(capture,1500L)
    }

    private fun updateGlassPreview(){
        val preview=glassPreview ?: return
        val screen=glassPreviewScreen ?: return
        val bundle=Bundle()
        for((key,value) in prefs.all)when(value){is Boolean->bundle.putBoolean(key,value);is Int->bundle.putInt(key,value)}
        previewDraft.forEach {key,value->bundle.putInt(key,value)}
        val bannerDark=renderGlassPreview(preview,screen,bundle)
        if(bannerDark!=null && previewBannerDark!=bannerDark){
            previewBannerDark=bannerDark
            previewBannerRefresh?.invoke()
        }
    }

    private fun renderGlassPreview(preview:GlassPreview,screen:Screen,bundle:Bundle):Boolean? {
        GlobalGlassSettings.apply(bundle)
        FeatureSections.apply(bundle)
        val wallpaperTone by lazy {IconPalette.wallpaperTone(this)}
        val iconDark=darkIcons()
        fun read(key:String):Int {
            if(bundle.containsKey(key))return bundle.getInt(key)
            val base=key.removePrefix("folder_")
            if(base!=key && base in FolderSettingsKeys.integers)return bundle.getInt(base,SettingsDefaults.integer(base))
            return SettingsDefaults.integer(key)
        }
        fun flag(key:String):Boolean {
            if(bundle.containsKey(key))return bundle.getBoolean(key)
            val base=key.removePrefix("folder_")
            if(base!=key && base in FolderSettingsKeys.booleans)return bundle.getBoolean(base,SettingsDefaults.boolean(base))
            return SettingsDefaults.boolean(key)
        }
        val enabled=flag(SettingsProvider.KEY_ENABLED) && bundle.getBoolean(FeatureSections.key(screen.name),true)
        val globalOpacity=GlobalGlassSettings.opacity(bundle)
        val material=when(screen){
            Screen.GLASS->GlassPreview.Material(enabled && flag(GlobalGlassSettings.ENABLED),
                read(GlobalGlassSettings.STYLE)==1,read(GlobalGlassSettings.BLUR),read(GlobalGlassSettings.OPACITY),
                read(GlobalGlassSettings.WIDTH),read(GlobalGlassSettings.DISTORTION),read(GlobalGlassSettings.DISPERSION),30)
            Screen.LOCKSCREEN->{
                val profile=LockscreenSettings.read(bundle,lockPreviewSurface)
                val round=if(profile.corner<0)30 else profile.corner
                val wide=lockPreviewSurface in setOf(LockscreenSettings.Surface.NOTIFICATIONS,LockscreenSettings.Surface.CAPSULE)
                GlassPreview.Material(enabled && profile.enabled,profile.liquid,profile.blur,profile.opacity,
                    profile.width,profile.distortion,profile.dispersion,round,banner=wide,
                    tiles=if(lockPreviewSurface==LockscreenSettings.Surface.PIN)3 else 1,
                    saturation=profile.saturation,brightness=profile.backdropBrightness/2)
            }
            Screen.ICONS,Screen.FOLDERS->{
                val folders=screen==Screen.FOLDERS
                val own=folders && flag(FolderSettingsKeys.OWN)
                fun key(base:String)=if(own)FolderSettingsKeys.key(base) else base
                val tone=flag(key(SettingsProvider.KEY_BLUR_TONE_ENABLED))
                GlassPreview.Material(
                    enabled && flag(if(folders)SettingsProvider.KEY_FOLDER_BLUR_ENABLED else SettingsProvider.KEY_BLUR_ENABLED),
                    read(key(SettingsProvider.KEY_BLUR_STYLE))==1,
                    read(key(SettingsProvider.KEY_BLUR_RADIUS)),
                    if(tone)read(key(SettingsProvider.KEY_BG_OPACITY))else 0,
                    read(key(SettingsProvider.KEY_LIQUID_WIDTH)),read(key(SettingsProvider.KEY_LIQUID_DISTORTION)),
                    read(if(own)GlassDispersionSettings.FOLDERS else GlassDispersionSettings.ICONS),28,
                    if(tone)read(key(SettingsProvider.KEY_BLUR_SATURATION))else 100,
                    if(tone)read(key(SettingsProvider.KEY_BLUR_VIBRANCY))else 0,
                    if(tone)read(key(SettingsProvider.KEY_BLUR_LIGHTENING))else 50,
                    tiles=if(folders)1 else 3)
                    .copy(plateColor=if(folders) (if(iconDark)0xff181818.toInt() else 0xffeeeeee.toInt()) else null,
                        iconPalette = if (folders || !enabled) null else {
                        val keys = SettingsProvider.colorKeys(iconDark)
                        val clear = read(SettingsProvider.KEY_ICON_APPEARANCE) == 0
                        val tone = wallpaperTone
                            ?.takeIf { !clear && flag(com.cosmod.launcherglass.util.IconPalette.FOLLOW_WALLPAPER) }
                        com.cosmod.launcherglass.util.IconPalette.from(read(SettingsProvider.KEY_COLOR_MODE),
                            tone?.hue ?: read(keys[0]), tone?.saturationAt(read(keys[1])) ?: read(keys[1]),
                            read(if (clear) SettingsProvider.KEY_CLEAR_BRIGHTNESS else keys[2]), iconDark, clear)
                            .withLogo(flag("icon_logo_tint"),read("icon_logo_hue"),read("icon_logo_saturation"),read("icon_logo_brightness"))
                    })
            }
            Screen.DOCK->GlassPreview.Material(enabled && read("dock_style")>0,read("dock_style")==2,
                read("dock_blur"),read("dock_opacity"),read("dock_liquid_width"),read("dock_liquid_distortion"),
                read(GlassDispersionSettings.DOCK),read("dock_corner"),
                backgroundFilter=android.graphics.ColorMatrixColorFilter(com.cosmod.launcherglass.util.DockToneMatrix.values(
                    read("dock_backdrop_saturation"),read("dock_backdrop_darkness"))))
            Screen.PHOTO->GlassPreview.Material(enabled && flag(SettingsProvider.KEY_PHOTO_WIDGET_BLUR),
                read(SettingsProvider.KEY_PHOTO_WIDGET_MATERIAL)==1,read("photo_widget_blur_radius"),
                read(SettingsProvider.KEY_PHOTO_WIDGET_OPACITY),read(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH),
                read(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION),read(GlassDispersionSettings.PHOTOS),
                read(SettingsProvider.KEY_PHOTO_WIDGET_CORNER))
            Screen.VOLUME->GlassPreview.Material(enabled && flag("volume_glass_enabled"),read("volume_glass_style")==1,
                read("volume_glass_blur"),read("volume_glass_opacity"),read("volume_glass_width"),read("volume_glass_distortion"),
                read("volume_glass_dispersion"),50,brightness=50+read("volume_glass_brightness")/2,vertical=true,
                rimStrength=read("volume_glass_rim"),volumeHeight=read("volume_glass_height").coerceIn(60,160))
            Screen.LIVE,Screen.BANNERS->{
                val prefix=if(screen==Screen.LIVE)"island_glass_" else "banner_glass_"
                GlassPreview.Material(enabled && flag(prefix+"enabled"),true,read(prefix+"blur"),
                    read(prefix+"opacity"),read(prefix+"width"),read(prefix+"distortion"),
                    read(if(screen==Screen.LIVE)GlassDispersionSettings.ALERTS else GlassDispersionSettings.BANNERS),
                    read(prefix+"corner"))
            }
            else->return null
        }
        val tintScope=when(screen){Screen.FOLDERS->1;Screen.DOCK->3;Screen.LIVE->5;Screen.VOLUME->13;Screen.LOCKSCREEN->lockPreviewSurface.edgeSurface;else->-1}
        val tintKey=SurfaceTintSettings.key(tintScope,"enabled")
        val tinted=if(tintScope>=0 && flag(tintKey)){
            fun key(field:String)=SurfaceTintSettings.key(tintScope,field)
            val tintTone=wallpaperTone?.takeIf {flag(key("wallpaper"))}
            material.copy(plateColor=com.cosmod.launcherglass.xposed.SurfaceTints.color(
                tintTone?.hue ?: read(key("hue")),tintTone?.saturationAt(read(key("saturation"))) ?: read(key("saturation")),
                read(key("brightness")),read(key("lightness"))),opacity=read(key("opacity")))
        }else if(screen==Screen.PHOTO && flag(SettingsProvider.KEY_PHOTO_WIDGET_TINT)){
            material.copy(plateColor=com.cosmod.launcherglass.xposed.SurfaceTints.color(read(WidgetSettings.HUE),
                read(WidgetSettings.TINT_SATURATION),read("photo_widget_tint_brightness"),read(WidgetSettings.LIGHTNESS)),
                opacity=100-read(WidgetSettings.TINT_TRANSPARENCY))
        }else if(screen==Screen.BANNERS && flag("banner_plate_enabled")){
            material.copy(plateColor=com.cosmod.launcherglass.xposed.SurfaceTints.color(read("banner_plate_hue"),
                read("banner_plate_saturation"),read("banner_plate_brightness"),read("banner_plate_lightness")),opacity=read("banner_plate_opacity"))
        }else material
        val finished=when(screen){
            Screen.ICONS->{
                val palette=tinted.iconPalette
                tinted.copy(backgroundFilter=palette?.let {com.cosmod.launcherglass.util.IconBackdropFilter.create(tinted.vibrancy,it)},
                    iconPalette=palette?.copy(background=com.cosmod.launcherglass.util.PlateTone.iconSaturation(palette.background,tinted.saturation,null)))
            }
            Screen.FOLDERS->tinted.copy(backgroundFilter=if(tinted.saturation==100)null else android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix().apply {setSaturation(tinted.saturation/100f)}))
            Screen.PHOTO->if(flag(SettingsProvider.KEY_PHOTO_WIDGET_TINT))tinted.copy(opacity=0,plateColor=null,
                backgroundFilter=com.cosmod.launcherglass.util.widgetTintFilter(read(WidgetSettings.HUE),read(WidgetSettings.TINT_SATURATION),
                    read(WidgetSettings.LIGHTNESS),read(WidgetSettings.TINT_TRANSPARENCY),read("photo_widget_tint_brightness")))else tinted
            else->tinted
        }
        var result=when(screen){
            Screen.PHOTO->finished.copy(opacity=0,contentOpacity=read(SettingsProvider.KEY_PHOTO_WIDGET_OPACITY),
                fade=read(SettingsProvider.KEY_PHOTO_WIDGET_GLOW),fadeHeight=read(WidgetSettings.FADE_HEIGHT),
                fadeSmoothness=read(WidgetSettings.FADE_SMOOTHNESS),shade=read(SettingsProvider.KEY_PHOTO_WIDGET_SHADE),rimStrength=read(SettingsProvider.KEY_PHOTO_WIDGET_RIM))
            Screen.ICONS->finished.copy(iconScale=read("launcher_icon_size"))
            Screen.BANNERS->{
                val tint=if(flag("banner_plate_enabled"))androidx.core.graphics.ColorUtils.setAlphaComponent(
                    com.cosmod.launcherglass.xposed.SurfaceTints.color(read("banner_plate_hue"),read("banner_plate_saturation"),read("banner_plate_brightness"),read("banner_plate_lightness")),
                    read("banner_plate_opacity")*255/100)else Color.TRANSPARENT
                val dark=read("banner_top_darkness")>=45 || if(Color.alpha(tint)>=164)androidx.core.graphics.ColorUtils.calculateLuminance(tint)<.32 else darkUi
                finished.copy(banner=true,opacity=read("banner_glass_opacity"),plateColor=if(dark)0xff121214.toInt() else 0xfff7f7f9.toInt(),
                    plateTint=tint,plateFade=read("banner_plate_bottom_fade"),topDarkness=read("banner_top_darkness"),
                    shadowStrength=read("banner_shadow_strength"),shadowSize=read("banner_shadow_size"),rimStrength=read("banner_glass_rim"),
                    rimWidth=read("banner_rim_width"),rimSoftness=read("banner_rim_softness"))
            }
            else->finished
        }
        if (screen == Screen.FOLDERS) {
            preview.setLargeFolderMaterial(if (flag("large_folder_glass_own")) finished.copy(
                liquid = read("large_folder_glass_style") == 1,
                blur = read(GlassEngineSettings.key(GlassEngineSettings.LARGE_FOLDERS,GlassEngineSettings.Parameter.BLUR_RADIUS)),
                width = read("large_folder_glass_width"),
                distortion = read("large_folder_glass_strength"),
                opacity = read("large_folder_glass_opacity"),
            ) else null)
        }
        if(screen==Screen.PHOTO){
            val profile=com.cosmod.launcherglass.xposed.WidgetMaterialSettings.read(bundle,wallpaperTone)
            val active=enabled && profile.enabled
            result=result.copy(opacity=globalOpacity,backgroundFilter=if(active)profile.tintFilter else null,contentOpacity=if(active)profile.opacity else 100,
                fade=if(active)profile.fade else 0,elementsFollowBackground=active && bundle.getBoolean("widget_elements_follow_background"),
                elementFilter=if(active && bundle.getBoolean("widget_logo_tint") && !bundle.getBoolean("widget_elements_follow_background"))android.graphics.ColorMatrixColorFilter(
                    com.cosmod.launcherglass.util.WidgetElementTint.matrix(com.cosmod.launcherglass.xposed.SurfaceTints.color(
                        read("widget_logo_hue"),read("widget_logo_saturation"),read("widget_logo_brightness"),0),read("widget_logo_transparency")))else null)
        }
        val globalOptics=if(screen==Screen.GLASS)GlassEngineSettings.Optics(
            blur=GlassEngineSettings.Blur(opacity=read(GlobalGlassSettings.BLUR_OPACITY)),
            lensHeight=read(GlobalGlassSettings.LENS_HEIGHT)) else null
        preview.configure(bundle,when(screen){Screen.ICONS->0;Screen.FOLDERS->1;Screen.PHOTO->2;Screen.DOCK->3;Screen.BANNERS->4;Screen.LIVE->5;Screen.VOLUME->13;Screen.LOCKSCREEN->lockPreviewSurface.edgeSurface;else->-1},globalOptics)
        preview.show(result)
        return if(screen==Screen.BANNERS)result.plateColor==0xff121214.toInt() else null
    }

    private fun slider(label:String,key:String,min:Int,max:Int,suffix:String,hint:String?=null,
        formatter:((Int)->String)?=null,inverseOpacity:Boolean=false,onPreview:((Int)->Unit)?=null):View {
        featureKeys.add(key)
        val inverse=key==SettingsProvider.KEY_PHOTO_WIDGET_OPACITY || key=="banner_plate_opacity"
        fun reverse(value:Int)=when {
            inverseOpacity && value>=0->max-value
            inverse->min+max-value
            else->value
        }
        fun displayed()=reverse(number(key))
        fun stored(value:Int)=reverse(value)
        fun labelFor(value:Int)=formatter?.invoke(value) ?: value.toString()+suffix
        val range=max-min
        val initial=displayed().coerceIn(min,max)-min
        val control=CouiSlider(this,darkUi,primary,label,range,initial,hint) { labelFor(it+min) }
        val hueTrack=key==WidgetSettings.HUE || key=="banner_plate_hue"
        val lightnessTrack=key.endsWith("lightness")
        fun updatePalette(){
            control.hueTrack = hueTrack
            if(lightnessTrack){
                val prefix=key.removeSuffix("lightness")
                val color=when {
                    key==WidgetSettings.LIGHTNESS -> Color.HSVToColor(floatArrayOf(number(WidgetSettings.HUE).toFloat(),number(WidgetSettings.TINT_SATURATION)/100f,number("photo_widget_tint_brightness")/100f))
                    key.startsWith("surface_")->Color.HSVToColor(floatArrayOf(number(prefix+"hue").toFloat(),number(prefix+"saturation")/100f,number(prefix+"brightness")/100f))
                    else -> Color.HSVToColor(floatArrayOf(number("banner_plate_hue").toFloat(),number("banner_plate_saturation")/100f,number("banner_plate_brightness")/100f))
                }
                control.brightnessColor = color
            }
        }
        updatePalette()
        if(lightnessTrack)refreshers.add(::updatePalette)
        control.onTouchFinished={
            previewDraft.remove(key)
            save(key,stored(it+min))
            updateGlassPreview()
        }
        control.onChange={progress->
            onPreview?.invoke(stored(progress+min))
            if(glassPreview!=null || shadePreview!=null){
                previewDraft[key]=stored(progress+min)
                updateGlassPreview()
                shadePreview?.refresh()
            }
        }
        refreshers.add {
            if(!control.adjusting)control.progress=displayed().coerceIn(min,max)-min
        }
        return control
    }
    private fun effectGroup(parent: LinearLayout, label: String, switchKey: String, sliderLabel: String, sliderKey: String,
        max: Int = 100, allowed: () -> Boolean = { design() }, hint: String? = null, min: Int = 0, suffix: String = "%",
        expanded:Boolean=false,extra: ((LinearLayout) -> Unit)? = null) {
        val group = column()
        val details=column()
        val accordion=SettingsAccordion(details,expanded)
        divider(details)
        val control = slider(sliderLabel,sliderKey,min,max,suffix,hint)
        gate(control) { allowed() && bool(switchKey) }
        add(details,control)
        extra?.invoke(details)
        add(group, switchRow(label,switchKey,expanded=expanded) { open ->
            accordion.setOpen(open,android.animation.ValueAnimator.areAnimatorsEnabled())
        })
        add(group,accordion)
        add(parent,card(group,true),top=8,bottom=4)
    }

    private fun glyphOutlineGroup(parent: LinearLayout) {
        val group=column()
        val details=column()
        val accordion=SettingsAccordion(details,false)
        gate(details) { design() && bool(SettingsProvider.KEY_GLYPH_STROKE) }
        divider(details)
        add(details,slider(getString(R.string.ui_logo_outline_opacity),SettingsProvider.KEY_GLYPH_STROKE_OPACITY,0,100,"%"))
        add(details,slider(getString(R.string.ui_logo_outline_width),SettingsProvider.KEY_GLYPH_STROKE_WIDTH,0,100,"%"))
        add(details,slider(getString(R.string.ui_logo_outline_brightness),SettingsProvider.KEY_GLYPH_STROKE_BRIGHTNESS,0,100,"%"))
        add(group,switchRow(getString(R.string.ui_logo_outline),SettingsProvider.KEY_GLYPH_STROKE,expanded=false) {
            accordion.setOpen(it,android.animation.ValueAnimator.areAnimatorsEnabled())
        })
        add(group,accordion)
        add(parent,card(group,true),top=8,bottom=4)
    }

    private fun blurToneGroup(parent:LinearLayout) {
        val group=column()
        val title=getString(R.string.ui_blur_tone)
        val details=column()
        val accordion=SettingsAccordion(details,false)
        add(group,switchRow(title,SettingsProvider.KEY_BLUR_TONE_ENABLED,expanded=false) {
            accordion.setOpen(it,android.animation.ValueAnimator.areAnimatorsEnabled())
        })
        gate(details) {blur() && bool(SettingsProvider.KEY_BLUR_TONE_ENABLED)}
        for((label,sliderKey) in listOf(
            R.string.ui_plate_opacity to SettingsProvider.KEY_BG_OPACITY,
            R.string.ui_blur_saturation to SettingsProvider.KEY_BLUR_SATURATION,
            R.string.ui_blur_vibrancy to SettingsProvider.KEY_BLUR_VIBRANCY,
            R.string.ui_blur_brightness to SettingsProvider.KEY_BLUR_LIGHTENING)) {
            divider(details)
            val hint=getString(when(sliderKey) {
                SettingsProvider.KEY_BG_OPACITY -> R.string.ui_plate_opacity_hint
                SettingsProvider.KEY_BLUR_VIBRANCY -> R.string.ui_blur_vibrancy_hint
                SettingsProvider.KEY_BLUR_SATURATION -> R.string.ui_blur_saturation_hint
                else -> R.string.ui_blur_lightening_hint
            })
            val control=slider(getString(label),sliderKey,0,if(sliderKey==SettingsProvider.KEY_BLUR_SATURATION || sliderKey==SettingsProvider.KEY_BLUR_VIBRANCY)200 else 100,"%",hint)
            add(details,control)
        }
        add(group,accordion)
        add(parent,card(group,true),top=8,bottom=4)
    }

    private fun colorDepthStages(parent:LinearLayout){
        modeChoice(parent,R.string.ui_color_depth_stage,SettingsProvider.KEY_COLOR_DEPTH_STAGE,
            listOf(R.string.ui_depth_before_blur,R.string.ui_depth_after_blur,R.string.ui_depth_after_refraction,
                R.string.ui_depth_finished).map(::getString)){
            bool(SettingsProvider.KEY_DEVELOPER_MODE) && bool(SettingsProvider.KEY_COLOR_DEPTH_ENABLED)
        }
        add(parent,text(getString(R.string.ui_material_order),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
    }

    private fun buildBa31kMotion(body: LinearLayout) {
        val settings = com.cosmod.launcherglass.util.Ba31kAnimationSettings
        val active = { master() && FeatureSections.enabled(prefs, Screen.MOTION.name) && bool(settings.ENABLED) }
        fun group(title: Int, content: LinearLayout.() -> Unit) {
            accordionCard(body,title,column().apply(content))
        }
        fun LinearLayout.toggle(label: Int, key: String, available: () -> Boolean = active) {
            if(childCount > 0)divider(this)
            add(this,switchRow(getString(label),key).also {gate(it,available)})
        }
        fun LinearLayout.amount(label: Int, key: String, available: () -> Boolean = active) {
            if(childCount > 0)divider(this)
            val control=slider(getString(label),key,settings.minimum(key),settings.maximum(key),"%")
            gate(control,available)
            add(this,control)
        }
        val appMotion = { active() && (bool(settings.OPEN) || bool(settings.CLOSE)) }
        val shade = { active() && bool(settings.SHADE) }
        val masterSwitch=switchRow(getString(R.string.ui_ba31k_title),settings.ENABLED,
            getString(R.string.ui_ba31k_summary))
        gate(masterSwitch,::master)
        add(body,card(masterSwitch),bottom=8)
        group(R.string.ui_ba31k_transitions) {
            toggle(R.string.app_tilt_open,settings.OPEN)
            amount(R.string.ui_genie_open_speed,settings.OPEN_SPEED){active() && bool(settings.OPEN)}
            toggle(R.string.app_tilt_close,settings.CLOSE)
            amount(R.string.ui_genie_close_speed,settings.CLOSE_SPEED){active() && bool(settings.CLOSE)}
            amount(R.string.ui_ba31k_bounce,settings.BOUNCE){active() && bool(settings.CLOSE)}
        }
        group(R.string.ui_ba31k_effects) {
            toggle(R.string.ui_ba31k_genie,settings.GENIE,appMotion)
            amount(R.string.ui_ios_deformation_strength,settings.GENIE_STRENGTH){appMotion() && bool(settings.GENIE)}
            amount(R.string.ui_ios_tilt_strength,settings.TILT,appMotion)
            toggle(R.string.ui_genie_trajectory,settings.TRAJECTORY,appMotion)
            toggle(R.string.ui_genie_droplet,settings.DROPLET,appMotion)
            amount(R.string.ui_ba31k_droplet_strength,settings.DROPLET_STRENGTH){appMotion() && bool(settings.DROPLET)}
            amount(R.string.ui_ba31k_speed,settings.SPEED)
        }
        group(R.string.ui_ba31k_spring) {
            amount(R.string.ui_ba31k_stiffness,settings.STIFFNESS,appMotion)
            amount(R.string.ui_ba31k_damping,settings.DAMPING,appMotion)
            amount(R.string.ui_ba31k_velocity,settings.INITIAL_VELOCITY,appMotion)
            amount(R.string.ui_ba31k_duration,settings.DURATION,appMotion)
        }
        group(R.string.ui_ba31k_shade) {
            toggle(R.string.ui_ba31k_fly,settings.SHADE)
            amount(R.string.ui_ba31k_spread,settings.SPREAD,shade)
            toggle(R.string.ui_ba31k_clear_shade,settings.CLEAR_SHADE)
        }
        add(body,card(preferenceLink(getString(R.string.ui_reset_section)){anchor->
            choicePopup(anchor,listOf(0 to R.string.ui_cancel,1 to R.string.ui_reset_section_confirm),-1){
                if(it==1)applySavedSettings(settings.booleans.keys+settings.integers.keys,emptyMap())
            }
        }),top=8,bottom=16)
    }

    private fun buildPerformance(body: LinearLayout) {
        add(body,card(text(getString(R.string.ui_performance_warning),14f,true).apply {
            setPadding(dp(15),dp(12),dp(15),dp(12))
        }),bottom=10)
        modeChoice(body,R.string.ui_performance,"performance_profile",listOf(R.string.ui_profile_auto,R.string.ui_profile_quality,
            R.string.ui_profile_eco,R.string.ui_profile_balanced).map(::getString)){true}
        add(body,card(text(getString(R.string.ui_performance_hint),13f,true).apply {
            setPadding(dp(15),dp(10),dp(15),dp(10))
        }),bottom=12)
        val budgets=column()
        add(budgets,switchRow(getString(R.string.ui_transition_live_glass),"performance_live_transition_glass",
            getString(R.string.ui_transition_live_glass_hint)))
        add(budgets,switchRow(getString(R.string.ui_ltpo_idle),"performance_ltpo_idle",getString(R.string.ui_ltpo_idle_hint)))
        add(budgets,switchRow(getString(R.string.ui_165hz_pacing),"performance_high_refresh_pacing",
            getString(R.string.ui_165hz_pacing_hint)))
        add(budgets,switchRow(getString(R.string.ui_performance_drawer),"performance_light_drawer",
            getString(R.string.ui_performance_drawer_hint)))
        add(body,card(budgets),bottom=20)
    }

    private fun nightMode(theme:Int)=when(theme){
        1->androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        2->androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        else->androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun updateSystemBars(){
        window.statusBarColor=Color.TRANSPARENT
        window.navigationBarColor=Color.TRANSPARENT
        window.isStatusBarContrastEnforced=false
        window.isNavigationBarContrastEnforced=false
        WindowCompat.getInsetsController(window,root).apply {
            isAppearanceLightStatusBars=!darkUi
            isAppearanceLightNavigationBars=!darkUi
        }
    }

    override fun onConfigurationChanged(config:android.content.res.Configuration){
        super.onConfigurationChanged(config)
        if(::page.isInitialized && !changingTheme){
            rebuildThemedPage(currentScroll?.scrollY ?: 0)
            updateSystemBars()
        }
    }

    private fun rebuildThemedPage(scrollY:Int){
        root.setBackgroundColor(background)
        backDestination?.let(page::removeView)
        backDestination=null
        backHeaderInset=null;backBottomInset=null
        showPageWithDestination()
        currentScroll?.doOnLayout {it.scrollTo(0,scrollY)}
    }

    private fun selectTheme(next:Int){
        if(changingTheme || next==prefs.getInt("ui_theme",0).coerceIn(0,2))return
        val decor=window.decorView as ViewGroup
        val animate=android.animation.ValueAnimator.areAnimatorsEnabled() && decor.width>0 && decor.height>0
        changingTheme=true
        fun applyTheme(bitmap:android.graphics.Bitmap?){
            if(isFinishing || isDestroyed){bitmap?.recycle();return}
            val previous=darkUi
            val scrollY=currentScroll?.scrollY ?: 0
            val overlay=bitmap?.let {frame->
                ImageView(this).apply {
                    scaleType=ImageView.ScaleType.FIT_XY
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    isClickable=true
                    setImageBitmap(frame)
                    decor.addView(this,ViewGroup.LayoutParams(-1,-1))
                }
            }
            prefs.edit().putInt("ui_theme",next).apply()
            delegate.localNightMode=nightMode(next)
            rebuildThemedPage(scrollY)
            if(overlay==null || previous==darkUi){
                if(overlay!=null)decor.removeView(overlay)
                updateSystemBars()
                changingTheme=false
                return
            }
            // Keep the old frame on top until the new colors and scroll position
            // are laid out in the same window.
            decor.doOnPreDraw {
                overlay.postOnAnimation {
                    var barsChanged=false
                    overlay.animate().alpha(0f).setDuration(460L)
                        .setInterpolator(android.view.animation.PathInterpolator(.42f,0f,.25f,1f))
                        .setUpdateListener {
                            if(!barsChanged && overlay.alpha<.5f){barsChanged=true;updateSystemBars()}
                        }
                        .withEndAction {
                            decor.removeView(overlay)
                            updateSystemBars()
                            changingTheme=false
                        }.start()
                }
            }
        }
        if(!animate){applyTheme(null);return}
        val scale=minOf(1f,1080f/decor.width,2200f/decor.height)
        val frame=android.graphics.Bitmap.createBitmap((decor.width*scale).roundToInt().coerceAtLeast(1),
            (decor.height*scale).roundToInt().coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(window,frame,{result->
                if(result==android.view.PixelCopy.SUCCESS)applyTheme(frame)
                else {frame.recycle();applyTheme(null)}
            },android.os.Handler(mainLooper))
        }catch(_:IllegalArgumentException){frame.recycle();applyTheme(null)}
    }

    private fun buildTools(body:LinearLayout){
        val tools=column()
        add(body,text(getString(R.string.ui_settings_display),13f,true).apply {setPadding(dp(16),dp(9),0,dp(7))})
        val theme=prefs.getInt("ui_theme",0).coerceIn(0,2)
        val previews=row().apply {setPadding(dp(8),dp(12),dp(8),dp(8))}
        for((value,label) in listOf(1 to R.string.ui_theme_light,2 to R.string.ui_theme_dark)){
            previews.addView(ThemePreview(this,value==2,theme==value,onSurface,secondaryText,primary,getString(label)).apply {
                setOnClickListener {haptic(it);selectTheme(value)}
                settingsPress()
            },LinearLayout.LayoutParams(0,dp(204),1f))
        }
        add(tools,previews)
        divider(tools)
        add(tools,preferenceLink(getString(R.string.ui_theme_system),if(theme==0)getString(R.string.ui_theme) else null){selectTheme(0)})
        divider(tools)
        val styles = listOf(CouiAppearance.ORIGINAL.value to R.string.ui_coui_original,
            CouiAppearance.MODERN.value to R.string.ui_coui_modern)
        val currentStyle = CouiAppearance.read(this).value
        add(tools,optionRow(getString(R.string.ui_coui_appearance),getString(styles.first { it.first == currentStyle }.second)).apply {
            setOnClickListener {
                haptic(it)
                choicePopup(it,styles,currentStyle) { style ->
                    if (style != currentStyle) {
                        val scrollY = currentScroll?.scrollY ?: 0
                        prefs.edit().putInt(CouiAppearance.KEY,style).apply()
                        rootNavigation?.let(page::removeView)
                        rootNavigation = null
                        rebuildThemedPage(scrollY)
                    }
                }
            }
        })
        divider(tools)
        add(tools,switchRow(getString(R.string.ui_coloros_effects),"coloros_ui_effects",getString(R.string.ui_coloros_effects_hint)))
        val backgrounds=listOf(0 to R.string.ui_background_off,1 to R.string.ui_background_classic,2 to R.string.ui_background_flowing)
        val selected=number("coloros_feature_background")
        val appearance=optionRow(getString(R.string.ui_feature_background),getString(backgrounds.first {it.first==selected}.second))
        appearance.setOnClickListener {
            haptic(it)
            choicePopup(it,backgrounds,selected){style->save("coloros_feature_background",style)}
        }
        add(tools,appearance)
        divider(tools)
        add(tools,optionRow(getString(R.string.ui_language),if(UiLanguage.tag(this)=="ru")"Русский" else "English").apply {
            setOnClickListener {haptic(it);showLanguageDialog(it)}
        })
        add(body,card(tools),bottom=12)
        add(body,text(getString(R.string.ui_catalog_tools),13f,true).apply {setPadding(dp(16),dp(9),0,dp(7))})
        val bootRestart=column()
        add(bootRestart,switchRow(getString(R.string.ui_boot_restart),SettingsProvider.KEY_BOOT_RESTART))
        add(bootRestart,text(getString(R.string.ui_boot_restart_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(12))})
        divider(bootRestart)
        val bootToast=switchRow(getString(R.string.ui_boot_restart_toast),SettingsProvider.KEY_BOOT_RESTART_TOAST)
        gate(bootToast){bool(SettingsProvider.KEY_BOOT_RESTART)}
        add(bootRestart,bootToast)
        add(body,card(bootRestart),bottom=12)
        val developer=column()
        add(developer,switchRow(getString(R.string.ui_developer_mode),SettingsProvider.KEY_DEVELOPER_MODE))
        val developerOptions=column()
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_gpu_effects),SettingsProvider.KEY_GPU_EFFECTS))
        add(developerOptions,text(getString(R.string.ui_gpu_effects_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_color_depth),SettingsProvider.KEY_COLOR_DEPTH_ENABLED))
        add(developerOptions,text(getString(R.string.ui_color_depth_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        val colorDepth=slider(getString(R.string.ui_color_depth_bits),SettingsProvider.KEY_COLOR_DEPTH_BITS,8,24," bit")
        gate(colorDepth){bool(SettingsProvider.KEY_DEVELOPER_MODE) && bool(SettingsProvider.KEY_COLOR_DEPTH_ENABLED)}
        add(developerOptions,colorDepth)
        colorDepthStages(developerOptions)
        divider(developerOptions)
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_disable_edge_optimization),SettingsProvider.KEY_DISABLE_EDGE_OPTIMIZATION))
        add(developerOptions,text(getString(R.string.ui_disable_edge_optimization_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        add(developerOptions,switchRow(getString(R.string.ui_optimize_mini_icons),SettingsProvider.KEY_OPTIMIZE_MINI_ICONS))
        add(developerOptions,text(getString(R.string.ui_optimize_mini_icons_hint),12f,true).apply {setPadding(dp(20),0,dp(20),dp(16))})
        divider(developerOptions)
        developerOptions.visibility=if(bool(SettingsProvider.KEY_DEVELOPER_MODE))View.VISIBLE else View.GONE
        refreshers.add {developerOptions.visibility=if(bool(SettingsProvider.KEY_DEVELOPER_MODE))View.VISIBLE else View.GONE}
        gate(developerOptions){bool(SettingsProvider.KEY_DEVELOPER_MODE)}
        add(developer,developerOptions)
        add(body,card(developer),bottom=12)
    }

    private fun buildSupport(body:LinearLayout){
        val actions=column()
        val logs=button().apply {
            text=getString(R.string.ui_export_diagnostics);minimumHeight=dp(48)
            background=glassRipple(raised,20)
            setOnClickListener { button ->
                haptic(button);button.isEnabled=false
                executor.execute {
                    try {
                        val file=DiagnosticsArchive.create(this@MainActivity,prefs)
                        val uri=androidx.core.content.FileProvider.getUriForFile(this@MainActivity,"${BuildConfig.APPLICATION_ID}.logs",file)
                        runOnUiThread {
                            button.isEnabled=true
                            if(!isFinishing && !isDestroyed) {
                                val send=Intent(Intent.ACTION_SEND).apply {
                                    type="application/zip"
                                    putExtra(Intent.EXTRA_STREAM,uri)
                                    putExtra(Intent.EXTRA_SUBJECT,"${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} logs")
                                    clipData=ClipData.newUri(contentResolver,file.name,uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(send,getString(R.string.ui_share_logs)))
                            }
                        }
                    } catch(t:Exception) {
                        runOnUiThread {
                            button.isEnabled=true
                            if(!isFinishing)Toast.makeText(this@MainActivity,getString(R.string.ui_log_error,t.message ?: ""),Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        add(actions,logs)
        val support=button().apply {
            text=getString(R.string.ui_support_project);minimumHeight=dp(48)
            background=glassRipple(raised,20)
            setOnClickListener {
                haptic(it)
                try {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://pay.cloudtips.ru/p/e6230fb4")))}
                catch(_:Exception){Toast.makeText(context,getString(R.string.ui_link_error),Toast.LENGTH_SHORT).show()}
            }
        }
        add(actions,support,top=8)
        actions.setPadding(dp(12),dp(10),dp(12),dp(10))
        add(body,card(actions))
    }
    private fun updateFluxWarning() {
        fluxWarning?.visibility=if(::prefs.isInitialized && prefs.getInt(SettingsProvider.KEY_NATIVE_FLUX_SUPPORT,0)==2)View.VISIBLE else View.GONE
    }
    private fun updateStatus() {
        updateFluxWarning()
        frameworkWarning?.visibility=if(isVectorFramework())View.VISIBLE else View.GONE
        val lspActive=scopeState?.enabled ?: isModuleActive()
        val missing=lspActive && scopeState?.launcherSelected==false
        fun loaded(role:String)=com.cosmod.launcherglass.util.RuntimeBuildState.loaded(
            prefs.getString("runtime_${role}_version",null),prefs.getInt("runtime_${role}_build",0),BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE)
        val loadedLauncher=loaded("launcher")
        val loadedSystemUi=loaded("systemui")
        val pending=ArrayList<String>()
        if(lspActive && master() && !loadedLauncher)pending.add(getString(R.string.ui_runtime_launcher))
        if(lspActive && master() && (bool("island_glass_enabled") || bool("banner_glass_enabled") || bool("volume_glass_enabled")) && !loadedSystemUi)pending.add("System UI")
        val unverified=lspActive && scopeState==null && !loadedLauncher
        val active=lspActive && !missing && master()
        rootStatus?.text=when {
            !rootChecked->getString(R.string.ui_status_checking)
            rootVersion!=null->rootVersion
            scopeState!=null->getString(R.string.ui_root_available)
            else->getString(R.string.ui_device_unknown)
        }
        lsposedText?.text=when {
            missing->getString(R.string.ui_scope_missing)
            pending.isNotEmpty()->getString(R.string.ui_status_restart_pending,pending.joinToString(", "))
            unverified->getString(R.string.ui_scope_unknown)
            active->getString(R.string.ui_cherrypick_enabled)
            else->getString(R.string.ui_cherrypick_disabled)
        }
        val heroColor=when {
            !master()->0xffbf383e.toInt()
            active && pending.isEmpty() && !unverified->0xff147df5.toInt()
            !lspActive && rootChecked->0xffd9443f.toInt()
            else->0xff64758a.toInt()
        }
        hero?.let {
            if(it.tag!=heroColor){
                it.tag=heroColor
                it.background=panelShape(heroColor,20)
            }
        }
    }
    private fun restart(view: View) {
        if(restartBusy)return
        restartBusy=true;view.isEnabled=false
        Thread({
            val success=SuUtil.restartLauncher(this@MainActivity)
            runOnUiThread { restartBusy=false;view.isEnabled=true
                Toast.makeText(this,if(success)getString(R.string.ui_launcher_restarted) else getString(R.string.ui_launcher_restart_error),Toast.LENGTH_SHORT).show()
            }
        },"CherryPickRestart").start()
    }

    private fun closeRestartConfirmation(immediate: Boolean = false) {
        restartDialogVisible.value = false
        if (immediate) {
            restartOverlay?.let(page::removeView)
            restartOverlay = null
        }
    }

    private fun confirmLauncherRestart(view:View)=
        confirmRestart(R.string.ui_restart_launcher,R.string.ui_restart_launcher_confirm){restart(view)}

    private fun restartSystemUi(view:View)=
        confirmRestart(R.string.ui_restart_systemui,R.string.ui_restart_systemui_confirm){sendSystemUiRestart(view)}

    private fun confirmSoftRestart(view:View)=
        confirmRestart(R.string.ui_soft_restart,R.string.ui_soft_restart_confirm){
            view.isEnabled=false
            executor.execute {
                val success=SuUtil.execute("am restart")
                runOnUiThread {
                    view.isEnabled=true
                    if(!success && !isFinishing)Toast.makeText(this,R.string.ui_soft_restart_error,Toast.LENGTH_LONG).show()
                }
            }
        }

    private fun confirmRestart(titleId: Int, messageId: Int, onConfirm: () -> Unit) {
        if (restartOverlay != null || !page.isAttachedToWindow) return
        closeChoicePopup(immediate = true)
        lateinit var overlay: CouiSlot
        overlay = CouiSlot(this, darkUi, primary) {
            CouiConfirmation(
                show = restartDialogVisible.value,
                title = getString(titleId),
                summary = getString(messageId),
                negativeText = getString(android.R.string.cancel),
                positiveText = getString(R.string.ui_restart_systemui_action),
                onDismissRequest = { closeRestartConfirmation() },
                onNegative = { closeRestartConfirmation() },
                onPositive = {
                    closeRestartConfirmation()
                    onConfirm()
                },
                onDismissFinished = {
                    page.removeView(overlay)
                    if (restartOverlay === overlay) restartOverlay = null
                },
                content = null,
            )
        }
        restartOverlay = overlay
        restartDialogVisible.value = true
        page.addView(overlay, FrameLayout.LayoutParams(-1, -2))
    }

    private fun sendSystemUiRestart(view:View){
        view.isEnabled=false
        val request=Intent(com.cosmod.launcherglass.util.SystemUiControl.ACTION).setPackage("com.android.systemui")
        try {
            sendOrderedBroadcast(request,null,object:BroadcastReceiver(){
                override fun onReceive(context:Context?,intent:Intent?){
                    view.isEnabled=true
                    val accepted=resultCode==com.cosmod.launcherglass.util.SystemUiControl.ACCEPTED
                    val message=if(accepted)R.string.ui_systemui_restarting else R.string.ui_systemui_restart_unavailable
                    Toast.makeText(this@MainActivity,message,Toast.LENGTH_LONG).show()
                }
            },android.os.Handler(android.os.Looper.getMainLooper()),0,null,null)
        }catch(_:Exception){
            view.isEnabled=true
            Toast.makeText(this,R.string.ui_systemui_restart_unavailable,Toast.LENGTH_LONG).show()
        }
    }

    @Keep fun isModuleActive(): Boolean = false
    @Keep fun isVectorFramework():Boolean=false
}
