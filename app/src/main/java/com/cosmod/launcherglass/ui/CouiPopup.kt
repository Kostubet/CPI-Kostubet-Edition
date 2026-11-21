package com.cosmod.launcherglass.ui
import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.cosmod.launcherglass.ui.onextbox.createOplusPopupBackdrop
import com.cosmod.launcherglass.ui.onextbox.ColorOsDropdownPopup
import io.github.suqi8.coui.kmp.basic.DropdownDefaults
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.DropdownImpl
import io.github.suqi8.coui.kmp.basic.ListPopupColumn
import io.github.suqi8.coui.kmp.basic.PopupPositionProvider
import io.github.suqi8.coui.kmp.window.WindowListPopup
import io.github.suqi8.coui.kmp.effect.LocalPopupMaterialBlur
import io.github.suqi8.coui.kmp.effect.MaterialBlurProvider
import io.github.suqi8.coui.kmp.theme.COUIRoundCorner
import kotlin.math.roundToInt

internal class CouiPopup(context: Context, dark: Boolean, accent: Int, surface: Int,
    options: List<Pair<Int,String>>, selected: Int, private val onDismiss: () -> Unit,
    onPick: (Int) -> Unit) : CouiControl(context, dark, accent) {
    var showing by mutableStateOf(true)
    private var picked: Int? = null
    init {
        composition.layoutParams = LayoutParams(-1,-1)
        content {
            val entry = remember(options, selected) {
                DropdownEntry(options.map { (id, label) ->
                    DropdownItem(text = label, selected = id == selected, onClick = {
                        if (showing) {
                            picked = id
                            close()
                        }
                    })
                })
            }
            if (appearance == CouiAppearance.ORIGINAL) {
                ColorOsDropdownPopup(showing, entry, maxHeight = 440.dp, onDismissRequest = ::close,
                    onDismissFinished = { finish(onPick) }, onItemClick = { index ->
                        if (showing) {
                            picked = options[index].first
                            close()
                        }
                    })
            } else {
                val color = Color(surface)
                val settle = remember { Animatable(-6f) }
                LaunchedEffect(showing) {
                    if (showing) settle.animateTo(0f, spring(dampingRatio = .58f, stiffness = 320f))
                }
                val material = remember(color) {
                    MaterialBlurProvider { spec, _ ->
                        Modifier.composed {
                            val view = LocalView.current
                            drawWithCache {
                                val tinted = spec.copy(
                                    mixColor = lerp(spec.mixColor, color.copy(alpha = spec.mixColor.alpha), .45f),
                                )
                                val drawable = if (view.isHardwareAccelerated)
                                    createOplusPopupBackdrop(view, tinted, COUIRoundCorner.XXXL.toPx()) else null
                                drawable?.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
                                onDrawBehind {
                                    drawIntoCanvas { canvas ->
                                        if (drawable != null && canvas.nativeCanvas.isHardwareAccelerated) {
                                            drawable.draw(canvas.nativeCanvas)
                                        } else drawRect(color)
                                    }
                                }
                            }
                        }
                    }
                }
                CompositionLocalProvider(LocalPopupMaterialBlur provides material) {
                    // Applying a choice can rebuild the page that hosts this popup.
                    WindowListPopup(
                        show = showing,
                        popupModifier = Modifier.graphicsLayer { translationY = settle.value.dp.toPx() },
                        alignment = PopupPositionProvider.Align.End,
                        onDismissRequest = ::close,
                        onDismissFinished = { finish(onPick) },
                        maxHeight = 440.dp,
                        backgroundColor = color,
                    ) {
                        val colors = DropdownDefaults.dropdownColors()
                        ListPopupColumn {
                            entry.items.forEachIndexed { index, item ->
                                DropdownImpl(item, entry.items.size, item.selected, index, dropdownColors = colors) {
                                    item.onClick?.invoke()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    fun close() { showing = false }

    private fun finish(onPick: (Int) -> Unit) {
        val choice = picked
        picked = null
        onDismiss()
        choice?.let(onPick)
    }
}
