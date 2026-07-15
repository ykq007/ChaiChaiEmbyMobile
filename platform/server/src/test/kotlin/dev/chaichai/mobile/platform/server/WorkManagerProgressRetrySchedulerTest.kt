package dev.chaichai.mobile.platform.server

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.chaichai.mobile.core.contracts.HomeScope
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WorkManagerProgressRetrySchedulerTest {
    @Test
    fun `retry is unique idempotent and waits for connectivity`() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        val scheduler = WorkManagerProgressRetryScheduler(context)
        val scope = HomeScope("server-a", "user-a")

        scheduler.schedule(scope)
        scheduler.schedule(scope)

        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork("progress:server-a:user-a").get()
        assertEquals(1, work.size)
        assertEquals(NetworkType.CONNECTED, work.single().constraints.requiredNetworkType)
    }
}
