package io.github.thanhngochoang.voicetrans.feature.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.thanhngochoang.voicetrans.R
import io.github.thanhngochoang.voicetrans.feature.ui.background.BackgroundSetupScreen
import io.github.thanhngochoang.voicetrans.feature.ui.modelsetup.ModelSetupScreen

object Routes {
    const val MODEL_SETUP = "modelsetup"
    const val BACKGROUND_SETUP = "backgroundsetup"
    const val LIVE = "live"
    const val SETTINGS = "settings"
    const val LANGUAGE_PICKER = "languagepicker"
}

/** The destinations behind the gates. Defaults are placeholders so the gate tests need no view models. */
class AppScreens(
    val live: @Composable (NavHostController) -> Unit = { nav -> LivePlaceholder(onSettings = { nav.navigate(Routes.SETTINGS) }) },
    val settings: @Composable (NavHostController) -> Unit = { nav -> Placeholder(androidx.compose.ui.res.stringResource(R.string.settings_title)) { nav.popBackStack() } },
    val languagePicker: @Composable (NavHostController) -> Unit = { nav -> Placeholder(androidx.compose.ui.res.stringResource(R.string.language_picker_title)) { nav.popBackStack() } },
)

/**
 * Gate navigation: modelsetup (until every required pack is Ready) -> backgroundsetup (first time only) -> live.
 * Live is unreachable while a pack is missing. Settings and the language picker are placeholders until Task 10.
 */
@Composable
fun AppNav(
    state: AppUiState,
    actions: AppActions,
    screens: AppScreens = AppScreens(),
    navController: NavHostController = rememberNavController(),
) {
    val start = remember {
        when {
            !state.ready -> Routes.MODEL_SETUP
            !state.backgroundSetupDone -> Routes.BACKGROUND_SETUP
            else -> Routes.LIVE
        }
    }
    val route = navController.currentBackStackEntryAsState().value?.destination?.route
    LaunchedEffect(state.ready, state.backgroundSetupDone, route) {
        val target = when {
            !state.ready -> Routes.MODEL_SETUP
            !state.backgroundSetupDone -> Routes.BACKGROUND_SETUP
            else -> Routes.LIVE
        }
        // Background setup is only gated on the first run; later it is an ordinary screen reachable from Live and Settings.
        val gated = route == Routes.MODEL_SETUP || route == Routes.LIVE || (route == Routes.BACKGROUND_SETUP && !state.backgroundSetupDone)
        if (gated && route != target) {
            navController.navigate(target) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }
    NavHost(navController, startDestination = start) {
        composable(Routes.MODEL_SETUP) { ModelSetupScreen(state, actions.onDownload) }
        composable(Routes.BACKGROUND_SETUP) {
            BackgroundSetupScreen(
                state.background, actions.onRequestNotifications, actions.onRequestBattery, actions.onRequestOverlay,
                onContinue = {
                    if (state.backgroundSetupDone) {
                        navController.popBackStack()
                    } else {
                        actions.onBackgroundContinue()
                        navController.navigate(Routes.LIVE) { popUpTo(navController.graph.id) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.LIVE) {
            if (state.ready) screens.live(navController)
        }
        composable(Routes.SETTINGS) { screens.settings(navController) }
        composable(Routes.LANGUAGE_PICKER) { screens.languagePicker(navController) }
    }
}

/** Stand-in for the Live screen (Task 10). */
@Composable
internal fun LivePlaceholder(onSettings: () -> Unit) {
    ScreenFrame {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.live_placeholder), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {}, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.start_listening)) }
        Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_title)) }
    }
}

@Composable
internal fun Placeholder(title: String, onBack: () -> Unit) {
    ScreenFrame {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nav_back)) }
    }
}

