package app.carecabin.engine.motion

import app.carecabin.engine.model.LocationSample
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.Tbt
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_RADIUS_M = 6_371_000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Signed smallest difference b - a in degrees, in (-180, 180]. */
    fun wrapTo180(deg: Double): Double {
        var d = deg % 360.0
        if (d > 180) d -= 360
        if (d <= -180) d += 360
        return d
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}

/**
 * All values are provisional (development plan 4-1); tune with scenarios, not with claims of efficacy.
 * 2026-11-01: plan values (D_ref 1630, τ 2 s, yaw EMA 0.4) damped a 0.05 Hz winding road to MSI ≈ 33.
 * Retuned so both plan reference cases hold: RMS 2.0 m/s² winding road 10 min ≈ 70, 50 m circle 30 km/h ≈ 25–45.
 */
data class MotionConstants(
    val wLat: Double = 1.0,
    val wLon: Double = 0.6,
    val doseTauSec: Double = 600.0,
    val doseRef: Double = 1300.0,
    val lowPassTauSec: Double = 1.0,
    val yawSmoothing: Double = 0.7,
    val lookAheadM: Double = 3000.0,
    val previewRef: Double = 3.0,
    val curveAlertThreshold: Double = 1.5,
    val curveAlertHorizonSec: Double = 120.0,
    val maxPlausibleSpeed: Double = 70.0,
    val minHeadingSpeed: Double = 2.0,
    val steeringRatio: Double = 14.0,
    val wheelbaseM: Double = 2.9,
    val gapFreezeSec: Double = 30.0,
)

data class MotionState(
    val speedMps: Double,
    val lateralAccel: Double,
    val longitudinalAccel: Double,
    val dose: Double,
    val preview: Double,
    val curveAlert: Boolean,
    val nearestTurnM: Double?,
    val base: Double,
) {
    val speedKmh: Double get() = speedMps * 3.6

    /** Seat MSI 0..100; null when the occupant switched care off. */
    fun msi(seat: Seat, sensitivityFactor: Double?): Double? =
        sensitivityFactor?.let { (base * it * seat.positionFactor).coerceIn(0.0, 100.0) }
}

/**
 * Seat motion-sickness index from location deltas (speed, yaw rate → lateral acceleration),
 * a 10-minute decaying dose, and a 3 km TBT look-ahead.
 */
class MotionEngine(private val c: MotionConstants = MotionConstants()) {
    private var last: LocationSample? = null
    private val rawSpeeds = ArrayDeque<Double>()
    private var speed = 0.0
    private var omega = 0.0
    private var aLon = 0.0
    private var latLp = 0.0
    private var lonLp = 0.0
    private var dose = 0.0
    private var lastFixSec = 0.0
    private var clockSec = 0.0
    var state: MotionState = MotionState(0.0, 0.0, 0.0, 0.0, 0.0, false, null, 0.0)
        private set

    /**
     * Advance one tick of [dtSec] seconds. [sample] may be null (tunnel, no fix). [steeringDeg] is used
     * only when no usable heading arrives.
     */
    fun update(
        dtSec: Double,
        sample: LocationSample?,
        steeringDeg: Float? = null,
        tbt: List<Tbt> = emptyList(),
    ): MotionState {
        require(dtSec > 0 && dtSec <= 5)
        clockSec += dtSec
        var aLat = 0.0
        var gotFix = false
        val prev = last
        if (sample != null && prev != null) {
            val dt = (sample.tMillis - prev.tMillis) / 1000.0
            if (dt > 0.2 && dt < 3.0) {
                val vRaw = Geo.haversineM(prev.lat, prev.lon, sample.lat, sample.lon) / dt
                if (vRaw <= c.maxPlausibleSpeed) {
                    gotFix = true
                    rawSpeeds.addLast(vRaw); if (rawSpeeds.size > 3) rawSpeeds.removeFirst()
                    val median = rawSpeeds.sorted()[rawSpeeds.size / 2]
                    val newSpeed = speed + 0.5 * (median - speed)
                    aLon += 0.3 * ((newSpeed - speed) / dt - aLon)
                    speed = newSpeed
                    val heading = sample.headingDeg?.takeIf { sample.confidence >= 0.3f }?.toDouble()
                    val prevHeading = prev.headingDeg?.toDouble()
                    val w = if (speed < c.minHeadingSpeed) 0.0 else if (heading != null && prevHeading != null) {
                        Math.toRadians(Geo.wrapTo180(heading - prevHeading)) / dt
                    } else {
                        steeringOmega(steeringDeg)
                    }
                    omega += c.yawSmoothing * (w - omega)
                    aLat = speed * omega
                    last = sample
                    lastFixSec = clockSec
                }
            } else {
                last = sample
            }
        } else if (sample != null) {
            last = sample
            lastFixSec = clockSec
        }
        if (!gotFix) {
            // No fix: keep last speed, steering-only yaw.
            omega += c.yawSmoothing * (steeringOmega(steeringDeg) - omega)
            aLat = speed * omega
        }
        val alpha = dtSec / (c.lowPassTauSec + dtSec)
        latLp += alpha * (aLat - latLp)
        lonLp += alpha * (aLon - lonLp)
        val stimulus = c.wLat * latLp * latLp + c.wLon * lonLp * lonLp
        val frozen = clockSec - lastFixSec > c.gapFreezeSec && steeringDeg == null
        dose = dose * exp(-dtSec / c.doseTauSec) + if (frozen) 0.0 else stimulus * dtSec

        val ahead = tbt.filter { it.distanceM in 0.0..c.lookAheadM }
        val preview = ahead.sumOf { it.kind.weight * (1 - it.distanceM / c.lookAheadM) }
        val nearest = ahead.filter { it.kind.weight > 0 }.minOfOrNull { it.distanceM }
        val curveAlert = preview >= c.curveAlertThreshold && nearest != null &&
            nearest <= maxOf(speed, 8.0) * c.curveAlertHorizonSec
        val previewNorm = min(preview / c.previewRef, 1.0)
        val base = 100 * (0.75 * min(dose / c.doseRef, 1.33) + 0.25 * previewNorm)
        state = MotionState(speed, aLat, aLon, dose, preview, curveAlert, nearest, base)
        return state
    }

    private fun steeringOmega(steeringDeg: Float?): Double {
        if (steeringDeg == null || speed < c.minHeadingSpeed) return 0.0
        val wheel = Math.toRadians(steeringDeg / c.steeringRatio)
        return speed * kotlin.math.tan(wheel) / c.wheelbaseM
    }

    fun reset() {
        last = null; rawSpeeds.clear(); speed = 0.0; omega = 0.0; aLon = 0.0
        latLp = 0.0; lonLp = 0.0; dose = 0.0; lastFixSec = clockSec
        state = MotionState(0.0, 0.0, 0.0, 0.0, 0.0, false, null, 0.0)
    }
}
