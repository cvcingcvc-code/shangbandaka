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
    val region: String = "", val countryCode: String = "", val administrativeOnly: Boolean = false,
    val street: String = "", val houseNumber: String = "", val addressAreas: List<String> = emptyList()
)

/** Reject misleading provider fallbacks rather than pretending a city/other-city POI matched. */
internal object PlaceSearchPolicy {
    private val municipalities = listOf("上海", "北京", "天津", "重庆")
    private val institutionSuffixes = listOf("职业技术学院", "职业学院", "技术学院", "大学", "学院", "学校", "校区")
    private val areaSuffixes = listOf("新片区", "片区", "新区", "街道", "开发区", "工业区", "区", "县", "镇", "乡", "市")
    private val numberedStreet = Regex("(路|街|道|巷|弄|胡同)\\s*([0-9]{1,8}(?:-[0-9]{1,8})?)\\s*号$")
    private val houseNumber = Regex("([0-9]{1,8}(?:-[0-9]{1,8})?)\\s*号?")
    private val numberDash = Regex("(?<=[0-9])\\s*[-‐‑‒–—−]\\s*(?=[0-9])")

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun explicitCity(normalizedQuery: String): String? =
        municipalities.firstOrNull { normalizedQuery.startsWith(it) }
            ?: Regex("^([\\p{IsHan}]{2,7}?市)").find(normalizedQuery)?.groupValues?.get(1)?.removeSuffix("市")

    fun accepts(query: String, place: PlaceCandidate): Boolean {
        if (!place.lat.isFinite() || !place.lng.isFinite() || place.lat !in -90.0..90.0 || place.lng !in -180.0..180.0)
            return false
        val addressQuery = canonicalNumberDashes(query.trim())
        val numbered = numberedStreet.find(addressQuery)
        if (numbered != null) {
            val (streetSuffix, requestedNumber) = numbered.destructured
            if (place.administrativeOnly) return false
            val beforeNumber = normalize(addressQuery.substring(0, numbered.range.first) + streetSuffix)
            val street = normalize(place.street)
            val number = houseNumber.matchEntire(canonicalNumberDashes(place.houseNumber.trim()))
                ?.groupValues?.get(1)
            if (place.street.isNotBlank() && (street.isBlank() || !beforeNumber.endsWith(street))) return false
            if (place.houseNumber.isNotBlank() && number != requestedNumber) return false
            if (street.isNotEmpty() && number != null) {
                return matchesAddressAreas(beforeNumber.removeSuffix(street), place)
            }
            // Incomplete provider fields can still be supplemented by the full display address.
        }
        val normalized = if (numbered == null) normalize(query) else normalizeNumberedAddress(addressQuery)
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
        val labelAndAddress = "${place.name} ${place.displayName}"
        val actualText = if (numbered == null) normalize(labelAndAddress) else normalizeNumberedAddress(labelAndAddress)
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

    private fun canonicalNumberDashes(value: String): String =
        numberDash.replace(Normalizer.normalize(value, Normalizer.Form.NFKC), "-")

    private fun normalizeNumberedAddress(value: String): String {
        val address = canonicalNumberDashes(value).lowercase(Locale.ROOT)
        return address.filterIndexed { index, char ->
            char.isLetterOrDigit() || char == '-' &&
                address.getOrNull(index - 1)?.isDigit() == true && address.getOrNull(index + 1)?.isDigit() == true
        }
    }

    private fun matchesAddressAreas(addressPrefix: String, place: PlaceCandidate): Boolean {
        if (explicitCity(addressPrefix) != null && place.countryCode.isNotBlank() &&
            !place.countryCode.equals("CN", ignoreCase = true)) return false

        // Compare the address components rather than relying on the provider's display order.
        // Every area supplied before the street must be present on the actual result.
        val areas = (place.addressAreas + place.region.split('，', ',') + place.city + place.country)
            .map(::normalize).filter { it.isNotEmpty() }.flatMap {
                when {
                    it in municipalities -> listOf(it, "${it}市")
                    it.endsWith("省") || it.endsWith("市") -> listOf(it, it.dropLast(1))
                    else -> listOf(it)
                }
            }.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        var prefix = addressPrefix
        while (prefix.isNotEmpty()) {
            val area = areas.firstOrNull { prefix.startsWith(it) } ?: return false
            prefix = prefix.removePrefix(area)
        }
        return true
    }
}
