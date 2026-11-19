package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline as CouiOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.doOnPreDraw
import androidx.core.content.ContextCompat
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.onextbox.applyOplusPopupBlur
import io.github.suqi8.coui.kmp.basic.ListPopupDefaults
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner

/** Uses COUI's popup shape and palette during CPI's row-to-card transition. */
internal class CouiPopupBackground(context: Context, private val dark: Boolean) : View(context) {
    private val density = Density(context.resources.displayMetrics.density)
    private val radius = COUIRoundCorner.XXXL
    private val shape = SquircleShape(radius, COUIRoundCorner.Normal.Weight)
    private val spec = ListPopupDefaults.materialBlurSpec(dark)
    private var progress = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width <= 0 || view.height <= 0) return
                val size = Size(view.width.toFloat(), view.height.toFloat())
                val path = (shape.createOutline(size, LayoutDirection.Ltr, density) as CouiOutline.Generic).path
                outline.setPath(path.asAndroidPath())
            }
        }
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(context, R.color.cpi_ui_panel))
            cornerRadius = with(density) { radius.toPx() }
        }
        renderProgress(progress)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        doOnPreDraw {
            if (couiMaterialEnabled(context) && applyOplusPopupBlur(this, spec, with(density) { radius.toPx() })) {
                renderProgress(progress)
            }
        }
    }

    fun renderProgress(value: Float) {
        progress = value
        background?.alpha = (255f * progress.coerceIn(0f, 1f)).toInt()
    }
}
