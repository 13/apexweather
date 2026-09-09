package it.apexweather

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import it.apexweather.notify.WeatherNotifier
import it.apexweather.work.RefreshScheduler
import javax.inject.Inject

@HiltAndroidApp
class ApexApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: WeatherNotifier

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        RefreshScheduler.ensureScheduled(this)
        // Channels have to exist before anything is posted, and the reader can only find them in
        // Android's settings once they do — so they are created whether or not anything is switched on.
        notifier.ensureChannels()
    }
}
