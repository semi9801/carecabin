package app.carecabin.engine.sim

import app.carecabin.engine.care.CareCommand
import app.carecabin.engine.care.Question
import app.carecabin.engine.model.Actuator
import app.carecabin.engine.model.Preset
import app.carecabin.engine.model.VehicleSignals
import app.carecabin.engine.model.VoiceIntent
import app.carecabin.engine.session.CareSession
import app.carecabin.engine.session.TickInput
import app.carecabin.engine.session.TickOutput

/** Whatever carries out commands: the in-memory [SimVehicle] or a real Pleos adapter in the app. */
interface VehicleBridge {
    val supported: Set<Actuator>
    fun signals(): VehicleSignals
    fun apply(cmd: CareCommand): Boolean
    /** Called for Virtual Console cues. A real vehicle ignores them (the operator acts in Virtual Console). */
    fun onCue(cue: Cue)
}

class SimBridge(val vehicle: SimVehicle) : VehicleBridge {
    override val supported get() = vehicle.supported
    override fun signals() = vehicle.signals
    override fun apply(cmd: CareCommand) = vehicle.apply(cmd)
    override fun onCue(cue: Cue) = vehicle.apply(cue)
}

data class StepResult(
    val tSec: Int,
    val out: TickOutput,
    val cues: List<Cue>,
    val applied: List<Pair<CareCommand, Boolean>>,
    val finished: Boolean,
)

/** Plays a [Scenario] through a [CareSession] one simulated second per [step]. */
class ScenarioRun(
    val scenario: Scenario,
    private val bridge: VehicleBridge,
    preset: Preset = scenario.preset,
) {
    val player = ScenarioPlayer(scenario)
    val session = CareSession(preset, bridge.supported)
    var t = 0; private set
    private val pendingVoice = ArrayDeque<VoiceIntent>()
    private var waypointRequested = false
    var lastOut: TickOutput? = null; private set

    fun say(intent: VoiceIntent) { pendingVoice.addLast(intent) }

    /** Stop before the scenario ends; restores and applies the user's original values. */
    fun endTrip(): List<Pair<CareCommand, Boolean>> = session.endTrip().map { it to bridge.apply(it) }

    val pendingQuestion: Question? get() = lastOut?.care?.ui?.question

    fun step(): StepResult {
        val cues = player.cues(t)
        cues.forEach { bridge.onCue(it) }
        val voice = buildList { while (pendingVoice.isNotEmpty()) add(pendingVoice.removeFirst()) }
        val out = session.tick(
            TickInput(
                tSec = t.toDouble(),
                location = player.location(t),
                tbt = player.tbt(t),
                signals = bridge.signals(),
                voice = voice,
                waypointArrived = waypointRequested && cues.any { it is Cue.WaypointArrived },
                destinationArrived = cues.any { it is Cue.DestinationArrived },
                restArea = scenario.restArea,
            ),
        )
        if (out.care.waypoint != null) waypointRequested = true
        if (cues.any { it is Cue.WaypointArrived }) waypointRequested = false
        val applied = (out.care.commands + out.tripEndCommands).map { it to bridge.apply(it) }
        lastOut = out
        val result = StepResult(t, out, cues, applied, player.isFinished(t))
        t++
        return result
    }
}
