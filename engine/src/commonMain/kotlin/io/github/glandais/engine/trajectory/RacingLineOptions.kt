package io.github.glandais.engine.trajectory

/**
 * Which part of the road the racing line is allowed to use.
 *
 * `n` is the lateral offset from the reference line, **positive to the left**.
 */
enum class CorridorMode {
    /**
     * The rider's own lane, right-hand traffic: `[−h, 0]`.
     *
     * The default, and the box is deliberately *not* centred on the lane. Keeping `n = 0` feasible
     * means a straight is never displaced — the solver has no reason to move where moving buys
     * nothing — and the line can never cross into oncoming traffic.
     */
    LANE,

    /** The rider's own lane, left-hand traffic: `[0, +h]`. UK, AU, JP, IE. */
    LANE_LEFT,

    /**
     * The whole carriageway: `[−h, +h]`.
     *
     * Closed roads and time trials only. This is the mode that produces the attractive numbers,
     * and it is illegal on any open road — hence opt-in, never inferred.
     */
    FULL_ROAD,
}

/**
 * What kind of bend a [CornerSpan] is.
 *
 * `ROUNDABOUT` and `CHICANE` are absent on purpose: nothing detects them yet, and an enum constant
 * no producer can emit is a claim the API cannot keep. They arrive with the detector that finds
 * them.
 */
enum class CornerKind {
    /** Too open for cornering to bind at any realistic speed. */
    GENTLE,

    /** An ordinary bend. */
    CORNER,

    /** A near-reversal — an alpine switchback or a U-turn. */
    HAIRPIN,
}

/**
 * Options for the racing-line stage.
 *
 * Note there is **no friction coefficient here.** vcyclist parameterises grip by
 * `Cyclist.maxLeanAngleDeg`, which *is* µ in disguise (`µ ≡ tan θ_lean`), so a second friction
 * model at this layer would be double-counting. The corridor is geometry; grip belongs to the
 * rider.
 *
 * @property defaultRoadWidthM width assumed where the path carries none — two 3 m lanes. The
 *   corridor half-width is linear in this, so it is the single most consequential number here
 *   when no file supplies a width. See `docs/design/racing-line.md` §12 question 1.
 * @property edgeMarginM kept clear of each edge: half a handlebar plus the gutter
 * @property corridor which part of the road may be used
 * @property cornerEnterRadiusM a bend opens when the radius drops below this
 * @property cornerExitRadiusM and stays open until it rises above this — hysteresis, so a corner
 *   does not flicker at its own threshold
 * @property minCornerLengthM spans shorter than this are noise, not corners
 * @property minCornerTurnDeg and so are spans that barely turn
 * @property hairpinTurnDeg at or beyond this a corner is a [CornerKind.HAIRPIN]
 * @property gentleRadiusM at or beyond this a corner is [CornerKind.GENTLE]
 * @property regularityFactor `|n| ≤ regularityFactor/|κ|`, keeping the offset map away from the
 *   fold at `1 − κn = 0`
 * @property selfProximityGapM how far apart along the path two points must be before they are
 *   treated as different pieces of road that could collide
 * @property straightRunM a straight run at least this long gets a pin at its midpoint
 * @property straightRadiusM what counts as straight for that purpose
 * @property widthSmoothWindowM arclength kernel applied to the width before it becomes a
 *   constraint — a step in the width is a step in the feasible set
 * @property objectiveRadiusM bends gentler than this are left out of the objective entirely.
 *   Downstream, `MaxSpeedComputer` applies no cornering limit at all beyond 200 m of radius, so
 *   straightening anything gentler buys exactly zero time — while still costing lateral movement.
 *   Worse, curvature at that scale is mostly measurement noise, and the objective's `n'' ≈ −κ`
 *   response to noise is a double integral, which is a random walk: left unmasked the solver
 *   *amplifies* jitter instead of ignoring it. This is a hard on/off mask; the graded time
 *   weighting that replaces it is a separate task.
 * @property steeringLengthM `L_R`; the `∫n'² ds` term is weighted `L_R⁻²`. To second order that
 *   integral is exactly the excess length a weaving line pays for, and it is also the
 *   steering-rate cost, so one term buys both.
 * @property centeringLengthM `L_C`; the centring prior is weighted `L_C⁻⁴`. Besides pinning
 *   straights, it is what makes the Hessian *strictly* positive definite — the other two terms
 *   have a null space between them.
 * @property maxNewtonIterations cap on projected-Newton iterations. The design predicts three to
 *   six; measured, a tight single corner takes around 40 and everything else far fewer. The count
 *   is bounded by how hard the corners are, **not** by route length — sixteen corners over 1286
 *   stations converge in 16 iterations, four corners in 12 — so a generous cap costs nothing on a
 *   long route and is what keeps a hard one from being truncated mid-solve.
 * @property gradientTolerance stopping threshold on `‖g_F‖_∞` **relative to the initial
 *   gradient**. Relative because the energy scales with route length in metres, so an absolute
 *   threshold would mean something different on every route.
 * @property boundEpsilonM how close to a corridor bound counts as being on it
 */
