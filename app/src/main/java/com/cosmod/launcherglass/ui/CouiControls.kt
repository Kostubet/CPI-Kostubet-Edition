package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonColors
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Slider
import io.github.suqi8.coui.kmp.basic.SliderDefaults
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import kotlin.math.roundToInt

/** Hosts the vendored COUI controls in CPI's existing View-based pages. */
internal abstract class CouiControl(context: Context, dark: Boolean, accent: Int) : FrameLayout(context) {
    private val colors = if (dark) darkColorScheme(primary = Color(accent)) else lightColorScheme(primary = Color(accent))
    protected var controlEnabled by mutableStateOf(true)
        private set
    protected val composition = ComposeView(context).apply { id = View.generateViewId() }

    init {
        clipChildren = false
        clipToPadding = false
        addView(composition, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
    }

    protected fun content(draw: @Composable () -> Unit) {
        composition.setContent { COUITheme(colors, content = draw) }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        controlEnabled = enabled
    }
}

internal class CouiButton(context: Context, dark: Boolean, accent: Int, foreground: Int, surface: Int) :
    CouiControl(context, dark, accent) {
    var text: CharSequence by mutableStateOf("")
    var textSize by mutableStateOf(15f)
    private var labelColor by mutableStateOf(foreground)
    private var filled by mutableStateOf(true)
    private var constructed = false
    private var materialMoved: (() -> Unit)? = null

    init {
        minimumHeight = (44 * resources.displayMetrics.density).roundToInt()
        constructed = true
        content {
            val colors = COUITheme.colorScheme
            Button(
                onClick = { performClick() },
                modifier = Modifier.fillMaxWidth(),
                enabled = controlEnabled,
                cornerRadius = 22.dp,
                minWidth = 0.dp,
                minHeight = 44.dp,
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                colors = ButtonColors(
                    color = if (filled) Color(surface) else Color.Transparent,
                    disabledColor = if (filled) colors.disabledSecondary else Color.Transparent,
                    contentColor = Color(labelColor),
                    disabledContentColor = colors.disabledOnSecondary,
                ),
            ) {
                Text(text.toString(), style = TextStyle(fontSize = textSize.sp, fontWeight = FontWeight.Medium))
            }
        }
    }

    fun setText(resId: Int) { text = context.getText(resId) }
    fun setTextColor(color: Int) { labelColor = color }
    fun shareGlass(onChange: () -> Unit) { materialMoved = onChange }

    @Suppress("DEPRECATION")
    override fun setBackgroundDrawable(background: Drawable?) {
        super.setBackgroundDrawable(null)
        if (constructed) filled = background != null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        materialMoved?.invoke()
        return super.dispatchTouchEvent(event)
    }
}

internal class CouiToggle(context: Context, dark: Boolean, accent: Int) : CouiControl(context, dark, accent) {
    var isChecked by mutableStateOf(false)
    var onPressChanged: ((Boolean) -> Unit)? = null
    var onChange: ((Boolean) -> Unit)? = null
    var label by mutableStateOf("")

    init {
        minimumWidth = (48 * resources.displayMetrics.density).roundToInt()
        minimumHeight = minimumWidth
        content {
            Box(Modifier.width(48.dp).height(48.dp), contentAlignment = Alignment.Center) {
                Switch(
                    checked = isChecked,
                    onCheckedChange = ::change,
                    modifier = Modifier.semantics { contentDescription = label },
                    enabled = controlEnabled,
                )
            }
        }
    }

