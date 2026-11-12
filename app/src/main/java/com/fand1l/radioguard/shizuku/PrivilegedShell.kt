package com.fand1l.radioguard.shizuku

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.fand1l.radioguard.BuildConfig
import com.fand1l.radioguard.shell.IShellService
import com.fand1l.radioguard.shell.ShellService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class ShellException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class ShellResult(val exitCode: Int, val output: String)

/** Єдина точка, через яку застосунок щось змінює в системі. */
// Тут лежить контекст застосунку, а не екрана: він живе стільки ж, скільки процес.
@SuppressLint("StaticFieldLeak")
object PrivilegedShell {

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 1
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val READY_POLL_MS = 250L

    private lateinit var app: Context

    private val _status = MutableStateFlow(Status.NOT_RUNNING)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var service: IShellService? = null

    @Volatile
    private var pendingBind: CompletableDeferred<IShellService>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val shell = IShellService.Stub.asInterface(binder)
            service = shell
            pendingBind?.complete(shell)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    fun init(context: Context) {
        app = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            refresh()
        }
        Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        refresh()
    }

    fun refresh(): Status {
        val current = readStatus()
        _status.value = current
        return current
    }

    /**
     * Після холодного старту процесу Shizuku передає свій binder не одразу.
     * Без цієї паузи справний Shizuku виглядав би як «не запущений».
     */
    suspend fun awaitReady(timeoutMs: Long): Boolean {
        val ready = withTimeoutOrNull(timeoutMs) {
            while (refresh() != Status.READY) delay(READY_POLL_MS)
            true
        }
        return ready == true
    }

    private fun readStatus(): Status = try {
        when {
            !Shizuku.pingBinder() -> if (isInstalled()) Status.NOT_RUNNING else Status.NOT_INSTALLED
            Shizuku.isPreV11() -> Status.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Status.NO_PERMISSION
            else -> Status.READY
        }
    } catch (e: RuntimeException) {
        // Shizuku може зупинитись між двома викликами.
        Status.NOT_RUNNING
    }

    private fun isInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            }
        } catch (e: RuntimeException) {
            refresh()
        }
    }

    suspend fun exec(script: String): ShellResult = call { shell ->
        val raw = shell.exec(script)
        val exitCode = raw.substringBefore('\n').trim().toIntOrNull()
            ?: throw ShellException("Незрозуміла відповідь shell")
        ShellResult(exitCode, raw.substringAfter('\n', ""))
    }

    /** Повертає текст помилки або null, якщо все гаразд. */
    suspend fun stopTethering(): String? = call { shell ->
        shell.stopTethering().takeIf { it.isNotEmpty() }
    }

    private suspend fun <T> call(block: (IShellService) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val shell = connect()
            try {
                block(shell)
            } catch (e: ShellException) {
                throw e
            } catch (e: Exception) {
                service = null
                throw ShellException("Збій виклику shell: ${e.message}", e)
            }
        }
    }

    private suspend fun connect(): IShellService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        service = null

        val status = refresh()
        if (status != Status.READY) throw ShellException("Shizuku не готовий: $status")

        val deferred = CompletableDeferred<IShellService>()
        pendingBind = deferred
        try {
            Shizuku.bindUserService(serviceArgs, connection)
        } catch (e: RuntimeException) {
            throw ShellException("Не вдалося запустити службу shell: ${e.message}", e)
        }
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
            ?: throw ShellException("Служба shell не відповіла за ${BIND_TIMEOUT_MS / 1000} с")
    }
}
