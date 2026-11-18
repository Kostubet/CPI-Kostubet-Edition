package com.cosmod.launcherglass.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.onextbox.ColorOsAppBarBlurView
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.HorizontalDivider
import io.github.suqi8.coui.kmp.basic.Checkbox
import io.github.suqi8.coui.kmp.basic.BasicComponent
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@Composable
internal fun FloatingDialogHeader(title:String,foreground:Color,onClose:()->Unit,
    editing:Boolean=false,onEdit:(()->Unit)?=null,
    actionIcon:Int?=null,actionDescription:String?=null,onAction:(()->Unit)?=null){
    Box(Modifier.fillMaxWidth().heightIn(min=52.dp)){
        Text(title,modifier=Modifier.fillMaxWidth().padding(horizontal=54.dp)
            .align(Alignment.Center).semantics {heading()},
            color=foreground,fontSize=17.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center,
            maxLines=2,overflow=TextOverflow.Ellipsis,lineHeight=20.sp)
        IconButton(onClick=onClose,modifier=Modifier.align(Alignment.CenterStart)){
            Icon(painterResource(R.drawable.ic_close),stringResource(R.string.ui_floating_close),
                Modifier.size(19.dp),tint=foreground)
        }
        if(onEdit!=null)IconButton(onClick=onEdit,modifier=Modifier.align(Alignment.CenterEnd)){
            Crossfade(editing,animationSpec=tween(190),label="profileEditIcon"){isEditing->
                Icon(painterResource(if(isEditing)R.drawable.ic_check else R.drawable.ic_cpi_edit),
                    stringResource(if(isEditing)R.string.ui_saved_settings_done else R.string.ui_saved_settings_edit),
                    Modifier.size(19.dp),tint=foreground)
            }
        }
        if(onAction!=null && actionIcon!=null)
            IconButton(onClick=onAction,modifier=Modifier.align(Alignment.CenterEnd)){
                Icon(painterResource(actionIcon),actionDescription,Modifier.size(19.dp),tint=foreground)
            }
    }
}

