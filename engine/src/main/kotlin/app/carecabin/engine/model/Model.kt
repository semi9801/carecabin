package app.carecabin.engine.model

/** Four-seat cabin. Position factors are provisional (development plan 4-1). */
enum class Seat(val label: String, val positionFactor: Double, val isDriver: Boolean) {
    DRIVER("운전석", 0.6, true),
    PASSENGER("동승석", 1.0, false),
    REAR_LEFT("뒷좌석 왼쪽", 1.1, false),
    REAR_RIGHT("뒷좌석 오른쪽", 1.1, false),
}

/** No age input: sensitivity is a personal choice per seat. */
enum class Sensitivity(val label: String, val factor: Double?) {
    HIGH("멀미 민감", 1.5),
    NORMAL("보통", 1.0),
    OFF("케어 끔", null),
}

data class Occupant(val seat: Seat, val alias: String = "", val sensitivity: Sensitivity = Sensitivity.NORMAL)

enum class CarClass(val label: String, val volumeM3: Double) {
    SEDAN("세단", 3.0),
    SUV("SUV", 3.8),
    LARGE("대형 SUV·MPV", 4.5),
}

/** Preset toggles: "care allowed automatically while driving". */
data class CarePermissions(
    val climate: Boolean = true,
    val seat: Boolean = true,
    val ambient: Boolean = true,
    val brakeTip: Boolean = true,
)

data class Preset(
    val occupants: Map<Seat, Occupant> = mapOf(Seat.DRIVER to Occupant(Seat.DRIVER)),
    val carClass: CarClass = CarClass.SEDAN,
    val permissions: CarePermissions = CarePermissions(),
)

enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

/**
 * Everything the app can change. Values are plain Ints so snapshots and comparisons stay simple:
 * Recirculation 1 = 내기 / 0 = 외기, FanSpeed level, ZoneTemp tenths of °C, FanFace 1 = face vent / 0 = other,
 * SeatVent level, Window open percent, Ambient 1 = calm colour.
 * Window is read only: the user's own opening feeds the air model, care never commands it.
 */
sealed interface Actuator {
    val label: String

    data object Recirculation : Actuator { override val label = "내외기" }
    data object FanSpeed : Actuator { override val label = "풍량" }
    data object Ambient : Actuator { override val label = "조명" }
    data class ZoneTemp(val seat: Seat) : Actuator { override val label get() = "${seat.label} 온도" }
    data class FanFace(val seat: Seat) : Actuator { override val label get() = "${seat.label} 풍향" }
    data class SeatVent(val seat: Seat) : Actuator { override val label get() = "${seat.label} 통풍" }
    data class Window(val seat: Seat) : Actuator { override val label get() = "${seat.label} 창문" }
}

/** Latest vehicle state. Missing values (null / absent keys) mean "not reported". */
data class VehicleSignals(
    val occupied: Set<Seat> = setOf(Seat.DRIVER),
    val gear: Gear = Gear.PARK,
    val wiperOn: Boolean = false,
    val autoRecirculation: Boolean = false,
    val steeringDeg: Float? = null,
    val brakePressed: Boolean? = null,
    val actuators: Map<Actuator, Int> = emptyMap(),
)

/** Fallback defaults used only when the vehicle did not report the current value. */
object ActuatorDefaults {
    fun of(a: Actuator): Int = when (a) {
        Actuator.Recirculation -> 1
        Actuator.FanSpeed -> 3
        Actuator.Ambient -> 0
        is Actuator.ZoneTemp -> 220
        is Actuator.FanFace -> 0
        is Actuator.SeatVent -> 0
        is Actuator.Window -> 0
    }
}

data class LocationSample(
    val tMillis: Long,
    val lat: Double,
    val lon: Double,
    val headingDeg: Float?,
    val confidence: Float = 1f,
)

/** Turn weights are provisional (development plan 4-1 ③). */
enum class TurnKind(val weight: Double, val label: String) {
    UTURN(1.0, "유턴"),
    SHARP(0.8, "급커브"),
    TURN(0.6, "회전"),
    KEEP(0.3, "완만한 커브"),
    RAMP(0.3, "분기"),
    OTHER(0.0, "기타"),
}

data class Tbt(val kind: TurnKind, val distanceM: Double)

data class RestArea(val name: String, val lat: Double, val lon: Double)

/** User intents from STT keywords or the on-screen keyword buttons. */
enum class VoiceIntent { SICK, STUFFY, RESTORE, YES, NO }

object IntentParser {
    private val rules = listOf(
        VoiceIntent.RESTORE to listOf("원래대로", "그만", "꺼 줘", "꺼줘", "하지 마", "하지마"),
        VoiceIntent.SICK to listOf("멀미", "속 안 좋", "속안좋", "토할 것 같", "어지러"),
        VoiceIntent.STUFFY to listOf("답답", "공기 바꿔", "환기", "덥다", "더워"),
        VoiceIntent.NO to listOf("아니", "괜찮아", "됐어", "싫어"),
        VoiceIntent.YES to listOf("응", "좋아", "그래", "네", "가자", "어"),
    )

    fun parse(text: String): VoiceIntent? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return rules.firstOrNull { (_, words) -> words.any { t.contains(it) } }?.first
    }
}
