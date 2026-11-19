package com.cosmod.launcherglass.util

import android.content.*
import android.os.*
import com.cosmod.launcherglass.BuildConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Signature-protected commands handled by the injected host, without su. */
object HostControl {
    const val RESTART_LAUNCHER=com.cosmod.launcherglass.BuildConfig.APPLICATION_ID+".RESTART_LAUNCHER"
    const val RESTART_SIDEBAR=BuildConfig.APPLICATION_ID+".RESTART_SIDEBAR"
    const val DIAGNOSTICS=com.cosmod.launcherglass.BuildConfig.APPLICATION_ID+".HOST_DIAGNOSTICS"
    const val RETRY_ALERTS=com.cosmod.launcherglass.BuildConfig.APPLICATION_ID+".RETRY_ALERTS"
    const val PREVIEW_URI = "wallpaper_uri"
    const val PREVIEW_WALLPAPER = BuildConfig.APPLICATION_ID + ".PREVIEW_WALLPAPER"
    const val PREVIEW_WALLPAPER_LIVE = "live_wallpaper"
    const val PREVIEW_ICONS = BuildConfig.APPLICATION_ID + ".PREVIEW_ICONS"
    const val PREVIEW_WIDGET=BuildConfig.APPLICATION_ID+".PREVIEW_WIDGET"
    const val PREVIEW_FOLDER=BuildConfig.APPLICATION_ID+".PREVIEW_FOLDER"
    const val PREVIEW_SURFACE=BuildConfig.APPLICATION_ID+".PREVIEW_SURFACE"
    const val PREVIEW_WIDGET_URI="widget_uri"
    const val PREVIEW_WIDGET_KIND="widget_kind"
    const val PREVIEW_APPS = "preview_apps"
    const val PREVIEW_ICONS_URI = "icons_uri"
    private val installed=HashSet<String>()
    fun install(context:Context,role:String,details:()->String={""},retry:(()->Unit)?=null){
        if(!installed.add(role))return
        val main=Handler(Looper.getMainLooper())
        val filter=IntentFilter(DIAGNOSTICS).apply {
            if(role=="launcher") {
                addAction(RESTART_LAUNCHER)
                addAction(PREVIEW_WALLPAPER)
                addAction(PREVIEW_ICONS)
                addAction(PREVIEW_WIDGET)
                addAction(PREVIEW_FOLDER)
            }
            if(role=="sidebar")addAction(RESTART_SIDEBAR)
            if(retry!=null)addAction(RETRY_ALERTS)
            addAction(PREVIEW_SURFACE)
        }
        androidx.core.content.ContextCompat.registerReceiver(context,object:BroadcastReceiver(){
            override fun onReceive(c:Context?,intent:Intent?){
                try {
                    when(intent?.action){
                        DIAGNOSTICS->{resultCode=SystemUiControl.ACCEPTED;resultData="${BuildConfig.VERSION_NAME} build=${BuildConfig.VERSION_CODE}; $role PID=${Process.myPid()}\n${details()}\n${GlassLogger.snapshot()}"}
                        RESTART_LAUNCHER->if(role=="launcher"){
                            resultCode=SystemUiControl.ACCEPTED
                            main.postDelayed({Process.killProcess(Process.myPid())},400L)
                        }
                        RESTART_SIDEBAR->if(role=="sidebar"){
                            resultCode=SystemUiControl.ACCEPTED
                            main.postDelayed({Process.killProcess(Process.myPid())},400L)
                        }
                        PREVIEW_WALLPAPER -> if (role == "launcher") {
                            val uri = intent.getStringExtra(PREVIEW_URI)?.let(android.net.Uri::parse)?.takeIf {
                                it.scheme == "content" && it.authority == BuildConfig.APPLICATION_ID + ".logs" &&
                                    it.pathSegments.firstOrNull() == "preview-wallpaper"
                            } ?: return
                            val live = intent.getBooleanExtra(PREVIEW_WALLPAPER_LIVE, false)
                            val reply = goAsync()
                            Thread({
                                try {
                                    if (PreviewWallpaper.writeFromLauncher(context, uri, live)) {
                                        reply.resultCode = SystemUiControl.ACCEPTED
                                    }
                                } finally {
                                    reply.finish()
                                }
                            }, "CPIWallpaperPreview").start()
                        }
                        PREVIEW_WIDGET,PREVIEW_FOLDER,PREVIEW_SURFACE -> {
                            val uri=intent.getStringExtra(PREVIEW_WIDGET_URI)?.let(android.net.Uri::parse)?.takeIf {
                                it.scheme=="content" && it.authority==BuildConfig.APPLICATION_ID+".logs" &&
                                    it.pathSegments.firstOrNull()=="preview-widgets"
                            } ?: return
                            val id=intent.getIntExtra(PREVIEW_WIDGET_KIND,-1)
                            val scene=when(intent.action){
                                PREVIEW_SURFACE -> {
                                    val kind=SurfacePreviewKind.entries.getOrNull(id)?.takeIf {it.role==role} ?: return
                                    com.cosmod.launcherglass.xposed.NativeMaterialPreview.snapshot(kind)
                                }
                                PREVIEW_FOLDER -> {
                                    if(role!="launcher")return
                                    val kind=FolderPreviewKind.entries.getOrNull(id) ?: return
                                    com.cosmod.launcherglass.xposed.FolderMaterialHook.snapshotPreview(kind)
                                }
                                else -> {
                                    if(role!="launcher")return
                                    val kind=WidgetPreviewKind.entries.getOrNull(id) ?: return
                                    com.cosmod.launcherglass.xposed.PhotoWidgetMaterial.snapshotPreview(kind)
                                }
                            } ?: return
                            val reply=goAsync()
                            Thread({
                                try {if(NativeWidgetPreviews.write(context,uri,id,scene))reply.resultCode=SystemUiControl.ACCEPTED}
                                finally {scene.content.recycle();scene.text?.recycle();scene.logos?.recycle();scene.shape?.recycle();reply.finish()}
                            },"CPIWidgetPreview").start()
                        }
                        PREVIEW_ICONS -> if (role == "launcher") {
                            val uri = intent.getStringExtra(PREVIEW_ICONS_URI)?.let(android.net.Uri::parse)?.takeIf {
                                it.scheme == "content" && it.authority == BuildConfig.APPLICATION_ID + ".logs" &&
                                    it.pathSegments.firstOrNull() == "preview-icons"
                            } ?: return
                            com.cosmod.launcherglass.xposed.PrefsHelper.loadPrefs(context)
                            val icons = com.cosmod.launcherglass.xposed.IconGlassHook.snapshotPreviewIcons(intent.getStringArrayListExtra(PREVIEW_APPS).orEmpty().take(3))
                            if (icons.isEmpty()) return
                            val reply = goAsync()
                            Thread({
                                try {
                                    if (PreviewIcons.write(context, uri, icons)) reply.resultCode = SystemUiControl.ACCEPTED
                                } finally {
                                    icons.forEach {
                                        it.source.recycle()
                                        it.layers.glyphBitmap.recycle()
                                        it.layers.plateMaskBitmap.recycle()
                                    }
                                    reply.finish()
                                }
                            }, "CPIIconPreview").start()
                        }
                        RETRY_ALERTS->{retry?.invoke();resultCode=SystemUiControl.ACCEPTED;resultData=details()}
                    }
                }catch(t:Throwable){resultData="Host command failed: $t"}
            }
        },filter,SystemUiControl.PERMISSION,main,androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }
    fun request(context:Context,pkg:String,action:String):Pair<Int,String> =
        request(context, Intent(action).setPackage(pkg))

    fun request(context: Context, intent: Intent, timeoutMs: Long = 1800): Pair<Int, String> {
        if(Looper.myLooper()==Looper.getMainLooper())return 0 to "Request must run on a worker"
        val done=CountDownLatch(1);var code=0;var text="Host receiver unavailable"
        try {
            context.sendOrderedBroadcast(intent,null,object:BroadcastReceiver(){
                override fun onReceive(c:Context?,intent:Intent?){code=resultCode;text=resultData ?: "";done.countDown()}
            },Handler(Looper.getMainLooper()),0,null,null)
            if(!done.await(timeoutMs,TimeUnit.MILLISECONDS))return 0 to "Host reply timed out"
        }catch(e:Exception){return 0 to e.toString()}
        return code to text
    }
    fun restartLauncher(context:Context):Boolean {
        val home=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val pkg=context.packageManager.resolveActivity(home,0)?.activityInfo?.packageName ?: "com.android.launcher"
        if(request(context,pkg,RESTART_LAUNCHER).first!=SystemUiControl.ACCEPTED)return false
        Handler(Looper.getMainLooper()).postDelayed({
            try {context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){}
        },650L)
        return true
    }
}
