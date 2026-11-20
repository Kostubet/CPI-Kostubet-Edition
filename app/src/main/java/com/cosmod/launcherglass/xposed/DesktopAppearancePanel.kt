package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.content.Context
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
import android.view.VelocityTracker
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
import android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
import android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
import android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.DesktopAppearance
import com.cosmod.launcherglass.ui.DesktopAppearance.ColorSource
import com.cosmod.launcherglass.ui.DesktopAppearance.Mode
import com.cosmod.launcherglass.ui.DesktopAppearance.Theme
import com.cosmod.launcherglass.ui.SettingsProvider.Companion as Keys
import com.cosmod.launcherglass.ui.onextbox.ColorOsDialogButtonMotion
import com.cosmod.launcherglass.ui.onextbox.ColorOsPopupMaterialView
import com.cosmod.launcherglass.ui.onextbox.applyOplusPopupBlur
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.WallpaperHelper
import de.robv.android.xposed.XposedHelpers
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Copy
import io.github.suqi8.coui.kmp.icon.extended.Image
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.roundToInt

internal object DesktopAppearancePanel {
    private val writes by lazy { Executors.newSingleThreadExecutor() }
    private val wallpapers by lazy { Executors.newSingleThreadExecutor() }
    private val previewIcons by lazy { Executors.newSingleThreadExecutor() }
    private val enter = PathInterpolator(.25f, .20f, .22f, 1f)
    private val exit = PathInterpolator(.38f, .04f, .34f, 1f)
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
        val classic = runCatching { NativeClassicIconStyle(loader) }.getOrNull()
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
        var wallpaperTone: IconPalette.WallpaperTone? = null
        val handler = Handler(Looper.getMainLooper())
        val pending = Bundle()
        val app = host.applicationContext
        val flush = Runnable {
            if (pending.isEmpty) return@Runnable
            val change = Bundle(pending)
            pending.clear()
            writes.execute {
                runCatching {
                    val result = app.contentResolver.call(Keys.CONTENT_URI, Keys.METHOD_SET_DESKTOP_APPEARANCE, null, change)
                    if (result?.getBoolean("applied") == true && change.getInt(DesktopAppearance.MODE, -1) == Mode.STOCK.ordinal) {
                        native.classic?.apply(app)
                    }
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
        val root = FrameLayout(host).apply { clipChildren = false; clipToPadding = false }
        val scroll = ScrollView(host).apply {
            clipChildren = false
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val radius = 35f
        val material = ColorOsPopupMaterialView(host, systemDark, radius).apply {
            setBackgroundColor(if (systemDark) 0xe624252a.toInt() else 0xebf6f6f9.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            doOnPreDraw { applyOplusPopupBlur(this, systemDark, density, radius) }
        }
        var heightMotion: ValueAnimator? = null
        var pickerMotion: ValueAnimator? = null
        var pickerActive = false
        var pull: (Float, Float) -> Unit = { _, _ -> }
        var release: (Boolean, Float, Float) -> Unit = { _, _, _ -> }
        val horizontalControls = mutableListOf<View>()
        val card = object : FrameLayout(host) {
            private val slop = ViewConfiguration.get(host).scaledTouchSlop
            private var startX = 0f
            private var startY = 0f
            private var pulling = false
            private var dismissGesture = false
            private var beganOnHorizontalControl = false
            private var velocity: VelocityTracker? = null

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        velocity?.recycle()
                        velocity = VelocityTracker.obtain()
                        material.spotlightDown(event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> material.spotlightMove(event.x, event.y)
                    MotionEvent.ACTION_UP -> material.spotlightUp(event.x, event.y)
                    MotionEvent.ACTION_CANCEL -> material.cancelSpotlight()
                }
                val raw = MotionEvent.obtain(event)
                raw.offsetLocation(event.rawX - event.x, event.rawY - event.y)
                velocity?.addMovement(raw)
                raw.recycle()
                try {
                    return super.dispatchTouchEvent(event)
                } finally {
                    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        velocity?.recycle()
                        velocity = null
                    }
                }
            }

            override fun onDetachedFromWindow() {
                velocity?.recycle()
                velocity = null
                super.onDetachedFromWindow()
            }

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                measureChild(scroll, widthMeasureSpec, heightMeasureSpec)
                setMeasuredDimension(scroll.measuredWidth, scroll.measuredHeight)
                material.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
            }

            private fun pastSlop(delta: Float): Float = delta.sign * (abs(delta) - slop).coerceAtLeast(0f)

            private fun pullAt(event: MotionEvent) {
                pull(pastSlop(event.rawX - startX), pastSlop(event.rawY - startY))
            }

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        pulling = false
                        dismissGesture = false
                        val position = IntArray(2)
                        beganOnHorizontalControl = horizontalControls.any { control ->
                            if (!control.isShown) return@any false
                            control.getLocationOnScreen(position)
                            startX >= position[0] && startX < position[0] + control.width * scaleX &&
                                startY >= position[1] && startY < position[1] + control.height * scaleY
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = abs(event.rawX - startX)
                        val dy = event.rawY - startY
                        val horizontal = dx >= abs(dy)
                        if (horizontal && beganOnHorizontalControl) return false
                        if (maxOf(dx, abs(dy)) > slop && (horizontal ||
                                !scroll.canScrollVertically(if (dy < 0f) 1 else -1))) {
                            pulling = true
                            dismissGesture = !horizontal && dy > 0f
                            pullAt(event)
                            return true
                        }
                    }
                }
                return super.onInterceptTouchEvent(event)
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (!pulling) return super.onTouchEvent(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> pullAt(event)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val released = event.actionMasked == MotionEvent.ACTION_UP
                        if (released) velocity?.computeCurrentVelocity(1000)
                        val down = event.rawY - startY - slop
                        val dismiss = released && dismissGesture && down >= minOf(dp(75).toFloat(), height * .35f)
                        release(dismiss, if (released) velocity?.xVelocity ?: 0f else 0f,
                            if (released) velocity?.yVelocity ?: 0f else 0f)
                        pulling = false
                    }
                }
                return true
            }
        }.apply {
            isClickable = true
            clipChildren = false
            clipToPadding = false
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
        val buttonTint = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(accent, foreground))
        fun appearanceButton(icon: android.graphics.drawable.Drawable?, title: Int, filled: Boolean, action: () -> Unit) =
            AppearanceIconButton(host, systemDark, icon, buttonTint, filled).apply {
                contentDescription = labels.getString(title)
                setOnClickListener { action() }
            }
        val body = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(14), dp(7), dp(14), dp(13))
        }
        val header = FrameLayout(host).apply { clipChildren = false; clipToPadding = false }
        header.addView(label(labels.getString(R.string.desktop_appearance_title), 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            isAccessibilityHeading = true
        }, FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(90); marginEnd = dp(90) })
        lateinit var shadeButton: View
        shadeButton = appearanceButton(labels.getDrawable(R.drawable.ic_nav_light), R.string.desktop_appearance_dim_wallpaper, filled = true) {
            darken = !darken
            shadeButton.isSelected = darken
            save(DesktopAppearance.DIM_WALLPAPER to if (darken) 1 else 0)
        }
        shadeButton.isSelected = darken
        header.addView(shadeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.CENTER_VERTICAL))
        lateinit var sizeButton: View
        sizeButton = appearanceButton(COUIIcons.Copy.asLauncherGlyph(host), R.string.desktop_appearance_icon_size_toggle, filled = true) {
            large = !large
            sizeButton.isSelected = large
            save(DesktopAppearance.LARGE_ICONS to if (large) 1 else 0, delay = 145L)
        }
        sizeButton.isSelected = large
        header.addView(sizeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.CENTER_VERTICAL))
        body.addView(header, LinearLayout.LayoutParams(-1, dp(47)))

        val samples = linkedMapOf<Mode, Choice>()
        val colorControls = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        val colorReveal = FrameLayout(host)
        val themeControls = FrameLayout(host).also(horizontalControls::add)
        val colorHeight = dp(36) * 2 + dp(54) + dp(5)
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
        var displayHue = profile.hue.toFloat()
        var displaySaturation = profile.saturation / 100f
        var updateSourceButtons: () -> Unit = {}
        fun updateSamples(only: Mode? = null) {
            for ((mode, choice) in samples) {
                if (only != null && mode != only) continue
                val selected = mode == profile.mode
                if (choice.group.isSelected != selected) choice.group.isSelected = selected
                val textColor = if (selected) foreground else contrast
                if (choice.caption.currentTextColor != textColor) choice.caption.setTextColor(textColor)
                val sample = profile.selectMode(mode)
                val dark = mode == Mode.DARK || sample.iconDark(systemDark)
                val plate = when (mode) {
                    Mode.DARK -> 0xff17181b.toInt()
                    Mode.CLEAR -> if (dark) 0x603b3736 else Color.TRANSPARENT
                    Mode.TINTED -> Color.HSVToColor(if (dark) 225 else 156,
                        floatArrayOf(displayHue, displaySaturation * if (dark) .42f else .68f,
                            if (dark) .15f else .9f))
                    else -> Color.TRANSPARENT
                }
                val glyph = if (mode == Mode.TINTED) Color.HSVToColor(floatArrayOf(displayHue,
                    displaySaturation * .42f, if (dark) .86f else 1f)) else 0xfff7f6f4.toInt()
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
            setPadding(0, dp(9), 0, 0)
        }
        for (mode in listOf(Mode.STOCK, Mode.DARK, Mode.CLEAR, Mode.TINTED)) {
            val sample = AppearanceSample(host, mode)
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
                    } else if (mode == Mode.STOCK) {
                        save(DesktopAppearance.MODE to mode.ordinal, delay = MODE_TRANSITION_MS)
                    }
                }
                addView(sample, LinearLayout.LayoutParams(dp(54), dp(54)))
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
                moveTo(value)
            }
            var dragging = false
            var moved = false
            var downX = 0f
            val slop = ViewConfiguration.get(host).scaledTouchSlop
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
                            if (profile.colorSource != ColorSource.MANUAL) {
                                profile = profile.copy(colorSource = ColorSource.MANUAL)
                                save(DesktopAppearance.COLOR_SOURCE to ColorSource.MANUAL.ordinal)
                                updateSourceButtons()
                            }
                            change(next)
                            track.moveTo(next, animate = !dragging || !moved)
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
                    MotionEvent.ACTION_DOWN -> {
                        dragging = true
                        moved = false
                        downX = event.x
                    }
                    MotionEvent.ACTION_MOVE -> if (!moved && abs(event.x - downX) > slop) {
                        moved = true
                        track.moveTo(track.position)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        input.post {
                            dragging = false
                            finishDrag()
                        }
                    }
                }
                false
            }
            val holder = FrameLayout(host).also(horizontalControls::add)
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
        fun saturationColor(strength: Float) = Color.HSVToColor(floatArrayOf(displayHue, strength, 1f))
        fun updateTracks() {
            saturation.track.setHandle(saturationColor(displaySaturation))
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
        }
        hue.track.onPositionDrawn = {
            displayHue = hue.track.displayedPosition
            updateTintOnFrame()
        }
        hue.track.setColors(intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED))
        saturation = slider(R.string.desktop_appearance_saturation, 100, profile.saturation, DesktopAppearance.SATURATION) {
            profile = profile.copy(saturation = it)
        }
        saturation.track.onPositionDrawn = {
            displaySaturation = saturation.track.displayedPosition / 100f
            updateTintOnFrame()
        }
        updateTracks()
        colorReveal.addView(colorControls, FrameLayout.LayoutParams(-1, colorHeight - dp(5)).apply { topMargin = dp(5) })
        body.addView(colorReveal, LinearLayout.LayoutParams(-1, 0))
        body.addView(themeControls, LinearLayout.LayoutParams(-1, 0))

        fun showColor(hueValue: Int, saturationValue: Int) {
            hue.track.moveTo(hueValue, animate = true)
            saturation.track.moveTo(saturationValue, animate = true)
            native.progress.invoke(hue.input, hueValue)
            native.progress.invoke(saturation.input, saturationValue)
        }
        fun selectColorSource(source: ColorSource, tone: IconPalette.WallpaperTone?) {
            val selectedHue = tone?.hue ?: profile.hue
            val selectedSaturation = tone?.saturation ?: if (source == ColorSource.MONOCHROME) 0 else profile.saturation
            profile = profile.copy(colorSource = source, hue = selectedHue, saturation = selectedSaturation)
            showColor(selectedHue, selectedSaturation)
            updateSourceButtons()
            save(DesktopAppearance.COLOR_SOURCE to source.ordinal,
                DesktopAppearance.HUE to selectedHue, DesktopAppearance.SATURATION to selectedSaturation)
        }
        val monochrome = appearanceButton(labels.getDrawable(R.drawable.ic_desktop_monochrome), R.string.desktop_appearance_monochrome, filled = false) {
            selectColorSource(ColorSource.MONOCHROME, null)
        }
        val followWallpaper = appearanceButton(COUIIcons.Image.asLauncherGlyph(host), R.string.desktop_appearance_wallpaper_colors, filled = false) {
            selectColorSource(ColorSource.WALLPAPER, wallpaperTone)
        }
        updateSourceButtons = {
            monochrome.isSelected = profile.colorSource == ColorSource.MONOCHROME
            followWallpaper.isSelected = profile.colorSource == ColorSource.WALLPAPER
        }
        updateSourceButtons()
        val pickerButton = appearanceButton(labels.getDrawable(R.drawable.ic_wallpaper_sample), R.string.desktop_appearance_pick_color, filled = false) {
            if (pickerActive) return@appearanceButton
            val image = wallpaper?.takeUnless { it.isRecycled }
            if (image == null) {
                Toast.makeText(host, labels.getString(R.string.desktop_appearance_wallpaper_unavailable), Toast.LENGTH_SHORT).show()
            } else {
                val closeAppearance = backAction
                lateinit var picker: DesktopWallpaperColorPicker
                fun transitionPicker(open: Boolean) {
                    pickerMotion?.removeAllListeners()
                    pickerMotion?.cancel()
                    val initial = picker.alpha
                    val target = if (open) 1f else 0f
                    fun position(alpha: Float) {
                        picker.alpha = alpha
                        card.alpha = 1f - alpha
                    }
                    fun finish() {
                        if (open) {
                            card.visibility = View.INVISIBLE
                        } else {
                            root.removeView(picker)
                            pickerActive = false
                            backAction = closeAppearance
                        }
                    }
                    if (initial == target || !ValueAnimator.areAnimatorsEnabled()) {
                        position(target)
                        finish()
                        return
                    }
                    pickerMotion = ValueAnimator.ofFloat(initial, target).apply {
                        duration = 290L
                        interpolator = modeTransition
                        addUpdateListener { position(it.animatedValue as Float) }
                        doOnEnd { finish() }
                        start()
                    }
                }
                fun hidePicker() {
                    if (!picker.isEnabled) return
                    picker.isEnabled = false
                    card.visibility = View.VISIBLE
                    transitionPicker(false)
                }
                picker = DesktopWallpaperColorPicker(host, image,
                    labels.getString(R.string.desktop_appearance_pick_color)) { color ->
                    val hsv = FloatArray(3)
                    Color.colorToHSV(color, hsv)
                    val pickedHue = if (hsv[1] == 0f) profile.hue else hsv[0].roundToInt().coerceIn(0, 360)
                    val pickedSaturation = (hsv[1] * 100f).roundToInt()
                    profile = profile.selectMode(Mode.TINTED).copy(
                        hue = pickedHue, saturation = pickedSaturation, colorSource = ColorSource.MANUAL,
                    )
                    showColor(pickedHue, pickedSaturation)
                    updateSourceButtons()
                    updateTracks()
                    updateSamples()
                    updateLayout()
                    save(DesktopAppearance.MODE to profile.mode.ordinal, DesktopAppearance.THEME to profile.theme.ordinal,
                        DesktopAppearance.COLOR_SOURCE to ColorSource.MANUAL.ordinal,
                        DesktopAppearance.HUE to pickedHue, DesktopAppearance.SATURATION to pickedSaturation)
                    hidePicker()
                }
                pickerActive = true
                picker.alpha = 0f
                backAction = ::hidePicker
                root.addView(picker, FrameLayout.LayoutParams(-1, -1))
                transitionPicker(true)
            }
        }
        val colorActions = LinearLayout(host).apply {
            clipChildren = false
            clipToPadding = false
            gravity = Gravity.CENTER
            for ((index, control) in listOf(monochrome, followWallpaper, pickerButton).withIndex()) {
                addView(control, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    if (index > 0) marginStart = dp(12)
                })
            }
        }
        colorControls.addView(colorActions, LinearLayout.LayoutParams(-1, dp(54)))
        updateLayout()
        updateSamples()
        scroll.addView(body)
        scroll.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scroll.clipChildren = body.height > scroll.height
        }
        card.addView(material, FrameLayout.LayoutParams(-1, -1))
        card.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
            bottomMargin = dp(8)
        })
        var motion: ValueAnimator? = null
        var closing = false
        var swiping = false
        lateinit var cardMotion: ColorOsDialogButtonMotion
        cardMotion = ColorOsDialogButtonMotion(density, dragRatio = .11f, onFrame = {
            if (!closing) {
                val params = card.layoutParams as FrameLayout.LayoutParams
                val room = minOf(params.leftMargin, params.rightMargin).toFloat()
                val scaleX = cardMotion.scaleX.coerceAtMost(1f + room / card.width.coerceAtLeast(1))
                val expansion = (card.width * (scaleX - 1f) / 2f).coerceAtLeast(0f)
                val travel = (room - expansion).coerceAtLeast(0f)
                card.translationX = cardMotion.translationX.coerceIn(-travel, travel)
                card.translationY = cardMotion.translationY
                card.scaleX = scaleX
                card.scaleY = cardMotion.scaleY
            }
        })
        fun cancelMotion() {
            motion?.removeAllListeners()
            motion?.cancel()
        }
        fun hiddenOffset() = (card.height + (card.layoutParams as FrameLayout.LayoutParams).bottomMargin).toFloat()
        fun moveCard(open: Boolean) {
            cancelMotion()
            if (!open) cardMotion.dispose()
            val target = if (open) 0f else hiddenOffset()
            if (!ValueAnimator.areAnimatorsEnabled() || !card.isLaidOut) {
                card.translationX = 0f
                card.translationY = target
                if (!open) dialog.dismiss()
                return
            }
            val startX = card.translationX
            val startY = card.translationY
            val startScaleX = card.scaleX
            val startScaleY = card.scaleY
            motion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (open) 465L else 390L
                interpolator = if (open) enter else exit
                addUpdateListener {
                    val fraction = it.animatedValue as Float
                    card.translationX = startX * (1f - fraction)
                    card.translationY = startY + (target - startY) * fraction
                    card.scaleX = startScaleX + (1f - startScaleX) * fraction
                    card.scaleY = startScaleY + (1f - startScaleY) * fraction
                }
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
        pull = { x, y ->
            if (!closing && ValueAnimator.areAnimatorsEnabled()) {
                if (!swiping) {
                    cancelMotion()
                    cardMotion.begin()
                    swiping = true
                }
                cardMotion.drag(Offset(x, y), IntSize(card.width, card.height))
            }
        }
        release = { dismiss, vx, vy ->
            if (dismiss) backAction()
            else if (swiping) cardMotion.release(IntSize(card.width, card.height), Offset(vx, vy))
            swiping = false
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
            val bars = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout())
            val left = bars.left + dp(6)
            val right = bars.right + dp(6)
            val bottom = bars.bottom + dp(8)
            val params = card.layoutParams as FrameLayout.LayoutParams
            if (params.bottomMargin != bottom || params.leftMargin != left || params.rightMargin != right) {
                params.setMargins(left, params.topMargin, right, bottom)
                card.layoutParams = params
            }
            insets
        }
        val hostDecor = host.window.decorView
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { dialog.dismiss() }
        }
        val lifecycle = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) { if (activity === host) dialog.dismiss() }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        var removeStateListener: (() -> Unit)? = null
        dialog.setOnDismissListener {
            removeStateListener?.invoke()
            host.application.unregisterActivityLifecycleCallbacks(lifecycle)
            hostDecor.removeOnAttachStateChangeListener(detach)
            cancelMotion()
            cardMotion.dispose()
            cancelThemeMotion()
            pickerMotion?.removeAllListeners()
            pickerMotion?.cancel()
            heightMotion?.removeAllListeners()
            heightMotion?.cancel()
            handler.removeCallbacks(flush)
            flush.run()
        }
        dialog.show()
        host.application.registerActivityLifecycleCallbacks(lifecycle)
        hostDecor.addOnAttachStateChangeListener(detach)
        runCatching {
            val manager = XposedHelpers.callMethod(host, "getStateManager") ?: return@runCatching
            val type = host.classLoader.loadClass("com.android.launcher3.statemanager.StateManager\$StateListener")
            val state = host.classLoader.loadClass("com.android.launcher3.LauncherState")
            val normal = XposedHelpers.getStaticObjectField(state, "NORMAL")
            val listener = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "CPI appearance launcher state listener"
                    "onStateTransitionStart" -> {
                        if (args?.firstOrNull() !== normal) root.post { dialog.dismiss() }
                        null
                    }
                    else -> null
                }
            }
            XposedHelpers.callMethod(manager, "addStateListener", listener)
            removeStateListener = { runCatching { XposedHelpers.callMethod(manager, "removeStateListener", listener) } }
        }.onFailure { GlassLogger.limited("DesktopAppearance", "stateListener") { "Launcher state listener unavailable: $it" } }
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
            val icon = AppearanceWeather.load(app)
            if (icon != null) {
                icon.source.prepareToDraw()
                icon.glyph.prepareToDraw()
                handler.post { if (dialog.isShowing) samples.values.forEach { it.sample.setWeather(icon) } }
            }
        }
        wallpapers.execute {
            val image = runCatching { WallpaperHelper.getPickerWallpaper(app) }.getOrNull()
            val tone = IconPalette.wallpaperTone(app)
            handler.post {
                if (!dialog.isShowing) return@post
                wallpaper = image
                wallpaperTone = tone
                if (profile.colorSource == ColorSource.WALLPAPER && tone != null) {
                    profile = profile.copy(hue = tone.hue, saturation = tone.saturation)
                    showColor(tone.hue, tone.saturation)
                }
            }
        }
        GlassLogger.i("DesktopAppearance", "Opened bottom-aligned classic COUI appearance panel")
        return dialog
    }

    private data class Choice(val group: LinearLayout, val caption: TextView, val sample: AppearanceSample)
    private data class ColorSlider(val input: View, val track: AppearanceSpectrum)
}
