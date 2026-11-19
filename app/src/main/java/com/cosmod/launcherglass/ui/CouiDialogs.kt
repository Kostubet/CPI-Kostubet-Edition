package com.cosmod.launcherglass.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.ui.onextbox.ColorOs17Button
import com.cosmod.launcherglass.ui.onextbox.ColorOs17ConfirmDialog
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardDefaults
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.layout.DialogButtonBar
import io.github.suqi8.coui.kmp.layout.DialogButtonBarAction
import io.github.suqi8.coui.kmp.effect.LocalCOUIMaterialEffectEnabled
import io.github.suqi8.coui.kmp.squircle.SquircleShape
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.window.WindowDialog

@Composable
internal fun CouiGroup(content: @Composable ColumnScope.() -> Unit) {
    val edge = if (LocalCOUIMaterialEffectEnabled.current &&
        CouiAppearance.read(LocalContext.current) == CouiAppearance.MODERN) {
        val color = COUITheme.colorScheme
        Modifier.border(1.dp, color.labelPrimary.copy(alpha = if (color.isDark) .13f else .09f),
            SquircleShape(CardDefaults.CornerRadius))
    } else Modifier
    Card(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).then(edge),
        insideMargin = PaddingValues(0.dp), content = content)
}

@Composable
internal fun CouiAction(
    text: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minimumHeight: Dp = ButtonDefaults.MinHeight,
    enabled: Boolean = true,
) {
    if (CouiAppearance.read(LocalContext.current) == CouiAppearance.ORIGINAL) {
        ColorOs17Button(text, primary, onClick = onClick, modifier = modifier,
            minimumHeight = minimumHeight, enabled = enabled)
        return
    }
    TextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        minHeight = minimumHeight,
        minWidth = 0.dp,
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
internal fun CouiConfirmation(
    show: Boolean,
    title: String,
    summary: String,
    negativeText: String,
    positiveText: String,
    onDismissRequest: () -> Unit,
    onNegative: () -> Unit,
    onPositive: () -> Unit,
    onDismissFinished: () -> Unit,
    content: (@Composable () -> Unit)?,
) {
    if (CouiAppearance.read(LocalContext.current) == CouiAppearance.ORIGINAL) {
        ColorOs17ConfirmDialog(show, title, summary, negativeText, positiveText, onDismissRequest,
            onNegative, onPositive, onDismissFinished, content)
        return
    }
    WindowDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        materialStroke = LocalCOUIMaterialEffectEnabled.current,
        customContent = content,
    ) {
        DialogButtonBar(
            negative = DialogButtonBarAction(negativeText, onClick = onNegative),
            positive = DialogButtonBarAction(positiveText, recommended = true, onClick = onPositive),
        )
    }
}
