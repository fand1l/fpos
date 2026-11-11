package com.fand1l.radioguard.radio

/** Розбір виводу скриптів із [Lockdown]. Чисті функції, без Android. */
object ShellProbe {

    data class Section(val text: String, val exitCode: Int?)

    data class SavedNetwork(val id: Int, val ssid: String)

    private const val MISSING_SERVICE = "Can't find service"
    private const val UNKNOWN_SSID = "<unknown ssid>"
    private const val CONNECTED_PREFIX = "Wifi is connected to "
    private val networkLine = Regex("""^\s*(\d+)\s+(.+?)\s*$""")

    /**
     * Якщо розділ трапився двічі, беремо перший: другий могла «домалювати»
     * назва Wi-Fi мережі з переносами рядків.
     */
    fun sections(output: String): Map<String, Section> {
        val result = LinkedHashMap<String, Section>()
        var name: String? = null
        val body = StringBuilder()
        for (line in output.lineSequence()) {
            val current = name
            when {
                current != null && line.startsWith("#rc=") -> {
                    val exitCode = line.removePrefix("#rc=").trim().toIntOrNull()
                    result.putIfAbsent(current, Section(body.toString().trimEnd(), exitCode))
                    name = null
                    body.clear()
                }
                current == null && line.startsWith("#") -> name = line.substring(1).trim()
                current != null -> body.appendLine(line)
            }
        }
        return result
    }

    fun parse(output: String): ProbeState {
        val sections = sections(output)
        val uwb = sections[Lockdown.SECTION_UWB]
        val thread = sections[Lockdown.SECTION_THREAD]
        val wifi = sections[Lockdown.SECTION_WIFI]?.text?.lines().orEmpty()

        val wifiEnabled = wifi.firstNotNullOfOrNull {
            when (it.trim()) {
                "Wifi is enabled" -> true
                "Wifi is disabled" -> false
                else -> null
            }
        }
        val connectedLine = wifi.firstOrNull { it.startsWith(CONNECTED_PREFIX) }
        val ssid = connectedLine?.removePrefix(CONNECTED_PREFIX)?.let(::unquote)

        return ProbeState(
            uwbPresent = uwb?.text?.contains(MISSING_SERVICE) != true,
            uwbOn = uwb?.text?.lineSequence()
                ?.firstOrNull { it.startsWith("Uwb is ") }
                ?.let { !it.startsWith("Uwb is disabled") },
            threadPresent = thread?.text?.contains(MISSING_SERVICE) != true,
            // Команда чекає на завершення, тож код 0 означає: Thread зараз вимкнений.
            threadOn = if (thread?.exitCode == 0) false else null,
            wifiScanAlways = wifi.firstNotNullOfOrNull {
                when {
                    !it.startsWith("Wifi scanning is ") -> null
                    else -> it.contains("always available")
                }
            },
            wifiConnected = when {
                connectedLine != null -> true
                wifiEnabled == false -> false
                wifi.any { it.trim() == "Wifi is not connected" } -> false
                else -> null
            },
            ssid = ssid?.takeIf { it != UNKNOWN_SSID },
        )
    }

    /** Вивід `cmd wifi list-networks`: номер, назва в лапках, тип захисту. */
    fun savedNetworks(output: String): List<SavedNetwork> = output.lineSequence().mapNotNull { line ->
        val match = networkLine.matchEntire(line) ?: return@mapNotNull null
        val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
        val rest = match.groupValues[2]
        val ssid = if (rest.startsWith('"') && rest.lastIndexOf('"') > 0) {
            rest.substring(1, rest.lastIndexOf('"'))
        } else {
            rest.substringBefore(' ')
        }
        SavedNetwork(id, ssid)
    }.toList()

    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
