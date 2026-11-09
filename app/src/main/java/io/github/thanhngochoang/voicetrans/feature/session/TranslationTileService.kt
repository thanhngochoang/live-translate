package io.github.thanhngochoang.voicetrans.feature.session

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.thanhngochoang.voicetrans.MainActivity
import io.github.thanhngochoang.voicetrans.R

/**
 * Quick Settings tile. Running: active, tap stops. Stopped: tap opens the app with EXTRA_AUTO_START, because a
 * mediaProjection session must start from a visible UI (new consent every time); the tile never starts the service.
 */
class TranslationTileService : TileService() {

    override fun onStartListening() = refresh()

    override fun onClick() {
        handleClick(currentState(), stop = { TranslationService.stop(this) }, openMain = ::openMain)
    }

    private fun currentState(): SessionState = (TranslationService.depsOverride ?: ServiceDeps.fromEntryPoint(applicationContext)).session.state.value

    private fun refresh() {
        val tile = qsTile ?: return
        val state = currentState()
        tile.state = tileStateFor(state)
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (state == SessionState.Running) getString(R.string.tile_subtitle_running) else null
        }
        tile.updateTile()
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openMain() {
        val intent = mainIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 10, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun tileStateFor(state: SessionState): Int = if (state == SessionState.Running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

        fun handleClick(state: SessionState, stop: () -> Unit, openMain: () -> Unit) {
            if (state == SessionState.Running) stop() else openMain()
        }

        fun mainIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_AUTO_START, true)

        /** Asks the system to refresh the tile (the tile is declared ACTIVE_TILE). */
        fun requestRefresh(context: Context) =
            requestListeningState(context, ComponentName(context, TranslationTileService::class.java))
    }
}