data class RacingLineOptions(
    val defaultRoadWidthM: Double = 6.0,
    val edgeMarginM: Double = 0.5,
    val corridor: CorridorMode = CorridorMode.LANE,
    val cornerEnterRadiusM: Double = 120.0,
    val cornerExitRadiusM: Double = 250.0,
    val minCornerLengthM: Double = 8.0,
    val minCornerTurnDeg: Double = 8.0,
    val hairpinTurnDeg: Double = 150.0,
    val gentleRadiusM: Double = 100.0,
    val regularityFactor: Double = 0.85,
    val selfProximityGapM: Double = 60.0,
    val straightRunM: Double = 150.0,
    val straightRadiusM: Double = 500.0,
    val widthSmoothWindowM: Double = 20.0,
    val objectiveRadiusM: Double = 200.0,
    val steeringLengthM: Double = 20.0,
    val centeringLengthM: Double = 60.0,
    val maxNewtonIterations: Int = 200,
    val gradientTolerance: Double = 1e-8,
    val boundEpsilonM: Double = 1e-6,
    val curvature: CurvatureOptions = CurvatureOptions.DEFAULT,
) {
    init {
        require(defaultRoadWidthM > 0.0) { "defaultRoadWidthM must be > 0" }
        require(edgeMarginM >= 0.0) { "edgeMarginM must be >= 0" }
        require(cornerEnterRadiusM > 0.0 && cornerExitRadiusM > 0.0) { "corner radii must be > 0" }
        require(cornerExitRadiusM >= cornerEnterRadiusM) {
            "cornerExitRadiusM ($cornerExitRadiusM) must be >= cornerEnterRadiusM " +
                "($cornerEnterRadiusM): a corner has to be harder to open than to keep open, or " +
                "the hysteresis runs backwards and corners flicker"
        }
        require(regularityFactor > 0.0 && regularityFactor < 1.0) {
            "regularityFactor must be in (0, 1) — at 1.0 the offset map folds"
        }
        require(selfProximityGapM > 0.0) { "selfProximityGapM must be > 0" }
        require(widthSmoothWindowM >= 0.0) { "widthSmoothWindowM must be >= 0" }
        require(objectiveRadiusM > 0.0) { "objectiveRadiusM must be > 0" }
        require(steeringLengthM > 0.0) { "steeringLengthM must be > 0" }
        require(centeringLengthM > 0.0) { "centeringLengthM must be > 0" }
        require(maxNewtonIterations > 0) { "maxNewtonIterations must be > 0" }
        require(gradientTolerance > 0.0) { "gradientTolerance must be > 0" }
        require(boundEpsilonM > 0.0) { "boundEpsilonM must be > 0" }
    }

    companion object {
        val DEFAULT = RacingLineOptions()
    }
}
