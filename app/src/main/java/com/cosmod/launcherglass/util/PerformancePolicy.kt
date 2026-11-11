package com.cosmod.launcherglass.util

import android.app.ActivityManager
import android.content.*
import android.hardware.display.DisplayManager
import android.os.*
import android.view.Display
import androidx.core.content.ContextCompat

/** Limits background sampling, leaving native geometry/touch animations at display rate. */
object PerformancePolicy {
    private data class Settings(val profile:Int=0,val resolution:Int=100,val alertRate:Int=0,
        val lightDrawer:Boolean=false,val transitionGlass:Boolean=false,val adaptiveAlerts:Boolean=true,
        val highRefreshPacing:Boolean=false)
    @Volatile private var settings=Settings()
    @Volatile private var saver=false
    @Volatile private var thermal=0
    @Volatile private var small=false
    private var bound=false
    private var displayManager:DisplayManager?=null
    private var watchingDisplay=false
    @Volatile private var displayHz=0f
    val captureRefreshHz get()=displayHz
    private val displayListener=object:DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId:Int){if(displayId==Display.DEFAULT_DISPLAY)readRefreshRate()}
        override fun onDisplayRemoved(displayId:Int){if(displayId==Display.DEFAULT_DISPLAY)displayHz=0f}
        override fun onDisplayChanged(displayId:Int){if(displayId==Display.DEFAULT_DISPLAY)readRefreshRate()}
    }

    private fun readRefreshRate(){
        val rate=displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        displayHz=rate.takeIf {it in 59f..240f} ?: 0f
    }

    private fun updateDisplayTracking(context:Context,liveFps:Int){
        val track=settings.highRefreshPacing || settings.alertRate==4 || liveFps>60
        if(watchingDisplay==track)return
        if(track){
            val manager=context.getSystemService(DisplayManager::class.java) ?: return
            displayManager=manager
            manager.registerDisplayListener(displayListener,Handler(Looper.getMainLooper()))
            watchingDisplay=true
            readRefreshRate()
        }else{
            displayManager?.unregisterDisplayListener(displayListener)
            displayManager=null
            watchingDisplay=false
            displayHz=0f
        }
    }
    private data class CaptureBudget(val economy:Boolean,val source:Int,val dock:Int,val alert:Int,val drawer:Boolean)
    private fun budget()=CaptureBudget(economy,sourceHeight,dockHeight,alertWidth,lightDrawer)
    val economy get()=settings.profile==2 || saver || thermal>=PowerManager.THERMAL_STATUS_SEVERE || (settings.profile==0 && small)
    private val balanced get()=settings.profile==3 || (settings.profile==0 && thermal>=PowerManager.THERMAL_STATUS_MODERATE)
    private val quality get()=settings.profile==1 && !economy
    val lightDrawer get()=settings.lightDrawer || economy
    val transitionGlass get()=settings.transitionGlass && !economy && thermal<PowerManager.THERMAL_STATUS_MODERATE
    val adaptiveAlerts get()=settings.adaptiveAlerts
    val sourceHeight get()=(when { economy->720; balanced->1000; quality->1800; else->1080 })*settings.resolution/100
    val dockHeight get()=(when { economy->480; balanced->600; quality->960; else->720 })*settings.resolution/100
    val alertWidth get()=(when { economy->384; balanced->448; quality->640; else->480 })*settings.resolution/100
    val alertFps:Int get() {
        val limit=when {
            economy->30
            balanced->45
            quality || settings.alertRate==4->120
            settings.alertRate==3->60
            else->45
        }
        return minOf(limit,when(settings.alertRate){1->30;2->45;3->60;else->120},refreshLimit())
    }
    val sensorMicros get()=if(economy)40000 else 20000
    private fun refreshLimit()=if(displayHz>0f)kotlin.math.round(displayHz).toInt().coerceAtLeast(1) else 120
    fun fps(requested:Int)=minOf(requested,when {economy->30;balanced->45;else->120},refreshLimit()).coerceAtLeast(1)
    fun describe()="profile=${settings.profile}, economy=$economy, saver=$saver, thermal=$thermal, sourceHeight=$sourceHeight, alert=${alertWidth}px/$alertFps FPS, lightDrawer=$lightDrawer"
    fun update(context:Context,values:Bundle):Boolean {
        val previous=budget()
        settings=Settings(values.getInt("performance_profile",0).coerceIn(0,3),
            values.getInt("performance_resolution",100).coerceIn(50,100),
            values.getInt("performance_alert_rate",0).coerceIn(0,4),
            values.getBoolean("performance_light_drawer",false),values.getBoolean("performance_live_transition_glass",false),
            values.getBoolean("performance_adaptive_alerts",true),
            values.getBoolean("performance_high_refresh_pacing",false))
        updateDisplayTracking(context,values.getInt(com.cosmod.launcherglass.ui.SettingsProvider.KEY_LIVE_FPS,60))
        if(!bound){
            bound=true
            val c=context.applicationContext ?: context
            val power=c.getSystemService(Context.POWER_SERVICE) as? PowerManager
            saver=power?.isPowerSaveMode ?: false;thermal=power?.currentThermalStatus ?: 0
            val manager=c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val info=ActivityManager.MemoryInfo();manager?.getMemoryInfo(info)
            small=manager?.isLowRamDevice==true || info.totalMem in 1..(6L*1024*1024*1024)
            ContextCompat.registerReceiver(c,object:BroadcastReceiver(){
                override fun onReceive(context:Context?,intent:Intent?){
                    val old=budget()
                    saver=power?.isPowerSaveMode ?: false
                    refresh(old)
                }
            },IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED)
            power?.addThermalStatusListener(c.mainExecutor){next->
                if(thermal!=next){
                    val old=budget()
                    thermal=next
                    refresh(old)
                }
            }
        }
        val next=budget()
        if(WallpaperHelper.appContext!=null && previous.economy!=next.economy)
            com.cosmod.launcherglass.xposed.IconMaterialRenderer.updateCacheBudget(next.economy)
        return previous!=next
    }
    private fun refresh(previous:CaptureBudget){
        val next=budget()
        if(previous==next)return
        if(WallpaperHelper.appContext!=null){
            if(previous.economy!=next.economy)
                com.cosmod.launcherglass.xposed.IconMaterialRenderer.updateCacheBudget(next.economy)
            WallpaperHelper.invalidate()
            com.cosmod.launcherglass.xposed.IconGlassHook.invalidateShaders()
            com.cosmod.launcherglass.xposed.IconGlassHook.invalidateActiveIcons()
        }
    }
}
