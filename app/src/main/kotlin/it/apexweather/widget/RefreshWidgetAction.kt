package it.apexweather.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import it.apexweather.work.RefreshScheduler

/**
 * The widget's refresh button. It enqueues the same one-shot worker the app uses, rather than
 * fetching here: the worker already carries the network constraint and the backoff policy, and it
 * writes through the repository, so the app and every placed widget see the new data together.
 */
class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshScheduler.refreshNow(context)
    }
}
