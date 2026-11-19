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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.CouiControl
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Pause
import io.github.suqi8.coui.kmp.icon.extended.Play
import io.github.suqi8.coui.kmp.icon.extended.Redo
import io.github.suqi8.coui.kmp.icon.extended.Undo
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
    history: History? = null,
) : CouiControl(context, dark, accent) {
    data class History(val undoLabel: String, val redoLabel: String, val undo: () -> Unit, val redo: () -> Unit)

    var duration by mutableIntStateOf(0)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    private var elapsedTenths by mutableIntStateOf(0)
    private var playing by mutableStateOf(false)

    init {
        content {
            val colors = COUITheme.colorScheme
            if (history != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${time(elapsedTenths)} / ${time(duration / 100)}",
                        Modifier.weight(1f), maxLines = 1,
                        style = COUITheme.textStyles.caption.copy(fontFeatureSettings = "tnum"),
                        color = colors.labelSecondary,
                    )
                    val symbol = if (playing) COUIIcons.Pause else COUIIcons.Play
                    TransportIcon(symbol, if (playing) pauseLabel else playLabel, toggle)
                    IconButton(onClick = stop, modifier = Modifier.size(44.dp), cornerRadius = 22.dp,
                        minWidth = 0.dp, minHeight = 0.dp) {
                        Icon(painterResource(R.drawable.ic_haptic_stop), stopLabel, Modifier.size(20.dp), tint = colors.labelPrimary)
                    }
                    TransportIcon(COUIIcons.Undo, history.undoLabel, history.undo, canUndo)
                    TransportIcon(COUIIcons.Redo, history.redoLabel, history.redo, canRedo)
                }
                return@content
            }
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${time(elapsedTenths)} / ${time(duration / 100)}",
                    style = COUITheme.textStyles.caption.copy(fontFeatureSettings = "tnum"),
                    color = colors.labelSecondary,
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
                        val symbol = if (playing) COUIIcons.Pause else COUIIcons.Play
                        Icon(symbol, null, Modifier.size(21.dp), tint = colors.onPrimary)
                        Text(if (playing) pauseLabel else playLabel, Modifier.padding(start = 7.dp),
                            color = colors.onPrimary, style = COUITheme.textStyles.bodyL, maxLines = 1)
                    }
                    Button(
                        onClick = stop,
                        minWidth = 48.dp,
                        minHeight = 48.dp,
                        cornerRadius = 24.dp,
                        insideMargin = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(color = colors.container16, contentColor = colors.labelPrimary),
                    ) {
                        Icon(painterResource(R.drawable.ic_haptic_stop), stopLabel, Modifier.size(20.dp), tint = colors.labelPrimary)
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun TransportIcon(icon: ImageVector, label: String, action: () -> Unit, enabled: Boolean = true) {
        val colors = COUITheme.colorScheme
        IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(44.dp), cornerRadius = 22.dp,
            minWidth = 0.dp, minHeight = 0.dp) {
            Icon(icon, label, Modifier.size(21.dp), tint = if (enabled) colors.labelPrimary else colors.labelTertiary)
        }
    }

    fun update(position: Int, active: Boolean) {
        elapsedTenths = position.coerceIn(0, duration) / 100
        playing = active
    }

    private fun time(tenths: Int) = "%d:%02d.%d".format(Locale.ROOT, tenths / 600, tenths / 10 % 60, tenths % 10)
}
