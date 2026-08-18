package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.toGpxDocument
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Compiles and runs the Kotlin snippet of the root README's "Kotlin" quick start, exactly as
 * written there. Its Java twin is `JavaGuideSnippetTest`; the rationale is the same — a
 * documentation example that does not compile is worse than no example.
 */
class ReadmeKotlinSnippetTest {
    private suspend fun virtualize(xml: String): String {
        val path = GpxParser.parse(xml).firstTrackAsPath()
        val out = Enhancer.enhanceCourseDefault(path) // pure physics, no HTTP
        return GpxWriter.write(out.toGpxDocument(trackName = "virtualized"))
    }

    @Test
    fun readmeSnippetCompilesAndRuns() =
        runTest {
            val xml =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
                  <trk><trkseg>
                    <trkpt lat="45.0000" lon="6.0000"><ele>1000.0</ele></trkpt>
                    <trkpt lat="45.0020" lon="6.0020"><ele>1010.0</ele></trkpt>
                  </trkseg></trk>
                </gpx>
                """.trimIndent()

            val out = virtualize(xml)

            assertTrue(out.contains("<trk>"))
            assertTrue(out.contains("virtualized"))
        }
}
