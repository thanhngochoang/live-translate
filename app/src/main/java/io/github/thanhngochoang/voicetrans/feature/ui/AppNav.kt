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

/**
 * Gate navigation: modelsetup (until every required pack is Ready) -> backgroundsetup (first time only) -> live.
 * Live is unreachable while a pack is missing. Settings and the language picker are placeholders until Task 10.
 */
@Composable
fun AppNav(state: AppUiState, actions: AppActions, navController: NavHostController = rememberNavController()) {
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
        val gated = route == Routes.MODEL_SETUP || route == Routes.BACKGROUND_SETUP || route == Routes.LIVE
        if (gated && route != target) {
            navController.navigate(target) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }
    NavHost(navController, startDestination = start) {
        composable(Routes.MODEL_SETUP) { ModelSetupScreen(state, actions.onDownload) }
        composable(Routes.BACKGROUND_SETUP) {
            BackgroundSetupScreen(state.background, actions.onRequestNotifications, actions.onRequestBattery, actions.onRequestOverlay, actions.onBackgroundContinue)
        }
        composable(Routes.LIVE) {
            if (state.ready) LivePlaceholder(onSettings = { navController.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) { Placeholder(stringResource(R.string.settings_title)) { navController.popBackStack() } }
        composable(Routes.LANGUAGE_PICKER) { Placeholder(stringResource(R.string.language_picker_title)) { navController.popBackStack() } }
    }
}

/** Stand-in for the Live screen (Task 10). */
@Composable
private fun LivePlaceholder(onSettings: () -> Unit) {
    ScreenFrame {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.live_placeholder), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = {}, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.start_listening)) }
        Button(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_title)) }
    }
}

@Composable
private fun Placeholder(title: String, onBack: () -> Unit) {
    ScreenFrame {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.nav_back)) }
    }
}

