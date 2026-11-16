package com.mocklocation.lab

import java.net.URI

/** The JavaScript bridge belongs only to the bundled map, never to external pages. */
internal object MapNavigationPolicy {
    const val MAP_URL = "file:///android_asset/map.html"

    enum class Decision { LOCAL_MAP, EXTERNAL_BROWSER, BLOCK }

    fun isLocalMap(url: String?): Boolean = url == MAP_URL

    fun decide(url: String?, isMainFrame: Boolean, hasGesture: Boolean): Decision {
        if (!isMainFrame) return Decision.BLOCK
        if (isLocalMap(url)) return Decision.LOCAL_MAP
        if (!hasGesture || url.isNullOrBlank()) return Decision.BLOCK
        val target = try { URI(url) } catch (_: Exception) { return Decision.BLOCK }
        return if (target.scheme.equals("https", ignoreCase = true) &&
            !target.host.isNullOrBlank() && target.rawUserInfo == null) {
            Decision.EXTERNAL_BROWSER
        } else Decision.BLOCK
    }
}
