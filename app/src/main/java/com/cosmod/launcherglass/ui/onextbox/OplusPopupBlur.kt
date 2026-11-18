// Android compositor adapter from ONextBox, MiToverG422 and contributors.
// SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.ui.onextbox

import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.effect.MaterialBlurBlendMode
import io.github.suqi8.coui.kmp.effect.MaterialBlurSpec
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner

internal fun applyOplusPopupBlur(view: View, spec: MaterialBlurSpec, radius: Float): Boolean = runCatching {
    if (Settings.System.getInt(view.context.contentResolver, "system_material_blur_enable", 0) != 1) return false
    if (view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled != true) return false

    val managerClass = Class.forName("com.oplus.view.ViewRootManager")
    val blurParamClass = Class.forName("com.oplus.graphics.OplusBlurParam")
    val manager = managerClass.getConstructor(View::class.java).newInstance(view)
    val blurParam = blurParamClass.getConstructor().newInstance()
    val int = Int::class.javaPrimitiveType
    val float = Float::class.javaPrimitiveType

    val mode = when (spec.blendMode) {
        MaterialBlurBlendMode.Overlay -> 2
        MaterialBlurBlendMode.ColorDodge -> 3
        MaterialBlurBlendMode.GlowOverlay -> 4
        MaterialBlurBlendMode.GlowColorDodge -> 5
    }
    blurParamClass.getMethod("setBlurType", int).invoke(blurParam, 2)
    blurParamClass.getMethod("setMaterialParams", int, FloatArray::class.java, FloatArray::class.java)
        .invoke(blurParam, mode, spec.blendColor.channels(), spec.mixColor.channels())
    runCatching {
        blurParamClass.getMethod("setSmoothCornerWeight", float).invoke(blurParam, COUIRoundCorner.Normal.Weight)
        blurParamClass.getMethod("setSmoothCornerType", int).invoke(blurParam, 1)
    }

    managerClass.getMethod("setBlurParams", blurParamClass).invoke(manager, blurParam)
    managerClass.getMethod("setBlurRadius", int).invoke(manager, spec.blurRadiusPx.toInt())
    managerClass.getMethod("setCornerRadius", float, float, float, float)
        .invoke(manager, radius, radius, radius, radius)
    managerClass.getMethod("setColor", int).invoke(manager, android.graphics.Color.TRANSPARENT)
    val backdrop = managerClass.getMethod("getBackgroundBlurDrawable").invoke(manager) as? Drawable ?: return false
    view.background = backdrop
    true
}.getOrDefault(false)

private fun Color.channels() = floatArrayOf(red, green, blue, alpha)
