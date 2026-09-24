package it.apexweather

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import it.apexweather.diagnostics.AppLog
import it.apexweather.diagnostics.CrashHandler
import it.apexweather.diagnostics.DiagnosticsExport
import it.apexweather.diagnostics.ExitReasons
import it.apexweather.notify.WeatherNotifier
import it.apexweather.ui.StaleRefresher
import it.apexweather.work.RefreshScheduler
import javax.inject.Inject

@HiltAndroidApp
class ApexApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: WeatherNotifier
    @Inject lateinit var staleRefresher: StaleRefresher

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // Before super.onCreate, which is where Hilt builds the graph: a crash assembling it is
        // exactly the kind nobody would otherwise ever see.
        AppLog.install(this)
        CrashHandler.install(AppLog.directory(this), DiagnosticsExport::appInfo)
        super.onCreate()
        AppLog.i("App", "start ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
        // What happened to the process before this one — an ANR, a native crash, a kill for memory.
        // Off the main thread: an ANR's trace can be tens of kilobytes.
        Thread({ runCatching { ExitReasons.collect(this) } }, "ExitReasons").start()
        RefreshScheduler.ensureScheduled(this)
        // Channels have to exist before anything is posted, and the reader can only find them in
        // Android's settings once they do — so they are created whether or not anything is switched on.
        notifier.ensureChannels()
        registerActivityLifecycleCallbacks(ForegroundCounter())
    }

    /**
     * Whether the app is in front of somebody, counted rather than observed.
     *
     * `ProcessLifecycleOwner` is the textbook answer and wants
     * `androidx.lifecycle:lifecycle-process`, which this app does not have. It has exactly one
     * Activity, so counting started ones answers the same question with no new dependency — and it
     * still holds if a second Activity ever appears, because the count only reaches zero when none
     * of them is started. A configuration change takes it 1 → 0 → 1, which is why
     * [StaleRefresher.start] is idempotent rather than assumed to be called once.
     */
    private inner class ForegroundCounter : ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            if (started++ == 0) staleRefresher.start()
        }

        override fun onActivityStopped(activity: Activity) {
            if (--started <= 0) {
                started = 0
                staleRefresher.stop()
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
