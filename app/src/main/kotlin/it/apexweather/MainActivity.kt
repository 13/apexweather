package it.apexweather

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import it.apexweather.ui.home.HomeScreen
import it.apexweather.ui.home.HomeViewModel
import it.apexweather.ui.sky.SkyBackground
import it.apexweather.ui.theme.ApexTheme

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ApexTheme {
                val vm: HomeViewModel = hiltViewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize()) {
                    SkyBackground(state.palette, state.settings.animations)
                    HomeScreen(onOpenBulletin = {})
                }
            }
        }
    }
}
