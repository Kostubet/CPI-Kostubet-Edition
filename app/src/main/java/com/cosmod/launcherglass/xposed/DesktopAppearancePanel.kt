package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
import android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
import android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
import android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.DesktopAppearance
import com.cosmod.launcherglass.ui.DesktopAppearance.Mode
import com.cosmod.launcherglass.ui.DesktopAppearance.Theme
import com.cosmod.launcherglass.ui.SettingsProvider.Companion as Keys
import com.cosmod.launcherglass.ui.onextbox.ColorOsPopupMaterialView
import com.cosmod.launcherglass.ui.onextbox.applyOplusPopupBlur
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.PreviewIcons
import com.cosmod.launcherglass.util.WallpaperHelper
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

internal object DesktopAppearancePanel {
    private val writes by lazy { Executors.newSingleThreadExecutor() }
    private val wallpapers by lazy { Executors.newSingleThreadExecutor() }
    private val previewIcons by lazy { Executors.newSingleThreadExecutor() }
    private val enter = PathInterpolator(.24f, .65f, .22f, 1f)
    private val exit = PathInterpolator(.35f, .02f, .35f, 1f)
    private val modeTransition = PathInterpolator(.24f, .06f, .24f, 1f)
    private const val MODE_TRANSITION_MS = 365L

    class Contract(loader: ClassLoader) {
        private val seekType = loader.loadClass("com.coui.appcompat.seekbar.COUISeekBar")
        val seek = seekType.getConstructor(Context::class.java)
        val max = seekType.getMethod("setMax", Int::class.javaPrimitiveType)
        val progress = seekType.getMethod("setProgress", Int::class.javaPrimitiveType)
        val start = runCatching { seekType.getMethod("getProgressStart") }.getOrNull()
        val length = runCatching { seekType.getMethod("getSeekBarLength") }.getOrNull()
        val listenerType: Class<*> = seekType.declaredClasses.single {
            it.isInterface && it.declaredMethods.any { method ->
                method.parameterTypes.contentEquals(arrayOf(seekType, Int::class.javaPrimitiveType))
            }
        }
        val listener = seekType.getMethod("setOnSeekBarChangeListener", listenerType)
        val segments = runCatching { AppearanceSegmentContract(loader) }.getOrNull()
        val button = runCatching { AppearanceButtonContract(loader) }.getOrNull()
    }

    fun contract(loader: ClassLoader) = Contract(loader)

