package com.mocklocation.lab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordConverterTest {
    @Test fun shanghaiForwardModelKeepsItsExistingCoordinateConvention() {
        // Fixed model reference, not a surveyed accuracy claim.
        assertArrayEquals(doubleArrayOf(31.22847374224698, 121.47822405739267),
            CoordConverter.wgs84ToGcj02(31.230416, 121.473701), 1e-10)
    }

    @Test fun inverseReturnsOriginalShanghaiAndBeijingTargets() {
        val targets = listOf(
            doubleArrayOf(31.230416, 121.473701),
            doubleArrayOf(39.908823, 116.397470),
            doubleArrayOf(22.543099, 114.057868),
            doubleArrayOf(30.572815, 104.066801)
        )
        for (target in targets) {
            val gcj = CoordConverter.wgs84ToGcj02(target[0], target[1])
            assertArrayEquals("Original target must survive the picker/engine round trip", target,
                CoordConverter.gcj02ToWgs84(gcj[0], gcj[1]), 1e-7)
        }
    }

    @Test fun fixedGcjPairDoesNotUseThePreviousIncorrectInverseOffset() {
        assertArrayEquals(doubleArrayOf(39.908823, 116.397470),
            CoordConverter.gcj02ToWgs84(39.91022649807321, 116.4037135824225), 1e-7)
    }

    @Test fun outsideTheModelBoundsPassesThroughInBothDirections() {
        for (point in listOf(doubleArrayOf(35.6584491, 139.745536),
            doubleArrayOf(37.421998, -122.084000), doubleArrayOf(-33.8688, 151.2093),
            doubleArrayOf(90.0, 180.0), doubleArrayOf(-90.0, -180.0))) {
            assertTrue(CoordConverter.outOfChina(point[0], point[1]))
            assertArrayEquals(point, CoordConverter.wgs84ToGcj02(point[0], point[1]), 0.0)
            assertArrayEquals(point, CoordConverter.gcj02ToWgs84(point[0], point[1]), 0.0)
        }
        assertFalse(CoordConverter.outOfChina(31.230416, 121.473701))
    }

    @Test fun nonFiniteOrOutOfRangeInputCannotReachTheEngine() {
        for (point in listOf(doubleArrayOf(Double.NaN, 121.0),
            doubleArrayOf(31.0, Double.NaN), doubleArrayOf(Double.POSITIVE_INFINITY, 121.0),
            doubleArrayOf(31.0, Double.NEGATIVE_INFINITY), doubleArrayOf(90.01, 121.0),
            doubleArrayOf(-90.01, 121.0), doubleArrayOf(31.0, 180.01), doubleArrayOf(31.0, -180.01))) {
            assertThrows(IllegalArgumentException::class.java) { CoordConverter.outOfChina(point[0], point[1]) }
            assertThrows(IllegalArgumentException::class.java) { CoordConverter.wgs84ToGcj02(point[0], point[1]) }
            assertThrows(IllegalArgumentException::class.java) { CoordConverter.gcj02ToWgs84(point[0], point[1]) }
        }
    }
}
