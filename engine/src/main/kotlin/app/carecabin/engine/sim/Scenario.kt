package app.carecabin.engine.sim

import app.carecabin.engine.model.CarClass
import app.carecabin.engine.model.Gear
import app.carecabin.engine.model.LocationSample
import app.carecabin.engine.model.Occupant
import app.carecabin.engine.model.Preset
import app.carecabin.engine.model.RestArea
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.Sensitivity
import app.carecabin.engine.model.Tbt
import app.carecabin.engine.model.TurnKind
import app.carecabin.engine.motion.Geo
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A driving segment. [yawAmpDegPerSec] > 0 makes a winding road (sinusoidal yaw rate with [yawPeriodSec]);
 * [curvy] segments emit TBT "sharp curve" points every [tbtSpacingM] metres.
 */
data class Segment(
    val durationSec: Int,
    val speedKmh: Double,
    val yawAmpDegPerSec: Double = 0.0,
    val yawPeriodSec: Double = 20.0,
    val curvy: Boolean = false,
    val tbtSpacingM: Double = 400.0,
)

/** What the demo operator would do in Virtual Console, or a navigation event the scenario stands in for. */
sealed interface Cue {
    val tSec: Int
    data class SetGear(override val tSec: Int, val gear: Gear) : Cue
    data class SetWiper(override val tSec: Int, val on: Boolean) : Cue
    data class SetOccupancy(override val tSec: Int, val seats: Set<Seat>) : Cue
    data class WaypointArrived(override val tSec: Int) : Cue
    data class DestinationArrived(override val tSec: Int) : Cue
    data class Note(override val tSec: Int, val text: String) : Cue
}

data class Scenario(
    val id: String,
    val title: String,
    val summary: String,
    val preset: Preset,
    val startLat: Double,
    val startLon: Double,
    val startHeadingDeg: Double,
    val segments: List<Segment>,
    val cues: List<Cue>,
    val restArea: RestArea?,
) {
    val durationSec: Int get() = segments.sumOf { it.durationSec }
}

data class TrackPoint(val tSec: Int, val lat: Double, val lon: Double, val headingDeg: Double, val distanceM: Double)

/**
 * Pre-computes a 1 Hz synthetic track by integrating speed and yaw rate, then replays it as location
 * samples, upcoming TBT points (≤ 3 km) and cues. The track is synthetic, not map data.
 */
class ScenarioPlayer(val scenario: Scenario) {
    val track: List<TrackPoint>
    private val tbtPoints: List<Double>
    private val cuesByT: Map<Int, List<Cue>> = scenario.cues.groupBy { it.tSec }

    init {
        val pts = ArrayList<TrackPoint>()
        val tbt = ArrayList<Double>()
        var lat = scenario.startLat; var lon = scenario.startLon
        var heading = scenario.startHeadingDeg; var dist = 0.0; var t = 0
        pts += TrackPoint(0, lat, lon, heading, 0.0)
        for (seg in scenario.segments) {
            val v = seg.speedKmh / 3.6
            if (seg.curvy) {
                val segLen = v * seg.durationSec
                var d = seg.tbtSpacingM / 2
                while (d < segLen) { tbt += dist + d; d += seg.tbtSpacingM }
            }
            for (k in 0 until seg.durationSec) {
                val yaw = if (seg.yawAmpDegPerSec > 0) seg.yawAmpDegPerSec * sin(2 * PI * k / seg.yawPeriodSec) else 0.0
                heading = (heading + yaw + 360) % 360
                val h = Math.toRadians(heading)
                lat += Math.toDegrees(v * cos(h) / Geo.EARTH_RADIUS_M)
                lon += Math.toDegrees(v * sin(h) / (Geo.EARTH_RADIUS_M * cos(Math.toRadians(lat))))
                dist += v; t++
                pts += TrackPoint(t, lat, lon, heading, dist)
            }
        }
        track = pts
        tbtPoints = tbt
    }

    fun location(tSec: Int, baseMillis: Long = 0L): LocationSample? = track.getOrNull(tSec)?.let {
        LocationSample(baseMillis + it.tSec * 1000L, it.lat, it.lon, it.headingDeg.toFloat(), 1f)
    }

    fun tbt(tSec: Int): List<Tbt> {
        val here = track.getOrNull(tSec)?.distanceM ?: return emptyList()
        return tbtPoints.filter { it > here && it - here <= 3000 }.map { Tbt(TurnKind.SHARP, it - here) }
    }

