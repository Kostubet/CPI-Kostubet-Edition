package com.cosmod.launcherglass.update

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.CouiAction
import com.cosmod.launcherglass.ui.CouiGroup
import com.cosmod.launcherglass.ui.SettingsProvider
import com.cosmod.launcherglass.ui.UiLanguage
import io.github.suqi8.coui.kmp.basic.Scaffold
import io.github.suqi8.coui.kmp.basic.CardListGroup
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.LinearProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TopAppBar
import io.github.suqi8.coui.kmp.preference.ArrowPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.darkColorScheme
import io.github.suqi8.coui.kmp.theme.lightColorScheme

class CpiUpdateActivity : AppCompatActivity() {
    private var release by mutableStateOf<CpiReleases.Release?>(null)
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var downloadProgress by mutableStateOf<Float?>(null)
    private var alerts by mutableStateOf(false)
    private var alertsBlocked by mutableStateOf(false)
    private var resumed = false

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshAlerts()
    }
    private val sources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) install()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { consumeInstallResult() }
    }

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(UiLanguage.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = when (getSharedPreferences(SettingsProvider.PREFS_NAME, MODE_PRIVATE).getInt("ui_theme", 0)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        super.onCreate(savedInstanceState)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val canvas = ContextCompat.getColor(this, R.color.cpi_ui_background)
        val accent = Color(ContextCompat.getColor(this, android.R.color.system_accent1_400))
        val panel = Color(ContextCompat.getColor(this, R.color.cpi_ui_panel))
        val colors = if (dark) darkColorScheme(primary = accent, surface = panel)
            else lightColorScheme(primary = accent, surface = panel)
        val icon = applicationInfo.loadIcon(packageManager).toBitmap(192, 192).asImageBitmap()
        val screen = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                COUITheme(colors = colors) {
                    val scrollBehavior = COUIScrollBehavior()
                    Scaffold(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        containerColor = Color(canvas),
                        topBar = {
                            TopAppBar(
                                title = getString(R.string.cpi_updates_title),
                                color = Color(canvas),
                                scrollBehavior = scrollBehavior,
                                showDivider = false,
                                navigationIcon = {
                                    IconButton(onClick = { onBackPressedDispatcher.onBackPressed() }) {
                                        Icon(painterResource(R.drawable.ic_arrow_back), getString(R.string.ui_back),
                                            tint = COUITheme.colorScheme.labelPrimary)
                                    }
                                },
                            )
                        },
                    ) { insets ->
                        Column(
                            Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)
                                .verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 12.dp),
                        ) {
                            CouiGroup {
                                Column(
                                    Modifier.fillMaxWidth().padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Image(icon, contentDescription = null, modifier = Modifier.size(64.dp))
                                    Text(status, style = COUITheme.textStyles.headlineS,
                                        color = COUITheme.colorScheme.labelPrimary, textAlign = TextAlign.Center)
                                    Text(getString(R.string.cpi_updates_current, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                                        style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary,
                                        textAlign = TextAlign.Center)
                                    if (busy) LinearProgressIndicator(progress = downloadProgress)
                                    if (release != null) {
                                        CouiAction(getString(R.string.cpi_updates_install), primary = true,
                                            onClick = ::install, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                                    } else {
                                        CouiAction(getString(R.string.cpi_updates_check), primary = false,
                                            onClick = ::check, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                                    }
                                }
                            }
                            CouiGroup {
                                CardListGroup {
                                    item {
                                        SwitchPreference(checked = alerts, onCheckedChange = ::toggleAlerts,
                                            title = getString(R.string.cpi_updates_notifications))
                                    }
                                    if (alertsBlocked) {
                                        item {
                                            ArrowPreference(title = getString(R.string.cpi_updates_notification_settings),
                                                summary = getString(R.string.cpi_updates_notifications_blocked),
                                                onClick = ::openNotificationSettings)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(screen)
        WindowCompat.getInsetsController(window, screen).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        check()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, receiver, IntentFilter(CpiUpdateInstaller.STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshAlerts()
        consumeInstallResult()
    }

    override fun onPause() { resumed = false; super.onPause() }

    override fun onStop() { unregisterReceiver(receiver); super.onStop() }

    private fun refreshAlerts() {
        CpiUpdateService.reconcile(this)
        val wasBlocked = alertsBlocked
        val wantsAlerts = CpiUpdateService.enabled(this)
        val allowed = CpiUpdateService.canNotify(this)
        alerts = wantsAlerts && allowed
        alertsBlocked = wantsAlerts && !allowed
        if (wasBlocked && alerts) checkForAlert()
    }

    private fun toggleAlerts(enabled: Boolean) {
        CpiUpdateService.setEnabled(this, enabled)
        refreshAlerts()
        if (!enabled) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!alerts) {
            openNotificationSettings()
        } else {
            checkForAlert()
        }
    }

    private fun openNotificationSettings() {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = if (manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CpiUpdateService.CHANNEL) != null) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CpiUpdateService.CHANNEL)
        } else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        startActivity(intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun checkForAlert() {
        val available = release
        if (available != null) CpiUpdateService.onCheckCompleted(this, available)
        else if (!busy) CpiUpdateService.checkForAlert(this, force = true)
    }

    private fun check() {
        busy = true
        downloadProgress = null
        status = getString(R.string.cpi_updates_checking)
        CpiUpdateInstaller.executor.execute {
            val result = runCatching { CpiReleases.latest(fresh = true)?.takeIf { it.build > BuildConfig.VERSION_CODE } }
            result.exceptionOrNull()?.let { android.util.Log.w("CPI.Updates", "Release check failed", it) }
            show {
                busy = false
                release = result.getOrNull()
                result.onSuccess { CpiUpdateService.onCheckCompleted(this, it) }
                status = release?.let { getString(R.string.cpi_updates_version, it.version, it.build) }
                    ?: getString(when {
                        result.isSuccess -> R.string.cpi_updates_current_latest
                        (result.exceptionOrNull() as? CpiReleases.HttpFailure)?.status in listOf(403, 429) -> R.string.cpi_updates_rate_limit
                        else -> R.string.cpi_updates_check_error
                    })
            }
        }
    }

    private fun install() {
        val update = release ?: return
        if (busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            runCatching { sources.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .onFailure { status = getString(R.string.cpi_updates_install_error) }
            return
        }
        busy = true
        downloadProgress = 0f
        status = getString(R.string.cpi_updates_download, 0)
        CpiUpdateInstaller.failure = false
        val app = applicationContext
        CpiUpdateInstaller.executor.execute {
            runCatching {
                val apk = CpiReleases.download(app, update) { percent ->
                    show {
                        downloadProgress = percent / 100f
                        status = getString(R.string.cpi_updates_download, percent)
                    }
                }
                try {
                    show {
                        downloadProgress = null
                        status = getString(R.string.cpi_updates_installing)
                    }
                    CpiUpdateInstaller.install(app, apk)
                } finally { apk.delete() }
            }.onFailure {
                show {
                    busy = false
                    status = getString(R.string.cpi_updates_install_error)
                }
            }
        }
    }

    private fun consumeInstallResult() {
        if (!resumed) return
        if (CpiUpdateInstaller.failure) {
            CpiUpdateInstaller.failure = false
            busy = false
            status = getString(R.string.cpi_updates_install_error)
        }
        val confirm = CpiUpdateInstaller.confirmation ?: return
        CpiUpdateInstaller.confirmation = null
        getSystemService(NotificationManager::class.java).cancel(CpiUpdateReceiver.INSTALL_ID)
        runCatching { startActivity(confirm) }.onFailure {
            busy = false
            status = getString(R.string.cpi_updates_install_error)
        }
    }

    private fun show(action: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) action() }
}
