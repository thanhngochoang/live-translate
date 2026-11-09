package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.feature.translate.api.EngineState
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundSetupScreen

object Routes {
    const val LOADING = "loading"
    const val BACKGROUND_SETUP = "backgroundsetup"
    const val LIVE = "live"
    const val SETTINGS = "settings"
}

/** The destinations behind the gates. Defaults are placeholders so the gate tests need no view models. */
class AppScreens(
    val live: @Composable (NavHostController) -> Unit = { nav -> LivePlaceholder(onSettings = { nav.navigate(Routes.SETTINGS) }) },
    val settings: @Composable (NavHostController) -> Unit = { nav -> Placeholder(androidx.compose.ui.res.stringResource(R.string.settings_title)) { nav.popBackStack() } },
)

/**
 * Gate navigation: loading (until NMT models are Ready) -> backgroundsetup (first time only) -> live.
 * Live is unreachable while models are loading or failed.
 */
@Composable
fun AppNav(
    state: AppUiState,
    actions: AppActions,
    screens: AppScreens = AppScreens(),
    navController: NavHostController = rememberNavController(),
) {
    val isReady = state.engineState is EngineState.Ready
    val start = remember {
        when {
            !isReady -> Routes.LOADING
            !state.backgroundSetupDone -> Routes.BACKGROUND_SETUP
            else -> Routes.LIVE
        }
    }
    // True while the first-run flow is active. The gate below is the single source of navigation: Continue only saves
    // the flag, and when it lands (it is saved asynchronously) the effect moves on to Live.
    var firstRun by rememberSaveable { mutableStateOf(!state.backgroundSetupDone) }
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(state.engineState, state.backgroundSetupDone, route) {
        val target = when {
            state.engineState !is EngineState.Ready -> Routes.LOADING
            !state.backgroundSetupDone -> Routes.BACKGROUND_SETUP
            else -> Routes.LIVE
        }
        fun go(to: String) = navController.navigate(to) { popUpTo(navController.graph.id) { inclusive = true } }
        when (route) {
            Routes.LOADING, Routes.LIVE -> if (route != target) go(target)
            Routes.BACKGROUND_SETUP -> when {
                state.engineState !is EngineState.Ready -> go(Routes.LOADING)
                !state.backgroundSetupDone -> Unit // first run: stay until the user continues
                firstRun -> { firstRun = false; go(Routes.LIVE) }
                // otherwise it was opened from Live or Settings: an ordinary screen, Continue pops back
            }
        }
    }
    NavHost(navController, startDestination = start) {
        composable(Routes.LOADING) { LoadingScreen(state.engineState) }
        composable(Routes.BACKGROUND_SETUP) {
            BackgroundSetupScreen(
                state.background, actions.onRequestNotifications, actions.onRequestBattery, actions.onRequestOverlay,
                onContinue = { if (firstRun) actions.onBackgroundContinue() else navController.popBackStack() },
            )
        }
        composable(Routes.LIVE) {
            if (state.engineState is EngineState.Ready) screens.live(navController)
        }
        composable(Routes.SETTINGS) { screens.settings(navController) }
    }
}

/** Stand-in for the Live screen (Task 10). */
@Composable
internal fun LivePlaceholder(onSettings: () -> Unit) {
    ScreenFrame {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Button(onClick = {}, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.start_listening)) }
        Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.settings_title))
        }
    }
}

@Composable
internal fun Placeholder(title: String, onBack: () -> Unit) {
    ScreenFrame {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.nav_back))
        }
    }
}
