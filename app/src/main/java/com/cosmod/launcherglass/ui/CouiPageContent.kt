package com.cosmod.launcherglass.ui

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.cosmod.launcherglass.ui.onextbox.LocalChromeNativeBlurEnabled
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme

/** Read system insets before the Compose host or an AndroidView can consume them. */
internal fun AppCompatActivity.observeCouiWindowInsets(update: (Insets) -> Unit) {
    val decor = window.decorView
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    var last: Insets? = null
    fun apply(insets: WindowInsetsCompat) {
        val safe = insets.getInsetsIgnoringVisibility(types)
        if (safe == last) return
        last = safe
        update(safe)
    }
    fun applyWindowMetrics() {
        apply(WindowInsetsCompat.toWindowInsetsCompat(windowManager.currentWindowMetrics.windowInsets, decor))
    }
    applyWindowMetrics()
    ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
        apply(insets)
        ViewCompat.onApplyWindowInsets(view, insets)
    }
    decor.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        if (r - l != oldR - oldL || b - t != oldB - oldT) {
            applyWindowMetrics()
            ViewCompat.requestApplyInsets(decor)
        }
    }
    ViewCompat.requestApplyInsets(decor)
}

/** Hosts existing Android previews and editors in the COUI page scaffold. */
internal fun AppCompatActivity.setCouiPageContent(body: View, dark: Boolean, accent: Int) {
    val colors = if (dark) darkColorScheme(primary = Color(accent)) else lightColorScheme(primary = Color(accent))
    setContentView(ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            COUITheme(colors = colors) {
                val effects = couiMaterialEnabled(context)
                CompositionLocalProvider(
                    LocalNavigationEventDispatcherOwner provides this@setCouiPageContent,
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
