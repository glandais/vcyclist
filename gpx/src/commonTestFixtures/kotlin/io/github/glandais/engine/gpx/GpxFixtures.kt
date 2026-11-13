package io.github.glandais.engine.gpx

/**
 * Inline GPX fixtures used by [GpxParserTest]. Mirror the real `.gpx` files under
 * `gpx/src/commonTest/resources/` (kept for human review and `git diff`), but truncated
 * to a handful of trackpoints per source so the strings stay readable. The first trackpoint
 * of each fixture preserves the exact values seen in the upstream file — tests assert on
 * those.
 *
 * This file lives in `src/commonTestFixtures/` rather than `src/commonTest/` because
 * `:engine`'s parity and JS-façade tests need the same strings. KMP has no test-fixtures
 * publication, so both `gpx/build.gradle.kts` and `engine/build.gradle.kts` add this
 * directory as an extra `commonTest` source dir — one file, two test compilations.
 */
object GpxFixtures {
    /**
     * Custom `<power>` (no namespace), `<time>` + `<ele>`. Metadata name "sample" and
     * `<trk><name>` "L'étape du Tour 2025" come straight from the upstream sample.gpx.
     */
    val SAMPLE_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpx3="http://www.garmin.com/xmlschemas/GpxExtensions/v3" xmlns:tpx1="http://www.garmin.com/xmlschemas/TrackPointExtension/v1" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" version="1.1">
  <metadata>
    <name>sample</name>
    <desc>sample</desc>
  </metadata>
  <trk>
    <name>L'étape du Tour 2025</name>
    <type>cycling</type>
    <trkseg>
      <trkpt lat="45.680697" lon="6.396115">
        <time>2024-11-03T14:25:22.000Z</time>
        <ele>350.1</ele>
        <extensions>
          <power>45</power>
        </extensions>
      </trkpt>
      <trkpt lat="45.681335" lon="6.396195">
        <time>2024-11-03T14:25:35.000Z</time>
        <ele>349.7</ele>
        <extensions>
          <power>26</power>
        </extensions>
      </trkpt>
      <trkpt lat="45.681565" lon="6.396291">
        <time>2024-11-03T14:25:40.000Z</time>
        <ele>349.5</ele>
        <extensions>
          <power>10</power>
        </extensions>
      </trkpt>
      <trkpt lat="45.682325" lon="6.396742">
        <time>2024-11-03T14:25:59.000Z</time>
        <ele>349.1</ele>
        <extensions>
          <power>16</power>
        </extensions>
      </trkpt>
      <trkpt lat="45.683043" lon="6.397265">
        <time>2024-11-03T14:26:19.000Z</time>
        <ele>349.3</ele>
        <extensions>
          <power>37</power>
        </extensions>
      </trkpt>
      <trkpt lat="45.683678" lon="6.397619">
        <time>2024-11-03T14:26:37.000Z</time>
        <ele>349.6</ele>
        <extensions>
          <power>39</power>
        </extensions>
      </trkpt>
      <trkpt lat="45.684207" lon="6.39801">
        <time>2024-11-03T14:26:51.000Z</time>
        <ele>350</ele>
        <extensions>
          <power>60</power>
        </extensions>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * Garmin Connect dialect: `<ns3:TrackPointExtension>` wrapping `<ns3:hr>` and `<ns3:cad>`.
     * No `<atemp>` in the upstream file → temperature stays null. Used to validate that the
     * parser descends into the TrackPointExtension wrapper via local-name matching.
     */
    val GARMIN_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx creator="Garmin Connect" version="1.1"
     xmlns:ns3="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xmlns:ns2="http://www.garmin.com/xmlschemas/GpxExtensions/v3">
  <metadata>
    <time>2018-04-23T15:54:34.000Z</time>
  </metadata>
  <trk>
    <type>running</type>
    <trkseg>
      <trkpt lat="60.29198664240539073944091796875" lon="25.02485521137714385986328125">
        <ele>16.200000762939453125</ele>
        <time>2018-04-23T15:54:34.000Z</time>
        <extensions>
          <ns3:TrackPointExtension>
            <ns3:hr>61</ns3:hr>
            <ns3:cad>0</ns3:cad>
          </ns3:TrackPointExtension>
        </extensions>
      </trkpt>
      <trkpt lat="60.2918745763599872589111328125" lon="25.0248872302472591400146484375">
        <ele>16</ele>
        <time>2018-04-23T15:54:51.000Z</time>
        <extensions>
          <ns3:TrackPointExtension>
            <ns3:hr>53</ns3:hr>
            <ns3:cad>81</ns3:cad>
          </ns3:TrackPointExtension>
        </extensions>
      </trkpt>
      <trkpt lat="60.29185370542109012603759765625" lon="25.0248932652175426483154296875">
        <ele>16</ele>
        <time>2018-04-23T15:54:52.000Z</time>
        <extensions>
          <ns3:TrackPointExtension>
            <ns3:hr>53</ns3:hr>
            <ns3:cad>80</ns3:cad>
          </ns3:TrackPointExtension>
        </extensions>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * Amazfit format : duplicate non-namespaced `<heartrate>` and Garmin-style
     * `<ns3:TrackPointExtension><ns3:hr/></ns3:TrackPointExtension>`. The first one wins
     * (deterministic local-name match in document order).
     */
    val AMAZFIT_GPX: String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
<gpx xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:ns3="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     creator="Huami Amazfit Sports Watch" version="1.1">
  <metadata>
    <name>Amazfit Sport Record</name>
    <time>2018-05-14T09:54:10Z</time>
  </metadata>
  <trk>
    <name>Sport</name>
    <type>Running</type>
    <trkseg>
      <trkpt lat="60.272281646728516" lon="24.973012924194336">
        <ele>23.649999618530273</ele>
        <time>2018-05-14T09:54:12Z</time>
        <extensions>
          <heartrate>89</heartrate>
          <ns3:TrackPointExtension>
            <ns3:hr>89</ns3:hr>
          </ns3:TrackPointExtension>
        </extensions>
      </trkpt>
      <trkpt lat="60.27223587036133" lon="24.97280502319336">
        <ele>23.649999618530273</ele>
        <time>2018-05-14T09:54:13Z</time>
        <extensions>
          <heartrate>88</heartrate>
          <ns3:TrackPointExtension>
            <ns3:hr>88</ns3:hr>
          </ns3:TrackPointExtension>
        </extensions>
      </trkpt>
      <trkpt lat="60.2722282409668" lon="24.972749710083008">
        <ele>23.649999618530273</ele>
        <time>2018-05-14T09:54:14Z</time>
        <extensions>
          <heartrate>87</heartrate>
        </extensions>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * Movescount / Cluetrust dialect: `<gpxdata:cadence>` / `<gpxdata:temp>` / `<gpxdata:speed>`.
     * Heart rate via Garmin TPX wrapper. Mix of self-closing `<extensions />` and full ones.
     */
    val MOVESCOUNT_GPX: String =
        """<?xml version="1.0" encoding="utf-8" standalone="no"?>
<gpx version="1.1" creator="Movescount - http://www.movescount.com"
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xmlns:gpxdata="http://www.cluetrust.com/XML/GPXDATA/1/0"
     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
     xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Move</name>
    <trkseg>
      <trkpt lat="42.941313" lon="0.962994">
        <ele>714.799987792969</ele>
        <time>2018-05-13T14:11:03.000Z</time>
        <extensions />
      </trkpt>
      <trkpt lat="42.94141" lon="0.962968">
        <ele>715.400024414063</ele>
        <time>2018-05-13T14:11:07.000Z</time>
        <extensions />
      </trkpt>
      <trkpt lat="42.94147" lon="0.962951">
        <ele>707</ele>
        <time>2018-05-13T14:11:10.000Z</time>
        <extensions>
          <gpxtpx:TrackPointExtension>
            <gpxtpx:hr>98</gpxtpx:hr>
          </gpxtpx:TrackPointExtension>
          <gpxdata:cadence>87</gpxdata:cadence>
          <gpxdata:temp>20.7999992370605</gpxdata:temp>
          <gpxdata:distance>17</gpxdata:distance>
          <gpxdata:speed>2.59999990463257</gpxdata:speed>
        </extensions>
      </trkpt>
      <trkpt lat="42.941487" lon="0.962941">
        <ele>706.799987792969</ele>
        <time>2018-05-13T14:11:11.000Z</time>
        <extensions>
          <gpxtpx:TrackPointExtension>
            <gpxtpx:hr>101</gpxtpx:hr>
          </gpxtpx:TrackPointExtension>
          <gpxdata:cadence>87</gpxdata:cadence>
          <gpxdata:temp>20.7999992370605</gpxdata:temp>
        </extensions>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * Multi-track GPX used to verify that `firstTrackAsPath()` materialises only the first track.
     */
    val MULTI_TRACK_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>first</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
      <trkpt lat="45.001" lon="6.001"><ele>110</ele></trkpt>
      <trkpt lat="45.002" lon="6.002"><ele>120</ele></trkpt>
    </trkseg>
  </trk>
  <trk>
    <name>second</name>
    <trkseg>
      <trkpt lat="46.0" lon="7.0"><ele>200</ele></trkpt>
      <trkpt lat="46.001" lon="7.001"><ele>210</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * Single track split into **3 `<trkseg>`** with a deliberate ~1.5 km teleport between
     * segment 1 and segment 2 (45.002 → 45.02, same longitude). Exercises the documented
     * difference between `tracksAsPaths()` (concatenates, jump counted) and
     * `segmentsAsPaths()` (one continuous Path per segment).
     */
    val MULTI_SEGMENT_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>paused ride</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
      <trkpt lat="45.001" lon="6.0"><ele>110</ele></trkpt>
      <trkpt lat="45.002" lon="6.0"><ele>120</ele></trkpt>
    </trkseg>
    <trkseg>
      <trkpt lat="45.02" lon="6.0"><ele>200</ele></trkpt>
      <trkpt lat="45.021" lon="6.0"><ele>210</ele></trkpt>
    </trkseg>
    <trkseg>
      <trkpt lat="45.03" lon="6.0"><ele>300</ele></trkpt>
      <trkpt lat="45.031" lon="6.0"><ele>310</ele></trkpt>
      <trkpt lat="45.032" lon="6.0"><ele>320</ele></trkpt>
      <trkpt lat="45.033" lon="6.0"><ele>330</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * A single track preceded by 3 `<wpt>` : one minimal (lat/lon only), one with every optional
     * field populated (`ele`, `name`, `desc`, `sym`, `type`, `time`), and one minimal again.
     * Exercises `GpxDocument.waypoints` parsing / round-trip (g03).
     */
    val WAYPOINTS_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>with waypoints</name>
  </metadata>
  <wpt lat="45.5" lon="6.5"/>
  <wpt lat="45.68" lon="6.4">
    <ele>1200.5</ele>
    <time>2024-11-03T14:00:00.000Z</time>
    <name>Col du Sommet</name>
    <desc>Ravitaillement au sommet</desc>
    <sym>Summit</sym>
    <type>peak</type>
  </wpt>
  <wpt lat="45.7" lon="6.3"/>
  <trk>
    <name>ride</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
      <trkpt lat="45.001" lon="6.001"><ele>110</ele></trkpt>
      <trkpt lat="45.002" lon="6.002"><ele>120</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * A pointless `<trkseg/>` between two real ones, plus a whole `<trk>` with no point at all.
     * Both must vanish from `tracksAsPaths()` / `segmentsAsPaths()` rather than producing a
     * parasitic `Path(0)`.
     */
    val EMPTY_PARTS_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>with holes</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
      <trkpt lat="45.001" lon="6.0"><ele>110</ele></trkpt>
    </trkseg>
    <trkseg/>
    <trkseg>
      <trkpt lat="45.01" lon="6.0"><ele>200</ele></trkpt>
      <trkpt lat="45.011" lon="6.0"><ele>210</ele></trkpt>
      <trkpt lat="45.012" lon="6.0"><ele>220</ele></trkpt>
    </trkseg>
  </trk>
  <trk>
    <name>ghost</name>
    <trkseg/>
  </trk>
</gpx>
"""

