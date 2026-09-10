package it.apexweather.ui.navigation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StackedLineChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import it.apexweather.R
import it.apexweather.data.LanguageSetting
import it.apexweather.ui.bulletin.BulletinScreen
import it.apexweather.ui.compare.CompareScreen
import it.apexweather.ui.home.HomeScreen
import it.apexweather.ui.home.HomeViewModel
import it.apexweather.ui.place.PlacePickerScreen
import it.apexweather.ui.settings.SettingsSheet
import it.apexweather.update.UpdateSection
import it.apexweather.ui.settings.SettingsViewModel
import it.apexweather.ui.sky.SkyBackground
import it.apexweather.ui.sky.SkyViewModel
import kotlinx.serialization.Serializable

/**
 * The one way to reach a top-level destination.
 *
 * The bulletin has two entrances, its tab and the teaser card on the home screen. The card used to
 * push it with a plain navigate while the tabs pushed it with these options, and the two shapes of
 * back stack are not interchangeable: after opening the bulletin from the card, the home tab could
 * no longer bring itself back. Everything that opens a tab's destination goes through here.
 */
internal fun NavHostController.openTopLevel(route: Any) {
    navigate(route) {
        popUpTo(HomeRoute) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The place picker. Not a bottom-bar destination, so it is reached with a plain navigate: the
 * one-way-in rule above exists to keep the three tabs' back stacks interchangeable, and this screen
 * is something the reader opens and leaves again.
 */
@Serializable object PlacePickerRoute

@Serializable object HomeRoute
@Serializable object CompareRoute
@Serializable object BulletinRoute

private data class NavItem(val route: Any, val tag: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun ApexApp() {
    val nav = rememberNavController()
    val skyVm: SkyViewModel = hiltViewModel()
    val sky by skyVm.state.collectAsStateWithLifecycle()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()
    val homeVm: HomeViewModel = hiltViewModel()
    val homeState by homeVm.state.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination

    Box(Modifier.fillMaxSize()) {
        SkyBackground(sky.palette, sky.animations)
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = Color(0x59000000), tonalElevation = 0.dp) {
                    val items = listOf(
                        NavItem(HomeRoute, "home", R.string.nav_home, Icons.Rounded.Home),
                        NavItem(CompareRoute, "compare", R.string.nav_compare, Icons.Rounded.StackedLineChart),
                        NavItem(BulletinRoute, "bulletin", R.string.nav_bulletin, Icons.Rounded.Article),
                    )
                    items.forEach { item ->
                        val selected = dest?.hasRoute(item.route::class) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.openTopLevel(item.route) },
                            icon = { Icon(item.icon, null) },
                            label = { Text(stringResource(item.labelRes)) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Color(0x33FFFFFF), unselectedIconColor = Color(0xAAFFFFFF), unselectedTextColor = Color(0xAAFFFFFF)),
                            modifier = Modifier.testTag("nav_${item.tag}"),
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                composable<HomeRoute> {
                    HomeScreen(
                        onOpenBulletin = { nav.openTopLevel(BulletinRoute) },
                        onOpenPlaces = { nav.navigate(PlacePickerRoute) },
                        viewModel = homeVm,
                    )
                }
                composable<PlacePickerRoute> { PlacePickerScreen(onBack = { nav.popBackStack() }) }
                composable<CompareRoute> { CompareScreen() }
                composable<BulletinRoute> { BulletinScreen() }
            }
        }
        IconButton(onClick = { settingsOpen = true }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).testTag("settings_button")) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White.copy(alpha = 0.85f))
        }
    }

    if (settingsOpen) {
        // Asked for straight from the sheet, and re-read afterwards: Android answers the request in
        // its own dialog, and on a refusal the hint has to come back rather than the sheet claiming
        // the switch took effect. Re-read on every open, too — the reader may have changed it in
        // Android's settings since.
        val context = LocalContext.current
        var notificationsAllowed by remember { mutableStateOf(true) }
        fun readPermission() {
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            notificationsAllowed = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { readPermission() }
        LaunchedEffect(Unit) { readPermission() }

        SettingsSheet(
            settings = settings,
            onLanguage = { l ->
                settingsVm.setLanguage(l) { homeVm.refresh() }
                AppCompatDelegate.setApplicationLocales(l.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList())
            },
            onWindUnit = settingsVm::setWindUnit,
            onAnimations = settingsVm::setAnimations,
            onRefresh = homeVm::refresh,
            onDismiss = { settingsOpen = false },
            placeName = homeState.place?.name(LocalConfiguration.current.locales[0]).orEmpty(),
            onOpenPlaces = { settingsOpen = false; nav.navigate(PlacePickerRoute) },
            notificationsAllowed = notificationsAllowed,
            onRequestNotifications = {
                // Below API 33 there is no permission to ask for; the switch that is off lives in
                // Android's own notification settings, and the hint never appears there anyway.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
            onNotifySummary = settingsVm::setNotifySummary,
            onNotifySummaryHour = settingsVm::setNotifySummaryHour,
            onNotifyRain = settingsVm::setNotifyRain,
            onNotifyWarnings = settingsVm::setNotifyWarnings,
            updateSection = { UpdateSection() },
        )
    }
}
