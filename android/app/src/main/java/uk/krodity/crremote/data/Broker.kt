package uk.krodity.crremote.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Thin client for cr-broker. Every catalogue lookup and player command is one
 * POST /api/rpc, which the broker forwards to the paired browser extension.
 *
 * The broker's own failures and the extension's failures arrive differently
 * (503/502 vs a 200 with an error field), so they are flattened here into one
 * BrokerException carrying a message that is already fit to put on screen.
 */
class Broker(private val config: () -> SettingsRepo.Config) {

    private val gson = Gson()

    private suspend fun rpc(method: String, params: Map<String, Any?> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) {
            val cfg = config()
            if (!cfg.paired) throw BrokerException("Not paired with a broker yet", recoverable = false)

            val body = JsonObject().apply {
                addProperty("method", method)
                if (cfg.host.isNotBlank()) addProperty("host", cfg.host)
                add("params", gson.toJsonTree(params))
            }.toString()

            val conn = (URL("${cfg.broker}/api/rpc").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8_000
                // Player commands answer instantly; a cold search can take a few
                // seconds while the extension refreshes its Crunchyroll token.
                readTimeout = 30_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${cfg.token}")
            }

            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

                val json = runCatching { JsonParser.parseString(text).asJsonObject }
                    .getOrElse { throw BrokerException("Broker sent an unreadable reply (HTTP $code)") }

                json.get("error")?.takeIf { !it.isJsonNull }?.let {
                    throw BrokerException(it.asString, recoverable = code != 401)
                }
                if (code == 401) throw BrokerException("Pairing token rejected", recoverable = false)
                if (code !in 200..299) throw BrokerException("Broker error (HTTP $code)")

                json.getAsJsonObject("result") ?: JsonObject()
            } catch (e: UnknownHostException) {
                throw BrokerException("Can't find ${cfg.broker.substringAfter("://").substringBefore(":")} — is Tailscale on?")
            } catch (e: SocketTimeoutException) {
                throw BrokerException("The PC didn't answer in time")
            } catch (e: BrokerException) {
                throw e
            } catch (e: Exception) {
                throw BrokerException(e.message ?: "Could not reach the broker")
            } finally {
                conn.disconnect()
            }
        }

    private inline fun <reified T> JsonObject.list(key: String): List<T> =
        getAsJsonArray(key)?.map { Gson().fromJson(it, T::class.java) } ?: emptyList()

    suspend fun hosts(): List<HostInfo> = withContext(Dispatchers.IO) {
        val cfg = config()
        if (!cfg.paired) throw BrokerException("Not paired with a broker yet", recoverable = false)
        val conn = (URL("${cfg.broker}/api/hosts").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer ${cfg.token}")
        }
        try {
            val code = conn.responseCode
            if (code == 401) throw BrokerException("Pairing token rejected", recoverable = false)
            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            JsonParser.parseString(text).asJsonObject.list<HostInfo>("hosts")
        } catch (e: BrokerException) {
            throw e
        } catch (e: UnknownHostException) {
            throw BrokerException("Can't reach the broker — is Tailscale on?")
        } catch (e: Exception) {
            throw BrokerException(e.message ?: "Could not list PCs")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun search(q: String): List<Series> = rpc("search", mapOf("q" to q)).list("series")

    /** The home rail: Crunchyroll's own "Popular" ordering. */
    suspend fun trending(n: Int = 30): List<Series> =
        rpc("browse", mapOf("sort" to "popularity", "n" to n)).list("series")

    /** Shows that just got a new episode, one row per show. */
    suspend fun newEpisodes(): List<Series> = rpc("recent").list("series")

    suspend fun seasons(seriesId: String): List<Season> =
        rpc("seasons", mapOf("seriesId" to seriesId)).list("seasons")

    suspend fun episodes(seasonId: String): List<Episode> =
        rpc("episodes", mapOf("seasonId" to seasonId)).list("episodes")

    /** Resume positions for a batch of episodes, keyed by episode id. */
    suspend fun playheads(ids: List<String>): Map<String, Playhead> {
        if (ids.isEmpty()) return emptyMap()
        val obj = rpc("playheads", mapOf("ids" to ids.joinToString(",")))
            .getAsJsonObject("playheads") ?: return emptyMap()
        val g = Gson()
        return obj.entrySet().associate { (k, v) -> k to g.fromJson(v, Playhead::class.java) }
    }

    suspend fun play(episode: Episode) {
        rpc("play", mapOf("url" to episode.url, "id" to episode.id, "slug" to episode.slug))
    }

    suspend fun status(): PlayerStatus =
        Gson().fromJson(rpc("status"), PlayerStatus::class.java) ?: PlayerStatus()

    /** @param value seconds for seek/forward/back, 0..1 for volume, else null. */
    suspend fun control(action: String, value: Double? = null): PlayerStatus {
        val r = rpc("control", buildMap {
            put("action", action)
            value?.let { put("value", it) }
        })
        return Gson().fromJson(r, PlayerStatus::class.java) ?: PlayerStatus()
    }

    suspend fun ping(): Boolean = rpc("ping").get("ok")?.asBoolean ?: false
}
