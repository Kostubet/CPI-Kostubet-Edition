package com.cosmod.launcherglass.ui

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.SegmentButton
import io.github.suqi8.coui.kmp.basic.SegmentButtonDefaults
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlin.math.roundToInt

/** COUI owns both the springs and the indicator's drag gesture. */
internal class CouiSectionTabs(
    context: Context, dark: Boolean, accent: Int, labels: List<String>, selected: Int,
    private val commitOnRelease: Boolean = false,
    floating: Boolean = false,
    private val choose: (Int) -> Unit,
) : CouiControl(context, dark, accent) {
    private var selection by mutableIntStateOf(selected)
    private var liveBackdrop by mutableStateOf(false)
    private var committed = selected
    private var trackingTouch = false

    init {
        val effects = couiMaterialEnabled(context)
        val height = 46.dp
        if (floating && effects) {
            val heightPx = (height.value * resources.displayMetrics.density).roundToInt()
            addView(CouiChromeBackdrop(context, dark) { liveBackdrop = it }, 0,
                LayoutParams(LayoutParams.MATCH_PARENT, heightPx, Gravity.CENTER))
        }
        content {
            val colors = if (floating) {
                SegmentButtonDefaults.segmentButtonColors(
                    backgroundColor = if (liveBackdrop) Color.Transparent else COUITheme.colorScheme.surface,
                )
            } else SegmentButtonDefaults.segmentButtonColors()
            SegmentButton(
                items = labels,
                selectedIndex = selection,
                onSelectedIndexChange = { index ->
                    selection = index
                    if (!commitOnRelease || !trackingTouch) commit()
                },
                enabled = controlEnabled,
                materialEffect = effects,
                dragAnimationEnabled = effects,
                modifier = Modifier.fillMaxWidth(),
                height = height,
                colors = colors,
            )
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (commitOnRelease && event.actionMasked == MotionEvent.ACTION_DOWN) trackingTouch = true
        val handled = super.dispatchTouchEvent(event)
        if (!handled && event.actionMasked == MotionEvent.ACTION_DOWN) trackingTouch = false
        if (trackingTouch) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    trackingTouch = false
                    commit()
                }
                MotionEvent.ACTION_CANCEL -> {
                    trackingTouch = false
                    selection = committed
                }
            }
        }
        return handled
    }

    private fun commit() {
        if (committed == selection) return
        committed = selection
        choose(committed)
    }
}