    fun cues(tSec: Int): List<Cue> = cuesByT[tSec].orEmpty()

    fun isFinished(tSec: Int) = tSec >= scenario.durationSec
}

object Scenarios {
    /** A. Summer commute, driver alone: the air cycle carries the scene. */
    val solo = Scenario(
        id = "A",
        title = "A. 여름 출근 혼자",
        summary = "혼자 타도 내기순환이면 CO₂가 오릅니다. 나빠지기 전에 외기를 짧게 넣습니다.",
        preset = Preset(occupants = mapOf(Seat.DRIVER to Occupant(Seat.DRIVER)), carClass = CarClass.SEDAN),
        startLat = 37.4979, startLon = 127.0276, startHeadingDeg = 0.0,
        segments = listOf(
            Segment(60, 30.0),
            Segment(540, 50.0, yawAmpDegPerSec = 1.5, yawPeriodSec = 60.0),
            Segment(540, 55.0, yawAmpDegPerSec = 1.5, yawPeriodSec = 70.0),
            Segment(60, 20.0),
        ),
        cues = listOf(
            Cue.Note(0, "VC: 운전석만 탑승 · 내기순환 · 풍량 3 · Gear D"),
            Cue.SetOccupancy(0, setOf(Seat.DRIVER)),
            Cue.SetGear(0, Gear.DRIVE),
            Cue.Note(1150, "VC: Gear P (도착)"),
            Cue.SetGear(1200, Gear.PARK),
            Cue.DestinationArrived(1200),
        ),
        restArea = null,
    )

    private val rest = RestArea("시연용 휴게소(가상)", 38.1050, 128.2150)

    /** B. Passengers on a mountain road: prediction, seat care, rain rule, rest stop, restore. */
    val mountain = Scenario(
        id = "B",
        title = "B. 동승자 산길",
        summary = "4명 탑승 · 뒷좌석 오른쪽 '멀미 민감'. 커브 예고 → 좌석별 케어 → 비 오면 창문 대신 바람 → 쉬어가기.",
        preset = Preset(
            occupants = mapOf(
                Seat.DRIVER to Occupant(Seat.DRIVER),
                Seat.PASSENGER to Occupant(Seat.PASSENGER),
                Seat.REAR_LEFT to Occupant(Seat.REAR_LEFT, "동승자 B", Sensitivity.NORMAL),
                Seat.REAR_RIGHT to Occupant(Seat.REAR_RIGHT, "동승자 A", Sensitivity.HIGH),
            ),
            carClass = CarClass.SUV,
        ),
        startLat = 38.0697, startLon = 128.1707, startHeadingDeg = 60.0,
        segments = listOf(
            Segment(300, 95.0),                                   // 0–300 highway
            Segment(120, 70.0),                                   // 300–420 approach (curves ahead on TBT)
            Segment(420, 45.0, yawAmpDegPerSec = 13.0, yawPeriodSec = 20.0, curvy = true), // 420–840 mountain
            Segment(60, 25.0),                                    // 840–900 into the rest area
            Segment(180, 0.0),                                    // 900–1080 stopped
            Segment(300, 60.0, yawAmpDegPerSec = 2.0, yawPeriodSec = 60.0), // 1080–1380 onward
            Segment(60, 20.0),                                    // 1380–1440 arrive
        ),
        cues = listOf(
            Cue.Note(0, "VC: 4좌석 탑승 · 내기순환 · 풍량 3 · Gear D"),
            Cue.SetOccupancy(0, Seat.entries.toSet()),
            Cue.SetGear(0, Gear.DRIVE),
            Cue.Note(560, "VC: 와이퍼 ON (우천 규칙 시연)"),
            Cue.SetWiper(600, true),
            Cue.Note(890, "VC: Gear P (휴게소)"),
            Cue.SetGear(900, Gear.PARK),
            Cue.WaypointArrived(900),
            Cue.SetWiper(900, false),
            Cue.Note(1070, "VC: Gear D (재출발)"),
            Cue.SetGear(1080, Gear.DRIVE),
            Cue.Note(1150, "시연: '원래대로' 말하기"),
            Cue.Note(1430, "VC: Gear P (도착)"),
            Cue.SetGear(1440, Gear.PARK),
            Cue.DestinationArrived(1440),
        ),
        restArea = rest,
    )

    val all = listOf(solo, mountain)
}
