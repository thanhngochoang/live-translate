package io.github.thanhngochoang.voicetrans.feature.session

import android.content.Context
import android.service.quicksettings.Tile
import androidx.test.core.app.ApplicationProvider
import io.github.thanhngochoang.voicetrans.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationTileServiceTest {
    @Test fun tile_followsSessionState() {
        assertEquals(Tile.STATE_ACTIVE, TranslationTileService.tileStateFor(SessionState.Running))
        assertEquals(Tile.STATE_INACTIVE, TranslationTileService.tileStateFor(SessionState.Idle))
        assertEquals(Tile.STATE_INACTIVE, TranslationTileService.tileStateFor(SessionState.Stopped(StopReason.USER)))
    }

    @Test fun clickWhenRunning_stops_andDoesNotOpenApp() {
        var stopped = false; var opened = false
        TranslationTileService.handleClick(SessionState.Running, stop = { stopped = true }, openMain = { opened = true })
        assertTrue(stopped); assertTrue(!opened)
    }

    @Test fun clickWhenStopped_opensMainWithAutoStart_andStartsNoService() {
        var stopped = false; var opened = false
        TranslationTileService.handleClick(SessionState.Stopped(StopReason.USER), stop = { stopped = true }, openMain = { opened = true })
        assertTrue(opened); assertTrue(!stopped)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val intent = TranslationTileService.mainIntent(ctx)
        assertEquals(true, intent.getBooleanExtra(MainActivity.EXTRA_AUTO_START, false))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }
}
