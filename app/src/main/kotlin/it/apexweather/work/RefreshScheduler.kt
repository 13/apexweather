package it.apexweather.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    const val PERIODIC_NAME = "apex_refresh"
    const val ONESHOT_NAME = "apex_refresh_now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun <B : WorkRequest.Builder<B, *>> B.withRefreshPolicy(): B =
        setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)

    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<RefreshWorker>(60, TimeUnit.MINUTES)
            .withRefreshPolicy()
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<RefreshWorker>()
            .withRefreshPolicy()
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONESHOT_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