    /** Minimal well-formed GPX used as the "already valid" baseline for [GpxXmlRepairTest]. */
    val VALID_MINIMAL_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Café &amp; Croissant</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
      <trkpt lat="45.001" lon="6.0"><ele>110</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /** Leading UTF-8 BOM before the XML declaration — a common artefact of Windows-authored exports. */
    val BOM_GPX: String = "﻿" + VALID_MINIMAL_GPX

    /** A stray NUL byte inside `<name>`, as seen from buggy device firmware. */
    val CONTROL_CHAR_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>broken${'\u0000'}name</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
      <trkpt lat="45.001" lon="6.0"><ele>110</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /** A literal `&` in `<name>` instead of `&amp;` — the single most common hand-edited-GPX bug. */
    val BARE_AMPERSAND_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Café & Croissant</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
      <trkpt lat="45.001" lon="6.0"><ele>110</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /**
     * A supplementary-plane character (here U+1F638, "grinning cat") written as two adjacent
     * numeric character references to its UTF-16 surrogate pair — legal in some hand-rolled XML
     * generators, but XML 1.0 forbids a numeric reference denoting a lone surrogate code point.
     */
    val SURROGATE_PAIR_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>cat &#55357;&#56888; face</name>
    <trkseg>
      <trkpt lat="45.0" lon="6.0"><ele>100</ele></trkpt>
      <trkpt lat="45.001" lon="6.0"><ele>110</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""

