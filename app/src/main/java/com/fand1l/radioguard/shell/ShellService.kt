package com.fand1l.radioguard.shell

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import androidx.annotation.Keep
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Працює не в процесі застосунку, а в окремому процесі, який Shizuku запускає
 * з правами shell (uid 2000). Тут немає Application, тому клас не чіпає решту коду.
 */
@Keep
class ShellService() : IShellService.Stub() {

    // Shizuku 13+ шукає саме такий конструктор; контекст застосунку тут не потрібен.
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    private var tethering: Any? = null

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(script: String): String {
        val process = ProcessBuilder("sh", "-c", script)
            .redirectErrorStream(true)
            .start()
        process.outputStream.close()

        val output = StringBuilder()
        val reader = thread(name = "shell-output") {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(4096)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    synchronized(output) {
                        // Binder не пропустить великий рядок, тому зайве відкидаємо.
                        val room = MAX_OUTPUT_CHARS - output.length
                        if (room > 0) output.append(buffer, 0, minOf(read, room))
                    }
                }
            }
        }

        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(READER_JOIN_MS)

        val exitCode = if (finished) process.exitValue() else EXIT_TIMEOUT
        return synchronized(output) { "$exitCode\n$output" }
    }

    /**
     * `cmd wifi stop-softap` дозволена лише root, а Shizuku через ADB працює як shell.
     * Тому точку доступу зупиняємо системним API: shell має дозвіл TETHER_PRIVILEGED.
     *
     * Зупиняємо лише радіо-тетеринг. `stopAllTethering()` щоразу скидав би ще й режим USB
     * (наприклад, передачу файлів), а дріт нічого не випромінює.
     */
    override fun stopTethering(): String = try {
        val manager = tethering ?: createTetheringManager().also { tethering = it }
        val stop = manager.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType)
        RADIO_TETHERING_TYPES.forEach { stop.invoke(manager, it) }
        ""
    } catch (e: Throwable) {
        val cause = e.cause ?: e
        "${cause.javaClass.simpleName}: ${cause.message}"
    }

    @SuppressLint("PrivateApi")
    private fun createTetheringManager(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getMethod("getService", String::class.java)
        val connector = Supplier { getService.invoke(null, "tethering") as IBinder? }
        return Class.forName("android.net.TetheringManager")
            .getConstructor(Context::class.java, Supplier::class.java)
            .newInstance(ShellIdentity, connector)
    }

    /**
     * Служба тетерингу звіряє назву пакета з uid того, хто викликає.
     * Для uid 2000 це системний пакет shell, а не наш застосунок.
     */
    private object ShellIdentity : ContextWrapper(null) {
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getAttributionTag(): String? = null
    }

    private companion object {
        const val SHELL_PACKAGE = "com.android.shell"

        // TetheringManager.TETHERING_WIFI і TETHERING_BLUETOOTH.
        val RADIO_TETHERING_TYPES = intArrayOf(0, 2)
        const val TIMEOUT_SECONDS = 15L
        const val READER_JOIN_MS = 1000L
        const val EXIT_TIMEOUT = -1
        const val MAX_OUTPUT_CHARS = 64 * 1024
    }
}
