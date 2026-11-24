package com.cosmod.launcherglass.xposed

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import com.cosmod.launcherglass.util.GlassLogger
import com.cosmod.launcherglass.util.LiveBlurEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.xmlpull.v1.XmlPullParser
import java.util.WeakHashMap
import java.lang.ref.WeakReference

/** Adds an action using the launcher's own edit toolbar and button style. */
internal object DesktopAppearanceToolbar {
    private const val TOOLBAR = "com.android.launcher.togglebar.views.ToggleStateToolbar"
    private const val BUTTON = "com.android.launcher.views.PressFeedbackButton"
    private val wallpaperShades = WeakHashMap<Activity, WeakReference<View>>()
    private val appearanceButtonId by lazy { View.generateViewId() }

    fun updateWallpaperShade() {
        for ((host, shade) in wallpaperShades.toMap()) {
            if (!host.isDestroyed) setWallpaperShade(host, shade.get())
        }
    }

    private fun setWallpaperShade(host: Activity, existing: View?) {
        if (existing != null) {
            val show = PrefsHelper.desktopDimWallpaper
            existing.animate().cancel()
            if (show) existing.visibility = View.VISIBLE
            existing.animate().alpha(if (show) 1f else 0f).setDuration(235L)
                .withEndAction { if (!PrefsHelper.desktopDimWallpaper) existing.visibility = View.GONE }.start()
            return
        }
        wallpaperShades[host] = WeakReference(null)
        if (!PrefsHelper.desktopDimWallpaper) return
        val id = host.resources.getIdentifier("workspace", "id", host.packageName)
        val workspace = host.findViewById<View>(id) ?: return
        val layer = workspace.parent as? FrameLayout ?: return
        if (layer.id != host.resources.getIdentifier("drag_layer", "id", host.packageName)) return
        val shade = View(host).apply {
            setBackgroundColor(0x38000000)
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        layer.addView(shade, layer.indexOfChild(workspace), FrameLayout.LayoutParams(-1, -1))
        wallpaperShades[host] = WeakReference(shade)
        shade.animate().alpha(1f).setDuration(235L).start()
    }

    fun install(loader: ClassLoader) {
        runCatching {
            val toolbar = loader.loadClass(TOOLBAR)
            val button = loader.loadClass(BUTTON).getConstructor(Context::class.java, AttributeSet::class.java)
            val sheet = DesktopAppearancePanel.contract(loader)
            val editState by lazy {
                runCatching {
                    XposedHelpers.getStaticObjectField(
                        loader.loadClass("com.android.launcher3.LauncherState"), "TOGGLE_BAR",
                    )
                }.getOrNull()
            }
            val attach = runCatching { toolbar.getDeclaredMethod("onAttachedToWindow") }.getOrElse {
                toolbar.getDeclaredMethod("onFinishInflate")
            }
            // The editor can be inflated before the module preferences are ready.
            XposedBridge.hookMethod(attach, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    val view = param.thisObject as? ViewGroup ?: return
                    view.post {
                        if (!view.isAttachedToWindow) return@post
                        runCatching {
                            bind(view, { context, attrs -> button.newInstance(context, attrs) as TextView }, sheet, editState)
                        }.onFailure { report(it) }
                    }
                }
            })
            GlassLogger.i("DesktopAppearance", "ColorOS edit toolbar contract installed")
        }.onFailure { report(it) }
    }

    private fun bind(
        toolbar: ViewGroup,
        makeButton: (Context, AttributeSet) -> TextView,
        sheet: DesktopAppearancePanel.Contract,
        editState: Any?,
    ) {
        if (toolbar.findViewById<View>(appearanceButtonId) != null) return
        val group = XposedHelpers.callMethod(toolbar, "getCancelButton") as? TextView ?: return
        val done = XposedHelpers.callMethod(toolbar, "getDoneButton") as? TextView ?: return
        val parent = done.parent as? ViewGroup ?: return
        if (group.parent !== parent || group.javaClass.name != BUTTON || done.javaClass.name != BUTTON) return
        val context = toolbar.context
        val resources = context.resources
        val groupText = resources.getIdentifier("edite_model_tidyup", "string", context.packageName)
        val doneText = resources.getIdentifier("toggle_bar_complete", "string", context.packageName)
        val layout = resources.getIdentifier("toggle_state_tool_bar", "layout", context.packageName)
        if (layout == 0) return
        val host = activity(context) ?: return
        val stateManager = runCatching { editState?.let { NativeIconAccess.call(host, "getStateManager") } }.getOrNull()
        val groupSelection = runCatching {
            stateManager?.let { NativeIconAccess.call(host, "getBatchDragViewManager") }
        }.getOrNull()
        val groupLabel = groupText.takeIf { it != 0 }?.let(resources::getString)
        val doneLabel = doneText.takeIf { it != 0 }?.let(resources::getString)
        // Use the actual button style from the matching resource. Creating the
        // class without attributes selects an unrelated stock button variant.
        val copy = resources.getLayout(layout).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == BUTTON &&
                    parser.getAttributeResourceValue("http://schemas.android.com/apk/res/android", "id", 0) == done.id) {
                    return@use makeButton(context, parser)
                }
            }
            null
        } ?: return
        val doneLayout = done.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val params = doneLayout.javaClass.getConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .newInstance(ViewGroup.LayoutParams.WRAP_CONTENT, doneLayout.height) as ViewGroup.MarginLayoutParams
        params.topMargin = doneLayout.topMargin
        params.bottomMargin = doneLayout.bottomMargin
        XposedHelpers.setIntField(params, "startToEnd", group.id)
        XposedHelpers.setIntField(params, "endToStart", done.id)
        XposedHelpers.setIntField(params, "topToTop", 0)
        XposedHelpers.setIntField(params, "bottomToBottom", 0)
        params.marginStart = dp(context, 6)
        params.marginEnd = dp(context, 6)

        val title = DesktopAppearanceResources(context).text("title")
        copy.apply {
            id = appearanceButtonId
            text = title
            contentDescription = title
            setTextSize(TypedValue.COMPLEX_UNIT_PX, done.textSize)
            typeface = done.typeface
            gravity = done.gravity
            includeFontPadding = done.includeFontPadding
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(dp(context, 12), done.paddingTop, dp(context, 12), done.paddingBottom)
            minHeight = done.minHeight
            visibility = View.INVISIBLE
        }
        var dialog: Dialog? = null
        var opening = false
        fun editorVisible(): Boolean {
            if (!done.isShown) return false
            val state = runCatching { stateManager?.let { NativeIconAccess.call(it, "getState") } }.getOrNull()
            if (state != null) {
                if (state !== editState) return false
                val selected = runCatching {
                    groupSelection?.let { NativeIconAccess.call(it, "getSelectedViewCount") } as? Number
                }.getOrNull()
                return (selected?.toInt() ?: 0) == 0
            }
            return group.isShown && group.text.toString() == groupLabel && done.text.toString() == doneLabel
        }
        var confirmedVisible = false
        val listener = ViewTreeObserver.OnPreDrawListener {
            val available = PrefsHelper.enabled && editorVisible()
            val visibility = if (available) View.VISIBLE else View.INVISIBLE
            if (copy.visibility != visibility) copy.visibility = visibility
            val alpha = if (available) done.alpha else 0f
            if (copy.alpha != alpha) copy.alpha = alpha
            if (copy.currentTextColor != done.currentTextColor) copy.setTextColor(done.textColors)
            if (copy.isEnabled != (alpha > .6f)) copy.isEnabled = alpha > .6f
            if (copy.isEnabled && !confirmedVisible) {
                confirmedVisible = true
                GlassLogger.i("DesktopAppearance", "Appearance action visible in the stock editor")
            }
            true
        }
        copy.setOnClickListener {
            if (!PrefsHelper.enabled || !copy.isEnabled || opening || dialog?.isShowing == true) return@setOnClickListener
            if (host.isFinishing || host.isDestroyed) return@setOnClickListener
            opening = true
            val editorWasTracked = LiveBlurEngine.isEditing
            val workspace = NativeIconAccess.call(host, "getWorkspace")
            val deadline = SystemClock.uptimeMillis() + 1200L
            var lastClick = 0L
            val owner = host.window.decorView
            val closeEditor = object : Runnable {
                override fun run() {
                    if (host.isFinishing || host.isDestroyed || !owner.isAttachedToWindow || !PrefsHelper.enabled) {
                        opening = false
                        return
                    }
                    val now = SystemClock.uptimeMillis()
                    if (!editorVisible() || (editorWasTracked && !LiveBlurEngine.isEditing)) {
                        opening = false
                        runCatching { dialog = DesktopAppearancePanel.open(host, sheet) }.onFailure { report(it) }
                        return
                    }
                    if (now >= deadline) {
                        opening = false
                        GlassLogger.limited("DesktopAppearance", "editorBusy") { "Launcher editor did not accept Done" }
                        return
                    }
                    val moving = workspace?.let { NativeIconAccess.call(it, "isSwitchingState") } == true
                    if (!moving && now - lastClick > 100L) {
                        done.performClick()
                        lastClick = now
                    }
                    owner.postDelayed(this, 32L)
                }
            }
            closeEditor.run()
        }
        copy.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.viewTreeObserver.addOnPreDrawListener(listener)
                view.post {
                    if (view.isAttachedToWindow) activity(view.context)?.let { host ->
                        setWallpaperShade(host, wallpaperShades[host]?.get())
                    }
                }
            }
            override fun onViewDetachedFromWindow(view: View) {
                view.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        })
        parent.addView(copy, parent.indexOfChild(done), params)
        GlassLogger.i("DesktopAppearance", "Added appearance action to the stock edit toolbar")
    }

    private tailrec fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> activity(context.baseContext)
        else -> null
    }

    internal fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()

    private fun report(error: Throwable) = GlassLogger.limited("DesktopAppearance", "toolbar") {
        "Optional appearance UI unavailable: ${error.message ?: error.javaClass.simpleName}"
    }
}
