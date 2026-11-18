package com.cosmod.launcherglass.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBar
import io.github.suqi8.coui.kmp.basic.FloatingNavigationBarItem

/** Persistent upstream COUI navigation; restart actions live in the page toolbar. */
internal class CouiRootNavigation(context:Context,dark:Boolean,accent:Int,
    icons:List<Int>,outlineIcons:List<Int>,labels:List<String>,selected:MutableIntState,
    onSelected:(Int)->Unit):CouiControl(context,dark,accent){
    init {
        composition.layoutParams=LayoutParams(-1,-1)
        composition.clipChildren=false;composition.clipToPadding=false
        content {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){
                FloatingNavigationBar(defaultWindowInsetsPadding = false) {
                    labels.forEachIndexed { index, label ->
                        val active = selected.intValue == index
                        FloatingNavigationBarItem(
                            selected = active,
                            onClick = { onSelected(index) },
                            icon = ImageVector.vectorResource(if (active) icons[index] else outlineIcons[index]),
                            label = label,
                        )
                    }
                }
            }
        }
    }
}
