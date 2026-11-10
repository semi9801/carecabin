package app.carecabin.platform

import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.FanDirectionType
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacRecirculationStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatVentilationListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import app.carecabin.engine.care.CareCommand
import app.carecabin.engine.model.Actuator
import app.carecabin.engine.model.Gear
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.VehicleSignals
import app.carecabin.engine.sim.Capabilities
import app.carecabin.engine.sim.Cue
import app.carecabin.engine.sim.VehicleBridge

private const val TAG = "CareCabin.Vehicle"

/**
 * Vehicle SDK v2.3.2 adapter. Listeners keep [signals] current; commands call the matching set* API.
 * Failures never crash: a failed callback leaves the value "not reported".
 * Window position is only read (air model input); care never moves a window.
 */
class PleosVehicleBridge(private val v: Vehicle, private val note: (String) -> Unit) : VehicleBridge {
    @Volatile private var state = VehicleSignals(occupied = setOf(Seat.DRIVER), gear = Gear.UNKNOWN)
    @Volatile private var supportedNow: Set<Actuator> = Capabilities.row1Only
    private var globalArea = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_CENTER, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    private val prevDirection = mutableMapOf<Seat, FanDirectionType>()
    private val unregister = mutableListOf<() -> Unit>()

    override val supported: Set<Actuator> get() = supportedNow

    init {
        subscribe()
        probe()
    }

    private fun put(a: Actuator, value: Int?) {
        state = state.copy(actuators = if (value == null) state.actuators - a else state.actuators + (a to value))
    }

    private fun failed(what: String) = { e: Exception -> Log.w(TAG, "$what failed", e); Unit }

    private fun subscribe() {
        val hvac = v.getHVAC(); val seat = v.getSeat(); val window = v.getWindow()
        val recirc = object : HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(p0: Boolean?) = put(Actuator.Recirculation, p0?.let { if (it) 1 else 0 })
            override fun onFailed(p0: Exception) { failed("recirculation")(p0) }
        }
        hvac.registerHvacRecirculationState(globalArea, recirc)
        unregister += { hvac.unregisterHvacRecirculationState(globalArea, recirc) }
        val auto = object : HvacRecirculationStateListener {
            override fun onHvacAutoRecirculationStateUpdated(p0: Boolean?) { state = state.copy(autoRecirculation = p0 == true) }
            override fun onFailed(p0: Exception) { failed("auto recirculation")(p0) }
        }
        hvac.registerHvacAutoRecirculationState(globalArea, auto)
        unregister += { hvac.unregisterHvacAutoRecirculationState(globalArea, auto) }
        val fan = object : HvacFanSpeedListener {
            override fun onHvacFanSpeedUpdated(p0: Int?) = put(Actuator.FanSpeed, p0)
            override fun onFailed(p0: Exception) { failed("fan speed")(p0) }
        }
        hvac.registerHvacFanSpeed(globalArea, fan)
        unregister += { hvac.unregisterHvacFanSpeed(globalArea, fan) }

