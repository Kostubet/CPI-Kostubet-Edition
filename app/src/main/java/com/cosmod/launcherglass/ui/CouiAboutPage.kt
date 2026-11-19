package com.cosmod.launcherglass.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.TagView
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

internal class CouiAboutPage(
    context: Context, dark: Boolean, accent: Int, enabled: Boolean,
    onEnabled: (Boolean) -> Unit, onSettings: () -> Unit, onContributors: () -> Unit, onLink: (String) -> Unit,
) : CouiControl(context, dark, accent) {
    init {
        val logo = context.applicationInfo.loadIcon(context.packageManager).toBitmap(186, 186).asImageBitmap()
        content {
            var active by remember { mutableStateOf(enabled) }
            Column {
                CouiGroup {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(logo, null, Modifier.size(62.dp))
                        Text(context.getString(R.string.app_name), style = COUITheme.textStyles.headlineS,
                            textAlign = TextAlign.Center)
                        Text(context.getString(R.string.ui_about_version, BuildConfig.VERSION_NAME),
                            style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary)
                        TagView(context.getString(R.string.ui_release_badge))
                        Text(context.getString(R.string.ui_module_description),
                            style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary,
                            textAlign = TextAlign.Center)
                    }
                }
                CouiGroup {
                    CardListGroup {
                        item {
                            SwitchPreference(checked = active, onCheckedChange = { active = it; onEnabled(it) },
                                title = context.getString(R.string.ui_module))
                        }
                        item { ArrowPreference(title = context.getString(R.string.ui_cpi_settings), onClick = onSettings) }
                    }
                }
                SmallTitle(context.getString(R.string.ui_support_author))
                CouiGroup {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(context.getString(R.string.ui_support_author_description), textAlign = TextAlign.Center,
                            style = COUITheme.textStyles.bodyL)
                        CouiAction(context.getString(R.string.ui_support_author), primary = true,
                            onClick = { onLink("https://pay.cloudtips.ru/p/3d28d485") })
                    }
                }
                SmallTitle(context.getString(R.string.ui_authors))
                CouiGroup {
                    CardListGroup {
                        item { ArrowPreference(title = "@immorgue", onClick = { onLink("https://t.me/naten_e_mire") }) }
                        item { ArrowPreference(title = "@Kostubet", onClick = { onLink("https://t.me/Kostubet") }) }
                        item {
                            ArrowPreference(title = context.getString(R.string.ui_about_contributors),
                                startAction = { Icon(painterResource(R.drawable.ic_cpi_people), null) }, onClick = onContributors)
                        }
                    }
                }
                CouiGroup {
                    CardListGroup {
                        item {
                            ArrowPreference(title = context.getString(R.string.ui_telegram_forum), summary = "Kostubet Topics",
                                startAction = { Icon(painterResource(R.drawable.ic_cpi_telegram), null) },
                                onClick = { onLink("https://t.me/KostubetTopics") })
                        }
                        item {
                            ArrowPreference(title = context.getString(R.string.ui_github_repository),
                                summary = "Kostubet/CPI-Kostubet-Edition",
                                startAction = { Icon(painterResource(R.drawable.ic_cpi_code), null) },
                                onClick = { onLink("https://github.com/Kostubet/CPI-Kostubet-Edition") })
                        }
                    }
                }
            }
        }
    }
}