@Composable
internal fun SavedProfileList(
    names:List<String>, active:String?, listHeight:Int, foreground:Color, blurHeader:Boolean, onPick:(String)->Unit,
    onSave:()->Unit, onClose:()->Unit, onOrder:(List<String>)->Unit, onDelete:(Set<String>)->Unit,
){
    var order by remember {mutableStateOf(names)}
    var editing by remember {mutableStateOf(false)}
    var selected by remember {mutableStateOf(emptySet<String>())}
    var dragged by remember {mutableStateOf<String?>(null)}
    var dragOffset by remember {mutableFloatStateOf(0f)}
    var settling by remember {mutableStateOf<Job?>(null)}
    val list=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val step=with(LocalDensity.current){48.dp.toPx()}
    val blurDistance=with(LocalDensity.current){30.dp.toPx()}
    val dark=COUITheme.colorScheme.background.luminance()<.5f
    val surface=COUITheme.colorScheme.surface.toArgb()
    val upLabel=stringResource(R.string.ui_saved_settings_move_up)
    val downLabel=stringResource(R.string.ui_saved_settings_move_down)
    fun toggle(name:String){selected=if(name in selected)selected-name else selected+name}
    fun move(name:String,delta:Int):Boolean {
        val from=order.indexOf(name)
        val to=from+delta
        if(from<0 || to !in order.indices)return false
        order=order.toMutableList().apply {add(to,removeAt(from))}
        val visible=list.layoutInfo.visibleItemsInfo
        if(visible.isNotEmpty() && to !in visible.first().index..visible.last().index){
            scope.launch {list.scrollToItem(to)}
        }
        return true
    }
    fun settle(name:String){
        if(dragged!=name)return
        onOrder(order)
        settling=scope.launch {
            animate(dragOffset,0f,animationSpec=spring(dampingRatio=.78f,stiffness=Spring.StiffnessMediumLow)){
                    offset,_->dragOffset=offset
            }
            dragged=null
        }
    }
    fun toggleEditing(){
        if(editing)onOrder(order)
        settling?.cancel()
        dragged=null
        dragOffset=0f
        editing=!editing
        selected=emptySet()
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        FloatingDialogHeader(stringResource(R.string.ui_presets),foreground,onClose,editing,
            onEdit=if(order.isEmpty())null else ::toggleEditing)
        if(active!=null){
            Text(stringResource(R.string.ui_saved_settings_active,active),
                modifier=Modifier.fillMaxWidth().padding(horizontal=8.dp),
                color=foreground,textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
        if(order.isNotEmpty())Box(Modifier.fillMaxWidth()){
          LazyColumn(modifier=Modifier.fillMaxWidth().heightIn(max=listHeight.dp),state=list){
            items(order,key={it}){name->
                val moving=dragged==name
                val placement=if(moving)Modifier.zIndex(1f).graphicsLayer {translationY=dragOffset}
                    else Modifier.animateItem(fadeInSpec=null,fadeOutSpec=null,
                        placementSpec=spring(dampingRatio=.82f,stiffness=Spring.StiffnessMediumLow))
                Box(modifier=placement.fillMaxWidth()){
                    BasicComponent(onClick={if(editing)toggle(name) else onPick(name)}){
                        Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){
                            Text(name,modifier=Modifier.fillMaxWidth().padding(horizontal=34.dp),
                                color=foreground,textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Crossfade(editing,modifier=Modifier.align(Alignment.CenterStart),
                                animationSpec=tween(185),label="profileSelection"){isEditing->
                                Box(Modifier.size(26.dp),contentAlignment=Alignment.Center){
                                    if(isEditing)Checkbox(if(name in selected)ToggleableState.On else ToggleableState.Off,null)
                                    else if(name==active)Icon(painterResource(R.drawable.ic_check),null,tint=foreground)
                                }
                            }
                        }
                    }
                    Box(Modifier.align(Alignment.CenterEnd).size(44.dp),contentAlignment=Alignment.Center){
                      androidx.compose.animation.AnimatedVisibility(editing,
                        enter=slideInHorizontally(tween(205)){it/4}+fadeIn(tween(205)),
                        exit=slideOutHorizontally(tween(175)){it/4}+fadeOut(tween(135))){
                        val reorder by rememberUpdatedState<(Int)->Boolean>({move(name,it)})
                        val persist by rememberUpdatedState<()->Unit>({onOrder(order)})
                        val release by rememberUpdatedState<()->Unit>({settle(name)})
                        Box(Modifier.size(44.dp).semantics {
                            customActions=listOf(CustomAccessibilityAction(upLabel){
                                reorder(-1).also {if(it)persist()}
                            },CustomAccessibilityAction(downLabel){reorder(1).also {if(it)persist()}})
                        }.pointerInput(name,step){
                            detectVerticalDragGestures(onDragStart={
                                settling?.cancel()
                                dragOffset=0f
                                dragged=name
                            },onDragEnd={release()},onDragCancel={release()}){change,dragAmount->
                                change.consume()
                                val rowHeight=list.layoutInfo.visibleItemsInfo.firstOrNull {it.key==name}
                                    ?.size?.toFloat()?.coerceAtLeast(step) ?: step
                                dragOffset=(dragOffset+dragAmount).coerceIn(-rowHeight,rowHeight)
                                val direction=if(dragOffset>0)1 else -1
                                if(kotlin.math.abs(dragOffset)>=rowHeight*.55f){
                                    if(reorder(direction))dragOffset-=rowHeight*direction
                                    else dragOffset=direction*rowHeight*.55f
                                }
                            }
                        },contentAlignment=Alignment.Center){
                            Icon(painterResource(R.drawable.ic_cpi_reorder),stringResource(R.string.ui_saved_settings_reorder),tint=foreground)
                        }
                      }
                    }
                }
                if(name!=order.last())HorizontalDivider()
            }
          }
          if(blurHeader)AndroidView(
              factory={ColorOsAppBarBlurView(it,dark,surface,tintOverlay=false,maxRadius=18f)},
              modifier=Modifier.align(Alignment.TopCenter).fillMaxWidth().height(24.dp),
              update={header->
                  val amount=if(list.firstVisibleItemIndex>0)1f else list.firstVisibleItemScrollOffset/blurDistance
                  header.updateAppearance(dark,surface,amount)
              },
          )
        }
        CouiAction(stringResource(if(editing)R.string.ui_saved_settings_delete_action else R.string.ui_saved_settings_save_action),
            primary=true,modifier=Modifier.fillMaxWidth(),enabled=!editing || selected.isNotEmpty(),
            minimumHeight=46.dp,
            onClick={
                if(editing){onOrder(order);onDelete(selected)}else onSave()
            })
    }
}
