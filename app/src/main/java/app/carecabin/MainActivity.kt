package app.carecabin

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.carecabin.platform.Platform
import app.carecabin.platform.PlatformFactory
import app.carecabin.platform.PrefsPresetStore
import app.carecabin.platform.UxGate
import app.carecabin.ui.CareCabinTheme
import app.carecabin.ui.DiagnosticsScreen
import app.carecabin.ui.DrivingScreen
import app.carecabin.ui.PresetScreen
import app.carecabin.ui.ReportScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the platform and controller for the process so a trip survives activity recreation. */
class CareCabinApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val platform: Platform by lazy { PlatformFactory.create(this) }
    val controller: CareController by lazy { CareController(platform, appScope, PrefsPresetStore(this)) }
    val uxGate: UxGate by lazy { UxGate(this) { restricted -> controller.setUxRestricted(restricted) } }

    override fun onTerminate() {
        uxGate.release()
        platform.release()
        super.onTerminate()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as CareCabinApp
        val controller = app.controller
        app.uxGate // subscribe once per process
        // Phone demo only: full screen, so the recording has more room and no personal notification icons.
        if (resources.configuration.screenHeightDp < 720) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        setContent {
            CareCabinTheme {
                val state by controller.state.collectAsState()
                // Keep the display on while a trip runs.
                LaunchedEffect(state.running) {
                    if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Back from report/diagnostics returns to the preset; during a trip it does nothing (no accidental exit).
                BackHandler(enabled = state.screen != Screen.PRESET) {
                    if (state.screen != Screen.DRIVING) controller.backToPreset()
                }
                when (state.screen) {
                    Screen.PRESET -> PresetScreen(state, controller)
                    Screen.DRIVING -> DrivingScreen(state, controller)
                    Screen.REPORT -> ReportScreen(state, controller)
                    Screen.DIAGNOSTICS -> DiagnosticsScreen(state, controller)
                }
            }
        }
    }
}
