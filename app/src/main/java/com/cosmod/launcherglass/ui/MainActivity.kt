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
import androidx.core.widget.doAfterTextChanged
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp as couiDp
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.NavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationBarItem
import io.github.suqi8.coui.kmp.theme.defaultTextStyles
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.SuUtil
import com.cosmod.launcherglass.util.LsposedScopeStatus
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var page: FrameLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) transferSettings(uri, false)
    }
    private val importSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) transferSettings(uri, true)
    }
    private val bannerPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) BannerTestNotification.post(this)
        else Toast.makeText(this,R.string.banner_test_permission,Toast.LENGTH_SHORT).show()
    }
    private data class Gate(val view: View, val allowed: () -> Boolean)
    private val gates = mutableListOf<Gate>()
    private val featureKeys = linkedSetOf<String>()
    private val previewDraft=mutableMapOf<String,Int>()
    private var glassPreview:GlassPreview?=null
    private var previewWallpaper: android.graphics.Bitmap? = null
    private var previewWallpaperId: Int? = null
    private var previewIcons: List<android.graphics.drawable.Drawable> = emptyList()
    private var previewLoading = false
    private var previewLastAttempt = 0L

    private var pageLayer:FrameLayout?=null
    private var backSnapshot:android.graphics.Bitmap?=null
    private var backPreview:View?=null
    private var backSwipeSide=0
    private var topInset=0
    private var applyHeaderInset:((Int)->Unit)?=null
    private var currentScroll: androidx.core.widget.NestedScrollView? = null
    private enum class Screen(val title: Int, val icon: Int, val tint: Int, val keywords: Int) {
        OVERVIEW(R.string.ui_cpi_home, R.drawable.ic_cpi_home, 0, 0),
        HOME(R.string.ui_cpi_features, R.drawable.ic_cpi_features, 0, 0),
        ICONS(R.string.ui_surface_icons, R.drawable.ic_nav_glass, 0x498DFF, R.string.ui_catalog_keywords_icons),
        FOLDERS(R.string.ui_surface_folders, R.drawable.ic_catalog_folder, 0xF49B46, R.string.ui_catalog_keywords_folders),
        PHOTO(R.string.ui_catalog_photo, R.drawable.ic_catalog_photo, 0xF46785, R.string.ui_catalog_keywords_photo),
        DOCK(R.string.ui_surface_dock, R.drawable.ic_catalog_dock, 0x6D85F4, R.string.ui_catalog_keywords_dock),
        BANNERS(R.string.ui_catalog_banners, R.drawable.ic_catalog_bell, 0x49A0FF, R.string.ui_catalog_keywords_banners),
        LIVE(R.string.ui_catalog_live, R.drawable.ic_catalog_island, 0x926DF5, R.string.ui_catalog_keywords_live),
        VOLUME(R.string.ui_volume_glass, R.drawable.ic_catalog_bell, 0x628EF1, R.string.ui_volume_glass_hint),
        LOCKSCREEN(R.string.lock_glass_title, R.drawable.ic_catalog_lock, 0x7665EF, R.string.lock_glass_keywords),
        LIGHT(R.string.ui_catalog_light, R.drawable.ic_nav_light, 0xE5AA27, R.string.ui_catalog_keywords_light),
        MOTION(R.string.app_tilt_title, R.drawable.ic_catalog_motion, 0x55A4ED, R.string.ui_catalog_keywords_motion),
        PERFORMANCE(R.string.ui_performance, R.drawable.ic_catalog_speed, 0x31B881, R.string.ui_catalog_keywords_performance),
        TOOLS(R.string.ui_about_module, R.drawable.ic_cpi_module, 0, 0),
        SETTINGS(R.string.ui_cpi_settings, R.drawable.ic_settings, 0x969AA8, R.string.ui_catalog_keywords_tools),
        DEVICE(R.string.ui_device_details, R.drawable.ic_cpi_module, 0, 0),
        CONTRIBUTORS(R.string.ui_about_contributors, R.drawable.ic_cpi_people, 0, 0),
    }
    private var screen=Screen.OVERVIEW
    private var detailParent=Screen.HOME
    private val tabScroll=mutableMapOf<Screen,Int>()
    private var searchQuery = ""
    private var searchInput: EditText? = null
    private var glowExpanded=true
    private var islandExpanded=true
    private var bannerExpanded=true
    private var appTiltExpanded=true
    private var folderExpanded=true
    private var blurExpanded = true
    private var designExpanded = false
    private var hero:View?=null
    private var changingTheme=false
    private var rootStatus:TextView?=null
    private var rootVersion:String?=null
    private var rootChecked=false
    private var lsposedText:TextView?=null
    private var fluxWarning:View?=null
    private val supportListener=SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
        if(key==SettingsProvider.KEY_NATIVE_FLUX_SUPPORT)runOnUiThread {updateFluxWarning()}
        if(key?.startsWith("runtime_")==true)runOnUiThread {updateStatus()}
    }
    private var scopeState:LsposedScopeStatus.State?=null
    private var statusEpoch=0
    private var restartBusy = false
    private var updatingUi = false
    private val refreshers = mutableListOf<() -> Unit>()
    private val switches = mutableListOf<Pair<CouiToggle,String>>()
    private val background get() = ContextCompat.getColor(this, R.color.cpi_ui_background)
    private val panel get() = ContextCompat.getColor(this, R.color.cpi_ui_panel)
    private val raised get() = ContextCompat.getColor(this, R.color.cpi_ui_raised)
    private val primary get() = ContextCompat.getColor(this,R.color.cpi_ui_accent)
    private val onSurface get() = ContextCompat.getColor(this,R.color.cpi_ui_text)
    private val secondaryText get() = ContextCompat.getColor(this,R.color.cpi_ui_muted)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val darkUi get()=androidx.core.graphics.ColorUtils.calculateLuminance(background)<.5
    private val coloredFeatures get()=screen==Screen.HOME && bool("coloros_ui_effects") && number("coloros_feature_background")!=0
    private val rootTabs=listOf(Screen.OVERVIEW,Screen.HOME,Screen.TOOLS)
    private var menuOverlay:OnextChoiceOverlay?=null
    private var restartOverlay:FrameLayout?=null
    private var restartShell:OnextPopupShell?=null
    private var restartButtonBackdrop:ColorosHeaderBlur?=null
    private var tabRelease:OnextBarRelease?=null
    private var lastRestartPlacement:Boolean?=null
    private var headerBody:LinearLayout?=null
    private var atmosphere:ColorosAtmosphere?=null
    private var celebration:BannerCelebration?=null
    private fun View.settingsPress(){if(bool("coloros_ui_effects"))springPress()}
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
    private fun master() = bool(SettingsProvider.KEY_ENABLED)
    private fun blur() = master() && bool(SettingsProvider.KEY_BLUR_ENABLED)
    private fun folderOwn()=blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED) && bool(FolderSettingsKeys.OWN)
    private fun design() = master() && bool(SettingsProvider.KEY_DESIGN_ENABLED)

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(UiLanguage.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        val theme=getSharedPreferences(SettingsProvider.PREFS_NAME,MODE_PRIVATE).getInt("ui_theme",0)
        delegate.localNightMode=nightMode(theme)
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        prefs = getSharedPreferences(SettingsProvider.PREFS_NAME, MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(supportListener)
        SettingsDefaults.initialize(this, prefs)
        screen = Screen.entries.firstOrNull { it.name == savedInstanceState?.getString("catalogScreen") } ?: Screen.OVERVIEW
        detailParent=Screen.entries.firstOrNull {it.name==savedInstanceState?.getString("catalogParent") && it in rootTabs} ?: Screen.HOME
        for(tab in rootTabs)tabScroll[tab]=savedInstanceState?.getInt("catalogScroll_"+tab.name)
            ?: if(tab==Screen.HOME)savedInstanceState?.getInt("catalogHomeScroll") ?: 0 else 0
        searchQuery = savedInstanceState?.getString("catalogQuery").orEmpty()
        blurExpanded = savedInstanceState?.getBoolean("blurExpanded") ?: true
        designExpanded = savedInstanceState?.getBoolean("designExpanded") ?: false
        root = column().apply { clipChildren=false;clipToPadding=false;setBackgroundColor(this@MainActivity.background) }
        setContentView(root)
        updateSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            if(topInset!=bars.top){
                topInset=bars.top
                applyHeaderInset?.invoke(topInset)
            }
            insets
        }
        page = FrameLayout(this).apply { isFocusableInTouchMode=true;clipChildren=false;clipToPadding=false }
        root.addView(page, LinearLayout.LayoutParams(-1, -1))
        showPage()
        currentScroll?.post { currentScroll?.scrollTo(0,savedInstanceState?.getInt("catalogScroll") ?: 0) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackProgressed(backEvent:androidx.activity.BackEventCompat){
                if(screen in rootTabs || restartOverlay!=null || menuOverlay!=null || !canAnimate())return
                backSwipeSide=if(backEvent.swipeEdge==androidx.activity.BackEventCompat.EDGE_LEFT)1 else -1
                val t=backEvent.progress.coerceIn(0f,1f)
                showBackPreview(t)
                pageLayer?.apply {
                    animate().withEndAction(null).cancel()
                    translationX=width*.31f*t*(.75f+.25f*t)*backSwipeSide
                    scaleX=1f-.075f*t
                    scaleY=scaleX
                }
            }
            override fun handleOnBackCancelled(){
                backSwipeSide=0
                pageLayer?.animate()?.translationX(0f)?.scaleX(1f)?.scaleY(1f)
                    ?.setDuration(240L)?.setInterpolator(colorosEase)
                    ?.withEndAction {removeBackPreview()}?.start()
            }
            override fun handleOnBackPressed() {
                if (restartOverlay!=null)closeRestartConfirmation()
                else if (menuOverlay!=null) closeChoicePopup()
                else if(screen !in rootTabs)backToCatalog()
                else if(screen==Screen.TOOLS)openTab(Screen.OVERVIEW)
                else if (screen==Screen.HOME && (searchQuery.isNotEmpty() || searchInput?.hasFocus()==true)) {
                    searchInput?.setText("")
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
        backSnapshot=null
        if(::prefs.isInitialized)prefs.unregisterOnSharedPreferenceChangeListener(supportListener)
        executor.shutdown()
        super.onDestroy()
    }
    private fun refreshControls() {
        updatingUi = true
        try {
            switches.forEach { (view,key) -> if(view.isChecked!=bool(key))view.isChecked=bool(key) }
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
                .setInterpolator(CherryMotion).start()
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
        val style = if (bold) couiTextStyles.headline1 else if (muted) couiTextStyles.body2 else couiTextStyles.body1
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

    private fun card(content:View,inner:Boolean=false):FrameLayout=FrameLayout(this).apply {
        val color=if(coloredFeatures)withAlpha(Color.WHITE,if(darkUi)27 else 154)else if(inner)raised else panel
        val radius=if(inner)16 else 20
        addView(CouiSurface(this@MainActivity,darkUi,primary,color,radius),FrameLayout.LayoutParams(-1,-1))
        clipChildren=false
        clipToPadding=false
        addView(content,FrameLayout.LayoutParams(-1,-2))
    }

    private fun button(stretchy:Boolean=false):CouiButton {
        val fill=androidx.core.graphics.ColorUtils.compositeColors(withAlpha(primary,22),panel)
        return CouiButton(this,darkUi,primary,onSurface,fill).apply {
            if(stretchy)clipChildren=false
        }
    }

    private fun closeChoicePopup(immediate:Boolean=false){
        val overlay=menuOverlay ?: return
        menuOverlay=null
        overlay.stopInteraction()
        val layer=pageLayer
        fun remove(){
            page.removeView(overlay)
            if(menuOverlay==null)layer?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        if(immediate || !canAnimate())remove()
        else overlay.shell.close(::remove)
    }

    private fun choicePopup(anchor:View,options:List<Pair<Int,Int>>,selected:Int,onPick:(Int)->Unit)=
        showChoicePopup(anchor,options.map {(id,label)->id to getString(label)},selected,onPick=onPick)

    private fun showChoicePopup(anchor:View,options:List<Pair<Int,String>>,selected:Int,widthDp:Int=198,onPick:(Int)->Unit){
        val layer=pageLayer ?: return
        val body=headerBody ?: return
        val scroll=currentScroll ?: return
        if(page.width<=0 || page.height<=0 || options.isEmpty())return
        closeChoicePopup(immediate=true)
        val animated=canAnimate()
        val anchorPos=IntArray(2)
        val pagePos=IntArray(2)
        anchor.getLocationInWindow(anchorPos)
        page.getLocationInWindow(pagePos)
        val width=minOf(dp(widthDp),page.width-dp(22)).coerceAtLeast(1)
        val menuPadding=dp(5)
        val availableHeight=page.height-topInset-dp(12)-2*menuPadding
        val itemHeight=(availableHeight/options.size).coerceIn(dp(24),dp(43))
        val height=2*menuPadding+options.size*itemHeight
        val anchorTop=anchorPos[1]-pagePos[1]
        val anchorBottom=anchorTop+anchor.height
        val rtl=anchor.layoutDirection==View.LAYOUT_DIRECTION_RTL
        val x=(anchorPos[0]-pagePos[0]+if(rtl)0 else anchor.width-width)
            .coerceIn(dp(10),(page.width-width-dp(10)).coerceAtLeast(dp(10)))
        val below=anchorBottom+dp(2)+height<=page.height-dp(8)
        val y=(if(below)anchorBottom+dp(2) else anchorTop-height-dp(2))
            .coerceIn(topInset+dp(4),(page.height-height-dp(8)).coerceAtLeast(topInset+dp(4)))
        val shell=OnextPopupShell(this).apply {
            background=panelShape(panel,28)
            clipToOutline=true
            elevation=dp(12).toFloat()
            isClickable=true
        }
        if(bool("coloros_ui_effects")){
            val blur=ColorosHeaderBlur(this,body,background,topInset,atmosphere,shell)
            blur.showScroll(scroll.scrollY)
            shell.addView(blur,FrameLayout.LayoutParams(-1,-1))
            shell.onMaterialMoved={blur.invalidate()}
        }
        shell.addView(View(this).apply {background=glass(withAlpha(panel,if(darkUi)91 else 150),28,legacyEdge=true)},
            FrameLayout.LayoutParams(-1,-1))
        val spotlight=if(animated)OnextSpotlightView(this,darkUi,dp(28).toFloat(),strength=.22f).also {
            shell.addView(it,FrameLayout.LayoutParams(-1,-1))
        }else null
        val highlight=View(this).apply {
            background=panelShape(withAlpha(if(darkUi)Color.WHITE else Color.BLACK,if(darkUi)52 else 23),21)
            alpha=0f
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        shell.addView(highlight,FrameLayout.LayoutParams(-1,itemHeight).apply {
            leftMargin=menuPadding;rightMargin=menuPadding
        })
        val menu=column().apply {setPadding(menuPadding,menuPadding,menuPadding,menuPadding)}
        for((position,option) in options.withIndex()){
            val (id,label)=option
            val item=row().apply {
                isFocusable=true
                contentDescription=label
                isSelected=id==selected
                setOnClickListener {
                    haptic(it)
                    closeChoicePopup()
                    onPick(id)
                }
            }
            item.addView(CouiChoiceLabel(this,darkUi,primary,label,id==selected,position,options.size),
                LinearLayout.LayoutParams(-1,-1))
            menu.addView(item,LinearLayout.LayoutParams(-1,itemHeight))
        }
        shell.addView(menu,FrameLayout.LayoutParams(-1,-1))
        val overlay=OnextChoiceOverlay(this,shell,menu,highlight,animated).apply {
            isClickable=true
            isFocusableInTouchMode=true
            clipChildren=false
            elevation=dp(16).toFloat()
            this.spotlight=spotlight
            setOnClickListener {closeChoicePopup()}
        }
        overlay.addView(shell,FrameLayout.LayoutParams(width,height,Gravity.TOP or Gravity.LEFT).apply {
            leftMargin=x;topMargin=y
        })
        menuOverlay=overlay
        layer.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        page.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        overlay.requestFocus()
        if(animated){
            shell.pivotX=if(rtl)0f else width.toFloat()
            shell.pivotY=if(below)0f else height.toFloat()
            shell.open()
        }
    }

    private fun optionRow(title:String,value:String)=optionRow(title,text(value,13f,true))
    private fun optionRow(title:String,value:TextView):LinearLayout=row().apply {
        setPadding(dp(15),dp(10),dp(12),dp(10))
        minimumHeight=dp(54)
        addView(text(title,16f),LinearLayout.LayoutParams(0,-2,1f))
        addView(value.apply {setPadding(dp(8),0,dp(3),0)})
        addView(AppCompatImageView(context).apply {
            setImageResource(R.drawable.ic_cpi_dropdown)
            imageTintList=ColorStateList.valueOf(secondaryText)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(16),dp(16)))
        rowRipple(this)
        settingsPress()
        isFocusable=true
    }

    private fun divider(parent:LinearLayout){
        parent.addView(CouiSlot(this,darkUi,primary) {
            HorizontalDivider(color = ComposeColor(withAlpha(onSurface,19)))
        },LinearLayout.LayoutParams(-1,-2).apply {leftMargin=dp(16);rightMargin=dp(16)})
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

    private fun save(key: String, value: Any) {
        if(updatingUi)return
        prefs.edit().apply {
            if(key==FolderSettingsKeys.OWN && value==true){
                FolderSettingsKeys.integers.forEach {base -> val own=FolderSettingsKeys.key(base);if(!prefs.contains(own))putInt(own,number(base))}
                FolderSettingsKeys.booleans.forEach {base -> val own=FolderSettingsKeys.key(base);if(!prefs.contains(own))putBoolean(own,bool(base))}
                if(!prefs.contains(GlassDispersionSettings.FOLDERS))
                    putInt(GlassDispersionSettings.FOLDERS,number(GlassDispersionSettings.ICONS))
            }
            when(value) { is Boolean -> putBoolean(key, value); is Int -> putInt(key, value) } }.apply()
        if(key!="coloros_ui_effects" && key!="coloros_feature_background")
            contentResolver.notifyChange(SettingsProvider.CONTENT_URI, null)
        updateGates();updateStatus()
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

    private fun savedSettingsDialog(title:String,content:LinearLayout):android.app.Dialog {
        val body=column().apply {setPadding(dp(16),dp(16),dp(16),dp(12))}
        add(body,text(title,19f,bold=true),bottom=10)
        add(body,content)
        val dialog=androidx.activity.ComponentDialog(this)
        val rounded=panelShape(withAlpha(panel,if(darkUi)174 else 195),24)
        val surface=FrameLayout(this).apply {
            clipChildren=false
            clipToPadding=false
            addView(card(body),FrameLayout.LayoutParams(-1,-2))
        }
        dialog.setContentView(surface)
        dialog.window?.apply {
            setBackgroundDrawable(rounded)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(if(darkUi).28f else .16f)
            setBackgroundBlurRadius(dp(26))
        }
        val backdrop=pageLayer
        backdrop?.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(dp(9).toFloat(),
            dp(9).toFloat(),android.graphics.Shader.TileMode.CLAMP))
        dialog.setOnDismissListener {backdrop?.setRenderEffect(null)}
        dialog.show()
        dialog.window?.setLayout(minOf(dp(390),resources.displayMetrics.widthPixels-dp(28)),-2)
        return dialog
    }

    private fun nameSettings(keys:Set<String>,slot:String,legacySlot:String,profiles:Map<String,String>){
        val form=column()
        val input=CouiField(this,darkUi,primary,getString(R.string.ui_saved_settings_name),"",
            onSurface,maxLength=48)
        form.addView(input,LinearLayout.LayoutParams(-1,-2))
        val actions=row().apply {setPadding(0,dp(12),0,0)}
        val cancel=button(stretchy=true).apply {setText(android.R.string.cancel)}
        val saveButton=button(stretchy=true).apply {setText(R.string.ui_preset_save)}
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
            dialog.dismiss()
            Toast.makeText(this,R.string.ui_preset_saved,Toast.LENGTH_SHORT).show()
        }
        saveButton.setOnClickListener {saveSettings()}
        input.onSubmit={saveSettings()}
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun openSavedSettings(anchor:View,keys:Set<String>,section:String){
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
        val saved=profiles.entries.toList()
        val pageSize=((page.height-topInset-dp(30))/dp(43)-4).coerceIn(2,8)

        fun confirmDelete(name:String){
            val form=column()
            add(form,text(getString(R.string.ui_saved_settings_delete_confirm,name),15f))
            val actions=row().apply {setPadding(0,dp(12),0,0)}
            val cancel=button(stretchy=true).apply {setText(android.R.string.cancel)}
            val delete=button(stretchy=true).apply {setText(R.string.ui_saved_settings_delete)}
            actions.addView(cancel,LinearLayout.LayoutParams(0,dp(44),1f).apply {marginEnd=dp(8)})
            actions.addView(delete,LinearLayout.LayoutParams(0,dp(44),1f))
            add(form,actions)
            val dialog=savedSettingsDialog(getString(R.string.ui_saved_settings_delete),form)
            cancel.setOnClickListener {dialog.dismiss()}
            delete.setOnClickListener {
                runCatching {
                    val stored=org.json.JSONObject(prefs.getString(slot,null) ?: "{}")
                    stored.remove(name)
                    prefs.edit().apply {
                        if(stored.length()==0)remove(slot) else putString(slot,stored.toString())
                        if(name==getString(R.string.ui_saved_settings_legacy))remove(legacySlot)
                    }.apply()
                }.onSuccess {
                    dialog.dismiss()
                    Toast.makeText(this,R.string.ui_saved_settings_deleted,Toast.LENGTH_SHORT).show()
                }.onFailure {Toast.makeText(this,R.string.ui_preset_error,Toast.LENGTH_SHORT).show()}
            }
        }

        fun openPage(start:Int,deleting:Boolean=false){
            val end=minOf(start+pageSize,saved.size)
            val choices=buildList {
                for(index in start until end)add(index to saved[index].key)
                if(start>0)add(-2 to getString(R.string.ui_saved_settings_previous))
                if(end<saved.size)add(-3 to getString(R.string.ui_saved_settings_next))
                if(deleting){
                    add(-6 to getString(android.R.string.cancel))
                }else{
                    add(-4 to getString(R.string.ui_preset_save))
                    if(saved.isNotEmpty())add(-5 to getString(R.string.ui_saved_settings_delete))
                }
            }
            showChoicePopup(anchor,choices,-1,widthDp=252){choice->
                when(choice){
                    -2->openPage(start-pageSize,deleting)
                    -3->openPage(end,deleting)
                    -4->nameSettings(keys,slot,legacySlot,profiles)
                    -5->openPage(0,deleting=true)
                    -6->openPage(0)
                    else->if(deleting){
                        confirmDelete(saved[choice].key)
                    }else{
                        runCatching {SettingsTransfer.parse(saved[choice].value)}
                            .onSuccess {applySavedSettings(keys,it)}
                            .onFailure {Toast.makeText(this,R.string.ui_preset_error,Toast.LENGTH_SHORT).show()}
                    }
                }
            }
        }
        openPage(0)
    }

    private fun addSavedSettings(body:LinearLayout,all:Boolean=false){
        val portable=SettingsDefaults.portable().keys
        if(screen==Screen.FOLDERS){
            featureKeys.addAll((FolderSettingsKeys.integers+FolderSettingsKeys.booleans).map(FolderSettingsKeys::key))
        }
        if(screen==Screen.ICONS)featureKeys.add(SettingsProvider.KEY_BLUR_QUALITY)
        val keys=if(all)portable else (featureKeys intersect portable)
        if(keys.isEmpty())return
        val section=if(all)"ALL" else screen.name
        val controls=column()
        add(controls,preferenceLink(getString(R.string.ui_presets)) {anchor->openSavedSettings(anchor,keys,section)})
        if(!all){
            divider(controls)
            add(controls,preferenceLink(getString(R.string.ui_reset_section)){anchor->
                choicePopup(anchor,listOf(0 to R.string.ui_cancel,1 to R.string.ui_reset_section_confirm),-1){
                    if(it==1)applySavedSettings(keys,emptyMap())
                }
            })
        }
        val card=card(controls)
        if(all)add(body,card,bottom=8)
        else body.addView(card,2,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(8)})
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

    private fun toggle(label: String, key: String): CouiToggle = CouiToggle(this,darkUi,primary).apply {
        featureKeys.add(key)
        isChecked=bool(key)
        this.label=label
        onChange={checked->if(!updatingUi)save(key,checked)}
        switches.add(this to key)
    }

    private fun actions(views: List<View>): View = row().apply {
        views.forEach { view ->
            val width = view.layoutParams?.width?.takeIf { it > 0 } ?: ViewGroup.LayoutParams.WRAP_CONTENT
            addView(view, LinearLayout.LayoutParams(width, dp(48)))
        }
    }
    private fun rowRipple(view: View) {
        view.background=RippleDrawable(ColorStateList.valueOf(withAlpha(if(darkUi)Color.WHITE else Color.BLACK,if(darkUi)48 else 22)),
            null,panelShape(Color.WHITE,10))
    }
    private fun smallIcon(icon:Int,label:String,action:(View)->Unit): CouiIconButton =
        CouiIconButton(this,darkUi,primary,onSurface,panel,icon,label,bool("coloros_ui_effects")).apply {
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
        restoreTabScroll(next)
    }

    private fun restoreTabScroll(tab:Screen){
        currentScroll?.let {scroll->scroll.post {scroll.scrollTo(0,tabScroll[tab] ?: 0)}}
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
        backSnapshot=null
        if(!canAnimate() || page.width<=0 || page.height<=0)return
        // Preserve the destination even after the old view tree and its GPU display lists detach.
        val scale=minOf(1f,1300f/maxOf(page.width,page.height))
        val bitmap=android.graphics.Bitmap.createBitmap((page.width*scale).roundToInt().coerceAtLeast(1),
            (page.height*scale).roundToInt().coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            val canvas=android.graphics.Canvas(bitmap)
            canvas.drawColor(background)
            canvas.scale(scale,scale)
            page.draw(canvas)
        }.onSuccess {backSnapshot=bitmap}.onFailure {bitmap.recycle()}
    }

    private fun showBackPreview(progress:Float){
        val snapshot=backSnapshot ?: return
        val view=backPreview ?: ImageView(this).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            scaleType=ImageView.ScaleType.FIT_XY
            setImageBitmap(snapshot)
            page.addView(this,0,FrameLayout.LayoutParams(-1,-1))
            backPreview=this
        }
        view.alpha=.90f+.10f*progress
    }

    private fun removeBackPreview(){
        backPreview?.let(page::removeView)
        backPreview=null
    }

    private fun backToCatalog(){
        val side=backSwipeSide.takeIf {it!=0} ?: if(page.layoutDirection==View.LAYOUT_DIRECTION_RTL)-1 else 1
        backSwipeSide=0
        removeBackPreview()
        backSnapshot=null
        screen=detailParent
        showPage(-1,returningSide=side)
    }

    private fun hideSearchKeyboard(){
        searchInput?.let { input ->
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(input.windowToken,0)
        }
    }

    private fun showPage(direction:Int=0,returningSide:Int=0){
        closeRestartConfirmation(immediate=true)
        closeChoicePopup(immediate=true)
        atmosphere?.stop()
        celebration?.stop()
        celebration=null
        gates.clear();featureKeys.clear();refreshers.clear();switches.clear()
        hero=null;rootStatus=null;lsposedText=null;fluxWarning=null
        searchInput=null
        restartButtonBackdrop=null
        val previous=pageLayer
        for(i in page.childCount-1 downTo 0)if(page.getChildAt(i)!==previous)page.removeViewAt(i)
        previous?.animate()?.cancel()
        val animate=direction!=0 && previous!=null && canAnimate() && page.isLaidOut
        if(!animate)page.removeAllViews()
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
            setPadding(0,topInset,0,0)
            isVerticalScrollBarEnabled=true
            isScrollbarFadingEnabled=true
        }
        val body=column().apply { clipChildren=false;clipToPadding=false;setPadding(dp(16),0,dp(16),dp(12)) }
        currentScroll=scroll
        headerBody=body
        var pinnedToolbar:View?=null
        var compactDetail:TextView?=null
        var backBlur:ColorosHeaderBlur?=null
        if(screen !in rootTabs){
            val toolbar=row().apply {
                setPadding(0,0,dp(14),0)
            }
            val back=smallIcon(R.drawable.ic_arrow_back,getString(R.string.ui_back)){backToCatalog()}
            if(bool("coloros_ui_effects")){
                val material=FrameLayout(this).apply {
                    background=panelShape(panel,22)
                    clipToOutline=true
                }
                val blur=ColorosHeaderBlur(this,body,background,topInset,scene,material,toolbar)
                backBlur=blur
                material.addView(blur,FrameLayout.LayoutParams(-1,-1))
                back.background=glassRipple(withAlpha(panel,if(darkUi)135 else 158),22)
                material.addView(back,FrameLayout.LayoutParams(-1,-1))
                back.followMaterial(material){blur.invalidate()}
                toolbar.addView(material,LinearLayout.LayoutParams(dp(44),dp(44)))
            }else toolbar.addView(back,LinearLayout.LayoutParams(dp(46),dp(46)))
            val compact=text(getString(screen.title),18f,bold=true).apply {
                maxLines=1
                ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(dp(5),0,0,0)
                alpha=0f
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                ViewCompat.setAccessibilityHeading(this,true)
            }
            toolbar.addView(compact,LinearLayout.LayoutParams(0,-2,1f))
            pinnedToolbar=toolbar
            compactDetail=compact
            space(body,51)
        }
        val catalog=screen==Screen.HOME
        val heading=text(getString(screen.title),32f,bold=true).apply {
            minHeight=dp(if(catalog)64 else if(pinnedToolbar!=null)47 else 104)
            gravity=if(catalog)Gravity.CENTER_VERTICAL else Gravity.BOTTOM
            setPadding(dp(if(catalog)5 else 0),0,dp(5),dp(if(catalog)0 else 9))
            ViewCompat.setAccessibilityHeading(this,true)
        }
        add(body,heading)
        scroll.addView(body,ViewGroup.LayoutParams(-1,-2))
        shell.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        layer.addView(shell,FrameLayout.LayoutParams(-1,-1))
        val headerBlur=if(bool("coloros_ui_effects"))ColorosHeaderBlur(this,body,background,topInset,scene)else null
        val blurHeight=dp(128)
        headerBlur?.let {layer.addView(it,FrameLayout.LayoutParams(-1,topInset+blurHeight,Gravity.TOP))}
        layer.addView(View(this).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(withAlpha(this@MainActivity.background,0),withAlpha(this@MainActivity.background,20),
                    withAlpha(this@MainActivity.background,if(darkUi)112 else 73)))
        },FrameLayout.LayoutParams(-1,dp(81),Gravity.BOTTOM))
        applyHeaderInset={inset->
            scroll.setPadding(0,inset,0,0)
            backBlur?.topInset=inset
            headerBlur?.let {header->
                header.topInset=inset
                header.layoutParams=header.layoutParams.apply {height=inset+blurHeight}
            }
            pinnedToolbar?.let {toolbar->
                toolbar.layoutParams=(toolbar.layoutParams as FrameLayout.LayoutParams).apply {topMargin=inset+dp(3)}
            }
        }
        pinnedToolbar?.let {
            layer.addView(it,FrameLayout.LayoutParams(-1,dp(54),Gravity.TOP).apply {
                setMargins(dp(16),topInset+dp(3),dp(16),0)
            })
        }
        val navigation=if(screen in rootTabs)addRootTabs(body,scene)else null
        addGlassPreview(body)
        when(screen){
            Screen.OVERVIEW->buildOverview(body)
            Screen.HOME->buildCatalog(body,scroll,heading,headerBlur,layer,navigation)
            Screen.ICONS->iconsSection(body)
            Screen.FOLDERS->folderSection(body)
            Screen.PHOTO->photoSection(body)
            Screen.DOCK->dockSection(body)
            Screen.BANNERS->bannerSection(body)
            Screen.LIVE->liveAlertSection(body)
            Screen.VOLUME->volumeSection(body)
            Screen.LOCKSCREEN->lockscreenSection(body)
            Screen.LIGHT->buildLight(body)
            Screen.MOTION->buildMotion(body)
            Screen.PERFORMANCE->buildPerformance(body)
            Screen.TOOLS->buildModule(body)
            Screen.SETTINGS->buildTools(body)
            Screen.DEVICE->buildDeviceDetails(body)
            Screen.CONTRIBUTORS->buildContributors(body)
        }
        if(screen in rootTabs)space(body,77)
        if(screen !in rootTabs && screen !in setOf(Screen.SETTINGS,Screen.DEVICE,Screen.CONTRIBUTORS,Screen.MOTION))addSavedSettings(body)
        addFeatureRestarts(layer,body)
        if(screen!=Screen.HOME){
            val detail=compactDetail!=null
            val compact=compactDetail ?: text(getString(screen.title),17f,bold=true).apply {
                alpha=0f;gravity=Gravity.CENTER_VERTICAL
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val titleParams=FrameLayout.LayoutParams(-1,dp(44),Gravity.TOP).apply {
                marginStart=dp(16);topMargin=topInset
            }
            if(!detail)layer.addView(compact,titleParams)
            val preceding=applyHeaderInset
            applyHeaderInset={inset->
                preceding?.invoke(inset)
                if(!detail)compact.layoutParams=titleParams.apply {topMargin=inset}
            }
            fun updateHeader(y:Int){
                headerBlur?.showScroll(y)
                backBlur?.showScroll(y)
                restartButtonBackdrop?.showScroll(y)
                navigation?.second?.showScroll(y)
                val travel=(heading.bottom-dp(if(detail)46 else 29)).coerceAtLeast(dp(24))
                val left=if(detail)(pinnedToolbar?.left ?: 0)+compact.left else dp(16)
                morphTitle(heading,compact,y,travel,left-body.paddingLeft,if(detail)dp(50) else dp(24))
            }
            scroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener {_,_,y,_,_->updateHeader(y)})
            scroll.post {updateHeader(scroll.scrollY)}
        }
        scroll.onPullChanged={
            headerBlur?.refreshScene()
            backBlur?.refreshScene()
            restartButtonBackdrop?.refreshScene()
            navigation?.second?.refreshScene()
        }
        if(navigation!=null){
            val rootInset=applyHeaderInset
            applyHeaderInset={inset->rootInset?.invoke(inset);navigation.second?.topInset=inset}
        }
        if(scene!=null && number("coloros_feature_background")==2){
            scene.onMotion={headerBlur?.refreshScene();navigation?.second?.refreshScene();restartButtonBackdrop?.refreshScene()}
            if(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))scene.start()
        }
        if(returningSide!=0){
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
            previous.animate().translationX(page.width*returningSide.toFloat()).scaleX(.925f).scaleY(.925f)
                .setDuration(290L).setInterpolator(colorosEase)
                .withEndAction {if(pageLayer!==previous)page.removeView(previous)}.start()
        }else if(animate && previous!=null){
            val tabs=screen in rootTabs && direction!=0 && previous.tag in rootTabs && previous.scaleX==1f
            val rtl=if(resources.configuration.layoutDirection==View.LAYOUT_DIRECTION_RTL)-1f else 1f
            val offset=(if(tabs)dp(23).toFloat() else page.width*.32f)*direction*rtl
            previous.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            val previousTarget=if(previous.scaleX<1f)previous.translationX*2f else -offset*.7f
            previous.animate().translationX(previousTarget).alpha(0f)
                .setDuration(320L).setInterpolator(colorosEase)
                .withEndAction {if(pageLayer!==previous)page.removeView(previous)}.start()
            layer.translationX=offset
            layer.alpha=if(tabs).0f else .58f
            layer.animate().translationX(0f).alpha(1f).setDuration(345L)
                .setInterpolator(colorosEase).start()
        }
    }

    private fun morphTitle(large:TextView,small:TextView,y:Int,travel:Int,targetX:Int,targetBaseline:Int){
        val t=(y.toFloat()/travel).coerceIn(0f,1f)
        val morph=t*t*(3f-2f*t)
        val blend=((t-.57f)/.43f).coerceIn(0f,1f)
        val handoff=blend*blend*(3f-2f*blend)
        val scale=1f+(small.textSize/large.textSize-1f)*morph
        large.pivotX=large.paddingLeft.toFloat()
        large.pivotY=large.baseline.toFloat()
        large.scaleX=scale;large.scaleY=scale
        large.translationX=(targetX-large.paddingLeft)*morph
        val currentBaseline=large.top+large.baseline-y.coerceAtMost(travel)
        large.translationY=(targetBaseline-currentBaseline)*morph
        large.alpha=1f-handoff
        small.alpha=handoff
        small.translationY=large.translationY*(1f-morph)*.12f
    }

    private fun addRootTabs(body:LinearLayout,scene:android.graphics.drawable.Drawable?):Pair<View,ColorosHeaderBlur?> {
        val effects=bool("coloros_ui_effects")
        val active=rootTabs.indexOf(screen)
        val selected=mutableIntStateOf(active)
        val release=tabRelease
        tabRelease=null
        val host=OnextTabBar(this,active,rootTabs.size,darkUi,effects,release,{selected.intValue=it}) {index,pose->
            haptic(page);openTab(rootTabs[index],pose)
        }.apply {elevation=dp(5).toFloat()}
        val tabs=CouiSlot(this,darkUi,primary,fill=true) {
            NavigationBar(color=ComposeColor.Transparent,showDivider=false,defaultWindowInsetsPadding=false) {
                rootTabs.forEachIndexed { index,tab ->
                    val current=index==selected.intValue
                    val icon=if(current)tab.icon else when(tab){
                        Screen.OVERVIEW->R.drawable.ic_cpi_home_outline
                        Screen.HOME->R.drawable.ic_cpi_features_outline
                        else->R.drawable.ic_cpi_module_outline
                    }
                    NavigationBarItem(current,{haptic(page);openTab(tab)},ImageVector.vectorResource(icon),
                        getString(if(tab==Screen.TOOLS)R.string.ui_cpi_module else tab.title))
                }
            }
        }
        val restart=smallIcon(R.drawable.ic_restart,getString(R.string.ui_restart_menu)){anchor->
            choicePopup(anchor,listOf(0 to R.string.ui_restart_launcher,1 to R.string.ui_restart_systemui,2 to R.string.ui_soft_restart),-1){
                when(it){0->confirmLauncherRestart(anchor);1->restartSystemUi(anchor);2->confirmSoftRestart(anchor)}
            }
        }.apply {
            background=null
            shareGlass {host.invalidate()}
        }
        val previousPlacement=lastRestartPlacement
        lastRestartPlacement=null
        return host to mountBottomCapsule(page,body,scene,host,tabs,restart,screen==Screen.HOME,previousPlacement)
    }

    private fun mountBottomCapsule(parent:FrameLayout,body:LinearLayout,scene:android.graphics.drawable.Drawable?,
        host:OnextTabBar,content:View,sideButton:CouiIconButton?=null,
        showSide:Boolean=sideButton!=null,previousSide:Boolean?=null):ColorosHeaderBlur? {
        val effects=bool("coloros_ui_effects")
        val material=FrameLayout(this)
        val blur=if(sideButton==null){
            material.background=panelShape(if(darkUi)0xff333333.toInt() else 0xfff0f0f0.toInt(),28)
            material.clipToOutline=true
            val backdrop=if(effects)ColorosHeaderBlur(this,body,background,topInset,scene,material,host)else null
            backdrop?.let {
                material.addView(it,FrameLayout.LayoutParams(-1,-1))
                host.onMaterialMoved={it.invalidate()}
            }
            material.addView(View(this).apply {
                background=glass(withAlpha(panel,if(effects)if(darkUi)79 else 112 else 255),28,legacyEdge=true)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(-1,-1))
            backdrop
        }else null
        material.addView(content,FrameLayout.LayoutParams(-1,-1))
        val gravity=if(sideButton==null)Gravity.CENTER else Gravity.LEFT or Gravity.CENTER_VERTICAL
        host.addView(material,FrameLayout.LayoutParams(dp(252),dp(56),gravity))
        val sideSize=if(sideButton==null)0 else dp(56)
        val gap=if(sideButton==null)0 else dp(10)
        var fusedBlur:ColorosHeaderBlur?=null
        if(sideButton!=null){
            host.addView(sideButton,FrameLayout.LayoutParams(sideSize,sideSize,gravity))
            if(effects){
                fusedBlur=ColorosHeaderBlur(this,body,background,topInset,scene,host).apply {
                    visibility=View.INVISIBLE
                    onBackdropChanged={host.invalidate()}
                }
                host.addView(fusedBlur,FrameLayout.LayoutParams(-1,-1))
            }
            val sample=fusedBlur?.let {backdrop-> {canvas:android.graphics.Canvas->backdrop.drawBackdrop(canvas)}}
            val tint=withAlpha(panel,if(effects)if(darkUi)170 else 132 else 255)
            val glass=GlassPairMaterial(this,tint,darkUi)
            val panelBounds=RectF()
            val buttonBounds=RectF()
            host.drawConnectedGlass={canvas->
                glassBounds(material,panelBounds)
                glassBounds(sideButton,buttonBounds)
                glass.draw(canvas,panelBounds,buttonBounds,host.height,sample)
            }
        }
        fun position(available:Int){
            val width=minOf(dp(252),(available-dp(28)-sideSize-gap).coerceAtLeast(1))
            val left=((available-width-sideSize-gap)/2).coerceAtLeast(0)
            val params=material.layoutParams as FrameLayout.LayoutParams
            if(params.width!=width || sideButton!=null && params.leftMargin!=left){
                params.width=width
                if(sideButton!=null)params.leftMargin=left
                material.layoutParams=params
            }
            sideButton?.let {button->
                val buttonParams=button.layoutParams as FrameLayout.LayoutParams
                val buttonLeft=left+width+gap
                if(buttonParams.leftMargin!=buttonLeft){
                    buttonParams.leftMargin=buttonLeft
                    button.layoutParams=buttonParams
                }
            }
        }
        var placement=if(canAnimate() && previousSide!=null && previousSide!=showSide){
            if(previousSide)1f else 0f
        }else if(showSide)1f else 0f
        fun place(progress:Float){
            placement=progress
            host.placementX=(sideSize+gap)*.5f*(1f-progress)
            sideButton?.apply {
                val radius=.5f*sideSize
                translationX=-(radius+gap)*(1f-progress)
                alpha=progress*progress
                if(progress==0f)visibility=View.INVISIBLE
                else visibility=View.VISIBLE
                if(progress<1f){scaleX=.58f+.42f*progress;scaleY=scaleX}
                else if(!isEnabled){scaleX=1f;scaleY=1f}
                isEnabled=progress>=1f
                importantForAccessibility=if(isEnabled)View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            host.invalidate()
        }
        position(parent.width.takeIf {it>0} ?: resources.displayMetrics.widthPixels)
        place(placement)
        if(previousSide!=null && placement!=(if(showSide)1f else 0f)){
            val motion=android.animation.ValueAnimator.ofFloat(placement,if(showSide)1f else 0f).apply {
                duration=345L
                interpolator=colorosEase
                addUpdateListener {place(it.animatedValue as Float)}
            }
            host.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
                override fun onViewAttachedToWindow(v:View){motion.start()}
                override fun onViewDetachedFromWindow(v:View){motion.cancel();v.removeOnAttachStateChangeListener(this)}
            })
        }
        host.addOnLayoutChangeListener { _,left,_,right,_,oldLeft,_,oldRight,_ ->
            if(right-left!=oldRight-oldLeft)position(right-left)
        }
        parent.addView(host,FrameLayout.LayoutParams(-1,dp(76),Gravity.BOTTOM))
        return fusedBlur ?: blur
    }

    private fun buildCatalog(body:LinearLayout,scroll:androidx.core.widget.NestedScrollView,heading:TextView,headerBlur:ColorosHeaderBlur?,layer:FrameLayout,navigation:Pair<View,ColorosHeaderBlur?>?){
        space(body,51)
        val transfer=column()
        settingsTransferSection(transfer)
        addSavedSettings(transfer,true)
        add(body,transfer)
        val entries=column()
        add(body,entries)
        val groups=listOf(
            listOf(Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK),
            listOf(Screen.BANNERS,Screen.LIVE,Screen.VOLUME,Screen.LOCKSCREEN,Screen.LIGHT),
            listOf(Screen.MOTION,Screen.PERFORMANCE)
        )
        val alternateLanguage=if(UiLanguage.tag(this)=="ru")"en" else "ru"
        val alternate=resources.configuration.let {current->
            createConfigurationContext(android.content.res.Configuration(current).apply {
                setLocale(java.util.Locale.forLanguageTag(alternateLanguage))
            })
        }
        val marks=Regex("\\p{M}+")
        val separators=Regex("[^\\p{L}\\p{N}]+")
        fun normalized(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFD)
            .lowercase(java.util.Locale.ROOT).replace(marks,"").replace(separators," ").trim()
        val searchIndex=(groups.flatten()+Screen.SETTINGS).associateWith {item->
            val titles=listOf(getString(item.title),alternate.getString(item.title)).map(::normalized)
            val keywords=(listOf(item.keywords)+FeatureSearch.labels(item.name).toList()).flatMap {
                normalized(getString(it)+" "+alternate.getString(it)).split(' ')
            }
            titles to keywords
        }
        var rendered:List<List<Screen>>?=null
        var renderedTransfer=false
        fun renderResults(){
            val query=normalized(searchQuery)
            val terms=query.split(' ').filter(String::isNotEmpty)
            val ranked=if(terms.isEmpty())groups else listOf(searchIndex.mapNotNull {(item,index)->
                val (titles,keywords)=index
                val words=titles.flatMap {it.split(' ')}
                if(terms.any {term->(words+keywords).none {term in it}})null
                else item to (when {
                    query in titles->100
                    titles.any {it.startsWith(query)}->75
                    terms.all {term->words.any {it.startsWith(term)}}->50
                    terms.all {term->(words+keywords).any {it.startsWith(term)}}->25
                    else->0
                })
            }.sortedByDescending {it.second}.map {it.first}).filter {it.isNotEmpty()}
            val transferMatches=listOf(R.string.ui_export_settings,R.string.ui_import_settings,
                R.string.ui_share_settings,R.string.ui_presets).any {id->
                val label=normalized(getString(id)+" "+alternate.getString(id))
                terms.all {it in label}
            }
            transfer.visibility=if(transferMatches)View.VISIBLE else View.GONE
            if(ranked==rendered && transferMatches==renderedTransfer)return
            rendered=ranked
            renderedTransfer=transferMatches
            entries.removeAllViews()
            for(matches in ranked){
                val rows=column()
                matches.forEachIndexed { index,item ->
                    if(index!=0)divider(rows)
                    add(rows,catalogRow(getString(item.title),item.icon,Color.BLACK or item.tint){openScreen(item)})
                }
                add(entries,card(rows),bottom=11)
            }
            if(ranked.isEmpty() && !transferMatches)add(entries,text(getString(R.string.ui_catalog_no_results),15f,true).apply {
                gravity=Gravity.CENTER;setPadding(0,dp(36),0,0)
            })
        }
        val searchBar=row().apply {
            setPadding(dp(13),0,dp(3),0)
        }
        val searchFill=View(this).apply {
            background=glass(withAlpha(panel,if(coloredFeatures)88 else 224),24)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val magnifier=AppCompatImageView(this).apply {
            setImageResource(R.drawable.ic_catalog_search);imageTintList=ColorStateList.valueOf(secondaryText)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        searchBar.addView(magnifier,LinearLayout.LayoutParams(dp(19),dp(19)))
        val input=androidx.appcompat.widget.AppCompatEditText(this).apply {
            hint=getString(R.string.ui_catalog_search)
            textSize=15f
            setTextColor(onSurface);setHintTextColor(secondaryText)
            inputType=android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            gravity=Gravity.CENTER_VERTICAL
            includeFontPadding=false
            background=null
            setPadding(dp(9),0,dp(8),0)
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setText(searchQuery)
            setSelection(length())
            setOnEditorActionListener { _,action,event ->
                val enter=event?.keyCode==android.view.KeyEvent.KEYCODE_ENTER && event.action==android.view.KeyEvent.ACTION_UP
                if(action!=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH && !enter)return@setOnEditorActionListener false
                val match=rendered?.flatten()?.singleOrNull()
                if(searchQuery.isNotBlank() && match!=null){
                    openScreen(match)
                    return@setOnEditorActionListener true
                }
                hideSearchKeyboard()
                page.requestFocus()
                true
            }
        }
        searchInput=input
        magnifier.setOnClickListener {
            input.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        searchBar.addView(input,LinearLayout.LayoutParams(0,-1,1f))
        val clear=OnextImageButton(this,bool("coloros_ui_effects")).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList=ColorStateList.valueOf(onSurface)
            contentDescription=getString(R.string.ui_close)
            scaleType=ImageView.ScaleType.CENTER
            background=android.graphics.drawable.InsetDrawable(glass(withAlpha(panel,if(darkUi)208 else 232),20),dp(3))
            setOnClickListener {haptic(it);input.setText("")}
        }
        clear.visibility=if(searchQuery.isEmpty())View.GONE else View.VISIBLE
        searchBar.addView(clear,LinearLayout.LayoutParams(dp(39),-1))
        var pinned=false
        fun styleSearch() {
            val focused=input.hasFocus()
            val opacity=if(coloredFeatures)if(focused)118 else 74 else if(pinned && headerBlur!=null)162 else 225
            searchFill.background=glass(withAlpha(panel,opacity),24)
        }
        var syncSearchMode:(()->Unit)?=null
        input.doAfterTextChanged {
            searchQuery=it.toString()
            clear.visibility=if(searchQuery.isEmpty())View.GONE else View.VISIBLE
            renderResults()
            syncSearchMode?.invoke()
        }
        val material=OnextPopupShell(this).apply {
            background=panelShape(panel,24)
            clipToOutline=true
        }
        val searchPanel=OnextSearchFrame(this,material){canAnimate() && !input.hasFocus()}.apply {
            clipChildren=false;clipToPadding=false
            addView(material,FrameLayout.LayoutParams(-1,-1))
            translationY=(topInset+dp(64)).toFloat()
        }
        val searchBlur=if(headerBlur!=null)ColorosHeaderBlur(this,body,background,topInset,atmosphere,material,searchPanel)else null
        searchBlur?.let {
            material.addView(it,FrameLayout.LayoutParams(-1,-1))
            material.onMaterialMoved={it.invalidate()}
        }
        material.addView(searchFill,FrameLayout.LayoutParams(-1,-1))
        if(canAnimate()){
            val light=OnextSpotlightView(this,darkUi,dp(24).toFloat())
            material.addView(light,FrameLayout.LayoutParams(-1,-1))
            searchPanel.spotlight=light
        }
        material.addView(searchBar,FrameLayout.LayoutParams(-1,-1))
        layer.addView(searchPanel,FrameLayout.LayoutParams(-1,dp(45),Gravity.TOP).apply {marginStart=dp(16);marginEnd=dp(16)})
        val compact=text(getString(Screen.HOME.title),17f,bold=true).apply {
            alpha=0f;gravity=Gravity.CENTER_VERTICAL
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        layer.addView(compact,FrameLayout.LayoutParams(-1,dp(34),Gravity.TOP).apply {marginStart=dp(18)})
        fun positionSearch(y:Int){
            val searching=input.hasFocus() || searchQuery.isNotEmpty()
            morphTitle(heading,compact,if(searching)0 else y,dp(51),dp(2),dp(23))
            compact.translationY+=topInset
            val offset=if(searching)0 else maxOf(dp(34),heading.bottom-y)
            searchPanel.translationY=(topInset+offset).toFloat()
            searchBlur?.invalidate()
            val next=searching || (y>=heading.bottom && heading.bottom>0)
            if(pinned!=next){pinned=next;styleSearch()}
        }
        val updateSearchMode={
            val searching=input.hasFocus() || searchQuery.isNotEmpty()
            heading.visibility=if(searching)View.GONE else View.VISIBLE
            navigation?.first?.visibility=if(input.hasFocus())View.INVISIBLE else View.VISIBLE
            if(searching)scroll.scrollTo(0,0)
            positionSearch(scroll.scrollY)
        }
        syncSearchMode=updateSearchMode
        input.setOnFocusChangeListener { _,_->updateSearchMode();styleSearch() }
        val updateInset=applyHeaderInset
        applyHeaderInset={inset->updateInset?.invoke(inset);searchBlur?.topInset=inset;positionSearch(scroll.scrollY)}
        heading.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->positionSearch(scroll.scrollY)}
        scroll.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _,_,y,_,_ ->
            headerBlur?.showScroll(y)
            searchBlur?.showScroll(y)
            restartButtonBackdrop?.showScroll(y)
            navigation?.second?.showScroll(y)
            positionSearch(y)
        })
        renderResults()
        updateSearchMode()
    }

    private fun catalogRow(label:String,icon:Int,tint:Int,action:(View)->Unit):View =
        preferenceLink(label,icon=icon,iconTint=tint,action=action)

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

    private fun informationRow(label:Int,value:String):View=column().apply {
        setPadding(dp(16),dp(10),dp(16),dp(10))
        minimumHeight=dp(56)
        add(this,text(getString(label),16f,bold=true))
        add(this,text(value.ifBlank {getString(R.string.ui_device_unknown)},13f,true),top=4)
    }

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

    // AboutAppCard and AboutAuthorGroup layout with CPI branding and project links.
    private fun buildModule(body:LinearLayout){
        val info=column().apply {
            gravity=Gravity.CENTER_HORIZONTAL
            setPadding(dp(16),dp(10),dp(16),dp(12))
        }
        info.addView(AppCompatImageView(this).apply {
            setImageDrawable(applicationInfo.loadIcon(packageManager))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(62),dp(62)))
        fun centered(value:String,size:Float,muted:Boolean=false,bold:Boolean=false)=
            text(value,size,muted,bold).apply {gravity=Gravity.CENTER}
        add(info,centered(getString(R.string.app_name),24f,bold=true),top=8)
        add(info,centered(getString(R.string.ui_about_version,BuildConfig.VERSION_NAME),14f,true),top=3)
        info.addView(text(getString(R.string.ui_release_badge),10f,bold=true).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(8),dp(3),dp(8),dp(3))
            background=panelShape(if(darkUi)0xff6250b6.toInt() else 0xff6c50c9.toInt(),12)
        },LinearLayout.LayoutParams(-2,-2).apply {topMargin=dp(6)})
        add(info,centered(getString(R.string.ui_module_description),13f,true),top=6)
        add(body,card(info).also(::decorateCelebration),bottom=10)

        val moduleControls=column()
        add(moduleControls,switchRow(getString(R.string.ui_module),SettingsProvider.KEY_ENABLED))
        divider(moduleControls)
        add(moduleControls,preferenceLink(getString(R.string.ui_cpi_settings)){openScreen(Screen.SETTINGS)})
        add(body,card(moduleControls),bottom=12)
        val people=column()
        add(people,text(getString(R.string.ui_authors),13f,true).apply {setPadding(dp(16),dp(11),dp(16),0)})
        val authors=row().apply {setPadding(dp(12),0,dp(12),dp(8))}
        for((name,url) in listOf("@immorgue" to "https://t.me/naten_e_mire","@Kostubet" to "https://t.me/Kostubet")){
            authors.addView(button().apply {
                text=name
                background=glassRipple(raised,20)
                setOnClickListener {haptic(it);openLink(url)}
            },LinearLayout.LayoutParams(0,dp(44),1f).apply {marginStart=dp(4);marginEnd=dp(4)})
        }
        add(people,authors,top=5)
        divider(people)
        add(people,preferenceLink(getString(R.string.ui_about_contributors),icon=R.drawable.ic_cpi_people){
            openScreen(Screen.CONTRIBUTORS)
        })
        divider(people)
        add(people,preferenceLink(getString(R.string.ui_telegram_forum),"Kostubet Topics",R.drawable.ic_cpi_telegram){
            openLink("https://t.me/KostubetTopics")
        })
        divider(people)
        add(people,preferenceLink(getString(R.string.ui_github_repository),"Kostubet/CPI-Kostubet-Edition",R.drawable.ic_cpi_code){
            openLink("https://github.com/Kostubet/CPI-Kostubet-Edition")
        })
        add(body,card(people),bottom=10)
        buildSupport(body)
    }

    private fun buildContributors(body:LinearLayout){
        val people=column()
        add(people,informationRow(R.string.ui_cpi_edition,"Kostubet Topics"))
        divider(people)
        add(people,informationRow(R.string.ui_contributor_help,"@anime_killer2009"))
        divider(people)
        add(people,informationRow(R.string.ui_contributor_beta,"@espcode"))
        divider(people)
        add(people,informationRow(R.string.ui_contributor_sources,"Mahmud0808 · MiToverG422"))
        add(body,card(people))
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

    private fun iconsSection(body:LinearLayout){
        val iconSize=slider(getString(R.string.ui_icon_size),"launcher_icon_size",70,130,"%",
            getString(R.string.ui_icon_size_hint))
        gate(iconSize,::master)
        add(body,card(iconSize),bottom=8)
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
        coloringSection(body)
        section(body, getString(R.string.ui_background_blur), "", SettingsProvider.KEY_BLUR_ENABLED, blurExpanded,
            { blurExpanded = it }, ::master, ::blur) { content ->
            glassStyleSelector(content,folder=false)
            add(content,glassDispersion(GlassDispersionSettings.ICONS){blur() && number(SettingsProvider.KEY_BLUR_STYLE)==1})
            effectGroup(content,getString(R.string.ui_blur),SettingsProvider.KEY_BLUR_RADIUS_ENABLED,
                getString(R.string.ui_blur_radius),SettingsProvider.KEY_BLUR_RADIUS,max=50,allowed=::blur,min=0,suffix="") { details ->
                if(bool(SettingsProvider.KEY_DEVELOPER_MODE)) {
                    val quality=slider(getString(R.string.ui_blur_quality),SettingsProvider.KEY_BLUR_QUALITY,0,100,"%",getString(R.string.ui_blur_quality_hint))
                    gate(quality,::blur);add(details,quality)
                }
                val fps=slider(getString(R.string.ui_live_blur_fps),SettingsProvider.KEY_LIVE_FPS,1,120," FPS")
                gate(fps) { blur() && number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE }
                add(details,fps)
            }
            blurToneGroup(content)
            val locations=column()
            val locationDetails=column()
            val locationAccordion=SettingsAccordion(locationDetails,false)
            divider(locationDetails)
            add(locationDetails,switchRow(getString(R.string.ui_drawer_blur),SettingsProvider.KEY_DRAWER_BLUR_ENABLED))
            add(locationDetails,switchRow(getString(R.string.ui_folder_blur),SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED,getString(R.string.ui_folder_blur_description)))
            gate(locationDetails) { blur() && bool(SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED) }
            add(locations,switchRow(getString(R.string.ui_icon_blur),SettingsProvider.KEY_BLUR_LOCATIONS_ENABLED,expanded=false) { open ->
                locationAccordion.setOpen(open,canAnimate())
            })
            add(locations,locationAccordion)
            add(content,card(locations,true),top=8,bottom=4)
        }
        section(body, getString(R.string.ui_icon_design), getString(R.string.ui_icon_design_hint), SettingsProvider.KEY_DESIGN_ENABLED, designExpanded,
            { designExpanded = it }, ::master, ::design) { content ->
            glyphOutlineGroup(content)
            effectGroup(content, getString(R.string.ui_logo_shadow), SettingsProvider.KEY_GLYPH_SHADOW,
                getString(R.string.ui_logo_shadow_opacity), SettingsProvider.KEY_SHADOW_OPACITY)
            effectGroup(content, getString(R.string.ui_icon_depth), SettingsProvider.KEY_VOLUME_ENABLED,
                getString(R.string.ui_depth_strength), SettingsProvider.KEY_GRADIENT_OPACITY)
        }
    }
    private fun glassDispersion(key:String,allowed:()->Boolean={true})=
        slider(getString(R.string.ui_glass_dispersion),key,0,100,"%").also {
            gate(it){Build.VERSION.SDK_INT>=33 && allowed()}
        }

    private fun photoSection(body:LinearLayout){
        val enabledKey=SettingsProvider.KEY_PHOTO_WIDGET_BLUR
        val enabled={master() && bool(enabledKey)}
        val frameKey=SettingsProvider.KEY_PHOTO_WIDGET_STYLE
        val materialKey=SettingsProvider.KEY_PHOTO_WIDGET_MATERIAL
        val fadeKey=SettingsProvider.KEY_PHOTO_WIDGET_GLOW
        val tintKey=SettingsProvider.KEY_PHOTO_WIDGET_TINT
        effectGroup(body,getString(R.string.ui_widget_glass),enabledKey,
            getString(R.string.ui_widget_content_transparency),
            SettingsProvider.KEY_PHOTO_WIDGET_OPACITY,allowed=::master,expanded=true){details->
            gate(details,enabled)
            modeChoice(details,R.string.ui_widget_scope,WidgetSettings.SCOPE,
                listOf(getString(R.string.ui_widget_scope_photo),getString(R.string.ui_widget_scope_all)),allowed=enabled)
            add(details,slider(getString(R.string.ui_blur_radius),WidgetSettings.RADIUS,0,50,""))
            modeChoice(details,R.string.ui_widget_frame,frameKey,
                listOf(getString(R.string.ui_photo_original),
                    getString(R.string.ui_widget_custom_frame)),allowed=enabled)
            modeChoice(details,R.string.ui_blur_style,materialKey,listOf("Frosted","Liquid Glass"),
                valid={it==0 || Build.VERSION.SDK_INT>=33},allowed=enabled)
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
            divider(details)
            add(details,switchRow(getString(R.string.ui_widget_tint),tintKey,
                getString(R.string.ui_widget_tint_hint)))
            add(details,switchRow(getString(R.string.ui_coloring_wallpaper),WidgetSettings.WALLPAPER_TINT).also {
                gate(it){enabled() && bool(tintKey)}
            })
            add(details,slider(getString(R.string.ui_hue),WidgetSettings.HUE,0,360,"°").also {
                gate(it){enabled() && bool(tintKey) && !bool(WidgetSettings.WALLPAPER_TINT)}
            })
            widgetTintSliders(details,WidgetSettings.TINT_SATURATION,WidgetSettings.TINT_TRANSPARENCY) {
                enabled() && bool(tintKey)
            }
            add(details,slider(getString(R.string.ui_tint_lightness),WidgetSettings.LIGHTNESS,-100,100,"%").also {
                gate(it){enabled() && bool(tintKey)}
            })
            val liquid=column()
            add(liquid,glassDispersion(GlassDispersionSettings.PHOTOS,enabled))
            add(liquid,slider(getString(R.string.ui_liquid_width),SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH,0,100,"%"))
            add(liquid,slider(getString(R.string.ui_liquid_distortion),SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION,0,300,"%"))
            gate(liquid){enabled() && number(materialKey)==1}
            liquid.visibility=if(number(materialKey)==1)View.VISIBLE else View.GONE
            refreshers.add {reveal(liquid,number(materialKey)==1)}
            add(details,liquid)
        }
    }

    private fun widgetTintSliders(body:LinearLayout,saturation:String,transparency:String,enabled:()->Boolean){
        add(body,slider(getString(R.string.ui_saturation),saturation,0,100,"%").also {gate(it,enabled)})
        add(body,slider(getString(R.string.ui_tint_transparency),transparency,0,100,"%").also {gate(it,enabled)})
    }

    private fun lockscreenSection(body:LinearLayout){
        effectGroup(body,getString(R.string.lock_aod_blur),LockscreenSettings.AOD_BLUR_ENABLED,
            getString(R.string.ui_blur_radius),LockscreenSettings.AOD_BLUR_RADIUS,max=36,allowed=::master,suffix=" dp")
        val hint=text(getString(R.string.lock_glass_hint),14f,true).apply {setPadding(dp(16),dp(14),dp(16),dp(14))}
        add(body,card(hint),bottom=10)
        val clearWallpaper=card(switchRow(getString(R.string.lock_glass_clear_bouncer),LockscreenSettings.CLEAR_BOUNCER_BLUR))
        gate(clearWallpaper,::master)
        add(body,clearWallpaper,bottom=10)
        effectGroup(body,getString(R.string.lock_unlock_animation),LockscreenSettings.UNLOCK_ENABLED,
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
            divider(details)
            add(details,slider(getString(R.string.lock_unlock_blur),LockscreenSettings.UNLOCK_BLUR,0,LockscreenSettings.MAX_UNLOCK_BLUR," dp")
                .also {gate(it,active)})
        }
        for(surface in LockscreenSettings.Surface.entries){
            val enabled=surface.key("enabled")
            val title=getString(surface.title)
            val active={master() && bool(enabled)}
            effectGroup(body,title,enabled,getString(R.string.island_opacity),surface.key("opacity"),allowed=::master){details->
                val style=surface.key("style")
                modeChoice(details,R.string.ui_blur_style,style,listOf("Frosted","Liquid Glass"),
                    valid={it==0 || Build.VERSION.SDK_INT>=33},allowed=active)
                add(details,slider(getString(R.string.ui_blur_radius),surface.key("blur"),0,50,""))
                if(surface.adjustableSaturation){
                    val saturation=surface.key("saturation")
                    add(details,slider(getString(R.string.ui_saturation),saturation,0,OpticalSettings.maximum(saturation),"%"))
                }
                if(surface.adjustableCorner){
                    val corner=surface.key("corner")
                    add(details,slider(getString(R.string.ui_glass_corner),corner,OpticalSettings.minimum(corner),50,"%",
                        formatter={if(it<0)getString(R.string.ui_system) else "$it%"}))
                }
                if(surface==LockscreenSettings.Surface.WIDGETS){
                    val wallpaper=LockscreenSettings.WIDGET_WALLPAPER_TINT
                    add(details,switchRow(getString(R.string.ui_coloring_wallpaper),wallpaper))
                    widgetTintSliders(details,LockscreenSettings.WIDGET_TINT_SATURATION,
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
                if(surface!=LockscreenSettings.Surface.CLOCK){
                    opticalSection(details,surface.edgeSurface,getString(R.string.edge_title))
                    darkContourSection(details,surface.edgeSurface,getString(R.string.ui_dark_contour))
                }
            }
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
    private fun addFeatureRestarts(layer:FrameLayout,body:LinearLayout){
        val size=dp(44)
        if(screen==Screen.BANNERS){
            space(body,77)
            addBannerBottomCapsule(layer,body)
            return
        }
        val launcher=screen in setOf(Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK,Screen.MOTION,Screen.PERFORMANCE)
        val systemUi=screen in setOf(Screen.LIVE,Screen.VOLUME,Screen.LOCKSCREEN,Screen.PERFORMANCE)
        if(!launcher && !systemUi)return
        space(body,64)
        if(launcher!=systemUi){
            val label=getString(if(launcher)R.string.ui_restart_launcher else R.string.ui_restart_systemui)
            addRoundRestart(layer,body,label,FrameLayout.LayoutParams(size,size,Gravity.BOTTOM or Gravity.END).apply {
                marginEnd=dp(16);bottomMargin=dp(12)
            },if(launcher)::confirmLauncherRestart else ::restartSystemUi)
            return
        }
        val bar=row().apply {
            setPadding(dp(4),dp(4),dp(4),dp(4))
            background=glass(panel,27)
            elevation=dp(5).toFloat()
        }
        fun addRestart(shortLabel:Int,fullLabel:Int,action:(View)->Unit){
            val label=getString(fullLabel)
            val control=if(launcher && systemUi)button().apply {
                setText(shortLabel)
                contentDescription=label
                background=glassRipple(raised,22)
                setOnClickListener {haptic(it);action(it)}
            }else smallIcon(R.drawable.ic_restart,label,action).apply {background=glassRipple(raised,22)}
            control.tooltipText=label
            bar.addView(control,LinearLayout.LayoutParams(if(launcher && systemUi)-2 else dp(44),dp(44)))
        }
        if(launcher)addRestart(R.string.ui_runtime_launcher,R.string.ui_restart_launcher,::confirmLauncherRestart)
        if(systemUi)addRestart(R.string.ui_restart_short_systemui,R.string.ui_restart_systemui,::restartSystemUi)
        layer.addView(bar,FrameLayout.LayoutParams(-2,dp(52),Gravity.BOTTOM or Gravity.END).apply {
            marginStart=dp(16);marginEnd=dp(16);bottomMargin=dp(12)
        })
    }

    private fun addBannerBottomCapsule(layer:FrameLayout,body:LinearLayout){
        val controls=row().apply {setPadding(dp(9),dp(4),dp(9),dp(4))}
        val icons=ArrayList<AppCompatImageView>(2)
        val labels=ArrayList<TextView>(2)
        fun preview(active:Int){
            for(i in icons.indices){
                val color=withAlpha(if(darkUi)Color.WHITE else Color.BLACK,if(i==active)230 else 184)
                icons[i].imageTintList=ColorStateList.valueOf(color)
                labels[i].setTextColor(color)
            }
        }
        fun action(icon:Int,title:Int,description:Int,onClick:(View)->Unit):View=column().apply {
            gravity=Gravity.CENTER
            isFocusable=true
            contentDescription=getString(description)
            tooltipText=contentDescription
            setOnClickListener {haptic(it);onClick(it)}
            val image=AppCompatImageView(context).apply {
                setImageResource(icon)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label=text(getString(title),10f).apply {
                maxLines=1;gravity=Gravity.CENTER
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            icons.add(image);labels.add(label)
            addView(image,LinearLayout.LayoutParams(dp(24),dp(24)))
            addView(label,LinearLayout.LayoutParams(-1,-2).apply {topMargin=dp(2)})
            controls.addView(this,LinearLayout.LayoutParams(0,-1,1f))
        }
        val test=action(R.drawable.ic_catalog_bell,R.string.ui_banner_test_short,R.string.banner_test){
            if(Build.VERSION.SDK_INT>=33 && !BannerTestNotification.hasPermission(this))
                bannerPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else BannerTestNotification.post(this)
        }
        gate(test){master() && bool("banner_glass_enabled")}
        action(R.drawable.ic_restart,R.string.ui_restart_short_systemui,R.string.ui_restart_systemui,::restartSystemUi)
        preview(-1)
        val host=OnextTabBar(this,-1,2,darkUi,bool("coloros_ui_effects"),null,::preview){_,_->}.apply {
            elevation=dp(5).toFloat()
        }
        val blur=mountBottomCapsule(layer,body,atmosphere,host,controls)
        restartButtonBackdrop=blur
        val preceding=applyHeaderInset
        applyHeaderInset={inset->preceding?.invoke(inset);blur?.topInset=inset}
    }

    private fun addRoundRestart(layer:FrameLayout,body:LinearLayout,label:String,params:FrameLayout.LayoutParams,
        action:(View)->Unit):View {
        val host=FrameLayout(this).apply {clipChildren=false;clipToPadding=false}
        val tint=if(darkUi)0x90373840.toInt() else 0x91f8f8fc.toInt()
        val material=GlassActionRow(this,tint,darkUi)
        if(bool("coloros_ui_effects")){
            val blur=ColorosHeaderBlur(this,body,background,topInset,atmosphere,material,host).apply {
                visibility=View.INVISIBLE
                onBackdropChanged={material.invalidate()}
                showScroll(currentScroll?.scrollY ?: 0)
            }
            host.addView(blur,FrameLayout.LayoutParams(-1,-1))
            material.drawBackdrop={blur.drawBackdrop(it)}
            restartButtonBackdrop=blur
            val preceding=applyHeaderInset
            applyHeaderInset={inset->preceding?.invoke(inset);blur.topInset=inset;blur.refreshScene()}
        }
        val control=smallIcon(R.drawable.ic_restart,label,action).apply {
            background=null
            tooltipText=label
            shareGlass {material.invalidate()}
        }
        material.addView(control,LinearLayout.LayoutParams(-1,-1))
        host.addView(material,FrameLayout.LayoutParams(-1,-1))
        layer.addView(host,params)
        return host
    }


    private fun bannerSection(body:LinearLayout){
        val enabled={master() && bool("banner_glass_enabled")}
        val motionEnabled={enabled() && bool("banner_glass_motion")}
        val plateEnabled={enabled() && bool("banner_plate_enabled")}
        section(body,getString(R.string.banner_title),getString(R.string.banner_hint),"banner_glass_enabled",
            bannerExpanded,{bannerExpanded=it},::master,enabled) {details->
            fun group(title:Int,controls:List<View>){
                divider(details)
                val heading=text(getString(title),13f,true,bold=true).apply {setPadding(dp(15),dp(12),dp(15),dp(3))}
                ViewCompat.setAccessibilityHeading(heading,true)
                add(details,heading)
                controls.forEachIndexed {index,control->
                    if(index>0)divider(details)
                    add(details,control)
                }
            }
            fun range(label:Int,key:String,min:Int=0,unit:String="%",allowed:(()->Boolean)?=null):View {
                return slider(getString(label),key,min,OpticalSettings.maximum(key),unit).also {
                    if(allowed!=null)gate(it,allowed)
                }
            }
            divider(details)
            add(details,preferenceLink(getString(R.string.banner_test_channel_settings),getString(R.string.banner_test_hint)){
                BannerTestNotification.openSettings(this)
            })
            group(R.string.ui_banner_group_motion,listOf(
                switchRow(getString(R.string.banner_motion),"banner_glass_motion"),
                switchRow(getString(R.string.ui_soft_motion),"banner_motion_soft").also {gate(it,motionEnabled)},
                range(R.string.banner_opening_blur,"banner_motion_blur",unit=" dp",allowed=motionEnabled),
                range(R.string.banner_open_speed,"banner_open_speed",min=50,allowed=motionEnabled),
                range(R.string.banner_close_speed,"banner_close_speed",min=50,allowed=motionEnabled)
            ))
            group(R.string.ui_banner_group_glass,listOf(
                range(R.string.ui_blur_radius,"banner_glass_blur",unit=""),
                range(R.string.ui_liquid_width,"banner_glass_width"),
                range(R.string.ui_liquid_distortion,"banner_glass_distortion"),
                range(R.string.island_opacity,"banner_glass_opacity"),
                range(R.string.island_lens_corner,"banner_glass_corner"),
                glassDispersion(GlassDispersionSettings.BANNERS,enabled)
            ))
            group(R.string.ui_banner_group_shading,listOf(
                range(R.string.banner_shadow,"banner_shadow_strength"),
                range(R.string.banner_shadow_size,"banner_shadow_size",unit=" dp"),
                range(R.string.banner_top_darkness,"banner_top_darkness")
            ))
            group(R.string.ui_banner_group_plate,listOf(
                switchRow(getString(R.string.banner_plate),"banner_plate_enabled"),
                range(R.string.ui_hue,"banner_plate_hue",unit="°",allowed=plateEnabled),
                range(R.string.ui_saturation,"banner_plate_saturation",allowed=plateEnabled),
                range(R.string.ui_brightness,"banner_plate_brightness",allowed=plateEnabled),
                range(R.string.ui_tint_lightness,"banner_plate_lightness",min=-100,allowed=plateEnabled),
                range(R.string.banner_plate_transparency,"banner_plate_opacity",allowed=plateEnabled),
                range(R.string.banner_plate_fade,"banner_plate_bottom_fade",allowed=plateEnabled)
            ))
        }
    }

    private fun liveAlertSection(body:LinearLayout) {
        section(body,getString(R.string.island_title),getString(R.string.island_hint),"island_glass_enabled",
            islandExpanded,{islandExpanded=it},::master,{master() && bool("island_glass_enabled")}){details->
            add(details,glassDispersion(GlassDispersionSettings.ALERTS))
            for((label,key) in listOf(R.string.ui_liquid_width to "island_glass_width",
                R.string.ui_liquid_distortion to "island_glass_distortion",R.string.ui_blur_radius to "island_glass_blur",
                R.string.island_mix to "island_glass_mix",R.string.island_opacity to "island_glass_opacity",
                R.string.island_lens_corner to "island_glass_corner"))
                add(details,slider(getString(label),key,0,OpticalSettings.maximum(key),if(key.endsWith("blur"))"" else "%"))
        }
    }

    private fun volumeSection(body:LinearLayout){
        val content=column()
        val enabled={master() && bool("volume_glass_enabled")}
        add(content,switchRow(getString(R.string.ui_volume_glass),"volume_glass_enabled",getString(R.string.ui_volume_glass_hint)))
        modeChoice(content,R.string.ui_blur_style,"volume_glass_style",listOf("Frosted","Liquid Glass"),
            valid={it==0 || Build.VERSION.SDK_INT>=33},allowed=enabled)
        for((label,key) in listOf(R.string.ui_volume_background_brightness to "volume_glass_brightness",
            R.string.ui_volume_height to "volume_glass_height",
            R.string.ui_volume_rim to "volume_glass_rim")){
            val control=slider(getString(label),key,OpticalSettings.minimum(key),OpticalSettings.maximum(key),"%")
            gate(control,enabled)
            add(content,control)
        }
        val blur=slider(getString(R.string.ui_blur_radius),"volume_glass_blur",0,32," dp")
        gate(blur,enabled)
        add(content,blur)
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
        gate(content,::master)
        add(body,card(content))
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
        val value=text("",13f,true)
        val row=optionRow(getString(title),value)
        fun selected()=number(key).coerceIn(labels.indices)
        fun refresh(){value.text=labels[selected()]}
        refresh()
        refreshers.add(::refresh)
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

    private fun dockSection(body:LinearLayout) {
        val content=column().apply {setPadding(0,dp(3),0,dp(5))}
        add(content,text(getString(R.string.ui_dock_glass_hint),14f,true).apply {
            setPadding(dp(15),dp(9),dp(15),dp(5))
        },bottom=3)
        modeChoice(content,R.string.ui_blur_style,SettingsProvider.KEY_DOCK_STYLE,
            listOf(getString(R.string.ui_glass_off),"Frosted","Liquid Glass"),
            valid={it!=2 || Build.VERSION.SDK_INT>=33},allowed=::blur)
        val controls=column()
        add(controls,slider(getString(R.string.ui_dock_width),"dock_width_percent",55,160,"%"))
        add(controls,slider(getString(R.string.ui_dock_height),"dock_height_percent",55,160,"%"))
        add(controls,slider(getString(R.string.ui_blur_radius),SettingsProvider.KEY_DOCK_BLUR,0,40,""))
        add(controls,slider(getString(R.string.ui_dock_rounding),SettingsProvider.KEY_DOCK_CORNER,0,50,"%"))
        add(controls,slider(getString(R.string.ui_dock_inset),SettingsProvider.KEY_DOCK_INSET,0,32," dp"))
        gate(controls){blur() && number(SettingsProvider.KEY_DOCK_STYLE)>0}
        add(content,controls)
        val liquid=column()
        add(liquid,slider(getString(R.string.ui_liquid_width),SettingsProvider.KEY_DOCK_LIQUID_WIDTH,0,100,"%"))
        add(liquid,slider(getString(R.string.ui_liquid_distortion),SettingsProvider.KEY_DOCK_LIQUID_DISTORTION,0,300,"%"))
        add(liquid,glassDispersion(GlassDispersionSettings.DOCK))
        gate(liquid){blur() && number(SettingsProvider.KEY_DOCK_STYLE)==2}
        add(content,liquid)
        add(body,card(content))
        val indicator=column().apply {setPadding(0,dp(3),0,dp(5))}
        val keepIndicator=switchRow(getString(R.string.ui_page_indicator_keep_visible),"indicator_keep_visible")
        gate(keepIndicator,::master)
        add(indicator,keepIndicator)
        modeChoice(indicator,R.string.ui_page_indicator_glass,"indicator_glass_style",
            listOf(getString(R.string.ui_glass_off),"Frosted","Liquid Glass"),
            valid={it!=2 || Build.VERSION.SDK_INT>=33},allowed=::master)
        val indicatorControls=column()
        for((label,key) in listOf(R.string.ui_blur_radius to "indicator_glass_blur",
            R.string.island_opacity to "indicator_glass_opacity",R.string.ui_glass_corner to "indicator_glass_corner"))
            add(indicatorControls,slider(getString(label),key,0,OpticalSettings.maximum(key),if(key.endsWith("blur"))"" else "%"))
        gate(indicatorControls){master() && number("indicator_glass_style")>0}
        add(indicator,indicatorControls)
        val indicatorLens=column()
        for((label,key) in listOf(R.string.ui_liquid_width to "indicator_glass_width",
            R.string.ui_liquid_distortion to "indicator_glass_distortion",R.string.ui_glass_dispersion to "indicator_glass_dispersion"))
            add(indicatorLens,slider(getString(label),key,0,OpticalSettings.maximum(key),"%"))
        gate(indicatorLens){master() && number("indicator_glass_style")==2}
        add(indicator,indicatorLens)
        add(body,card(indicator),top=8)
    }

    private fun settingsTransferSection(body:LinearLayout){
        fun picker(action:()->Unit){
            try {action()}catch(_:ActivityNotFoundException){
                Toast.makeText(this,R.string.ui_file_picker_error,Toast.LENGTH_SHORT).show()
            }
        }
        val rows=column()
        add(rows,preferenceLink(getString(R.string.ui_export_settings)){
            picker {exportSettings.launch("CherryPick_${BuildConfig.VERSION_NAME}_settings.json")}
        })
        divider(rows)
        add(rows,preferenceLink(getString(R.string.ui_import_settings)){
            picker {importSettings.launch(arrayOf("application/json","text/plain","application/octet-stream"))}
        })
        divider(rows)
        add(rows,preferenceLink(getString(R.string.ui_share_settings)){shareSettings()})
        add(body,card(rows),bottom=10)
    }

    private fun shareSettings(){
        executor.execute {
            runCatching {
                val directory=java.io.File(cacheDir,"shared-settings").apply {mkdirs()}
                val oldest=System.currentTimeMillis()-86_400_000L
                directory.listFiles()?.filter {it.isFile && it.lastModified()<oldest}?.forEach {it.delete()}
                val file=java.io.File.createTempFile("CPI-config-",".json",directory)
                file.writeText(SettingsTransfer.export(prefs))
                val uri=androidx.core.content.FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.logs",file)
                runOnUiThread {
                    if(isFinishing || isDestroyed)return@runOnUiThread
                    val intent=Intent(Intent.ACTION_SEND).apply {
                        type="application/json"
                        putExtra(Intent.EXTRA_STREAM,uri)
                        clipData=ClipData.newUri(contentResolver,file.name,uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(intent,getString(R.string.ui_share_settings)))
                }
            }.onFailure {
                runOnUiThread {if(!isFinishing && !isDestroyed)Toast.makeText(this,R.string.ui_file_picker_error,Toast.LENGTH_SHORT).show()}
            }
        }
    }

    private fun transferSettings(uri: Uri, importing: Boolean) {
        executor.execute {
            try {
                if (importing) {
                    val values = contentResolver.openInputStream(uri)?.use(SettingsTransfer::read)
                        ?: error("Cannot open preset")
                    SettingsTransfer.apply(prefs, values)
                    contentResolver.notifyChange(SettingsProvider.CONTENT_URI, null)
                } else {
                    val json = SettingsTransfer.export(prefs)
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("Cannot create preset")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        if (importing) refreshCatalogStyle()
                        Toast.makeText(this, if (importing) R.string.ui_settings_imported else R.string.ui_settings_exported, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) Toast.makeText(this, getString(R.string.ui_settings_transfer_error, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun folderSection(body:LinearLayout){
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
            glassStyleSelector(controls,folder=true)
            effectGroup(controls,getString(R.string.ui_blur),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS_ENABLED),
                getString(R.string.ui_blur_radius),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_RADIUS),max=50,allowed=::folderOwn,suffix="") {details ->
                if(bool(SettingsProvider.KEY_DEVELOPER_MODE))add(details,slider(getString(R.string.ui_blur_quality),FolderSettingsKeys.key(SettingsProvider.KEY_BLUR_QUALITY),0,100,"%"))
            }
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
            add(tone,accordion);add(controls,card(tone,true),top=8,bottom=4)
            add(content,controls)
        }
        val available={blur() && bool(SettingsProvider.KEY_FOLDER_BLUR_ENABLED)}
        effectGroup(body,getString(R.string.ui_large_folder_glass),"large_folder_glass_own",
            getString(R.string.ui_plate_opacity),"large_folder_glass_opacity",allowed=available){controls->
            val custom={available() && bool("large_folder_glass_own")}
            modeChoice(controls,R.string.ui_blur_style,"large_folder_glass_style",listOf("Frosted","Liquid Glass"),
                valid={it==0 || Build.VERSION.SDK_INT>=33},allowed=custom)
            for((label,key,max) in listOf(
                Triple(R.string.ui_liquid_width,"large_folder_glass_width",100),
                Triple(R.string.ui_liquid_distortion,"large_folder_glass_strength",300))){
                divider(controls)
                val setting=slider(getString(label),key,0,max,"%")
                gate(setting){custom() && number("large_folder_glass_style")==1}
                add(controls,setting)
            }
        }
    }

    private fun glassStyleSelector(parent:LinearLayout,folder:Boolean){
        fun key(base:String)=if(folder)FolderSettingsKeys.key(base)else base
        val style=key(SettingsProvider.KEY_BLUR_STYLE)
        val allowed=if(folder)::folderOwn else ::blur
        modeChoice(parent,R.string.ui_blur_style,style,listOf("Frosted","Liquid Glass"),
            valid={it==0 || Build.VERSION.SDK_INT>=33},allowed=allowed)
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
        val mode=text("",13f,true)
        val header=optionRow(getString(R.string.ui_coloring),mode)
        add(group,header)
        val details=column().apply { setPadding(dp(20),0,dp(20),dp(16)); visibility=if(number(SettingsProvider.KEY_COLOR_MODE)==1)View.VISIBLE else View.GONE }
        add(group,details)
        var shown=number(SettingsProvider.KEY_COLOR_MODE)==1
        fun updateMode() {
            val detailed=number(SettingsProvider.KEY_COLOR_MODE)==1
            mode.text=if(detailed)getString(R.string.ui_detailed) else getString(R.string.ui_system)
            header.contentDescription=getString(R.string.ui_color_mode_accessibility,mode.text)
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
            val band=ColorBand(this,index).apply {
                contentDescription=label
                onChange={ next,commit -> values[index]=next;paintPreview();if(commit)save(keys[index],next) }
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
        val header = row().apply {
            setPadding(dp(15),dp(8),dp(9),dp(8));minimumHeight=dp(58)
        }
        val titleArea = row()
        val labels = column()
        add(labels,text(title,17f,bold=true))
        titleArea.addView(labels, LinearLayout.LayoutParams(0,-2,1f))
        titleArea.contentDescription = "$title, ${if(expanded) getString(R.string.ui_collapse) else getString(R.string.ui_expand)}"
        header.addView(titleArea, LinearLayout.LayoutParams(0,-2,1f))
        val toggle = toggle(title,key).apply {onPressChanged={header.isPressed=it}}
        rowRipple(header)
        add(group, header)
        val children = column().apply { setPadding(0,0,0,dp(6)) }
        val accordion=SettingsAccordion(children,expanded)
        add(group,accordion)
        val sectionCard = card(group)
        gate(sectionCard, headerAllowed)
        gate(children, contentAllowed)
        if(subtitle.isNotBlank()){
            divider(children)
            add(children,text(subtitle,12f,true).apply {setPadding(dp(15),dp(8),dp(15),dp(8))})
        }
        content(children)
        val chevron = expandButton(expanded, title) { open ->
            onExpand(open)
            accordion.setOpen(open,android.animation.ValueAnimator.areAnimatorsEnabled())
            titleArea.contentDescription = "$title, ${if(open) getString(R.string.ui_collapse) else getString(R.string.ui_expand)}"
        }
        header.addView(actions(listOf(chevron,toggle)))
        titleArea.setOnClickListener { chevron.performClick() }
        titleArea.isFocusable = true
        add(parent, sectionCard, bottom = 12)
    }

    private fun switchRow(label: String, key: String, subtitle: String? = null,
        expanded: Boolean = false, onExpand: ((Boolean) -> Unit)? = null): LinearLayout {
        val wrapper=column()
        val line=row().apply {setPadding(dp(15),dp(8),dp(10),dp(8));minimumHeight=dp(55)}
        val labels=column()
        add(labels,text(label,16f))
        if(subtitle!=null)add(labels,text(subtitle,13f,true),4)
        line.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val control=toggle(label,key).apply {onPressChanged={line.isPressed=it}}
        val expand=onExpand?.let {expandButton(expanded,label,it)}
        line.addView(actions(listOfNotNull(expand,control)))
        add(wrapper,line)
        rowRipple(line)
        line.setOnClickListener { (expand ?: control).performClick() }
        return wrapper
    }

    private fun expandButton(expanded:Boolean,label:String,action:(Boolean)->Unit):View {
        var open=expanded
        val button=smallIcon(R.drawable.ic_expand_more,label){view->
            open=!open
            val angle=if(open)180f else 0f
            if(canAnimate())view.animate().rotation(angle).setDuration(240).setInterpolator(colorosEase).start()
            else view.rotation=angle
            view.contentDescription=label+", "+getString(if(open)R.string.ui_collapse else R.string.ui_expand)
            action(open)
        }
        button.background=null
        button.layoutParams=LinearLayout.LayoutParams(dp(39),dp(40)).apply {marginEnd=dp(4)}
        button.rotation=if(open)180f else 0f
        button.contentDescription=label+", "+getString(if(open)R.string.ui_collapse else R.string.ui_expand)
        return button
    }

    private fun addGlassPreview(body:LinearLayout){
        previewDraft.clear()
        glassPreview=null
        if(screen !in setOf(Screen.ICONS,Screen.FOLDERS,Screen.PHOTO,Screen.DOCK,Screen.LIVE,Screen.BANNERS,Screen.VOLUME))return
        val preview=GlassPreview(this,darkUi).apply {
            contentDescription=getString(R.string.ui_glass_preview)
        }
        glassPreview=preview
        loadPreviewScene(preview)
        val section=column().apply {setPadding(dp(12),dp(10),dp(12),dp(12))}
        add(section,text(getString(R.string.ui_glass_preview),14f,bold=true),bottom=8)
        section.addView(preview,LinearLayout.LayoutParams(-1,dp(if(screen==Screen.VOLUME)144 else 108)))
        add(body,card(section),bottom=8)
        updateGlassPreview()
        refreshers.add(::updateGlassPreview)
    }

    private fun loadPreviewScene(preview: GlassPreview) {
        val wallpaperId = runCatching {
            android.app.WallpaperManager.getInstance(this).getWallpaperId(android.app.WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()
        if (previewWallpaperId != wallpaperId) {
            previewWallpaper = null
            previewWallpaperId = wallpaperId
            previewLastAttempt = 0L
        }
        preview.setScene(previewWallpaper, previewIcons)
        if (previewLoading || (previewWallpaper != null && previewIcons.isNotEmpty())) return
        val now = SystemClock.uptimeMillis()
        if (previewLastAttempt != 0L && now - previewLastAttempt < 5000) return
        previewLastAttempt = now
        previewLoading = true
        val context = applicationContext
        executor.execute {
            val wallpaper = com.cosmod.launcherglass.util.PreviewWallpaper.read(context)
            val icons = runCatching { GlassPreview.systemIcons(context) }.getOrDefault(emptyList())
            runOnUiThread {
                previewLoading = false
                if (isDestroyed) return@runOnUiThread
                if (previewWallpaperId != wallpaperId) {
                    glassPreview?.let(::loadPreviewScene)
                    return@runOnUiThread
                }
                previewWallpaper = wallpaper
                previewIcons = icons
                glassPreview?.setScene(wallpaper, icons)
            }
        }
    }

    private fun updateGlassPreview(){
        val preview=glassPreview ?: return
        fun read(key:String)=previewDraft[key] ?: number(key)
        val material=when(screen){
            Screen.ICONS,Screen.FOLDERS->{
                val folders=screen==Screen.FOLDERS
                val own=folders && bool(FolderSettingsKeys.OWN)
                fun key(base:String)=if(own)FolderSettingsKeys.key(base) else base
                val tone=bool(key(SettingsProvider.KEY_BLUR_TONE_ENABLED))
                GlassPreview.Material(
                    master() && bool(if(folders)SettingsProvider.KEY_FOLDER_ICONS_BLUR_ENABLED else SettingsProvider.KEY_BLUR_ENABLED),
                    read(key(SettingsProvider.KEY_BLUR_STYLE))==1,
                    if(bool(key(SettingsProvider.KEY_BLUR_RADIUS_ENABLED)))read(key(SettingsProvider.KEY_BLUR_RADIUS))else 0,
                    if(tone)read(key(SettingsProvider.KEY_BG_OPACITY))else 0,
                    read(key(SettingsProvider.KEY_LIQUID_WIDTH)),read(key(SettingsProvider.KEY_LIQUID_DISTORTION)),
                    read(if(folders)GlassDispersionSettings.FOLDERS else GlassDispersionSettings.ICONS),28,
                    if(tone)read(key(SettingsProvider.KEY_BLUR_SATURATION))else 100,
                    if(tone)read(key(SettingsProvider.KEY_BLUR_VIBRANCY))else 0,
                    if(tone)read(key(SettingsProvider.KEY_BLUR_LIGHTENING))else 50,
                    tiles=if(folders)1 else 3)
            }
            Screen.DOCK->GlassPreview.Material(master() && read("dock_style")>0,read("dock_style")==2,
                read("dock_blur"),read("dock_opacity"),read("dock_liquid_width"),read("dock_liquid_distortion"),
                read(GlassDispersionSettings.DOCK),read("dock_corner"))
            Screen.PHOTO->GlassPreview.Material(master() && bool(SettingsProvider.KEY_PHOTO_WIDGET_BLUR),
                read(SettingsProvider.KEY_PHOTO_WIDGET_MATERIAL)==1,read("photo_widget_blur_radius"),
                read(SettingsProvider.KEY_PHOTO_WIDGET_OPACITY),read(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_WIDTH),
                read(SettingsProvider.KEY_PHOTO_WIDGET_LIQUID_DISTORTION),read(GlassDispersionSettings.PHOTOS),
                read(SettingsProvider.KEY_PHOTO_WIDGET_CORNER))
            Screen.VOLUME->GlassPreview.Material(master() && bool("volume_glass_enabled"),read("volume_glass_style")==1,
                read("volume_glass_blur"),read("volume_glass_opacity"),read("volume_glass_width"),read("volume_glass_distortion"),
                read("volume_glass_dispersion"),50,brightness=50+read("volume_glass_brightness")/2,vertical=true,
                rimStrength=read("volume_glass_rim"))
            Screen.LIVE,Screen.BANNERS->{
                val prefix=if(screen==Screen.LIVE)"island_glass_" else "banner_glass_"
                GlassPreview.Material(master() && bool(prefix+"enabled"),true,read(prefix+"blur"),
                    read(prefix+"opacity"),read(prefix+"width"),read(prefix+"distortion"),
                    read(if(screen==Screen.LIVE)GlassDispersionSettings.ALERTS else GlassDispersionSettings.BANNERS),
                    read(prefix+"corner"))
            }
            else->return
        }
        preview.show(material)
    }

    private fun slider(label:String,key:String,min:Int,max:Int,suffix:String,hint:String?=null,
        formatter:((Int)->String)?=null):LinearLayout {
        featureKeys.add(key)
        val inverse=key==SettingsProvider.KEY_PHOTO_WIDGET_OPACITY || key=="banner_plate_opacity"
        fun displayed()=if(inverse)min+max-number(key)else number(key)
        fun stored(value:Int)=if(inverse)min+max-value else value
        fun labelFor(value:Int)=formatter?.invoke(value) ?: value.toString()+suffix
        val group = column().apply { setPadding(dp(15),dp(8),dp(15),dp(3)) }
        val labels = row()
        labels.addView(text(label,14f), LinearLayout.LayoutParams(0,-2,1f))
        val value = text(labelFor(displayed()),14f,muted=true).apply { setPadding(dp(12),0,0,0) }
        labels.addView(value)
        add(group, labels)
        if(hint != null) add(group, text(hint,12f,true),6)
        val range=max-min
        val initial=displayed().coerceIn(min,max)-min
        val control=CouiSlider(this,darkUi,primary,label,range,initial)
        val hueTrack=key==WidgetSettings.HUE || key=="banner_plate_hue"
        val lightnessTrack=key==WidgetSettings.LIGHTNESS || key=="banner_plate_lightness"
        fun updatePalette(){
            if(hueTrack)control.palette=HUE_TRACK
            if(lightnessTrack){
                val color=if(key==WidgetSettings.LIGHTNESS)Color.HSVToColor(floatArrayOf(number(WidgetSettings.HUE).toFloat(),.32f,.76f))
                    else Color.HSVToColor(floatArrayOf(number("banner_plate_hue").toFloat(),number("banner_plate_saturation")/100f,
                        number("banner_plate_brightness")/100f))
                control.palette=intArrayOf(Color.BLACK,color,Color.WHITE)
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
            value.text=labelFor(progress+min)
            if(glassPreview!=null){
                previewDraft[key]=stored(progress+min)
                updateGlassPreview()
            }
        }
        refreshers.add {
            if(!control.adjusting){
                control.progress=displayed().coerceIn(min,max)-min
                value.text=labelFor(control.progress+min)
            }
        }
        add(group, control, 2)
        return group
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

    private fun buildMotion(body: LinearLayout) {
        section(body,getString(R.string.ui_tilt_effect),getString(R.string.app_tilt_hint),"app_tilt_enabled",
            appTiltExpanded,{appTiltExpanded=it},::master,{master() && bool("app_tilt_enabled")}) { details ->
            add(details,switchRow(getString(R.string.app_tilt_open),"app_tilt_open"))
            add(details,switchRow(getString(R.string.app_tilt_close),"app_tilt_close"))
            add(details,slider(getString(R.string.app_tilt_strength),"app_tilt_reference_strength",0,200,"%"))
            add(details,slider(getString(R.string.ui_tilt_open_amount),"app_tilt_enter",0,150,"%"))
            add(details,slider(getString(R.string.ui_tilt_close_amount),"app_tilt_exit",0,150,"%"))
            add(details,slider(getString(R.string.ui_tilt_perspective),"app_tilt_reference_perspective",0,150,"%"))
            add(details,slider(getString(R.string.ui_tilt_yaw),"app_tilt_yaw",0,150,"%"))
            add(details,slider(getString(R.string.ui_tilt_center),"app_tilt_reference_center",0,100,"%"))
            add(details,slider(getString(R.string.ui_tilt_smoothness),"app_tilt_smoothness",0,100,"%"))
        }
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
        add(budgets,slider(getString(R.string.ui_performance_resolution),"performance_resolution",50,100,"%"))
        val alerts=column().apply {setPadding(dp(20),0,dp(20),0)}
        modeChoice(alerts,R.string.ui_performance_alert_rate,"performance_alert_rate",listOf(getString(R.string.ui_profile_auto),"30","45","60","120")){true}
        add(budgets,alerts)
        divider(budgets)
        add(budgets,switchRow(getString(R.string.ui_adaptive_alert_capture),"performance_adaptive_alerts",
            getString(R.string.ui_adaptive_alert_capture_hint)))
        add(budgets,switchRow(getString(R.string.ui_transition_live_glass),"performance_live_transition_glass",
            getString(R.string.ui_transition_live_glass_hint)))
        add(budgets,switchRow(getString(R.string.ui_ltpo_idle),"performance_ltpo_idle",getString(R.string.ui_ltpo_idle_hint)))
        add(budgets,switchRow(getString(R.string.ui_165hz_pacing),"performance_high_refresh_pacing",
            getString(R.string.ui_165hz_pacing_hint)))
        add(budgets,switchRow(getString(R.string.ui_performance_drawer),"performance_light_drawer",
            getString(R.string.ui_performance_drawer_hint)))
        add(body,card(budgets),bottom=20)
        add(body,card(text(getString(R.string.ui_blur_update),14f,true).apply {
            setPadding(dp(15),dp(10),dp(15),dp(10))
        }),bottom=12)
        val modes = column().apply { setPadding(dp(20),dp(20),dp(20),dp(12)) }
        val modeHeader = row()
        modeHeader.addView(text(getString(R.string.ui_blur_mode),18f,bold=true),LinearLayout.LayoutParams(0,-2,1f))
        add(modes,modeHeader,bottom=12)
        modeChoice(modes,R.string.ui_blur_mode,SettingsProvider.KEY_BLUR_MODE,
            listOf(getString(R.string.ui_static_blur),getString(R.string.ui_live_blur)),allowed=::blur)
        val modeHint=text("",12f,true)
        fun updateModeHint(){
            modeHint.setText(if(number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE)
                R.string.ui_live_blur_description else R.string.ui_static_blur_description)
        }
        updateModeHint()
        refreshers.add(::updateModeHint)
        add(modes,modeHint,bottom=6)
        val modeCard=card(modes);gate(modeCard,::blur);add(body,modeCard,bottom=12)
        val idle=card(switchRow(getString(R.string.ui_energy_saving),SettingsProvider.KEY_IDLE_PAUSE,
            getString(R.string.ui_energy_saving_description)))
        gate(idle) { blur() && number(SettingsProvider.KEY_BLUR_MODE)==SettingsProvider.BLUR_MODE_LIVE }
        add(body,idle,bottom=24)
    }

    private fun nightMode(theme:Int)=when(theme){
        1->androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        2->androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        else->androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun updateSystemBars(){
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
        backSnapshot=null
        showPage()
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
        val lspActive=scopeState?.enabled ?: isModuleActive()
        val missing=lspActive && scopeState?.launcherSelected==false
        val loadedLauncher=prefs.getString("runtime_launcher_version",null)==BuildConfig.VERSION_NAME
        val loadedSystemUi=prefs.getString("runtime_systemui_version",null)==BuildConfig.VERSION_NAME
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

    private fun closeRestartConfirmation(immediate:Boolean=false){
        val overlay=restartOverlay ?: return
        restartOverlay=null
        val layer=pageLayer
        fun remove(){
            page.removeView(overlay)
            if(restartOverlay==null && menuOverlay==null)
                layer?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        val shell=restartShell
        restartShell=null
        if(immediate || !canAnimate() || shell==null)remove()
        else {
            overlay.animate().alpha(0f).setDuration(190).start()
            shell.closeSheet(::remove)
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

    private fun confirmRestart(titleId:Int,messageId:Int,onConfirm:()->Unit){
        val layer=pageLayer ?: return
        val body=headerBody ?: return
        val scroll=currentScroll ?: return
        if(restartOverlay!=null || !page.isAttachedToWindow || page.width<=0 || page.height<=topInset)return
        closeChoicePopup(immediate=true)
        val overlay=FrameLayout(this).apply {
            clipChildren=false;clipToPadding=false
            elevation=dp(22).toFloat()
            isFocusableInTouchMode=true
            setBackgroundColor(if(darkUi)0x57000000 else 0x38000000)
            setOnClickListener {closeRestartConfirmation()}
        }
        val corner=28
        val sheetColor=if(darkUi)0xff28282a.toInt() else panel
        val shell=OnextPopupShell(this).apply {
            background=panelShape(sheetColor,corner)
            clipChildren=false;clipToPadding=false
            isClickable=true
            elevation=dp(12).toFloat()
        }
        val skin=FrameLayout(this).apply {
            background=panelShape(sheetColor,corner)
            clipToOutline=true
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        shell.addView(skin,FrameLayout.LayoutParams(-1,-1))
        val effects=bool("coloros_ui_effects")
        val touchLayer=if(effects)OnextSearchFrame(this,shell){canAnimate()}.apply {
            clipChildren=false;clipToPadding=false
            addView(shell,FrameLayout.LayoutParams(-1,-1))
        }else null
        val blur=if(effects)ColorosHeaderBlur(this,body,background,topInset,atmosphere,shell,touchLayer).apply {
            showScroll(scroll.scrollY)
            skin.addView(this,FrameLayout.LayoutParams(-1,-1))
        }else null
        skin.addView(CouiSurface(this,darkUi,primary,withAlpha(sheetColor,if(effects)if(darkUi)218 else 232 else 255),
            corner),FrameLayout.LayoutParams(-1,-1))
        touchLayer?.let {
            val light=OnextSpotlightView(this,darkUi,dp(corner).toFloat(),.34f)
            skin.addView(light,FrameLayout.LayoutParams(-1,-1))
            it.spotlight=light
        }
        val content=column().apply {
            setPadding(dp(16),dp(19),dp(16),dp(14))
            clipChildren=false;clipToPadding=false
        }
        val title=text(getString(titleId),19f,bold=true).apply {gravity=Gravity.CENTER}
        ViewCompat.setAccessibilityHeading(title,true)
        add(content,title,bottom=9)
        val message=ScrollView(this).apply {
            addView(text(getString(messageId),14f,true).apply {
                setPadding(dp(6),0,dp(6),0)
                setLineSpacing(dp(2).toFloat(),1f)
            })
        }
        content.addView(message,LinearLayout.LayoutParams(-1,-2,1f).apply {bottomMargin=dp(16)})
        val buttonTint=if(darkUi)0xac4b4c51.toInt() else 0xb5eeeeef.toInt()
        val buttons=GlassActionRow(this,buttonTint,darkUi)
        if(blur!=null){
            buttons.drawBackdrop={canvas->
                val save=canvas.save()
                canvas.translate(-(content.left+buttons.left).toFloat(),-(content.top+buttons.top).toFloat())
                blur.drawBackdrop(canvas)
                canvas.restoreToCount(save)
            }
            shell.onMaterialMoved={blur.invalidate();buttons.invalidate()}
        }
        val cancel=button(stretchy=true).apply {
            setText(android.R.string.cancel)
            textSize=13f
            setPadding(dp(5),0,dp(5),0)
            setTextColor(onSurface)
            background=null
            shareGlass {buttons.invalidate()}
            setOnClickListener {haptic(it);closeRestartConfirmation()}
        }
        val confirm=button(stretchy=true).apply {
            setText(R.string.ui_restart_systemui_action)
            textSize=13f
            setPadding(dp(5),0,dp(5),0)
            setTextColor(if(darkUi)0xffff9c97.toInt() else 0xffc13331.toInt())
            background=null
            shareGlass {buttons.invalidate()}
            setOnClickListener {
                haptic(it)
                closeRestartConfirmation()
                onConfirm()
            }
        }
        buttons.addView(cancel,LinearLayout.LayoutParams(0,dp(44),1f).apply {marginEnd=dp(7)})
        buttons.addView(confirm,LinearLayout.LayoutParams(0,dp(44),1f))
        add(content,buttons)
        touchLayer?.dragExclusion=buttons
        val width=minOf(dp(410),page.width-dp(28)).coerceAtLeast(1)
        val heightLimit=(page.height-topInset-dp(22)).coerceAtLeast(1)
        shell.addView(content,FrameLayout.LayoutParams(-1,-1))
        val sheet=touchLayer ?: shell
        val sheetLayout=FrameLayout.LayoutParams(width,heightLimit,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin=dp(10)
        }
        overlay.addView(sheet,sheetLayout)
        restartOverlay=overlay
        restartShell=shell
        layer.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ViewCompat.setAccessibilityPaneTitle(overlay,getString(titleId))
        val animate=canAnimate()
        if(animate)overlay.alpha=0f
        page.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        // ComposeView needs the window's composition context before its first measure.
        // Measure only the content so the decorative layers cannot inflate the sheet.
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightLimit,View.MeasureSpec.AT_MOST))
        sheetLayout.height=content.measuredHeight
        sheet.layoutParams=sheetLayout
        overlay.requestFocus()
        if(animate)shell.doOnLayout {
            if(restartShell===shell){
                overlay.animate().alpha(1f).setDuration(210).setInterpolator(colorosEase).start()
                shell.openSheet()
            }
        }
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
    /** Replaced by the LSPosed activation hook. */
    private companion object {
        val HUE_TRACK=IntArray(7){Color.HSVToColor(floatArrayOf((it*60%360).toFloat(),.86f,.96f))}
    }

    @Keep fun isModuleActive(): Boolean = false
}
