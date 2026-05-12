package cy.txtracker.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import cy.txtracker.notify.DeeplinkBus
import cy.txtracker.ui.MainActivity.Deeplink
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cy.txtracker.ui.home.HomeRoute
import cy.txtracker.ui.lock.LockScreen
import cy.txtracker.ui.onboarding.OnboardingScreen
import cy.txtracker.ui.onboarding.openListenerSettings
import cy.txtracker.ui.onboarding.rememberListenerGrantState
import cy.txtracker.ui.settings.SettingsScreen
import cy.txtracker.ui.settings.categories.CategoriesScreen
import cy.txtracker.ui.settings.descriptions.DescriptionMappingsScreen
import cy.txtracker.ui.settings.merchants.MerchantMappingsScreen
import cy.txtracker.ui.settings.notifications.NotificationsScreen
import cy.txtracker.ui.settings.sources.NotificationPriorityScreen

/** Navigation animation duration. 300ms matches the Android platform default. */
private const val NAV_ANIMATION_MS = 300

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SETTINGS_CATEGORIES = "settings/categories"
    const val SETTINGS_MERCHANTS = "settings/merchants"
    const val SETTINGS_DESCRIPTIONS = "settings/descriptions"
    const val SETTINGS_SOURCES = "settings/sources"
    const val SETTINGS_NOTIFICATIONS = "settings/notifications"
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DeeplinkBusEntryPoint {
    fun deeplinkBus(): DeeplinkBus
}

@androidx.compose.runtime.Composable
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
    val deeplinkBus = rememberDeeplinkBus()
    LaunchedEffect(deeplinkBus, nav) {
        deeplinkBus.forAppRoute.collect { deeplink ->
            when (deeplink) {
                Deeplink.PendingFilter -> {
                    nav.navigate(Routes.HOME) {
                        launchSingleTop = true
                    }
                }
            }
        }
    }
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
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
            HomeRoute(onSettingsClick = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onCategoriesClick = { nav.navigate(Routes.SETTINGS_CATEGORIES) },
                onMerchantMappingsClick = { nav.navigate(Routes.SETTINGS_MERCHANTS) },
                onDescriptionMappingsClick = { nav.navigate(Routes.SETTINGS_DESCRIPTIONS) },
                onNotificationPriorityClick = { nav.navigate(Routes.SETTINGS_SOURCES) },
                onNotificationsClick = { nav.navigate(Routes.SETTINGS_NOTIFICATIONS) },
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
        composable(Routes.SETTINGS_NOTIFICATIONS) {
            NotificationsScreen(onBack = { nav.popBackStack() })
        }
    }
}
