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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.CouiAnchoredDialog
import com.cosmod.launcherglass.ui.CouiButton
import com.cosmod.launcherglass.ui.CouiField
import com.cosmod.launcherglass.ui.CouiIconButton
import com.cosmod.launcherglass.ui.CouiInformation
import com.cosmod.launcherglass.ui.CouiPreference
import com.cosmod.launcherglass.ui.CouiSelection
import com.cosmod.launcherglass.ui.CouiSlider
import com.cosmod.launcherglass.ui.CouiSurface
import com.cosmod.launcherglass.ui.CouiSwitchRow
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.ui.UiLanguage
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class HapticStudioActivity : AppCompatActivity() {
    private enum class Page { HOME, GENERATOR, EDITOR, LAB, LIBRARY, HARDWARE }
    private var page = Page.HOME
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var scroller: ScrollView
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
    private var channel = HapticChannel.AMPLITUDE
    private var drawCurve = false
    private var graph: HapticTimeline? = null
    private var timeLabel: TextView? = null
    private var playButton: CouiButton? = null
    private var dragBefore: HapticPattern? = null
    private val undo = ArrayDeque<HapticPattern>()
    private val redo = ArrayDeque<HapticPattern>()
    private val work = Executors.newSingleThreadExecutor()
    private var busy = false
    private var dialog: CouiAnchoredDialog? = null
    private var slide = HapticSlide.INTENSITY
    private var curve = HapticCurve.SMOOTH_STEP
    private var adsr = HapticAdsr()
    private var labDuration = 900
    private var labStrength = 70
    private var labSharpness = 65
    private var labFrequency = 50
    private var labFrequencyEnd = 80
    private var labDensity = 3
    private var dark = false
    private val canvasColor get() = if (dark) 0xff08090d.toInt() else 0xfff3f5f8.toInt()
    private val surface get() = if (dark) 0xff1c1e26.toInt() else 0xffffffff.toInt()
    private val ink get() = if (dark) 0xfff2f3f8.toInt() else 0xff191d28.toInt()
    private val accent get() = if (dark) 0xff76a9ff.toInt() else 0xff175ac9.toInt()
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
                        box.addView(label(t("Hardware frequency data will be removed. The amplitude curve will be kept.",
                            "Частотные параметры будут удалены. Кривая амплитуды сохранится."), 14f))
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
        super.onCreate(savedInstanceState)
        dark = when (getSharedPreferences(SettingsProvider.PREFS_NAME, MODE_PRIVATE).getInt("ui_theme", 0)) {
            1 -> false
            2 -> true
            else -> resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        motor = HapticMotor(this)
        store = HapticLibrary(this)
        pattern = HapticPattern.empty(t("Untitled pattern", "Новый паттерн"))
        player = HapticPreview(this, motor, { ms ->
            graph?.cursor = ms
            timeLabel?.text = "${clock(ms)} / ${clock(pattern.length)}"
            playButton?.text = if (player.playing) t("Pause", "Пауза") else t("Play", "Воспроизвести")
        }, { message(t("Cannot play this selection", "Не удалось воспроизвести фрагмент")) })
        setContentView(column().also { root = it })
        root.setBackgroundColor(canvasColor)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left + dp(16), safe.top, safe.right + dp(16), safe.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == Page.HOME) finish() else { player.stop(); show(Page.HOME) }
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
        root.removeAllViews()
        graph = null
        displayPointBox = null
        timeLabel = null
        playButton = null
        val heading = row()
        heading.addView(CouiIconButton(this, dark, accent, ink, surface, R.drawable.ic_arrow_back, t("Back", "Назад"), true)
            .apply { setOnClickListener { onBackPressedDispatcher.onBackPressed() } },
            LinearLayout.LayoutParams(dp(46), dp(46)))
        heading.addView(label(title(page), 22f).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(heading)
        body = column().apply { setPadding(0, dp(8), 0, dp(18)) }
        scroller = ScrollView(this).apply { isFillViewport = false; addView(body) }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
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

    private fun home() {
        card(body) { box ->
            box.addView(CouiInformation(this, dark, accent, pattern.name,
                "${clock(pattern.length)} · ${pattern.points.size} " + t("points", "точек")))
            previewControls(box)
        }
        card(body) { box ->
            for (destination in Page.entries.drop(1)) {
                link(box, title(destination)) { player.stop(); show(destination) }
            }
        }
        card(body) { box ->
            toggle(box, t("Use for incoming calls", "Вибрация входящих звонков"), HapticRingStore.enabled(this)) {
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
        card(body) { box ->
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
            option(box, t("Generation style", "Стиль генерации"), style.ordinal, HapticStyle.entries.map(::styleName)) {
                style = HapticStyle.entries[it]
                generator = style.options()
                show(preserveScroll = true)
            }
            box.addView(button(if (busy) t("Analyzing…", "Анализ…") else t("Generate", "Сгенерировать")) {
                val source = audio ?: pattern.audio?.let(Uri::parse)
                if (source == null) message(t("Choose audio first", "Сначала выбери аудио"))
                else if (!busy) analyze(source, true)
            }.apply { isEnabled = !busy })
        }
        card(body) { box ->
            generatorSlider(box, t("Accent sensitivity", "Чувствительность к акцентам"), 0, 100, generator.sensitivity) { generator.copy(sensitivity = it) }
            generatorSlider(box, t("Detail", "Детализация"), 0, 100, generator.detail) { generator.copy(detail = it) }
            generatorSlider(box, t("Intensity", "Интенсивность"), 0, 100, generator.strength) { generator.copy(strength = it) }
            generatorSlider(box, t("Smoothness", "Плавность"), 0, 100, generator.smoothness) { generator.copy(smoothness = it) }
            generatorSlider(box, t("Bass emphasis", "Акцент на басах"), 0, 100, generator.bass) { generator.copy(bass = it) }
            generatorSlider(box, t("Melody sensitivity", "Чувствительность к мелодии"), 0, 100, generator.melody) { generator.copy(melody = it) }
            generatorSlider(box, t("Impulse length · ms", "Длительность импульса · мс"), 25, 400, generator.impulseMs) { generator.copy(impulseMs = it) }
            generatorSlider(box, t("Accent limit · per second", "Предел акцентов · в секунду"), 1, 16, generator.repetitions) { generator.copy(repetitions = it) }
            generatorSlider(box, t("Dynamics", "Динамика"), 0, 100, generator.dynamics) { generator.copy(dynamics = it) }
            generatorSlider(box, t("Noise suppression", "Подавление слабых звуков"), 0, 100, generator.noise) { generator.copy(noise = it) }
            slider(box, t("Sync offset · ms", "Сдвиг синхронизации · мс"), -1500, 1500, pattern.offset) {
                changePattern(pattern.copy(offset = it))
            }
        }
    }

    private fun editor() {
        card(body) { box ->
            val channels = availableChannels()
            option(box, t("Envelope", "Огибающая"), channels.indexOf(channel).coerceAtLeast(0), channels.map(::channelName)) {
                channel = channels[it]; show(preserveScroll = true)
            }
            toggle(box, t("Draw control points", "Рисовать точки"), drawCurve) {
                drawCurve = it
                graph?.drawCurve = it
            }
            addGraph(box, pattern, true)
            previewControls(box)
        }
        card(body) { box ->
            displayPointBox = column()
            box.addView(displayPointBox)
            refreshPointControls()
            buttons(box, t("Undo", "Отменить"), t("Redo", "Повторить"),
                { restore(undo, redo) }, { restore(redo, undo) })
        }
        card(body) { box ->
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

    private fun lab() {
        val types = HapticSlide.entries.filter {
            (it != HapticSlide.FREQUENCY || motor.frequency != null || motor.richTap) &&
                (it != HapticSlide.SHARPNESS || motor.envelope != null)
        }
        card(body) { box ->
            option(box, "Haptic Slide", types.indexOf(slide).coerceAtLeast(0), types.map(::slideName)) {
                slide = types[it]; show(preserveScroll = true)
            }
            addGraph(box, labPattern(), false)
            buttons(box, t("Test slide", "Проверить"), t("Insert at cursor", "Вставить в дорожку"), {
                val project = labPattern()
                player.play(project, null, 0, project.length, false)
            }, {
                val project = labPattern()
                if (cursor + project.length <= MAX_TRACK_MS) {
                    val stop = cursor + project.length
                    val length = maxOf(pattern.length, stop)
                    val points = pattern.points.filterNot { it.ms in cursor..stop } +
                        project.points.map { it.copy(ms = it.ms + cursor) }
                    if (points.size <= MAX_HAPTIC_POINTS) {
                        changePattern(pattern.copy(length = length, points = points.sortedBy { it.ms }))
                        show(Page.EDITOR)
                    }
                }
            })
        }
        card(body) { box ->
            fun control(title: String, min: Int, max: Int, initial: Int, set: (Int) -> Unit) {
                slider(box, title, min, max, initial) { value -> set(value); graph?.pattern = labPattern() }
            }
            control(t("Duration · ms", "Длительность · мс"), 80, 5000, labDuration) { labDuration = it }
            control(t("Amplitude", "Амплитуда"), 0, 100, labStrength) { labStrength = it }
            control(t("Attack · ms", "Нарастание · мс"), 1, 2000, adsr.attack) { adsr = adsr.copy(attack = it) }
            control(t("Decay · ms", "Начальное затухание · мс"), 1, 2000, adsr.decay) { adsr = adsr.copy(decay = it) }
            control(t("Sustain", "Уровень удержания"), 0, 100, adsr.sustain) { adsr = adsr.copy(sustain = it) }
            control(t("Release · ms", "Окончательное затухание · мс"), 1, 2000, adsr.release) { adsr = adsr.copy(release = it) }
            if (slide == HapticSlide.WAVE || slide == HapticSlide.PULSE_FLOW) {
                control(t("Rhythm density", "Плотность ритма"), 1, 8, labDensity) { labDensity = it }
            }
            if (motor.envelope != null) control(t("Sharpness", "Резкость"), 0, 100, labSharpness) { labSharpness = it }
            if (motor.richTap || motor.frequency != null) {
                control(t("Start frequency position", "Начальная позиция частоты"), 0, 100, labFrequency) { labFrequency = it }
                if (slide == HapticSlide.FREQUENCY)
                    control(t("End frequency position", "Конечная позиция частоты"), 0, 100, labFrequencyEnd) { labFrequencyEnd = it }
            }
            option(box, t("Transition smoothing", "Плавность перехода"), curve.ordinal, HapticCurve.entries.map(::curveName)) {
                curve = HapticCurve.entries[it]; show(preserveScroll = true)
            }
        }
    }

    private fun labPattern(): HapticPattern {
        val hz = motor.frequency
        fun absolute(value: Int) = if (hz == null) 0f else hz.start + (hz.endInclusive - hz.start) * value / 100f
        val raw = slidePoints(slide, 0, labDuration, labStrength * .01f, labSharpness * .01f,
            absolute(labFrequency), absolute(labFrequencyEnd), labDensity, adsr, curve)
        val points = if (hz == null && motor.richTap) raw.map {
            val from = (labFrequency - 50) * 2f
            val to = if (slide == HapticSlide.FREQUENCY) (labFrequencyEnd - 50) * 2f else from
            it.copy(pitch = from + (to - from) * it.ms / labDuration)
        } else raw
        return HapticPattern(slideName(slide), labDuration, points)
    }

    private fun library() {
        card(body) { box ->
            buttons(box, t("New", "Новый"), t("Save", "Сохранить"), {
                acceptImport(HapticPattern.empty(t("Untitled pattern", "Новый паттерн")))
            }, { save(false) })
            buttons(box, t("Rename", "Переименовать"), t("Duplicate", "Дублировать"),
                { save(false) }, { save(true) })
            buttons(box, t("Import", "Импорт"), t("Export", "Экспорт"), {
                if (!busy) openPattern.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }, {
                exportProject = pattern
                val fileName = pattern.name.replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().trim('.').ifEmpty { "haptic" }
                export.launch("$fileName.cpi-haptic.json")
            })
        }
        card(body) { box ->
            if (entries.isEmpty()) box.addView(CouiInformation(this, dark, accent,
                t("Saved patterns", "Сохранённые паттерны"), t("Save the current pattern to start your library.", "Сохрани текущий паттерн, чтобы создать библиотеку.")))
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
        card(body) { box ->
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
        val timeline = HapticTimeline(this, ink, accent, ::drawPoint, ::seek).apply {
            pattern = project
            source = if (editable && project.audio == audio?.toString()) waveform else null
            channel = if (editable) this@HapticStudioActivity.channel else HapticChannel.AMPLITUDE
            limits = when (channel) {
                HapticChannel.FREQUENCY -> frequencyLimits
                HapticChannel.RICHTAP -> -100f..100f
                else -> 0f..1f
            }
            selected = this@HapticStudioActivity.selected
            cursor = this@HapticStudioActivity.cursor
            drawCurve = editable && this@HapticStudioActivity.drawCurve
            contentDescription = t("Audio and haptic timeline", "Звуковая и тактильная дорожки")
        }
        box.addView(timeline, LinearLayout.LayoutParams(-1, dp(172)).apply { setMargins(dp(12), dp(7), dp(12), 0) })
        graph = timeline
    }

    private fun previewControls(box: LinearLayout) {
        timeLabel = label("${clock(cursor)} / ${clock(pattern.length)}", 13f).also {
            it.gravity = Gravity.CENTER; box.addView(it)
        }
        val controls = row()
        playButton = button(if (player.playing) t("Pause", "Пауза") else t("Play", "Воспроизвести")) {
            if (player.playing) { cursor = player.position; player.stop() } else play(false)
        }.also { controls.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        controls.addView(button(t("Stop", "Стоп")) { player.stop(); seek(regionStart) }, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(controls)
        link(box, t("Haptics only", "Только вибрация")) { play(true) }
        toggle(box, t("Loop fragment", "Зациклить фрагмент"), pattern.repeat) { changePattern(pattern.copy(repeat = it)) }
        if (original != null) toggle(box, t("Compare with original", "Сравнить с оригиналом"), compare) { compare = it }
    }

    private fun play(silent: Boolean) {
        val project = if (compare) original ?: pattern else pattern
        val end = minOf(regionEnd ?: project.length, project.length)
        val lower = regionStart.coerceAtMost(end - 1)
        val start = cursor.takeIf { it in lower until end } ?: lower
        player.play(project, if (silent) null else project.audio?.let(Uri::parse), start, end, pattern.repeat, lower)
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

    private fun drawPoint(ms: Int, value: Float, finished: Boolean) {
        if (dragBefore == null) {
            if (player.playing) player.stop()
            dragBefore = pattern
        }
        val point = rawPoint(ms)
        val next = when (channel) {
            HapticChannel.AMPLITUDE -> point.copy(amplitude = value)
            HapticChannel.FREQUENCY -> point.copy(hz = value)
            HapticChannel.RICHTAP -> point.copy(pitch = value)
            HapticChannel.SHARPNESS -> point.copy(sharpness = value)
        }
        pattern = pattern.replace(next)
        graph?.pattern = pattern
        graph?.selected = ms
        selected = ms
        cursor = ms
        graph?.cursor = ms
        timeLabel?.text = "${clock(cursor)} / ${clock(pattern.length)}"
        if (finished) {
            dragBefore?.takeIf { it != pattern }?.let(::record)
            dragBefore = null
            refreshPointControls()
        }
    }

    private fun seek(ms: Int) {
        if (player.playing) player.stop()
        cursor = ms.coerceIn(0, pattern.length)
        val previous = selected
        val nearest = pattern.points.minByOrNull { abs(it.ms - cursor) }
        selected = nearest?.ms?.takeIf { abs(it - cursor) <= maxOf(18, pattern.length / 100) }
        graph?.cursor = cursor
        graph?.selected = selected
        timeLabel?.text = "${clock(cursor)} / ${clock(pattern.length)}"
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
    private fun changePattern(next: HapticPattern) {
        if (pattern == next) return
        record(pattern)
        pattern = next
        graph?.pattern = next
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
    )[value.ordinal]
    private fun curveName(value: HapticCurve) = listOf(
        t("Linear", "Линейная"), t("Ease in", "Плавный старт"), t("Ease out", "Плавный финиш"),
        t("Ease in out", "Плавный старт и финиш"), t("Smooth step", "Мягкая S-кривая"), "Bézier",
    )[value.ordinal]
    private fun styleName(value: HapticStyle) = listOf(
        "Balanced", "Smooth", "Dynamic", "Bass Focus", "Melody Flow", "Precision", "Custom AI",
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
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(ink) }
    private fun message(value: String) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
    private fun button(title: String, action: () -> Unit) = CouiButton(this, dark, accent, ink, surface).apply {
        text = title
        setOnClickListener { action() }
    }
    private fun buttons(parent: LinearLayout, first: String, second: String, a: () -> Unit, b: () -> Unit) {
        parent.addView(row().apply {
            addView(button(first, a), LinearLayout.LayoutParams(0, -2, 1f))
            addView(button(second, b), LinearLayout.LayoutParams(0, -2, 1f))
        })
    }
    private fun link(parent: LinearLayout, title: String, detail: String? = null, action: () -> Unit) {
        parent.addView(CouiPreference(this, dark, accent, title, detail, null, accent) { action() })
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
            setOnClickListener { choose(title, choices, select) }
        })
    }
    private fun card(parent: LinearLayout, content: (LinearLayout) -> Unit) {
        val box = column()
        val glass = CouiSurface(this, dark, accent, surface, 24)
        val shell = FrameLayout(this)
        shell.addView(glass, FrameLayout.LayoutParams(-1, -1))
        shell.addView(box, FrameLayout.LayoutParams(-1, -2))
        parent.addView(shell, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        content(box)
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
            box.addView(button(t("Apply", "Применить"), submit))
        }
    }
    private fun showDialog(title: String, keyboard: Boolean = false, content: (LinearLayout, CouiAnchoredDialog) -> Unit) {
        dialog?.dispose()
        val panel = column().apply { setPadding(dp(14), dp(12), dp(14), dp(14)) }
        panel.addView(label(title, 19f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) })
        val effect = RenderEffect.createBlurEffect(dp(11).toFloat(), dp(11).toFloat(), Shader.TileMode.CLAMP)
        val d = CouiAnchoredDialog(this, panel, dark, minOf(dp(420), root.width - dp(24)).coerceAtLeast(dp(180)),
            null, ValueAnimator.areAnimatorsEnabled()) { alpha -> root.setRenderEffect(if (alpha > .15f) effect else null) }
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