    /** Not XML at all: no repair can rescue this, [GpxXmlRepair] must not loop or hang on it. */
    val NOT_XML_AT_ALL: String = "pas du xml du tout"

    /**
     * A real AF3V export (creator "AF3V WordPress", public data from af3v.org, "de Langogne à Le
     * Puy-en-Velay, Voie Verte du Velay"), kept verbatim: one `<trk>` of **9 `<trkseg>`** written
     * out of order, 4 of them backwards. Concatenated as written the track draws straight-line
     * jumps over 10 km in total; put back end to end, the segments join within 5 m, but for a
     * small loop at about 50 m. Exercises `GpxTrack.orderedSegments`.
     */
    val UNORDERED_SEGMENTS_GPX: String =
        """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="AF3V WordPress" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>de Langogne à Le Puy-en-Velay Voie Verte du Velay</name>
<trkseg>
<trkpt lat="45.050786" lon="3.908679"></trkpt>
<trkpt lat="45.050218" lon="3.909274"></trkpt>
<trkpt lat="45.049617" lon="3.909853"></trkpt>
<trkpt lat="45.049151" lon="3.910336"></trkpt>
<trkpt lat="45.048912" lon="3.910637"></trkpt>
<trkpt lat="45.048726" lon="3.910961"></trkpt>
<trkpt lat="45.048436" lon="3.911495"></trkpt>
<trkpt lat="45.047911" lon="3.912812"></trkpt>
<trkpt lat="45.047396" lon="3.914115"></trkpt>
<trkpt lat="45.046742" lon="3.915776"></trkpt>
<trkpt lat="45.045980" lon="3.917661"></trkpt>
<trkpt lat="45.045114" lon="3.919804"></trkpt>
<trkpt lat="45.045063" lon="3.919764"></trkpt>
<trkpt lat="45.045027" lon="3.919815"></trkpt>
<trkpt lat="45.044788" lon="3.920440"></trkpt>
<trkpt lat="45.044728" lon="3.920577"></trkpt>
<trkpt lat="45.044652" lon="3.920738"></trkpt>
<trkpt lat="45.044415" lon="3.921062"></trkpt>
<trkpt lat="45.044284" lon="3.921196"></trkpt>
<trkpt lat="45.044176" lon="3.921344"></trkpt>
<trkpt lat="45.043971" lon="3.921709"></trkpt>
<trkpt lat="45.043795" lon="3.922197"></trkpt>
<trkpt lat="45.043653" lon="3.922682"></trkpt>
<trkpt lat="45.043600" lon="3.922902"></trkpt>
<trkpt lat="45.043460" lon="3.923632"></trkpt>
<trkpt lat="45.043172" lon="3.924667"></trkpt>
<trkpt lat="45.043177" lon="3.924884"></trkpt>
<trkpt lat="45.043117" lon="3.925091"></trkpt>
<trkpt lat="45.043064" lon="3.925332"></trkpt>
<trkpt lat="45.043060" lon="3.925579"></trkpt>
</trkseg>
<trkseg>
<trkpt lat="45.043058" lon="3.925601"></trkpt>
<trkpt lat="45.042914" lon="3.925834"></trkpt>
<trkpt lat="45.042827" lon="3.925990"></trkpt>
<trkpt lat="45.042181" lon="3.927073"></trkpt>
<trkpt lat="45.041819" lon="3.927585"></trkpt>
<trkpt lat="45.041563" lon="3.927846"></trkpt>
<trkpt lat="45.041324" lon="3.928036"></trkpt>
<trkpt lat="45.040901" lon="3.928259"></trkpt>
<trkpt lat="45.039753" lon="3.928763"></trkpt>
<trkpt lat="45.039417" lon="3.928902"></trkpt>
<trkpt lat="45.039146" lon="3.929074"></trkpt>
<trkpt lat="45.038892" lon="3.929281"></trkpt>
<trkpt lat="45.038542" lon="3.929670"></trkpt>
<trkpt lat="45.037501" lon="3.931113"></trkpt>
<trkpt lat="45.036783" lon="3.932105"></trkpt>
<trkpt lat="45.036315" lon="3.932690"></trkpt>
<trkpt lat="45.036078" lon="3.932894"></trkpt>
<trkpt lat="45.035827" lon="3.933092"></trkpt>
<trkpt lat="45.035465" lon="3.933301"></trkpt>
<trkpt lat="45.035103" lon="3.933446"></trkpt>
<trkpt lat="45.034823" lon="3.933510"></trkpt>
<trkpt lat="45.034576" lon="3.933567"></trkpt>
<trkpt lat="45.034184" lon="3.933634"></trkpt>
<trkpt lat="45.033458" lon="3.933486"></trkpt>
<trkpt lat="45.032821" lon="3.933358"></trkpt>
<trkpt lat="45.032469" lon="3.933237"></trkpt>
<trkpt lat="45.032080" lon="3.933049"></trkpt>
<trkpt lat="45.031693" lon="3.932794"></trkpt>
<trkpt lat="45.031280" lon="3.932497"></trkpt>
<trkpt lat="45.031039" lon="3.932301"></trkpt>
<trkpt lat="45.030774" lon="3.932011"></trkpt>
<trkpt lat="45.030516" lon="3.931630"></trkpt>
<trkpt lat="45.030293" lon="3.931201"></trkpt>
<trkpt lat="45.030124" lon="3.930775"></trkpt>
<trkpt lat="45.029978" lon="3.930187"></trkpt>
<trkpt lat="45.029796" lon="3.929442"></trkpt>
<trkpt lat="45.029601" lon="3.928750"></trkpt>
<trkpt lat="45.029443" lon="3.928355"></trkpt>
<trkpt lat="45.029286" lon="3.928109"></trkpt>
<trkpt lat="45.029127" lon="3.927819"></trkpt>
<trkpt lat="45.028931" lon="3.927537"></trkpt>
<trkpt lat="45.027942" lon="3.926226"></trkpt>
<trkpt lat="45.027587" lon="3.925893"></trkpt>
<trkpt lat="45.027204" lon="3.925641"></trkpt>
<trkpt lat="45.026969" lon="3.925483"></trkpt>
<trkpt lat="45.026695" lon="3.925335"></trkpt>
<trkpt lat="45.026150" lon="3.925163"></trkpt>
<trkpt lat="45.025593" lon="3.925070"></trkpt>
<trkpt lat="45.025227" lon="3.925067"></trkpt>
<trkpt lat="45.024878" lon="3.925150"></trkpt>
<trkpt lat="45.023919" lon="3.925563"></trkpt>
<trkpt lat="45.023335" lon="3.926041"></trkpt>
<trkpt lat="45.022765" lon="3.926470"></trkpt>
<trkpt lat="45.021718" lon="3.927336"></trkpt>
<trkpt lat="45.021318" lon="3.927671"></trkpt>
<trkpt lat="45.020859" lon="3.927953"></trkpt>
<trkpt lat="45.020264" lon="3.928109"></trkpt>
<trkpt lat="45.019924" lon="3.928114"></trkpt>
<trkpt lat="45.019553" lon="3.928047"></trkpt>
<trkpt lat="45.019223" lon="3.927923"></trkpt>
<trkpt lat="45.017638" lon="3.927009"></trkpt>
<trkpt lat="45.016857" lon="3.926523"></trkpt>
<trkpt lat="45.016466" lon="3.926285"></trkpt>
<trkpt lat="45.016059" lon="3.926038"></trkpt>
<trkpt lat="45.015812" lon="3.925888"></trkpt>
<trkpt lat="45.015503" lon="3.925676"></trkpt>
<trkpt lat="45.015112" lon="3.925303"></trkpt>
<trkpt lat="45.014648" lon="3.924694"></trkpt>
<trkpt lat="45.014346" lon="3.924225"></trkpt>
<trkpt lat="45.014003" lon="3.923739"></trkpt>
<trkpt lat="45.013738" lon="3.923329"></trkpt>
<trkpt lat="45.013427" lon="3.922894"></trkpt>
<trkpt lat="45.013190" lon="3.922648"></trkpt>
<trkpt lat="45.012008" lon="3.921596"></trkpt>
<trkpt lat="45.011559" lon="3.921143"></trkpt>
<trkpt lat="45.011301" lon="3.920842"></trkpt>
<trkpt lat="45.010924" lon="3.920344"></trkpt>
<trkpt lat="45.009993" lon="3.919029"></trkpt>
<trkpt lat="45.009530" lon="3.918369"></trkpt>
<trkpt lat="45.009039" lon="3.917710"></trkpt>
<trkpt lat="45.008286" lon="3.916647"></trkpt>
<trkpt lat="45.007508" lon="3.915575"></trkpt>
<trkpt lat="45.007215" lon="3.915282"></trkpt>
<trkpt lat="45.006915" lon="3.915065"></trkpt>
<trkpt lat="45.006452" lon="3.914834"></trkpt>
<trkpt lat="45.005953" lon="3.914593"></trkpt>
<trkpt lat="45.005498" lon="3.914333"></trkpt>
<trkpt lat="45.005261" lon="3.914145"></trkpt>
<trkpt lat="45.004874" lon="3.913777"></trkpt>
<trkpt lat="45.004501" lon="3.913372"></trkpt>
<trkpt lat="45.004417" lon="3.913292"></trkpt>
<trkpt lat="45.004277" lon="3.913383"></trkpt>
<trkpt lat="45.004171" lon="3.913346"></trkpt>
<trkpt lat="45.004030" lon="3.912916"></trkpt>
<trkpt lat="45.004038" lon="3.912833"></trkpt>
<trkpt lat="45.004201" lon="3.912680"></trkpt>
<trkpt lat="45.004298" lon="3.912506"></trkpt>
<trkpt lat="45.004340" lon="3.912385"></trkpt>
<trkpt lat="45.004326" lon="3.912241"></trkpt>
<trkpt lat="45.004112" lon="3.911130"></trkpt>
<trkpt lat="45.003852" lon="3.909894"></trkpt>
<trkpt lat="45.003670" lon="3.909419"></trkpt>
<trkpt lat="45.003522" lon="3.908963"></trkpt>
<trkpt lat="45.003545" lon="3.908432"></trkpt>
<trkpt lat="45.003664" lon="3.906991"></trkpt>
<trkpt lat="45.003738" lon="3.906447"></trkpt>
<trkpt lat="45.003903" lon="3.905806"></trkpt>
<trkpt lat="45.004080" lon="3.905280"></trkpt>
<trkpt lat="45.004300" lon="3.904757"></trkpt>
<trkpt lat="45.005199" lon="3.902394"></trkpt>
<trkpt lat="45.005768" lon="3.900653"></trkpt>
<trkpt lat="45.006549" lon="3.898990"></trkpt>
<trkpt lat="45.006742" lon="3.898524"></trkpt>
<trkpt lat="45.006905" lon="3.897934"></trkpt>
<trkpt lat="45.006989" lon="3.897341"></trkpt>
<trkpt lat="45.007095" lon="3.896171"></trkpt>
<trkpt lat="45.007199" lon="3.895257"></trkpt>
<trkpt lat="45.007264" lon="3.894659"></trkpt>
<trkpt lat="45.007376" lon="3.894138"></trkpt>
<trkpt lat="45.007526" lon="3.893626"></trkpt>
<trkpt lat="45.007755" lon="3.893076"></trkpt>
<trkpt lat="45.008030" lon="3.892524"></trkpt>
<trkpt lat="45.009471" lon="3.890128"></trkpt>
<trkpt lat="45.010835" lon="3.887757"></trkpt>
<trkpt lat="45.011424" lon="3.886035"></trkpt>
<trkpt lat="45.011726" lon="3.885182"></trkpt>
<trkpt lat="45.011972" lon="3.884260"></trkpt>
<trkpt lat="45.012029" lon="3.882980"></trkpt>
<trkpt lat="45.012002" lon="3.881728"></trkpt>
<trkpt lat="45.011766" lon="3.880293"></trkpt>
<trkpt lat="45.011622" lon="3.879861"></trkpt>
<trkpt lat="45.011449" lon="3.879502"></trkpt>
<trkpt lat="45.011182" lon="3.879059"></trkpt>
<trkpt lat="45.010831" lon="3.878635"></trkpt>
<trkpt lat="45.010427" lon="3.878182"></trkpt>
<trkpt lat="45.009843" lon="3.877549"></trkpt>
<trkpt lat="45.009509" lon="3.877200"></trkpt>
<trkpt lat="45.009168" lon="3.876908"></trkpt>
<trkpt lat="45.008821" lon="3.876672"></trkpt>
<trkpt lat="45.007804" lon="3.876197"></trkpt>
<trkpt lat="45.007552" lon="3.876055"></trkpt>
<trkpt lat="45.007325" lon="3.875891"></trkpt>
<trkpt lat="45.006378" lon="3.875231"></trkpt>
<trkpt lat="45.005972" lon="3.875033"></trkpt>
<trkpt lat="45.005623" lon="3.874942"></trkpt>
<trkpt lat="45.005426" lon="3.874875"></trkpt>
<trkpt lat="45.005134" lon="3.874813"></trkpt>
<trkpt lat="45.004880" lon="3.874775"></trkpt>
<trkpt lat="45.004586" lon="3.874765"></trkpt>
<trkpt lat="45.004330" lon="3.874800"></trkpt>
<trkpt lat="45.004146" lon="3.874888"></trkpt>
<trkpt lat="45.002293" lon="3.875891"></trkpt>
<trkpt lat="45.000913" lon="3.876943"></trkpt>
<trkpt lat="44.998227" lon="3.878831"></trkpt>
<trkpt lat="44.995845" lon="3.880741"></trkpt>
<trkpt lat="44.994904" lon="3.881170"></trkpt>
<trkpt lat="44.993827" lon="3.881207"></trkpt>
<trkpt lat="44.993481" lon="3.881063"></trkpt>
<trkpt lat="44.992764" lon="3.880668"></trkpt>
<trkpt lat="44.992250" lon="3.880371"></trkpt>
<trkpt lat="44.991850" lon="3.880019"></trkpt>
<trkpt lat="44.991524" lon="3.879603"></trkpt>
<trkpt lat="44.990822" lon="3.878102"></trkpt>
<trkpt lat="44.990185" lon="3.875913"></trkpt>
<trkpt lat="44.989957" lon="3.875097"></trkpt>
<trkpt lat="44.989517" lon="3.874277"></trkpt>
<trkpt lat="44.988948" lon="3.873788"></trkpt>
<trkpt lat="44.988439" lon="3.873660"></trkpt>
<trkpt lat="44.988085" lon="3.873633"></trkpt>
<trkpt lat="44.987743" lon="3.873681"></trkpt>
<trkpt lat="44.987322" lon="3.873815"></trkpt>
<trkpt lat="44.986929" lon="3.874046"></trkpt>
<trkpt lat="44.986423" lon="3.874368"></trkpt>
<trkpt lat="44.985916" lon="3.874606"></trkpt>
<trkpt lat="44.985579" lon="3.874698"></trkpt>
<trkpt lat="44.985269" lon="3.874784"></trkpt>
<trkpt lat="44.984820" lon="3.874749"></trkpt>
<trkpt lat="44.984383" lon="3.874617"></trkpt>
<trkpt lat="44.984065" lon="3.874454"></trkpt>
<trkpt lat="44.983693" lon="3.874164"></trkpt>
<trkpt lat="44.983317" lon="3.873850"></trkpt>
<trkpt lat="44.980616" lon="3.871270"></trkpt>
<trkpt lat="44.979724" lon="3.870417"></trkpt>
<trkpt lat="44.978817" lon="3.869615"></trkpt>
<trkpt lat="44.978563" lon="3.869465"></trkpt>
<trkpt lat="44.978291" lon="3.869341"></trkpt>
<trkpt lat="44.977800" lon="3.869247"></trkpt>
<trkpt lat="44.976127" lon="3.869218"></trkpt>
<trkpt lat="44.973899" lon="3.869140"></trkpt>
<trkpt lat="44.972958" lon="3.869089"></trkpt>
<trkpt lat="44.972514" lon="3.869186"></trkpt>
<trkpt lat="44.972335" lon="3.869255"></trkpt>
<trkpt lat="44.972079" lon="3.869382"></trkpt>
<trkpt lat="44.971635" lon="3.869744"></trkpt>
<trkpt lat="44.970529" lon="3.870999"></trkpt>
<trkpt lat="44.969979" lon="3.871846"></trkpt>
<trkpt lat="44.969561" lon="3.872876"></trkpt>
<trkpt lat="44.968916" lon="3.875669"></trkpt>
<trkpt lat="44.968609" lon="3.877082"></trkpt>
<trkpt lat="44.968288" lon="3.878525"></trkpt>
<trkpt lat="44.967859" lon="3.880722"></trkpt>
<trkpt lat="44.967745" lon="3.880969"></trkpt>
<trkpt lat="44.967514" lon="3.882093"></trkpt>
<trkpt lat="44.967191" lon="3.883436"></trkpt>
<trkpt lat="44.967043" lon="3.883876"></trkpt>
<trkpt lat="44.967058" lon="3.884088"></trkpt>
</trkseg>
<trkseg>
<trkpt lat="44.967052" lon="3.884134"></trkpt>
<trkpt lat="44.966876" lon="3.884718"></trkpt>
<trkpt lat="44.966625" lon="3.885327"></trkpt>
<trkpt lat="44.966299" lon="3.885893"></trkpt>
<trkpt lat="44.965834" lon="3.886462"></trkpt>
<trkpt lat="44.965582" lon="3.886746"></trkpt>
<trkpt lat="44.965128" lon="3.887068"></trkpt>
<trkpt lat="44.964631" lon="3.887323"></trkpt>
<trkpt lat="44.964147" lon="3.887462"></trkpt>
<trkpt lat="44.963511" lon="3.887497"></trkpt>
<trkpt lat="44.962824" lon="3.887358"></trkpt>
<trkpt lat="44.962004" lon="3.887154"></trkpt>
<trkpt lat="44.959932" lon="3.886628"></trkpt>
<trkpt lat="44.959002" lon="3.886376"></trkpt>
<trkpt lat="44.958662" lon="3.886304"></trkpt>
<trkpt lat="44.958176" lon="3.886156"></trkpt>
<trkpt lat="44.957721" lon="3.885952"></trkpt>
<trkpt lat="44.956381" lon="3.885140"></trkpt>
<trkpt lat="44.955481" lon="3.884566"></trkpt>
<trkpt lat="44.954976" lon="3.884225"></trkpt>
<trkpt lat="44.954714" lon="3.884123"></trkpt>
<trkpt lat="44.954433" lon="3.884053"></trkpt>
<trkpt lat="44.953944" lon="3.884043"></trkpt>
<trkpt lat="44.953503" lon="3.884102"></trkpt>
<trkpt lat="44.953046" lon="3.884289"></trkpt>
<trkpt lat="44.952693" lon="3.884544"></trkpt>
<trkpt lat="44.952378" lon="3.884818"></trkpt>
<trkpt lat="44.951550" lon="3.885512"></trkpt>
<trkpt lat="44.950969" lon="3.886025"></trkpt>
<trkpt lat="44.950474" lon="3.886320"></trkpt>
<trkpt lat="44.950094" lon="3.886424"></trkpt>
<trkpt lat="44.949692" lon="3.886494"></trkpt>
<trkpt lat="44.949037" lon="3.886373"></trkpt>
<trkpt lat="44.948718" lon="3.886239"></trkpt>
<trkpt lat="44.948330" lon="3.885958"></trkpt>
<trkpt lat="44.947966" lon="3.885596"></trkpt>
<trkpt lat="44.947712" lon="3.885290"></trkpt>
<trkpt lat="44.947507" lon="3.884949"></trkpt>
<trkpt lat="44.946518" lon="3.882970"></trkpt>
<trkpt lat="44.946278" lon="3.882310"></trkpt>
<trkpt lat="44.946164" lon="3.881570"></trkpt>
<trkpt lat="44.946134" lon="3.881028"></trkpt>
<trkpt lat="44.946161" lon="3.880416"></trkpt>
<trkpt lat="44.946269" lon="3.879850"></trkpt>
<trkpt lat="44.946434" lon="3.879306"></trkpt>
<trkpt lat="44.946996" lon="3.877876"></trkpt>
<trkpt lat="44.947157" lon="3.877291"></trkpt>
<trkpt lat="44.947260" lon="3.876618"></trkpt>
<trkpt lat="44.947260" lon="3.875840"></trkpt>
<trkpt lat="44.947184" lon="3.875248"></trkpt>
<trkpt lat="44.947093" lon="3.874826"></trkpt>
<trkpt lat="44.946901" lon="3.874269"></trkpt>
<trkpt lat="44.946660" lon="3.873780"></trkpt>
<trkpt lat="44.946295" lon="3.873247"></trkpt>
<trkpt lat="44.945678" lon="3.872724"></trkpt>
<trkpt lat="44.945134" lon="3.872479"></trkpt>
<trkpt lat="44.944536" lon="3.872383"></trkpt>
<trkpt lat="44.944236" lon="3.872429"></trkpt>
<trkpt lat="44.943919" lon="3.872512"></trkpt>
<trkpt lat="44.943252" lon="3.872834"></trkpt>
<trkpt lat="44.942527" lon="3.873225"></trkpt>
<trkpt lat="44.942022" lon="3.873359"></trkpt>
<trkpt lat="44.941504" lon="3.873335"></trkpt>
<trkpt lat="44.940581" lon="3.873161"></trkpt>
<trkpt lat="44.939778" lon="3.872968"></trkpt>
<trkpt lat="44.938284" lon="3.872337"></trkpt>
<trkpt lat="44.937744" lon="3.872273"></trkpt>
<trkpt lat="44.935815" lon="3.872380"></trkpt>
<trkpt lat="44.935073" lon="3.872249"></trkpt>
<trkpt lat="44.934357" lon="3.871785"></trkpt>
<trkpt lat="44.933763" lon="3.871125"></trkpt>
<trkpt lat="44.933188" lon="3.870412"></trkpt>
<trkpt lat="44.932599" lon="3.869765"></trkpt>
<trkpt lat="44.932462" lon="3.869607"></trkpt>
<trkpt lat="44.932044" lon="3.869451"></trkpt>
<trkpt lat="44.931858" lon="3.869272"></trkpt>
<trkpt lat="44.930047" lon="3.869199"></trkpt>
<trkpt lat="44.929762" lon="3.869092"></trkpt>
<trkpt lat="44.929475" lon="3.868918"></trkpt>
<trkpt lat="44.929044" lon="3.868569"></trkpt>
<trkpt lat="44.928647" lon="3.868121"></trkpt>
<trkpt lat="44.928423" lon="3.867735"></trkpt>
<trkpt lat="44.928226" lon="3.867351"></trkpt>
<trkpt lat="44.927910" lon="3.866463"></trkpt>
<trkpt lat="44.927274" lon="3.864666"></trkpt>
<trkpt lat="44.927031" lon="3.863778"></trkpt>
<trkpt lat="44.926879" lon="3.862081"></trkpt>
<trkpt lat="44.926788" lon="3.861429"></trkpt>
<trkpt lat="44.926560" lon="3.860388"></trkpt>
<trkpt lat="44.926408" lon="3.859575"></trkpt>
<trkpt lat="44.926349" lon="3.858902"></trkpt>
<trkpt lat="44.926289" lon="3.857666"></trkpt>
<trkpt lat="44.926245" lon="3.857296"></trkpt>
<trkpt lat="44.926139" lon="3.856821"></trkpt>
<trkpt lat="44.925960" lon="3.856325"></trkpt>
<trkpt lat="44.925755" lon="3.855871"></trkpt>
<trkpt lat="44.925459" lon="3.855418"></trkpt>
<trkpt lat="44.925045" lon="3.854978"></trkpt>
<trkpt lat="44.924023" lon="3.854219"></trkpt>
<trkpt lat="44.923664" lon="3.853857"></trkpt>
<trkpt lat="44.923379" lon="3.853468"></trkpt>
<trkpt lat="44.923161" lon="3.853060"></trkpt>
<trkpt lat="44.922998" lon="3.852698"></trkpt>
<trkpt lat="44.922861" lon="3.852266"></trkpt>
<trkpt lat="44.922770" lon="3.851816"></trkpt>
<trkpt lat="44.922697" lon="3.851234"></trkpt>
<trkpt lat="44.922688" lon="3.850810"></trkpt>
<trkpt lat="44.922724" lon="3.850386"></trkpt>
<trkpt lat="44.922889" lon="3.849482"></trkpt>
<trkpt lat="44.923093" lon="3.848364"></trkpt>
<trkpt lat="44.923100" lon="3.847792"></trkpt>
<trkpt lat="44.923077" lon="3.847350"></trkpt>
<trkpt lat="44.923015" lon="3.846937"></trkpt>
<trkpt lat="44.922948" lon="3.846599"></trkpt>
<trkpt lat="44.922758" lon="3.846036"></trkpt>
<trkpt lat="44.922530" lon="3.845513"></trkpt>
<trkpt lat="44.922236" lon="3.845081"></trkpt>
<trkpt lat="44.921976" lon="3.844780"></trkpt>
<trkpt lat="44.921421" lon="3.844351"></trkpt>
<trkpt lat="44.920700" lon="3.844067"></trkpt>
<trkpt lat="44.920257" lon="3.844110"></trkpt>
<trkpt lat="44.919852" lon="3.844193"></trkpt>
<trkpt lat="44.919361" lon="3.844405"></trkpt>
<trkpt lat="44.918965" lon="3.844694"></trkpt>
<trkpt lat="44.918713" lon="3.844901"></trkpt>
<trkpt lat="44.918498" lon="3.845175"></trkpt>
<trkpt lat="44.917091" lon="3.847540"></trkpt>
<trkpt lat="44.915932" lon="3.849477"></trkpt>
<trkpt lat="44.914766" lon="3.851395"></trkpt>
<trkpt lat="44.914151" lon="3.852232"></trkpt>
<trkpt lat="44.913683" lon="3.852556"></trkpt>
<trkpt lat="44.913256" lon="3.852728"></trkpt>
<trkpt lat="44.912804" lon="3.852816"></trkpt>
<trkpt lat="44.912413" lon="3.852803"></trkpt>
<trkpt lat="44.912080" lon="3.852741"></trkpt>
<trkpt lat="44.911681" lon="3.852559"></trkpt>
<trkpt lat="44.910485" lon="3.851945"></trkpt>
<trkpt lat="44.910078" lon="3.851722"></trkpt>
<trkpt lat="44.909692" lon="3.851572"></trkpt>
<trkpt lat="44.909159" lon="3.851470"></trkpt>
<trkpt lat="44.908826" lon="3.851467"></trkpt>
<trkpt lat="44.908321" lon="3.851539"></trkpt>
<trkpt lat="44.907428" lon="3.851928"></trkpt>
<trkpt lat="44.906461" lon="3.852443"></trkpt>
<trkpt lat="44.905789" lon="3.852607"></trkpt>
<trkpt lat="44.905473" lon="3.852639"></trkpt>
</trkseg>
<trkseg>
<trkpt lat="45.048726" lon="3.898267"></trkpt>
<trkpt lat="45.048757" lon="3.898352"></trkpt>
<trkpt lat="45.048711" lon="3.898712"></trkpt>
<trkpt lat="45.048651" lon="3.898846"></trkpt>
<trkpt lat="45.048681" lon="3.898937"></trkpt>
<trkpt lat="45.048776" lon="3.898808"></trkpt>
<trkpt lat="45.048844" lon="3.898374"></trkpt>
<trkpt lat="45.048825" lon="3.898234"></trkpt>
<trkpt lat="45.048753" lon="3.898245"></trkpt>
</trkseg>
<trkseg>
<trkpt lat="45.048730" lon="3.898255"></trkpt>
<trkpt lat="45.048721" lon="3.898255"></trkpt>
<trkpt lat="45.048312" lon="3.897976"></trkpt>
<trkpt lat="45.047732" lon="3.897584"></trkpt>
<trkpt lat="45.046326" lon="3.896726"></trkpt>
<trkpt lat="45.045746" lon="3.896340"></trkpt>
<trkpt lat="45.045390" lon="3.896045"></trkpt>
<trkpt lat="45.044946" lon="3.895626"></trkpt>
<trkpt lat="45.044439" lon="3.895042"></trkpt>
<trkpt lat="45.044408" lon="3.894854"></trkpt>
<trkpt lat="45.044416" lon="3.894505"></trkpt>
<trkpt lat="45.044113" lon="3.893996"></trkpt>
<trkpt lat="45.043540" lon="3.892998"></trkpt>
<trkpt lat="45.043476" lon="3.892837"></trkpt>
<trkpt lat="45.043351" lon="3.892665"></trkpt>
<trkpt lat="45.043305" lon="3.892537"></trkpt>
</trkseg>
<trkseg>
<trkpt lat="44.895463" lon="3.846712"></trkpt>
<trkpt lat="44.899500" lon="3.850740"></trkpt>
<trkpt lat="44.900541" lon="3.851502"></trkpt>
<trkpt lat="44.902099" lon="3.852146"></trkpt>
<trkpt lat="44.903718" lon="3.852414"></trkpt>
<trkpt lat="44.905268" lon="3.852639"></trkpt>
<trkpt lat="44.905511" lon="3.852618"></trkpt>
</trkseg>
<trkseg>
<trkpt lat="44.889031" lon="3.845977"></trkpt>
<trkpt lat="44.892919" lon="3.845759"></trkpt>
<trkpt lat="44.894037" lon="3.845790"></trkpt>
<trkpt lat="44.894637" lon="3.846026"></trkpt>
<trkpt lat="44.895404" lon="3.846653"></trkpt>
<trkpt lat="44.895463" lon="3.846712"></trkpt>
</trkseg>
<trkseg>
<trkpt lat="44.842665" lon="3.834923"></trkpt>
<trkpt lat="44.843365" lon="3.835116"></trkpt>
<trkpt lat="44.844065" lon="3.835352"></trkpt>
<trkpt lat="44.844780" lon="3.835781"></trkpt>
<trkpt lat="44.845906" lon="3.836661"></trkpt>
<trkpt lat="44.847168" lon="3.837627"></trkpt>
<trkpt lat="44.847975" lon="3.838313"></trkpt>
<trkpt lat="44.850622" lon="3.840223"></trkpt>
<trkpt lat="44.853123" lon="3.842004"></trkpt>
<trkpt lat="44.854501" lon="3.842948"></trkpt>
<trkpt lat="44.855231" lon="3.843356"></trkpt>
<trkpt lat="44.855885" lon="3.843614"></trkpt>
<trkpt lat="44.856652" lon="3.843785"></trkpt>
<trkpt lat="44.857519" lon="3.843763"></trkpt>
<trkpt lat="44.858554" lon="3.843892"></trkpt>
<trkpt lat="44.859436" lon="3.844021"></trkpt>
<trkpt lat="44.860440" lon="3.844643"></trkpt>
<trkpt lat="44.863877" lon="3.847046"></trkpt>
<trkpt lat="44.865170" lon="3.847990"></trkpt>
<trkpt lat="44.866341" lon="3.848484"></trkpt>
<trkpt lat="44.867253" lon="3.848505"></trkpt>
<trkpt lat="44.868090" lon="3.848312"></trkpt>
<trkpt lat="44.870857" lon="3.847239"></trkpt>
<trkpt lat="44.872728" lon="3.846917"></trkpt>
<trkpt lat="44.873382" lon="3.846424"></trkpt>
<trkpt lat="44.874264" lon="3.845630"></trkpt>
<trkpt lat="44.875326" lon="3.844297"></trkpt>
<trkpt lat="44.876252" lon="3.843438"></trkpt>
<trkpt lat="44.877244" lon="3.842926"></trkpt>
<trkpt lat="44.878202" lon="3.842840"></trkpt>
<trkpt lat="44.878901" lon="3.843012"></trkpt>
<trkpt lat="44.879251" lon="3.843441"></trkpt>
<trkpt lat="44.880391" lon="3.844493"></trkpt>
<trkpt lat="44.881197" lon="3.845201"></trkpt>
<trkpt lat="44.882322" lon="3.845694"></trkpt>
<trkpt lat="44.883964" lon="3.845845"></trkpt>
<trkpt lat="44.888708" lon="3.845995"></trkpt>
<trkpt lat="44.889031" lon="3.845977"></trkpt>
</trkseg>
<trkseg>
<trkpt lat="45.050785" lon="3.908682"></trkpt>
<trkpt lat="45.050849" lon="3.908573"></trkpt>
<trkpt lat="45.050861" lon="3.908488"></trkpt>
<trkpt lat="45.050853" lon="3.908391"></trkpt>
<trkpt lat="45.050857" lon="3.908348"></trkpt>
<trkpt lat="45.050876" lon="3.908321"></trkpt>
<trkpt lat="45.050902" lon="3.908332"></trkpt>
<trkpt lat="45.050969" lon="3.908388"></trkpt>
<trkpt lat="45.050999" lon="3.908415"></trkpt>
<trkpt lat="45.051026" lon="3.908423"></trkpt>
<trkpt lat="45.051052" lon="3.908393"></trkpt>
<trkpt lat="45.051054" lon="3.908361"></trkpt>
<trkpt lat="45.051035" lon="3.908329"></trkpt>
<trkpt lat="45.050952" lon="3.908214"></trkpt>
<trkpt lat="45.050944" lon="3.908150"></trkpt>
<trkpt lat="45.050971" lon="3.908123"></trkpt>
<trkpt lat="45.051058" lon="3.908166"></trkpt>
<trkpt lat="45.051133" lon="3.908193"></trkpt>
<trkpt lat="45.051151" lon="3.908198"></trkpt>
<trkpt lat="45.051172" lon="3.908189"></trkpt>
<trkpt lat="45.051183" lon="3.908165"></trkpt>
<trkpt lat="45.051179" lon="3.908134"></trkpt>
<trkpt lat="45.051143" lon="3.908077"></trkpt>
<trkpt lat="45.051046" lon="3.907946"></trkpt>
<trkpt lat="45.051028" lon="3.907905"></trkpt>
<trkpt lat="45.051018" lon="3.907868"></trkpt>
<trkpt lat="45.051033" lon="3.907827"></trkpt>
<trkpt lat="45.051061" lon="3.907817"></trkpt>
<trkpt lat="45.051137" lon="3.907843"></trkpt>
<trkpt lat="45.051225" lon="3.907886"></trkpt>
<trkpt lat="45.051262" lon="3.907892"></trkpt>
<trkpt lat="45.051293" lon="3.907865"></trkpt>
<trkpt lat="45.051346" lon="3.907731"></trkpt>
<trkpt lat="45.051416" lon="3.907557"></trkpt>
<trkpt lat="45.051581" lon="3.907093"></trkpt>
<trkpt lat="45.051638" lon="3.907055"></trkpt>
<trkpt lat="45.051720" lon="3.906592"></trkpt>
<trkpt lat="45.051863" lon="3.905921"></trkpt>
<trkpt lat="45.051628" lon="3.905535"></trkpt>
<trkpt lat="45.051367" lon="3.904977"></trkpt>
<trkpt lat="45.050973" lon="3.904059"></trkpt>
<trkpt lat="45.050681" lon="3.903437"></trkpt>
<trkpt lat="45.050438" lon="3.903019"></trkpt>
<trkpt lat="45.050253" lon="3.902718"></trkpt>
<trkpt lat="45.050131" lon="3.902273"></trkpt>
<trkpt lat="45.049980" lon="3.902021"></trkpt>
<trkpt lat="45.049891" lon="3.901766"></trkpt>
<trkpt lat="45.049783" lon="3.901441"></trkpt>
<trkpt lat="45.049736" lon="3.901433"></trkpt>
<trkpt lat="45.049588" lon="3.901020"></trkpt>
<trkpt lat="45.049178" lon="3.900007"></trkpt>
<trkpt lat="45.048791" lon="3.899095"></trkpt>
<trkpt lat="45.048655" lon="3.898893"></trkpt>
</trkseg>
</trk></gpx>
"""
}
