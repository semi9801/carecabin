package app.carecabin.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import app.carecabin.engine.model.LocationSample
import app.carecabin.engine.model.RestArea
import app.carecabin.engine.model.Tbt
import app.carecabin.engine.sim.VehicleBridge
import java.util.Locale

interface Speaker {
    val name: String
    fun speak(text: String)
    fun release() {}
}

interface Listener {
    val name: String
    /** One recognition request; [onText] gets the final text (or null on error/silence). */
    fun listenOnce(onText: (String?) -> Unit)
    fun release() {}
}

interface NaviPort {
    fun addWaypoint(area: RestArea)
    fun latestTbt(): List<Tbt>
    /** Set by the controller; called on waypoint / destination arrival. */
    var onWaypointArrived: () -> Unit
    var onDestinationArrived: () -> Unit
    fun release() {}
}

interface LocationPort {
    fun latest(): LocationSample?
    fun release() {}
}

/** What this build can reach. Every part is optional; the controller falls back to the simulator. */
interface Platform {
    val name: String
    val vehicle: VehicleBridge?
    val speaker: Speaker
    val listener: Listener?
    val navi: NaviPort?
    val location: LocationPort?
    fun status(): List<String>
    fun release()
}

/** Android platform TTS: fallback voice when Pleos TextToSpeech is not available (e.g. a phone). */
class AndroidTtsSpeaker(context: Context) : Speaker {
    override val name = "Android TTS(대체)"
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }.also { it.language = Locale.KOREAN }

    override fun speak(text: String) {
        if (ready) tts.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    override fun release() = tts.shutdown()
}
