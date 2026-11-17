package com.cosmod.launcherglass.ui.onextbox
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
internal val LocalChromeNativeBlurEnabled = staticCompositionLocalOf { true }
internal val LocalBottomBarContentOpacity = staticCompositionLocalOf<() -> Float> { { 1f } }
internal object AppUiTokens { val CardCornerRadius = 20.dp }
