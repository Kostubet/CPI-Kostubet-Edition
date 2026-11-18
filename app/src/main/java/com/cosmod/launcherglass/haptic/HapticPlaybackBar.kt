package com.cosmod.launcherglass.haptic

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.CouiControl
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
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
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${time(elapsedTenths)} / ${time(duration / 100)}",
                    style = COUITheme.textStyles.footnote1.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = toggle,
                        modifier = Modifier.weight(1f),
                        minWidth = 0.dp,
                        minHeight = 48.dp,
                        cornerRadius = 24.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp),
                        colors = ButtonDefaults.buttonColors(color = colors.primary, contentColor = colors.onPrimary),
                    ) {
                        val symbol = if (playing) R.drawable.ic_haptic_pause else R.drawable.ic_haptic_play
                        Icon(painterResource(symbol), null, Modifier.size(21.dp), tint = colors.onPrimary)
                        Text(if (playing) pauseLabel else playLabel, Modifier.padding(start = 7.dp),
                            color = colors.onPrimary, style = COUITheme.textStyles.body1, maxLines = 1)
                    }
                    Button(
                        onClick = stop,
                        minWidth = 48.dp,
                        minHeight = 48.dp,
                        cornerRadius = 24.dp,
                        insideMargin = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(color = colors.surfaceContainerHigh, contentColor = colors.onSurface),
                    ) {
                        Icon(painterResource(R.drawable.ic_haptic_stop), stopLabel, Modifier.size(20.dp), tint = colors.onSurface)
                    }
                }
            }
        }
    }

    fun update(position: Int, active: Boolean) {
        elapsedTenths = position.coerceIn(0, duration) / 100
        playing = active
    }

    private fun time(tenths: Int) = "%d:%02d.%d".format(Locale.ROOT, tenths / 600, tenths / 10 % 60, tenths % 10)
}
