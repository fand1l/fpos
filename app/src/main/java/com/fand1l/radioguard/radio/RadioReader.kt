package com.fand1l.radioguard.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat

/** Читає стан звичайними API: без Shizuku, без запуску процесів. */
class RadioReader(context: Context) {

    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private val bluetooth = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val nfc: NfcAdapter? = NfcAdapter.getDefaultAdapter(app)

    /** [softApSeen] — служба бачила подію «точка доступу вмикається» і ще не бачила вимкнення. */
    // isDataEnabled досить звичайного ACCESS_NETWORK_STATE; lint бачить у переліку ще й небезпечні дозволи.
    @SuppressLint("MissingPermission")
    fun read(softApSeen: Boolean = false): RadioState = RadioState(
        airplaneOn = globalFlag(Settings.Global.AIRPLANE_MODE_ON),
        mobileDataOn = attempt { telephony?.isDataEnabled },
        bluetoothOn = bluetoothOn(),
        bleScanOn = globalFlag(BLE_SCAN_ALWAYS_ENABLED),
        hasNfc = nfc != null,
        nfcOn = attempt { nfc?.isEnabled },
        hotspotOn = if (softApSeen) true else tetheringActive(),
        wifiOn = attempt { wifi?.isWifiEnabled },
    )

    private fun globalFlag(key: String): Boolean? =
        attempt { Settings.Global.getInt(resolver, key, 0) != 0 }

    private fun bluetoothOn(): Boolean? {
        val setting = globalFlag(Settings.Global.BLUETOOTH_ON)
        // Bluetooth можуть увімкнути без запису в налаштування, тому питаємо ще й адаптер.
        // До Android 12 це вимагало окремого дозволу, якого застосунок не має.
        val adapter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            attempt { bluetooth?.state?.let { it != BluetoothAdapter.STATE_OFF } }
        } else {
            null
        }
        return when {
            setting == true || adapter == true -> true
            setting == null && adapter == null -> null
            else -> false
        }
    }

    /**
     * Система тримає останнє оголошення про тетеринг «липким», тому його можна
     * прочитати без прав. Якщо оголошення ще не було — тетеринг від увімкнення телефона не запускали.
     */
    private fun tetheringActive(): Boolean? = attempt {
        val sticky = ContextCompat.registerReceiver(
            app,
            null,
            IntentFilter(ACTION_TETHER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        ) ?: return@attempt false
        val tethered = sticky.getStringArrayListExtra(EXTRA_ACTIVE_TETHER).orEmpty()
        val localOnly = sticky.getStringArrayListExtra(EXTRA_ACTIVE_LOCAL_ONLY).orEmpty()
        hasRadioTethering(tethered + localOnly)
    }

    private inline fun <T> attempt(block: () -> T?): T? = try {
        block()
    } catch (e: SecurityException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        private val WIRED_PREFIXES = listOf("rndis", "ncm", "usb", "eth")

        /** Тетеринг по дроту (USB, Ethernet) нічого не випромінює; незнайомий інтерфейс вважаємо радіо. */
        fun hasRadioTethering(interfaces: List<String>): Boolean =
            interfaces.any { name -> WIRED_PREFIXES.none { name.startsWith(it) } }

        const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
        const val WIFI_AP_STATE_ENABLING = 12
        const val WIFI_AP_STATE_ENABLED = 13

        private const val BLE_SCAN_ALWAYS_ENABLED = "ble_scan_always_enabled"
        private const val EXTRA_ACTIVE_TETHER = "tetherArray"
        private const val EXTRA_ACTIVE_LOCAL_ONLY = "android.net.extra.ACTIVE_LOCAL_ONLY"
    }
}
