package com.fand1l.radioguard.radio

/**
 * Усі shell-команди застосунку живуть тут. Кожна має рядок у таблиці розділу 6 PLAN.md.
 * У скрипти потрапляють лише сталі рядки, назва пакета та числа — нічого, що вводить користувач.
 */
object Lockdown {

    /** Вимикає лише те, що порушено, щоб не смикати Wi-Fi без потреби. */
    fun lockScript(violations: Set<Radio>, keepWifi: Boolean): String = buildList {
        if (Radio.CELLULAR in violations) add("cmd connectivity airplane-mode enable")
        if (Radio.MOBILE_DATA in violations) add("svc data disable")
        if (Radio.BLUETOOTH in violations) add("svc bluetooth disable")
        if (Radio.BLE_SCAN in violations) add("settings put global ble_scan_always_enabled 0")
        if (Radio.NFC in violations) add("svc nfc disable")
        // Спрацює лише з root-Shizuku; для shell точку доступу зупиняє ShellService.stopTethering().
        if (Radio.HOTSPOT in violations) add("cmd wifi stop-softap")
        if (Radio.UWB in violations) add("cmd uwb disable-uwb")
        if (Radio.THREAD in violations) add("cmd thread_network disable")

        if (keepWifi) {
            if (Radio.CELLULAR in violations) {
                // Режим польоту може вимкнути Wi-Fi із затримкою; вмикаємо вже після цього.
                add("sleep 1")
                add("cmd wifi set-wifi-enabled enabled")
            } else if (Radio.WIFI in violations) {
                add("cmd wifi set-wifi-enabled enabled")
            }
        } else if (Radio.WIFI in violations) {
            add("cmd wifi set-wifi-enabled disabled")
            add("cmd wifi set-scan-always-available disabled")
        }
    }.joinToString("\n")

    /**
     * Раз на 30 с. Стан Thread прочитати нічим, тому його вимикаємо наосліп;
     * UWB і Wi-Fi лише читаємо. Wi-Fi стоїть останнім: назву мережі задає чужий роутер,
     * і вона не повинна мати змоги підробити розділи, що йдуть перед нею.
     */
    fun slowScript(): String = listOf(
        section(SECTION_THREAD, "cmd thread_network disable"),
        probeScript(),
    ).joinToString("\n")

    /** Нічого не змінює, лише читає. */
    fun probeScript(): String = listOf(
        section(SECTION_UWB, "cmd uwb status"),
        section(SECTION_WIFI, "cmd wifi status"),
    ).joinToString("\n")

    fun savedNetworksScript(): String = "cmd wifi list-networks"

    fun forgetNetworksScript(networkIds: List<Int>): String =
        networkIds.joinToString("\n") { "cmd wifi forget-network $it" }

    /** Запасний спосіб розірвати з'єднання з мережею, якої немає серед збережених. */
    fun wifiReconnectScript(): String = listOf(
        "cmd wifi set-wifi-enabled disabled",
        "sleep 1",
        "cmd wifi set-wifi-enabled enabled",
    ).joinToString("\n")

    /**
     * Без цього система може не дати службі піднятись у фоні: після вбивства процесу
     * або з плитки чи віджета. Тому виняток лишається й після вимкнення режиму.
     */
    fun setupScript(packageName: String): String = "cmd deviceidle whitelist +$packageName"

    fun restoreScript(snapshot: Snapshot): String = buildList {
        if (snapshot.uwbOn == true) add("cmd uwb enable-uwb")
        if (snapshot.nfcOn == true) add("svc nfc enable")
        if (snapshot.bleScanOn == true) add("settings put global ble_scan_always_enabled 1")
        if (snapshot.mobileDataOn == true) add("svc data enable")
        if (snapshot.airplaneOn == false) {
            add("cmd connectivity airplane-mode disable")
            add("sleep 1")
        }
        if (snapshot.bluetoothOn == true) add("svc bluetooth enable")
        when (snapshot.wifiOn) {
            true -> add("cmd wifi set-wifi-enabled enabled")
            false -> add("cmd wifi set-wifi-enabled disabled")
            null -> Unit
        }
    }.joinToString("\n")

    const val SECTION_THREAD = "thread"
    const val SECTION_UWB = "uwb"
    const val SECTION_WIFI = "wifi"

    private fun section(name: String, command: String): String =
        "echo '#$name'\n$command 2>&1\necho \"#rc=\$?\""
}
