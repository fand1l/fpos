package com.fand1l.radioguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.fand1l.radioguard.R
import com.fand1l.radioguard.guard.GuardRequest
import com.fand1l.radioguard.guard.GuardSummary
import com.fand1l.radioguard.guard.JournalEntry
import com.fand1l.radioguard.radio.Check
import com.fand1l.radioguard.radio.CheckStatus
import com.fand1l.radioguard.radio.Radio
import com.fand1l.radioguard.radio.labelRes
import com.fand1l.radioguard.shizuku.PrivilegedShell
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val QuietGreen = Color(0xFF2E7D32)
private val QuietGreenDark = Color(0xFF66BB6A)
private val LoudRed = Color(0xFFC62828)
private val LoudRedDark = Color(0xFFEF5350)

// Довгий стан (назва чужої мережі) переноситься сам і не стискає назву радіо.
private val StateTextMaxWidth = 180.dp

@Composable
fun MainScreen(
    state: MainUiState,
    notificationsAllowed: Boolean,
    canAddTile: Boolean,
    canAddWidget: Boolean,
    actions: MainActions,
    onOpenShizuku: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onAddTile: () -> Unit,
    onAddWidget: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !state.loaded -> Unit
            !state.onboardingDone -> Onboarding(onDone = actions::onOnboardingDone)
            else -> Dashboard(
                state = state,
                notificationsAllowed = notificationsAllowed,
                canAddTile = canAddTile,
                canAddWidget = canAddWidget,
                actions = actions,
                onOpenShizuku = onOpenShizuku,
                onOpenNotificationSettings = onOpenNotificationSettings,
                onAddTile = onAddTile,
                onAddWidget = onAddWidget,
            )
        }
    }

    if (!state.loaded) return
    when (state.confirmation) {
        GuardRequest.TURN_ON -> ConfirmOnDialog(state, actions)
        GuardRequest.TURN_OFF -> ConfirmOffDialog(state, actions)
        // Режим уже такий, як просили (наприклад, плитку натиснули двічі): питати нема про що.
        null -> if (state.request != null) LaunchedEffect(state.request) { actions.onDismissRequest() }
    }
}

@Composable
private fun ConfirmOnDialog(state: MainUiState, actions: MainActions) {
    ChoiceDialog(
        title = stringResource(R.string.confirm_on_title),
        onDismiss = actions::onDismissRequest,
        body = {
            val silence = state.toSilence
            val exceptions = state.allowable.filter { it in state.allowed }
            DialogText(
                if (silence.isEmpty()) {
                    stringResource(R.string.confirm_on_nothing)
                } else {
                    stringResource(R.string.confirm_on_silence, names(silence))
                },
            )
            if (exceptions.isNotEmpty()) DialogText(stringResource(R.string.confirm_on_allowed, names(exceptions)))
            if (state.keepWifi) DialogText(stringResource(R.string.confirm_on_wifi))
            if (!state.shizukuReady) {
                DialogText(stringResource(R.string.confirm_on_no_shell), color = MaterialTheme.colorScheme.error)
            }
        },
    ) {
        Button(
            onClick = actions::onConfirmOn,
            enabled = state.shizukuReady,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.confirm_on_yes)) }
        TextButton(onClick = actions::onDismissRequest, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.confirm_cancel))
        }
    }
}

@Composable
private fun ConfirmOffDialog(state: MainUiState, actions: MainActions) {
    val restore = state.toRestore
    ChoiceDialog(
        title = stringResource(R.string.confirm_off_title),
        onDismiss = actions::onDismissRequest,
        body = {
            DialogText(stringResource(R.string.confirm_off_text))
            if (restore.isNotEmpty()) DialogText(stringResource(R.string.confirm_off_restore_list, names(restore)))
        },
    ) {
        if (restore.isEmpty()) {
            OutlinedButton(onClick = { actions.onConfirmOff(restore = true) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.confirm_off_yes))
            }
        } else {
            // Безпечніший варіант — першим.
            OutlinedButton(onClick = { actions.onConfirmOff(restore = false) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.confirm_off_keep))
            }
            OutlinedButton(onClick = { actions.onConfirmOff(restore = true) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.confirm_off_restore))
            }
        }
        TextButton(onClick = actions::onDismissRequest, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.confirm_cancel))
        }
    }
}

/** Діалог із кнопками на всю ширину, одна під одною: довгі підписи не ламаються. */
@Composable
private fun ChoiceDialog(
    title: String,
    onDismiss: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
                body()
                Spacer(Modifier.height(4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = buttons)
            }
        }
    }
}

@Composable
private fun DialogText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun names(radios: List<Radio>): String = radios.map { stringResource(it.labelRes) }.joinToString()

