package com.fand1l.radioguard.radio

import androidx.annotation.StringRes
import com.fand1l.radioguard.R

@get:StringRes
val Radio.labelRes: Int
    get() = when (this) {
        Radio.CELLULAR -> R.string.radio_cellular
        Radio.MOBILE_DATA -> R.string.radio_mobile_data
        Radio.BLUETOOTH -> R.string.radio_bluetooth
        Radio.BLE_SCAN -> R.string.radio_ble_scan
        Radio.NFC -> R.string.radio_nfc
        Radio.HOTSPOT -> R.string.radio_hotspot
        Radio.UWB -> R.string.radio_uwb
        Radio.THREAD -> R.string.radio_thread
        Radio.WIFI -> R.string.radio_wifi
        Radio.WIFI_NETWORK -> R.string.radio_wifi_network
    }
