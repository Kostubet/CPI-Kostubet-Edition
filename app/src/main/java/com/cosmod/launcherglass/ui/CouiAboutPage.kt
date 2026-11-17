// AboutAppCard / BuildTypeBadge from ONextBox 5e417d7, MiToverG422 and contributors.
// Adapted only for CPI branding and its existing actions. SPDX-License-Identifier: GPL-3.0-only
package com.cosmod.launcherglass.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.onextbox.*
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

internal class CouiAboutPage(context:Context,dark:Boolean,accent:Int,enabled:Boolean,
    onEnabled:(Boolean)->Unit,onSettings:()->Unit,onContributors:()->Unit,onLink:(String)->Unit):CouiControl(context,dark,accent){
    init {
        val logo=context.applicationInfo.loadIcon(context.packageManager).toBitmap(186,186).asImageBitmap()
        content {
            var active by remember {mutableStateOf(enabled)}
            Column {
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally){
                        Image(logo,null,Modifier.size(62.dp).clip(RoundedCornerShape(18.dp))
                            .background(COUITheme.colorScheme.onSurface.copy(alpha=.08f)))
                        Spacer(Modifier.height(8.dp))
                        Text(context.getString(R.string.app_name),color=COUITheme.colorScheme.onSurface,
                            fontSize=24.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
                        Spacer(Modifier.height(2.dp))
                        Text(context.getString(R.string.ui_about_version,BuildConfig.VERSION_NAME),
                            style=COUITheme.textStyles.body2,color=COUITheme.colorScheme.onSurfaceVariantSummary,fontSize=14.sp)
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF6C50C9))
                            .padding(horizontal=7.dp,vertical=2.dp),contentAlignment=Alignment.Center){
                            Text(context.getString(R.string.ui_release_badge),style=COUITheme.textStyles.body1,
                                color=Color.White,fontWeight=FontWeight.Bold,fontSize=10.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(context.getString(R.string.ui_module_description),style=COUITheme.textStyles.body1,
                            color=COUITheme.colorScheme.onSurfaceVariantSummary,fontSize=13.sp,textAlign=TextAlign.Center)
                    }
                }
                SettingsGroup {
                    SettingsSwitchPreference(active,{active=it;onEnabled(it)},context.getString(R.string.ui_module))
                    SettingsDivider()
                    SettingsCardRow(context.getString(R.string.ui_cpi_settings),"",showArrow=true,
                        hasDividerAbove=true,onClick=onSettings)
                }
                SettingsSection(context.getString(R.string.ui_support_author))
                SettingsGroup {
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=14.dp),
                        horizontalAlignment=Alignment.CenterHorizontally){
                        Text(context.getString(R.string.ui_support_author_description),
                            style=COUITheme.textStyles.body1,color=COUITheme.colorScheme.onSurface,
                            textAlign=TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        ColorOs17Button(context.getString(R.string.ui_support_author),primary=true,
                            onClick={onLink("https://pay.cloudtips.ru/p/3d28d485")})
                    }
                }
                SettingsSection(context.getString(R.string.ui_authors))
                SettingsGroup {
                    SettingsCardRow("@immorgue","",showArrow=true,hasDividerBelow=true,
                        onClick={onLink("https://t.me/naten_e_mire")})
                    SettingsDivider()
                    SettingsCardRow("@Kostubet","",showArrow=true,hasDividerAbove=true,hasDividerBelow=true,
                        onClick={onLink("https://t.me/Kostubet")})
                    SettingsDivider()
                    SettingsCardRow(context.getString(R.string.ui_about_contributors),"",showArrow=true,
                        hasDividerAbove=true,onClick=onContributors,leadingContent={Icon(painterResource(R.drawable.ic_cpi_people),null)})
                }
                SettingsGroup {
                    SettingsCardRow(context.getString(R.string.ui_telegram_forum),"Kostubet Topics",showArrow=true,
                        hasDividerBelow=true,onClick={onLink("https://t.me/KostubetTopics")},
                        leadingContent={Icon(painterResource(R.drawable.ic_cpi_telegram),null)})
                    SettingsDivider()
                    SettingsCardRow(context.getString(R.string.ui_github_repository),"Kostubet/CPI-Kostubet-Edition",showArrow=true,
                        hasDividerAbove=true,onClick={onLink("https://github.com/Kostubet/CPI-Kostubet-Edition")},
                        leadingContent={Icon(painterResource(R.drawable.ic_cpi_code),null)})
                }
            }
        }
    }
}
