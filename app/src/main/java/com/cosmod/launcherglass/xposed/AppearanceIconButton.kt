package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.IntSize
import com.cosmod.launcherglass.ui.onextbox.ColorOsTopBarMaterialView
import com.cosmod.launcherglass.ui.onextbox.ColorOsTopBarMotion
import com.cosmod.launcherglass.ui.onextbox.ColorOsTopBarSpotlightView

/** View host for the same COUI toolbar material, spring and spotlight used by CPI's top bars. */
internal class AppearanceIconButton(
    context: Context, dark: Boolean, icon: Drawable?, tint: ColorStateList, filled: Boolean,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val visual = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val light = ColorOsTopBarSpotlightView(context, dark)
    private val glyph = ImageView(context).apply {
        setImageDrawable(icon)
        imageTintList = tint
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val motion = ColorOsTopBarMotion(density, ::updatePose)
    private var tracker: VelocityTracker? = null
    private var down = Offset.Zero
    private var dragging = false

    init {
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        if (filled) {
            visual.addView(ColorOsTopBarMaterialView(context, dark, Color.TRANSPARENT,
                nativeBlurEnabled = false, overMaterial = true), LayoutParams(-1, -1))
        }
        val glyphSize = (22 * density + .5f).toInt()
        visual.addView(glyph, LayoutParams(glyphSize, glyphSize, Gravity.CENTER))
        visual.addView(light, LayoutParams(-1, -1))
        val diameter = (40 * density + .5f).toInt()
        addView(visual, LayoutParams(diameter, diameter, Gravity.CENTER))
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        glyph.isSelected = selected
    }

    private fun updatePose() {
        visual.translationX = motion.translationX
        visual.translationY = motion.translationY
        visual.scaleX = motion.scaleX
        visual.scaleY = motion.scaleY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || !ValueAnimator.areAnimatorsEnabled()) return super.onTouchEvent(event)
        val size = IntSize(visual.width, visual.height)
        val position = Offset(event.x - visual.left, event.y - visual.top)
        val point = motion.localPosition(position, size)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                parent?.requestDisallowInterceptTouchEvent(true)
                down = position
                dragging = false
                motion.begin()
                light.spotlightDown(point.x, point.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = position - down
                if (!dragging && delta.getDistance() > slop) {
                    dragging = true
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try { super.onTouchEvent(cancel) } finally { cancel.recycle() }
                }
                motion.move(delta, size)
                light.spotlightMove(point.x, point.y)
            }
        }
        tracker?.addMovement(event)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            tracker?.computeCurrentVelocity(1000)
            val velocity = if (event.actionMasked == MotionEvent.ACTION_UP) {
                Offset(tracker?.xVelocity ?: 0f, tracker?.yVelocity ?: 0f)
            } else Offset.Zero
            motion.release(size, velocity)
            light.spotlightUp(point.x, point.y)
            tracker?.recycle()
            tracker = null
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return if (dragging) true else super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        tracker?.recycle()
        tracker = null
        motion.dispose()
        light.cancelSpotlight()
        super.onDetachedFromWindow()
    }
}

/** Draw the COUI library's filled symbols in the launcher View host. */
internal fun ImageVector.asLauncherGlyph(context: Context): Drawable {
    val size = (24 * context.resources.displayMetrics.density + .5f).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.scale(size / viewportWidth, size / viewportHeight)
    fun draw(group: VectorGroup) {
        val count = canvas.save()
        canvas.translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
        canvas.rotate(group.rotation)
        canvas.scale(group.scaleX, group.scaleY)
        canvas.translate(-group.pivotX, -group.pivotY)
        for (item in group) when (item) {
            is VectorGroup -> draw(item)
            is VectorPath -> if (item.fill != null) {
                val path = PathParser().addPathNodes(item.pathData).toPath().asAndroidPath()
                path.fillType = if (item.pathFillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                paint.alpha = (item.fillAlpha * 255).toInt()
                canvas.drawPath(path, paint)
            }
        }
        canvas.restoreToCount(count)
    }
    draw(root)
    return BitmapDrawable(context.resources, bitmap)
}
