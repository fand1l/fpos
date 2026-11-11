package com.fand1l.radioguard.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fand1l.radioguard.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val prefs = (context.applicationContext as App).prefs
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val guardOn = try {
                    prefs.current().guardOn
                } catch (e: IOException) {
                    // Не знаємо, чи режим був увімкнений. Краще зайвий раз запустити вартового.
                    true
                }
                if (guardOn) GuardService.start(context)
            } finally {
                pending.finish()
            }
        }
    }
}
