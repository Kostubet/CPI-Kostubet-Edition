package com.cosmod.launcherglass.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.util.GlassLogger
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streams the running system packages into the same archive as the report. */
internal object DiagnosticsArchive {
    private val launchers=listOf("com.android.launcher","com.oppo.launcher","com.oplus.launcher",
        "com.coloros.launcher","com.android.launcher3")

    fun create(context:Context,prefs:SharedPreferences):File {
        val now=Date()
        val timestamp=SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS",Locale.US).format(now)
        val directory=File(context.cacheDir,"diagnostics")
        check(directory.isDirectory || directory.mkdirs()) {"Could not open diagnostics directory"}
        val result=File(directory,"CherryPick_logs_$timestamp.zip")
        val pending=File.createTempFile("CherryPick_logs_",".part",directory)
        try {
            val report=buildString {
                appendLine("${context.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine(now)
                appendLine("\nSettings:")
                prefs.all.toSortedMap().forEach {(key,value)->
                    if(!key.startsWith("saved_preset_"))appendLine("$key=$value")
                }
                appendLine("\nLogs:")
                append(GlassLogger.readLogs(context))
            }
            ZipOutputStream(pending.outputStream().buffered()).use {zip->
                zip.text("CherryPick_logs_$timestamp.txt",report)
                val manager=context.packageManager
                val home=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val default=manager.resolveActivity(home,PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo?.applicationInfo
                val launcher=default?.takeIf {it.packageName in launchers} ?:
                    launchers.firstNotNullOfOrNull {application(manager,it)} ?:
                    manager.queryIntentActivities(home,PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
                        .firstOrNull {it.activityInfo.packageName!="android"}?.activityInfo?.applicationInfo
                zip.setLevel(Deflater.NO_COMPRESSION)
                val status=buildString {
                    appendLine(zip.addPackage("SystemLauncher",launcher))
                    appendLine(zip.addPackage("SystemUI",application(manager,"com.android.systemui")))
                }
                zip.text("packages.txt",status)
            }
            check(pending.renameTo(result)) {"Could not finish diagnostics archive"}
            val cutoff=now.time-24*60*60*1000L
            directory.listFiles {file->file.name.startsWith("CherryPick_logs_") && file.extension=="zip"}?.forEach {
                if(it.lastModified()<cutoff)it.delete()
            }
            return result
        }finally{
            pending.delete()
        }
    }

    private fun application(manager:PackageManager,name:String):ApplicationInfo? =
        try {manager.getApplicationInfo(name,0)}catch(_:PackageManager.NameNotFoundException){null}

    private fun ZipOutputStream.addPackage(label:String,info:ApplicationInfo?):String {
        if(info==null)return "$label: package not found"
        val status=mutableListOf("$label: ${info.packageName}")
        status.add(addApk("$label.apk",info.sourceDir))
        info.splitSourceDirs.orEmpty().forEachIndexed {index,path->
            status.add(addApk("$label/split-${index+1}.apk",path))
        }
        return status.joinToString("\n")
    }

    private fun ZipOutputStream.addApk(entry:String,path:String?):String {
        if(path.isNullOrBlank())return "$entry: source path unavailable"
        val input=try {File(path).inputStream()}catch(_:FileNotFoundException){return "$entry: file unavailable"}
            catch(_:SecurityException){return "$entry: access denied"}
        input.use {
            putNextEntry(ZipEntry(entry))
            it.copyTo(this,64*1024)
            closeEntry()
        }
        return "$entry: included"
    }

    private fun ZipOutputStream.text(name:String,text:String){
        putNextEntry(ZipEntry(name))
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }
}
