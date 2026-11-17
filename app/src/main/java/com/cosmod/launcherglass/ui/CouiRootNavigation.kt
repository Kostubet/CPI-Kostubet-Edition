package com.cosmod.launcherglass.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.ui.onextbox.ColorOs17BottomNavigationBar
import io.github.suqi8.coui.kmp.basic.NavigationItem

/** Persistent upstream COUI navigation; restart actions live in the page toolbar. */
internal class CouiRootNavigation(context:Context,dark:Boolean,accent:Int,
    icons:List<Int>,outlineIcons:List<Int>,labels:List<String>,selected:MutableIntState,
    onSelected:(Int)->Unit):CouiControl(context,dark,accent){
    init {
        composition.layoutParams=LayoutParams(-1,-1)
        composition.clipChildren=false;composition.clipToPadding=false
        content {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter){
                ColorOs17BottomNavigationBar(
                    items=labels.mapIndexed {index,label->NavigationItem(label,ImageVector.vectorResource(icons[index]))},
                    selectedIndex=selected.intValue,onItemSelected=onSelected,
                    modifier=Modifier.fillMaxWidth().height(88.dp),
                    unselectedIcons=outlineIcons.map {ImageVector.vectorResource(it)})
            }
        }
    }
}
