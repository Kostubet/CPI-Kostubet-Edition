package com.cosmod.launcherglass.xposed

import android.animation.ValueAnimator
import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd
import com.cosmod.launcherglass.ui.DesktopAppearance
import com.cosmod.launcherglass.util.LiveBlurEngine
import kotlin.math.roundToInt

/** Hold one frame of the visible items while the launcher installs their next material. */
internal object DesktopAppearanceTransition {
    private val main = Handler(Looper.getMainLooper())
    private val curve = PathInterpolator(.25f, .10f, .22f, 1f)
    private val visualKeys = setOf(
        DesktopAppearance.MODE, DesktopAppearance.THEME, DesktopAppearance.COLOR_SOURCE,
        DesktopAppearance.HUE, DesktopAppearance.SATURATION, DesktopAppearance.TINT_TRANSPARENCY,
        DesktopAppearance.DARK_LOGO_HUE, DesktopAppearance.DARK_LOGO_SATURATION,
    )
    private var active: Frame? = null
    private var copyPending = false

    fun beforeChange(host: Activity, change: Bundle, apply: () -> Unit) {
        val root = host.window.peekDecorView() as? ViewGroup
        if (root == null || !root.isShown || !root.isAttachedToWindow || !root.isHardwareAccelerated ||
            root.width <= 0 || root.height <= 0 || !ValueAnimator.areAnimatorsEnabled() ||
            copyPending || LiveBlurEngine.isWorkspaceScrolling || change.keySet().none { it in visualKeys }) {
            apply()
            return
        }
        val viewport = Rect(0, 0, root.width, root.height)
        active?.let { frame ->
            if (frame.canReuse(root, viewport)) {
                frame.holdFor(change)
                apply()
                return
            }
            frame.remove()
        }
        val clip = itemBounds(root, viewport)
        val area = Rect().also { crop ->
            RectF().also { clip.computeBounds(it, true) }.roundOut(crop)
        }
        if (area.isEmpty) {
            apply()
            return
        }
        val scale = minOf(1f, 800f / viewport.width(), 1560f / viewport.height())
        val bitmap = runCatching {
            Bitmap.createBitmap((area.width() * scale).roundToInt().coerceAtLeast(1),
                (area.height() * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            apply()
            return
        }
        var waiting = true
        val timeout = Runnable {
            waiting = false
            apply()
        }
        @Suppress("DEPRECATION")
        try {
            copyPending = true
            PixelCopy.request(host.window, Rect(area), bitmap, { result ->
                copyPending = false
                if (!waiting) {
                    bitmap.recycle()
                } else {
                    main.removeCallbacks(timeout)
                    waiting = false
                    if (result == PixelCopy.SUCCESS && root.isShown && root.isAttachedToWindow &&
                        root.width == viewport.width() && root.height == viewport.height()) {
                        val frame = Frame(root, bitmap, area, viewport, clip)
                        active = frame
                        frame.show()
                        frame.holdFor(change)
                    } else bitmap.recycle()
                    apply()
                }
            }, main)
            main.postDelayed(timeout, 65L)
        } catch (_: IllegalArgumentException) {
            copyPending = false
            bitmap.recycle()
            apply()
        }
    }

    private fun itemBounds(root: ViewGroup, area: Rect): Path {
        val tracked = IconGlassHook.registeredViews().mapNotNull { it.get() }.toHashSet()
        tracked.addAll(PhotoWidgetMaterial.appearanceHosts())
        val result = Path()
        val visible = Rect()
        fun collect(view: View) {
            if (!view.isShown || view.alpha <= .01f || !view.getGlobalVisibleRect(visible) ||
                !visible.intersect(area)) return
            val name = view.javaClass.name
            if (view in tracked || view is AppWidgetHostView ||
                name.startsWith("com.android.launcher3.") &&
                (name.endsWith("BubbleTextView") || name.endsWith("FolderIcon"))) {
                result.addRect(visible.left.toFloat(), visible.top.toFloat(),
                    visible.right.toFloat(), visible.bottom.toFloat(), Path.Direction.CW)
            } else if (view is ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(root)
        return result
    }

    fun onSettingsApplied(stored: Bundle) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { onSettingsApplied(stored) }
            return
        }
        val frame = active ?: return
        if (frame.matches(stored)) frame.fade()
    }

    private class Frame(
        private val root: ViewGroup, private val bitmap: Bitmap, area: Rect,
        private val viewport: Rect, private val clip: Path,
    ) : Drawable(), View.OnAttachStateChangeListener {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val deadline = Runnable { fade() }
        private var animation: ValueAnimator? = null
        private var expected = Bundle()

        init { bounds = area }

        fun show() {
            root.overlay.add(this)
            root.addOnAttachStateChangeListener(this)
        }

        fun canReuse(view: ViewGroup, size: Rect) = root === view && viewport == size

        fun holdFor(change: Bundle) {
            // Preserve the currently visible blend and avoid copying a frame that still contains this overlay.
            stopFade()
            expected = change
            main.removeCallbacks(deadline)
            main.postDelayed(deadline, 240L)
        }

        fun matches(stored: Bundle) = expected.keySet().all { key ->
            expected.getInt(key) == stored.getInt(key, DesktopAppearance.defaults.getValue(key))
        }

        fun fade() {
            if (active !== this || animation != null) return
            main.removeCallbacks(deadline)
            animation = ValueAnimator.ofInt(paint.alpha, 0).apply {
                duration = 285L
                interpolator = curve
                addUpdateListener { alpha = it.animatedValue as Int }
                doOnEnd { remove() }
                start()
            }
        }

        fun remove() {
            if (active === this) active = null
            main.removeCallbacks(deadline)
            stopFade()
            root.overlay.remove(this)
            root.removeOnAttachStateChangeListener(this)
        }

        private fun stopFade() {
            animation?.removeAllListeners()
            animation?.cancel()
            animation = null
        }

        override fun draw(canvas: Canvas) {
            if (LiveBlurEngine.isWorkspaceScrolling || root.width != viewport.width() || root.height != viewport.height()) return
            val save = canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(bitmap, null, bounds, paint)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = remove()
    }
}
