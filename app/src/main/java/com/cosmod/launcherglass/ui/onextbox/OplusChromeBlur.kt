// Android compositor integration adapted from ONextBox by MiToverG422 and contributors.
// SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.ui.onextbox

import android.graphics.BlendMode as AndroidBlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.View
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.toArgb
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBarDefaults

internal object OplusChromeBlur {
    private val setEffect by lazy {
        runCatching {
            Class.forName("com.oplus.view.OplusViewBackgroundRenderEffect").getMethod(
                "setBackgroundRenderEffect", RenderEffect::class.java, View::class.java,
            )
        }.getOrNull()
    }

    private val darkEffect by lazy { createEffect(dark = true) }
    private val lightEffect by lazy { createEffect(dark = false) }

    fun apply(view: View, dark: Boolean): Boolean {
        val method = setEffect ?: return false
        return runCatching {
            method.invoke(null, if (dark) darkEffect else lightEffect, view)
            true
        }.getOrDefault(false)
    }

    fun clear(view: View) {
        runCatching { setEffect?.invoke(null, null, view) }
    }

    private fun createEffect(dark: Boolean): RenderEffect {
        val spec = FloatingNavigationBarDefaults.backdropSpec(dark)
        val blur = RenderEffect.createBlurEffect(spec.blurRadiusPx, spec.blurRadiusPx, Shader.TileMode.MIRROR)
        return RenderEffect.createColorFilterEffect(
            BlendModeColorFilter(spec.layer2.toArgb(), spec.mode2.toViewMode()),
            RenderEffect.createColorFilterEffect(
                BlendModeColorFilter(spec.layer1.toArgb(), spec.mode1.toViewMode()),
                blur,
            ),
        )
    }

    private fun BlendMode.toViewMode() = when (this) {
        BlendMode.Overlay -> AndroidBlendMode.OVERLAY
        BlendMode.ColorDodge -> AndroidBlendMode.COLOR_DODGE
        BlendMode.Luminosity -> AndroidBlendMode.LUMINOSITY
        else -> AndroidBlendMode.SRC_OVER
    }
}
