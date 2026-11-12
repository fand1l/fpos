package com.fand1l.radioguard

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.StatusBarManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
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
import com.fand1l.radioguard.guard.GuardRequest
import com.fand1l.radioguard.quick.GuardTileService
import com.fand1l.radioguard.quick.GuardWidget
import com.fand1l.radioguard.shizuku.PrivilegedShell
import com.fand1l.radioguard.ui.MainScreen
import com.fand1l.radioguard.ui.MainViewModel
import com.fand1l.radioguard.ui.RadioGuardTheme

class MainActivity : ComponentActivity() {

    /** Плитка чи віджет попросили змінити режим; підтвердження покаже екран. */
    private var intentRequest by mutableStateOf<GuardRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Після повороту екрана той самий intent приходить знову; запит уже у ViewModel.
        if (savedInstanceState == null) intentRequest = intent.request()
        setContent {
            RadioGuardTheme {
                MainRoute(
                    intentRequest = intentRequest,
                    onIntentRequestTaken = { intentRequest = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.request()?.let { intentRequest = it }
    }

    private fun Intent.request(): GuardRequest? = when (action) {
        ACTION_CONFIRM_ON -> GuardRequest.TURN_ON
        ACTION_CONFIRM_OFF -> GuardRequest.TURN_OFF
        else -> null
    }

    companion object {
        private const val ACTION_CONFIRM_ON = "com.fand1l.radioguard.action.CONFIRM_ON"
        private const val ACTION_CONFIRM_OFF = "com.fand1l.radioguard.action.CONFIRM_OFF"

        fun intent(context: Context, request: GuardRequest?): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(
                    when (request) {
                        GuardRequest.TURN_ON -> ACTION_CONFIRM_ON
                        GuardRequest.TURN_OFF -> ACTION_CONFIRM_OFF
                        null -> Intent.ACTION_MAIN
                    },
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun pendingIntent(context: Context, request: GuardRequest?): PendingIntent = PendingIntent.getActivity(
            context,
            request?.ordinal?.plus(1) ?: 0,
            intent(context, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

@Composable
private fun MainRoute(
    intentRequest: GuardRequest?,
    onIntentRequestTaken: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(intentRequest) {
        if (intentRequest != null) {
            viewModel.onRequest(intentRequest)
            onIntentRequestTaken()
        }
    }

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
        canAddTile = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        canAddWidget = remember { context.canPinWidget() },
        actions = viewModel,
        onOpenShizuku = { context.openShizuku() },
        onOpenNotificationSettings = { context.openNotificationSettings() },
        onAddTile = { context.requestAddTile() },
        onAddWidget = { context.requestPinWidget() },
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

private fun Context.requestAddTile() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(this, GuardTileService::class.java),
        getString(R.string.guard_title),
        Icon.createWithResource(this, R.drawable.ic_stat_guard),
        mainExecutor,
    ) { }
}

private fun Context.canPinWidget(): Boolean =
    getSystemService(AppWidgetManager::class.java)?.isRequestPinAppWidgetSupported == true

private fun Context.requestPinWidget() {
    getSystemService(AppWidgetManager::class.java)
        ?.requestPinAppWidget(ComponentName(this, GuardWidget::class.java), null, null)
}
