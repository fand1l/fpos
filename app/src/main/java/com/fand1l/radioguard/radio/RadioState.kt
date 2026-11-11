package com.fand1l.radioguard.radio

enum class Radio(val critical: Boolean = true) {
    CELLULAR,

    // У режимі польоту модем вимкнений, тож самі мобільні дані нічого не випромінюють.
    MOBILE_DATA(critical = false),
    BLUETOOTH,
    BLE_SCAN,
    NFC,
    HOTSPOT,
    UWB,
    THREAD,
    WIFI,
    WIFI_NETWORK,
}

enum class CheckStatus { OK, VIOLATION, UNKNOWN }

data class Check(
    val radio: Radio,
    val status: CheckStatus,
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
) {
    fun checks(probe: ProbeState?, keepWifi: Boolean, allowedSsid: String): List<Check> = buildList {
        add(Check(Radio.CELLULAR, mustBeOn(airplaneOn)))
        add(Check(Radio.MOBILE_DATA, mustBeOff(mobileDataOn)))
        add(Check(Radio.BLUETOOTH, mustBeOff(bluetoothOn)))
        add(Check(Radio.BLE_SCAN, mustBeOff(bleScanOn)))
        if (hasNfc) add(Check(Radio.NFC, mustBeOff(nfcOn)))
        add(Check(Radio.HOTSPOT, mustBeOff(hotspotOn)))
        if (probe?.uwbPresent != false) add(Check(Radio.UWB, mustBeOff(probe?.uwbOn)))
        if (probe?.threadPresent != false) add(Check(Radio.THREAD, mustBeOff(probe?.threadOn)))

        if (keepWifi) {
            add(Check(Radio.WIFI, mustBeOn(wifiOn), wantedOn = true))
            val allowed = allowedSsid.trim()
            if (allowed.isNotEmpty()) add(networkCheck(probe, allowed))
        } else {
            // Сканування «завжди доступне» вмикає радіо Wi-Fi навіть при вимкненому Wi-Fi.
            val scanning = probe?.wifiScanAlways == true
            add(Check(Radio.WIFI, if (scanning) CheckStatus.VIOLATION else mustBeOff(wifiOn)))
        }
    }

    private fun networkCheck(probe: ProbeState?, allowed: String): Check = when {
        probe?.wifiConnected == null -> Check(Radio.WIFI_NETWORK, CheckStatus.UNKNOWN)
        probe.wifiConnected == false -> Check(Radio.WIFI_NETWORK, CheckStatus.OK)
        probe.ssid == null -> Check(Radio.WIFI_NETWORK, CheckStatus.UNKNOWN)
        probe.ssid == allowed -> Check(Radio.WIFI_NETWORK, CheckStatus.OK, detail = probe.ssid)
        else -> Check(Radio.WIFI_NETWORK, CheckStatus.VIOLATION, detail = probe.ssid)
    }

    fun snapshot(probe: ProbeState?) = Snapshot(
        airplaneOn = airplaneOn,
        mobileDataOn = mobileDataOn,
        bluetoothOn = bluetoothOn,
        bleScanOn = bleScanOn,
        nfcOn = nfcOn,
        wifiOn = wifiOn,
        uwbOn = probe?.uwbOn,
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
)

fun List<Check>.violations(): Set<Radio> =
    filter { it.status == CheckStatus.VIOLATION }.mapTo(linkedSetOf()) { it.radio }

private fun mustBeOff(on: Boolean?) = when (on) {
    null -> CheckStatus.UNKNOWN
    true -> CheckStatus.VIOLATION
    false -> CheckStatus.OK
}

private fun mustBeOn(on: Boolean?) = when (on) {
    null -> CheckStatus.UNKNOWN
    true -> CheckStatus.OK
    false -> CheckStatus.VIOLATION
}
