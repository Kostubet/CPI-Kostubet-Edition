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
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.cosmod.launcherglass.BuildConfig
import com.cosmod.launcherglass.R
import com.cosmod.launcherglass.ui.CouiAction
import com.cosmod.launcherglass.ui.CouiGroup
import com.cosmod.launcherglass.ui.CouiSlot
import com.cosmod.launcherglass.ui.UiLanguage
import com.cosmod.launcherglass.ui.setCouiPageContent
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

class CpiUpdateActivity : AppCompatActivity() {
    private var release by mutableStateOf<CpiReleases.Release?>(null)
    private var status by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var alerts by mutableStateOf(false)
    private var resumed = false

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) CpiUpdateService.setEnabled(this, true)
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
        super.onCreate(savedInstanceState)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val canvas = ContextCompat.getColor(this, R.color.cpi_ui_background)
        val accent = ContextCompat.getColor(this, android.R.color.system_accent1_400)
        val screen = CouiSlot(this, dark, accent) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { finish() }) {
                        Icon(painterResource(R.drawable.ic_arrow_back), getString(R.string.ui_cancel),
                            tint = COUITheme.colorScheme.labelPrimary)
                    }
                    Text(getString(R.string.cpi_updates_title), style = COUITheme.textStyles.headlineS)
                }
                CouiGroup {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(getString(R.string.cpi_updates_current, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                            style = COUITheme.textStyles.bodyM, color = COUITheme.colorScheme.labelSecondary)
                        Text(status, style = COUITheme.textStyles.bodyL)
                        if (release != null) CouiAction(getString(R.string.cpi_updates_install), primary = true,
                            onClick = ::install, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        CouiAction(getString(R.string.cpi_updates_check), primary = false,
                            onClick = ::check, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                    }
                }
                CouiGroup {
                    SwitchPreference(checked = alerts, onCheckedChange = ::toggleAlerts,
                        title = getString(R.string.cpi_updates_notifications))
                }
            }
        }
        val scroller = ScrollView(this).apply { setBackgroundColor(canvas); addView(screen) }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setCouiPageContent(scroller, dark, accent)
        WindowCompat.getInsetsController(window, scroller).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroller) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
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
        alerts = CpiUpdateService.enabled(this) && getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        CpiUpdateService.reconcile(this)
    }

    private fun toggleAlerts(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            CpiUpdateService.setEnabled(this, enabled)
            refreshAlerts()
            if (enabled && !alerts) startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun check() {
        busy = true
        status = getString(R.string.cpi_updates_checking)
        CpiUpdateInstaller.executor.execute {
            val result = runCatching { CpiReleases.latest(fresh = true)?.takeIf { it.build > BuildConfig.VERSION_CODE } }
            result.exceptionOrNull()?.let { android.util.Log.w("CPI.Updates", "Release check failed", it) }
            show {
                busy = false
                release = result.getOrNull()
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
        status = getString(R.string.cpi_updates_download, 0)
        CpiUpdateInstaller.failure = false
        val app = applicationContext
        CpiUpdateInstaller.executor.execute {
            runCatching {
                val apk = CpiReleases.download(app, update) { percent ->
                    show { status = getString(R.string.cpi_updates_download, percent) }
                }
                try {
                    show { status = getString(R.string.cpi_updates_installing) }
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
