package app.carecabin.platform

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import app.carecabin.PresetStore
import app.carecabin.engine.model.CarClass
import app.carecabin.engine.model.CarePermissions
import app.carecabin.engine.model.Occupant
import app.carecabin.engine.model.Preset
import app.carecabin.engine.model.Seat
import app.carecabin.engine.model.Sensitivity

/**
 * CarUxRestrictions subscription (development plan 5-6). Only on automotive devices; elsewhere it stays
 * inactive. The platform supplies the region-specific restrictions, the app only follows them.
 */
class UxGate(context: Context, private val onChange: (restricted: Boolean) -> Unit) {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    val active: Boolean

    init {
        active = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) && runCatching {
            val c = Car.createCar(context)
            val m = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            m.registerListener { onChange(it.isRequiresDistractionOptimization) }
            onChange(m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true)
            car = c; manager = m
        }.onFailure { Log.w("CareCabin", "UxGate unavailable", it) }.isSuccess
    }

    fun release() {
        runCatching { manager?.unregisterListener() }
        runCatching { car?.disconnect() }
    }
}

/** SharedPreferences preset store. Stores seat, optional alias and sensitivity only — never age. */
class PrefsPresetStore(context: Context) : PresetStore {
    private val prefs = context.getSharedPreferences("carecabin_preset", Context.MODE_PRIVATE)

    override fun load(): Triple<Preset, String, Int>? {
        if (!prefs.contains("scenario")) return null
        return runCatching {
            val occupants = prefs.getString("occupants", "").orEmpty().split(';').filter { it.isNotBlank() }.associate { item ->
                val (seat, sens, alias) = (item.split('|') + listOf("", "", "")).take(3)
                Seat.valueOf(seat) to Occupant(Seat.valueOf(seat), alias, Sensitivity.valueOf(sens))
            }
            val perms = prefs.getString("permissions", "11111").orEmpty().padEnd(5, '1').map { it == '1' }
            val preset = Preset(
                occupants = occupants.ifEmpty { mapOf(Seat.DRIVER to Occupant(Seat.DRIVER)) },
                carClass = CarClass.valueOf(prefs.getString("carClass", CarClass.SEDAN.name)!!),
                permissions = CarePermissions(perms[0], perms[1], perms[2], perms[3], perms[4]),
            )
            Triple(preset, prefs.getString("scenario", "B")!!, prefs.getInt("speed", 10))
        }.getOrNull()
    }

    override fun save(preset: Preset, scenarioId: String, speed: Int) {
        val occ = preset.occupants.values.joinToString(";") { "${it.seat.name}|${it.sensitivity.name}|${it.alias.replace("|", " ").replace(";", " ")}" }
        val p = preset.permissions
        val perms = listOf(p.climate, p.seat, p.window, p.ambient, p.brakeTip).joinToString("") { if (it) "1" else "0" }
        prefs.edit()
            .putString("occupants", occ).putString("carClass", preset.carClass.name).putString("permissions", perms)
            .putString("scenario", scenarioId).putInt("speed", speed)
            .apply()
    }
}
