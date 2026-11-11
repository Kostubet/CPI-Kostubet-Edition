// COUI source integration for the existing Android settings host.
// SPDX-License-Identifier: Apache-2.0
package io.github.suqi8.coui.kmp.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.LocalIndication
import io.github.suqi8.coui.kmp.utils.COUIIndication

@Composable
fun COUITheme(colors: Colors, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalTextStyles provides defaultTextStyles(),
        LocalContentColor provides colors.onSurface,
        LocalIndication provides COUIIndication(colors.onSurface),
        content = content,
    )
}
object COUITheme {
    val colorScheme: Colors
        @Composable @ReadOnlyComposable get() = LocalColors.current
    val textStyles: TextStyles
        @Composable @ReadOnlyComposable get() = LocalTextStyles.current
    val isDynamicColor: Boolean
        @Composable @ReadOnlyComposable get() = false
}
