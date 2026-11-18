package com.cosmod.launcherglass.ui

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.SmallTopAppBar
import io.github.suqi8.coui.kmp.icon.COUIIcons
import io.github.suqi8.coui.kmp.icon.extended.Back

internal class CouiDetailToolbar(
    context: Context, dark: Boolean, accent: Int, title: String, backLabel: String, onBack: () -> Unit,
) : CouiControl(context, dark, accent) {
    var actions by mutableStateOf(emptyList<View>())

    init {
        composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content {
            SmallTopAppBar(
                title = title,
                modifier = Modifier.fillMaxSize().consumeWindowInsets(WindowInsets.statusBars),
                color = Color.Transparent,
                showDivider = false,
                defaultWindowInsetsPadding = false,
                navigationIconPadding = 0.dp,
                actionIconPadding = 0.dp,
                titlePadding = 6.dp,
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(46.dp), cornerRadius = 23.dp) {
                        Icon(COUIIcons.Back, backLabel, Modifier.size(22.dp))
                    }
                },
                actions = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        actions.forEach { action ->
                            AndroidView(factory = { action }, modifier = Modifier.size(46.dp))
                        }
                    }
                },
            )
        }
    }
}
