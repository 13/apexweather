package it.apexweather.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.apexweather.data.LanguageSetting

/**
 * Settings as a destination of its own, which is what it should always have been.
 *
 * It was a `ModalBottomSheet` opened from the bottom bar — an action rather than a place, so its
 * bar item could never be *selected*, the back button dismissed it instead of going anywhere, and
 * `ApexApp` carried forty lines of notification-permission plumbing that belong to this screen and
 * to nothing else.
 *
 * What is left here is the wiring and that permission. The page itself is [SettingsContent], the
 * rows it is built from are in `SettingsRows.kt`, and the notification group is its own file.
 *
 * The permission is asked for from here and re-read afterwards: Android answers in its own dialog,
 * and on a refusal the hint has to come back rather than the screen claiming the switch took
 * effect. It is re-read on every entry too — the reader may have changed it in Android's settings
 * since.
 */
@Composable
fun SettingsScreen(
    onOpenPlaces: () -> Unit,
    onRefresh: () -> Unit,
    /** Applying a language is the caller's business: it restarts the activity's locale list. */
    onLanguage: (LanguageSetting) -> Unit,
    placeName: String,
    /** Null where there is nothing to open — no key, so no neighbourhood to list. */
    onOpenStations: (() -> Unit)? = null,
    viewModel: SettingsViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
    updateSection: @Composable () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notificationsAllowed by remember { mutableStateOf(true) }
    fun readPermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        notificationsAllowed = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { readPermission() }
    LaunchedEffect(Unit) { readPermission() }

    SettingsContent(
        settings = settings,
        onLanguage = onLanguage,
        onWindUnit = viewModel::setWindUnit,
        onAnimations = viewModel::setAnimations,
        onAmateurStations = viewModel::setAmateurStations,
        onWuApiKey = viewModel::setWuApiKey,
        onRefresh = onRefresh,
        placeName = placeName,
        onOpenPlaces = onOpenPlaces,
        onOpenStations = onOpenStations,
        notificationsAllowed = notificationsAllowed,
        onRequestNotifications = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Below Tiramisu there is no runtime permission to ask for, only the app's own
                // notification settings, and the hint never appears there anyway.
                readPermission()
            }
        },
        onNotifySummary = viewModel::setNotifySummary,
        onNotifySummaryHour = viewModel::setNotifySummaryHour,
        onNotifyRain = viewModel::setNotifyRain,
        onNotifyWarnings = viewModel::setNotifyWarnings,
        updateSection = updateSection,
    )
}
