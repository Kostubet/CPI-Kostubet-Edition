// From ONextBox. SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.ui.onextbox
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
@Composable
internal fun ColorOsSettingsSliderRow(
    title: String,
    enabled: Boolean,
    hasDividerAbove: Boolean,
    hasDividerBelow: Boolean,
    heading: String? = null,
    titleAction: (@Composable () -> Unit)? = null,
    interactionEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val rowModifier = Modifier.settingsInteractionGate(interactionEnabled)


    val interactionSource = remember { MutableInteractionSource() }
    val titleAlpha by animateFloatAsState(
        targetValue = if (enabled) 1f else ColorOsSettingsSliderDisabledTitleAlpha,
        animationSpec = tween(durationMillis = ColorOsSettingsSliderStateAnimationMillis),
        label = "ColorOsSettingsSliderTitleAlpha",
    )
    Column(
        modifier = rowModifier
            .fillMaxWidth()
            .settingsInteractiveRowHighlight(
                interactionSource = interactionSource,
                color = Color.Transparent,
                hasDividerAbove = hasDividerAbove,
                hasDividerBelow = hasDividerBelow,
            )
            // Slider title padding inside the card.
            .padding(horizontal = 16.dp),
    ) {
        if (heading != null) {
            Column(Modifier.padding(top = 10.dp)) {
                SettingsRowTextContent(title = heading, summary = null, enabled = enabled)
            }
        }
        Row(
            modifier = Modifier.padding(top = if (heading == null) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = COUITheme.textStyles.title3,
                color = COUITheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f, fill = true).graphicsLayer { alpha = titleAlpha },
            )
            titleAction?.invoke()
        }
        content()
    }
}

internal const val ColorOsSettingsSliderStateAnimationMillis = 183
internal const val ColorOsSettingsSliderDisabledTitleAlpha = 0.38f
