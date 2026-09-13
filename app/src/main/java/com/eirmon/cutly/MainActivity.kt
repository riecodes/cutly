package com.eirmon.cutly

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.eirmon.cutly.ui.CameraScreen
import com.eirmon.cutly.ui.HomeScreen
import com.eirmon.cutly.ui.theme.CutlyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Two services, so two destinations. Not enough of a graph to be worth a navigation library. */
private enum class Service { Home, Camera }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A take can run a full minute with no touch input; without this the screen dims mid-clip.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pruneStaleTemps()
        setContent {
            CutlyTheme {
                var service by rememberSaveable { mutableStateOf(Service.Home) }
                val view = LocalView.current

                // The camera is black and the home surface is always light.
                LaunchedEffect(service) {
                    val light = service == Service.Home
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = light
                        isAppearanceLightNavigationBars = light
                    }
                }

                when (service) {
                    Service.Home -> HomeScreen(onOpenCamera = { service = Service.Camera })
                    Service.Camera -> {
                        BackHandler { service = Service.Home }
                        CameraScreen()
                    }
                }
            }
        }
    }

    /**
     * Export and transcription temps live in the cache root and are deleted by the code that made
     * them, which a crash or a process kill skips. Anything of ours older than an hour is not in
     * use by any job that could still be running.
     */
    private fun pruneStaleTemps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - STALE_TEMP_MS
            cacheDir.listFiles { file ->
                file.isFile && file.name.startsWith("cutly_") && file.lastModified() < cutoff
            }?.forEach { it.delete() }
        }
    }

    private companion object {
        const val STALE_TEMP_MS = 60L * 60 * 1000
    }
}
