package com.cosmod.launcherglass.util

import android.util.Log
import de.robv.android.xposed.XposedBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GlassLogger {

    const val TAG = "ColorOSGlass"
    private val recent=java.util.ArrayDeque<String>()
    private val failures=java.util.ArrayDeque<String>()
    private fun retain(message:String,error:Boolean=false)=synchronized(recent){
        val list=if(error)failures else recent
        list.addLast(message.take(5000))
        while(list.size>(if(error)12 else 160))list.removeFirst()
    }
    fun snapshot():String=synchronized(recent){
        "Errors:\n"+failures.joinToString("\n").takeLast(20000)+"\nRecent module events:\n"+recent.joinToString("\n").takeLast(24000)
    }
    private val dateFormat = object:ThreadLocal<SimpleDateFormat>() {
        override fun initialValue()=SimpleDateFormat("HH:mm:ss.SSS",Locale.US)
    }

    private val bridgeTimes=HashMap<String,Long>()
    fun i(component: String, message: String) {
        val time = dateFormat.get()!!.format(Date())
        val formatted = "[$time] [$component] $message"
        retain(formatted)

        // Use info-level messages so diagnostics remain visible with debug logging filtered.
        Log.i(TAG, formatted)

        // Keep full in-memory/logcat diagnostics; avoid synchronous Xposed file I/O in animations.
        val now=android.os.SystemClock.uptimeMillis()
        val bridge=synchronized(bridgeTimes){
            if(now-(bridgeTimes[component] ?: -1000L)>=1000L){bridgeTimes[component]=now;true}else false
        }
        if(!bridge)return
        try {
            XposedBridge.log("$TAG: $formatted")
        } catch (_: Throwable) {}
    }

    private val counters = HashMap<String, Int>()

    /**
     * Hot-path diagnostics: the first few occurrences, then one aggregated
     * line per [every] events. XposedBridge.log is synchronous file I/O and
     * must not run on every animation frame.
     */
    fun limited(component: String, key: String, every: Int = 500, message: () -> String) {
        val count = synchronized(counters) { (counters[key] ?: 0).inc().also { counters[key] = it } }
        if (count <= 3 || count % every == 0) i(component, if (count <= 3) message() else "${message()} (x$count)")
    }

    fun d(component: String, message: String) {
        i(component, message)
    }

    fun e(component: String, message: String, throwable: Throwable? = null) {
        val errorMsg = if (throwable != null) "$message: ${throwable.message}\n${Log.getStackTraceString(throwable)}" else message
        val time = dateFormat.get()!!.format(Date())
        val formatted = "[$time] [$component] ERROR: $errorMsg"
        retain(formatted,true)
        Log.e(TAG, formatted)
        try {
            XposedBridge.log("$TAG: $formatted")
        } catch (_: Throwable) {}
    }

    fun readLogs(context: android.content.Context): String {
        return SuUtil.readLogcat(context)
    }

    fun clearLogs(): Boolean {
        return SuUtil.clearLogcat()
    }
}
