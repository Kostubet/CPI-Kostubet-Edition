package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
import android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import androidx.core.view.doOnPreDraw
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.DesktopAppearance
import com.cosmod.launcherglass.ui.DesktopAppearance.Mode
import com.cosmod.launcherglass.ui.DesktopAppearance.Theme
import com.cosmod.launcherglass.ui.FeatureSections
import com.cosmod.launcherglass.ui.GlassDispersionSettings
import com.cosmod.launcherglass.ui.GlassPreview
import com.cosmod.launcherglass.ui.SettingsDefaults
import com.cosmod.launcherglass.ui.SettingsProvider.Companion as Keys
import com.cosmod.launcherglass.ui.onextbox.ColorOsPopupMaterialView
import com.cosmod.launcherglass.ui.onextbox.applyOplusPopupBlur
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.PreviewIcons
import com.cosmod.launcherglass.util.WallpaperHelper
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.math.roundToInt

/** The original CPI COUI material on an explicitly bottom-aligned launcher panel. */
internal object DesktopAppearancePanel {
    private const val COUI_SEEK = "com.coui.appcompat.seekbar.COUISeekBar"

    class Contract(loader: ClassLoader) {
        private val seekType = loader.loadClass(COUI_SEEK)
        val seek: Constructor<*> = seekType.getConstructor(Context::class.java)
        val max: Method = seekType.getMethod("setMax", Int::class.javaPrimitiveType)
        val progress: Method = seekType.getMethod("setProgress", Int::class.javaPrimitiveType)
        val track: Method = seekType.getMethod("setSeekBarBackgroundColor", ColorStateList::class.java)
        val fill: Method = seekType.getMethod("setProgressColor", ColorStateList::class.java)
        val thumb: Method? = runCatching { seekType.getMethod("setThumbColor", ColorStateList::class.java) }.getOrNull()
        val listenerType: Class<*> = seekType.declaredClasses.single {
            it.isInterface && it.declaredMethods.any { method -> method.parameterTypes.contentEquals(arrayOf(seekType, Int::class.javaPrimitiveType)) }
        }
        val listener: Method = seekType.getMethod("setOnSeekBarChangeListener", listenerType)
    }

    fun contract(loader: ClassLoader) = Contract(loader)

