package com.aashishgodambe.arcana.core.ai.pricing

import android.os.SystemClock
import android.util.Log
import com.aashishgodambe.arcana.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** eBay returned HTTP 429 — the provider maps this to [PriceResult.RateLimited][com.aashishgodambe.arcana.core.ai.model.PriceResult.RateLimited]. */
class EbayRateLimitException : IOException("eBay rate limited")

/**
 * Thin client for the eBay **Browse** API: mints and caches an OAuth *application* token
 * (client-credentials grant), then runs `item_summary/search` and normalizes the response to pure
 * [EbayListing]s. Credentials come from `BuildConfig` (sourced from the gitignored `ebay.properties`).
 *
 * Only the network + JSON parse live here (impure); the query and median math are in [EbayPriceMath]. The
 * token is cached across calls (eBay app tokens last ~2 h) behind a [Mutex] so concurrent price fetches
 * don't each mint one. No HTTP dependency — [HttpURLConnection] is enough for two endpoints.
 */
@Singleton
class EbayBrowseClient @Inject constructor() {

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiryElapsedMs: Long = 0L

    /** False when no keyset was supplied at build time (fresh clone) — the provider then defers to the mock. */
    val isConfigured: Boolean
        get() = BuildConfig.EBAY_CLIENT_ID.isNotBlank() && BuildConfig.EBAY_CLIENT_SECRET.isNotBlank()

    /** Active fixed-price listings for [query], normalized. Empty when nothing matched; throws on HTTP error. */
    suspend fun searchActiveListings(query: String, limit: Int = 20): List<EbayListing> =
        withContext(Dispatchers.IO) {
            val token = accessToken() ?: return@withContext emptyList()
            val url = "$BROWSE_SEARCH?q=${enc(query)}&limit=$limit&filter=${enc("buyingOptions:{FIXED_PRICE}")}"
            val (code, body) = request(
                "GET", url,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "X-EBAY-C-MARKETPLACE-ID" to MARKETPLACE,
                ),
            )
            when {
                code == 429 -> throw EbayRateLimitException()
                code !in 200..299 -> throw IOException("eBay search HTTP $code")
                else -> parseListings(body)
            }
        }

    /** Cached client-credentials token, minting a fresh one when absent or within a minute of expiry. */
    private suspend fun accessToken(): String? = tokenMutex.withLock {
        if (!isConfigured) return null
        val now = SystemClock.elapsedRealtime()
        cachedToken?.let { if (now < tokenExpiryElapsedMs) return it }

        val basic = Base64.getEncoder()
            .encodeToString("${BuildConfig.EBAY_CLIENT_ID}:${BuildConfig.EBAY_CLIENT_SECRET}".toByteArray())
        val (code, body) = request(
            "POST", OAUTH_TOKEN,
            headers = mapOf(
                "Authorization" to "Basic $basic",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
            body = "grant_type=client_credentials&scope=${enc(SCOPE)}",
        )
        if (code !in 200..299) {
            Log.w(TAG, "eBay token HTTP $code")
            return null
        }
        val json = JSONObject(body)
        val token = json.optString("access_token").ifBlank { return null }
        val expiresInSec = json.optLong("expires_in", 7200L)
        cachedToken = token
        tokenExpiryElapsedMs = now + (expiresInSec - 60).coerceAtLeast(60) * 1000L
        token
    }

    private fun parseListings(body: String): List<EbayListing> {
        val summaries = JSONObject(body).optJSONArray("itemSummaries") ?: return emptyList()
        return (0 until summaries.length()).mapNotNull { i ->
            val item = summaries.optJSONObject(i) ?: return@mapNotNull null
            val price = item.optJSONObject("price") ?: return@mapNotNull null
            val value = price.optString("value").toDoubleOrNull() ?: return@mapNotNull null
            EbayListing(
                title = item.optString("title"),
                priceCents = Math.round(value * 100).toInt(),
                currency = price.optString("currency", "USD"),
                condition = item.optString("condition").ifBlank { null },
                sellerFeedbackPct = item.optJSONObject("seller")
                    ?.optString("feedbackPercentage")?.toFloatOrNull(),
                itemWebUrl = item.optString("itemWebUrl").ifBlank { null },
            )
        }
    }

    /** One HTTP round-trip → (status, body). Reads the error stream on failure so callers see eBay's reason. */
    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: String? = null,
    ): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val TAG = "EbayBrowse"
        const val OAUTH_TOKEN = "https://api.ebay.com/identity/v1/oauth2/token"
        const val BROWSE_SEARCH = "https://api.ebay.com/buy/browse/v1/item_summary/search"
        const val MARKETPLACE = "EBAY_US"
        const val SCOPE = "https://api.ebay.com/oauth/api_scope"
    }
}
