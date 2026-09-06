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
import com.eirmon.cutly.ui.CameraScreen
import com.eirmon.cutly.ui.HomeScreen
import com.eirmon.cutly.ui.theme.CutlyTheme

/** Two services, so two destinations. Not enough of a graph to be worth a navigation library. */
private enum class Service { Home, Camera }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A take can run a full minute with no touch input; without this the screen dims mid-clip.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            CutlyTheme {
                var service by rememberSaveable { mutableStateOf(Service.Home) }
                val view = LocalView.current

                // The hub is a light page and the camera is a black one, so the system bar icons
                // have to flip with the destination or they vanish into whatever is behind them.
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
}
