package com.dirkraft.macroman

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dirkraft.macroman.ui.FoodScreen
import com.dirkraft.macroman.ui.SettingsScreen
import com.dirkraft.macroman.ui.WorkoutScreen

enum class Tab { Food, Workout, Settings }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            MaterialTheme(colorScheme = scheme) { App(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}

@Composable
private fun App(vm: MainViewModel) {
    // Without a key nothing can be analysed, so first launch goes straight to Settings.
    var tab by rememberSaveable { mutableStateOf(if (!vm.settings.configured()) Tab.Settings else Tab.Food) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(vm.error) {
        vm.error?.let {
            snackbar.showSnackbar(it)
            vm.error = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Food, onClick = { tab = Tab.Food },
                    icon = { Icon(Icons.Default.Restaurant, null) }, label = { Text("Food") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Workout, onClick = { tab = Tab.Workout },
                    icon = { Icon(Icons.Default.FitnessCenter, null) }, label = { Text("Workout") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings, onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .consumeWindowInsets(padding)
            .imePadding()
        when (tab) {
            Tab.Food -> FoodScreen(vm, snackbar, modifier)
            Tab.Workout -> WorkoutScreen(vm, snackbar, modifier)
            Tab.Settings -> SettingsScreen(vm, modifier)
        }
    }
}
