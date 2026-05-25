package cy.txtracker.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import cy.txtracker.notify.DeeplinkBus
import cy.txtracker.ui.MainActivity.Deeplink
import cy.txtracker.ui.foreign.ForeignRoute
import cy.txtracker.ui.home.HomeRoute
import cy.txtracker.ui.lock.LockScreen
import cy.txtracker.ui.onboarding.OnboardingScreen
import cy.txtracker.ui.onboarding.openListenerSettings
import cy.txtracker.ui.onboarding.rememberListenerGrantState
import cy.txtracker.ui.settings.SettingsScreen
import cy.txtracker.ui.settings.categories.CategoriesScreen
import cy.txtracker.ui.settings.capture.PoolScreen
import cy.txtracker.ui.settings.capture.TrackedAppsScreen
import cy.txtracker.ui.settings.currencies.CurrenciesScreen
import cy.txtracker.ui.settings.currencies.TripHistoryScreen
import cy.txtracker.ui.settings.descriptions.DescriptionMappingsScreen
import cy.txtracker.ui.settings.merchants.MerchantMappingsScreen
import cy.txtracker.ui.settings.rewrites.RewritesScreen
import cy.txtracker.ui.settings.notifications.NotificationsScreen
import cy.txtracker.ui.settings.sources.NotificationPriorityScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Navigation animation duration. 300ms matches the Android platform default. */
private const val NAV_ANIMATION_MS = 300

private object Routes {
    const val HOME = "home"
    const val FOREIGN = "foreign"
    const val SETTINGS = "settings"
    const val SETTINGS_CATEGORIES = "settings/categories"
    const val SETTINGS_MERCHANTS = "settings/merchants"
    const val SETTINGS_DESCRIPTIONS = "settings/descriptions"
    const val SETTINGS_SOURCES = "settings/sources"
    const val SETTINGS_CURRENCIES = "settings/currencies"
    const val SETTINGS_CURRENCIES_TRIPS = "settings/currencies/trips/{code}"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
    const val SETTINGS_REWRITES = "settings/rewrites"
    const val SETTINGS_POOL = "settings/pool"
    const val SETTINGS_POOL_PACKAGE = "settings/pool/{packageName}"
    const val SETTINGS_TRACKED_APPS = "settings/tracked-apps"
}

private val TOP_LEVEL_ROUTES = setOf(Routes.HOME, Routes.FOREIGN, Routes.SETTINGS)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeeplinkBusEntryPoint {
    fun deeplinkBus(): DeeplinkBus
}

@Composable
private fun rememberDeeplinkBus(): DeeplinkBus {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DeeplinkBusEntryPoint::class.java,
        ).deeplinkBus()
    }
}

/**
 * Top-level routing.
 *
 * Onboarding is shown whenever the dismissed flag is false. The flag is auto-set to true
 * the moment the user grants notification access, so first-time users see onboarding, grant
 * access, and never see it again. The "Reset onboarding" action in Settings clears the flag,
 * which makes the screen reappear immediately (the StateFlow drives recomposition); the
 * auto-dismiss only fires on a `granted` transition, not on every recomposition, so a Reset
 * with access already granted does NOT immediately re-dismiss itself.
 */
