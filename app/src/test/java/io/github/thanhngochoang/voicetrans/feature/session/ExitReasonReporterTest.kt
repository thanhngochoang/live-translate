package io.github.thanhngochoang.voicetrans.feature.session

import android.app.ApplicationExitInfo
import io.github.thanhngochoang.voicetrans.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitReasonReporterTest {
    @Test fun memoryLimiter_isExitMemory() =
        assertEquals(R.string.exit_memory, ExitReasonReporter.lastAbnormalExit(listOf(ExitInfo(ApplicationExitInfo.REASON_OTHER, "MemoryLimiter:AnonSwap"))))

    @Test fun lowMemory_isExitMemory() =
        assertEquals(R.string.exit_memory, ExitReasonReporter.lastAbnormalExit(listOf(ExitInfo(ApplicationExitInfo.REASON_LOW_MEMORY, null))))

    @Test fun userRequested_isExitUser() =
        assertEquals(R.string.exit_user, ExitReasonReporter.lastAbnormalExit(listOf(ExitInfo(ApplicationExitInfo.REASON_USER_REQUESTED, null))))

    @Test fun excessiveResources_isExitResources() =
        assertEquals(R.string.exit_resources, ExitReasonReporter.lastAbnormalExit(listOf(ExitInfo(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, null))))

    @Test fun exitSelf_isNull() = assertNull(ExitReasonReporter.lastAbnormalExit(listOf(ExitInfo(ApplicationExitInfo.REASON_EXIT_SELF, null))))

    @Test fun otherWithoutMemoryLimiter_isNull() =
        assertNull(ExitReasonReporter.lastAbnormalExit(listOf(ExitInfo(ApplicationExitInfo.REASON_OTHER, "something else"))))

    @Test fun empty_isNull() = assertNull(ExitReasonReporter.lastAbnormalExit(emptyList()))

    @Test fun usesMostRecentFirst_skippingNormalExits() =
        assertEquals(
            R.string.exit_memory,
            ExitReasonReporter.lastAbnormalExit(listOf(ExitInfo(ApplicationExitInfo.REASON_EXIT_SELF, null), ExitInfo(ApplicationExitInfo.REASON_LOW_MEMORY, null))),
        )
}
