package com.cosmod.launcherglass.haptic

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.ui.CouiControl
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.basic.Close
import io.github.suqi8.coui.kmp.theme.COUITheme
import java.util.Locale

internal class HapticPlaybackBar(
    context: Context,
    dark: Boolean,
    accent: Int,
    playLabel: String,
    pauseLabel: String,
    stopLabel: String,
    toggle: () -> Unit,
    stop: () -> Unit,
) : CouiControl(context, dark, accent) {
    var duration by mutableIntStateOf(0)
    private var elapsedTenths by mutableIntStateOf(0)
    private var playing by mutableStateOf(false)

    init {
        content {
            val colors = COUITheme.colorScheme
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = toggle,
                    backgroundColor = colors.primary,
                    cornerRadius = 26.dp,
                    minWidth = 52.dp,
                    minHeight = 52.dp,
                ) {
                    // The vendored COUI set has no transport glyphs. Use the native Android resources.
                    val glyph = if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
                    Icon(painterResource(glyph), if (playing) pauseLabel else playLabel,
                        Modifier.size(27.dp), tint = colors.onPrimary)
                }
                IconButton(onClick = stop, backgroundColor = colors.surfaceContainerHigh) {
                    Icon(COUIIcons.Basic.Close, stopLabel, Modifier.size(20.dp), tint = colors.onSurface)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${time(elapsedTenths)} / ${time(duration / 100)}",
                    style = COUITheme.textStyles.footnote1.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                )
            }
        }
    }

    fun update(position: Int, active: Boolean) {
        elapsedTenths = position.coerceIn(0, duration) / 100
        playing = active
    }

    private fun time(tenths: Int) = "%d:%02d.%d".format(Locale.ROOT, tenths / 600, tenths / 10 % 60, tenths % 10)
}
