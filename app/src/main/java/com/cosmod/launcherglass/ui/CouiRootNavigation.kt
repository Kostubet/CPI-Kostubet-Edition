package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Rect
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.ui.onextbox.ColorOs17BottomNavigationBar
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBar
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBarItem
import io.github.suqi8.coui.kmp.basic.NavigationItem
import kotlin.math.roundToInt

/** Persistent upstream COUI navigation; restart actions live in the page toolbar. */
internal class CouiRootNavigation(context:Context,dark:Boolean,accent:Int,
    icons:List<Int>,outlineIcons:List<Int>,labels:List<String>,selected:MutableIntState,surface:Int,
    onSelected:(Int)->Unit):CouiControl(context,dark,accent){
    var surfaceColor by mutableIntStateOf(surface)
    private var hardwareBackdrop by mutableStateOf(false)
    private val capsuleBounds = Rect()
    private val backdrop = CouiChromeBackdrop(context, dark) { hardwareBackdrop = it }

    init {
        WinterEffects.mark(this, 28f, 3)
        if (appearance == CouiAppearance.MODERN) addView(backdrop, 0, LayoutParams(0, 0))
        composition.layoutParams=LayoutParams(-1,-1)
        composition.clipChildren=false;composition.clipToPadding=false
        content {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){
                if (appearance == CouiAppearance.ORIGINAL) {
                    ColorOs17BottomNavigationBar(
                        items = labels.mapIndexed { index, label ->
                            NavigationItem(label, ImageVector.vectorResource(icons[index]))
                        },
                        selectedIndex = selected.intValue,
                        onItemSelected = onSelected,
                        modifier = Modifier.fillMaxWidth().height(88.dp),
                        unselectedIcons = outlineIcons.map { ImageVector.vectorResource(it) },
                    )
                } else {
                    FloatingNavigationBar(
                        defaultWindowInsetsPadding = false,
                        color = Color(surfaceColor).copy(alpha = if (hardwareBackdrop) .20f else 1f),
                        backgroundModifier = Modifier.onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInRoot()
                            capsuleBounds.set(bounds.left.roundToInt(), bounds.top.roundToInt(),
                                bounds.right.roundToInt(), bounds.bottom.roundToInt())
                            placeBackdrop()
                        },
                    ) {
                        labels.forEachIndexed { index, label ->
                            val active = selected.intValue == index
                            FloatingNavigationBarItem(
                                selected = active,
                                onClick = { onSelected(index) },
                                icon = ImageVector.vectorResource(if (active) icons[index] else outlineIcons[index]),
                                label = label,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        composition.measure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(composition.measuredWidth, composition.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        composition.layout(0, 0, right - left, bottom - top)
        if (appearance == CouiAppearance.MODERN) placeBackdrop()
    }

    private fun placeBackdrop() {
        if (capsuleBounds.isEmpty) {
            backdrop.layout(0, 0, 0, 0)
            return
        }
        if (backdrop.measuredWidth != capsuleBounds.width() || backdrop.measuredHeight != capsuleBounds.height()) {
            backdrop.measure(MeasureSpec.makeMeasureSpec(capsuleBounds.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(capsuleBounds.height(), MeasureSpec.EXACTLY))
        }
        backdrop.layout(capsuleBounds.left, capsuleBounds.top, capsuleBounds.right, capsuleBounds.bottom)
    }

    internal fun snowBounds(out: android.graphics.RectF) {
        if (appearance == CouiAppearance.MODERN && !capsuleBounds.isEmpty) {
            out.set(out.left + capsuleBounds.left, out.top + capsuleBounds.top,
                out.left + capsuleBounds.right, out.top + capsuleBounds.bottom)
        } else if (appearance == CouiAppearance.ORIGINAL) {
            val d = resources.displayMetrics.density
            val w = minOf((24f + 70f * 3 + 8f * 2 - 16f) * d, width - 48f * d)
            val x = out.centerX()
            val y = out.top + height * .5f
            out.set(x - w * .5f, y - 28f * d, x + w * .5f, y + 28f * d)
        }
    }
}