    private fun change(checked: Boolean) {
        if (!isEnabled || isChecked == checked) return
        isChecked = checked
        onChange?.invoke(checked)
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        change(!isChecked)
        if (!performHapticFeedback(302)) performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onPressChanged?.invoke(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onPressChanged?.invoke(false)
        }
        return super.dispatchTouchEvent(event)
    }
}

internal class CouiSlider(context: Context, dark: Boolean, accent: Int, private val label: String,
    private val range: Int, initial: Int) : CouiControl(context, dark, accent) {
    var progress by mutableStateOf(initial)
    var adjusting = false
        private set
    var onChange: ((Int) -> Unit)? = null
    var onTouchFinished: ((Int) -> Unit)? = null
    var palette by mutableStateOf<IntArray?>(null)
    private var startValue = initial
    private var cancelled = false

    init {
        content {
            val spectrum = palette
            Box(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                Slider(
                    value = progress.toFloat(),
                    onValueChange = { value ->
                        val next = value.roundToInt().coerceIn(0, range)
                        if (progress != next) {
                            progress = next
                            onChange?.invoke(next)
                            if (!adjusting) onTouchFinished?.invoke(next)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
                    enabled = controlEnabled,
                    valueRange = 0f..range.coerceAtLeast(1).toFloat(),
                    height = 20.dp,
                    stretchTrack = true,
                    trackBrush = spectrum?.let { Brush.horizontalGradient(it.map(::Color)) },
                    colors = if (spectrum == null) SliderDefaults.sliderColors() else SliderDefaults.sliderColors(
                        foregroundColor = Color.Transparent, backgroundColor = Color.Transparent,
                        disabledForegroundColor = Color.Transparent, disabledBackgroundColor = Color.Transparent,
                    ),
                    onValueChangeFinished = { if (!cancelled) onTouchFinished?.invoke(progress) },
                )
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            startValue = progress
            cancelled = false
            adjusting = true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) cancelled = true
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (!handled) adjusting = false
            MotionEvent.ACTION_UP -> adjusting = false
            MotionEvent.ACTION_CANCEL -> {
                adjusting = false
                progress = startValue
                onChange?.invoke(progress)
            }
        }
        return handled
    }
}

internal class CouiChoiceLabel(context: Context, dark: Boolean, accent: Int, title: String,
    selected: Boolean, position: Int, count: Int) : CouiControl(context, dark, accent) {
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        content {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                DropdownImpl(
                    item = DropdownItem(text = title),
                    optionSize = count,
                    isSelected = selected,
                    index = position,
                    isFirst = false,
                    isLast = false,
                    onSelectedIndexChange = {},
                )
            }
        }
    }

    // The existing popup owns tap, swipe-to-select and dismiss gestures.
    override fun dispatchTouchEvent(event: MotionEvent) = false
}


/** Adapts stateless COUI components to the settings activity's existing layout slots. */
internal class CouiSlot(context: Context, dark: Boolean, accent: Int, fill: Boolean = false,
    draw: @Composable () -> Unit) : CouiControl(context, dark, accent) {
    init {
        if (fill) composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content(draw)
    }
}

internal class CouiSurface(context: Context, dark: Boolean, accent: Int, fill: Int, radius: Int) :
    CouiControl(context, dark, accent) {
    var color by mutableStateOf(fill)
    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content {
            Card(Modifier.fillMaxSize(), cornerRadius = radius.dp, insideMargin = PaddingValues(0.dp),
                colors = CardDefaults.defaultColors(color = Color(color))) {}
        }
    }
}

internal class CouiPreference(context: Context, dark: Boolean, accent: Int, title: String,
    summary: String?, icon: Int?, iconTint: Int, action: (View) -> Unit) : CouiControl(context, dark, accent) {
    init {
        setOnClickListener(action)
        content {
            ArrowPreference(
                title = title,
                summary = summary,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                startAction = icon?.let { resource ->
                    { Icon(painterResource(resource), null, Modifier.size(22.dp), tint = Color(iconTint)) }
                },
                onClick = { performClick() },
                enabled = controlEnabled,
            )
        }
    }
}

internal class CouiIconButton(context: Context, dark: Boolean, accent: Int, foreground: Int, surface: Int,
    private val icon: Int, private val label: String, effects: Boolean) : CouiControl(context, dark, accent) {
    private val motion = if (effects) OnextButtonMotion(this, true) else null
    private var filled by mutableStateOf(true)
    private var initialized = false

    init {
        initialized = true
        composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content {
            IconButton(onClick = { performClick() }, modifier = Modifier.fillMaxSize(),
                backgroundColor = if (filled) Color(surface) else Color.Transparent, cornerRadius = 24.dp,
                minWidth = 0.dp, minHeight = 0.dp, enabled = controlEnabled) {
                Icon(painterResource(icon), label, Modifier.size(22.dp), tint = Color(foreground))
            }
        }
    }

    override fun setBackgroundDrawable(background: Drawable?) {
        super.setBackgroundDrawable(background)
        if (initialized) filled = false
    }

    fun shareGlass(onMove: () -> Unit) { motion?.shareGlass(onMove) }

    fun followMaterial(material: View, onMove: () -> Unit) {
        motion?.material = material
        motion?.onMaterialMoved = onMove
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (motion?.listen(event) != true) return super.dispatchTouchEvent(event)
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        return true
    }

    override fun draw(canvas: Canvas) {
        val spring = motion ?: return super.draw(canvas)
        val saved = spring.beginDraw(canvas)
        super.draw(canvas)
        spring.endDraw(canvas, saved)
    }

    override fun onDetachedFromWindow() {
        motion?.dispose()
        super.onDetachedFromWindow()
    }
}

internal class CouiField(context: Context, dark: Boolean, accent: Int, label: String, initial: String,
    foreground: Int, maxLength: Int) : CouiControl(context, dark, accent) {
    private var field by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    var error by mutableStateOf<String?>(null)
    val value get() = field.text
    var onSubmit: (() -> Unit)? = null

    init {
        content {
            val keyboard = LocalSoftwareKeyboardController.current
            val requester = remember { FocusRequester() }
            TextField(
                value = field,
                onValueChange = { next ->
                    if (next.text.length <= maxLength) {
                        if (field.text != next.text) error = null
                        field = next
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(requester),
                backgroundMode = TextFieldMode.Rectangle,
                cornerRadius = 16.dp,
                textStyle = COUITheme.textStyles.body1.copy(color = Color(foreground), fontSize = 15.sp),
                label = error ?: label,
                singleLine = true,
                isError = error != null,
                enabled = controlEnabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onSubmit?.invoke() },
                ),
            )
            LaunchedEffect(Unit) {
                requester.requestFocus()
                keyboard?.show()
            }
        }
    }
}
