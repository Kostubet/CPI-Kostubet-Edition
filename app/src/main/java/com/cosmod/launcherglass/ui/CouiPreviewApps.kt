package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.onextbox.ColorOs17ConfirmDialog
import com.cosmod.launcherglass.ui.onextbox.ColorOsDialogChoice
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.basic.TextFieldMode
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A searchable, virtualized app list inside the upstream COUI dialog. */
internal class CouiPreviewApps(context:Context,dark:Boolean,accent:Int,initial:List<String>,
    onDismiss:()->Unit,onApply:(List<String>)->Unit):CouiControl(context,dark,accent){
    private data class Entry(val pkg:String,val label:String)
    init {
        composition.layoutParams=LayoutParams(-1,-1)
        content {
            var showing by remember {mutableStateOf(true)}
            var selection by remember {mutableStateOf(initial.take(3))}
            var query by remember {mutableStateOf(TextFieldValue())}
            var entries by remember {mutableStateOf<List<Entry>?>(null)}
            LaunchedEffect(Unit){
                entries=withContext(Dispatchers.IO){
                    context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER),0)
                        .distinctBy {it.activityInfo.packageName}
                        .map {Entry(it.activityInfo.packageName,it.loadLabel(context.packageManager).toString())}
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER){it.label})
                }
            }
            ColorOs17ConfirmDialog(showing,context.getString(R.string.ui_preview_apps),
                context.getString(R.string.ui_preview_apps_hint)+" (${selection.size}/3)",
                context.getString(android.R.string.cancel),context.getString(android.R.string.ok),
                onDismissRequest={showing=false},onNegative={showing=false},
                onPositive={onApply(selection);showing=false},onDismissFinished=onDismiss,content={
                    TextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth(),
                        backgroundMode=TextFieldMode.Rectangle,cornerRadius=16.dp,
                        label=context.getString(R.string.ui_preview_apps_search),singleLine=true)
                    ColorOsDialogChoice(context.getString(R.string.ui_preview_apps_reset),"") {selection=emptyList()}
                    val visible=remember(entries,query.text){entries?.filter {
                        it.label.contains(query.text,true) || it.pkg.contains(query.text,true)
                    }}
                    LazyColumn(Modifier.fillMaxWidth().height(330.dp)){
                        if(visible==null)item {Text(context.getString(R.string.ui_preview_apps_loading))}
                        else items(visible,key={it.pkg}){entry->
                            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                                val bitmap by produceState<android.graphics.Bitmap?>(null,entry.pkg){
                                    value=withContext(Dispatchers.IO){runCatching {
                                        context.packageManager.getApplicationIcon(entry.pkg).toBitmap(96,96)
                                    }.getOrNull()}
                                }
                                bitmap?.let {Image(it.asImageBitmap(),null,Modifier.padding(end=10.dp).size(32.dp))}
                                Box(Modifier.weight(1f)){
                                    ColorOsDialogChoice((if(entry.pkg in selection)"✓  "else "")+entry.label,entry.pkg){
                                        selection=if(entry.pkg in selection)selection-entry.pkg
                                            else if(selection.size<3)selection+entry.pkg else selection
                                    }
                                }
                            }
                        }
                    }
                })
        }
    }
}
