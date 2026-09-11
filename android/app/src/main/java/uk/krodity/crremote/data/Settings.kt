package uk.krodity.crremote.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("cr-remote")

/** Broker address + pairing token. Written once at pairing, read on every call. */
class SettingsRepo(private val context: Context) {

    private val kBroker = stringPreferencesKey("broker")
    private val kToken = stringPreferencesKey("token")
    private val kHost = stringPreferencesKey("host")

    data class Config(val broker: String = "", val token: String = "", val host: String = "") {
        val paired: Boolean get() = broker.isNotBlank() && token.isNotBlank()
    }

    val config: Flow<Config> = context.dataStore.data.map { p ->
        Config(p[kBroker] ?: "", p[kToken] ?: "", p[kHost] ?: "")
    }

    suspend fun pair(broker: String, token: String) {
        context.dataStore.edit {
            it[kBroker] = normalise(broker)
            it[kToken] = token
        }
    }

    suspend fun selectHost(hostId: String) {
        context.dataStore.edit { it[kHost] = hostId }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    /** Accept "aepc", "aepc:8774" or a full URL and always end up with a base URL. */
    private fun normalise(raw: String): String {
        var s = raw.trim().removeSuffix("/")
        if (s.isEmpty()) return s
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val afterScheme = s.substringAfter("://")
        if (!afterScheme.contains(":")) s = "$s:8774"
        return s
    }
}
