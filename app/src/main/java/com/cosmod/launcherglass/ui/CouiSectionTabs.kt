package com.cosmod.launcherglass.ui

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.SegmentButton

/** COUI owns both the springs and the indicator's drag gesture. */
internal class CouiSectionTabs(
    context: Context, dark: Boolean, accent: Int, labels: List<String>, selected: Int,
    private val commitOnRelease: Boolean = false,
    private val choose: (Int) -> Unit,
) : CouiControl(context, dark, accent) {
    private var selection by mutableIntStateOf(selected)
    private var committed = selected
    private var trackingTouch = false

    init {
        val effects = couiMaterialEnabled(context)
        content {
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
                height = 46.dp,
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
