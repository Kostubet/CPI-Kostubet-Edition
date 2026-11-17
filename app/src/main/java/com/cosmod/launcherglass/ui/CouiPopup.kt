package com.cosmod.launcherglass.ui
import android.content.Context
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.ui.onextbox.ColorOsDropdownPopup
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem

internal class CouiPopup(context: Context, dark: Boolean, accent: Int,
    options: List<Pair<Int,String>>, selected: Int, private val onDismiss: () -> Unit,
    onPick: (Int) -> Unit) : CouiControl(context, dark, accent) {
    var showing by mutableStateOf(true)
    init {
        composition.layoutParams = LayoutParams(-1,-1)
        content {
            val entry = remember(options, selected) { DropdownEntry(options.map { (id,label) ->
                DropdownItem(text = label, selected = id == selected)
            }) }
            ColorOsDropdownPopup(showing, entry, maxHeight = 440.dp,
                onDismissRequest = { close() }, onItemClick = { index ->
                    options.getOrNull(index)?.let { close(); onPick(it.first) }
                })
        }
    }
    fun close() { if (showing) { showing = false; postDelayed(onDismiss, 500) } }
}