    fun open(host: Activity, native: Contract): Dialog? {
        val stored = host.contentResolver.call(Keys.CONTENT_URI, Keys.METHOD_GET_ALL, null, null) ?: return null
        if (!stored.getBoolean(Keys.KEY_ENABLED, true)) return null
        var backAction: () -> Unit = {}
        val dialog = object : Dialog(host) {
            @Deprecated("Used by the host Dialog to dispatch Back on older firmware.")
            override fun onBackPressed() = backAction()
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val context = host
        val labels = host.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        val systemDark = IconPalette.isSystemDark(host)
        var profile = DesktopAppearance.read(stored, systemDark)
        var wallpaper: Bitmap? = null
        var darken = stored.getInt(DesktopAppearance.DIM_WALLPAPER) == 1
        var large = stored.getInt(DesktopAppearance.LARGE_ICONS) == 1
        val handler = Handler(Looper.getMainLooper())
        val pending = Bundle()
        val flush = Runnable {
            if (pending.isEmpty) return@Runnable
            val change = Bundle(pending)
            pending.clear()
            runCatching {
                host.contentResolver.call(Keys.CONTENT_URI, Keys.METHOD_SET_DESKTOP_APPEARANCE, null, change)
            }.onFailure { GlassLogger.limited("DesktopAppearance", "save") { "Could not save appearance: ${it.message}" } }
        }
        fun save(vararg values: Pair<String, Int>, dragging: Boolean = false) {
            for ((key, value) in values) pending.putInt(key, value)
            if (!dragging) {
                handler.removeCallbacks(flush)
                flush.run()
            } else if (!handler.hasCallbacks(flush)) handler.postDelayed(flush, 120)
        }

        fun dp(value: Int) = DesktopAppearanceToolbar.dp(context, value)
        val foreground = if (systemDark) Color.WHITE else 0xff17181a.toInt()
        val panelColor = if (systemDark) 0xe626272c.toInt() else 0xeaf6f6f9.toInt()
        val density = context.resources.displayMetrics.density
        val root = FrameLayout(context)
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
        }
        val material = ColorOsPopupMaterialView(context, systemDark).apply {
            setBackgroundColor(panelColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            doOnPreDraw { applyOplusPopupBlur(this, systemDark, density) }
        }
        val card = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                measureChild(scroll, widthMeasureSpec, heightMeasureSpec)
                val aspect = when {
                    profile.hasTintControls -> 1.14f
                    profile.themeChoices.isNotEmpty() -> .69f
                    else -> .53f
                }
                val targetHeight = (scroll.measuredWidth * aspect).roundToInt()
                    .coerceAtMost(MeasureSpec.getSize(heightMeasureSpec))
                if (scroll.measuredHeight < targetHeight) {
                    scroll.measure(
                        MeasureSpec.makeMeasureSpec(scroll.measuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY),
                    )
                }
                setMeasuredDimension(scroll.measuredWidth, scroll.measuredHeight)
                material.measure(
                    MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
                )
            }
        }
        fun label(text: String, size: Float) = TextView(context).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(foreground)
        }
        fun roundButton(icon: Int, title: Int, diameter: Int, action: () -> Unit): FrameLayout {
            val surface = ColorOsPopupMaterialView(context, systemDark, diameter / 2f).apply {
                setBackgroundColor(ColorUtils.setAlphaComponent(foreground, 23))
            }
            val glyph = ImageView(context).apply {
                setImageDrawable(labels.getDrawable(icon))
                setColorFilter(foreground)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            return FrameLayout(context).apply {
                addView(surface, FrameLayout.LayoutParams(-1, -1))
                addView(glyph, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
                this.foreground = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(
                    if (systemDark) Color.WHITE else Color.BLACK, 45)), null, GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                })
                contentDescription = labels.getString(title)
                setOnClickListener { action() }
                isFocusable = true
            }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        val header = FrameLayout(context)
        header.addView(label(labels.getString(R.string.desktop_appearance_title), 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            isAccessibilityHeading = true
        }, FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(48); marginEnd = dp(48) })
        lateinit var shadeButton: FrameLayout
        shadeButton = roundButton(R.drawable.ic_nav_light, R.string.desktop_appearance_dim_wallpaper, 38) {
            darken = !darken
            shadeButton.isSelected = darken
            shadeButton.alpha = if (darken) 1f else .72f
            save(DesktopAppearance.DIM_WALLPAPER to if (darken) 1 else 0)
        }
        shadeButton.isSelected = darken
        shadeButton.alpha = if (darken) 1f else .72f
        lateinit var sizeButton: FrameLayout
        sizeButton = roundButton(R.drawable.ic_desktop_icon_size, R.string.desktop_appearance_icon_size_toggle, 38) {
            large = !large
            sizeButton.isSelected = large
            sizeButton.alpha = if (large) 1f else .72f
            save(DesktopAppearance.LARGE_ICONS to if (large) 1 else 0)
        }
        sizeButton.isSelected = large
        sizeButton.alpha = if (large) 1f else .72f
        header.addView(shadeButton, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(sizeButton, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.END or Gravity.CENTER_VERTICAL))
        body.addView(header, LinearLayout.LayoutParams(-1, dp(50)))
        body.addView(Space(context), LinearLayout.LayoutParams(-1, dp(5), 1f))
        val weather = sequenceOf("net.oneplus.weather", "com.coloros.weather2", "com.oplus.weather", "com.coloros.weather.service")
            .mapNotNull { name -> runCatching {
                val pm = host.packageManager
                val info = pm.getApplicationInfo(name, 0)
                pm.getResourcesForApplication(info).getDrawable(info.icon, null)
            }.getOrNull() }
            .firstOrNull() ?: labels.getDrawable(R.drawable.ic_nav_light)
        val icons = listOfNotNull(weather?.let { runCatching { PreviewIcons.fromDrawable(it) }.getOrNull() })
        val previews = mutableMapOf<Mode, GlassPreview>()
        val modeButtons = mutableMapOf<Mode, Choice>()
        val themeButtons = mutableMapOf<Theme, Choice>()
        val colorControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val themeControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val sampleRow = FrameLayout(context)
        val accent = android.util.TypedValue().let { value ->
            if (context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)) value.data else 0xff1677ff.toInt()
        }
        val labelColor = label("", 14f).currentTextColor
        val secondary = ColorUtils.setAlphaComponent(labelColor, 170)
        val choiceRipple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 32))
        fun choice(text: String, sample: View, height: Int, action: () -> Unit): Choice {
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                this.foreground = RippleDrawable(choiceRipple, null,
                    GradientDrawable().apply {
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.WHITE)
                    })
                contentDescription = text
                isFocusable = true
                setOnClickListener { action() }
            }
            val caption = label(text, 11f).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                minHeight = dp(32)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val frame = FrameLayout(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(sample, FrameLayout.LayoutParams(-1, -1))
                this.foreground = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
                        cornerRadius = dp(15).toFloat()
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1).coerceAtLeast(1), ColorUtils.setAlphaComponent(accent, 200))
                    })
                    addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
                }
            }
            item.addView(frame, LinearLayout.LayoutParams(dp(height), dp(height)))
            item.addView(caption, LinearLayout.LayoutParams(-1, -2))
            return Choice(item, caption, frame)
        }
        fun highlight(choice: Choice, selected: Boolean) {
            choice.item.isSelected = selected
            choice.sample.isSelected = selected
            val color = if (selected) accent else secondary
            if (choice.label.currentTextColor != color) {
                choice.label.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                choice.label.setTextColor(color)
            }
        }
        fun render(modes: Set<Mode> = previews.keys) {
            colorControls.visibility = if (profile.hasTintControls) View.VISIBLE else View.GONE
            val themes = profile.themeChoices
            themeControls.visibility = if (themes.isEmpty()) View.GONE else View.VISIBLE
            for ((mode, button) in modeButtons) highlight(button, mode == profile.mode)
            for ((theme, button) in themeButtons) {
                button.item.visibility = if (theme in themes) View.VISIBLE else View.GONE
                button.sample.visibility = if (profile.hasTintControls) View.VISIBLE else View.GONE
                if (theme == Theme.DARK) {
                    val title = labels.getString(
                        if (profile.mode == Mode.DARK) R.string.desktop_appearance_dark_always else theme.label)
                    if (button.label.text != title) {
                        button.label.text = title
                        button.item.contentDescription = title
                    }
                }
                highlight(button, theme == profile.theme)
                button.label.backgroundTintList = ColorStateList.valueOf(if (theme == profile.theme)
                    ColorUtils.setAlphaComponent(foreground, 28) else Color.TRANSPARENT)
                if (theme == Theme.AUTO) button.sample.isSelected = false
            }
            for (mode in modes) {
                val iconView = previews[mode] ?: continue
                val candidate = profile.selectMode(mode)
                val settings = Bundle(stored)
                DesktopAppearance.apply(settings, candidate, systemDark)
                val enabled = settings.getBoolean(FeatureSections.key("ICONS"), true)
                val dark = candidate.iconDark(systemDark)
                val keys = Keys.colorKeys(dark)
                fun number(key: String) = settings.getInt(key, SettingsDefaults.integer(key))
                val clear = number(Keys.KEY_ICON_APPEARANCE) == 0
                val palette = if (!enabled) null else IconPalette.from(number(Keys.KEY_COLOR_MODE), number(keys[0]), number(keys[1]),
                    if (clear) number(Keys.KEY_CLEAR_BRIGHTNESS) else number(keys[2]), dark, clear)
                    .withLogo(settings.getBoolean("icon_logo_tint"), settings.getInt("icon_logo_hue"),
                        settings.getInt("icon_logo_saturation"), settings.getInt("icon_logo_brightness", 100))
                iconView.configure(settings, 0)
                iconView.show(GlassPreview.Material(enabled,
                    number(Keys.KEY_BLUR_STYLE) == Keys.BLUR_STYLE_LIQUID, number(Keys.KEY_BLUR_RADIUS),
                    number(Keys.KEY_BG_OPACITY), number(Keys.KEY_LIQUID_WIDTH), number(Keys.KEY_LIQUID_DISTORTION),
                    GlassDispersionSettings.strength(settings, GlassDispersionSettings.ICONS), 28,
                    tiles = 1, iconPalette = palette, plateColor = palette?.background))
            }
        }

        val modes = LinearLayout(context).apply {
            isBaselineAligned = false
            setPadding(0, dp(6), 0, 0)
        }
        fun animateHeightChange() {
            if (!card.isLaidOut || !android.animation.ValueAnimator.areAnimatorsEnabled()) return
            TransitionManager.beginDelayedTransition(root, TransitionSet()
                .addTransition(ChangeBounds().addTarget(card))
                .addTransition(Fade().addTarget(colorControls).addTarget(themeControls))
                .setDuration(240L).setInterpolator(DecelerateInterpolator(1.5f)))
        }
        for (mode in listOf(Mode.STOCK, Mode.DARK, Mode.CLEAR, Mode.TINTED)) {
            val weatherLayers = weather as? AdaptiveIconDrawable
            val sample = if (mode == Mode.STOCK || mode == Mode.DARK || icons.isEmpty()) {
                FrameLayout(context).apply {
                    val icon = ImageView(context).apply {
                        val drawable = if (mode == Mode.DARK && weatherLayers != null) {
                            val front = weatherLayers.foreground
                            AdaptiveIconDrawable(ColorDrawable(0xff202126.toInt()),
                                front.constantState?.newDrawable() ?: front)
                        } else weather?.constantState?.newDrawable() ?: weather
                        setImageDrawable(drawable)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                    addView(icon, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
                }
            } else {
                GlassPreview(context, systemDark, useDeviceWallpaperColor = false, showWallpaper = false).apply {
                    setScene(null, icons)
                    previews[mode] = this
                }
            }
            val item = choice(labels.getString(mode.label), sample, 56) {
                if (mode == profile.mode) return@choice
                animateHeightChange()
                profile = profile.selectMode(mode)
                render()
                save(DesktopAppearance.MODE to mode.ordinal, DesktopAppearance.THEME to profile.theme.ordinal)
            }
            modeButtons[mode] = item
            modes.addView(item.item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(modes)
        body.addView(Space(context), LinearLayout.LayoutParams(-1, dp(4), 1f))

        fun slider(title: Int, range: Int, value: Int, change: (Int) -> Unit): ColorSlider {
            val text = labels.getString(title)
            val slider = native.seek.newInstance(context) as View
            slider.contentDescription = text
            native.max.invoke(slider, range)
            native.progress.invoke(slider, value)
            val holder = FrameLayout(context)
            val gradient = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                cornerRadius = dp(12).toFloat()
            }
            val track = View(context).apply {
                background = gradient
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            holder.addView(track, FrameLayout.LayoutParams(-1, dp(24), Gravity.CENTER).apply {
                marginStart = dp(14); marginEnd = dp(14)
            })
            native.track.invoke(slider, ColorStateList.valueOf(Color.TRANSPARENT))
            native.fill.invoke(slider, ColorStateList.valueOf(Color.TRANSPARENT))
            native.thumb?.invoke(slider, ColorStateList.valueOf(Color.WHITE))
            val callback = Proxy.newProxyInstance(native.listenerType.classLoader, arrayOf(native.listenerType)) { proxy, method, args ->
                when {
                    method.name == "hashCode" -> System.identityHashCode(slider)
                    method.name == "equals" -> proxy === args?.getOrNull(0)
                    method.name == "toString" -> "CPI appearance slider listener"
                    args?.size == 2 && args[1] is Int -> {
                        change(args[1] as Int)
                        null
                    }
                    else -> null
                }
            }
            native.listener.invoke(slider, callback)
            holder.addView(slider, FrameLayout.LayoutParams(-1, -1))
            colorControls.addView(holder, LinearLayout.LayoutParams(-1, dp(42)))
            return ColorSlider(slider, gradient)
        }
        fun direction(colors: IntArray): IntArray {
            if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) colors.reverse()
            return colors
        }
        fun updateSaturationTrack(track: GradientDrawable) {
            track.colors = direction(intArrayOf(Color.WHITE, Color.HSVToColor(floatArrayOf(profile.hue.toFloat(), 1f, 1f))))
        }
        lateinit var saturation: ColorSlider
        val hue = slider(R.string.desktop_appearance_hue, 360, profile.hue) {
            if (it == profile.hue) return@slider
            profile = profile.copy(hue = it)
            updateSaturationTrack(saturation.gradient)
            render(setOf(Mode.TINTED))
            save(DesktopAppearance.HUE to it, dragging = true)
        }
        hue.gradient.colors = direction(intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED))
        saturation = slider(R.string.desktop_appearance_saturation, 100, profile.saturation) {
            if (it == profile.saturation) return@slider
            profile = profile.copy(saturation = it)
            render(setOf(Mode.TINTED))
            save(DesktopAppearance.SATURATION to it, dragging = true)
        }
        updateSaturationTrack(saturation.gradient)
        body.addView(colorControls)
        val themes = LinearLayout(context).apply {
            isBaselineAligned = false
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        for (theme in listOf(Theme.LIGHT, Theme.DARK, Theme.AUTO)) {
            val sample = if (theme == Theme.AUTO) sampleRow else FrameLayout(context).apply {
                val swatch = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        val light = 0xffeeeeef.toInt()
                        val dark = 0xff252528.toInt()
                        when (theme) {
                            Theme.LIGHT -> setColor(light)
                            Theme.DARK -> setColor(dark)
                            Theme.AUTO -> {
                                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                                colors = intArrayOf(light, light, dark, dark)
                            }
                        }
                        setStroke(dp(1), 0x50888888)
                    }
                }
                addView(swatch, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            }
            val toggle = choice(labels.getString(theme.label), sample, 42) {
                profile = profile.copy(theme = theme)
                render()
                save(DesktopAppearance.THEME to theme.ordinal)
            }
            toggle.label.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
            if (theme == Theme.AUTO) toggle.sample.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            themeButtons[theme] = toggle
            themes.addView(toggle.item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val sampleButton = roundButton(R.drawable.ic_wallpaper_sample, R.string.desktop_appearance_pick_color, 38) {
            val image = wallpaper?.takeUnless { it.isRecycled }
            if (image == null) {
                Toast.makeText(context, labels.getString(R.string.desktop_appearance_wallpaper_unavailable), Toast.LENGTH_SHORT).show()
            } else {
                val closeAppearance = backAction
                lateinit var picker: DesktopWallpaperColorPicker
                fun hidePicker() {
                    root.removeView(picker)
                    card.visibility = View.VISIBLE
                    backAction = closeAppearance
                }
                picker = DesktopWallpaperColorPicker(context, image,
                    labels.getString(R.string.desktop_appearance_pick_color)) { color ->
                    val hsv = FloatArray(3)
                    Color.colorToHSV(color, hsv)
                    val pickedHue = if (hsv[1] == 0f) profile.hue else hsv[0].roundToInt().coerceIn(0, 360)
                    val pickedSaturation = (hsv[1] * 100f).roundToInt()
                    profile = profile.copy(hue = pickedHue, saturation = pickedSaturation)
                    native.progress.invoke(hue.view, pickedHue)
                    native.progress.invoke(saturation.view, pickedSaturation)
                    updateSaturationTrack(saturation.gradient)
                    render(setOf(Mode.TINTED))
                    save(DesktopAppearance.HUE to pickedHue, DesktopAppearance.SATURATION to pickedSaturation)
                    hidePicker()
                }
                card.visibility = View.INVISIBLE
                backAction = ::hidePicker
                root.addView(picker, FrameLayout.LayoutParams(-1, -1))
            }
        }
        sampleRow.addView(sampleButton, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER))
        themeControls.addView(themes)
        body.addView(themeControls)
        render()
        scroll.addView(body)
        card.isClickable = true
        card.addView(material, FrameLayout.LayoutParams(-1, -1))
        card.addView(scroll, FrameLayout.LayoutParams(-1, -2))
        root.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(8)
        })
        fun closeCard() {
            if (!android.animation.ValueAnimator.areAnimatorsEnabled()) {
                dialog.dismiss()
                return
            }
            if (!root.isClickable) return
            root.isClickable = false
            card.animate().translationY((card.height + dp(12)).toFloat())
                .setInterpolator(android.view.animation.AccelerateInterpolator(1.25f))
                .setDuration(185L).withEndAction { dialog.dismiss() }.start()
        }
        backAction = ::closeCard
        root.setOnClickListener { backAction() }
        root.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        dialog.setContentView(root)
        val window = requireNotNull(dialog.window)
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(FLAG_BLUR_BEHIND or FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
        dialog.setOnDismissListener {
            card.animate().cancel()
            handler.removeCallbacks(flush)
            flush.run()
        }
        dialog.show()
        window.clearFlags(FLAG_BLUR_BEHIND or FLAG_DIM_BEHIND)
        window.setLayout(-1, -1)
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            card.doOnPreDraw {
                card.translationY = card.height + dp(8).toFloat()
                card.animate().translationY(0f).setDuration(265L).setInterpolator(DecelerateInterpolator(1.65f)).start()
            }
        }
        // Wallpaper I/O must not delay the host sheet animation.
        Thread({
            val image = runCatching {
                WallpaperHelper.getRawScaledWallpaper(host.applicationContext)?.let(WallpaperHelper::forSoftwareCanvas)
            }.getOrNull()
            if (image != null) handler.post {
                if (dialog.isShowing) {
                    wallpaper = image
                    previews.values.forEach { it.setWallpaper(image) }
                }
            }
        }, "CPI appearance wallpaper").start()
        GlassLogger.i("DesktopAppearance", "Opened bottom-aligned classic COUI appearance panel")
        return dialog
    }

    private data class Choice(val item: LinearLayout, val label: TextView, val sample: FrameLayout)
    private data class ColorSlider(val view: View, val gradient: GradientDrawable)
}
