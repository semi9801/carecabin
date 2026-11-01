package app.carecabin

import app.carecabin.engine.care.AirStage
import app.carecabin.engine.care.Question
import app.carecabin.engine.care.SickStage
import app.carecabin.engine.model.Actuator
import app.carecabin.engine.model.CarClass
import app.carecabin.engine.model.CarePermissions
import app.carecabin.engine.model.Gear
import app.carecabin.engine.model.IntentParser
import app.carecabin.engine.model.Occupant
import app.carecabin.engine.model.Preset
import app.carecabin.engine.model.RestArea
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.Sensitivity
import app.carecabin.engine.model.VoiceIntent
import app.carecabin.engine.session.AirGrade
import app.carecabin.engine.session.TripReport
import app.carecabin.engine.sim.Capabilities
import app.carecabin.engine.sim.Cue
import app.carecabin.engine.sim.ScenarioRun
import app.carecabin.engine.sim.Scenarios
import app.carecabin.engine.sim.SimBridge
import app.carecabin.engine.sim.SimVehicle
import app.carecabin.engine.sim.VehicleBridge
import app.carecabin.platform.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Screen { PRESET, DRIVING, REPORT, DIAGNOSTICS }

enum class VehicleSource(val label: String) { SIM("시뮬레이터"), PLEOS("Pleos 차량(에뮬레이터)") }

enum class CapabilityProfile(val label: String, val set: Set<Actuator>) {
    FULL("전 좌석 공조 장착 차량", Capabilities.full),
    ROW1("1열만 지원(Virtual Console 수준)", Capabilities.row1Only),
}

data class LogLine(val tSec: Int, val text: String)

data class UiState(
    val screen: Screen = Screen.PRESET,
    val preset: Preset = Scenarios.mountain.preset,
    val scenarioId: String = Scenarios.mountain.id,
    val speed: Int = 10,
    val vehicleSource: VehicleSource = VehicleSource.SIM,
    val profile: CapabilityProfile = CapabilityProfile.FULL,
    val running: Boolean = false,
    val tSec: Int = 0,
    val durationSec: Int = 0,
    val gear: Gear = Gear.PARK,
    val wiperOn: Boolean = false,
    val occupied: Set<Seat> = emptySet(),
    val co2: Double = 600.0,
    val airGrade: AirGrade = AirGrade.GOOD,
    val airStage: AirStage = AirStage.OBSERVE,
    val seatMsi: Map<Seat, Double> = emptyMap(),
    val seatStages: Map<Seat, SickStage> = emptyMap(),
    val stage: SickStage = SickStage.OBSERVE,
    val targetSeat: Seat? = null,
    val activeCare: List<String> = emptyList(),
    val curveAlert: Boolean = false,
    val nearestTurnM: Double? = null,
    val speedKmh: Double = 0.0,
    val question: Question? = null,
    val paused: Boolean = false,
    val restAdded: RestArea? = null,
    val lastSpeech: String? = null,
    val cueNote: String? = null,
    val logs: List<LogLine> = emptyList(),
    val report: TripReport? = null,
    val platformStatus: List<String> = emptyList(),
    val listening: Boolean = false,
    val lastSpeechAt: Int = -1000,
    val secondsTo1000: Double = Double.POSITIVE_INFINITY,
    val freshRemainingSec: Int = 0,
    /** Last 3 minutes (1 sample / 5 s) for the driving-screen trend lines. */
    val co2History: List<Float> = emptyList(),
    val msiHistory: List<Float> = emptyList(),
    /** CarUxRestrictions says distraction optimisation is required (automotive only). */
    val uxRestricted: Boolean = false,
) {
    /** Subtitles fade after 20 simulated seconds so stale prompts do not linger. */
    val recentSpeech: String? get() = lastSpeech?.takeIf { tSec - lastSpeechAt <= 20 }
}

/** Remembers the preset between launches (device-local; no age, alias stays on the device). */
interface PresetStore {
    fun load(): Triple<Preset, String, Int>?
    fun save(preset: Preset, scenarioId: String, speed: Int)
}

/** Simulator whose seat occupancy follows the preset instead of the scenario's default cue. */
private class PresetSimBridge(private val v: SimVehicle, private val seats: Set<Seat>) : VehicleBridge by SimBridge(v) {
    override fun onCue(cue: Cue) = if (cue is Cue.SetOccupancy) v.setOccupancy(seats) else v.apply(cue)
}

