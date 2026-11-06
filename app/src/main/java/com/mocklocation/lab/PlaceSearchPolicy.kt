package com.mocklocation.lab

import java.text.Normalizer
import java.util.Locale

enum class PlaceSearchSource(val label: String) {
    SYSTEM("系统地点服务"), NOMINATIM("Nominatim 联网搜索")
}

/** Names and addresses always come from the provider, never from the requested keyword. */
data class PlaceCandidate(
    val name: String, val city: String, val country: String,
    val lat: Double, val lng: Double, val displayName: String,
    val source: PlaceSearchSource,
    val region: String = "", val countryCode: String = "", val administrativeOnly: Boolean = false
)

/** Reject misleading provider fallbacks rather than pretending a city/other-city POI matched. */
internal object PlaceSearchPolicy {
    private val municipalities = listOf("上海", "北京", "天津", "重庆")
    private val institutionSuffixes = listOf("职业技术学院", "职业学院", "技术学院", "大学", "学院", "学校", "校区")
    private val areaSuffixes = listOf("新片区", "片区", "新区", "街道", "开发区", "工业区", "区", "县", "镇", "乡", "市")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun explicitCity(normalizedQuery: String): String? =
        municipalities.firstOrNull { normalizedQuery.startsWith(it) }
            ?: Regex("^([\\p{IsHan}]{2,7}?市)").find(normalizedQuery)?.groupValues?.get(1)?.removeSuffix("市")

    fun accepts(query: String, place: PlaceCandidate): Boolean {
        if (!place.lat.isFinite() || !place.lng.isFinite() || place.lat !in -90.0..90.0 || place.lng !in -180.0..180.0)
            return false
        val normalized = normalize(query)
        if (normalized.isEmpty()) return false
        val city = explicitCity(normalized)
        var term = if (city != null) normalized.removePrefix(city).removePrefix("市") else normalized
        if (city != null) {
            // A restaurant named '上海…' in Taiwan is not a Shanghai administrative address.
            val administrativeAddress = normalize("${place.city} ${place.region}")
            if (!administrativeAddress.contains(city)) return false
            if (place.countryCode.isNotBlank() && !place.countryCode.equals("CN", true)) return false
        }
        if (term.isEmpty()) return true // An explicit city query may legitimately return a city.
        if (term.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return true

        val institution = institutionSuffixes.firstOrNull { term.endsWith(it) }
        val area = areaSuffixes.firstOrNull { term.endsWith(it) }
        val distinctivePart = when {
            institution != null -> term.removeSuffix(institution)
            area != null -> term.removeSuffix(area)
            else -> term
        }
        // Generic '学校/学院' alone gives no safe unique target.
        if (distinctivePart.length < 2) return false
        // Keep the requested kind in the match. Removing "职业学院" or "新片区"
        // would let a same-stem company or shop masquerade as that institution/area.
        val actualText = normalize("${place.name} ${place.displayName}")
        if (!actualText.contains(term)) return false
        val actualName = normalize(place.name)
        val nameWithoutCity = if (city != null) actualName.removePrefix(city).removePrefix("市") else actualName
        if (institution != null) {
            if (!actualName.contains(term)) return false
            if (institutionSuffixes.none { actualName.endsWith(it) }) return false
        }
        if (area != null && !place.administrativeOnly && nameWithoutCity != term) return false
        // A precise POI request must not be fulfilled by a city-centre-only result.
        if (place.administrativeOnly && (institution != null || area == null)) return false
        return true
    }
}
