package app.carecabin.engine.session

import app.carecabin.engine.air.AirInputs
import app.carecabin.engine.air.AirModel
import app.carecabin.engine.care.CareCommand
import app.carecabin.engine.care.CareInputs
import app.carecabin.engine.care.CareOrchestrator
import app.carecabin.engine.care.CareOutput
import app.carecabin.engine.model.Actuator
import app.carecabin.engine.model.ActuatorDefaults
import app.carecabin.engine.model.Gear
import app.carecabin.engine.model.LocationSample
import app.carecabin.engine.model.Occupant
import app.carecabin.engine.model.Preset
import app.carecabin.engine.model.RestArea
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.Tbt
import app.carecabin.engine.model.VehicleSignals
import app.carecabin.engine.model.VoiceIntent
import app.carecabin.engine.motion.MotionEngine
import app.carecabin.engine.motion.MotionState

data class TickInput(
    val tSec: Double,
    val dtSec: Double = 1.0,
    val location: LocationSample?,
    val tbt: List<Tbt>,
    val signals: VehicleSignals,
    val voice: List<VoiceIntent> = emptyList(),
    val waypointArrived: Boolean = false,
    val destinationArrived: Boolean = false,
    val restArea: RestArea? = null,
)

enum class AirGrade(val label: String) { GOOD("좋음"), FAIR("보통"), POOR("나쁨"), BAD("매우 나쁨") }

data class TickOutput(
    val motion: MotionState,
    val co2Ppm: Double,
    val airGrade: AirGrade,
    val seatMsi: Map<Seat, Double>,
    /** Predicted seconds to 1,000 ppm under the current settings (Infinity = not reached). */
    val secondsTo1000: Double,
    val care: CareOutput,
    /** Restore commands issued at trip end (destination reached). */
    val tripEndCommands: List<CareCommand>,
)

data class TripSample(val tSec: Double, val co2: Double, val co2RecircOnly: Double, val maxMsi: Double, val lat: Double?, val lon: Double?)

data class TripReport(
    val durationSec: Double,
    val occupantCount: Int,
    val maxMsiBySeat: Map<Seat, Double>,
    val sickZoneCount: Int,
    val goodAirRatio: Double,
    val autoCareCount: Int,
    val restoreCount: Int,
    val restCount: Int,
    val samples: List<TripSample>,
)

