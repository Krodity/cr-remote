package uk.krodity.crremote.data

import android.net.Uri

/**
 * The one place a pairing payload is parsed.
 *
 * Both routes into the app carry the identical string — the `/pair` page's deep
 * link and the QR code drawn from it — so scanning and tapping cannot drift
 * apart:
 *
 *     crremote://pair?broker=host:8774&token=…
 */
object PairLink {

    data class Pairing(val broker: String, val token: String)

    fun parse(raw: String?): Pairing? {
        val uri = runCatching { Uri.parse(raw?.trim() ?: return null) }.getOrNull() ?: return null
        return parse(uri)
    }

    fun parse(uri: Uri?): Pairing? {
        if (uri == null || uri.scheme != "crremote" || uri.host != "pair") return null
        val broker = uri.getQueryParameter("broker")?.trim().orEmpty()
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        if (broker.isBlank() || token.isBlank()) return null
        return Pairing(broker, token)
    }
}
