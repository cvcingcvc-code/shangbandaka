package com.mocklocation.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MapNavigationPolicyTest {
    @Test fun onlyBundledMapCanRemainInWebView() {
        assertEquals(MapNavigationPolicy.Decision.LOCAL_MAP,
            MapNavigationPolicy.decide(MapNavigationPolicy.MAP_URL, true, false))
        listOf("file:///sdcard/map.html", "file:///android_asset/map.html?external=1",
            "file:///android_asset/map.html#external", "file:///android_asset/../map.html",
            "content://example/map", "javascript:alert(1)", "data:text/html,hello",
            "about:blank", "intent://example", "https://leafletjs.com").forEach { url ->
            assertFalse("$url must not use the bridge", MapNavigationPolicy.isLocalMap(url))
            assertEquals(MapNavigationPolicy.Decision.BLOCK, MapNavigationPolicy.decide(url, true, false))
        }
    }

    @Test fun gestureToHttpsMainPageOpensOutsideBridgeWebView() {
        assertEquals(MapNavigationPolicy.Decision.EXTERNAL_BROWSER,
            MapNavigationPolicy.decide("https://leafletjs.com/", true, true))
        assertEquals(MapNavigationPolicy.Decision.EXTERNAL_BROWSER,
            MapNavigationPolicy.decide("https://www.openstreetmap.org/copyright", true, true))
    }

    @Test fun subframesAndAutomaticRedirectsCannotOpenExternalPages() {
        assertEquals(MapNavigationPolicy.Decision.BLOCK,
            MapNavigationPolicy.decide("https://leafletjs.com/", false, true))
        assertEquals(MapNavigationPolicy.Decision.BLOCK,
            MapNavigationPolicy.decide("https://leafletjs.com/", true, false))
        assertEquals(MapNavigationPolicy.Decision.BLOCK,
            MapNavigationPolicy.decide(MapNavigationPolicy.MAP_URL, false, true))
    }

    @Test fun malformedAndUntrustedSchemesAreBlockedEvenWithGesture() {
        listOf(null, "", "https://", "http://leafletjs.com/", "https://user@leafletjs.com/",
            "file:///android_asset/map.html?redirect=1", "intent://map", "javascript:alert(1)",
            "content://map", "data:text/html,hello", "https://host with spaces/").forEach { url ->
            assertEquals("$url", MapNavigationPolicy.Decision.BLOCK,
                MapNavigationPolicy.decide(url, true, true))
        }
    }
}
