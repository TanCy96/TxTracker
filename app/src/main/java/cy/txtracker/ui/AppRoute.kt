package cy.txtracker.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
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

/** Navigation animation duration. 300ms matches the Android platform default. */
private const val NAV_ANIMATION_MS = 300

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SETTINGS_CATEGORIES = "settings/categories"
    const val SETTINGS_MERCHANTS = "settings/merchants"
    const val SETTINGS_DESCRIPTIONS = "settings/descriptions"
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

    LaunchedEffect(granted) {
        if (granted && !dismissed) {
            viewModel.markOnboardingDismissed()
        }
    }

    // Lock check first — even ahead of onboarding. If the user has the lock toggle on and
    // the app cold-starts or returns from background after the grace period, they
    // authenticate before seeing anything else.
    if (locked) {
        LockScreen(onUnlocked = viewModel::unlock)
        return
    }

    if (!dismissed) {
        OnboardingScreen(
            onGrantAccess = {
                // Optimistically dismiss before opening settings. The grant takes one or two
                // beats to propagate to Settings.Secure on some OEMs, which made the
                // post-grant LaunchedEffect occasionally miss the transition and leave the
                // user stranded on the onboarding screen. Dismissing up front means the
                // user always lands on home after returning from settings, regardless of
                // whether they actually completed the toggle. If they didn't grant,
                // capture stays silent until they fix it via Reset Onboarding.
                viewModel.markOnboardingDismissed()
                context.openListenerSettings()
            },
            onSkip = { viewModel.markOnboardingDismissed() },
        )
        return
    }

    val nav = rememberNavController()
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
    }
}
