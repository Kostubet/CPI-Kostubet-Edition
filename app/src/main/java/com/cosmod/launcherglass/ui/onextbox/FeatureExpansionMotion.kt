// From ONextBox. SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.ui.onextbox
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
internal object FeatureExpansionMotion {
    const val SizeDurationMillis = 280
    const val FadeDurationMillis = 180

    val Enter = expandVertically(
        tween(SizeDurationMillis, easing = FastOutSlowInEasing),
        expandFrom = Alignment.Top,
    ) + fadeIn(tween(FadeDurationMillis))

    val Exit = shrinkVertically(
        tween(SizeDurationMillis, easing = FastOutSlowInEasing),
        shrinkTowards = Alignment.Top,
    ) + fadeOut(tween(FadeDurationMillis))
}