@Composable
fun AppRoute(viewModel: AppViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val granted by rememberListenerGrantState()
    val dismissed by viewModel.onboardingDismissed.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()

    // Lock check first — even ahead of onboarding. If the user has the lock toggle on and
    // the app cold-starts or returns from background after the grace period, they
    // authenticate before seeing anything else.
    if (locked) {
        LockScreen(onUnlocked = viewModel::unlock)
        return
    }

    if (!dismissed) {
        OnboardingScreen(
            granted = granted,
            onGrantAccess = { context.openListenerSettings() },
            onDismiss = { viewModel.markOnboardingDismissed() },
        )
        return
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // Deep-link bus: navigates to the appropriate top-level route when a notification
    // tap (or other intent) emits a Deeplink. HomeViewModel also collects from the bus
    // independently to update its filter state.
    val deeplinkBus = rememberDeeplinkBus()
    LaunchedEffect(deeplinkBus, nav) {
        deeplinkBus.forAppRoute.collect { deeplink ->
            when (deeplink) {
                Deeplink.PendingFilter,
                Deeplink.CurrencyReview,
                -> navigateTopLevel(nav, Routes.HOME)
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in TOP_LEVEL_ROUTES) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.HOME,
                        onClick = { navigateTopLevel(nav, Routes.HOME) },
                        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
                        label = { Text("Home") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.FOREIGN,
                        onClick = { navigateTopLevel(nav, Routes.FOREIGN) },
                        icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                        label = { Text("Foreign") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { navigateTopLevel(nav, Routes.SETTINGS) },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            // Only consume the bottom inset (NavigationBar height). Each
            // destination has its own TopAppBar that owns the status-bar
            // inset — applying the full Scaffold padding here would double
            // up the top spacing.
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            // Native-style horizontal slide: forward push moves both screens to the left
            // (incoming from the right, outgoing off the left), back pop reverses both.
            // Applied at NavHost level so every destination inherits without per-route boilerplate.
            enterTransition = {
                slideIntoContainer(SlideDirection.Left, animationSpec = tween(NAV_ANIMATION_MS))
            },
            exitTransition = {
                slideOutOfContainer(SlideDirection.Left, animationSpec = tween(NAV_ANIMATION_MS))
            },
            popEnterTransition = {
                slideIntoContainer(SlideDirection.Right, animationSpec = tween(NAV_ANIMATION_MS))
            },
            popExitTransition = {
                slideOutOfContainer(SlideDirection.Right, animationSpec = tween(NAV_ANIMATION_MS))
            },
        ) {
            composable(Routes.HOME) {
                HomeRoute(onSettingsClick = { navigateTopLevel(nav, Routes.SETTINGS) })
            }
            composable(Routes.FOREIGN) {
                ForeignRoute(onSettingsClick = { navigateTopLevel(nav, Routes.SETTINGS) })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    // With Settings as a top-level tab, popBackStack() here returns to whichever
                    // tab was previously active (saveState/restoreState mechanic). This is
                    // acceptable for v1 — revisit if a dedicated back-arrow feels wrong.
                    onBack = { nav.popBackStack() },
                    onCategoriesClick = { nav.navigate(Routes.SETTINGS_CATEGORIES) },
                    onMerchantMappingsClick = { nav.navigate(Routes.SETTINGS_MERCHANTS) },
                    onDescriptionMappingsClick = { nav.navigate(Routes.SETTINGS_DESCRIPTIONS) },
                    onNotificationPriorityClick = { nav.navigate(Routes.SETTINGS_SOURCES) },
                    onForeignCurrenciesClick = { nav.navigate(Routes.SETTINGS_CURRENCIES) },
                    onNotificationsClick = { nav.navigate(Routes.SETTINGS_NOTIFICATIONS) },
                    onRewritesClick = { nav.navigate(Routes.SETTINGS_REWRITES) },
                    onNotificationPoolClick = { nav.navigate(Routes.SETTINGS_POOL) },
                    onTrackedAppsClick = { nav.navigate(Routes.SETTINGS_TRACKED_APPS) },
                )
            }
            composable(Routes.SETTINGS_CATEGORIES) {
                CategoriesScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_MERCHANTS) {
                MerchantMappingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_DESCRIPTIONS) {
                DescriptionMappingsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_SOURCES) {
                NotificationPriorityScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_CURRENCIES) {
                CurrenciesScreen(
                    onBack = { nav.popBackStack() },
                    onTripHistory = { code -> nav.navigate("settings/currencies/trips/$code") },
                )
            }
            composable(Routes.SETTINGS_CURRENCIES_TRIPS) { entry ->
                val code = entry.arguments?.getString("code") ?: return@composable
                TripHistoryScreen(currency = code, onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_NOTIFICATIONS) {
                NotificationsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_REWRITES) {
                RewritesScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_POOL) {
                PoolScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS_POOL_PACKAGE) { entry ->
                PoolScreen(
                    packageName = entry.arguments?.getString("packageName"),
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SETTINGS_TRACKED_APPS) {
                TrackedAppsScreen(
                    onBack = { nav.popBackStack() },
                    onPoolPackageClick = { pkg ->
                        nav.navigate("settings/pool/${Uri.encode(pkg)}")
                    },
                )
            }
        }
    }
}

/** Navigate to a top-level tab without accumulating back-stack entries. */
private fun navigateTopLevel(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
