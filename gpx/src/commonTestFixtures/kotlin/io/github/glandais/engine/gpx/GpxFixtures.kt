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
}
