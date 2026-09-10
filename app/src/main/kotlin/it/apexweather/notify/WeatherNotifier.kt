package it.apexweather.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import it.apexweather.MainActivity
import it.apexweather.R
import it.apexweather.domain.SouthTyrol
import it.apexweather.ui.common.Format
import it.apexweather.ui.common.Formats
import it.apexweather.ui.common.argb
import it.apexweather.ui.common.labelRes
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the notifications [NotificationDecider] asked for.
 *
 * Everything decidable lives in the decider; this class only turns a decision into a platform
 * notification, so that the rules stay testable off-device and this stays a thin, obvious layer.
 *
 * Three channels rather than one, because the three kinds are not equally welcome: a reader who
 * wants a severe-weather warning at three in the morning does not necessarily want a morning
 * summary at all, and Android's own settings are where that is worth deciding.
 */
@Singleton
class WeatherNotifier @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Idempotent, and cheap enough to call on every launch: creating a channel that already exists
     * updates its name and leaves everything the reader changed about it alone.
     */
    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUMMARY, context.getString(R.string.channel_summary_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_summary_desc)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RAIN, context.getString(R.string.channel_rain_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_rain_desc)
            },
        )
        manager.createNotificationChannel(
            // The only one that may wake a phone: a red wind warning is worth it, the other two are not.
            NotificationChannel(CHANNEL_WARNING, context.getString(R.string.channel_warning_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_warning_desc)
            },
        )
    }

    /**
     * True while Android would actually deliver what we post.
     *
     * POST_NOTIFICATIONS is a runtime permission only from API 33. Below that the constant names a
     * permission the platform does not know, so asking for it comes back denied and checking it
     * would switch notifications off on exactly the devices that never needed the permission —
     * minSdk here is 31, so those devices exist.
     */
    fun canPost(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Posts [notifications] and returns those that actually went out.
     *
     * Suppressed because the check lint is asking for is [canPost], one line down, and lint does not
     * follow into it; the call is inside runCatching as well, so a permission revoked between the
     * two costs one notification rather than the background refresh.
     */
    @SuppressLint("MissingPermission")
    fun post(notifications: List<WeatherNotification>, formats: Formats, now: Instant): List<WeatherNotification> {
        if (notifications.isEmpty() || !canPost()) return emptyList()
        val manager = NotificationManagerCompat.from(context)
        return notifications.filter { n ->
            val (id, built) = build(n, formats, now)
            // The permission can be revoked between the check above and here; a lost notification is
            // not worth taking the background refresh down with it.
            runCatching { manager.notify(id, built) }.isSuccess
        }
    }

    private fun build(n: WeatherNotification, formats: Formats, now: Instant): Pair<Int, android.app.Notification> = when (n) {
        // The status-bar icon stays the app's own glyph throughout. The weather drawables are
        // filled shapes drawn to be seen in colour; Android flattens a small icon to a white
        // silhouette, which turns most of them into an unreadable blob.
        is WeatherNotification.Summary -> ID_SUMMARY to base(CHANNEL_SUMMARY)
            .setContentTitle(context.getString(R.string.notif_summary_title, context.getString(n.condition.labelRes()), n.placeName))
            .setContentText(
                context.getString(
                    R.string.notif_summary_text,
                    Format.temp(n.minC, formats), Format.temp(n.maxC, formats), Format.mm(n.precipMm, formats),
                ),
            )
            .build()

        is WeatherNotification.RainStarting -> ID_RAIN to base(CHANNEL_RAIN)
            .setContentTitle(
                context.getString(
                    R.string.notif_rain_title,
                    context.getString(n.hour.condition.labelRes()),
                    Format.time(n.startsAt, SouthTyrol.ZONE, formats),
                ),
            )
            .setContentText(context.getString(R.string.notif_rain_text, n.hour.precipProb, Format.mm(n.hour.precipMm, formats)))
            .build()

        is WeatherNotification.Severe -> {
            val w = n.warning
            warningId(w.identifier) to base(CHANNEL_WARNING)
                .setColor(w.level.argb.toInt())
                .setColorized(false)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentTitle(
                    context.getString(R.string.warn_headline, context.getString(w.type.labelRes()), context.getString(w.level.labelRes())),
                )
                .setContentText(
                    context.getString(
                        R.string.notif_warning_text,
                        w.areaDesc,
                        context.getString(R.string.warn_until, Format.timestamp(w.expires, SouthTyrol.ZONE, now, formats)),
                    ),
                )
                .build()
        }
    }

    private fun base(channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

    companion object {
        const val CHANNEL_SUMMARY = "summary"
        const val CHANNEL_RAIN = "rain"
        const val CHANNEL_WARNING = "warning"

        private const val ID_SUMMARY = 1
        private const val ID_RAIN = 2

        /**
         * One id per warning, derived from its identifier, so re-posting the same warning replaces
         * its notification instead of stacking a second copy. Offset well clear of the fixed ids.
         */
        internal fun warningId(identifier: String): Int = 1000 + (identifier.hashCode() and 0xFFFF)
    }
}