    fun open(host: Activity, native: Contract): Dialog? {
        val stored = host.contentResolver.call(Keys.CONTENT_URI, Keys.METHOD_GET_ALL, null, null) ?: return null
        if (!stored.getBoolean(Keys.KEY_ENABLED, true)) return null
        var backAction: () -> Unit = {}
        val dialog = object : Dialog(host) {
            @Deprecated("Used by the host dialog to dispatch Back on older firmware.")
            override fun onBackPressed() = backAction()
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val labels = host.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        val systemDark = IconPalette.isSystemDark(host)
        var profile = DesktopAppearance.read(stored, systemDark)
        var darken = stored.getInt(DesktopAppearance.DIM_WALLPAPER) == 1
        var large = stored.getInt(DesktopAppearance.LARGE_ICONS) == 1
        var wallpaper: Bitmap? = null
        val handler = Handler(Looper.getMainLooper())
        val pending = Bundle()
        val app = host.applicationContext
        val flush = Runnable {
            if (pending.isEmpty) return@Runnable
            val change = Bundle(pending)
            pending.clear()
            writes.execute {
                runCatching {
                    app.contentResolver.call(Keys.CONTENT_URI, Keys.METHOD_SET_DESKTOP_APPEARANCE, null, change)
                }.onFailure {
                    GlassLogger.limited("DesktopAppearance", "save") { "Could not save appearance: ${it.message}" }
                }
            }
        }
        fun save(vararg values: Pair<String, Int>, delay: Long = 72L) {
            for ((key, value) in values) pending.putInt(key, value)
            handler.removeCallbacks(flush)
            handler.postDelayed(flush, delay)
        }

        fun dp(value: Int) = DesktopAppearanceToolbar.dp(host, value)
        val density = host.resources.displayMetrics.density
        val foreground = if (systemDark) Color.WHITE else 0xff17181a.toInt()
        val contrast = ColorUtils.setAlphaComponent(foreground, 190)
        val accent = android.util.TypedValue().let { value ->
            if (host.theme.resolveAttribute(android.R.attr.colorAccent, value, true) && Color.alpha(value.data) > 0)
                value.data else 0xff1677ff.toInt()
        }
        val root = FrameLayout(host)
        val scroll = ScrollView(host).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val radius = 34f
        val material = ColorOsPopupMaterialView(host, systemDark, radius).apply {
            setBackgroundColor(if (systemDark) 0xe624252a.toInt() else 0xebf6f6f9.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            doOnPreDraw { applyOplusPopupBlur(this, systemDark, density, radius) }
        }
        var heightMotion: ValueAnimator? = null
        var swipe: (Float, Boolean) -> Unit = { _, _ -> }
        val card = object : FrameLayout(host) {
            private val slop = ViewConfiguration.get(host).scaledTouchSlop
            private var startX = 0f
            private var startY = 0f
            private var candidate = false

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                measureChild(scroll, widthMeasureSpec, heightMeasureSpec)
                setMeasuredDimension(scroll.measuredWidth, scroll.measuredHeight)
                material.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
            }

            private fun downwardDistance(event: MotionEvent) = (event.rawY - startY - slop).coerceAtLeast(0f)

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        candidate = !scroll.canScrollVertically(-1)
                    }
                    MotionEvent.ACTION_MOVE -> if (candidate) {
                        val dx = abs(event.rawX - startX)
                        val dy = event.rawY - startY
                        if (dy > slop && dy > dx * 1.3f) return true
                        if (dx > slop || dy < -slop) candidate = false
                    }
                }
                return super.onInterceptTouchEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> if (candidate) {
                        val distance = downwardDistance(event)
                        if (distance > 0 && distance + slop > abs(event.rawX - startX) * 1.3f) swipe(distance, false)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (candidate) {
                        swipe(if (event.actionMasked == MotionEvent.ACTION_UP) downwardDistance(event) else 0f, true)
                        candidate = false
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply {
            isClickable = true
            clipToOutline = true
            outlineProvider = material.outlineProvider
        }
        fun label(text: String, size: Float) = TextView(host).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(foreground)
        }
        fun ripple(radius: Int, color: Int) = RippleDrawable(ColorStateList.valueOf(color), null,
            GradientDrawable().apply {
                cornerRadius = dp(radius).toFloat()
                setColor(Color.WHITE)
            })
        fun roundButton(icon: Int, title: Int, action: () -> Unit): View {
            val systemButton = runCatching { native.button?.create(host) }.getOrNull()
            if (systemButton != null) return systemButton.apply {
                setImageDrawable(labels.getDrawable(icon))
                scaleType = ImageView.ScaleType.FIT_CENTER
                val inset = dp(11)
                setPadding(inset, inset, inset, inset)
                imageTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                    intArrayOf(accent, foreground))
                contentDescription = labels.getString(title)
                setOnClickListener { action() }
                isFocusable = true
            }
            val surface = ColorOsPopupMaterialView(host, systemDark, 18f)
            fun update(selected: Boolean) {
                surface.setBackgroundColor(ColorUtils.setAlphaComponent(
                    if (selected) accent else foreground, if (selected) 102 else 28))
            }
            update(false)
            val glyph = ImageView(host).apply {
                setImageDrawable(labels.getDrawable(icon))
                setColorFilter(foreground)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            return object : FrameLayout(host) {
                override fun setSelected(selected: Boolean) {
                    super.setSelected(selected)
                    update(selected)
                }
            }.apply {
                addView(surface, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
                addView(glyph, FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER))
                this.foreground = ripple(22, ColorUtils.setAlphaComponent(foreground, 40))
                contentDescription = labels.getString(title)
                setOnClickListener { action() }
                isFocusable = true
            }
        }
        val body = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(12))
        }
        val header = FrameLayout(host)
        header.addView(label(labels.getString(R.string.desktop_appearance_title), 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            isAccessibilityHeading = true
        }, FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(90); marginEnd = dp(90) })
        lateinit var shadeButton: View
        shadeButton = roundButton(R.drawable.ic_nav_light, R.string.desktop_appearance_dim_wallpaper) {
            darken = !darken
            shadeButton.isSelected = darken
            save(DesktopAppearance.DIM_WALLPAPER to if (darken) 1 else 0)
        }
        shadeButton.isSelected = darken
        header.addView(shadeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        lateinit var sizeButton: View
        sizeButton = roundButton(R.drawable.ic_desktop_icon_size, R.string.desktop_appearance_icon_size_toggle) {
            large = !large
            sizeButton.isSelected = large
            save(DesktopAppearance.LARGE_ICONS to if (large) 1 else 0, delay = 145L)
        }
        sizeButton.isSelected = large
        header.addView(sizeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(44)
        })
        body.addView(header, LinearLayout.LayoutParams(-1, dp(47)))

        val samples = linkedMapOf<Mode, Choice>()
        val colorControls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        val colorReveal = FrameLayout(host)
        val themeControls = FrameLayout(host)
        val colorHeight = dp(36) * 2 + dp(5)
        val themeHeight = dp(41) + dp(4)
        lateinit var fullTheme: AppearanceSegment
        lateinit var darkTheme: AppearanceSegment
        var activeTheme: AppearanceSegment? = null
        var themeMotion: ValueAnimator? = null

        fun cancelThemeMotion() {
            themeMotion?.removeAllListeners()
            themeMotion?.cancel()
            themeMotion = null
        }
        fun updateSamples(only: Mode? = null) {
            for ((mode, choice) in samples) {
                if (only != null && mode != only) continue
                val selected = mode == profile.mode
                if (choice.group.isSelected != selected) choice.group.isSelected = selected
                val textColor = if (selected) accent else contrast
                if (choice.caption.currentTextColor != textColor) choice.caption.setTextColor(textColor)
                val dark = mode == Mode.DARK || profile.selectMode(mode).dark(systemDark)
                val plate = when (mode) {
                    Mode.DARK -> 0xff23262d.toInt()
                    Mode.CLEAR -> if (dark) 0x7823262b else 0x39ffffff
                    Mode.TINTED -> Color.HSVToColor(if (dark) 182 else 153,
                        floatArrayOf(profile.hue.toFloat(), profile.saturation / 100f, if (dark) .40f else .85f))
                    else -> Color.TRANSPARENT
                }
                val glyph = if (mode == Mode.TINTED) Color.HSVToColor(floatArrayOf(profile.hue.toFloat(),
                    profile.saturation / 180f, if (dark) .95f else 1f)) else 0xffecf1f5.toInt()
                choice.sample.setPlate(plate, glyph, selected)
            }
        }
        fun updateTheme(choices: List<Theme>) {
            if (choices.isEmpty()) return
            val incoming = if (choices.size == 2) darkTheme else fullTheme
            incoming.select(choices.indexOf(profile.theme).coerceAtLeast(0))
            if (activeTheme === incoming) return

            val outgoing = activeTheme?.view
            val view = incoming.view
            cancelThemeMotion()
            activeTheme = incoming
            val animate = outgoing != null && themeControls.height > 0 && themeControls.isShown &&
                ValueAnimator.areAnimatorsEnabled()
            outgoing?.isEnabled = false
            outgoing?.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.isEnabled = true
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            if (view.visibility != View.VISIBLE) view.alpha = if (animate) 0f else 1f
            view.visibility = View.VISIBLE
            view.bringToFront()

            fun settle() {
                outgoing?.visibility = View.GONE
                outgoing?.alpha = 0f
                view.alpha = 1f
            }
            if (outgoing == null || !animate) {
                settle()
                return
            }

            val incomingAlpha = view.alpha
            val outgoingAlpha = outgoing.alpha
            themeMotion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 270L
                interpolator = modeTransition
                addUpdateListener {
                    val fraction = it.animatedFraction
                    view.alpha = incomingAlpha + (1f - incomingAlpha) * fraction
                    outgoing.alpha = outgoingAlpha * (1f - fraction)
                }
                doOnEnd { settle() }
                start()
            }
        }

        fun updateLayout() {
            heightMotion?.cancel()
            val choices = profile.themeChoices
            updateTheme(choices)
            val oldColor = colorReveal.layoutParams.height
            val oldTheme = themeControls.layoutParams.height
            val targetColor = if (profile.hasTintControls) colorHeight else 0
            val targetTheme = if (choices.isNotEmpty()) themeHeight else 0
            fun reveal(view: View, height: Int, fullHeight: Int) {
                val params = view.layoutParams
                if (params.height != height) {
                    params.height = height
                    view.layoutParams = params
                }
                view.alpha = (height.toFloat() / fullHeight).coerceIn(0f, 1f)
                val visibility = if (height == 0) View.INVISIBLE else View.VISIBLE
                if (view.visibility != visibility) view.visibility = visibility
            }
            if (!card.isLaidOut || !ValueAnimator.areAnimatorsEnabled() ||
                (oldColor == targetColor && oldTheme == targetTheme)) {
                reveal(colorReveal, targetColor, colorHeight)
                reveal(themeControls, targetTheme, themeHeight)
                return
            }
            heightMotion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = MODE_TRANSITION_MS
                interpolator = modeTransition
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    reveal(colorReveal, (oldColor + (targetColor - oldColor) * fraction).roundToInt(), colorHeight)
                    reveal(themeControls, (oldTheme + (targetTheme - oldTheme) * fraction).roundToInt(), themeHeight)
                }
                start()
            }
        }
        val modes = LinearLayout(host).apply {
            isBaselineAligned = false
            setPadding(0, dp(5), 0, 0)
        }
        for (mode in listOf(Mode.STOCK, Mode.DARK, Mode.CLEAR, Mode.TINTED)) {
            val sample = AppearanceSample(host, null, mode, accent)
            val caption = label(labels.getString(mode.label), 11.5f).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val item = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                this.foreground = ripple(16, ColorUtils.setAlphaComponent(accent, 32))
                contentDescription = caption.text
                isFocusable = true
                setOnClickListener {
                    if (mode != profile.mode) {
                        profile = profile.selectMode(mode)
                        updateLayout()
                        updateSamples()
                        save(DesktopAppearance.MODE to mode.ordinal, DesktopAppearance.THEME to profile.theme.ordinal, delay = MODE_TRANSITION_MS)
                    }
                }
                addView(sample, LinearLayout.LayoutParams(dp(49), dp(49)))
                addView(caption, LinearLayout.LayoutParams(-1, dp(28)))
            }
            samples[mode] = Choice(item, caption, sample)
            modes.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(modes)

        fun segment(choices: List<Theme>, titles: List<String>): AppearanceSegment {
            val change: (Int) -> Unit = { index ->
                val theme = choices[index]
                if (choices.size == profile.themeChoices.size && theme != profile.theme) {
                    profile = profile.copy(theme = theme)
                    updateSamples()
                    save(DesktopAppearance.THEME to theme.ordinal, delay = 160L)
                }
            }
            runCatching { native.segments?.create(host, titles, change) }.getOrNull()?.let { return it }
            val row = LinearLayout(host).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(ColorUtils.setAlphaComponent(foreground, 18))
                }
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            fun highlight(index: Int) {
                for (i in 0 until row.childCount) {
                    val cell = row.getChildAt(i)
                    cell.isSelected = i == index
                    cell.backgroundTintList = ColorStateList.valueOf(
                        if (i == index) ColorUtils.setAlphaComponent(foreground, 44) else Color.TRANSPARENT)
                }
            }
            titles.forEachIndexed { i, title ->
                row.addView(label(title, 13f).apply {
                    isFocusable = true
                    this.foreground = ripple(18, ColorUtils.setAlphaComponent(accent, 28))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(18).toFloat()
                        setColor(Color.WHITE)
                    }
                    setOnClickListener {
                        change(i)
                        highlight(choices.indexOf(profile.theme))
                    }
                }, LinearLayout.LayoutParams(0, -1, 1f))
            }
            return AppearanceSegment(row, ::highlight)
        }
        val themeOrder = listOf(Theme.LIGHT, Theme.DARK, Theme.AUTO)
        fullTheme = segment(themeOrder, themeOrder.map { labels.getString(it.label) })
        darkTheme = segment(listOf(Theme.DARK, Theme.AUTO),
            listOf(labels.getString(R.string.desktop_appearance_dark_always), labels.getString(Theme.AUTO.label)))
        for (segment in listOf(fullTheme, darkTheme)) {
            segment.view.visibility = View.GONE
            themeControls.addView(segment.view, FrameLayout.LayoutParams(-1, dp(41)).apply { topMargin = dp(4) })
        }

        fun slider(title: Int, range: Int, value: Int, key: String, change: (Int) -> Unit): ColorSlider {
            val input = native.seek.newInstance(host) as View
            input.contentDescription = labels.getString(title)
            input.alpha = .01f
            native.max.invoke(input, range)
            native.progress.invoke(input, value)
            val track = AppearanceSpectrum(host, range).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                position = value
            }
            var dragging = false
            var dirty = false
            fun finishDrag() {
                if (dirty) save(key to track.position)
                dirty = false
            }
            val callback = Proxy.newProxyInstance(native.listenerType.classLoader, arrayOf(native.listenerType)) { proxy, method, args ->
                when {
                    method.name == "hashCode" -> System.identityHashCode(proxy)
                    method.name == "equals" -> proxy === args?.getOrNull(0)
                    method.name == "toString" -> "CPI appearance slider listener"
                    args?.size == 2 && args[1] is Int -> {
                        val next = (args[1] as Int).coerceIn(0, range)
                        if (next != track.position) {
                            track.position = next
                            change(next)
                            if (dragging) dirty = true else save(key to next)
                        }
                        null
                    }
                    else -> null
                }
            }
            native.listener.invoke(input, callback)
            input.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> dragging = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        input.post {
                            dragging = false
                            finishDrag()
                        }
                    }
                }
                false
            }
            val holder = FrameLayout(host)
            holder.addView(input, FrameLayout.LayoutParams(-1, -1))
            holder.addView(track, FrameLayout.LayoutParams(-1, -1))
            holder.addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
                if (r - l != oldR - oldL) input.doOnLayout {
                    val start = runCatching { (native.start?.invoke(input) as? Number)?.toFloat() }.getOrNull()
                    val length = runCatching { (native.length?.invoke(input) as? Number)?.toFloat() }.getOrNull()
                    if (start != null && length != null) track.setInputBounds(start, length)
                }
            }
            colorControls.addView(holder, LinearLayout.LayoutParams(-1, dp(36)))
            return ColorSlider(input, track)
        }
        lateinit var saturation: ColorSlider
        fun saturationColor(strength: Float) = Color.HSVToColor(floatArrayOf(profile.hue.toFloat(), strength, 1f))
        fun updateTracks() {
            saturation.track.setHandle(saturationColor(profile.saturation / 100f))
            saturation.track.setColors(intArrayOf(Color.WHITE, saturationColor(1f)))
        }
        var tintUpdatePending = false
        fun updateTintOnFrame() {
            if (tintUpdatePending) return
            tintUpdatePending = true
            card.postOnAnimation {
                tintUpdatePending = false
                updateTracks()
                updateSamples(Mode.TINTED)
            }
        }
        val hue = slider(R.string.desktop_appearance_hue, 360, profile.hue, DesktopAppearance.HUE) {
            profile = profile.copy(hue = it)
            updateTintOnFrame()
        }
        hue.track.setColors(intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED))
        saturation = slider(R.string.desktop_appearance_saturation, 100, profile.saturation, DesktopAppearance.SATURATION) {
            profile = profile.copy(saturation = it)
            updateTintOnFrame()
        }
        updateTracks()
        colorReveal.addView(colorControls, FrameLayout.LayoutParams(-1, dp(36) * 2).apply { topMargin = dp(5) })
        body.addView(colorReveal, LinearLayout.LayoutParams(-1, 0))
        body.addView(themeControls, LinearLayout.LayoutParams(-1, 0))

        val pickerButton = roundButton(R.drawable.ic_wallpaper_sample, R.string.desktop_appearance_pick_color) {
            val image = wallpaper?.takeUnless { it.isRecycled }
            if (image == null) {
                Toast.makeText(host, labels.getString(R.string.desktop_appearance_wallpaper_unavailable), Toast.LENGTH_SHORT).show()
            } else {
                val closeAppearance = backAction
                lateinit var picker: DesktopWallpaperColorPicker
                fun hidePicker() {
                    if (!picker.isEnabled) return
                    picker.isEnabled = false
                    backAction = closeAppearance
                    card.visibility = View.VISIBLE
                    if (!ValueAnimator.areAnimatorsEnabled()) root.removeView(picker)
                    else picker.animate().alpha(0f).setDuration(220L).setInterpolator(enter)
                        .withEndAction { root.removeView(picker) }.start()
                }
                picker = DesktopWallpaperColorPicker(host, image,
                    labels.getString(R.string.desktop_appearance_pick_color)) { color ->
                    val hsv = FloatArray(3)
                    Color.colorToHSV(color, hsv)
                    val pickedHue = if (hsv[1] == 0f) profile.hue else hsv[0].roundToInt().coerceIn(0, 360)
                    val pickedSaturation = (hsv[1] * 100f).roundToInt()
                    profile = profile.selectMode(Mode.TINTED).copy(hue = pickedHue, saturation = pickedSaturation)
                    hue.track.position = pickedHue
                    saturation.track.position = pickedSaturation
                    native.progress.invoke(hue.input, pickedHue)
                    native.progress.invoke(saturation.input, pickedSaturation)
                    updateTracks()
                    updateSamples()
                    updateLayout()
                    save(DesktopAppearance.MODE to profile.mode.ordinal, DesktopAppearance.THEME to profile.theme.ordinal,
                        DesktopAppearance.HUE to pickedHue, DesktopAppearance.SATURATION to pickedSaturation)
                    hidePicker()
                }
                card.visibility = View.INVISIBLE
                backAction = ::hidePicker
                root.addView(picker, FrameLayout.LayoutParams(-1, -1))
                if (ValueAnimator.areAnimatorsEnabled()) {
                    picker.alpha = 0f
                    picker.animate().alpha(1f).setDuration(220L).setInterpolator(enter).start()
                }
            }
        }
        header.addView(pickerButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        updateLayout()
        updateSamples()
        scroll.addView(body)
        card.addView(material, FrameLayout.LayoutParams(-1, -1))
        card.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(8)
        })
        var motion: ValueAnimator? = null
        var closing = false
        var swiping = false
        fun cancelMotion() {
            motion?.removeAllListeners()
            motion?.cancel()
        }
        fun hiddenOffset() = (card.height + (card.layoutParams as FrameLayout.LayoutParams).bottomMargin).toFloat()
        fun moveCard(open: Boolean) {
            cancelMotion()
            val target = if (open) 0f else hiddenOffset()
            if (!ValueAnimator.areAnimatorsEnabled() || !card.isLaidOut) {
                card.translationY = target
                if (!open) dialog.dismiss()
                return
            }
            motion = ValueAnimator.ofFloat(card.translationY, target).apply {
                duration = if (open) 375L else 320L
                interpolator = if (open) enter else exit
                addUpdateListener { card.translationY = it.animatedValue as Float }
                doOnEnd { if (!open) dialog.dismiss() }
                start()
            }
        }
        backAction = {
            if (!closing) {
                closing = true
                moveCard(false)
            }
        }
        swipe = { distance, finished ->
            if (!closing) {
                if (!finished) {
                    if (!swiping) {
                        cancelMotion()
                        swiping = true
                    }
                    card.translationY = distance
                } else if (swiping) {
                    swiping = false
                    if (distance >= minOf(dp(75).toFloat(), card.height * .35f)) backAction()
                    else moveCard(true)
                }
            }
        }
        root.setOnClickListener { backAction() }
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        dialog.setContentView(root)
        val window = requireNotNull(dialog.window)
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(FLAG_BLUR_BEHIND or FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(FLAG_TRANSLUCENT_STATUS)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.isStatusBarContrastEnforced = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom + dp(8)
            val params = card.layoutParams as FrameLayout.LayoutParams
            if (params.bottomMargin != bottom) {
                params.bottomMargin = bottom
                card.layoutParams = params
            }
            insets
        }
        val hostDecor = host.window.decorView
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { dialog.dismiss() }
        }
        dialog.setOnDismissListener {
            hostDecor.removeOnAttachStateChangeListener(detach)
            cancelMotion()
            cancelThemeMotion()
            heightMotion?.removeAllListeners()
            heightMotion?.cancel()
            handler.removeCallbacks(flush)
            flush.run()
        }
        dialog.show()
        hostDecor.addOnAttachStateChangeListener(detach)
        window.clearFlags(FLAG_BLUR_BEHIND or FLAG_DIM_BEHIND)
        window.setLayout(-1, -1)
        ViewCompat.requestApplyInsets(root)
        card.doOnPreDraw {
            if (!closing) {
                card.translationY = hiddenOffset()
                moveCard(true)
            }
        }
        previewIcons.execute {
            val weather = sequenceOf("net.oneplus.weather", "com.coloros.weather2", "com.oplus.weather")
                .mapNotNull { name -> runCatching {
                    val manager = app.packageManager
                    val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(name)
                    manager.resolveActivity(launch, 0)?.activityInfo?.loadIcon(manager) ?: manager.getApplicationIcon(name)
                }.getOrNull() }.firstOrNull() ?: labels.getDrawable(R.drawable.ic_nav_light)
            val icon = weather?.let { runCatching { PreviewIcons.fromDrawable(it) }.getOrNull() }
            if (icon != null) {
                icon.source.prepareToDraw()
                icon.layers.glyphBitmap.prepareToDraw()
                handler.post { if (dialog.isShowing) samples.values.forEach { it.sample.setWeather(icon) } }
            }
        }
        wallpapers.execute {
            val image = runCatching { WallpaperHelper.getPickerWallpaper(app) }.getOrNull()
            handler.post { if (dialog.isShowing) wallpaper = image }
        }
        GlassLogger.i("DesktopAppearance", "Opened bottom-aligned classic COUI appearance panel")
        return dialog
    }

    private data class Choice(val group: LinearLayout, val caption: TextView, val sample: AppearanceSample)
    private data class ColorSlider(val input: View, val track: AppearanceSpectrum)
}
