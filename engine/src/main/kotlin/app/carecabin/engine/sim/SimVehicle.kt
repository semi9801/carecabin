package app.carecabin.engine.sim

import app.carecabin.engine.care.CareCommand
import app.carecabin.engine.model.Actuator
import app.carecabin.engine.model.ActuatorDefaults
import app.carecabin.engine.model.Gear
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.VehicleSignals

object Capabilities {
    /** Every actuator, including rear climate zones and ambient light (a fully equipped car). */
    val full: Set<Actuator> = buildSet {
        add(Actuator.Recirculation); add(Actuator.FanSpeed); add(Actuator.Ambient)
        Seat.entries.forEach { add(Actuator.ZoneTemp(it)); add(Actuator.FanFace(it)); add(Actuator.SeatVent(it)); add(Actuator.Window(it)) }
    }

    /** Like the Pleos Virtual Console: climate and seat comfort on row 1 only, no ambient light. */
    val row1Only: Set<Actuator> = buildSet {
        add(Actuator.Recirculation); add(Actuator.FanSpeed)
        listOf(Seat.DRIVER, Seat.PASSENGER).forEach { add(Actuator.ZoneTemp(it)); add(Actuator.FanFace(it)); add(Actuator.SeatVent(it)) }
        Seat.entries.forEach { add(Actuator.Window(it)) }
    }
}

/** In-memory vehicle: commands change its state immediately; cues play the Virtual Console operator. */
class SimVehicle(var supported: Set<Actuator> = Capabilities.full) {
    var signals: VehicleSignals = VehicleSignals(
        actuators = buildMap {
            for (a in Capabilities.full) put(a, ActuatorDefaults.of(a))
            put(Actuator.ZoneTemp(Seat.DRIVER), 220); put(Actuator.ZoneTemp(Seat.PASSENGER), 230)
            put(Actuator.ZoneTemp(Seat.REAR_LEFT), 230); put(Actuator.ZoneTemp(Seat.REAR_RIGHT), 230)
        },
    )
        private set

    fun apply(cmd: CareCommand): Boolean {
        if (cmd.actuator !in supported) return false
        signals = signals.copy(actuators = signals.actuators + (cmd.actuator to cmd.value))
        return true
    }

    fun apply(cue: Cue) {
        signals = when (cue) {
            is Cue.SetGear -> signals.copy(gear = cue.gear)
            is Cue.SetWiper -> signals.copy(wiperOn = cue.on)
            is Cue.SetOccupancy -> signals.copy(occupied = cue.seats)
            else -> signals
        }
    }

    fun setOccupancy(seats: Set<Seat>) { signals = signals.copy(occupied = seats) }
    fun setGear(gear: Gear) { signals = signals.copy(gear = gear) }
}
