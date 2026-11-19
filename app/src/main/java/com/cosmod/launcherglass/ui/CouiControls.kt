package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonColors
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.graphicsLayer
import io.github.suqi8.coui.kmp.basic.DropdownArrowEndAction
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.ExpandableGroupArrow
import io.github.suqi8.coui.kmp.basic.HsvHueSlider
import io.github.suqi8.coui.kmp.basic.HsvValueSlider
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.preference.SliderPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import com.cosmod.launcherglass.R
import kotlin.math.roundToInt

/** Connects the COUI controls to CPI's View-based feature pages. */
internal abstract class CouiControl(context: Context, dark: Boolean, accent: Int, barSurface: Int? = null) : FrameLayout(context) {
    private val surface = Color(ContextCompat.getColor(context, R.color.cpi_ui_panel))
    private val bar = barSurface?.let { Color(it) } ?: surface
    private val colors = if (dark) {
        darkColorScheme(primary = Color(accent), backgroundTop = surface, surface = surface, bar = bar)
    } else {
        lightColorScheme(primary = Color(accent), backgroundTop = surface, surface = surface, bar = bar)
    }
    protected var controlEnabled by mutableStateOf(true)
        private set
    protected val composition = ComposeView(context).apply {
        id = View.generateViewId()
        setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(composition, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
    }

    protected fun content(draw: @Composable () -> Unit) {
        composition.setContent {
            COUITheme(colors = colors) {
                CompositionLocalProvider(LocalCOUIMaterialEffectEnabled provides couiMaterialEnabled(context), content = draw)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        controlEnabled = enabled
    }
}

internal fun couiMaterialEnabled(context: Context): Boolean =
    context.getSharedPreferences(SettingsProvider.PREFS_NAME, Context.MODE_PRIVATE).getBoolean("coloros_ui_effects", true)

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
                    disabledColor = if (filled) colors.disabledSecondaryButton else Color.Transparent,
                    contentColor = Color(labelColor),
                    disabledContentColor = colors.disabledOnSecondaryButton,
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

internal class CouiDisclosure(context:Context,dark:Boolean,accent:Int,title:String,
    onExpand:(Boolean)->Unit) : CouiControl(context,dark,accent) {
    private var expanded by mutableStateOf(false)

    init {
        content {
            BasicComponent(
                title=title,
                onClick={
                    expanded=!expanded
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    onExpand(expanded)
                },
                endActions={ ExpandableGroupArrow(expanded) },
                enabled=controlEnabled,
            )
        }
    }
}

internal class CouiSwitchRow(
    context: Context,
    dark: Boolean,
    accent: Int,
    title: String,
    summary: String?,
    initial: Boolean,
    initiallyExpanded: Boolean,
    private val onExpand: ((Boolean) -> Unit)?,
    private val onChange: (Boolean) -> Unit,
) : CouiControl(context, dark, accent) {
    var checked by mutableStateOf(initial)
    private var expanded by mutableStateOf(initiallyExpanded)

    init {
        content {
            if (onExpand == null) {
                SwitchPreference(checked = checked, onCheckedChange = ::change, title = title,
                    summary = summary, enabled = controlEnabled)
            } else {
                BasicComponent(title = title, summary = summary, enabled = controlEnabled,
                    onClick = ::toggleExpansion, onClickLabel = context.getString(
                        if (expanded) R.string.ui_collapse else R.string.ui_expand
                    ),
                    endActions = {
                        ExpandableGroupArrow(expanded)
                        Switch(checked = checked, onCheckedChange = ::change, enabled = controlEnabled)
                    })
            }
        }
    }

    private fun change(value: Boolean) {
        if (!isEnabled || checked == value) return
        checked = value
        onChange(value)
    }

    private fun toggleExpansion() {
        if (!isEnabled) return
        haptic()
        expanded = !expanded
        onExpand?.invoke(expanded)
    }

    private fun haptic() {
        if (!performHapticFeedback(302)) performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
    }
}

internal class CouiSlider(context: Context, dark: Boolean, accent: Int, private val label: String,
    private val range: Int, initial: Int, hint: String?, format: (Int) -> String) : CouiControl(context, dark, accent) {
    var progress by mutableStateOf(initial)
    var adjusting = false
        private set
    var onChange: ((Int) -> Unit)? = null
    var onTouchFinished: ((Int) -> Unit)? = null
    var hueTrack by mutableStateOf(false)
    var brightnessColor by mutableStateOf<Int?>(null)
    private var startValue = initial
    private var cancelled = false

    init {
        content {
            val color = brightnessColor
            if (controlEnabled && (hueTrack || color != null)) {
                BasicComponent(title = label, summary = hint, enabled = controlEnabled,
                    endActions = { Text(format(progress), color = COUITheme.colorScheme.labelSecondary) },
                    bottomAction = {
                        if (hueTrack) {
                            HsvHueSlider(progress * 360f / range.coerceAtLeast(1), onHueChanged = { change(it * range) })
                        } else if (color != null) {
                            val hsv = remember(color) { FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) } }
                            HsvValueSlider(hsv[0], hsv[1], progress.toFloat() / range.coerceAtLeast(1),
                                onValueChanged = { change(it * range) })
                        }
                    })
            } else {
                SliderPreference(
                    value = progress.toFloat(), onValueChange = ::change, title = label, summary = hint,
                    valueText = format(progress), enabled = controlEnabled,
                    valueRange = 0f..range.coerceAtLeast(1).toFloat(),
                    onValueChangeFinished = { if (!cancelled) onTouchFinished?.invoke(progress) },
                )
            }
        }
    }

    private fun change(value: Float) {
        val next = value.roundToInt().coerceIn(0, range)
        if (progress == next) return
        progress = next
        onChange?.invoke(next)
        if (!adjusting) onTouchFinished?.invoke(next)
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
            MotionEvent.ACTION_UP -> {
                adjusting = false
                if ((hueTrack || brightnessColor != null) && handled && progress != startValue) {
                    onTouchFinished?.invoke(progress)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                adjusting = false
                progress = startValue
                onChange?.invoke(progress)
            }
        }
        return handled
    }
}

internal class CouiSelection(
    context: Context, dark: Boolean, accent: Int, title: String, initial: String,
) : CouiControl(context, dark, accent) {
    var value by mutableStateOf(initial)

    init {
        content {
            BasicComponent(
                modifier = Modifier.heightIn(min = 54.dp),
                title = title,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                enabled = controlEnabled,
                onClick = { performClick() },
                endActions = {
                    Text(value, Modifier.padding(end = 5.dp), style = COUITheme.textStyles.bodyM,
                        color = COUITheme.colorScheme.labelSecondary, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    DropdownArrowEndAction(COUITheme.colorScheme.labelSecondary)
                },
            )
        }
    }
}

internal class CouiInformation(
    context: Context, dark: Boolean, accent: Int, title: String, summary: String,
) : CouiControl(context, dark, accent) {
    init {
        content {
            BasicComponent(
                modifier = Modifier.heightIn(min = 56.dp),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                title = title,
                summary = summary,
                enabled = controlEnabled,
            )
        }
    }
}

internal class CouiSlot(context: Context, dark: Boolean, accent: Int, fill: Boolean = false,
    draw: @Composable () -> Unit) : CouiControl(context, dark, accent) {
    init {
        if (fill) composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content(draw)
    }
}

internal class CouiPreviewActions(context: Context, dark: Boolean, accent: Int,
    open: () -> Unit, close: () -> Unit) : CouiControl(context, dark, accent) {
    private var reveal by mutableFloatStateOf(0f)
    private var revealScale by mutableFloatStateOf(.92f)

    fun renderReveal(progress:Float,scale:Float=.92f+.08f*progress){
        reveal=progress
        revealScale=scale
        visibility=if(progress>.008f)View.VISIBLE else View.INVISIBLE
    }

    init {
        renderReveal(0f)
        content {
            val openLabel=stringResource(R.string.ui_feature_peek_open)
            val closeLabel=stringResource(R.string.ui_floating_close)
            val entry=remember(openLabel,closeLabel){
                DropdownEntry(items=listOf(
                    DropdownItem(text=openLabel,onClick=open),
                    DropdownItem(text=closeLabel,onClick=close),
                ))
            }
            Card(Modifier.graphicsLayer {
                alpha = reveal
                scaleX = revealScale
                scaleY = revealScale
            }, insideMargin = PaddingValues(0.dp)) {
                entry.items.forEachIndexed { index, item ->
                    DropdownImpl(item, entry.items.size, false, index, enabled = reveal > .55f) {
                        item.onClick?.invoke()
                    }
                }
            }
        }
    }
}

internal class CouiCardHost(
    context: Context, color: Int, radius: Int, edge: Int, body: View,
) : FrameLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
        background = CouiCardFill(context, color, radius, edge)
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
}

/** Paint the COUI Card outline for View rows without creating a composition for the fill. */
private class CouiCardFill(context: Context, private val fillColor: Int, radius: Int, private val edge: Int) : Drawable() {
    private val density = Density(context.resources.displayMetrics.density)
    private val shape = SquircleShape(radius.dp, COUIRoundCorner.Normal.Weight)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fillColor }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = edge
        style = Paint.Style.STROKE
        strokeWidth = with(density) { 1.dp.toPx() }
    }
    private val path = android.graphics.Path()
    private val strokePath = android.graphics.Path()

    override fun onBoundsChange(bounds: Rect) {
        path.rewind()
        strokePath.rewind()
        if (!bounds.isEmpty) {
            val size = Size(bounds.width().toFloat(), bounds.height().toFloat())
            val outline = shape.createOutline(size, LayoutDirection.Ltr, density) as Outline.Generic
            path.addPath(outline.path.asAndroidPath(), bounds.left.toFloat(), bounds.top.toFloat())
            val inset = stroke.strokeWidth / 2
            if (edge != 0 && size.width > inset * 2 && size.height > inset * 2) {
                val inner = shape.createOutline(Size(size.width - inset * 2, size.height - inset * 2),
                    LayoutDirection.Ltr, density) as Outline.Generic
                strokePath.addPath(inner.path.asAndroidPath(), bounds.left + inset, bounds.top + inset)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
        if (edge != 0) canvas.drawPath(strokePath, stroke)
    }
    override fun setAlpha(alpha: Int) {
        paint.alpha = (fillColor ushr 24) * alpha / 255
        stroke.alpha = (edge ushr 24) * alpha / 255
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
        invalidateSelf()
    }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

internal class CouiPreference(context: Context, dark: Boolean, accent: Int, title: String,
    summary: String?, icon: Int?, iconTint: Int, catalog:Boolean=false, onPreview:((View)->Unit)?=null,
    longPressLabel:Int=R.string.ui_feature_peek_title,
    action: (View) -> Unit) : CouiControl(context, dark, accent) {
    private var liftingForDialog = false
    private val previewScale = OnextSpring(1f) { scaleX = it; scaleY = it }

    fun resetPreviewLift() {
        liftingForDialog = false
        previewScale.snap(1f)
    }

    init {
        setOnClickListener(action)
        content {
            val interactions = remember { MutableInteractionSource() }
            if (onPreview != null && LocalCOUIMaterialEffectEnabled.current) LaunchedEffect(interactions) {
                interactions.interactions.collect { interaction ->
                    if (!liftingForDialog && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        val pressed = interaction is PressInteraction.Press
                        if (pressed || interaction is PressInteraction.Release || interaction is PressInteraction.Cancel) {
                            if (pressed) previewScale.to(1.033f, 105f, .8f)
                            else previewScale.to(1f, 250f, .55f)
                        }
                    }
                }
            }
            val gesture=if(onPreview==null)Modifier else Modifier.combinedClickable(
                enabled=controlEnabled,
                onLongClickLabel=context.getString(longPressLabel),
                interactionSource=interactions,
                indication=null,
                onLongClick={
                    liftingForDialog=true
                    previewScale.snap(previewScale.value)
                    onPreview(this@CouiPreference)
                },
                onClick={
                    if(!catalog){liftingForDialog=true;previewScale.snap(previewScale.value)}
                    performClick()
                },
            )
            ArrowPreference(
                modifier=Modifier.clip(RoundedCornerShape(22.dp)).then(gesture),
                title = title,
                summary = summary,
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = if(catalog)10.dp else 12.dp),
                startAction = icon?.let { resource ->
                    { if(catalog)Box(Modifier.size(32.dp),contentAlignment=Alignment.Center){
                        Icon(painterResource(resource),null,Modifier.size(28.dp),tint=Color(iconTint))
                    }else Icon(painterResource(resource), null, Modifier.size(22.dp), tint = Color(iconTint)) }
                },
                onClick = if(onPreview==null) {{performClick()}} else null,
                enabled = controlEnabled,
            )
        }
    }
}

internal class CouiIconButton(context: Context, dark: Boolean, accent: Int, foreground: Int, surface: Int,
    private val icon: Int, private val label: String, effects: Boolean, chrome: Boolean = false) :
    CouiControl(context, dark, accent, surface) {
    init {
        composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content {
            IconButton(
                onClick={performClick()}, modifier=Modifier.fillMaxSize(), enabled=controlEnabled,
                material=effects, backgroundColor=when {
                    !effects -> Color(surface)
                    chrome -> Color(surface).copy(alpha = .27f)
                    else -> Color.Unspecified
                },
                cornerRadius=50.dp, minHeight=0.dp, minWidth=0.dp) {
                Icon(painterResource(icon),label,Modifier.size(22.dp),tint=Color(foreground))
            }
        }
    }
    fun shareGlass(onMove:()->Unit) { /* The upstream component moves its material and glyph together. */ }
}

internal class CouiField(context: Context, dark: Boolean, accent: Int, label: String, initial: String,
    foreground: Int, maxLength: Int, autofocus:Boolean=true, multiline:Boolean=false) : CouiControl(context, dark, accent) {
    private var field by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    var error by mutableStateOf<String?>(null)
    val value get() = field.text
    var onSubmit: (() -> Unit)? = null

    fun replaceText(text: String) {
        field = TextFieldValue(text, TextRange(text.length))
        error = null
    }

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
                textStyle = COUITheme.textStyles.bodyL.copy(color = Color(foreground), fontSize = 15.sp),
                label = error ?: label,
                singleLine = !multiline,
                maxLines = if(multiline)4 else 1,
                isError = error != null,
                enabled = controlEnabled,
                keyboardOptions = KeyboardOptions(imeAction = if(multiline)ImeAction.Default else ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { onSubmit?.invoke() },
                ),
            )
            if(autofocus)LaunchedEffect(Unit) {
                requester.requestFocus()
                keyboard?.show()
            }
        }
    }
}
