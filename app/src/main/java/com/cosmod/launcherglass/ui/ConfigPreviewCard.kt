package com.cosmod.launcherglass.ui

import android.os.Bundle
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cosmod.launcherglass.R
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.RadioButtonPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

internal enum class ConfigGlassMaterial(val label: Int) {
    FROSTED(R.string.community_glass_frosted),
    LIQUID(R.string.community_glass_liquid),
    NEW_ENGINE(R.string.community_glass_new_engine),
    SOFT_LIGHT(R.string.soft_glass_material);

    companion object {
        fun read(settings: Bundle, scope: String, liquid: Boolean = true): ConfigGlassMaterial = when {
            !liquid -> FROSTED
            settings.getBoolean(GlassEngineSettings.softKey(scope)) -> SOFT_LIGHT
            settings.getBoolean(scope) -> NEW_ENGINE
            else -> LIQUID
        }
    }
}

internal class ConfigPreviewScenes(
    val view: View,
    val options: List<Pair<String, ConfigGlassMaterial>>,
    val wallpaperMessage: State<Int?>,
    val select: (Int) -> Unit,
)

@Composable
internal fun ConfigPreviewCard(
    document: SettingsTransfer.Document,
    createScenes: () -> ConfigPreviewScenes,
    onApply: (() -> Unit)?,
    onDismissFinished: (applied: Boolean) -> Unit,
) {
    var show by remember { mutableStateOf(true) }
    var apply by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var selected by remember { mutableIntStateOf(0) }
    CouiConfirmation(
        show = show,
        title = stringResource(R.string.ui_config_preview_title),
        summary = null,
        negativeText = stringResource(if (onApply == null) R.string.ui_close else android.R.string.cancel),
        positiveText = if (onApply == null) null else stringResource(R.string.ui_config_apply),
        onDismissRequest = { if (choosing) choosing = false else show = false },
        onNegative = { choosing = false; show = false },
        onPositive = { apply = true; choosing = false; show = false },
        onDismissFinished = {
            onDismissFinished(apply)
            if (apply) onApply?.invoke()
        },
        content = {
            val scenes = remember(document) { createScenes() }
            val groups = remember(scenes) { scenes.options.indices.groupBy { scenes.options[it].second } }
            val (selection, material) = scenes.options[selected]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidView(
                    factory = { scenes.view },
                    modifier = Modifier.fillMaxWidth().height(144.dp),
                )
                scenes.wallpaperMessage.value?.let { message ->
                    Text(stringResource(message), modifier = Modifier.padding(horizontal = 4.dp),
                        color = COUITheme.colorScheme.labelSecondary, style = COUITheme.textStyles.bodyM)
                }
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    CardListGroup {
                        item {
                            ArrowPreference(
                                title = selection,
                                summary = stringResource(material.label),
                                onClick = { choosing = !choosing },
                            )
                        }
                    }
                }
                AnimatedVisibility(choosing) {
                    Column {
                        ConfigGlassMaterial.entries.forEach { category ->
                            val options = groups[category].orEmpty()
                            if (options.isNotEmpty()) {
                                SmallTitle(stringResource(category.label))
                                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                                    CardListGroup {
                                        options.forEach { index ->
                                            item {
                                                RadioButtonPreference(
                                                    title = scenes.options[index].first,
                                                    selected = selected == index,
                                                    onClick = {
                                                        selected = index
                                                        scenes.select(index)
                                                        choosing = false
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    CardListGroup {
                        item { BasicComponent(title = stringResource(R.string.ui_config_title),
                            summary = document.metadata.title.ifBlank { stringResource(R.string.ui_config_legacy_title) }) }
                        if (document.metadata.description.isNotBlank()) item {
                            BasicComponent(title = stringResource(R.string.ui_config_description),
                                summary = document.metadata.description)
                        }
                        if (document.metadata.author.isNotBlank()) item {
                            BasicComponent(title = stringResource(R.string.ui_config_author),
                                summary = document.metadata.author)
                        }
                    }
                }
            }
        },
    )
}
