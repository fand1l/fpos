package com.fand1l.radioguard

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fand1l.radioguard.shizuku.PrivilegedShell
import com.fand1l.radioguard.ui.MainScreen
import com.fand1l.radioguard.ui.MainViewModel
import com.fand1l.radioguard.ui.RadioGuardTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            RadioGuardTheme {
                MainRoute()
            }
        }
    }
}

@Composable
private fun MainRoute(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var notificationsAllowed by remember { mutableStateOf(context.notificationsEnabled()) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = context.notificationsEnabled()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsAllowed = context.notificationsEnabled()
    }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    val readyToAsk = state.loaded && state.onboardingDone
    LaunchedEffect(readyToAsk) {
        if (readyToAsk && !permissionAsked && !notificationsAllowed &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    MainScreen(
        state = state,
        notificationsAllowed = notificationsAllowed,
        actions = viewModel,
        onOpenShizuku = { context.openShizuku() },
        onOpenNotificationSettings = { context.openNotificationSettings() },
    )
}

private fun Context.notificationsEnabled(): Boolean =
    getSystemService(NotificationManager::class.java).areNotificationsEnabled()

private fun Context.openShizuku() {
    val intent = packageManager.getLaunchIntentForPackage(PrivilegedShell.SHIZUKU_PACKAGE) ?: return
    startActivity(intent)
}

private fun Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
}
