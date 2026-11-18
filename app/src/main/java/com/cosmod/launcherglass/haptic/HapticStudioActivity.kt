package com.cosmod.launcherglass.haptic

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.ColorosScrollView
import com.cosmod.launcherglass.ui.CouiAnchoredDialog
import com.cosmod.launcherglass.ui.CouiCardHost
import com.cosmod.launcherglass.ui.CouiField
import com.cosmod.launcherglass.ui.CouiIconButton
import com.cosmod.launcherglass.ui.CouiInformation
import com.cosmod.launcherglass.ui.CouiPopup
import com.cosmod.launcherglass.ui.CouiPreference
import com.cosmod.launcherglass.ui.CouiSelection
import com.cosmod.launcherglass.ui.CouiSlider
import com.cosmod.launcherglass.ui.CouiSlot
import com.cosmod.launcherglass.ui.CouiSwitchRow
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.ui.UiLanguage
import com.cosmod.launcherglass.ui.onextbox.ColorOs17Button
import com.cosmod.launcherglass.ui.onextbox.ColorOsAppBarBlurView
import com.cosmod.launcherglass.ui.onextbox.SettingsDivider
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class HapticStudioActivity : AppCompatActivity() {
    private enum class Page { HOME, GENERATOR, EDITOR, LAB, LIBRARY, HARDWARE }
    private var page = Page.HOME
    private lateinit var root: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var scroller: ColorosScrollView
    private lateinit var motor: HapticMotor
    private lateinit var player: HapticPreview
    private lateinit var store: HapticLibrary
    private lateinit var pattern: HapticPattern
    private var original: HapticPattern? = null
    private var audio: Uri? = null
    private var waveform: HapticAnalysis.Track? = null
    private var selectedId: String? = null
    private var entries = emptyList<HapticLibrary.Entry>()
    private var style = HapticStyle.BALANCED
    private var generator = style.options()
    private var cursor = 0
    private var selected: Int? = null
    private var regionStart = 0
    private var regionEnd: Int? = null
    private var compare = false
    private var previewAudio = true
    private var channel = HapticChannel.AMPLITUDE
    private var drawMode = HapticDrawMode.NAVIGATE
    private var graph: HapticTimeline? = null
    private var playback: HapticPlaybackBar? = null
    private var labCursor = 0
    private var labFromEditor = false
    private var topInset = 0
    private var bottomInset = 0
    private var toolbar: LinearLayout? = null
    private var headerBlur: ColorOsAppBarBlurView? = null
    private var popup: CouiPopup? = null
    private var dragBefore: HapticPattern? = null
    private val undo = ArrayDeque<HapticPattern>()
    private val redo = ArrayDeque<HapticPattern>()
    private val work = Executors.newSingleThreadExecutor()
    private var busy = false
    private var dialog: CouiAnchoredDialog? = null
    private var slide = HapticSlide.INTENSITY
    private var lastContinuousSlide = slide
    private var curve = HapticCurve.SMOOTH_STEP
    private var adsr = HapticAdsr()
    private var labDuration = 900
    private var labStrength = 70
    private var labSharpness = 65
    private var labFrequency = 50
    private var labFrequencyEnd = 80
    private var labDensity = 3
    private var taps = HapticTapTrain()
    private var labLeadIn = 0
    private var labTail = 0
    private var tapTail = 0
    private var repeatLab = false
    private var repeatTaps = false
    private val labLength get() = if (slide == HapticSlide.TAPS) taps.length + tapTail
        else labLeadIn + labDuration + labTail
    private var dark = false
    private val canvasColor get() = ContextCompat.getColor(this, R.color.cpi_ui_background)
    private val surface get() = ContextCompat.getColor(this, R.color.cpi_ui_panel)
    private val ink get() = ContextCompat.getColor(this, R.color.cpi_ui_text)
    private val accent get() = ContextCompat.getColor(this, R.color.cpi_ui_accent)
    private var effects = true
    private val ru get() = resources.configuration.locales[0].language == "ru"
    private fun t(en: String, russian: String) = if (ru) russian else en

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        analyze(uri, false)
    }
    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.let {
            IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } ?: return@registerForActivityResult
        val actual = if (RingtoneManager.isDefault(uri)) actualRingtone() else uri
        actual?.let { analyze(it, false) } ?: message(t("No system ringtone selected", "Системный рингтон не выбран"))
    }
    private val openPattern = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            task({ requireNotNull(contentResolver.openInputStream(it)).use { input -> HapticPatternCodec.read(input, false) } }) { imported ->
                val hz = imported.points.any { point -> point.hz > 0 }
                val pitch = imported.points.any { point -> point.pitch != 0f }
                if ((hz && motor.frequency == null) || (pitch && !motor.richTap)) {
                    showDialog(t("Adapt imported pattern?", "Адаптировать паттерн?")) { box, d ->
                        box.addView(summary(t("Hardware frequency data will be removed. The amplitude curve will be kept.",
                            "Частотные параметры будут удалены. Кривая амплитуды сохранится.")))
                        box.addView(button(t("Adapt", "Адаптировать")) {
                            acceptImport(imported.copy(points = imported.points.map { it.copy(
                                hz = if (motor.frequency == null) 0f else it.hz,
                                pitch = if (!motor.richTap) 0f else it.pitch) }))
                            d.dismiss()
                        })
                        box.addView(button(t("Open as is", "Открыть как есть")) { acceptImport(imported); d.dismiss() })
                    }
                } else acceptImport(imported)
            }
        }
    }
    private var exportProject: HapticPattern? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = exportProject ?: pattern
        exportProject = null
        if (uri != null) task({
            requireNotNull(contentResolver.openOutputStream(uri, "wt")).use {
                it.write(HapticPatternCodec.encode(snapshot, false).toByteArray(Charsets.UTF_8))
            }
        }) { message(t("Pattern exported", "Паттерн экспортирован")) }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences(SettingsProvider.PREFS_NAME, MODE_PRIVATE)
        delegate.localNightMode = when (prefs.getInt("ui_theme", 0)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        effects = prefs.getBoolean("coloros_ui_effects", true)
        motor = HapticMotor(this)
        store = HapticLibrary(this)
        pattern = HapticPattern.empty(t("Untitled pattern", "Новый паттерн"))
        player = HapticPreview(this, motor, { ms ->
            graph?.cursor = ms
            playback?.update(ms, player.playing)
        }, { message(t("Cannot play this selection", "Не удалось воспроизвести фрагмент")) })
        root = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        setContentView(root)
        root.setBackgroundColor(canvasColor)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, 0, safe.right, 0)
            topInset = safe.top
            bottomInset = safe.bottom
            applyInsets()
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (popup != null) popup?.close()
                else if (page == Page.HOME) finish() else {
                    player.stop()
                    val destination = if (page == Page.LAB && labFromEditor) Page.EDITOR else Page.HOME
                    labFromEditor = false
                    show(destination)
                }
            }
        })
        show(Page.HOME)
        task(store::read) { entries = it; if (page == Page.LIBRARY) show(preserveScroll = true) }
    }

    override fun onStop() { player.stop(); super.onStop() }
    override fun onDestroy() {
        dialog?.dispose()
        work.shutdownNow()
        super.onDestroy()
    }

    private fun show(next: Page = page, preserveScroll: Boolean = false) {
        val savedScroll = if (preserveScroll && ::scroller.isInitialized) scroller.scrollY else 0
        page = next
        popup = null
        root.removeAllViews()
        graph = null
        displayPointBox = null
        playback = null
        body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(10)) }
        scroller = ColorosScrollView(this, canAnimate()).apply {
            clipToPadding = false
            addView(body)
        }
        root.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        headerBlur = if (effects) ColorOsAppBarBlurView(this, dark, canvasColor).apply {
            updateAppearance(dark, canvasColor, 0f)
            root.addView(this, FrameLayout.LayoutParams(-1, 0, Gravity.TOP))
        } else null
        val toolbarView = row().apply {
            setPadding(dp(16), 0, dp(16), 0)
            if (!effects) setBackgroundColor(canvasColor)
        }
        toolbarView.addView(CouiIconButton(this, dark, accent, ink, surface, R.drawable.ic_arrow_back, t("Back", "Назад"), effects)
            .apply { setOnClickListener { onBackPressedDispatcher.onBackPressed() } },
            LinearLayout.LayoutParams(dp(46), dp(46)))
        toolbarView.addView(CouiSlot(this, dark, accent) {
            Text(title(next), Modifier.padding(start = 6.dp).semantics { heading() },
                style = COUITheme.textStyles.title4, color = COUITheme.colorScheme.onBackground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar = toolbarView
        root.addView(toolbarView, FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP))
        applyInsets()
        scroller.setOnScrollChangeListener(NestedScrollView.OnScrollChangeListener { _, _, y, _, _ ->
            headerBlur?.updateAppearance(dark, canvasColor, (y / dp(30).toFloat()).coerceIn(0f, 1f))
        })
        when (page) {
            Page.HOME -> home()
            Page.GENERATOR -> generatorPage()
            Page.EDITOR -> editor()
            Page.LAB -> lab()
            Page.LIBRARY -> library()
            Page.HARDWARE -> hardware()
        }
        if (savedScroll > 0) scroller.post { scroller.scrollTo(0, savedScroll) }
    }

    private fun applyInsets() {
        if (!::scroller.isInitialized) return
        scroller.setPadding(0, topInset + dp(57), 0, bottomInset)
        toolbar?.let { it.layoutParams = (it.layoutParams as FrameLayout.LayoutParams).apply { topMargin = topInset } }
        headerBlur?.let { it.layoutParams = it.layoutParams.apply { height = topInset + dp(64) } }
    }

    private fun canAnimate() = effects && ValueAnimator.areAnimatorsEnabled()

    private fun home() {
        card(body, t("Current pattern", "Текущий паттерн")) { box ->
            box.addView(CouiInformation(this, dark, accent, pattern.name, "${pattern.points.size} " + t("points", "точек")))
            previewControls(box)
        }
        card(body, t("Studio", "Студия"), divided = true) { box ->
            val tools = listOf(
                Page.GENERATOR to R.drawable.ic_catalog_motion,
                Page.EDITOR to R.drawable.ic_cpi_edit,
                Page.LAB to R.drawable.ic_catalog_speed,
                Page.LIBRARY to R.drawable.ic_catalog_folder,
                Page.HARDWARE to R.drawable.ic_cpi_module_outline,
            )
            for ((destination, icon) in tools) {
                link(box, title(destination), icon = icon) { player.stop(); show(destination) }
            }
        }
        card(body, t("Incoming calls", "Входящие звонки"), divided = true) { box ->
            toggle(box, t("Use current vibration settings", "Использовать паттерны студии"), HapticRingStore.enabled(this)) {
                HapticRingStore.enable(this, it)
            }
            val adapter = HapticRingStore.status(this)
            val state = when (adapter) {
                "coloros17" -> t("ColorOS ringtone adapter loaded", "Адаптер звонков ColorOS загружен")
                "aosp" -> t("Android ringtone adapter loaded", "Адаптер звонков Android загружен")
                "unsupported" -> t("The system ringtone path is preserved on this firmware.", "На этой прошивке используется штатная вибрация.")
                else -> t("Enable Telecom in LSPosed. On firmware that hosts Telecom in Android, enable System Framework too.",
                    "Включи область Telecom в LSPosed. Если Telecom работает внутри Android, нужна и область «Системный фреймворк».")
            }
            box.addView(CouiInformation(this, dark, accent, t("Connection", "Подключение"), state))
            val tags = listOf("default", "sim1", "sim2")
            val captions = listOf(t("Default / matching ringtone", "По умолчанию / по рингтону"), "SIM 1", "SIM 2")
            for ((index, tag) in tags.withIndex()) {
                val current = HapticRingStore.bindingName(this, tag) ?: t("System", "Системная")
                link(box, captions[index], current) {
                    choose(t("Call vibration", "Вибрация звонка"), listOf(t("Use current pattern", "Использовать текущий"),
                        t("Use current for any ringtone", "Текущий для любого рингтона"),
                        t("Restore system vibration", "Вернуть штатную вибрацию"))) { choice ->
                        when (choice) {
                            2 -> { HapticRingStore.put(this, tag, null); show(preserveScroll = true) }
                            else -> {
                                val snapshot = if (choice == 1) pattern.copy(audio = null) else pattern
                                task({
                                    val effect = HapticWaveform.sample(snapshot, 0, snapshot.length, motor.amplitude)
                                    HapticRingStore.put(this, tag, snapshot, effect)
                                }) { show(preserveScroll = true) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun generatorPage() {
        card(body, t("Audio source", "Источник аудио"), divided = true) { box ->
            link(box, t("Choose audio file", "Выбрать аудиофайл")) { if (!busy) openAudio.launch(arrayOf("audio/*")) }
            link(box, t("Choose ringtone", "Выбрать рингтон")) {
                if (!busy) runCatching { ringtonePicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, audio)
                }) }.onFailure { actualRingtone()?.let { analyze(it, false) } }
            }
            link(box, t("Use current system ringtone", "Взять текущий рингтон системы")) {
                if (!busy) actualRingtone()?.let { analyze(it, false) }
                    ?: message(t("No system ringtone selected", "Системный рингтон не выбран"))
            }
            waveform?.let { track ->
                val tempo = track.bpm?.let { "$it BPM" } ?: t("Tempo not identified", "Темп не определён")
                box.addView(CouiInformation(this, dark, accent, t("Audio analyzed", "Аудио проанализировано"),
                    "${clock(track.length)} · $tempo"))
            }
        }
        card(body, t("Generation", "Генерация")) { box ->
            option(box, t("Generation style", "Стиль генерации"), style.ordinal, HapticStyle.entries.map(::styleName)) {
                style = HapticStyle.entries[it]
                generator = style.options()
                show(preserveScroll = true)
            }
            box.addView(button(if (busy) t("Analyzing…", "Анализ…") else t("Generate", "Сгенерировать"),
                primary = true, enabled = !busy) {
                val source = audio ?: pattern.audio?.let(Uri::parse)
                if (source == null) message(t("Choose audio first", "Сначала выбери аудио"))
                else if (!busy) analyze(source, true)
            }, actionMargin())
        }
        if (style != HapticStyle.TAPS) card(body, t("Continuous vibration", "Непрерывная вибрация")) { box ->
            generatorSlider(box, t("Accent sensitivity", "Чувствительность к акцентам"), 0, 100, generator.sensitivity) { generator.copy(sensitivity = it) }
            generatorSlider(box, t("Detail", "Детализация"), 0, 100, generator.detail) { generator.copy(detail = it) }
            generatorSlider(box, t("Intensity", "Интенсивность"), 0, 100, generator.strength) { generator.copy(strength = it) }
            generatorSlider(box, t("Smoothness", "Плавность"), 0, 100, generator.smoothness) { generator.copy(smoothness = it) }
            generatorSlider(box, t("Bass emphasis", "Акцент на басах"), 0, 100, generator.bass) { generator.copy(bass = it) }
            generatorSlider(box, t("Melody sensitivity", "Чувствительность к мелодии"), 0, 100, generator.melody) { generator.copy(melody = it) }
            generatorSlider(box, t("Impulse length · ms", "Длительность импульса · мс"), 25, 400, generator.impulseMs) { generator.copy(impulseMs = it) }
            generatorSlider(box, t("Dynamics", "Динамика"), 0, 100, generator.dynamics) { generator.copy(dynamics = it) }
        }
        if (style == HapticStyle.TAPS || style == HapticStyle.MIXED) card(body, t("Taps", "Постукивания")) { box ->
            generatorSlider(box, t("Tap strength", "Сила удара"), 0, 100, generator.tapStrength) { generator.copy(tapStrength = it) }
            generatorSlider(box, t("Tap sensitivity", "Чувствительность к ударам"), 0, 100, generator.tapSensitivity) { generator.copy(tapSensitivity = it) }
        }
        card(body, t("Synchronization", "Синхронизация")) { box ->
            generatorSlider(box, t("Accent limit · per second", "Предел акцентов · в секунду"), 1, 16, generator.repetitions) { generator.copy(repetitions = it) }
            generatorSlider(box, t("Noise suppression", "Подавление слабых звуков"), 0, 100, generator.noise) { generator.copy(noise = it) }
            slider(box, t("Sync offset · ms", "Сдвиг синхронизации · мс"), -1500, 1500, pattern.offset) {
                changePattern(pattern.copy(offset = it))
            }
        }
    }

    private fun editor() {
        card(body, t("Timeline", "Временная шкала")) { box ->
            val channels = availableChannels()
            option(box, t("Envelope", "Огибающая"), channels.indexOf(channel).coerceAtLeast(0), channels.map(::channelName)) {
                channel = channels[it]; show(preserveScroll = true)
            }
            option(box, t("Timeline tool", "Инструмент"), drawMode.ordinal,
                listOf(t("Navigate", "Навигация"), t("Point", "Точка"), t("Brush", "Кисть"))) {
                player.stop()
                drawMode = HapticDrawMode.entries[it]
                show(preserveScroll = true)
            }
            addGraph(box, pattern, true)
            buttons(box, t("Zoom out", "Уменьшить"), t("Zoom in", "Увеличить"),
                { graph?.zoomBy(.5f) }, { graph?.zoomBy(2f) })
            previewControls(box)
        }
        card(body, t("Add effect at cursor", "Добавить эффект в позицию курсора"), divided = true) { box ->
            link(box, t("Vibration slide", "Слайд вибрации")) { openLab(lastContinuousSlide) }
            link(box, t("Taps", "Постукивания")) { openLab(HapticSlide.TAPS) }
        }
        card(body, t("Control points", "Контрольные точки")) { box ->
            displayPointBox = column()
            box.addView(displayPointBox)
            refreshPointControls()
            buttons(box, t("Undo", "Отменить"), t("Redo", "Повторить"),
                { restore(undo, redo) }, { restore(redo, undo) })
        }
        card(body, t("Fragment", "Фрагмент")) { box ->
            link(box, t("Set exact position", "Задать точное время"), "${cursor} ms") {
                numberDialog(t("Position · ms", "Время · мс"), cursor, 0..pattern.length, ::seek)
            }
            buttons(box, t("Selection start", "Начало фрагмента"), t("Selection end", "Конец фрагмента"), {
                regionStart = cursor.coerceAtMost((regionEnd ?: pattern.length) - 1)
                show(preserveScroll = true)
            }, {
                regionEnd = cursor.coerceAtLeast(regionStart + 1).coerceAtMost(pattern.length)
                show(preserveScroll = true)
            })
            val range = regionEnd
            if (range != null) {
                box.addView(CouiInformation(this, dark, accent, t("Selected fragment", "Выбранный фрагмент"),
                    "${clock(regionStart)} – ${clock(range)}"))
                buttons(box, t("Move group", "Переместить группу"), t("Copy group", "Копировать группу"),
                    { transferGroup(false) }, { transferGroup(true) })
                link(box, t("Clear selection", "Снять выделение")) { regionStart = 0; regionEnd = null; show(preserveScroll = true) }
            }
        }
        card(body, t("Pattern", "Паттерн")) { box ->
            slider(box, t("Global intensity", "Общая интенсивность"), 0, 150, pattern.intensity, "%") {
                changePattern(pattern.copy(intensity = it))
            }
            slider(box, t("Fade in · ms", "Плавное появление · мс"), 0, 5000, pattern.fadeIn) { changePattern(pattern.copy(fadeIn = it)) }
            slider(box, t("Fade out · ms", "Плавное затухание · мс"), 0, 5000, pattern.fadeOut) { changePattern(pattern.copy(fadeOut = it)) }
            link(box, t("Duration · ms", "Длительность · мс"), "${pattern.length}") {
                numberDialog(t("Duration · ms", "Длительность · мс"), pattern.length, 80..MAX_TRACK_MS) { length ->
                    val points = pattern.points.filter { it.ms < length && it.ms != pattern.length }
                    changePattern(pattern.copy(length = length, points = points + HapticPoint(length, 0f)))
                    show(preserveScroll = true)
                }
            }
            divider(box)
            link(box, t("Reset pattern", "Сбросить паттерн"), icon = R.drawable.ic_restart) { confirmPatternReset() }
        }
    }

    private fun pointControls(box: LinearLayout, point: HapticPoint) {
        buttons(box, t("Move", "Переместить"), t("Duplicate", "Дублировать"), {
            movePoint(point, false)
        }, { movePoint(point, true) })
        slider(box, t("Amplitude", "Амплитуда"), 0, 100, (point.amplitude * 100).roundToInt(), "%") {
            editPoint(point.ms) { p -> p.copy(amplitude = it * .01f) }
        }
        if (motor.frequency != null) {
            val range = frequencyLimits
            slider(box, t("Frequency · Hz", "Частота · Гц"), range.start.roundToInt(), range.endInclusive.roundToInt(),
                point.hz.takeIf { it > 0 }?.roundToInt() ?: range.start.roundToInt()) { editPoint(point.ms) { p -> p.copy(hz = it.toFloat()) } }
        }
        if (motor.richTap) slider(box, t("RichTap frequency offset", "Относительная частота RichTap"), -100, 100, point.pitch.roundToInt()) {
            editPoint(point.ms) { p -> p.copy(pitch = it.toFloat()) }
        }
        if (motor.envelope != null) slider(box, t("Sharpness", "Резкость"), 0, 100, (point.sharpness * 100).roundToInt(), "%") {
            editPoint(point.ms) { p -> p.copy(sharpness = it * .01f) }
        }
        option(box, t("Incoming curve", "Кривая перехода"), point.curve.ordinal, HapticCurve.entries.map(::curveName)) {
            editPoint(point.ms) { p -> p.copy(curve = HapticCurve.entries[it]) }; show(preserveScroll = true)
        }
        if (point.curve == HapticCurve.BEZIER) {
            slider(box, "Bézier 1", 0, 100, (point.bezierIn * 100).roundToInt()) { editPoint(point.ms) { p -> p.copy(bezierIn = it * .01f) } }
            slider(box, "Bézier 2", 0, 100, (point.bezierOut * 100).roundToInt()) { editPoint(point.ms) { p -> p.copy(bezierOut = it * .01f) } }
        }
    }

    private fun openLab(type: HapticSlide) {
        player.stop()
        labFromEditor = page == Page.EDITOR
        slide = type
        labCursor = 0
        show(Page.LAB)
    }

    private fun lab() {
        val types = HapticSlide.entries.filter {
            (it != HapticSlide.FREQUENCY || motor.frequency != null || motor.richTap) &&
                (it != HapticSlide.SHARPNESS || motor.envelope != null)
        }
        card(body, t("Effect preview", "Предпросмотр эффекта")) { box ->
            option(box, t("Effect", "Эффект"), types.indexOf(slide).coerceAtLeast(0), types.map(::slideName)) {
                player.stop()
                labCursor = 0
                slide = types[it]
                if (slide != HapticSlide.TAPS) lastContinuousSlide = slide
                show(preserveScroll = true)
            }
            addGraph(box, labPattern(), false)
            playbackBar(box, labLength, labCursor, ::seekLab) {
                val project = labPattern()
                val repeat = if (slide == HapticSlide.TAPS) repeatTaps else repeatLab
                player.play(project, null, labCursor.takeIf { it < project.length } ?: 0, project.length, repeat, 0)
            }
            divider(box)
            toggle(box, t("Repeat preview", "Повторять предпросмотр"), if (slide == HapticSlide.TAPS) repeatTaps else repeatLab) {
                player.stop()
                if (slide == HapticSlide.TAPS) repeatTaps = it else repeatLab = it
            }
            divider(box)
            box.addView(button(t("Insert at cursor", "Вставить в дорожку"), primary = true) {
                val project = labPattern()
                val updated = pattern.insertEffect(cursor, project)
                if (updated == null) message(t("Pattern limit reached", "Достигнут предел длины или числа точек"))
                else {
                    val end = (cursor + project.length).coerceAtMost(updated.length)
                    changePattern(updated)
                    labFromEditor = false
                    resetSelection()
                    cursor = end
                    show(Page.EDITOR)
                }
            }, actionMargin())
        }
        card(body, if (slide == HapticSlide.TAPS) t("Tap settings", "Настройки постукиваний")
            else t("Slide settings", "Настройки слайда")) { box ->
            fun control(title: String, min: Int, max: Int, initial: Int, suffix: String, set: (Int) -> Unit) {
                slider(box, title, min, max, initial, suffix) { value ->
                    player.stop()
                    set(value)
                    graph?.pattern = labPattern()
                    playback?.duration = labLength
                    seekLab(labCursor)
                }
            }
            if (slide == HapticSlide.TAPS) {
                option(box, t("Tap output", "Тип постукивания"), if (taps.customPulse) 1 else 0,
                    listOf(t("System click", "Штатный щелчок"), t("Custom pulse", "Настраиваемый импульс"))) {
                    player.stop()
                    taps = taps.copy(customPulse = it == 1)
                    show(preserveScroll = true)
                }
                control(t("Number of taps", "Количество ударов"), 1, 16, taps.count, "") {
                    val showLast = (taps.count > 1) != (it > 1)
                    taps = taps.copy(count = it)
                    if (showLast) show(preserveScroll = true)
                }
                control(t("First tap strength", "Сила первого удара"), 0, 100, taps.firstStrength, "%") {
                    taps = taps.copy(firstStrength = it)
                }
                if (taps.count > 1) control(t("Last tap strength", "Сила последнего удара"), 0, 100, taps.lastStrength, "%") {
                    taps = taps.copy(lastStrength = it)
                }
                val ms = t(" ms", " мс")
                control(t("Interval", "Интервал"), 80, 900, taps.spacing, ms) { taps = taps.copy(spacing = it) }
                control(t("Start delay", "Пауза перед первым ударом"), 0, 1500, taps.delay, ms) { taps = taps.copy(delay = it) }
                control(t("Alternating intervals", "Чередование интервалов"), 0, 35, taps.swing, "%") { taps = taps.copy(swing = it) }
                if (taps.customPulse) control(t("Pulse width", "Длительность удара"), 12, 44, taps.pulseWidth, ms) {
                    taps = taps.copy(pulseWidth = it)
                }
            } else {
                val ms = t(" ms", " мс")
                control(t("Amplitude", "Амплитуда"), 0, 100, labStrength, "%") { labStrength = it }
                control(t("Duration", "Длительность"), 80, 5000, labDuration, ms) { labDuration = it }
                control(t("Start delay", "Пауза перед эффектом"), 0, 1500, labLeadIn, ms) { labLeadIn = it }
                control(t("Attack", "Нарастание"), 1, 2000, adsr.attack, ms) { adsr = adsr.copy(attack = it) }
                control(t("Decay", "Начальное затухание"), 1, 2000, adsr.decay, ms) { adsr = adsr.copy(decay = it) }
                control(t("Sustain", "Уровень удержания"), 0, 100, adsr.sustain, "%") { adsr = adsr.copy(sustain = it) }
                control(t("Release", "Окончательное затухание"), 1, 2000, adsr.release, ms) { adsr = adsr.copy(release = it) }
                if (slide == HapticSlide.WAVE || slide == HapticSlide.PULSE_FLOW) {
                    control(t("Rhythm density", "Плотность ритма"), 1, 8, labDensity, "") { labDensity = it }
                }
                if (motor.envelope != null) control(t("Sharpness", "Резкость"), 0, 100, labSharpness, "%") { labSharpness = it }
                if (motor.richTap || motor.frequency != null) {
                    control(t("Start frequency position", "Начальная позиция частоты"), 0, 100, labFrequency, "%") { labFrequency = it }
                    if (slide == HapticSlide.FREQUENCY)
                        control(t("End frequency position", "Конечная позиция частоты"), 0, 100, labFrequencyEnd, "%") { labFrequencyEnd = it }
                }
                option(box, t("Transition smoothing", "Плавность перехода"), curve.ordinal, HapticCurve.entries.map(::curveName)) {
                    player.stop()
                    curve = HapticCurve.entries[it]
                    show(preserveScroll = true)
                }
            }
            control(t("End pause", "Пауза после эффекта"), 0, 2000,
                if (slide == HapticSlide.TAPS) tapTail else labTail, t(" ms", " мс")) {
                if (slide == HapticSlide.TAPS) tapTail = it else labTail = it
            }
            divider(box)
            link(box, t("Reset effect settings", "Сбросить настройки эффекта")) { resetLab() }
        }
    }

    private fun labPattern(): HapticPattern {
        if (slide == HapticSlide.TAPS) {
            val points = taps.points()
            return HapticPattern(slideName(slide), labLength,
                if (tapTail == 0) points else points + HapticPoint(labLength, 0f))
        }
        val hz = motor.frequency
        fun absolute(value: Int) = if (hz == null) 0f else hz.start + (hz.endInclusive - hz.start) * value / 100f
        val raw = slidePoints(slide, labLeadIn, labDuration, labStrength * .01f, labSharpness * .01f,
            absolute(labFrequency), absolute(labFrequencyEnd), labDensity, adsr, curve)
        val points = if (hz == null && motor.richTap) raw.map {
            val from = (labFrequency - 50) * 2f
            val to = if (slide == HapticSlide.FREQUENCY) (labFrequencyEnd - 50) * 2f else from
            it.copy(pitch = from + (to - from) * (it.ms - labLeadIn) / labDuration)
        } else raw
        return HapticPattern(slideName(slide), labLength, buildList {
            if (labLeadIn > 0) add(points.first().copy(ms = 0))
            addAll(points)
            if (labTail > 0) add(points.last().copy(ms = labLength))
        })
    }

    private fun resetLab() {
        player.stop()
        labCursor = 0
        if (slide == HapticSlide.TAPS) {
            taps = HapticTapTrain()
            tapTail = 0
            repeatTaps = false
            show(preserveScroll = true)
            return
        }
        curve = HapticCurve.SMOOTH_STEP
        adsr = HapticAdsr()
        labDuration = 900
        labStrength = 70
        labSharpness = 65
        labFrequency = 50
        labFrequencyEnd = 80
        labDensity = 3
        labLeadIn = 0
        labTail = 0
        repeatLab = false
        labCursor = 0
        show(preserveScroll = true)
    }

    private fun library() {
        card(body, t("Current pattern", "Текущий паттерн")) { box ->
            box.addView(CouiInformation(this, dark, accent, pattern.name, clock(pattern.length)))
            buttons(box, t("New", "Новый"), t("Save", "Сохранить"), {
                acceptImport(HapticPattern.empty(t("Untitled pattern", "Новый паттерн")))
            }, { save(false) })
            buttons(box, t("Rename", "Переименовать"), t("Duplicate", "Дублировать"),
                { save(false) }, { save(true) })
        }
        card(body, t("Transfer", "Перенос"), divided = true) { box ->
            link(box, t("Import pattern", "Импортировать паттерн")) {
                if (!busy) openPattern.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
            link(box, t("Export pattern", "Экспортировать паттерн")) {
                exportProject = pattern
                val fileName = pattern.name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().trim('.').ifEmpty { "haptic" }
                export.launch("$fileName.cpi-haptic.json")
            }
        }
        card(body, t("Saved patterns", "Сохранённые паттерны"), divided = true) { box ->
            if (entries.isEmpty()) box.addView(CouiInformation(this, dark, accent,
                t("Library is empty", "Библиотека пуста"), t("Save the current pattern to add it here.", "Сохрани текущий паттерн, чтобы добавить его сюда.")))
            for (entry in entries) link(box, entry.pattern.name,
                if (selectedId == entry.id) t("Current", "Текущий") else clock(entry.pattern.length)) {
                choose(entry.pattern.name, listOf(t("Open", "Открыть"), t("Remove", "Удалить"))) {
                    if (it == 0) acceptImport(entry.pattern, entry.id)
                    else task({ store.delete(entry.id); store.read() }) { updated ->
                        entries = updated
                        if (selectedId == entry.id) selectedId = null
                        show(preserveScroll = true)
                    }
                }
            }
        }
    }

    private fun hardware() {
        val hardware = motor.envelope
        val tier = when {
            !motor.present -> t("No vibrator", "Вибромотор недоступен")
            motor.frequency != null || motor.richTap -> "Pro Haptics"
            motor.amplitude || motor.primitives > 0 || hardware != null -> "Advanced Haptics"
            else -> "Basic Haptics"
        }
        card(body, divided = true) { box ->
            fun report(title: String, value: String) { box.addView(CouiInformation(this, dark, accent, title, value)) }
            fun status(value: Boolean) = if (value) t("Available", "Доступно") else t("Unavailable", "Недоступно")
            report(t("Hardware level", "Возможности устройства"), tier)
            report(t("Amplitude control", "Управление амплитудой"), status(motor.amplitude))
            report(t("Native primitives", "Штатные примитивы"), "${motor.primitives} / 8")
            report("Android PWLE", hardware?.let {
                "${it.points} · ${it.minimum}–${it.maximum} ms"
            } ?: status(false))
            report(t("Frequency range", "Диапазон частот"), motor.frequency?.let {
                "${it.start.roundToInt()}–${it.endInclusive.roundToInt()} Hz"
            } ?: status(false))
            report("RichTap HE 2.0", if (motor.richTap)
                t("Vendor reports support. Tuning is relative.", "Драйвер сообщает о поддержке. Шкала частоты относительная.") else status(false))
        }
    }

    private fun addGraph(box: LinearLayout, project: HapticPattern, editable: Boolean) {
        val timeline = HapticTimeline(this, ink, accent, ::drawStroke, if (editable) ::seek else ::seekLab).apply {
            pattern = project
            source = if (editable && project.audio == audio?.toString()) waveform else null
            channel = if (editable) this@HapticStudioActivity.channel else HapticChannel.AMPLITUDE
            limits = when (channel) {
                HapticChannel.FREQUENCY -> frequencyLimits
                HapticChannel.RICHTAP -> -100f..100f
                else -> 0f..1f
            }
            selected = this@HapticStudioActivity.selected
            cursor = if (editable) this@HapticStudioActivity.cursor else labCursor
            drawMode = if (editable) this@HapticStudioActivity.drawMode else HapticDrawMode.NAVIGATE
            contentDescription = t("Audio and haptic timeline", "Звуковая и тактильная дорожки")
        }
        box.addView(timeline, LinearLayout.LayoutParams(-1, dp(if (editable) 246 else 172)).apply { setMargins(dp(12), dp(7), dp(12), 0) })
        graph = timeline
    }

    private fun previewControls(box: LinearLayout) {
        playbackBar(box, pattern.length, cursor, ::seek, regionStart, ::play)
        divider(box)
        if (pattern.audio != null || original?.audio != null) {
            toggle(box, t("Play audio with vibration", "Воспроизводить звук вместе с вибрацией"), previewAudio) {
                player.stop()
                previewAudio = it
            }
        }
        toggle(box, t("Loop fragment", "Зациклить фрагмент"), pattern.repeat) { changePattern(pattern.copy(repeat = it)) }
        if (original != null) toggle(box, t("Compare with original", "Сравнить с оригиналом"), compare) {
            player.stop()
            compare = it
        }
    }

    private fun playbackBar(box: LinearLayout, duration: Int, position: Int, seek: (Int) -> Unit, start: Int = 0, play: () -> Unit) {
        val bar = HapticPlaybackBar(this, dark, accent, t("Play", "Воспроизвести"), t("Pause", "Пауза"), t("Stop", "Остановить"),
            toggle = {
                if (player.playing) {
                    player.pause()
                    if (page == Page.LAB) labCursor = player.position else cursor = player.position
                } else if (!player.resume()) play()
            },
            stop = { player.stop(); seek(start) },
        )
        bar.duration = duration
        bar.update(if (player.playing) player.position else position, player.playing)
        playback = bar
        box.addView(bar)
    }

    private fun seekLab(position: Int) {
        player.stop()
        labCursor = position.coerceIn(0, labLength)
        graph?.cursor = labCursor
        playback?.update(labCursor, false)
    }

    private fun play() {
        val project = if (compare) original ?: pattern else pattern
        val end = minOf(regionEnd ?: project.length, project.length)
        val lower = regionStart.coerceAtMost(end - 1)
        val start = cursor.takeIf { it in lower until end } ?: lower
        player.play(project, if (previewAudio) project.audio?.let(Uri::parse) else null, start, end, pattern.repeat, lower)
    }

    private fun analyze(uri: Uri, generate: Boolean) {
        val cached = waveform.takeIf { audio == uri }
        val options = generator
        val selectedStyle = style
        val snapshot = pattern
        task({
            val source = cached ?: HapticAudio.analyze(this, uri)
            val points = if (generate) HapticAnalysis.generate(source, selectedStyle, options) else null
            source to points
        }) { (source, points) ->
            audio = uri
            waveform = source
            if (points != null) {
                val project = snapshot.copy(length = source.length, points = points, audio = uri.toString(),
                    style = selectedStyle, generator = options)
                changePattern(project)
                original = project
                cursor = 0
                resetSelection()
                show(Page.EDITOR)
            } else show(preserveScroll = true)
        }
        if (page == Page.GENERATOR) show(preserveScroll = true)
    }

    private fun acceptImport(project: HapticPattern, id: String? = null) {
        player.stop()
        pattern = project
        original = project
        selectedId = id
        audio = project.audio?.let(Uri::parse)
        waveform = null
        style = project.style ?: HapticStyle.BALANCED
        generator = project.generator
        cursor = 0
        resetSelection()
        undo.clear()
        redo.clear()
        show(Page.EDITOR)
        audio?.let { analyze(it, false) }
    }

    private fun save(copy: Boolean) {
        if (busy) return
        askText(t("Pattern name", "Название паттерна"), if (copy) pattern.name.take(72) + t(" copy", " копия") else pattern.name) {
            val snapshot = pattern.copy(name = it)
            val id = if (copy) null else selectedId
            task({ store.save(snapshot, id) to store.read() }) { (saved, list) ->
                selectedId = saved.id
                pattern = saved.pattern
                entries = list
                show(preserveScroll = true)
                message(t("Pattern saved", "Паттерн сохранён"))
            }
        }
    }

    private fun rawPoint(time: Int): HapticPoint = pattern.points.firstOrNull { it.ms == time }
        ?: HapticPoint(time, 0f)
    private fun selectedPoint() = pattern.points.firstOrNull { it.ms == selected }
    private fun editPoint(ms: Int, change: (HapticPoint) -> HapticPoint) {
        changePattern(pattern.replace(change(rawPoint(ms))))
    }

    private fun drawStroke(samples: List<Pair<Int, Float>>, finished: Boolean) {
        if (dragBefore == null) {
            player.stop()
            dragBefore = pattern
        }
        val ms = samples.last().first
        pattern = requireNotNull(dragBefore).paintStroke(channel, samples)
        graph?.pattern = pattern
        selected = (ms - pattern.offset).coerceIn(0, pattern.length)
        graph?.selected = selected
        cursor = ms
        graph?.cursor = ms
        playback?.update(cursor, player.playing)
        if (finished) {
            dragBefore?.takeIf { it != pattern }?.let(::record)
            dragBefore = null
            refreshPointControls()
        }
    }

    private fun seek(ms: Int) {
        player.stop()
        cursor = ms.coerceIn(0, pattern.length)
        val previous = selected
        val nearest = pattern.points.minByOrNull { abs(it.ms + pattern.offset - cursor) }
        selected = nearest?.ms?.takeIf { abs(it + pattern.offset - cursor) <= maxOf(18, pattern.length / 100) }
        graph?.cursor = cursor
        graph?.selected = selected
        playback?.update(cursor, player.playing)
        if (page == Page.EDITOR && previous != selected) refreshPointControls()
    }

    private var displayPointBox: LinearLayout? = null
    private fun refreshPointControls() {
        val box = displayPointBox ?: return
        box.removeAllViews()
        val point = selectedPoint()
        box.addView(CouiInformation(this, dark, accent, t("Control point", "Контрольная точка"),
            point?.let { "${clock(it.ms)} · ${(it.amplitude * 100).roundToInt()}%" }
                ?: t("Tap the timeline to set the position.", "Коснись шкалы, чтобы выбрать время.")))
        buttons(box, t("Add point", "Добавить точку"), t("Delete", "Удалить"), {
            changePattern(pattern.replace(rawPoint(cursor)))
            selected = cursor
            graph?.selected = cursor
            refreshPointControls()
        }, { point?.let {
            if (it.ms in 1 until pattern.length) {
                changePattern(pattern.copy(points = pattern.points.filterNot { p -> p.ms == it.ms }))
                selected = null
                graph?.selected = null
                refreshPointControls()
            }
        } })
        if (point != null) pointControls(box, point)
    }

    private fun movePoint(point: HapticPoint, copy: Boolean) {
        if (!copy && point.ms !in 1 until pattern.length) return
        numberDialog(t("Position · ms", "Время · мс"), (point.ms + if (copy) 100 else 0).coerceIn(1, pattern.length - 1),
            1 until pattern.length) { target ->
            val base = if (copy) pattern else pattern.copy(points = pattern.points.filterNot { it.ms == point.ms })
            changePattern(base.replace(point.copy(ms = target)))
            selected = target
            show(preserveScroll = true)
        }
    }

    private fun transferGroup(copy: Boolean) {
        val end = regionEnd ?: return
        val from = regionStart
        val group = pattern.points.filter { it.ms in from..end && it.ms in 1 until pattern.length }
        if (group.isEmpty()) return
        numberDialog(t("New group start · ms", "Начало группы · мс"), from, 0..(pattern.length - (end - from))) { target ->
            val index = if (copy) pattern.points.associateBy { it.ms }.toMutableMap()
                else pattern.points.filterNot { it in group }.associateBy { it.ms }.toMutableMap()
            for (point in group) {
                val newTime = (point.ms - from + target).coerceIn(1, pattern.length - 1)
                index[newTime] = point.copy(ms = newTime)
            }
            if (index.size <= MAX_HAPTIC_POINTS) {
                changePattern(pattern.copy(points = index.values.sortedBy { it.ms }))
                regionStart = target
                regionEnd = target + (end - from)
                show(preserveScroll = true)
            }
        }
    }

    private fun resetSelection() { regionStart = 0; regionEnd = null; selected = null }
    private fun confirmPatternReset() {
        showDialog(t("Reset pattern?", "Сбросить паттерн?")) { box, dialog ->
            box.addView(summary(t(
                "The vibration track and its adjustments will be cleared. The audio source and length will be kept.",
                "Дорожка вибрации и её настройки будут сброшены. Звук и длительность сохранятся.",
            )))
            buttons(box, t("Cancel", "Отмена"), t("Reset", "Сбросить"), { dialog.dismiss() }, {
                dialog.dismiss()
                compare = false
                changePattern(pattern.resetVibration())
                resetSelection()
                cursor = 0
                style = HapticStyle.BALANCED
                generator = style.options()
                show(preserveScroll = true)
            })
        }
    }

    private fun changePattern(next: HapticPattern) {
        if (pattern == next) return
        player.stop()
        record(pattern)
        pattern = next
        graph?.pattern = next
        playback?.duration = next.length
        cursor = cursor.coerceAtMost(next.length)
        if (regionStart >= next.length || (regionEnd ?: 0) > next.length) resetSelection()
    }

    private fun record(previous: HapticPattern) {
        if (undo.size == 24) undo.removeFirst()
        undo.addLast(previous)
        redo.clear()
    }

    private fun restore(from: ArrayDeque<HapticPattern>, to: ArrayDeque<HapticPattern>) {
        val restored = from.removeLastOrNull() ?: return
        player.stop()
        to.addLast(pattern)
        pattern = restored
        cursor = cursor.coerceAtMost(pattern.length)
        resetSelection()
        show(preserveScroll = true)
    }

    private val frequencyLimits get() = motor.frequency ?: (0f..1f)
    private fun availableChannels() = HapticChannel.entries.filter {
        when (it) {
            HapticChannel.AMPLITUDE -> true
            HapticChannel.FREQUENCY -> motor.frequency != null
            HapticChannel.RICHTAP -> motor.richTap
            HapticChannel.SHARPNESS -> motor.envelope != null
        }
    }
    private fun channelName(value: HapticChannel) = when (value) {
        HapticChannel.AMPLITUDE -> t("Amplitude", "Амплитуда")
        HapticChannel.FREQUENCY -> t("Frequency · Hz", "Частота · Гц")
        HapticChannel.RICHTAP -> t("RichTap relative frequency", "Относительная частота RichTap")
        HapticChannel.SHARPNESS -> t("Sharpness", "Резкость")
    }
    private fun slideName(value: HapticSlide) = listOf(
        t("Intensity slide", "Скольжение амплитуды"), t("Frequency slide", "Скольжение частоты"),
        t("Soft rise", "Мягкое нарастание"), t("Soft fall", "Мягкое затухание"),
        t("Haptic wave", "Волна"), t("Pulse flow", "Поток импульсов"),
        t("Sharpness transition", "Переход резкости"), t("Custom curve", "Своя кривая"),
        t("Taps", "Постукивания"),
    )[value.ordinal]
    private fun curveName(value: HapticCurve) = listOf(
        t("Linear", "Линейная"), t("Ease in", "Плавный старт"), t("Ease out", "Плавный финиш"),
        t("Ease in out", "Плавный старт и финиш"), t("Smooth step", "Мягкая S-кривая"), "Bézier",
    )[value.ordinal]
    private fun styleName(value: HapticStyle) = listOf(
        "Balanced", "Smooth", "Dynamic", "Bass Focus", "Melody Flow", "Precision", "Custom AI",
        t("Taps", "Постукивания"), t("Slides and taps", "Слайды и постукивания"),
    )[value.ordinal]
    private fun title(value: Page) = when (value) {
        Page.HOME -> "Advanced Haptic Studio"
        Page.GENERATOR -> t("Smart Generator", "Генератор")
        Page.EDITOR -> t("Manual Editor", "Редактор")
        Page.LAB -> "Haptic Lab"
        Page.LIBRARY -> t("Pattern Library", "Библиотека")
        Page.HARDWARE -> t("Hardware Capabilities", "Возможности мотора")
    }
    private fun actualRingtone() = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun clock(ms: Int) = "%d:%02d.%02d".format(java.util.Locale.ROOT, ms / 60_000, ms / 1000 % 60, ms / 10 % 100)
    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }
    private fun summary(value: String) = CouiSlot(this, dark, accent) {
        Text(value, Modifier.fillMaxWidth().padding(bottom = 12.dp),
            style = COUITheme.textStyles.body2, color = COUITheme.colorScheme.onSurfaceVariantSummary)
    }
    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
    private fun button(title: String, primary: Boolean = false, enabled: Boolean = true, action: () -> Unit) = CouiSlot(this, dark, accent) {
        ColorOs17Button(title, primary, onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            minimumHeight = 44.dp)
    }
    private fun actionMargin() = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(dp(16), dp(6), dp(16), dp(12))
    }
    private fun buttons(parent: LinearLayout, first: String, second: String, a: () -> Unit, b: () -> Unit) {
        parent.addView(row().apply {
            addView(button(first, action = a), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(button(second, action = b), LinearLayout.LayoutParams(0, -2, 1f))
        }, actionMargin())
    }
    private fun link(parent: LinearLayout, title: String, detail: String? = null, icon: Int? = null, action: () -> Unit) {
        parent.addView(CouiPreference(this, dark, accent, title, detail, icon, accent) { action() })
    }
    private fun toggle(parent: LinearLayout, title: String, state: Boolean, change: (Boolean) -> Unit) {
        parent.addView(CouiSwitchRow(this, dark, accent, title, null, state, false, null, change))
    }
    private fun slider(parent: LinearLayout, title: String, min: Int, max: Int, initial: Int, suffix: String = "",
        change: (Int) -> Unit) {
        if (max <= min) return
        parent.addView(CouiSlider(this, dark, accent, title, max - min, (initial - min).coerceIn(0, max - min), null) {
            "${it + min}$suffix"
        }.apply { onTouchFinished = { change(it + min) } })
    }
    private fun generatorSlider(parent: LinearLayout, title: String, min: Int, max: Int, initial: Int,
        change: (Int) -> GeneratorOptions) {
        slider(parent, title, min, max, initial) { generator = change(it) }
    }
    private fun option(parent: LinearLayout, title: String, value: Int, choices: List<String>, select: (Int) -> Unit) {
        parent.addView(CouiSelection(this, dark, accent, title, choices[value]).apply {
            setOnClickListener { showOptions(this, choices, value, select) }
        })
    }
    private fun showOptions(anchor: View, choices: List<String>, selected: Int, select: (Int) -> Unit) {
        popup?.let(root::removeView)
        val anchorPos = IntArray(2)
        val rootPos = IntArray(2)
        anchor.getLocationInWindow(anchorPos)
        root.getLocationInWindow(rootPos)
        lateinit var menu: CouiPopup
        menu = CouiPopup(this, dark, accent, choices.mapIndexed { i, label -> i to label }, selected, {
            root.removeView(menu)
            if (popup === menu) popup = null
        }, select)
        popup = menu
        root.addView(menu, FrameLayout.LayoutParams(anchor.width, anchor.height).apply {
            leftMargin = anchorPos[0] - rootPos[0] - root.paddingLeft
            topMargin = anchorPos[1] - rootPos[1]
        })
    }
    private fun divider(parent: LinearLayout, position: Int = parent.childCount) {
        parent.addView(CouiSlot(this, dark, accent) { SettingsDivider() }, position)
    }
    private fun card(parent: LinearLayout, title: String? = null, divided: Boolean = false, content: (LinearLayout) -> Unit) {
        if (title != null) parent.addView(CouiSlot(this, dark, accent) {
            SmallTitle(title, Modifier.semantics { heading() }, insideMargin = PaddingValues(16.dp, 8.dp))
        })
        val box = column()
        content(box)
        if (divided) for (index in (box.childCount - 1) downTo 1) divider(box, index)
        parent.addView(CouiCardHost(this, dark, accent, surface, 20, box),
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }
    private fun choose(title: String, options: List<String>, action: (Int) -> Unit) {
        showDialog(title) { box, dialog ->
            options.forEachIndexed { index, value -> link(box, value) { dialog.dismiss(); action(index) } }
        }
    }
    private fun numberDialog(title: String, initial: Int, range: IntRange, action: (Int) -> Unit) {
        askText(title, initial.toString(), 8, { it.toIntOrNull()?.let(range::contains) == true }) { action(it.toInt()) }
    }
    private fun askText(title: String, initial: String, max: Int = 80, valid: (String) -> Boolean = { it.isNotBlank() },
        receive: (String) -> Unit) {
        showDialog(title, true) { box, d ->
            val field = CouiField(this, dark, accent, title, initial, ink, max)
            val submit = {
                val value = field.value.trim()
                if (valid(value)) { d.dismiss(); receive(value) }
                else field.error = t("Check the value", "Проверь значение")
            }
            field.onSubmit = submit
            box.addView(field)
            box.addView(button(t("Apply", "Применить"), primary = true, action = submit))
        }
    }
    private fun showDialog(title: String, keyboard: Boolean = false, content: (LinearLayout, CouiAnchoredDialog) -> Unit) {
        dialog?.dispose()
        val panel = column().apply { setPadding(dp(14), dp(12), dp(14), dp(14)) }
        val dialogHeader = row()
        val close = CouiIconButton(this, dark, accent, ink, surface, R.drawable.ic_close, t("Close", "Закрыть"), effects)
        dialogHeader.addView(close, LinearLayout.LayoutParams(dp(42), dp(42)))
        dialogHeader.addView(CouiSlot(this, dark, accent) {
            Text(title, Modifier.padding(start = 8.dp).semantics { heading() }, style = COUITheme.textStyles.title4,
                color = COUITheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(dialogHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        val effect = RenderEffect.createBlurEffect(dp(11).toFloat(), dp(11).toFloat(), Shader.TileMode.CLAMP)
        val d = CouiAnchoredDialog(this, panel, dark, minOf(dp(420), root.width - dp(24)).coerceAtLeast(dp(180)),
            null, canAnimate()) { alpha -> if (effects) root.setRenderEffect(if (alpha > .15f) effect else null) }
        close.setOnClickListener { d.dismiss() }
        content(panel, d)
        d.setOnDismissListener { if (dialog === d) { dialog = null; root.setRenderEffect(null) } }
        dialog = d
        d.show()
        if (keyboard) d.animateWithKeyboard()
    }
    private fun <T> task(action: () -> T, completed: (T) -> Unit) {
        if (busy) return
        busy = true
        work.submit {
            val result = runCatching(action)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                result.onSuccess(completed).onFailure {
                    message(t("Could not complete the operation", "Не удалось завершить операцию"))
                    if (page == Page.GENERATOR) show(preserveScroll = true)
                }
            }
        }
    }
}