        for (s in Seat.entries) {
            val area = seatArea(s)
            val occ = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(p0: Boolean?) {
                    val now = state.occupied
                    state = state.copy(occupied = if (p0 == true || s == Seat.DRIVER) now + s else now - s)
                }
                override fun onFailed(p0: Exception) { failed("occupancy ${s.name}")(p0) }
            }
            seat.registerSeatOccupancy(area, occ); unregister += { seat.unregisterSeatOccupancy(area, occ) }
            val temp = object : HvacTemperatureListener {
                override fun onHvacTemperatureUpdated(p0: Float?) = put(Actuator.ZoneTemp(s), p0?.let { (it * 10).toInt() })
                override fun onFailed(p0: Exception) { failed("temperature ${s.name}")(p0) }
            }
            hvac.registerHvacTemperature(area, temp); unregister += { hvac.unregisterHvacTemperature(area, temp) }
            val vent = object : HvacSeatVentilationListener {
                override fun onHvacSeatVentilationUpdated(p0: Int?) = put(Actuator.SeatVent(s), p0)
                override fun onFailed(p0: Exception) { failed("seat ventilation ${s.name}")(p0) }
            }
            seat.registerHvacSeatVentilation(area, vent); unregister += { seat.unregisterHvacSeatVentilation(area, vent) }
            val wArea = windowArea(s)
            val win = object : WindowPositionListener {
                override fun onWindowPositionUpdated(p0: Int?) = put(Actuator.Window(s), p0)
                override fun onFailed(p0: Exception) { failed("window ${s.name}")(p0) }
            }
            window.registerWindowPosition(wArea, win)
            hvac.getHvacFanDirection(area, { d -> if (d != null && d != FanDirectionType.FACE) prevDirection[s] = d; put(Actuator.FanFace(s), if (d == FanDirectionType.FACE) 1 else 0) }, failed("fan direction ${s.name}"))
        }
        v.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(p0: VehicleGear?) {
                state = state.copy(gear = when (p0) {
                    VehicleGear.GEAR_PARK -> Gear.PARK
                    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
                    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
                    VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
                    else -> Gear.DRIVE
                })
            }
            override fun onFailed(p0: Exception) { failed("gear")(p0) }
        })
        v.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), object : WiperStateListener {
            override fun onWiperStateUpdated(p0: WiperState?) { state = state.copy(wiperOn = p0 == WiperState.ON) }
            override fun onFailed(p0: Exception) { failed("wiper")(p0) }
        })
        v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(p0: Float?) { state = state.copy(steeringDeg = p0) }
            override fun onFailed(p0: Exception) { failed("steering")(p0) }
        })
        v.getBrake().registerBrakePedalStatus(object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(p0: Boolean?) { state = state.copy(brakePressed = p0) }
            override fun onFailed(p0: Exception) { failed("brake")(p0) }
        })
    }

    /** Capability probe: which climate zones exist, and the global HVAC area to use. */
    private fun probe() {
        val hvac = v.getHVAC()
        hvac.checkHvacRecirculationCapabilities({ areas: List<SeatArea> ->
            areas.firstOrNull()?.let { globalArea = it }
            note("capability 내외기 영역 ${areas.size}개")
        }, failed("recirculation capability"))
        hvac.checkHvacTemperatureCapabilities({ zones: Map<SeatArea, AreaBounds<Float>> ->
            val seats = zones.keys.flatMap { it.values.toList() }.mapNotNull { seatOf(it) }.toSet()
            val set = buildSet {
                add(Actuator.Recirculation); add(Actuator.FanSpeed)
                for (s in seats) { add(Actuator.ZoneTemp(s)); add(Actuator.FanFace(s)); add(Actuator.SeatVent(s)) }
            }
            if (seats.isNotEmpty()) supportedNow = set
            note("capability 온도 존: ${seats.joinToString { it.label }.ifEmpty { "없음(기본 1열 가정)" }}")
        }, failed("temperature capability"))
    }

    override fun signals(): VehicleSignals = state

    override fun apply(cmd: CareCommand): Boolean {
        val hvac = v.getHVAC()
        val ok = { _: Any?, value: Any? -> Log.i(TAG, "set ${cmd.actuator.label} → $value") }
        try {
            when (val a = cmd.actuator) {
                Actuator.Recirculation -> hvac.setHvacRecirculation(globalArea, cmd.value == 1, { ar, b -> ok(ar, b); put(a, b?.let { if (it) 1 else 0 }) }, failed("setRecirculation"))
                Actuator.FanSpeed -> hvac.setHvacFanSpeed(globalArea, cmd.value, { ar, i -> ok(ar, i); put(a, i) }, failed("setFanSpeed"))
                is Actuator.ZoneTemp -> hvac.setHvacTemperature(seatArea(a.seat), cmd.value / 10f, TemperatureUnit.CELSIUS, { ar, f -> ok(ar, f); put(a, f?.let { (it * 10).toInt() }) }, failed("setTemperature"))
                is Actuator.FanFace -> {
                    val dir = if (cmd.value == 1) FanDirectionType.FACE else prevDirection[a.seat] ?: FanDirectionType.FACE_AND_FLOOR
                    hvac.setHvacFanDirection(seatArea(a.seat), dir, { ar, d -> ok(ar, d); put(a, if (d == FanDirectionType.FACE) 1 else 0) }, failed("setFanDirection"))
                }
                is Actuator.SeatVent -> v.getSeat().setHvacSeatVentilation(seatArea(a.seat), cmd.value, { ar, i -> ok(ar, i); put(a, i) }, failed("setSeatVentilation"))
                is Actuator.Window -> return false // read only since 2026-11-10
                Actuator.Ambient -> return false // not wired in the MVP (first item in the cut list)
            }
        } catch (e: Exception) {
            Log.w(TAG, "apply ${cmd.actuator.label} threw", e); return false
        }
        return true
    }

    /** Virtual Console cues are for the human operator; the real vehicle state comes from listeners. */
    override fun onCue(cue: Cue) = Unit

    fun release() { unregister.forEach { runCatching { it() } }; unregister.clear() }

    private fun seatArea(s: Seat) = SeatArea(
        when (s) { Seat.DRIVER -> SeatValue.ROW_1_LEFT; Seat.PASSENGER -> SeatValue.ROW_1_RIGHT; Seat.REAR_LEFT -> SeatValue.ROW_2_LEFT; Seat.REAR_RIGHT -> SeatValue.ROW_2_RIGHT },
    )

    private fun windowArea(s: Seat) = WindowArea(
        when (s) { Seat.DRIVER -> WindowValue.ROW_1_LEFT; Seat.PASSENGER -> WindowValue.ROW_1_RIGHT; Seat.REAR_LEFT -> WindowValue.ROW_2_LEFT; Seat.REAR_RIGHT -> WindowValue.ROW_2_RIGHT },
    )

    private fun seatOf(v: SeatValue): Seat? = when (v) {
        SeatValue.ROW_1_LEFT -> Seat.DRIVER
        SeatValue.ROW_1_RIGHT -> Seat.PASSENGER
        SeatValue.ROW_2_LEFT -> Seat.REAR_LEFT
        SeatValue.ROW_2_RIGHT -> Seat.REAR_RIGHT
        else -> null
    }
}
