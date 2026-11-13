package app.carecabin

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.carecabin.engine.sim.Scenarios
import app.carecabin.engine.sim.VehicleBridge
import app.carecabin.platform.Listener
import app.carecabin.platform.LocationPort
import app.carecabin.platform.NaviPort
import app.carecabin.platform.Platform
import app.carecabin.platform.Speaker
import app.carecabin.ui.CareCabinTheme
import app.carecabin.ui.DiagnosticsScreen
import app.carecabin.ui.DrivingScreen
import app.carecabin.ui.PresetScreen
import app.carecabin.ui.ReportScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the four screens at 1920×1080 while the real controller plays scenario B, and saves PNGs to
 * app/build/screens/. Checks that the trip reaches the states the demo relies on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1920dp-h1080dp-land-mdpi")
class ScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val spoken = mutableListOf<String>()
    private val platform = object : Platform {
        override val name = "test"
        override val vehicle: VehicleBridge? = null
        override val speaker = object : Speaker { override val name = "test"; override fun speak(text: String) { spoken += text } }
        override val listener: Listener? = null
        override val navi: NaviPort? = null
        override val location: LocationPort? = null
        override fun status() = listOf("빌드: sim (테스트)", "차량: 시뮬레이터", "음성 안내: 테스트 스피커", "위치·내비: 시나리오 재생")
        override fun release() = Unit
    }

    private val outDir = File("build/screens").apply { mkdirs() }

    private fun shot(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bmp)) }
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderAllScreensThroughScenarioB() {
        val scope = TestScope()
        val c = CareController(platform, scope)
        c.selectScenario(Scenarios.mountain.id)
        c.setSpeed(10)
        compose.setContent {
            CareCabinTheme {
                val s by c.state.collectAsState()
                when (s.screen) {
                    Screen.PRESET -> PresetScreen(s, c)
                    Screen.DRIVING -> DrivingScreen(s, c)
                    Screen.REPORT -> ReportScreen(s, c)
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(s, c)
                }
            }
        }
        shot("1_preset")

        c.start()
        fun advanceTo(t: Int) { while (c.state.value.running && c.state.value.tSec < t) { scope.advanceTimeBy(100); scope.runCurrent() } }
        fun advanceUntil(limit: Int, done: () -> Boolean) { while (c.state.value.running && !done() && c.state.value.tSec < limit) { scope.advanceTimeBy(100); scope.runCurrent() } }
        advanceTo(5)
        println("notice at departure: ${c.state.value.notice} care=${c.state.value.activeCare}")
        advanceUntil(300) { !c.state.value.toolbarVisible }   // any departure notice has closed itself
        shot("2a_driving_idle_edge_tab")     // nothing to say: navigation keeps the screen
        assertTrue("toolbar hidden while idle", !c.state.value.toolbarVisible)
        advanceUntil(560) { c.state.value.notice?.kind == NoticeKind.CARE }
        shot("2b_toolbar_care_notice")
        assertEquals("care notice shown", NoticeKind.CARE, c.state.value.notice?.kind)
        val careAt = c.state.value.notice!!.id
        scope.advanceTimeBy(CareController.NOTICE_MS + 100); scope.runCurrent()
        assertTrue("care notice closes itself", c.state.value.notice?.id != careAt)
        advanceUntil(840) { c.state.value.notice?.kind == NoticeKind.SICK }
        shot("3_toolbar_sickness_high")
        assertEquals("sickness notice shown", NoticeKind.SICK, c.state.value.notice?.kind)
        advanceUntil(840) { c.state.value.question != null }
        shot("4_toolbar_rest_question")
        assertTrue("rest question asked", c.state.value.question != null)
        assertTrue("toolbar stays open for the question", c.state.value.toolbarVisible)
        c.setDashboard(true)
        shot("4b_dashboard")
        c.setDashboard(false)
        c.closeToolbar(); c.openToolbar()
        c.voice(app.carecabin.engine.model.VoiceIntent.YES)
        advanceTo(720)
        shot("5_driving_rain_rule")
        advanceTo(1150)
        c.voice(app.carecabin.engine.model.VoiceIntent.RESTORE)
        while (c.state.value.running) { scope.advanceTimeBy(1000); scope.runCurrent() }
        shot("6_report")
        assertEquals(Screen.REPORT, c.state.value.screen)
        assertTrue(spoken.any { it.contains("커브") })
        c.open(Screen.DIAGNOSTICS)
        shot("7_diagnostics")
    }
}
