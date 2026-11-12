package com.fand1l.radioguard.radio

/**
 * [allowable] — пункт можна дозволити як виняток. Wi-Fi має власні налаштування.
 * [emits] — пункт сам випромінює. GPS лише приймає сигнал супутників.
 */
enum class Radio(val critical: Boolean = true, val allowable: Boolean = true, val emits: Boolean = true) {
    CELLULAR,

    // У режимі польоту модем вимкнений, тож самі мобільні дані нічого не випромінюють.
    MOBILE_DATA(critical = false),
    BLUETOOTH,
    BLE_SCAN,
    NFC,
    HOTSPOT,
    UWB,
    THREAD,
    LOCATION(critical = false, emits = false),
    WIFI(allowable = false),
    WIFI_NETWORK(allowable = false),
    ;

    companion object {
        /** Власник вирішив: GPS потрібен, карти офлайн. Тому геолокація типово дозволена. */
        val DEFAULT_ALLOWED: Set<Radio> = setOf(LOCATION)
    }
}

enum class CheckStatus { OK, VIOLATION, UNKNOWN, ALLOWED }

data class Check(
    val radio: Radio,
    val status: CheckStatus,
    /** Чи працює пункт зараз. null — невідомо. */
    val on: Boolean? = null,
    /** true лише для Wi-Fi, коли він має лишатись увімкненим. */
    val wantedOn: Boolean = false,
    val detail: String? = null,
)

/** Стан, який застосунок читає сам, без Shizuku. null — прочитати не вдалося. */
data class RadioState(
    val airplaneOn: Boolean?,
    val mobileDataOn: Boolean?,
    val bluetoothOn: Boolean?,
    val bleScanOn: Boolean?,
    val hasNfc: Boolean,
    val nfcOn: Boolean?,
    val hotspotOn: Boolean?,
    val wifiOn: Boolean?,
    val locationOn: Boolean? = null,
) {
    fun checks(
        probe: ProbeState?,
        keepWifi: Boolean,
        allowedSsid: String,
        allowed: Set<Radio> = emptySet(),
    ): List<Check> = buildList {
        fun offCheck(radio: Radio, on: Boolean?) = Check(
            radio = radio,
            status = if (radio in allowed) CheckStatus.ALLOWED else mustBeOff(on),
            on = on,
        )

        add(offCheck(Radio.CELLULAR, airplaneOn?.not()))
        add(offCheck(Radio.MOBILE_DATA, mobileDataOn))
        add(offCheck(Radio.BLUETOOTH, bluetoothOn))
        add(offCheck(Radio.BLE_SCAN, bleScanOn))
        if (hasNfc) add(offCheck(Radio.NFC, nfcOn))
        add(offCheck(Radio.HOTSPOT, hotspotOn))
        if (probe?.uwbPresent != false) add(offCheck(Radio.UWB, probe?.uwbOn))
        if (probe?.threadPresent != false) add(offCheck(Radio.THREAD, probe?.threadOn))
        add(offCheck(Radio.LOCATION, locationOn))

        if (keepWifi) {
            add(Check(Radio.WIFI, mustBeOff(wifiOn?.not()), on = wifiOn, wantedOn = true))
            val wanted = allowedSsid.trim()
            if (wanted.isNotEmpty()) add(networkCheck(probe, wanted))
        } else {
            // Сканування «завжди доступне» вмикає радіо Wi-Fi навіть при вимкненому Wi-Fi.
            val scanning = probe?.wifiScanAlways == true
            val on = if (scanning) true else wifiOn
            add(Check(Radio.WIFI, mustBeOff(on), on = on))
        }
    }

    private fun networkCheck(probe: ProbeState?, wanted: String): Check = when {
        probe?.wifiConnected == null -> Check(Radio.WIFI_NETWORK, CheckStatus.UNKNOWN)
        probe.wifiConnected == false -> Check(Radio.WIFI_NETWORK, CheckStatus.OK, on = false)
        probe.ssid == null -> Check(Radio.WIFI_NETWORK, CheckStatus.UNKNOWN, on = true)
        probe.ssid == wanted -> Check(Radio.WIFI_NETWORK, CheckStatus.OK, on = true, detail = probe.ssid)
        else -> Check(Radio.WIFI_NETWORK, CheckStatus.VIOLATION, on = true, detail = probe.ssid)
    }

    fun snapshot(probe: ProbeState?) = Snapshot(
        airplaneOn = airplaneOn,
        mobileDataOn = mobileDataOn,
        bluetoothOn = bluetoothOn,
        bleScanOn = bleScanOn,
        nfcOn = nfcOn,
        wifiOn = wifiOn,
        uwbOn = probe?.uwbOn,
        locationOn = locationOn,
    )
}

/** Стан, який видно лише через shell. null — невідомо. */
data class ProbeState(
    val uwbPresent: Boolean = true,
    val uwbOn: Boolean? = null,
    val threadPresent: Boolean = true,
    val threadOn: Boolean? = null,
    val wifiScanAlways: Boolean? = null,
    val wifiConnected: Boolean? = null,
    val ssid: String? = null,
)

/** Яким усе було до «Режиму тиші». null — не знаємо, тому при поверненні не чіпаємо. */
data class Snapshot(
    val airplaneOn: Boolean? = null,
    val mobileDataOn: Boolean? = null,
    val bluetoothOn: Boolean? = null,
    val bleScanOn: Boolean? = null,
    val nfcOn: Boolean? = null,
    val wifiOn: Boolean? = null,
    val uwbOn: Boolean? = null,
    val locationOn: Boolean? = null,
)

fun List<Check>.violations(): Set<Radio> =
    filter { it.status == CheckStatus.VIOLATION }.mapTo(linkedSetOf()) { it.radio }

/** Дозволені винятки, які зараз працюють. */
fun List<Check>.allowedAndOn(): List<Radio> =
    filter { it.status == CheckStatus.ALLOWED && it.on == true }.map { it.radio }

private fun mustBeOff(on: Boolean?) = when (on) {
    null -> CheckStatus.UNKNOWN
    true -> CheckStatus.VIOLATION
    false -> CheckStatus.OK
}
