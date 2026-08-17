package io.github.glandais.engine.physics

import io.github.glandais.engine.EngineConstants

/**
 * The catalog of ways a simulated rider can choose its power, and the single place that turns a
 * choice into a [CyclistPowerProvider].
 *
 * ## Why this exists
 *
 * Four surfaces build providers from user input — the CLI (`CyclistMixin`), the JS façade
 * (`EngineJsApi`), the WASI façade (`WasiOptions`) and, through JS, the browser demo. Each used to
 * carry its own `when (type)`, its own default power and its own decorator ordering. They drifted,
 * three times over:
 *
 * - `g29` — three tasks landed in the core and the CLI without reaching JS.
 * - task `41` — five ledger entries (R9, R15, R16, R18, R19) did the same.
 * - task `40` — the demo *broke*, because R17 renamed a `type` string on one surface only.
 *
 * A test would have caught the third and not the first two. So the fix is structural: the mapping
 * exists once, here, and every surface parses input into a [CyclistPowerSpec] and calls
 * [toProvider]. Adding a model means adding a [PowerModel] entry, and the `when` below stops
 * compiling until it is handled — on every target at once.
 *
 * What this does **not** cover: a surface can still fail to *expose* a field it never reads. The
 * JS façade guards that with a strict unknown-key check on its DTOs, and WASI with `requireOnly`.
 */
enum class PowerModel(
    /** Wire name, identical on the CLI, the JS DTO and the WASI JSON ABI. */
    val id: String,
) {
    /** Hold [CyclistPowerSpec.powerW] for the whole ride — [PowerProviderConstant]. */
    CONSTANT("constant"),

    /** Fade with work accumulated above CP (ledger R17) — [PowerProviderDurability]. */
    DURABILITY("durability"),

    /** Spend a W′ reserve, then settle at CP (ledger R16) — [PowerProviderCriticalPower]. */
    CRITICAL_POWER("critical-power"),

    /** Replay `pInputPower` from the input path — [PowerProviderFromData]. */
    FROM_DATA("from_data"),
    ;

    override fun toString(): String = id

    companion object {
        /** Wire names in declaration order, for help text and error messages. */
        val ids: List<String> get() = entries.map { it.id }

        /**
         * Parse a wire name, case-insensitively — `"WET"`-style shouting is what people type, and
         * every surface accepted it differently before this existed. `null` when unknown, so each
         * surface can raise the error type its callers expect.
         */
        fun fromIdOrNull(id: String): PowerModel? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * A rider's power strategy, as chosen by a user on any surface.
 *
 * Defaults are [EngineConstants] values, never literals — a surface that hardcodes its own is how
 * the JS and WASI façades came to default to 250 W while the CLI defaulted to 280 W for the same
 * "unconfigured rider" (found and fixed in task 43).
 *
 * @property model which fatigue model chooses the target
 * @property powerW the target itself
 * @property criticalPowerW CP, read by [PowerModel.DURABILITY] and [PowerModel.CRITICAL_POWER]
 * @property wPrimeJ W′, read by [PowerModel.CRITICAL_POWER]
 * @property useHarmonics add the harmonic variation of [CyclistPowerProviderBase]
 * @property pacing wrap in [PowerProviderTerrainPacing] (ledger R19)
 * @property maxSlewWPerS wrap in [PowerProviderSlewLimited] (ledger R18); `0` disables
 */
data class CyclistPowerSpec(
    val model: PowerModel = PowerModel.CONSTANT,
    val powerW: Double = EngineConstants.DEFAULT_CYCLIST_POWER_W,
    val criticalPowerW: Double = EngineConstants.DEFAULT_CRITICAL_POWER_W,
    val wPrimeJ: Double = EngineConstants.DEFAULT_W_PRIME_J,
    val useHarmonics: Boolean = false,
    val pacing: Boolean = false,
    val maxSlewWPerS: Double = 0.0,
) {
    /**
     * Build the provider chain.
     *
     * **Composition order is `base → pacing → slew`, and it is not a free choice.** The fatigue
     * model picks a target, terrain pacing redistributes it, and the rate limiter smooths whatever
     * comes out — so [PowerProviderSlewLimited] must see the final signal, or it smooths something
     * that is then stepped again. Defining it here is the point of this class: it used to be
     * written out three times.
     *
     * Decorators apply to [PowerModel.FROM_DATA] too, which is well-defined (pace or smooth a
     * recorded trace) but rarely wanted; callers that replay data usually leave both off.
     */
    fun toProvider(): CyclistPowerProvider {
        var provider = baseProvider()
        if (pacing) provider = PowerProviderTerrainPacing(provider)
        if (maxSlewWPerS > 0.0) provider = PowerProviderSlewLimited(provider, maxSlewWPerS)
        return provider
    }

    /** The undecorated model. Exhaustive over [PowerModel] — that exhaustiveness is the guard. */
    private fun baseProvider(): CyclistPowerProvider =
        when (model) {
            PowerModel.CONSTANT -> PowerProviderConstant(powerW, useHarmonics = useHarmonics)
            PowerModel.DURABILITY ->
                PowerProviderDurability(
                    powerW = powerW,
                    criticalPowerW = criticalPowerW,
                    useHarmonics = useHarmonics,
                )
            PowerModel.CRITICAL_POWER ->
                PowerProviderCriticalPower(
                    powerW = powerW,
                    criticalPowerW = criticalPowerW,
                    wPrimeJ = wPrimeJ,
                    useHarmonics = useHarmonics,
                )
            PowerModel.FROM_DATA -> PowerProviderFromData
        }
}
