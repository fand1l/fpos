package com.fand1l.radioguard.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.fand1l.radioguard.radio.Snapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class GuardSettings(
    val guardOn: Boolean = false,
    val keepWifi: Boolean = true,
    val allowedSsid: String = "",
    /** Режим вимкнули, але попередній стан ще не повернуто. */
    val restorePending: Boolean = false,
    val onboardingDone: Boolean = false,
)

/**
 * Читання може кинути IOException. Його навмисно не ховаємо за типовими значеннями:
 * інакше збій диска виглядав би як «режим вимкнено».
 */
class Prefs(private val store: DataStore<Preferences>) {

    val settings: Flow<GuardSettings> = store.data.map { prefs ->
        GuardSettings(
            guardOn = prefs[GUARD_ON] ?: false,
            keepWifi = prefs[KEEP_WIFI] ?: true,
            allowedSsid = prefs[ALLOWED_SSID].orEmpty(),
            restorePending = prefs[RESTORE_PENDING] ?: false,
            onboardingDone = prefs[ONBOARDING_DONE] ?: false,
        )
    }

    suspend fun current(): GuardSettings = settings.first()

    suspend fun snapshot(): Snapshot = store.data.first().let { prefs ->
        Snapshot(
            airplaneOn = prefs[SNAP_AIRPLANE],
            mobileDataOn = prefs[SNAP_MOBILE_DATA],
            bluetoothOn = prefs[SNAP_BLUETOOTH],
            bleScanOn = prefs[SNAP_BLE_SCAN],
            nfcOn = prefs[SNAP_NFC],
            wifiOn = prefs[SNAP_WIFI],
            uwbOn = prefs[SNAP_UWB],
        )
    }

    suspend fun enableGuard(snapshot: Snapshot) {
        store.edit { prefs ->
            // Якщо попередній стан ще не повернуто, зараз усе вже вимкнене.
            // Такий знімок затер би справжній «стан до».
            if (prefs[RESTORE_PENDING] != true) {
                prefs.putOrRemove(SNAP_AIRPLANE, snapshot.airplaneOn)
                prefs.putOrRemove(SNAP_MOBILE_DATA, snapshot.mobileDataOn)
                prefs.putOrRemove(SNAP_BLUETOOTH, snapshot.bluetoothOn)
                prefs.putOrRemove(SNAP_BLE_SCAN, snapshot.bleScanOn)
                prefs.putOrRemove(SNAP_NFC, snapshot.nfcOn)
                prefs.putOrRemove(SNAP_WIFI, snapshot.wifiOn)
                prefs.putOrRemove(SNAP_UWB, snapshot.uwbOn)
            }
            prefs[GUARD_ON] = true
            prefs[RESTORE_PENDING] = false
        }
    }

    suspend fun disableGuard() {
        store.edit { prefs ->
            if (prefs[GUARD_ON] == true) prefs[RESTORE_PENDING] = true
            prefs[GUARD_ON] = false
        }
    }

    suspend fun restoreDone() {
        store.edit { it[RESTORE_PENDING] = false }
    }

    suspend fun setKeepWifi(keep: Boolean) {
        store.edit { it[KEEP_WIFI] = keep }
    }

    suspend fun setAllowedSsid(ssid: String) {
        store.edit { it[ALLOWED_SSID] = ssid.trim() }
    }

    suspend fun setOnboardingDone() {
        store.edit { it[ONBOARDING_DONE] = true }
    }

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<Boolean>, value: Boolean?) {
        if (value == null) remove(key) else this[key] = value
    }

    companion object {
        /** Викликати один раз на процес: два сховища на одному файлі DataStore забороняє. */
        fun create(context: Context): Prefs = Prefs(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.applicationContext.preferencesDataStoreFile("settings") },
            ),
        )

        private val GUARD_ON = booleanPreferencesKey("guard_on")
        private val KEEP_WIFI = booleanPreferencesKey("keep_wifi")
        private val ALLOWED_SSID = stringPreferencesKey("allowed_ssid")
        private val RESTORE_PENDING = booleanPreferencesKey("restore_pending")
        private val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        private val SNAP_AIRPLANE = booleanPreferencesKey("snap_airplane")
        private val SNAP_MOBILE_DATA = booleanPreferencesKey("snap_mobile_data")
        private val SNAP_BLUETOOTH = booleanPreferencesKey("snap_bluetooth")
        private val SNAP_BLE_SCAN = booleanPreferencesKey("snap_ble_scan")
        private val SNAP_NFC = booleanPreferencesKey("snap_nfc")
        private val SNAP_WIFI = booleanPreferencesKey("snap_wifi")
        private val SNAP_UWB = booleanPreferencesKey("snap_uwb")
    }
}
