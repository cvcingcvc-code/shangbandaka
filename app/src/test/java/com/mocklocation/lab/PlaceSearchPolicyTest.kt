package com.mocklocation.lab

import org.junit.Assert.*
import org.junit.Test

class PlaceSearchPolicyTest {
    private fun place(name: String, city: String, region: String, countryCode: String = "CN",
                      address: String = name, administrative: Boolean = false) =
        PlaceCandidate(name, city, "country", 31.0, 121.0, address, PlaceSearchSource.NOMINATIM,
            region, countryCode, administrative)

    @Test fun shanghaiKeywordCannotMatchTaiwanRestaurantWithShanghaiInItsName() {
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工", place("上海湯包", "台北市", "臺灣", "TW")))
    }
    @Test fun explicitShanghaiRejectsShenzhenBankAndKunshanMetro() {
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工", place("浦发银行", "深圳市", "广东省")))
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工", place("地铁11号线", "昆山市", "江苏省")))
    }
    @Test fun specificSchoolCannotBecomeShanghaiCityCentreOrUnrelatedShanghaiPoi() {
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工职业学院", place("上海市", "上海市", "上海市", administrative = true)))
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工", place("浦发银行", "上海市", "上海市")))
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工职业学院", place("现代化工区", "上海市", "上海市", administrative = true)))
    }
    @Test fun schoolRequiresActualProviderNameOrAddressAndShanghaiRegion() {
        assertTrue(PlaceSearchPolicy.accepts("上海现代化工职业学院",
            place("上海现代化工职业学院", "金山区", "上海市", address = "上海市金山区龙胜路1097号")))
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工职业学院",
            place("上海市", "上海市", "上海市", address = "上海市")))
    }
    @Test fun administrativeAreaQueryCanReturnItsActualAreaButNotJustTheCity() {
        assertTrue(PlaceSearchPolicy.accepts("上海临港新片区", place("临港新片区", "浦东新区", "上海市", administrative = true)))
        assertFalse(PlaceSearchPolicy.accepts("上海临港新片区", place("上海市", "上海市", "上海市", administrative = true)))
    }
    @Test fun cityOnlyAndEnglishSearchRemainPossibleWithoutInventingNames() {
        assertTrue(PlaceSearchPolicy.accepts("上海", place("上海市", "上海市", "上海市", administrative = true)))
        assertTrue(PlaceSearchPolicy.accepts("Tokyo Tower", place("東京タワー", "港区", "東京都", "JP")))
    }
    @Test fun whitespaceWidthAndCaseNormalizationDoNotAlterThePlaceMeaning() {
        assertTrue(PlaceSearchPolicy.accepts(" 上海市 现代化工职业学院 ",
            place("上海现代化工职业学院", "金山区", "上海市")))
        assertEquals("abc123", PlaceSearchPolicy.normalize(" ＡＢＣ １２３ "))
    }
    @Test fun invalidCoordinatesAndAmbiguousGenericQueryAreRejected() {
        assertFalse(PlaceSearchPolicy.accepts("上海学院", place("学院", "上海市", "上海市")))
        assertFalse(PlaceSearchPolicy.accepts("上海", place("上海", "上海市", "上海市").copy(lat = Double.NaN)))
    }
    @Test fun anExplicitCollegeQueryCannotAcceptASameStemCompanyOrShop() {
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工职业学院",
            place("现代化工有限公司", "金山区", "上海市")))
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工职业学院",
            place("现代化工商店", "金山区", "上海市")))
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工职业学院",
            place("现代化工职业学院商店", "金山区", "上海市")))
        assertFalse(PlaceSearchPolicy.accepts("上海现代化工职业学院",
            place("便利店", "金山区", "上海市", address = "上海现代化工职业学院附近")))
    }
    @Test fun anExplicitAreaCannotAcceptASameStemCompanyOrAddressMention() {
        assertFalse(PlaceSearchPolicy.accepts("上海临港新片区",
            place("临港科技公司", "浦东新区", "上海市")))
        assertFalse(PlaceSearchPolicy.accepts("上海临港新片区",
            place("临港新片区商店", "浦东新区", "上海市")))
        assertFalse(PlaceSearchPolicy.accepts("上海临港新片区",
            place("便利店", "浦东新区", "上海市", address = "上海临港新片区某路")))
        assertTrue(PlaceSearchPolicy.accepts("上海临港新片区",
            place("临港新片区", "浦东新区", "上海市", administrative = false)))
    }

    @Test fun fullAddressMatchesStructuredFieldsInEitherDisplayOrder() {
        val address = place("上海环球金融中心", "上海市", "上海市",
            address = "上海环球金融中心, 100, 世纪大道, 陆家嘴街道, 浦东新区, 上海市, 中国").copy(
            street = "世纪大道", houseNumber = "100", addressAreas = listOf("浦东新区", "陆家嘴街道"))
        assertTrue(PlaceSearchPolicy.accepts("上海市浦东新区世纪大道100号", address))
        assertTrue(PlaceSearchPolicy.accepts("上海 浦东新区 世纪大道 １００ 号", address))
    }

    @Test fun exactAddressRejectsWrongStreetNumberDistrictOrCity() {
        val address = place("上海环球金融中心", "上海市", "上海市",
            address = "上海市浦东新区世纪大道100号").copy(
            street = "世纪大道", houseNumber = "100", addressAreas = listOf("浦东新区"))
        val query = "上海市浦东新区世纪大道100号"
        for (wrong in listOf(address.copy(street = "临港大道"), address.copy(houseNumber = "1001"),
            address.copy(houseNumber = "100-1"), address.copy(houseNumber = "", displayName = "世纪大道"),
            address.copy(addressAreas = listOf("金山区")), address.copy(city = "苏州市", region = "江苏省，苏州市"),
            address.copy(countryCode = "TW"),
            address.copy(administrativeOnly = true))) {
            assertFalse("Accepted a different address: $wrong", PlaceSearchPolicy.accepts(query, wrong))
        }
        assertTrue(PlaceSearchPolicy.accepts("上海市浦东新区世纪大道100-1号", address.copy(houseNumber = "100-1号")))
    }

    @Test fun formattedAddressWithoutStructuredFieldsKeepsExactNumbers() {
        val address = place("目的地", "上海市", "上海市", address = "上海市浦东新区世纪大道100-1号")
        assertTrue(PlaceSearchPolicy.accepts("上海市浦东新区世纪大道100-1号", address))
        assertFalse(PlaceSearchPolicy.accepts("上海市浦东新区世纪大道1001号", address))
        assertTrue(PlaceSearchPolicy.accepts("上海市浦东新区世纪大道100-1号",
            address.copy(street = "世纪大道")))
        assertFalse(PlaceSearchPolicy.accepts("上海市浦东新区世纪大道100-1号",
            address.copy(street = "临港大道")))
    }

    @Test fun addressDashVariantsDoNotCollapseDifferentHouseNumbers() {
        val address = place("目的地", "上海市", "上海市", address = "上海市浦东新区世纪大道100-1号")
        val another = address.copy(name = "100-1号门店", displayName = "上海市浦东新区世纪大道1001号")
        for (separator in listOf("-", "–", " - ")) {
            val query = "上海市浦东新区世纪大道100${separator}1号"
            assertTrue(PlaceSearchPolicy.accepts(query, address))
            assertFalse(PlaceSearchPolicy.accepts(query, another))
        }
    }
}
