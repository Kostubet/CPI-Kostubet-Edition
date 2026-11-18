package com.cosmod.launcherglass.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.suqi8.coui.kmp.basic.InputField

/** Keeps the CPI catalog query in sync with COUI's search field. */
internal class CouiSearchHost(
    context: Context,
    dark: Boolean,
    accent: Int,
    label: String,
    initialQuery: String,
    private val onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    private val onFocusChanged: () -> Unit,
) : CouiControl(context, dark, accent) {
    private var queryText by mutableStateOf(initialQuery)
    private var inputFocused by mutableStateOf(false)
    private var clearInputFocus: (() -> Unit)? = null

    init {
        composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            clearInputFocus = { keyboard?.hide(); focus.clearFocus() }

            InputField(
                query = queryText,
                onQueryChange = ::setQuery,
                onSearch = { onSearch() },
                expanded = inputFocused,
                onExpandedChange = { if (!it) closeKeyboard() },
                label = label,
                modifier = Modifier.fillMaxSize().onFocusChanged {
                    if (inputFocused != it.hasFocus) {
                        inputFocused = it.hasFocus
                        post { onFocusChanged() }
                    }
                },
            )
        }
    }

    fun isInputFocused(): Boolean = inputFocused

    fun setQuery(value: String) {
        if (queryText == value) return
        queryText = value
        onQueryChanged(value)
    }

    fun closeKeyboard() {
        clearInputFocus?.invoke()
    }

    override fun onDetachedFromWindow() {
        clearInputFocus = null
        super.onDetachedFromWindow()
    }
}
