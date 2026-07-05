package com.hdrezka.pult.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HdRezkaPultTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    when (val s = vm.current) {
        Screen.Home -> HomeScreen(vm)
        Screen.Devices -> DevicesScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Remote -> RemoteScreen(vm)
        is Screen.Results -> ResultsScreen(vm, s)
        is Screen.Details -> DetailsScreen(vm, s)
        is Screen.Seasons -> SeasonsScreen(vm, s)
        is Screen.Episodes -> EpisodesScreen(vm, s)
        is Screen.Quality -> QualityScreen(vm, s)
    }
}
