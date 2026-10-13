package io.github.glandais.engine.path

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thresholds of [TunnelDetector], on the along-track grid of [stepM] metres.
 *
 * Measured in E4 of `docs/archive/tasks/elevation-bridge-decks.md` (*Mesures (E4)*) on the tunnel tuning set
 * (`tt01..tt08`, the four Vercors tunnels of `test4`), its 13 negatives (`tn01..tn13`), the 16 bridge
 * negatives of E2 bis and ten whole rides, on IGN LiDAR HD MNT z15 and Mapterhorn z12. **Precision
 * first**: the detector reads the terrain model of any source, Mapterhorn worldwide included, and a
 * wrong "tunnel" flattens a real climb. Each default comes from the one-at-a-time sensitivity sweep
 * recorded there, which also gives the nearest value that changes a result.
 *
 * Grades are fractions (0.30 = 30 %). Windows count *stations*: [footWindowM] = 20 m is 4 stations.
 * Every window must be a whole number of stations at [stepM], or construction fails.
 */
data class TunnelDetectorOptions(
    /** Grid spacing of the profile, metres. The step reads the terrain at `k × stepM`. */
    val stepM: Double = 5.0,
    /**
     * Short grade window of a portal, metres: the grade just inside a portal (towards the bump) and
     * just outside it (towards the road) are read over this.
     */
    val footWindowM: Double = 20.0,
    /** Long grade window, metres: the road-like outer side of a portal and the steep sides of a bump. */
    val steepWindowM: Double = 50.0,
    /**
     * A portal's **outer** side is road: its grade over [footWindowM] **and** over [steepWindowM] is
     * at most this. Measured: approach roads 0–10 %, Mapterhorn noise included; the descent of a
     * hill above a tunnel (Croix-Rousse, −15 to −20 % over 20 m) passes the short window and fails
     * the long one, which is what keeps a portal off a slope.
     */
    val maxRoadGrade: Double = 0.20,
    /**
     * A portal's **inner** side rises (entry) or falls (exit) at least this over [footWindowM]: the
     * cut face above a portal. The sweep is flat from 15 to 25 %; 35 % loses a tuning tunnel.
     */
    val minPortalGrade: Double = 0.25,
    /**
     * The spec's signature, *pentes impossibles des deux côtés*: within [sideSearchM] inside each
     * portal the terrain climbs (entry) and falls (exit) at least this over [steepWindowM]. Tunnels
     * found: 34–177 % up, 34–249 % down. At 20–25 %, Bourne loses 47 ground stations and Mapterhorn
     * finds a "tunnel" on a Pyrenean ride; at 40 % no negative moves and the same tunnels are found.
     *
     * "Impossible for a road" holds for **paved** roads only: a trail over a real knoll this steep is
     * flattened (`sports-tracker.gpx` km 2,19 fails only on its exit fall, −26.5 %, pinned in
     * `TunnelBenchProbeTest.tunnelRides`). Out of sample, the held-out Dordogne greenway — a tunnel
     * under a low rounded hill, exit flank about −20 to −24 % over 50 m — is missed because of it.
     */
    val minSideGrade: Double = 0.30,
    /** How far inside a portal [minSideGrade] is looked for, metres. */
    val sideSearchM: Double = 50.0,
    /**
     * The line between the portals is the road through the tunnel: its grade is at most this. Tunnels
     * found: −9.6 to +4.6 %. At 15 % the Roselend dam crest of `sample.gpx` (+13.6 %, a garbage
     * terrain reading) gets through; at 8 % Moines (−9.6 %) is lost.
     */
    val maxChordGrade: Double = 0.12,
    /**
     * The terrain rises at least this above the portal line somewhere between the portals, metres —
     * and already within [faceReachM] of **each** portal.
     */
    val minHeightM: Double = 15.0,
    /**
     * How far inside each portal the terrain must already stand [minHeightM] above the portal line,
     * metres: a mountain face, not the far wall of a stream gully under a bridge, which a terrain
     * model reads as a notch (review of E4: `sample.gpx` D 109 mirrored, a 70 m real hill flattened
     * without it, rejected with it). Every tunnel of the tuning set and every negative is unchanged
     * from 75 to 150 m; a larger height there (20 m) loses the first Nesque tunnel on Mapterhorn.
     */
    val faceReachM: Double = 100.0,
    /**
     * The terrain never dips more than this below the portal line, metres: a gorge read beside the
     * road, or open ground between two tunnels on a descending road, is not under a mountain.
     */
    val maxBelowChordM: Double = 3.0,
    /**
     * A valley **inside** a candidate — a portal-like exit followed by a portal-like entry — must
     * stay at least this above the portal line, metres; lower, it is open road between two tunnels and
     * the candidate is two (Gorges de la Bourne). Grands Goulets keeps a 700 m terrace 80 m up.
     */
    val minValleyHeightM: Double = 15.0,
    /** Shortest tunnel, metres between portals (the held-out rule's lower bound). */
    val minLengthM: Double = 50.0,
    /** Longest tunnel, metres between portals. Longest found: Croix-Rousse, 1 790 m. */
    val maxLengthM: Double = 3000.0,
    /**
     * Straight-line distance between the portals over the distance along the road, at least this.
     * Tunnels: 0.90–1.00; hairpin roads that a looser threshold set took for tunnels: 0.43 and 0.67.
     */
    val minStraightness: Double = 0.8,
) {
    init {
        require(stepM > 0.0) { "stepM must be positive, got $stepM" }
        require(footWindowStations >= 1 && steepWindowStations >= 1) { "grade windows must cover a station" }
        require(sideSearchStations >= 0) { "negative sideSearchM" }
        require(faceReachStations >= 1) { "faceReachM must cover a station" }
        require(maxRoadGrade >= 0.0 && minPortalGrade > 0.0 && minSideGrade > 0.0) { "grades must be positive" }
        require(maxChordGrade >= 0.0 && minHeightM > 0.0 && maxBelowChordM >= 0.0) { "negative threshold" }
        require(minLengthM in 0.0..maxLengthM) { "minLengthM must be in 0..maxLengthM" }
        require(minStraightness in 0.0..1.0) { "minStraightness must be in 0..1, got $minStraightness" }
    }

    private fun stations(
        metres: Double,
        name: String,
    ): Int {
        val r = metres / stepM
        val k = r.roundToInt()
        require(abs(r - k) < 1e-9) { "$name = $metres m is not a whole number of $stepM m stations" }
        return k
    }

    /** [footWindowM] in stations (4). */
    internal val footWindowStations: Int get() = stations(footWindowM, "footWindowM")

    /** [steepWindowM] in stations (10). */
    internal val steepWindowStations: Int get() = stations(steepWindowM, "steepWindowM")

    /** [sideSearchM] in stations (10). */
    internal val sideSearchStations: Int get() = stations(sideSearchM, "sideSearchM")

    /** [faceReachM] in stations (20). */
    internal val faceReachStations: Int get() = stations(faceReachM, "faceReachM")

    companion object {
        /** The E4 thresholds on a 5 m grid. */
        val DEFAULT: TunnelDetectorOptions = TunnelDetectorOptions()
    }
}
