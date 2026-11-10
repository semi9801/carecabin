package app.carecabin.engine.sim

import app.carecabin.engine.care.AirStage
import app.carecabin.engine.care.Question
import app.carecabin.engine.care.SickStage
import app.carecabin.engine.care.SpeechKind
import app.carecabin.engine.model.Actuator
import app.carecabin.engine.model.IntentParser
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.VoiceIntent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** End-to-end: the demo scenarios must play through and show the behaviours the demo video relies on. */
class ScenarioRunTest {
    @Test fun soloCommuteRunsAnAirCycleAndNeverTouchesDriverTemperature() {
        val car = SimVehicle()
        val run = ScenarioRun(Scenarios.solo, SimBridge(car))
        var cycles = 0; var prevStage = AirStage.OBSERVE; var maxPpm = 0.0
        val speech = mutableListOf<String>()
        while (true) {
            val r = run.step()
            val st = r.out.care.ui.airStage
            if (st == AirStage.CYCLE && prevStage != AirStage.CYCLE) cycles++
            prevStage = st
            maxPpm = maxOf(maxPpm, r.out.co2Ppm)
            speech += r.out.care.speech.map { it.text }
            assertTrue(r.applied.none { it.first.actuator == Actuator.ZoneTemp(Seat.DRIVER) })
            if (r.finished) break
        }
        assertTrue(cycles >= 2, "air cycles: $cycles")
        assertTrue(maxPpm < 1200, "CO₂ kept near the threshold, max $maxPpm")
        assertTrue(speech.count { it.contains("바깥 공기") } in 1..3)
        assertEquals(1, car.signals.actuators[Actuator.Recirculation], "back to recirculation at the end")
        val report = run.session.report()
        assertTrue(report.goodAirRatio > 0.8, "good air ratio ${report.goodAirRatio}")
    }

    @Test fun mountainScenarioShowsPredictionSeatCareRestStopAndRestore() {
        val car = SimVehicle()
        val run = ScenarioRun(Scenarios.mountain, SimBridge(car))
        var curveSpokenAt: Int? = null
        var cautionAt: Int? = null
        var restAskedAt: Int? = null
        var waypointAt: Int? = null
        var arrivalVent = false
        var restoredAt: Int? = null
        while (true) {
            val r = run.step()
            val ui = r.out.care.ui
            if (curveSpokenAt == null && r.out.care.speech.any { it.kind == SpeechKind.CURVE }) curveSpokenAt = r.tSec
            if (cautionAt == null && ui.seatStages[Seat.REAR_RIGHT]?.let { it >= SickStage.CAUTION } == true) cautionAt = r.tSec
            if (restAskedAt == null && ui.question == Question.REST) { restAskedAt = r.tSec; run.say(IntentParser.parse("응, 쉬어가자")!!) }
            if (waypointAt == null && r.out.care.waypoint != null) waypointAt = r.tSec
            if (r.tSec in 901..1000 && car.signals.actuators[Actuator.Recirculation] == 0 && car.signals.actuators[Actuator.FanSpeed] == 8) arrivalVent = true
            assertTrue(r.applied.none { it.first.actuator is Actuator.Window }, "no window command at ${r.tSec}")
            if (r.tSec == 1150) run.say(VoiceIntent.RESTORE)
            if (r.tSec == 1152) restoredAt = r.tSec.takeIf { car.signals.actuators[Actuator.Recirculation] == 1 && car.signals.actuators[Actuator.FanSpeed] == 3 }
            if (r.finished) break
        }
        assertNotNull(curveSpokenAt); assertTrue(curveSpokenAt!! < 420, "curve announced before the winding road: $curveSpokenAt")
        assertNotNull(cautionAt); assertTrue(cautionAt!! in 420..700, "caution at $cautionAt")
        assertNotNull(restAskedAt); assertNotNull(waypointAt)
        assertTrue(arrivalVent, "arrival ventilation at the rest stop")
        assertNotNull(restoredAt, "restore by voice")
        assertEquals(230, car.signals.actuators[Actuator.ZoneTemp(Seat.REAR_RIGHT)], "trip end leaves user settings")
        assertEquals(220, car.signals.actuators[Actuator.ZoneTemp(Seat.DRIVER)])
        val rep = run.session.report()
        assertEquals(4, rep.occupantCount)
        assertTrue(rep.restCount == 1 && rep.restoreCount == 1)
    }

    @Test fun endingEarlyRestoresEverythingTheTripChanged() {
        val car = SimVehicle()
        val before = car.signals.actuators
        val run = ScenarioRun(Scenarios.mountain, SimBridge(car))
        repeat(560) { run.step() }
        assertTrue(car.signals.actuators != before, "care changed something mid-trip")
        val restored = run.endTrip()
        assertTrue(restored.isNotEmpty() && restored.all { it.second })
        for (a in listOf(Actuator.Recirculation, Actuator.FanSpeed, Actuator.ZoneTemp(Seat.REAR_RIGHT), Actuator.SeatVent(Seat.REAR_RIGHT), Actuator.Window(Seat.REAR_RIGHT))) {
            assertEquals(before[a], car.signals.actuators[a], "$a restored")
        }
    }

    @Test fun keywordParser() {
        assertEquals(VoiceIntent.SICK, IntentParser.parse("멀미 나"))
        assertEquals(VoiceIntent.SICK, IntentParser.parse("어지러워"))
        assertEquals(VoiceIntent.STUFFY, IntentParser.parse("답답해"))
        assertEquals(VoiceIntent.RESTORE, IntentParser.parse("원래대로 해 줘"))
        assertEquals(VoiceIntent.YES, IntentParser.parse("응"))
        assertEquals(VoiceIntent.NO, IntentParser.parse("아니 괜찮아"))
        assertEquals(null, IntentParser.parse("오늘 날씨"))
    }
}
