package com.cosmod.launcherglass.xposed

import android.animation.LayoutTransition
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
import android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.cosmod.launcherglass.ui.onextbox.createOplusPopupBackdrop
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.IconPalette
import com.cosmod.launcherglass.util.PreviewIcons
import com.cosmod.launcherglass.util.WallpaperHelper
import io.github.suqi8.coui.kmp.basic.ListPopupDefaults
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** Uses the launcher's own COUI panel and slider interaction. */
internal object DesktopAppearancePanel {
    private const val COUI_SHEET = "com.coui.appcompat.panel.COUIBottomSheetDialog"
    private const val COUI_SEEK = "com.coui.appcompat.seekbar.COUISeekBar"

    class Contract(loader: ClassLoader) {
        private val sheetType = loader.loadClass(COUI_SHEET)
        val sheet: Constructor<*> = sheetType.getConstructor(Context::class.java)
        val compact: Method = sheetType.getMethod("setIsShowInMaxHeight", Boolean::class.javaPrimitiveType)
        val outsideMask: Method = sheetType.getMethod("setOutsideMaskColor", Int::class.javaPrimitiveType)
        val width: Method? = runCatching { sheetType.getMethod("setWidth", Int::class.javaPrimitiveType) }.getOrNull()
        val panelBackground: Method? = runCatching { sheetType.getMethod("setPanelBackground", Drawable::class.java) }.getOrNull()
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
        val dialog = native.sheet.newInstance(host) as Dialog
        val context = dialog.context
        val labels = host.createPackageContext(BuildConfig.APPLICATION_ID, 0)
        val systemDark = IconPalette.isSystemDark(host)
        var profile = DesktopAppearance.read(stored, systemDark)
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
        fun save(key: String, value: Int, dragging: Boolean = false) {
            pending.putInt(key, value)
            if (!dragging) {
                handler.removeCallbacks(flush)
                flush.run()
            } else if (!handler.hasCallbacks(flush)) handler.postDelayed(flush, 120)
        }

