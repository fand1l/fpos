package com.fand1l.radioguard

import android.app.Application
import com.fand1l.radioguard.data.Prefs
import com.fand1l.radioguard.guard.Notifier
import com.fand1l.radioguard.shizuku.PrivilegedShell

class App : Application() {

    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        Notifier(this).createChannels()
        PrivilegedShell.init(this)
    }
}
