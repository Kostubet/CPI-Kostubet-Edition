package com.cosmod.launcherglass.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.onextbox.ColorOsSearchSurface
import io.github.suqi8.coui.kmp.basic.InputField
import io.github.suqi8.coui.kmp.basic.SearchBar
import io.github.suqi8.coui.kmp.basic.SearchBarDefaults
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.TextButton

/** Keeps the CPI catalog query in sync with COUI's search field. */
internal class CouiSearchHost(
    context: Context,
    dark: Boolean,
    accent: Int,
    private val surface: Int,
    foreground: Int,
    label: String,
    initialQuery: String,
    private val onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    private val onFocusChanged: () -> Unit,
) : CouiControl(context, dark, accent, surface) {
    private var queryText by mutableStateOf(initialQuery)
    private var inputFocused by mutableStateOf(false)
    private var clearInputFocus: (() -> Unit)? = null
    private var requestedWidth = LayoutParams.MATCH_PARENT
    val capsuleWidth get() = composition.width
    private val backdrop = CouiChromeBackdrop(context, dark) {}

    init {
        WinterEffects.mark(this, 25f, 2)
        if (appearance == CouiAppearance.MODERN) addView(backdrop, 0, LayoutParams(0, 0))
        composition.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        content {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            clearInputFocus = { keyboard?.hide(); focus.clearFocus() }

            val input: @Composable () -> Unit = {
                InputField(
                    query = queryText,
                    onQueryChange = ::setQuery,
                    onSearch = { onSearch() },
                    expanded = inputFocused || queryText.isNotEmpty(),
                    onExpandedChange = { if (!it) cancelSearch() },
                    label = label,
                    material = appearance == CouiAppearance.MODERN && couiMaterialEnabled(context),
                    colors = SearchBarDefaults.searchBarColors(
                        backgroundColor = if (appearance == CouiAppearance.ORIGINAL) Color.Transparent else Color(surface),
                        labelColor = Color(foreground).copy(alpha = .74f),
                        iconColor = Color(foreground).copy(alpha = .8f),
                        clearIconColor = Color(foreground).copy(alpha = .5f),
                    ),
                    modifier = Modifier.fillMaxSize().onFocusChanged {
                        if (inputFocused != it.hasFocus) {
                            inputFocused = it.hasFocus
                            post { onFocusChanged() }
                        }
                    },
                )
            }
            // The published COUI 2.0 uses SearchBar for the animated cancel action.
            SearchBar(
                modifier = Modifier.fillMaxWidth(),
                expanded = inputFocused || queryText.isNotEmpty(),
                insideMargin = DpSize.Zero,
                onExpandedChange = { if (!it) cancelSearch() },
                inputField = {
                    if (appearance == CouiAppearance.ORIGINAL) {
                        ColorOsSearchSurface(Modifier.fillMaxWidth().height(45.dp), Color(surface),
                            inputFocused, queryText.isEmpty()) { input() }
                    } else input()
                },
                outsideEndAction = {
                    TextButton(
                        text = context.getString(R.string.ui_cancel),
                        onClick = ::cancelSearch,
                        minWidth = 0.dp,
                        minHeight = 40.dp,
                        colors = ButtonDefaults.textButtonColorsBorderless(),
                    )
                },
            ) {}
        }
    }

    /** Keep the catalog's measure/layout pass out of the search spring. */
    fun resizeCapsule(value: Int) {
        if (requestedWidth == value) return
        requestedWidth = value
        if (isLaidOut) {
            measureCapsule(width, height)
            placeCapsule(width, height)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        measureCapsule(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        placeCapsule(right - left, bottom - top)
    }

    private fun measureCapsule(width: Int, height: Int) {
        val visibleWidth = if (requestedWidth < 0) width else requestedWidth.coerceIn(0, width)
        val widthSpec = MeasureSpec.makeMeasureSpec(visibleWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        if (appearance == CouiAppearance.MODERN) backdrop.measure(widthSpec, heightSpec)
        composition.measure(widthSpec, heightSpec)
    }

    private fun placeCapsule(width: Int, height: Int) {
        val visibleWidth = composition.measuredWidth
        val left = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - visibleWidth else 0
        if (appearance == CouiAppearance.MODERN) backdrop.layout(left, 0, left + visibleWidth, height)
        composition.layout(left, 0, left + visibleWidth, height)
    }

    fun isInputFocused(): Boolean = inputFocused

    internal fun snowBounds(out: android.graphics.RectF) {
        out.left += composition.left
        out.right = out.left + composition.width
    }

    fun setQuery(value: String) {
        if (queryText == value) return
        queryText = value
        onQueryChanged(value)
    }

    fun closeKeyboard() {
        clearInputFocus?.invoke()
    }

    private fun cancelSearch() {
        setQuery("")
        closeKeyboard()
    }

    override fun onDetachedFromWindow() {
        clearInputFocus = null
        super.onDetachedFromWindow()
    }
}