        fun dp(value: Int) = DesktopAppearanceToolbar.dp(context, value)
        fun label(text: String, size: Float) = TextView(context).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(14))
        }
        body.addView(label(labels.getString(R.string.desktop_appearance_title), 17f).apply {
            typeface = Typeface.DEFAULT_BOLD
            isAccessibilityHeading = true
            setPadding(0, dp(2), 0, dp(12))
        })
        val weather = sequenceOf("net.oneplus.weather", "com.coloros.weather2", "com.oplus.weather", "com.coloros.weather.service")
            .mapNotNull { name -> runCatching { host.packageManager.getApplicationIcon(name) }.getOrNull() }
            .firstOrNull() ?: labels.getDrawable(R.drawable.ic_nav_light)
        val icons = listOfNotNull(weather?.let { runCatching { PreviewIcons.fromDrawable(it) }.getOrNull() })
        val previews = mutableMapOf<Mode, GlassPreview>()
        val modeButtons = mutableMapOf<Mode, Choice>()
        val themeButtons = mutableMapOf<Theme, Choice>()
        val colorControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val themeControls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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
                foreground = RippleDrawable(choiceRipple, null,
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
            }
            item.addView(frame, LinearLayout.LayoutParams(-1, dp(height)))
            item.addView(caption, LinearLayout.LayoutParams(-1, -2))
            return Choice(item, caption)
        }
        fun highlight(choice: Choice, selected: Boolean) {
            choice.item.isSelected = selected
            val color = if (selected) accent else secondary
            if (choice.label.currentTextColor != color) {
                choice.label.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                choice.label.setTextColor(color)
            }
        }
        fun render(modes: Set<Mode> = previews.keys) {
            colorControls.visibility = if (profile.mode == Mode.TINTED) View.VISIBLE else View.GONE
            themeControls.visibility = if (profile.mode in setOf(Mode.DARK, Mode.CLEAR, Mode.TINTED)) View.VISIBLE else View.GONE
            for ((mode, button) in modeButtons) highlight(button, mode == profile.mode)
            for ((theme, button) in themeButtons) highlight(button, theme == profile.theme)
            for (mode in modes) {
                val iconView = previews[mode] ?: continue
                val candidate = profile.copy(mode = mode)
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

        val modes = LinearLayout(context).apply { isBaselineAligned = false }
        for (mode in listOf(Mode.STOCK, Mode.DARK, Mode.CLEAR, Mode.TINTED)) {
            val sample = if (mode == Mode.STOCK || icons.isEmpty()) {
                FrameLayout(context).apply {
                    addView(ImageView(context).apply {
                        setImageDrawable(weather?.constantState?.newDrawable() ?: weather)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }, FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER))
                }
            } else {
                GlassPreview(context, systemDark, useDeviceWallpaperColor = false, showWallpaper = false).apply {
                    setScene(null, icons)
                    previews[mode] = this
                }
            }
            val card = choice(labels.getString(mode.label), sample, 54) {
                profile = profile.copy(mode = mode)
                render(emptySet())
                save(DesktopAppearance.MODE, mode.ordinal)
            }
            modeButtons[mode] = card
            modes.addView(card.item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.addView(modes)

        fun slider(title: Int, range: Int, value: Int, change: (Int) -> Unit): GradientDrawable {
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
            colorControls.addView(holder, LinearLayout.LayoutParams(-1, dp(46)))
            return gradient
        }
        fun direction(colors: IntArray): IntArray {
            if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) colors.reverse()
            return colors
        }
        fun updateSaturationTrack(track: GradientDrawable) {
            track.colors = direction(intArrayOf(Color.WHITE, Color.HSVToColor(floatArrayOf(profile.hue.toFloat(), 1f, 1f))))
        }
        lateinit var saturationTrack: GradientDrawable
        slider(R.string.desktop_appearance_hue, 360, profile.hue) {
            profile = profile.copy(hue = it)
            updateSaturationTrack(saturationTrack)
            render(setOf(Mode.TINTED))
            save(DesktopAppearance.HUE, it, dragging = true)
        }.colors = direction(intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED))
        saturationTrack = slider(R.string.desktop_appearance_saturation, 100, profile.saturation) {
            profile = profile.copy(saturation = it)
            render(setOf(Mode.TINTED))
            save(DesktopAppearance.SATURATION, it, dragging = true)
        }
        updateSaturationTrack(saturationTrack)
        body.addView(colorControls)
        val themes = LinearLayout(context).apply {
            isBaselineAligned = false
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        for (theme in listOf(Theme.LIGHT, Theme.DARK, Theme.AUTO)) {
            val sample = FrameLayout(context).apply {
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
                save(DesktopAppearance.THEME, theme.ordinal)
            }
            themeButtons[theme] = toggle
            themes.addView(toggle.item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        themeControls.addView(themes)
        body.addView(themeControls)
        render()
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            body.layoutTransition = LayoutTransition().apply { setDuration(200L) }
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(body)
        }
        native.compact.invoke(dialog, false)
        native.outsideMask.invoke(dialog, Color.TRANSPARENT)
        dialog.setContentView(scroll)
        val screenWidth = context.resources.configuration.screenWidthDp
        if (screenWidth > 0) native.width?.invoke(dialog, dp((screenWidth - 24).coerceIn(1, 480)))
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            clearFlags(FLAG_BLUR_BEHIND or FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
        dialog.setOnDismissListener {
            handler.removeCallbacks(flush)
            flush.run()
        }
        dialog.show()
        native.panelBackground?.let { setPanelBackground ->
            scroll.doOnPreDraw {
                runCatching {
                    val panelId = context.resources.getIdentifier("design_bottom_sheet", "id", context.packageName)
                    val panel = dialog.findViewById<View>(panelId) ?: return@runCatching
                    val material = createOplusPopupBackdrop(panel, ListPopupDefaults.materialBlurSpec(systemDark),
                        dp(28).toFloat()) ?: return@runCatching
                    setPanelBackground.invoke(dialog, material)
                }.onFailure { GlassLogger.limited("DesktopAppearance", "panelBlur") { "COUI material unavailable: ${it.message}" } }
            }
        }
        // Wallpaper I/O must not delay the host sheet animation.
        Thread({
            val wallpaper = runCatching {
                WallpaperHelper.getRawScaledWallpaper(host.applicationContext)?.let(WallpaperHelper::forSoftwareCanvas)
            }.getOrNull()
            if (wallpaper != null) handler.post {
                if (dialog.isShowing) previews.values.forEach { it.setWallpaper(wallpaper) }
            }
        }, "CPI appearance wallpaper").start()
        GlassLogger.i("DesktopAppearance", "Opened native ColorOS appearance sheet")
        return dialog
    }

    private data class Choice(val item: LinearLayout, val label: TextView)
}
