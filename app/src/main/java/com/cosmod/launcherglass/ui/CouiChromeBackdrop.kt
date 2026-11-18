package com.cosmod.launcherglass.ui

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import com.cosmod.launcherglass.ui.onextbox.OplusChromeBlur

/** Behind the COUI chrome, so the compositor samples the page below the icons and labels. */
internal class CouiChromeBackdrop(
    context: Context,
    private val dark: Boolean,
    private val onAvailabilityChanged: (Boolean) -> Unit,
) : View(context) {
    private var installed = false
    private var attempted = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (view.width > 0 && view.height > 0) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        install()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        install()
    }

    private fun install() {
        if (attempted || !isAttachedToWindow || width == 0 || height == 0) return
        attempted = true
        installed = couiMaterialEnabled(context) && isHardwareAccelerated && OplusChromeBlur.apply(this, dark)
        onAvailabilityChanged(installed)
    }

    override fun onDetachedFromWindow() {
        if (installed) OplusChromeBlur.clear(this)
        installed = false
        attempted = false
        onAvailabilityChanged(false)
        super.onDetachedFromWindow()
    }
}
