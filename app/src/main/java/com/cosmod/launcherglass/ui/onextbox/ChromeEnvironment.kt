package com.cosmod.launcherglass.ui.onextbox
import androidx.compose.runtime.staticCompositionLocalOf
internal val LocalChromeNativeBlurEnabled = staticCompositionLocalOf { true }
internal val LocalBottomBarContentOpacity = staticCompositionLocalOf<() -> Float> { { 1f } }
