package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
import io.github.suqi8.coui.kmp.basic.CardDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.cosmod.launcherglass.ui.onextbox.ColorOs17SettingsSlider
import com.cosmod.launcherglass.ui.onextbox.ColorOs17AppTheme
import com.cosmod.launcherglass.ui.onextbox.ColorOsEmbeddedPopup
import com.cosmod.launcherglass.ui.onextbox.ColorOsDropdownMenuContent
import com.cosmod.launcherglass.ui.onextbox.ColorOsSettingsSliderRow
import com.cosmod.launcherglass.ui.onextbox.SettingsExpandArrow
import com.cosmod.launcherglass.ui.onextbox.SettingsPopupArrow
import com.cosmod.launcherglass.ui.onextbox.SettingsSwitchPreference
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme
import com.cosmod.launcherglass.R
import kotlin.math.roundToInt

/** Hosts the vendored COUI controls in CPI's existing View-based pages. */
internal abstract class CouiControl(context: Context, dark: Boolean, accent: Int) : FrameLayout(context) {
    private val colors = if (dark) darkColorScheme(primary = Color(accent)) else lightColorScheme(primary = Color(accent))
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
        composition.setContent { ColorOs17AppTheme(nativeAccent = true, monetColors = colors, content = draw) }
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
                endActions={ SettingsExpandArrow(expanded) },
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
            val expansion = if (onExpand != null) ::toggleExpansion else null
            SettingsSwitchPreference(
                checked = checked, onCheckedChange = ::change, title = title, summary = summary,
                enabled = controlEnabled, onRowClick = expansion ?: { change(!checked); haptic() },
                endActions = {
                if (expansion != null) {
                    val description = context.getString(if (expanded) R.string.ui_collapse else R.string.ui_expand)
                    Box(
                        modifier = Modifier.size(42.dp, 48.dp)
                            .clickable(enabled = controlEnabled, role = Role.Button, onClick = expansion)
                            .semantics { contentDescription = description },
                        contentAlignment = Alignment.Center,
                    ) { SettingsExpandArrow(expanded) }
                }
            })
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
    var palette by mutableStateOf<IntArray?>(null)
    private var startValue = initial
    private var cancelled = false

    init {
        content {
            val spectrum = palette
            val track = remember(spectrum) { spectrum?.let { Brush.horizontalGradient(it.map(::Color)) } }
            ColorOsSettingsSliderRow(title = label, enabled = controlEnabled,
                hasDividerAbove = false, hasDividerBelow = false,
                titleAction = { Text(format(progress), style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantActions) }) {
                if(hint!=null)Text(hint,style=COUITheme.textStyles.body2,color=COUITheme.colorScheme.onSurfaceVariantSummary)
                Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    ColorOs17SettingsSlider(
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
                        trackBrush = track,
                        onValueChangeFinished = { if (!cancelled) onTouchFinished?.invoke(progress) },
                    )
                }
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
                    Text(value, Modifier.padding(end = 5.dp), style = COUITheme.textStyles.body2,
                        color = COUITheme.colorScheme.onSurfaceVariantActions, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    SettingsPopupArrow()
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
            ColorOsEmbeddedPopup(reveal,revealScale) {
                ColorOsDropdownMenuContent(reveal>.55f,entry,selectionMenu=false,
                    leadingIcon={index,tint->
                        Icon(painterResource(if(index==0)R.drawable.ic_catalog_next else R.drawable.ic_close),
                            null,Modifier.size(19.dp),tint=tint)
                    }){
                    entry.items[it].onClick?.invoke()
                }
            }
        }
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

internal class CouiCardHost(
    context: Context, dark: Boolean, accent: Int, color: Int, radius: Int, private val body: View,
) : FrameLayout(context) {
    private val surface = CouiSurface(context, dark, accent, color, radius)

    init {
        clipChildren = false
        clipToPadding = false
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // Size the decorative surface to the rows, including when the card is inside a scroll view.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        measureChildWithMargins(body, widthMeasureSpec, 0, heightMeasureSpec, 0)
        setMeasuredDimension(
            resolveSizeAndState(body.measuredWidth + paddingLeft + paddingRight, widthMeasureSpec, body.measuredState),
            resolveSizeAndState(body.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec, 0),
        )
        surface.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth - paddingLeft - paddingRight, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight - paddingTop - paddingBottom, MeasureSpec.EXACTLY),
        )
    }
}

internal class CouiPreference(context: Context, dark: Boolean, accent: Int, title: String,
    summary: String?, icon: Int?, iconTint: Int, catalog:Boolean=false, onPreview:((View)->Unit)?=null,
    longPressLabel:Int=R.string.ui_feature_peek_title,
    action: (View) -> Unit) : CouiControl(context, dark, accent) {
    private var liftingForDialog = false

    fun resetPreviewLift() {
        liftingForDialog = false
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
    }

    init {
        setOnClickListener(action)
        content {
            val interactions = remember { MutableInteractionSource() }
            if (onPreview != null) LaunchedEffect(interactions) {
                interactions.interactions.collect { interaction ->
                    if (!liftingForDialog && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                        val pressed = interaction is PressInteraction.Press
                        if (pressed || interaction is PressInteraction.Release || interaction is PressInteraction.Cancel) {
                            val scale = if (pressed) 1.033f else 1f
                            animate().scaleX(scale).scaleY(scale).setDuration(if (pressed) 390L else 160L)
                                .setInterpolator(android.view.animation.PathInterpolator(.28f, .03f, .50f, 1f)).start()
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
                    animate().cancel()
                    onPreview(this@CouiPreference)
                },
                onClick={
                    if(!catalog){liftingForDialog=true;animate().cancel()}
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
    private val icon: Int, private val label: String, effects: Boolean) : CouiControl(context, dark, accent) {
    init {
        composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content {
            com.cosmod.launcherglass.ui.onextbox.ColorOsTopBarButton(
                onClick={if(controlEnabled)performClick()},modifier=Modifier.fillMaxSize(),nativeBlurEnabled=effects){
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
