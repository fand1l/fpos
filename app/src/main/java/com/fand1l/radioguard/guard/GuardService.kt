package com.fand1l.radioguard.guard

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.fand1l.radioguard.App
import com.fand1l.radioguard.R
import com.fand1l.radioguard.data.GuardSettings
import com.fand1l.radioguard.data.Prefs
import com.fand1l.radioguard.radio.Check
import com.fand1l.radioguard.radio.Lockdown
import com.fand1l.radioguard.radio.ProbeState
import com.fand1l.radioguard.radio.Radio
import com.fand1l.radioguard.radio.RadioReader
import com.fand1l.radioguard.radio.ShellProbe
import com.fand1l.radioguard.radio.labelRes
import com.fand1l.radioguard.radio.violations
import com.fand1l.radioguard.shizuku.PrivilegedShell
import com.fand1l.radioguard.shizuku.ShellException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private lateinit var prefs: Prefs
    private lateinit var reader: RadioReader
    private lateinit var notifier: Notifier
    private lateinit var wakeLock: PowerManager.WakeLock

    // Лише з головного потоку: так запуск і зупинка циклу не можуть розминутись.
    private var loop: Job? = null
    private var startRequested = false

    @Volatile
    private var softApSeen = false

    @Volatile
    private var wifiChanged = true

    @Volatile
    private var foregroundFailed = false

    // Усе нижче чіпає лише корутина циклу.
    private val backoff = Backoff()
    private var probe: ProbeState? = null
    private var lastSlowAt = 0L
    private var lastSettings: GuardSettings? = null
    private var settingsBroken = false
    private var shellReady: Boolean? = null
    private var setupDone = false
    private var firstLock = true
    private var stuck: Set<Radio> = emptySet()
    private var shellError: String? = null
    private var hotspotError: String? = null
    private var lastStatusText: String? = null

    private val settingsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = poke()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                RadioReader.ACTION_WIFI_AP_STATE_CHANGED -> {
                    val state = intent.getIntExtra(RadioReader.EXTRA_WIFI_AP_STATE, -1)
                    softApSeen = state == RadioReader.WIFI_AP_STATE_ENABLING ||
                        state == RadioReader.WIFI_AP_STATE_ENABLED
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION,
                WifiManager.NETWORK_STATE_CHANGED_ACTION,
                -> wifiChanged = true
            }
            poke()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onWifiChanged()
        override fun onLost(network: Network) = onWifiChanged()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = (application as App).prefs
        reader = RadioReader(this)
        notifier = Notifier(this)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "radioguard:enforce")
            .apply { setReferenceCounted(false) }
        registerWatchers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        enterForeground()
        startRequested = true
        if (loop == null) launchLoop()
        poke()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterWatchers()
        GuardState.publish(null)
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun enterForeground() {
        val notification = notifier.status(getString(R.string.status_starting))
        foregroundFailed = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    Notifier.ID_STATUS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Notifier.ID_STATUS, notification)
            }
            false
        } catch (e: RuntimeException) {
            // Android 12+ може не дозволити запуск із фону. Без статусу foreground систему
            // ніщо не стримує від вбивства процесу, тому показуємо тривогу.
            true
        }
    }

    private fun launchLoop() {
        startRequested = false
        loop = scope.launch {
            val crashed = try {
                runGuard()
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                GuardState.log(getString(R.string.log_error, describe(e)))
                delay(CRASH_RESTART_MS)
                true
            }
            withContext(Dispatchers.Main.immediate) {
                loop = null
                if (startRequested || crashed) {
                    launchLoop()
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun runGuard() {
        GuardState.log(getString(R.string.log_guard_started))
        while (true) {
            val settings = readSettings()
            if (!settings.guardOn) break
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
            try {
                tick(settings)
            } catch (e: ShellException) {
                // Та сама помилка повторюється кожні кілька секунд; у журналі їй досить одного рядка.
                if (describe(e) != shellError) GuardState.log(getString(R.string.log_error, describe(e)))
                shellError = describe(e)
                report(currentChecks(settings), shellProblem = describe(e))
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
            withTimeoutOrNull(TICK_MS) { wake.receive() }
            // Одна дія користувача дає кілька подій поспіль; чекаємо, поки стан устоїться.
            delay(DEBOUNCE_MS)
        }
        finish()
    }

    private suspend fun readSettings(): GuardSettings = try {
        prefs.current().also {
            lastSettings = it
            settingsBroken = false
        }
    } catch (e: IOException) {
        if (!settingsBroken) GuardState.log(getString(R.string.log_settings_unreadable))
        settingsBroken = true
        // Збій читання — не причина вимикати режим і тим паче повертати радіо.
        lastSettings ?: GuardSettings(guardOn = true)
    }

    private suspend fun tick(settings: GuardSettings) {
        val status = PrivilegedShell.refresh()
        val ready = status == PrivilegedShell.Status.READY
        if (shellReady != ready) {
            if (!ready) {
                GuardState.log(getString(R.string.log_shell_lost))
            } else if (shellReady == false) {
                GuardState.log(getString(R.string.log_shell_back))
            }
            shellReady = ready
        }
        if (!ready) {
            // Старі дані з shell уже нічого не доводять.
            probe = null
            setupDone = false
            report(currentChecks(settings), shellProblem = getString(status.alarmText()))
            return
        }

        if (!setupDone) {
            PrivilegedShell.exec(Lockdown.setupScript(packageName))
            setupDone = true
        }
        if (slowCycleDue()) runSlowCycle()

        var checks = currentChecks(settings)
        val violations = checks.violations()
        if (violations.isEmpty()) {
            backoff.onSuccess()
            stuck = emptySet()
        } else if (violations.any { it !in stuck } || backoff.canAttempt(SystemClock.elapsedRealtime())) {
            if (violations != stuck) {
                val message = if (firstLock) R.string.log_locking else R.string.log_violation
                GuardState.log(getString(message, names(violations)))
            }
            enforce(violations, settings)
            delay(SETTLE_MS)
            runSlowCycle()

            checks = currentChecks(settings)
            val remaining = checks.violations()
            val fixed = violations - remaining
            if (fixed.isNotEmpty()) GuardState.log(getString(R.string.log_fixed, names(fixed)))
            if (remaining.isEmpty()) {
                backoff.onSuccess()
            } else {
                if (remaining != stuck) GuardState.log(getString(R.string.log_failed, names(remaining)))
                backoff.onFailure(SystemClock.elapsedRealtime())
            }
            stuck = remaining
        }
        firstLock = false
        shellError = null
        report(checks, shellProblem = null)
    }

    private fun currentChecks(settings: GuardSettings): List<Check> =
        reader.read(softApSeen).checks(probe, settings.keepWifi, settings.allowedSsid)

    private fun slowCycleDue(): Boolean {
        val sinceLast = SystemClock.elapsedRealtime() - lastSlowAt
        return probe == null || sinceLast >= SLOW_CYCLE_MS || (wifiChanged && sinceLast >= MIN_PROBE_GAP_MS)
    }

    private suspend fun runSlowCycle() {
        wifiChanged = false
        probe = ShellProbe.parse(PrivilegedShell.exec(Lockdown.slowScript()).output)
        // Наосліп: стан точки доступу читаємо без прав і не можемо на нього цілком покладатись.
        stopHotspot()
        lastSlowAt = SystemClock.elapsedRealtime()
    }

    private suspend fun enforce(violations: Set<Radio>, settings: GuardSettings) {
        val script = Lockdown.lockScript(violations, settings.keepWifi)
        if (script.isNotEmpty()) PrivilegedShell.exec(script)
        if (Radio.HOTSPOT in violations) stopHotspot()
        if (Radio.WIFI_NETWORK in violations) dropForeignNetwork()
    }

    private suspend fun stopHotspot() {
        val error = PrivilegedShell.stopTethering()
        if (error != null && error != hotspotError) {
            GuardState.log(getString(R.string.log_hotspot_error, error))
        }
        hotspotError = error
    }

    private suspend fun dropForeignNetwork() {
        val ssid = probe?.ssid ?: return
        val saved = ShellProbe.savedNetworks(PrivilegedShell.exec(Lockdown.savedNetworksScript()).output)
        val ids = saved.filter { it.ssid == ssid }.map { it.id }
        if (ids.isNotEmpty()) {
            PrivilegedShell.exec(Lockdown.forgetNetworksScript(ids))
            GuardState.log(getString(R.string.log_network_forgotten, ssid))
        } else {
            PrivilegedShell.exec(Lockdown.wifiReconnectScript())
            GuardState.log(getString(R.string.log_network_dropped, ssid))
        }
    }

    private fun report(checks: List<Check>, shellProblem: String?) {
        GuardState.publish(checks)
        val critical = stuck.filter { it.critical }
        when {
            shellProblem != null -> {
                showStatus(R.string.status_no_shell)
                notifier.showAlarm(getString(R.string.alarm_no_shell_title), shellProblem)
            }
            critical.isNotEmpty() -> {
                showStatus(R.string.status_violation)
                notifier.showAlarm(
                    getString(R.string.alarm_stuck_title),
                    getString(R.string.alarm_stuck_text, names(critical)),
                )
            }
            foregroundFailed -> notifier.showAlarm(
                getString(R.string.alarm_service_title),
                getString(R.string.alarm_service_text),
            )
            settingsBroken -> notifier.showAlarm(
                getString(R.string.alarm_settings_title),
                getString(R.string.alarm_settings_text),
            )
            else -> {
                val ok = checks.violations().isEmpty()
                showStatus(if (ok) R.string.status_ok else R.string.status_violation)
                notifier.clearAlarm()
            }
        }
    }

    private fun showStatus(textRes: Int) {
        val text = getString(textRes)
        if (text == lastStatusText) return
        lastStatusText = text
        notifier.showStatus(text)
    }

    private suspend fun finish() {
        GuardState.publish(null)
        val settings = try {
            prefs.current()
        } catch (e: IOException) {
            null
        }
        // Режим могли ввімкнути знову, поки цикл завершувався: тоді повертати нічого не треба.
        if (settings == null || settings.guardOn || !settings.restorePending) {
            notifier.clearAlarm()
            return
        }
        showStatus(R.string.status_restoring)
        try {
            if (PrivilegedShell.refresh() != PrivilegedShell.Status.READY) {
                throw ShellException("Shizuku не готовий")
            }
            PrivilegedShell.exec(Lockdown.restoreScript(prefs.snapshot(), packageName))
            prefs.restoreDone()
            GuardState.log(getString(R.string.log_restored))
            notifier.clearAlarm()
        } catch (e: ShellException) {
            restoreFailed()
        } catch (e: IOException) {
            restoreFailed()
        }
    }

    private fun restoreFailed() {
        GuardState.log(getString(R.string.log_restore_failed))
        notifier.showAlarm(
            getString(R.string.alarm_restore_title),
            getString(R.string.alarm_restore_text),
            ongoing = false,
        )
    }

    private fun registerWatchers() {
        contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(RadioReader.ACTION_WIFI_AP_STATE_CHANGED)
            addAction(RadioReader.ACTION_TETHER_STATE_CHANGED)
        }
        // Ці оголошення надсилають і системні процеси з іншим uid (Bluetooth, NFC),
        // тому приймач відкритий. Підробити їх застосунки не можуть: дії захищені системою.
        // Навіть підробка лише змусила б ще раз перечитати стан.
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            networkCallback,
        )

        scope.launch {
            merge(prefs.settings.catch { }, PrivilegedShell.status).collect { poke() }
        }
    }

    private fun unregisterWatchers() {
        contentResolver.unregisterContentObserver(settingsObserver)
        unregisterReceiver(receiver)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
    }

    private fun onWifiChanged() {
        wifiChanged = true
        poke()
    }

    /** Будить цикл і не дає телефону заснути, поки порушення не оброблено. */
    private fun poke() {
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        wake.trySend(Unit)
    }

    private fun names(radios: Collection<Radio>): String =
        radios.joinToString { getString(it.labelRes) }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName

    private fun PrivilegedShell.Status.alarmText(): Int = when (this) {
        PrivilegedShell.Status.NOT_INSTALLED -> R.string.alarm_shizuku_not_installed
        PrivilegedShell.Status.NO_PERMISSION -> R.string.alarm_shizuku_no_permission
        PrivilegedShell.Status.NOT_RUNNING, PrivilegedShell.Status.READY -> R.string.alarm_shizuku_not_running
    }

    companion object {
        private const val TICK_MS = 3_000L
        private const val DEBOUNCE_MS = 200L
        private const val SETTLE_MS = 2_000L
        private const val SLOW_CYCLE_MS = 30_000L
        private const val MIN_PROBE_GAP_MS = 2_000L
        private const val CRASH_RESTART_MS = 3_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30_000L

        /** false — система не дозволила запуск (застосунок у фоні). */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, GuardService::class.java))
            true
        } catch (e: RuntimeException) {
            false
        }
    }
}
