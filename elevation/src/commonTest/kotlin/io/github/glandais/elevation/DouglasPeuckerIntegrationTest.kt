package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DouglasPeuckerIntegrationTest {
    @Test
    fun `ECEF batch conversion and Douglas-Peucker work together`() {
        val testPath =
            listOf(
                LatLonElevation(46.5197, 9.8544, 1000.0),
                LatLonElevation(46.5198, 9.8545, 1001.0),
                LatLonElevation(46.5199, 9.8546, 1200.0),
                LatLonElevation(46.5200, 9.8547, 1500.0),
            )

        val ecefVectors = EcefConverter.convertBatch(testPath, 3.0)
        assertEquals(4, ecefVectors.size)

        val simplified = DouglasPeucker.simplify(testPath, 10.0, 3.0)
        assertTrue(simplified.size <= testPath.size)
        assertEquals(testPath.first(), simplified.first())
        assertEquals(testPath.last(), simplified.last())
    }
}
