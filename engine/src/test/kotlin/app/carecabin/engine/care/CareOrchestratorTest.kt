package app.carecabin.engine.care

import app.carecabin.engine.model.Actuator
import app.carecabin.engine.model.CarePermissions
import app.carecabin.engine.model.Gear
import app.carecabin.engine.model.Occupant
import app.carecabin.engine.model.Preset
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.Sensitivity
import app.carecabin.engine.model.VehicleSignals
import app.carecabin.engine.model.VoiceIntent
import app.carecabin.engine.sim.Capabilities
import app.carecabin.engine.sim.SimVehicle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CareOrchestratorTest {
    private val preset = Preset(
        occupants = Seat.entries.associateWith { Occupant(it, sensitivity = if (it == Seat.REAR_RIGHT) Sensitivity.HIGH else Sensitivity.NORMAL) },
    )

    private class Rig(val supported: Set<Actuator> = Capabilities.full) {
        val orch = CareOrchestrator()
        val car = SimVehicle(supported).apply { setOccupancy(Seat.entries.toSet()); setGear(Gear.DRIVE) }
        val sent = mutableListOf<CareCommand>()
        val spoken = mutableListOf<Speech>()
        var t = 0.0

        fun tick(
            msi: Map<Seat, Double>,
            preset: Preset,
            co2: Double = 700.0,
            curve: Boolean = false,
            speed: Double = 50.0,
            wiper: Boolean = false,
            voice: List<VoiceIntent> = emptyList(),
            signals: VehicleSignals = car.signals.copy(wiperOn = wiper),
        ): CareOutput {
            val out = orch.evaluate(
                CareInputs(
                    tSec = t, msi = msi, curveAlert = curve, nearestTurnM = if (curve) 1500.0 else null,
                    co2Ppm = co2, secondsTo1000 = if (co2 >= 1000) 0.0 else 9999.0, freshNeededSec = 120.0,
                    speedKmh = speed, moving = speed > 3, signals = signals, preset = preset,
                    supported = supported, tripDrivingSec = t, voice = voice,
                ),
            )
            out.commands.forEach { car.apply(it); sent += it }
            spoken += out.speech
            t += 1
            return out
        }
    }

    @Test fun driverZoneAndSeatAreNeverChanged() {
        val r = Rig()
        repeat(600) { r.tick(mapOf(Seat.DRIVER to 90.0, Seat.REAR_RIGHT to 80.0), preset, co2 = 1600.0) }
        assertTrue(r.sent.none { it.actuator == Actuator.ZoneTemp(Seat.DRIVER) || it.actuator == Actuator.SeatVent(Seat.DRIVER) || it.actuator == Actuator.Window(Seat.DRIVER) })
        assertEquals(220, r.car.signals.actuators[Actuator.ZoneTemp(Seat.DRIVER)])
    }

    @Test fun rainKeepsWindowsClosedButVentilates() {
        val r = Rig()
        repeat(60) { r.tick(mapOf(Seat.REAR_RIGHT to 60.0), preset, wiper = true) }
        assertTrue(r.sent.none { it.actuator is Actuator.Window && it.value > 0 })
        assertEquals(2, r.car.signals.actuators[Actuator.SeatVent(Seat.REAR_RIGHT)])
        assertEquals(1, r.car.signals.actuators[Actuator.FanFace(Seat.REAR_RIGHT)])
    }

    @Test fun highSpeedKeepsWindowsClosed() {
        val r = Rig()
        repeat(60) { r.tick(mapOf(Seat.REAR_RIGHT to 60.0), preset, speed = 95.0) }
        assertTrue(r.sent.none { it.actuator is Actuator.Window && it.value > 0 })
    }

    @Test fun cautionOpensRearWindowSlightlyWhenDryAndSlow() {
        val r = Rig()
        repeat(30) { r.tick(mapOf(Seat.REAR_RIGHT to 60.0), preset) }
        assertEquals(5, r.car.signals.actuators[Actuator.Window(Seat.REAR_RIGHT)])
    }

    @Test fun airAndSicknessShareOneOutsideAirCommand() {
        val r = Rig()
        r.tick(mapOf(Seat.REAR_RIGHT to 35.0), preset, co2 = 1100.0)
        assertEquals(1, r.sent.count { it.actuator == Actuator.Recirculation })
        assertEquals(0, r.car.signals.actuators[Actuator.Recirculation])
    }

    @Test fun hysteresisLimitsStageFlapping() {
        val r = Rig()
        var changes = 0
        var last = SickStage.OBSERVE
        for (k in 0 until 180) {
            val m = if (k % 2 == 0) 49.0 else 51.0
            val st = r.tick(mapOf(Seat.REAR_LEFT to m), preset).ui.seatStages[Seat.REAR_LEFT]!!
            if (st != last) { changes++; last = st }
        }
        assertTrue(changes <= 2, "stage changes in 3 min: $changes") // MILD then CAUTION at most
    }

    @Test fun restoreBringsBackOriginalsAndPausesTenMinutes() {
        val r = Rig()
        repeat(60) { r.tick(mapOf(Seat.REAR_RIGHT to 60.0), preset, co2 = 1200.0) }
        assertTrue(r.car.signals.actuators[Actuator.ZoneTemp(Seat.REAR_RIGHT)] != 230)
        r.tick(mapOf(Seat.REAR_RIGHT to 60.0), preset, co2 = 1200.0, voice = listOf(VoiceIntent.RESTORE))
        assertEquals(230, r.car.signals.actuators[Actuator.ZoneTemp(Seat.REAR_RIGHT)])
        assertEquals(1, r.car.signals.actuators[Actuator.Recirculation])
        assertEquals(3, r.car.signals.actuators[Actuator.FanSpeed])
        assertEquals(0, r.car.signals.actuators[Actuator.Window(Seat.REAR_RIGHT)])
        val before = r.sent.size
        repeat(590) { r.tick(mapOf(Seat.REAR_RIGHT to 60.0), preset, co2 = 1200.0) }
        assertEquals(before, r.sent.size, "no climate commands while paused")
    }

    @Test fun pauseStillLetsAirSafetyActWhenCo2IsHigh() {
        val r = Rig()
        r.tick(emptyMap(), preset, co2 = 900.0, voice = listOf(VoiceIntent.RESTORE))
        repeat(30) { r.tick(emptyMap(), preset, co2 = 1200.0) }
        assertEquals(1, r.car.signals.actuators[Actuator.Recirculation], "no air care at 1,200 while paused")
        repeat(30) { r.tick(emptyMap(), preset, co2 = 1600.0) }
        assertEquals(0, r.car.signals.actuators[Actuator.Recirculation], "air safety exception at 1,600")
    }

    @Test fun sameActuatorChangedTwiceRestoresFirstValue() {
        val r = Rig()
        repeat(20) { r.tick(mapOf(Seat.REAR_RIGHT to 35.0), preset, co2 = 1600.0) } // fan +2 (breath2)
        repeat(400) { r.tick(mapOf(Seat.REAR_RIGHT to 0.0), preset, co2 = 600.0) }
        assertEquals(3, r.car.signals.actuators[Actuator.FanSpeed])
        assertEquals(1, r.car.signals.actuators[Actuator.Recirculation])
    }

    @Test fun permissionsOffMeansNoWindowAndNoSeat() {
        val r = Rig()
        val p = preset.copy(permissions = CarePermissions(window = false, seat = false))
        repeat(60) { r.tick(mapOf(Seat.REAR_RIGHT to 60.0), p) }
        assertTrue(r.sent.none { it.actuator is Actuator.Window || it.actuator is Actuator.SeatVent })
    }

    @Test fun rearZoneFallsBackWhenRow1Only() {
        val r = Rig(Capabilities.row1Only)
        val p = Preset(occupants = mapOf(Seat.DRIVER to Occupant(Seat.DRIVER), Seat.REAR_RIGHT to Occupant(Seat.REAR_RIGHT, sensitivity = Sensitivity.HIGH)))
        val signals = r.car.signals.copy(occupied = setOf(Seat.DRIVER, Seat.REAR_RIGHT))
        repeat(30) { r.tick(mapOf(Seat.REAR_RIGHT to 40.0), p, signals = signals) }
        assertTrue(r.sent.none { it.actuator == Actuator.ZoneTemp(Seat.REAR_RIGHT) })
        assertTrue(r.sent.any { it.actuator == Actuator.ZoneTemp(Seat.PASSENGER) }, "falls back to the empty passenger zone")
        assertTrue(r.sent.any { it.actuator == Actuator.FanFace(Seat.PASSENGER) })
    }

    @Test fun restQuestionAndAnswer() {
        val r = Rig()
        val area = app.carecabin.engine.model.RestArea("테스트 휴게소", 0.0, 0.0)
        var out = r.tick(mapOf(Seat.REAR_RIGHT to 75.0), preset)
        assertEquals(Question.REST, out.ui.question)
        out = r.orch.evaluate(
            CareInputs(r.t, mapOf(Seat.REAR_RIGHT to 75.0), false, null, 700.0, 9999.0, 120.0, 50.0, true,
                r.car.signals, preset, Capabilities.full, r.t, listOf(VoiceIntent.YES), restArea = area),
        )
        assertEquals(area, out.waypoint)
        assertTrue(out.speech.any { it.text.contains("테스트 휴게소") })
    }

    @Test fun speechIsRateLimited() {
        val r = Rig()
        repeat(900) { k -> r.tick(emptyMap(), preset, co2 = if (k % 200 < 5) 1100.0 else 700.0) }
        val air = r.spoken.filter { it.kind == SpeechKind.AIR }
        assertTrue(air.size <= 2, "air speech in 15 min: ${air.size}")
    }
}