@Composable
private fun Dashboard(
    state: MainUiState,
    notificationsAllowed: Boolean,
    canAddTile: Boolean,
    canAddWidget: Boolean,
    actions: MainActions,
    onOpenShizuku: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onAddTile: () -> Unit,
    onAddWidget: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        item {
            GuardCard(state) { on -> actions.onRequest(if (on) GuardRequest.TURN_ON else GuardRequest.TURN_OFF) }
        }

        if (!state.shizukuReady) {
            item { ShizukuCard(state.shizuku, onGrant = actions::onShizukuGrant, onOpen = onOpenShizuku) }
        }
        if (!notificationsAllowed) {
            item {
                NoticeCard(
                    title = stringResource(R.string.notifications_title),
                    text = stringResource(R.string.notifications_text),
                    button = stringResource(R.string.notifications_allow),
                    onClick = onOpenNotificationSettings,
                )
            }
        }
        if (state.restorePending && !state.guardOn) {
            item {
                NoticeCard(
                    title = stringResource(R.string.restore_title),
                    text = stringResource(R.string.restore_text),
                    button = stringResource(R.string.restore_retry),
                    onClick = actions::onRestoreRetry,
                    enabled = state.shizukuReady,
                )
            }
        }

        item { SectionTitle(stringResource(R.string.section_state)) }
        item {
            Card {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    state.checks.forEach { CheckRow(it) }
                }
            }
        }

        item { SectionTitle(stringResource(R.string.section_settings)) }
        item {
            SettingsCard(
                keepWifi = state.keepWifi,
                allowedSsid = state.allowedSsid,
                onKeepWifiChange = actions::onKeepWifiChange,
                onSsidSave = actions::onSsidSave,
            )
        }

        item { SectionTitle(stringResource(R.string.section_exceptions)) }
        item { ExceptionsCard(state.allowable, state.allowed, actions::onAllowChange) }

        item { SectionTitle(stringResource(R.string.section_quick)) }
        item { QuickAccessCard(canAddTile, canAddWidget, onAddTile, onAddWidget) }

        item { SectionTitle(stringResource(R.string.section_journal)) }
        if (state.journal.isEmpty()) {
            item { Hint(stringResource(R.string.journal_empty)) }
        } else {
            items(state.journal, key = { it.id }) { JournalRow(it) }
        }
        item { Hint(stringResource(R.string.journal_note)) }
    }
}

@Composable
private fun GuardCard(state: MainUiState, onToggle: (Boolean) -> Unit) {
    val summary = state.summary
    val alarm = summary == GuardSummary.VIOLATION || summary == GuardSummary.NO_SHELL
    val colors = when {
        alarm -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        summary == GuardSummary.OK -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        else -> CardDefaults.cardColors()
    }
    Card(colors = colors) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.guard_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                val exceptions = state.allowedAndOn
                Text(
                    text = when {
                        summary == GuardSummary.OK && exceptions.isNotEmpty() ->
                            stringResource(R.string.guard_ok_exceptions, names(exceptions))
                        else -> stringResource(
                            when (summary) {
                                GuardSummary.OFF ->
                                    if (state.shizukuReady) R.string.guard_off else R.string.guard_needs_shell
                                GuardSummary.STARTING -> R.string.guard_starting
                                GuardSummary.OK -> R.string.guard_ok
                                GuardSummary.VIOLATION -> R.string.guard_violation
                                GuardSummary.NO_SHELL -> R.string.guard_no_shell
                            },
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = state.guardOn, onCheckedChange = onToggle, enabled = state.canToggle)
        }
    }
}

@Composable
private fun ShizukuCard(status: PrivilegedShell.Status, onGrant: () -> Unit, onOpen: () -> Unit) {
    val text = when (status) {
        PrivilegedShell.Status.NOT_INSTALLED -> R.string.shizuku_not_installed
        PrivilegedShell.Status.NOT_RUNNING -> R.string.shizuku_not_running
        PrivilegedShell.Status.NO_PERMISSION -> R.string.shizuku_no_permission
        PrivilegedShell.Status.READY -> R.string.shizuku_ready
    }
    when (status) {
        PrivilegedShell.Status.NO_PERMISSION -> NoticeCard(
            title = stringResource(R.string.shizuku_title),
            text = stringResource(text),
            button = stringResource(R.string.shizuku_grant),
            onClick = onGrant,
        )
        PrivilegedShell.Status.NOT_RUNNING -> NoticeCard(
            title = stringResource(R.string.shizuku_title),
            text = stringResource(text),
            button = stringResource(R.string.shizuku_open),
            onClick = onOpen,
        )
        else -> NoticeCard(
            title = stringResource(R.string.shizuku_title),
            text = stringResource(text),
            button = null,
            onClick = {},
        )
    }
}

