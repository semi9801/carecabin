package app.carecabin.engine.motion

import app.carecabin.engine.model.LocationSample
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.Tbt
import app.carecabin.engine.model.TurnKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MotionEngineTest {
    /** Integrates speed/yaw into 1 Hz samples, like the scenario player. */
    private class Driver(var lat: Double = 37.5, var lon: Double = 127.0, var heading: Double = 0.0) {
        var t = 0L
        fun next(speedKmh: Double, yawDegPerSec: Double): LocationSample {
            heading = (heading + yawDegPerSec + 360) % 360
            val v = speedKmh / 3.6; val h = Math.toRadians(heading)
            lat += Math.toDegrees(v * cos(h) / Geo.EARTH_RADIUS_M)
            lon += Math.toDegrees(v * sin(h) / (Geo.EARTH_RADIUS_M * cos(Math.toRadians(lat))))
            t += 1000
            return LocationSample(t, lat, lon, heading.toFloat())
        }
    }

    private fun MotionState.passenger() = msi(Seat.PASSENGER, 1.0)!!

    @Test fun headingWrapsAcrossNorth() {
        assertEquals(2.0, Geo.wrapTo180(1.0 - 359.0), 1e-9)
        assertEquals(-2.0, Geo.wrapTo180(359.0 - 1.0), 1e-9)
    }

    @Test fun oneDegreeLatitudeIsAbout111Km() {
        assertEquals(111_195.0, Geo.haversineM(37.0, 127.0, 38.0, 127.0), 111_195.0 * 0.005)
    }

    @Test fun straightHighwayStaysLow() {
        val e = MotionEngine(); val d = Driver()
        var s = e.state
        repeat(20 * 60) { s = e.update(1.0, d.next(60.0, 0.0)) }
        assertTrue(s.passenger() < 5, "MSI ${s.passenger()}")
        assertEquals(60.0, s.speedKmh, 1.0)
    }

    @Test fun windingRoadForTenMinutesReachesAboutSeventy() {
        val e = MotionEngine(); val d = Driver()
        var s = e.state
        // 45 km/h, yaw amplitude 13 °/s, period 20 s → RMS lateral ≈ 2.0 m/s²
        repeat(600) { k -> s = e.update(1.0, d.next(45.0, 13.0 * sin(2 * PI * k / 20.0))) }
        val peak = s.passenger()
        assertTrue(peak in 55.0..80.0, "MSI after 10 min winding: $peak")
        repeat(7 * 60) { s = e.update(1.0, d.next(45.0, 0.0)) }
        assertTrue(s.passenger() < peak * 0.6, "decays on straight road: ${s.passenger()} vs $peak")
    }

    @Test fun circleIncreasesMonotonicallyToModerateLevel() {
        val e = MotionEngine(); val d = Driver()
        // radius 50 m at 30 km/h → ω = v/r = 0.1667 rad/s = 9.55 °/s, a_lat ≈ 1.39 m/s²
        var prev = 0.0
        var s = e.state
        for (k in 0 until 600) {
            s = e.update(1.0, d.next(30.0, 9.55))
            if (k > 5) assertTrue(s.passenger() >= prev - 1e-6)
            prev = s.passenger()
        }
        assertTrue(s.passenger() in 20.0..45.0, "circle MSI ${s.passenger()}")
    }

    @Test fun gpsJumpIsDiscarded() {
        val e = MotionEngine(); val d = Driver()
        repeat(60) { e.update(1.0, d.next(50.0, 0.0)) }
        val before = e.state.passenger()
        val jump = d.next(50.0, 0.0).let { it.copy(lat = it.lat + 0.0045) } // ~500 m in 1 s
        val after = e.update(1.0, jump)
        assertTrue(after.passenger() - before < 2, "no spike: $before → ${after.passenger()}")
        assertTrue(after.speedKmh < 60)
    }

    @Test fun steeringKeepsEstimatingWithoutHeading() {
        val e = MotionEngine(); val d = Driver()
        repeat(30) { e.update(1.0, d.next(40.0, 0.0)) }
        var s = e.state
        repeat(120) { s = e.update(1.0, d.next(40.0, 0.0).copy(headingDeg = null), steeringDeg = 90f) }
        assertTrue(s.lateralAccel > 0.5, "steering-based lateral accel ${s.lateralAccel}")
    }

    @Test fun curveClusterAheadRaisesAlert() {
        val e = MotionEngine(); val d = Driver()
        repeat(30) { e.update(1.0, d.next(60.0, 0.0)) }
        val tbt = listOf(400.0, 800.0, 1200.0, 1600.0).map { Tbt(TurnKind.SHARP, it) }
        val s = e.update(1.0, d.next(60.0, 0.0), tbt = tbt)
        assertTrue(s.curveAlert, "preview ${s.preview}")
        assertFalse(e.update(1.0, d.next(60.0, 0.0), tbt = listOf(Tbt(TurnKind.SHARP, 2400.0))).curveAlert)
    }

    @Test fun careOffSeatHasNoIndex() {
        assertEquals(null, MotionEngine().state.msi(Seat.REAR_LEFT, null))
    }
}
