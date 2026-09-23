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
import androidx.compose.material.icons.rounded.Map
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import it.apexweather.domain.model.Source
import it.apexweather.ui.compare.CompareViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import it.apexweather.R
import it.apexweather.ui.common.CompactLabel
import it.apexweather.data.LanguageSetting
import it.apexweather.ui.bulletin.BulletinScreen
import it.apexweather.ui.compare.CompareScreen
import it.apexweather.ui.home.HomeScreen
import it.apexweather.ui.home.HomeViewModel
import it.apexweather.ui.map.MapScreen
import it.apexweather.ui.place.PlacePickerScreen
import it.apexweather.ui.settings.SettingsScreen
import it.apexweather.update.UpdateSection
import it.apexweather.ui.settings.SettingsViewModel
import it.apexweather.ui.sky.SkyBackground
import it.apexweather.ui.sky.SkyViewModel
import it.apexweather.ui.stations.NearbyStationsScreen
import it.apexweather.ui.stats.StatsScreen
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

/** The key the statistics screen leaves on the comparison screen's back-stack entry. */
internal const val COMPARE_SOURCE_KEY = "compare_source"

/**
 * What a tab in the bar does. The comparison tab stays selected while the statistics show, and
 * [openTopLevel] would save and restore that same two-entry stack, so tapping it did nothing;
 * from there it pops back to the comparison instead.
 */
internal fun NavHostController.selectTab(route: Any) {
    if (route == CompareRoute && currentDestination?.hasRoute(StatsRoute::class) == true) {
        popBackStack(CompareRoute, inclusive = false)
    } else {
        openTopLevel(route)
    }
}

/**
 * "Details zur Quelle": leaves [source] on the comparison entry's own handle and pops back to it,
 * where [ReceiveSourceHandoff] picks it up. Only while the statistics are on top, so a double tap
 * cannot pop the comparison screen as well.
 */
internal fun NavHostController.handSourceToCompare(source: Source) {
    if (currentDestination?.hasRoute(StatsRoute::class) != true) return
    getBackStackEntry<CompareRoute>().savedStateHandle[COMPARE_SOURCE_KEY] = source.name
    popBackStack(CompareRoute, inclusive = false)
}

/**
 * The receiving half of [handSourceToCompare]. It reads the **entry's** handle: a Hilt view model's
 * `SavedStateHandle` is a different object, keyed per view model, and never sees this key — which
 * is why the source sheet once never opened. The key is cleared once delivered.
 */
@Composable
internal fun ReceiveSourceHandoff(entry: NavBackStackEntry, onSource: (Source) -> Unit) {
    val pending by entry.savedStateHandle.getStateFlow<String?>(COMPARE_SOURCE_KEY, null).collectAsState()
    val deliver by rememberUpdatedState(onSource)
    LaunchedEffect(pending) {
        val name = pending ?: return@LaunchedEffect
        Source.entries.firstOrNull { it.name == name }?.let(deliver)
        entry.savedStateHandle[COMPARE_SOURCE_KEY] = null
    }
}

/**
 * The place picker. Not a bottom-bar destination, so it is reached with a plain navigate: the
 * one-way-in rule above exists to keep the three tabs' back stacks interchangeable, and this screen
 * is something the reader opens and leaves again.
 */
@Serializable object PlacePickerRoute

@Serializable object HomeRoute
@Serializable object MapRoute
@Serializable object CompareRoute
@Serializable object BulletinRoute

/**
 * The statistics screen. Reached from the comparison screen's card with a plain navigate, like the
 * place picker: it is opened and left again, and the comparison tab stays selected while it shows.
 */
@Serializable object StatsRoute

/** Every private station around the chosen place, opened from the station card. */
@Serializable object NearbyStationsRoute

/**
 * Settings, which used to be a sheet. Making it a destination is what lets its bar item be
 * *selected* like every other, and lets the back button take the reader out of it rather than
 * dismissing something.
 */
@Serializable object SettingsRoute

