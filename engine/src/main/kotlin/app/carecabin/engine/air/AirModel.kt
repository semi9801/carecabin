package app.carecabin.engine.air

import kotlin.math.exp
import kotlin.math.ln

/** All values are provisional model assumptions, not sensor measurements. */
object AirConstants {
    const val OUTDOOR_PPM = 420.0
    const val INITIAL_PPM = 600.0
    // Uniform provisional rate per occupant; no age or body-size inference.
    const val PER_OCCUPANT_GENERATION_M3_S = 5.2e-6
}

data class AirInputs(
    /** Total people in the cabin, including the driver. */
    val occupantCount: Int,
    val volumeM3: Double = 3.0,
    val recirculating: Boolean = true,
    val fanLevel: Int = 2,
    val airChangesPerHourOverride: Double? = null,
    /** Sum of open window gaps in cm (percent × 50 cm, provisional). */
    val windowOpenCm: Double = 0.0,
    val speedKmh: Double = 0.0,
) {
    init {
        require(occupantCount >= 0)
        require(volumeM3.isFinite() && volumeM3 > 0)
        require(fanLevel in 0..8)
        require(airChangesPerHourOverride == null ||
            (airChangesPerHourOverride.isFinite() && airChangesPerHourOverride >= 0))
        require(windowOpenCm.isFinite() && windowOpenCm >= 0)
        require(speedKmh.isFinite() && speedKmh >= 0)
    }

    val generationPpmPerSecond: Double get() =
        occupantCount * AirConstants.PER_OCCUPANT_GENERATION_M3_S * 1e6 / volumeM3
    val lambdaPerHour: Double get() = airChangesPerHourOverride
        ?: (LambdaTable.perHour(recirculating, fanLevel) + LambdaTable.windowPerHour(windowOpenCm, speedKmh))
            .coerceAtMost(LambdaTable.MAX_PER_HOUR)
    val lambdaPerSecond: Double get() = lambdaPerHour / 3600
}

object LambdaTable {
    const val MAX_PER_HOUR = 150.0

    /** Provisional: +6 h⁻¹ per open cm at low speed, scaled by (1 + v/50). */
    fun windowPerHour(openCm: Double, speedKmh: Double): Double = 6.0 * openCm * (1 + speedKmh / 50)

    fun perHour(recirculating: Boolean, fanLevel: Int): Double {
        require(fanLevel in 0..8)
        val index = if (fanLevel == 0) 0 else (fanLevel + 1) / 2
        return (if (recirculating) listOf(0.6, 1.0, 1.2, 1.5, 1.8)
            else listOf(8.0, 20.0, 35.0, 55.0, 80.0))[index]
    }
}

class AirModel(initialPpm: Double = AirConstants.INITIAL_PPM) {
    var ppm: Double = initialPpm
        private set

    init { require(initialPpm.isFinite() && initialPpm in AirConstants.OUTDOOR_PPM..10_000.0) }

    /** One Euler step; the host must split long gaps into <= 1 second ticks. */
    fun step(inputs: AirInputs, dtSeconds: Double = 1.0): Double {
        require(dtSeconds.isFinite() && dtSeconds > 0 && dtSeconds <= 1)
        ppm = (ppm + dtSeconds * (inputs.generationPpmPerSecond -
            inputs.lambdaPerSecond * (ppm - AirConstants.OUTDOOR_PPM)))
            .coerceIn(AirConstants.OUTDOOR_PPM, 10_000.0)
        return ppm
    }

    /** Infinity means unreachable under the current constant input assumptions. */
    fun secondsUntil(targetPpm: Double, inputs: AirInputs): Double {
        require(targetPpm.isFinite() && targetPpm in AirConstants.OUTDOOR_PPM..10_000.0)
        if (ppm >= targetPpm) return 0.0
        val lambda = inputs.lambdaPerSecond
        val generation = inputs.generationPpmPerSecond
        if (lambda == 0.0) return if (generation > 0) (targetPpm - ppm) / generation else Double.POSITIVE_INFINITY
        val steady = AirConstants.OUTDOOR_PPM + generation / lambda
        if (targetPpm >= steady) return Double.POSITIVE_INFINITY
        return -ln((targetPpm - steady) / (ppm - steady)) / lambda
    }

    /** Outside-air seconds needed to bring the cabin down to [targetPpm], clamped to 60..180 s. */
    fun freshAirSecondsTo(targetPpm: Double, freshInputs: AirInputs): Double {
        val lambda = freshInputs.lambdaPerSecond
        if (lambda <= 0.0 || ppm <= targetPpm) return 60.0
        val steady = AirConstants.OUTDOOR_PPM + freshInputs.generationPpmPerSecond / lambda
        if (targetPpm <= steady) return 180.0
        return (kotlin.math.ln((ppm - steady) / (targetPpm - steady)) / lambda).coerceIn(60.0, 180.0)
    }

    fun analyticalAfter(inputs: AirInputs, seconds: Double): Double {
        require(seconds.isFinite() && seconds >= 0)
        val lambda = inputs.lambdaPerSecond
        if (lambda == 0.0) return ppm + inputs.generationPpmPerSecond * seconds
        val steady = AirConstants.OUTDOOR_PPM + inputs.generationPpmPerSecond / lambda
        return steady + (ppm - steady) * exp(-lambda * seconds)
    }
}
