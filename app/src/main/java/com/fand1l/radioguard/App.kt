package com.fand1l.radioguard

import android.app.Application
import com.fand1l.radioguard.data.Prefs
import com.fand1l.radioguard.guard.GuardService
import com.fand1l.radioguard.guard.Notifier
import com.fand1l.radioguard.guard.QuickState
import com.fand1l.radioguard.guard.quickStates
import com.fand1l.radioguard.quick.GuardWidget
import com.fand1l.radioguard.shizuku.PrivilegedShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.IOException

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val prefs: Prefs by lazy { Prefs.create(this) }

    /** Стан для плитки й віджета. */
    val quickStates: Flow<QuickState> by lazy { quickStates(prefs) }

    override fun onCreate() {
        super.onCreate()
        Notifier(this).createChannels()
        PrivilegedShell.init(this)

        // Процес могли підняти плитка, віджет чи перезавантаження. Якщо режим увімкнено,
        // вартовий має працювати, хоч би хто розбудив застосунок.
        appScope.launch {
            val guardOn = try {
                prefs.current().guardOn
            } catch (e: IOException) {
                true
            }
            if (guardOn) GuardService.start(this@App)
        }
        appScope.launch {
            quickStates.collect { GuardWidget.render(this@App, it) }
        }
    }
}
