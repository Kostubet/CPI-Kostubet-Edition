package com.cosmod.launcherglass.ui
import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.DropdownDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.popup.WindowDropdownPopup

internal class CouiPopup(context: Context, dark: Boolean, accent: Int,
    options: List<Pair<Int,String>>, selected: Int, private val onDismiss: () -> Unit,
    onPick: (Int) -> Unit) : CouiControl(context, dark, accent) {
    var showing by mutableStateOf(true)
    init {
        composition.layoutParams = LayoutParams(-1,-1)
        content {
            val entry = remember(options, selected) {
                DropdownEntry(options.map { (id, label) ->
                    DropdownItem(text = label, selected = id == selected, onClick = { close(); onPick(id) })
                })
            }
            WindowDropdownPopup(entry, showing, onDismiss = ::close, onDismissFinished = onDismiss,
                maxHeight = 440.dp, dropdownColors = DropdownDefaults.dropdownColors())
        }
    }
    fun close() { showing = false }
}
