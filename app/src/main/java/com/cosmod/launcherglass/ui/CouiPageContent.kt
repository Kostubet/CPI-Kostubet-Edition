package com.cosmod.launcherglass.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import com.cosmod.launcherglass.ui.onextbox.LocalChromeNativeBlurEnabled
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme

/** Hosts existing Android previews and editors in the COUI page scaffold. */
internal fun AppCompatActivity.setCouiPageContent(body: View, dark: Boolean, accent: Int) {
    val colors = if (dark) darkColorScheme(primary = Color(accent)) else lightColorScheme(primary = Color(accent))
    setContentView(ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            COUITheme(colors = colors) {
                val effects = couiMaterialEnabled(context)
                CompositionLocalProvider(
                    LocalCOUIMaterialEffectEnabled provides effects,
                    LocalChromeNativeBlurEnabled provides effects,
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        // CPI's animated Android toolbars and lists already apply system-bar insets.
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        containerColor = Color.Transparent,
                    ) { padding ->
                        AndroidView(
                            factory = { body },
                            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                        )
                    }
                }
            }
        }
    })
}
