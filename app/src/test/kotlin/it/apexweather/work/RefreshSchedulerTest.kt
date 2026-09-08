package it.apexweather.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        val config = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    }

    @Test
    fun `ensureScheduled enqueues exactly one periodic job and is idempotent`() {
        RefreshScheduler.ensureScheduled(context)
        RefreshScheduler.ensureScheduled(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RefreshScheduler.PERIODIC_NAME).get()
        assertEquals(1, infos.size)
        assertTrue(infos[0].state == WorkInfo.State.ENQUEUED || infos[0].state == WorkInfo.State.RUNNING)
    }

    @Test
    fun `refreshNow enqueues a one-shot job`() {
        RefreshScheduler.refreshNow(context)
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(RefreshScheduler.ONESHOT_NAME).get()
        assertEquals(1, infos.size)
    }
}
