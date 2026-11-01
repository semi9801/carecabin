package app.carecabin.platform

import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.vehicle.Vehicle
import app.carecabin.engine.model.LocationSample
import app.carecabin.engine.model.RestArea
import app.carecabin.engine.model.Tbt
import app.carecabin.engine.model.TurnKind
import app.carecabin.engine.sim.VehicleBridge

private const val TAG = "CareCabin"

/**
 * pleos flavor: binds the five contest SDKs. Each part falls back on failure so the app still runs on a
 * device without Pleos services. Permissions are declared in AndroidManifest (development stage needs no
 * approval — 10/28 briefing); whether each call takes effect on the emulator is verified separately.
 */
object PlatformFactory {
    fun create(context: Context): Platform = PleosPlatform(context.applicationContext)
}

private class PleosPlatform(ctx: Context) : Platform {
    private val status = mutableListOf("빌드: pleos (Vehicle 2.3.2 · NaviHelper 3.0.5 · FusedLocation 1.1.3 · TTS/STT 3.3.1)")
    override val name = "pleos"

    private val vehicleSdk: Vehicle? = attempt("Vehicle") { Vehicle(ctx).also { it.initialize() } }
    override val vehicle: VehicleBridge? = vehicleSdk?.let { attempt("Vehicle 구독") { PleosVehicleBridge(it) { msg -> note(msg) } } }

    private val fallbackSpeaker by lazy { AndroidTtsSpeaker(ctx) }
    private val pleosTts: PleosSpeaker? = attempt("TextToSpeech") { PleosSpeaker(ctx) { msg -> note(msg) } }
    override val speaker: Speaker get() = pleosTts?.takeIf { it.ready } ?: fallbackSpeaker

    override val listener: Listener? = attempt("SpeechToText") { PleosListener(ctx) }
    override val navi: NaviPort? = attempt("NaviHelper") { PleosNavi(ctx) }
    override val location: LocationPort? = attempt("FusedLocation") { PleosLocation(ctx) }

    private fun note(msg: String) { synchronized(status) { status += msg }; Log.i(TAG, msg) }

    private fun <T> attempt(what: String, block: () -> T): T? = try {
        block().also { note("$what: 초기화 요청됨") }
    } catch (t: Throwable) {
        note("$what: 사용 불가 → 대체 (${t.javaClass.simpleName})"); null
    }

    override fun status(): List<String> = synchronized(status) { status.toList() } +
        listOf("음성 안내 현재: ${speaker.name}", "위치·내비 시연: 시나리오 재생(Virtual Console 미지원)")

    override fun release() {
        runCatching { (vehicle as? PleosVehicleBridge)?.release() }
        runCatching { vehicleSdk?.release() }
        runCatching { pleosTts?.release() }; runCatching { fallbackSpeaker.release() }
        runCatching { listener?.release() }; runCatching { navi?.release() }; runCatching { location?.release() }
    }
}

private class PleosSpeaker(ctx: Context, note: (String) -> Unit) : Speaker {
    override val name = "Pleos TextToSpeech (온디바이스)"
    @Volatile var ready = false; private set
    private val tts = TextToSpeech(ctx, ai.pleos.playground.tts.constant.Mode.ON_DEVICE).also {
        it.initialize(object : OnInitializeListener {
            override fun onSuccess() { ready = true; note("TextToSpeech: 준비됨") }
            override fun onFailure(p0: ai.pleos.playground.tts.constant.ErrorType) { note("TextToSpeech: 실패 $p0 → Android TTS") }
        })
    }

    override fun speak(text: String) = tts.speak(text)
    override fun release() = tts.release()
}

private class PleosListener(ctx: Context) : Listener {
    override val name = "Pleos SpeechToText (온디바이스)"
    private val stt = SpeechToText(ctx, ai.pleos.playground.stt.constant.Mode.ON_DEVICE).also { it.initialize() }
    private var pending: ((String?) -> Unit)? = null

    init {
        stt.addListener(object : ResultListener {
            override fun onUpdated(p0: String, p1: Boolean) { if (p1) deliver(p0) }
            override fun onUpdatedEpdData(p0: Long, p1: Long) = Unit
            override fun onStartedRecognition() = Unit
            override fun onEndedRecognition() = Unit
            override fun onError(p0: ai.pleos.playground.stt.constant.ErrorType) = deliver(null)
            override fun onReady() = Unit
            override fun onUpdatedRms(p0: Double) = Unit
        })
    }

    private fun deliver(text: String?) { val cb = pending; pending = null; cb?.invoke(text) }

    override fun listenOnce(onText: (String?) -> Unit) { pending = onText; stt.request() }
    override fun release() = stt.release()
}

private class PleosNavi(ctx: Context) : NaviPort {
    private val navi = NaviHelper(ctx)
    @Volatile private var tbt: List<Tbt> = emptyList()
    override var onWaypointArrived: () -> Unit = {}
    override var onDestinationArrived: () -> Unit = {}

    init {
        navi.initialize({ navi.getTBTInfo() }, { Log.w(TAG, "NaviHelper init failed", it) })
        navi.addListener(object : NaviHelperEventListener {
            override fun onTBTInfo(p0: List<TBTInfo>) { tbt = p0.map { Tbt(kindOf(it.type), it.distance) } }
            override fun onWayPointArrived(p0: WaypointArrivedInfo) = onWaypointArrived()
            override fun onDestinationArrived(p0: DestinationArrivedInfo) = onDestinationArrived()
        })
    }

    override fun addWaypoint(area: RestArea) =
        navi.addWaypoint(RequestWaypointInfo(area.lat, area.lon, area.name, WaypointIndex.FIRST, "", "", "", ""))

    override fun latestTbt(): List<Tbt> { runCatching { navi.getTBTInfo() }; return tbt }
    override fun release() = navi.release()

    private fun kindOf(t: TurnType): TurnKind = when (t) {
        TurnType.TURN_UTURN -> TurnKind.UTURN
        TurnType.TURN_SHARP_L, TurnType.TURN_SHARP_R -> TurnKind.SHARP
        TurnType.TURN_LEFT, TurnType.TURN_RIGHT -> TurnKind.TURN
        TurnType.TURN_KEEP_L, TurnType.TURN_KEEP_R -> TurnKind.KEEP
        TurnType.TURN_ENTER_IC_L, TurnType.TURN_ENTER_IC_R, TurnType.TURN_EXIT_IC_L, TurnType.TURN_EXIT_IC_R,
        TurnType.TURN_DEVIDE_MERGE -> TurnKind.RAMP
        else -> TurnKind.OTHER
    }
}

private class PleosLocation(ctx: Context) : LocationPort {
    private val fused = FusedLocation(ctx).also { it.initialize() }
    @Volatile private var last: LocationSample? = null
    private val listener = object : FusedLocationListener {
        override fun onChanged(p0: LocationData?) {
            p0 ?: return
            last = LocationSample(p0.timestampMillis, p0.latitude, p0.longitude, p0.heading, p0.confidence)
        }
        override fun onError(p0: Exception) { Log.w(TAG, "FusedLocation error", p0) }
    }

    init { fused.registerFusedLocationCallback(listener) }

    override fun latest() = last
    override fun release() { runCatching { fused.unregisterFusedLocationCallback(listener) }; fused.release() }
}
