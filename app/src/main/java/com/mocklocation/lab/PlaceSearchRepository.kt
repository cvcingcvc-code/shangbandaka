package com.mocklocation.lab

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal enum class PlaceSearchState { RESULTS, EMPTY, UNAVAILABLE, TIMEOUT, NETWORK_ERROR, ERROR, BUSY }
internal data class PlaceSearchOutcome(
    val source: PlaceSearchSource, val state: PlaceSearchState,
    val places: List<PlaceCandidate> = emptyList(), val received: Int = 0,
    val detail: String = "", val cached: Boolean = false
)

/** System geocoding first; online fallback is requested explicitly by the user. */
internal class PlaceSearchRepository(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    inner class Request internal constructor(
        private val source: PlaceSearchSource, private val callback: (PlaceSearchOutcome) -> Unit
    ) {
        private val gate = PlaceSearchCompletionGate(SystemClock.elapsedRealtime(), SEARCH_TIMEOUT_MS)
        @Volatile var future: Future<*>? = null
        @Volatile var connection: HttpURLConnection? = null
        private val timeout = Runnable {
            complete(PlaceSearchOutcome(source, PlaceSearchState.TIMEOUT))
        }
        init { main.postDelayed(timeout, SEARCH_TIMEOUT_MS) }
        fun isActive(): Boolean {
            if (gate.isActive() && gate.expired(SystemClock.elapsedRealtime()))
                complete(PlaceSearchOutcome(source, PlaceSearchState.TIMEOUT))
            return gate.isActive()
        }
        fun complete(result: PlaceSearchOutcome) {
            val delivery = when (gate.complete(SystemClock.elapsedRealtime())) {
                PlaceSearchCompletionGate.Completion.IGNORE -> return
                PlaceSearchCompletionGate.Completion.TIMED_OUT -> PlaceSearchOutcome(source, PlaceSearchState.TIMEOUT)
                PlaceSearchCompletionGate.Completion.ACCEPT -> result
            }
            main.removeCallbacks(timeout)
            if (delivery.state == PlaceSearchState.TIMEOUT) stopWork()
            main.post { if (!gate.isCancelled()) callback(delivery) }
        }
        fun cancel() {
            gate.cancel()
            main.removeCallbacks(timeout)
            stopWork()
        }
        private fun stopWork() {
            future?.cancel(true)
            // Cancelling a result must not crash the UI if a backend close fails.
            runCatching { connection?.disconnect() }
        }
    }

    fun searchSystem(query: String, callback: (PlaceSearchOutcome) -> Unit): Request {
        val request = Request(PlaceSearchSource.SYSTEM, callback)
        try {
            systemWorkers.purge()
            request.future = systemWorkers.submit {
                if (!request.isActive()) return@submit
                try {
                    if (!Geocoder.isPresent()) {
                        request.complete(PlaceSearchOutcome(PlaceSearchSource.SYSTEM, PlaceSearchState.UNAVAILABLE))
                        return@submit
                    }
                    val geocoder = Geocoder(app, Locale.SIMPLIFIED_CHINESE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder.getFromLocationName(query, 5, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (!request.isActive()) return
                                try { request.complete(systemResult(query, addresses)) }
                                catch (e: Exception) {
                                    request.complete(PlaceSearchOutcome(PlaceSearchSource.SYSTEM, PlaceSearchState.ERROR,
                                        detail = e.javaClass.simpleName))
                                }
                            }
                            override fun onError(errorMessage: String?) {
                                // Provider error text may contain the requested address; keep it out of logs.
                                request.complete(PlaceSearchOutcome(PlaceSearchSource.SYSTEM, PlaceSearchState.ERROR,
                                    detail = "系统地点服务返回错误"))
                            }
                        })
                    } else {
                        @Suppress("DEPRECATION")
                        val addresses = geocoder.getFromLocationName(query, 5).orEmpty()
                        if (request.isActive()) request.complete(systemResult(query, addresses))
                    }
                } catch (e: IOException) {
                    request.complete(PlaceSearchOutcome(PlaceSearchSource.SYSTEM, PlaceSearchState.NETWORK_ERROR,
                        detail = e.javaClass.simpleName))
                } catch (e: Exception) {
                    request.complete(PlaceSearchOutcome(PlaceSearchSource.SYSTEM, PlaceSearchState.ERROR,
                        detail = e.javaClass.simpleName))
                }
            }
        } catch (_: RejectedExecutionException) {
            request.complete(PlaceSearchOutcome(PlaceSearchSource.SYSTEM, PlaceSearchState.BUSY))
        }
        return request
    }

    fun searchOnline(query: String, callback: (PlaceSearchOutcome) -> Unit): Request {
        val request = Request(PlaceSearchSource.NOMINATIM, callback)
        val key = query.trim()
        cached(key)?.let { request.complete(it.copy(cached = true)); return request }
        try {
            onlineWorker.purge()
            request.future = onlineWorker.submit {
                if (!request.isActive()) return@submit
                var conn: HttpURLConnection? = null
                try {
                    // One process-wide serial queue; no per-keystroke calls or parallel retries.
                    val wait = ONLINE_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastOnlineAt)
                    if (wait > 0L) Thread.sleep(wait)
                    if (!request.isActive()) return@submit
                    lastOnlineAt = SystemClock.elapsedRealtime()
                    val url = "https://nominatim.openstreetmap.org/search?q=" + URLEncoder.encode(key, "UTF-8") +
                        "&format=jsonv2&limit=8&accept-language=zh-CN&addressdetails=1"
                    conn = URL(url).openConnection() as HttpURLConnection
                    request.connection = conn
                    if (!request.isActive()) return@submit
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "MockLocationLab (https://github.com/cvcingcvc-code/shangbandaka)")
                    val code = conn.responseCode
                    if (code != 200) {
                        request.complete(PlaceSearchOutcome(PlaceSearchSource.NOMINATIM, PlaceSearchState.NETWORK_ERROR,
                            detail = "HTTP $code"))
                        return@submit
                    }
                    val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    if (!request.isActive()) return@submit
                    val arr = JSONArray(body)
                    val places = (0 until arr.length()).mapNotNull { i ->
                        val item = arr.getJSONObject(i)
                        val address = item.optJSONObject("address")
                        fun part(field: String) = address?.optString(field).orEmpty()
                        val city = listOf("city", "town", "village", "municipality", "county", "state")
                            .map(::part).firstOrNull { it.isNotBlank() } ?: "城市未标注"
                        val region = listOf("state", "state_district", "county", "city", "municipality")
                            .map(::part).filter { it.isNotBlank() }.distinct().joinToString("，")
                        val display = item.optString("display_name")
                        val name = item.optString("name").ifBlank { display.substringBefore(',') }
                        if (name.isBlank()) return@mapNotNull null
                        val kind = item.optString("addresstype").ifBlank { item.optString("type") }
                        PlaceCandidate(name, city, part("country").ifBlank { "国家未标注" },
                            item.optDouble("lat", Double.NaN), item.optDouble("lon", Double.NaN), display,
                            PlaceSearchSource.NOMINATIM, region, part("country_code"), kind in ADMINISTRATIVE_TYPES)
                    }.filter { PlaceSearchPolicy.accepts(key, it) }.distinctBy { listOf(it.name, it.lat, it.lng) }
                    val result = PlaceSearchOutcome(PlaceSearchSource.NOMINATIM,
                        if (places.isEmpty()) PlaceSearchState.EMPTY else PlaceSearchState.RESULTS,
                        places, arr.length())
                    remember(key, result)
                    request.complete(result)
                } catch (_: InterruptedException) {
                    // An explicitly cancelled request is silent; it must not replace a new search.
                } catch (e: IOException) {
                    request.complete(PlaceSearchOutcome(PlaceSearchSource.NOMINATIM, PlaceSearchState.NETWORK_ERROR,
                        detail = e.javaClass.simpleName))
                } catch (e: Exception) {
                    request.complete(PlaceSearchOutcome(PlaceSearchSource.NOMINATIM, PlaceSearchState.ERROR,
                        detail = e.javaClass.simpleName))
                } finally {
                    conn?.disconnect()
                    request.connection = null
                }
            }
        } catch (_: RejectedExecutionException) {
            request.complete(PlaceSearchOutcome(PlaceSearchSource.NOMINATIM, PlaceSearchState.BUSY))
        }
        return request
    }

    private fun systemResult(query: String, addresses: List<Address>): PlaceSearchOutcome {
        val places = addresses.mapNotNull { address ->
            if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null
            val lines = (0..address.maxAddressLineIndex).mapNotNull { address.getAddressLine(it) }.filter { it.isNotBlank() }
            val name = address.featureName?.takeIf { it.isNotBlank() } ?: lines.firstOrNull() ?: return@mapNotNull null
            val administrativeParts = listOfNotNull(address.adminArea, address.subAdminArea, address.locality, address.subLocality)
            val administrative = administrativeParts.any { PlaceSearchPolicy.normalize(name) == PlaceSearchPolicy.normalize(it) }
            PlaceCandidate(name, address.locality ?: address.subAdminArea ?: address.adminArea ?: "城市未标注",
                address.countryName ?: "国家未标注", address.latitude, address.longitude,
                lines.joinToString("，").ifBlank { administrativeParts.distinct().joinToString("，") },
                PlaceSearchSource.SYSTEM, administrativeParts.distinct().joinToString("，"), address.countryCode.orEmpty(), administrative)
        }.filter { PlaceSearchPolicy.accepts(query, it) }.distinctBy { listOf(it.name, it.lat, it.lng) }
        return PlaceSearchOutcome(PlaceSearchSource.SYSTEM,
            if (places.isEmpty()) PlaceSearchState.EMPTY else PlaceSearchState.RESULTS, places, addresses.size)
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 9000L
        private const val ONLINE_INTERVAL_MS = 1100L
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private val ADMINISTRATIVE_TYPES = setOf("administrative", "country", "state", "city", "town", "county", "region", "municipality")
        private val systemWorkers = ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2))
        private val onlineWorker = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(2))
        private var lastOnlineAt = -ONLINE_INTERVAL_MS
        private val onlineCache = object : LinkedHashMap<String, Pair<Long, PlaceSearchOutcome>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, PlaceSearchOutcome>>?): Boolean = size > 16
        }
        @Synchronized private fun cached(query: String): PlaceSearchOutcome? {
            val entry = onlineCache[query] ?: return null
            if (SystemClock.elapsedRealtime() - entry.first >= CACHE_TTL_MS) { onlineCache.remove(query); return null }
            return entry.second
        }
        @Synchronized private fun remember(query: String, result: PlaceSearchOutcome) {
            onlineCache[query] = SystemClock.elapsedRealtime() to result
        }
    }
}
