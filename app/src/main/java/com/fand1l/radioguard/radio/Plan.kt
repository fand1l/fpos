package com.fand1l.radioguard.radio

/**
 * Що вимикатиме «Режим тиші», якщо ввімкнути його зараз. [present] — пункти, які є
 * на цьому телефоні (рядки зі «Стану зараз»).
 */
fun radiosToSilence(present: List<Radio>, allowed: Set<Radio>, keepWifi: Boolean): List<Radio> = buildList {
    addAll(present.filter { it.allowable && it !in allowed })
    if (!keepWifi && Radio.WIFI in present) add(Radio.WIFI)
}

/**
 * Що знову ввімкнеться, якщо повернути стан зі знімка. Має збігатися з
 * [Lockdown.restoreScript]. Те, що й так працює зараз, не згадуємо.
 */
fun radiosToRestore(snapshot: Snapshot, currentlyOn: Set<Radio>): List<Radio> = buildList {
    if (snapshot.airplaneOn == false) add(Radio.CELLULAR)
    if (snapshot.mobileDataOn == true) add(Radio.MOBILE_DATA)
    if (snapshot.bluetoothOn == true) add(Radio.BLUETOOTH)
    if (snapshot.bleScanOn == true) add(Radio.BLE_SCAN)
    if (snapshot.nfcOn == true) add(Radio.NFC)
    if (snapshot.uwbOn == true) add(Radio.UWB)
    if (snapshot.locationOn == true) add(Radio.LOCATION)
    if (snapshot.wifiOn == true) add(Radio.WIFI)
}.filter { it !in currentlyOn }

fun List<Check>.currentlyOn(): Set<Radio> = filter { it.on == true }.mapTo(mutableSetOf()) { it.radio }