/** Runs a demo trip (scenario location/TBT + simulated or Pleos vehicle) and exposes UI state. */
class CareController(
    private val platform: Platform,
    private val scope: CoroutineScope,
    private val store: PresetStore? = null,
) {
    private val _state = MutableStateFlow(
        UiState(platformStatus = platform.status()).let { base ->
            store?.load()?.let { (p, id, speed) -> base.copy(preset = p, scenarioId = id, speed = speed) } ?: base
        },
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var run: ScenarioRun? = null

    init {
        if (platform.vehicle != null) _state.update { it.copy(vehicleSource = VehicleSource.PLEOS, profile = CapabilityProfile.ROW1) }
        platform.navi?.onWaypointArrived = { log("내비: 경유지 도착") }
        platform.navi?.onDestinationArrived = { log("내비: 목적지 도착") }
    }

    // ---- preset editing (parked only) ----
    fun selectScenario(id: String) {
        val sc = Scenarios.all.first { it.id == id }
        _state.update { it.copy(scenarioId = id, preset = sc.preset) }
        persist()
    }

    /** 보통 → 멀미 민감 → 케어 끔 → (빈 좌석) → 보통. The driver seat is always occupied. */
    fun cycleSensitivity(seat: Seat) = updatePreset { p ->
        val next = when (p.occupants[seat]?.sensitivity) {
            null -> Sensitivity.NORMAL
            Sensitivity.NORMAL -> Sensitivity.HIGH
            Sensitivity.HIGH -> Sensitivity.OFF
            Sensitivity.OFF -> if (seat.isDriver) Sensitivity.NORMAL else null
        }
        p.copy(occupants = if (next == null) p.occupants - seat else p.occupants + (seat to (p.occupants[seat] ?: Occupant(seat)).copy(sensitivity = next)))
    }

    fun setCarClass(c: CarClass) = updatePreset { it.copy(carClass = c) }
    fun setPermissions(f: (CarePermissions) -> CarePermissions) = updatePreset { it.copy(permissions = f(it.permissions)) }
    fun setSpeed(x: Int) { _state.update { it.copy(speed = x) }; persist() }
    fun setVehicleSource(v: VehicleSource) = _state.update { it.copy(vehicleSource = if (v == VehicleSource.PLEOS && platform.vehicle == null) VehicleSource.SIM else v) }
    fun setProfile(p: CapabilityProfile) = _state.update { it.copy(profile = p) }
    fun open(screen: Screen) = _state.update { it.copy(screen = screen) }

    private fun updatePreset(f: (Preset) -> Preset) {
        if (_state.value.uxRestricted) return
        _state.update { if (it.running) it else it.copy(preset = f(it.preset)) }
        persist()
    }

    private fun persist() = _state.value.let { store?.save(it.preset, it.scenarioId, it.speed) }

    /** From UxGate. While restricted, settings screens are locked and an active trip shows the driving screen. */
    fun setUxRestricted(restricted: Boolean) = _state.update {
        it.copy(uxRestricted = restricted, screen = if (restricted && it.running) Screen.DRIVING else it.screen)
    }

    // ---- trip ----
    fun start() {
        if (_state.value.running) return
        val s = _state.value
        val scenario = Scenarios.all.first { it.id == s.scenarioId }
        val bridge: VehicleBridge = if (s.vehicleSource == VehicleSource.PLEOS && platform.vehicle != null) {
            platform.vehicle!!
        } else {
            PresetSimBridge(SimVehicle(s.profile.set), s.preset.occupants.keys + Seat.DRIVER)
        }
        val r = ScenarioRun(scenario, bridge, s.preset)
        run = r
        _state.update { it.copy(running = true, tSec = 0, durationSec = scenario.durationSec, logs = emptyList(), report = null, restAdded = null, lastSpeech = null, cueNote = null, co2History = emptyList(), msiHistory = emptyList(), screen = Screen.DRIVING) }
        log("출발: ${scenario.title} · 차량 신호 ${if (bridge is PresetSimBridge) "시뮬레이터" else "Pleos"} · 위치·내비 시나리오 재생")
        job = scope.launch {
            while (isActive) {
                val res = r.step()
                val out = res.out
                res.cues.forEach { cue ->
                    when (cue) {
                        is Cue.Note -> _state.update { st -> st.copy(cueNote = cue.text) }
                        else -> Unit
                    }
                }
                res.applied.forEach { (cmd, ok) ->
                    log("${if (cmd.restore) "↺" else "▶"} ${cmd.actuator.label} = ${fmtValue(cmd.actuator, cmd.value)} · ${cmd.reason}${if (ok) "" else " (미지원·미반영)"}", res.tSec)
                }
                out.care.log.forEach { log(it, res.tSec) }
                out.care.speech.forEach { sp -> platform.speaker.speak(sp.text); log("🔊 ${sp.text}", res.tSec); _state.update { st -> st.copy(lastSpeech = sp.text, lastSpeechAt = res.tSec) } }
                out.care.waypoint?.let { area -> platform.navi?.addWaypoint(area); log("내비: 경유지 추가 요청 ${area.name}${if (platform.navi == null) " (시나리오)" else ""}", res.tSec) }
                val sig = bridge.signals()
                val ui = out.care.ui
                _state.update { st ->
                    st.copy(
                        tSec = res.tSec, gear = sig.gear, wiperOn = sig.wiperOn, occupied = sig.occupied,
                        co2 = out.co2Ppm, airGrade = out.airGrade, airStage = ui.airStage,
                        seatMsi = out.seatMsi, seatStages = ui.seatStages, stage = ui.stage, targetSeat = ui.targetSeat,
                        activeCare = ui.activeCare, curveAlert = out.motion.curveAlert, nearestTurnM = out.motion.nearestTurnM,
                        speedKmh = out.motion.speedKmh, question = ui.question, paused = ui.paused, restAdded = ui.restAdded,
                        secondsTo1000 = out.secondsTo1000, freshRemainingSec = ui.freshRemainingSec,
                        co2History = if (res.tSec % 5 == 0) (st.co2History + out.co2Ppm.toFloat()).takeLast(36) else st.co2History,
                        msiHistory = if (res.tSec % 5 == 0) (st.msiHistory + (out.seatMsi.filterKeys { !it.isDriver }.values.maxOrNull() ?: 0.0).toFloat()).takeLast(36) else st.msiHistory,
                    )
                }
                if (res.finished) { finish(); break }
                delay((1000L / _state.value.speed).coerceAtLeast(20))
            }
        }
    }

    /** Ends the demo early (shown only while stopped). Restores every value the trip changed. */
    fun stop() {
        if (!_state.value.running) return
        job?.cancel()
        run?.let { r -> r.endTrip().forEach { (cmd, ok) -> log("↺ ${cmd.actuator.label} = ${fmtValue(cmd.actuator, cmd.value)} · 시연 종료${if (ok) "" else " (미반영)"}") } }
        finish()
    }

    private fun finish() {
        val rep = run?.session?.report()
        _state.update { it.copy(running = false, report = rep, screen = if (rep != null) Screen.REPORT else Screen.PRESET, cueNote = null) }
        log("트립 종료")
    }

    fun voice(intent: VoiceIntent) {
        run?.say(intent)
        log("🎤 ${when (intent) { VoiceIntent.SICK -> "멀미 나"; VoiceIntent.STUFFY -> "답답해"; VoiceIntent.RESTORE -> "원래대로"; VoiceIntent.YES -> "응"; VoiceIntent.NO -> "아니" }}")
    }

    /** Pleos STT when available; the screen shows keyword buttons otherwise. */
    fun listen(): Boolean {
        val l = platform.listener ?: return false
        _state.update { it.copy(listening = true) }
        l.listenOnce { text ->
            _state.update { it.copy(listening = false) }
            val intent = text?.let { IntentParser.parse(it) }
            log("🎤 인식: ${text ?: "(없음)"} → ${intent ?: "의도 없음"}")
            if (intent != null) run?.say(intent)
        }
        return true
    }

    fun backToPreset() = _state.update { it.copy(screen = Screen.PRESET) }

    private fun log(text: String, t: Int = _state.value.tSec) = _state.update { it.copy(logs = (it.logs + LogLine(t, text)).takeLast(80)) }

    companion object {
        fun fmtValue(a: Actuator, v: Int): String = when (a) {
            Actuator.Recirculation -> if (v == 1) "내기" else "외기"
            Actuator.FanSpeed -> "${v}단"
            Actuator.Ambient -> if (v == 1) "차분한 단색" else "원래"
            is Actuator.ZoneTemp -> "%.1f℃".format(v / 10.0)
            is Actuator.FanFace -> if (v == 1) "얼굴 쪽" else "원래 방향"
            is Actuator.SeatVent -> "${v}단"
            is Actuator.Window -> "$v%"
        }
    }
}