@Composable
private fun NoticeCard(
    title: String,
    text: String,
    button: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            if (button != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onClick, enabled = enabled) { Text(button) }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun CheckRow(check: Check) {
    val dark = isSystemInDarkTheme()
    val color = when (check.status) {
        CheckStatus.OK -> if (dark) QuietGreenDark else QuietGreen
        CheckStatus.VIOLATION -> if (dark) LoudRedDark else LoudRed
        CheckStatus.UNKNOWN -> MaterialTheme.colorScheme.outline
        CheckStatus.ALLOWED -> MaterialTheme.colorScheme.primary
    }
    // Один рядок для читача екрана: «Bluetooth, вимкнено».
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(check.radio.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = check.stateText(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (check.status == CheckStatus.VIOLATION) FontWeight.Bold else FontWeight.Normal,
            color = if (check.status == CheckStatus.VIOLATION) color else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = StateTextMaxWidth),
        )
    }
}

@Composable
private fun Check.stateText(): String = when {
    status == CheckStatus.ALLOWED -> stringResource(
        when (on) {
            true -> R.string.check_allowed_on
            false -> R.string.check_allowed_off
            null -> R.string.check_allowed
        },
    )
    status == CheckStatus.UNKNOWN -> stringResource(R.string.check_unknown)
    radio == Radio.CELLULAR -> stringResource(
        if (status == CheckStatus.OK) R.string.check_cellular_off else R.string.check_cellular_on,
    )
    radio == Radio.WIFI && wantedOn -> stringResource(
        if (status == CheckStatus.OK) R.string.check_wifi_allowed else R.string.check_wifi_missing,
    )
    radio == Radio.WIFI_NETWORK -> when {
        status == CheckStatus.VIOLATION -> stringResource(R.string.check_network_wrong, detail.orEmpty())
        detail != null -> stringResource(R.string.check_network_ok, detail)
        else -> stringResource(R.string.check_network_none)
    }
    status == CheckStatus.OK -> stringResource(R.string.check_off)
    else -> stringResource(R.string.check_on)
}

@Composable
private fun SettingsCard(
    keepWifi: Boolean,
    allowedSsid: String,
    onKeepWifiChange: (Boolean) -> Unit,
    onSsidSave: (String) -> Unit,
) {
    // Чернетка живе тут, доки її не збережено; збережене значення приходить зі стану.
    var draft by rememberSaveable(allowedSsid) { mutableStateOf(allowedSsid) }
    val focusManager = LocalFocusManager.current
    val save = {
        onSsidSave(draft)
        focusManager.clearFocus()
    }

    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.setting_keep_wifi),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Hint(stringResource(R.string.setting_keep_wifi_hint))
                }
                Spacer(Modifier.width(16.dp))
                Switch(checked = keepWifi, onCheckedChange = onKeepWifiChange)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.setting_ssid)) },
                supportingText = { Text(stringResource(R.string.setting_ssid_hint)) },
                singleLine = true,
                enabled = keepWifi,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = save,
                enabled = keepWifi && draft.trim() != allowedSsid,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.setting_ssid_save))
            }
        }
    }
}

@Composable
private fun ExceptionsCard(radios: List<Radio>, allowed: Set<Radio>, onAllowChange: (Radio, Boolean) -> Unit) {
    Card {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            Hint(
                text = stringResource(R.string.exceptions_hint),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            radios.forEach { radio ->
                val isAllowed = radio in allowed
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .toggleable(value = isAllowed, role = Role.Switch) { onAllowChange(radio, it) }
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = stringResource(radio.labelRes), style = MaterialTheme.typography.bodyLarge)
                        Hint(
                            stringResource(
                                if (isAllowed) R.string.exception_allowed else R.string.exception_blocked,
                            ),
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(checked = isAllowed, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun QuickAccessCard(
    canAddTile: Boolean,
    canAddWidget: Boolean,
    onAddTile: () -> Unit,
    onAddWidget: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.quick_hint), style = MaterialTheme.typography.bodyMedium)
            if (canAddTile) {
                OutlinedButton(onClick = onAddTile, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.quick_add_tile))
                }
            } else {
                Hint(stringResource(R.string.quick_tile_manual))
            }
            if (canAddWidget) {
                OutlinedButton(onClick = onAddWidget, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.quick_add_widget))
                }
            }
        }
    }
}

@Composable
private fun JournalRow(entry: JournalEntry) {
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()) }
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatter.format(Instant.ofEpochMilli(entry.timeMillis)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(text = entry.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Onboarding(onDone: () -> Unit) {
    val points = listOf(
        R.string.onboarding_1,
        R.string.onboarding_2,
        R.string.onboarding_3,
        R.string.onboarding_4,
        R.string.onboarding_5,
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        points.forEach { point ->
            Text(text = stringResource(point), style = MaterialTheme.typography.bodyLarge)
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_done))
        }
    }
}