/** One trip: motion → air → care, ticked once per (simulated) second. Pure Kotlin, no Android. */
class CareSession(
    private val preset: Preset,
    private val supported: Set<Actuator>,
    private val orchestrator: CareOrchestrator = CareOrchestrator(),
) {
    private val motion = MotionEngine()
    private val air = AirModel()
    private val airRecircOnly = AirModel()
    private val speedWindow = ArrayDeque<Double>()
    private var startedAt: Double? = null
    private var lastT = 0.0
    private var drivingSec = 0.0
    private var goodSec = 0.0
    private var totalSec = 0.0
    private val maxMsi = mutableMapOf<Seat, Double>()
    private var inSickZone = false
    private var sickZoneCount = 0
    private val samples = mutableListOf<TripSample>()
    private var lastSampleT = Double.NEGATIVE_INFINITY
    private var maxOccupants = 0

    val co2Ppm: Double get() = air.ppm

    fun tick(input: TickInput): TickOutput {
        if (startedAt == null) startedAt = input.tSec
        lastT = input.tSec
        val s = input.signals
        val m = motion.update(input.dtSec, input.location, s.steeringDeg, input.tbt)
        speedWindow.addLast(m.speedKmh); if (speedWindow.size > 10) speedWindow.removeFirst()
        val speedAvg = speedWindow.average()
        val moving = m.speedMps > 1.0 && s.gear != Gear.PARK
        if (moving) drivingSec += input.dtSec

        val fan = s.actuators[Actuator.FanSpeed] ?: ActuatorDefaults.of(Actuator.FanSpeed)
        val recirc = (s.actuators[Actuator.Recirculation] ?: 1) == 1
        val windowCm = Seat.entries.sumOf { (s.actuators[Actuator.Window(it)] ?: 0) / 100.0 * 50.0 }
        val airIn = AirInputs(
            occupantCount = s.occupied.size,
            volumeM3 = preset.carClass.volumeM3,
            recirculating = recirc,
            fanLevel = fan.coerceIn(0, 8),
            windowOpenCm = windowCm,
            speedKmh = m.speedKmh,
        )
        val secondsTo1000 = air.secondsUntil(1000.0, airIn)
        val freshNeeded = air.freshAirSecondsTo(700.0, airIn.copy(recirculating = false, fanLevel = (fan + 1).coerceIn(1, 8), windowOpenCm = 0.0))
        val dt = input.dtSec
        var left = dt
        while (left > 1e-9) { val step = minOf(1.0, left); air.step(airIn, step); airRecircOnly.step(airIn.copy(recirculating = true, windowOpenCm = 0.0), step); left -= step }

        val seatMsi = buildMap {
            for (seat in s.occupied) {
                val occ = preset.occupants[seat] ?: Occupant(seat)
                m.msi(seat, occ.sensitivity.factor)?.let { put(seat, it) }
            }
        }
        maxOccupants = maxOf(maxOccupants, s.occupied.size)
        seatMsi.forEach { (k, v) -> maxMsi[k] = maxOf(maxMsi[k] ?: 0.0, v) }
        val anyHigh = seatMsi.values.any { it >= 50 }
        if (anyHigh && !inSickZone) sickZoneCount++
        inSickZone = anyHigh
        totalSec += dt
        if (air.ppm < 1000) goodSec += dt

        val care = orchestrator.evaluate(
            CareInputs(
                tSec = input.tSec,
                msi = seatMsi,
                curveAlert = m.curveAlert,
                nearestTurnM = m.nearestTurnM,
                co2Ppm = air.ppm,
                secondsTo1000 = secondsTo1000,
                freshNeededSec = freshNeeded,
                speedKmh = speedAvg,
                moving = moving,
                signals = s,
                preset = preset,
                supported = supported,
                tripDrivingSec = drivingSec,
                voice = input.voice,
                arrivedAtWaypoint = input.waypointArrived,
                restArea = input.restArea,
            ),
        )
        val endCommands = if (input.destinationArrived) orchestrator.restoreForTripEnd(input.tSec) else emptyList()
        if (input.tSec - lastSampleT >= 10) {
            lastSampleT = input.tSec
            samples += TripSample(input.tSec, air.ppm, airRecircOnly.ppm, seatMsi.values.maxOrNull() ?: 0.0, input.location?.lat, input.location?.lon)
        }
        return TickOutput(m, air.ppm, grade(air.ppm), seatMsi, secondsTo1000, care, endCommands)
    }

    /** Ends the trip early: every value the session changed goes back to the user's original. */
    fun endTrip(): List<CareCommand> = orchestrator.restoreForTripEnd(lastT)

    fun report(): TripReport = TripReport(
        durationSec = lastT - (startedAt ?: lastT),
        occupantCount = maxOccupants,
        maxMsiBySeat = maxMsi.toMap(),
        sickZoneCount = sickZoneCount,
        goodAirRatio = if (totalSec > 0) goodSec / totalSec else 1.0,
        autoCareCount = orchestrator.autoCareCount,
        restoreCount = orchestrator.restoreCount,
        restCount = orchestrator.restAcceptedCount,
        samples = samples.toList(),
    )

    companion object {
        fun grade(ppm: Double) = when {
            ppm < 1000 -> AirGrade.GOOD
            ppm < 1500 -> AirGrade.FAIR
            ppm < 2000 -> AirGrade.POOR
            else -> AirGrade.BAD
        }
    }
}