private data class NavItem(val route: Any, val tag: String, val labelRes: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun ApexApp() {
    val nav = rememberNavController()
    val skyVm: SkyViewModel = hiltViewModel()
    val sky by skyVm.state.collectAsStateWithLifecycle()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val homeVm: HomeViewModel = hiltViewModel()
    val homeState by homeVm.state.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination

    Box(Modifier.fillMaxSize()) {
        // Coming back to the app asks for today's weather, whichever tab it comes back to. This sits
        // here rather than on the home screen because the reader who left the app on the comparison
        // or the radar is owed the same thing, and because a ViewModel outlives a trip to another
        // app — its one-shot check in init cannot notice that the cache has since gone stale.
        LifecycleResumeEffect(Unit) {
            homeVm.onResumed()
            onPauseOrDispose { }
        }
        SkyBackground(sky.palette, sky.animations)
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                // Nearly opaque, so it carries white labels over anything behind it. It used to be
                // 0x59, which was fine over the sky gradient and unreadable the moment the map put
                // yellow and red radar underneath it.
                NavigationBar(containerColor = Color(0xE6000000), tonalElevation = 0.dp) {
                    val items = listOf(
                        NavItem(HomeRoute, "home", R.string.nav_home, Icons.Rounded.Home),
                        // Second in the bar: after today's weather, where the rain is now is
                        // the next thing a reader reaches for.
                        NavItem(MapRoute, "map", R.string.nav_map, Icons.Rounded.Map),
                        NavItem(CompareRoute, "compare", R.string.nav_compare, Icons.Rounded.StackedLineChart),
                        NavItem(BulletinRoute, "bulletin", R.string.nav_bulletin, Icons.Rounded.Article),
                    )
                    val barColors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Color(0x33FFFFFF), unselectedIconColor = Color(0xAAFFFFFF), unselectedTextColor = Color(0xAAFFFFFF))
                    items.forEach { item ->
                        val selected = dest?.hasRoute(item.route::class) == true ||
                            (item.route == CompareRoute && dest?.hasRoute(StatsRoute::class) == true)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { nav.selectTab(item.route) },
                            icon = { Icon(item.icon, null) },
                            // Five items share the width whatever the reader's text size, so the
                            // label is held to CompactLabel's ceiling; the icon above it still
                            // grows the whole way. maxLines is the backstop: if that ceiling is
                            // ever raised the label clips rather than wrapping into its neighbour.
                            label = {
                                CompactLabel {
                                    Text(stringResource(item.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            colors = barColors,
                            modifier = Modifier.testTag("nav_${item.tag}"),
                        )
                    }
                    // A destination like the rest of them, so it can be selected and the back
                    // button leads out of it. It sits in the bar rather than floating over the
                    // top-right corner, where it covered the first warning card.
                    val settingsSelected = dest?.hasRoute(SettingsRoute::class) == true
                    NavigationBarItem(
                        selected = settingsSelected,
                        onClick = { nav.openTopLevel(SettingsRoute) },
                        icon = { Icon(Icons.Rounded.Settings, null) },
                        // The same ceiling as the four above it. It is outside their loop, so it
                        // has to say so itself — and at a 2x font scale a "Mehr" a third larger
                        // than every label beside it is the one thing worse than all five being.
                        label = {
                            CompactLabel {
                                Text(stringResource(R.string.nav_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        colors = barColors,
                        modifier = Modifier.testTag("settings_button"),
                    )
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = HomeRoute, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
                composable<HomeRoute> {
                    HomeScreen(
                        onOpenBulletin = { nav.openTopLevel(BulletinRoute) },
                        // Not a top-level destination: it is a detail of the station card, and the
                        // back button should return to the home screen rather than switch tabs.
                        onOpenStations = dropUnlessResumed { nav.navigate(NearbyStationsRoute) },
                        onOpenPlaces = { nav.navigate(PlacePickerRoute) },
                        viewModel = homeVm,
                    )
                }
                composable<PlacePickerRoute> { PlacePickerScreen(onBack = { nav.popBackStack() }) }
                composable<MapRoute> { MapScreen() }
                composable<CompareRoute> { entry ->
                    val compareVm: CompareViewModel = hiltViewModel()
                    // The statistics screen hands a source back on this entry's handle.
                    ReceiveSourceHandoff(entry, compareVm::openSource)
                    CompareScreen(onOpenStats = dropUnlessResumed { nav.navigate(StatsRoute) }, viewModel = compareVm)
                }
                composable<NearbyStationsRoute> {
                    NearbyStationsScreen()
                }
                composable<StatsRoute> {
                    StatsScreen(
                        // Dropped unless resumed, so a double tap on the arrow pops once.
                        onBack = dropUnlessResumed { nav.popBackStack() },
                        onOpenSource = nav::handSourceToCompare,
                    )
                }
                composable<BulletinRoute> { BulletinScreen() }
                composable<SettingsRoute> {
                    SettingsScreen(
                        onOpenPlaces = { nav.navigate(PlacePickerRoute) },
                        onRefresh = homeVm::refresh,
                        // Applying a language restarts the activity's locale list, which is the
                        // app shell's business rather than the screen's.
                        onLanguage = { l ->
                            settingsVm.setLanguage(l) { homeVm.refresh() }
                            AppCompatDelegate.setApplicationLocales(
                                l.tag?.let { LocaleListCompat.forLanguageTags(it) } ?: LocaleListCompat.getEmptyLocaleList(),
                            )
                        },
                        placeName = homeState.place?.name(LocalConfiguration.current.locales[0]).orEmpty(),
                        viewModel = settingsVm,
                        updateSection = { UpdateSection() },
                    )
                }
            }
        }
    }

}
