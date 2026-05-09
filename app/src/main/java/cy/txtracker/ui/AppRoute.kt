package cy.txtracker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cy.txtracker.ui.home.HomeRoute
import cy.txtracker.ui.onboarding.OnboardingPrefs
import cy.txtracker.ui.onboarding.OnboardingScreen
import cy.txtracker.ui.onboarding.openListenerSettings
import cy.txtracker.ui.onboarding.rememberListenerGrantState
import cy.txtracker.ui.settings.SettingsScreen
import cy.txtracker.ui.settings.categories.CategoriesScreen
import cy.txtracker.ui.settings.descriptions.DescriptionMappingsScreen
import cy.txtracker.ui.settings.merchants.MerchantMappingsScreen

private object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SETTINGS_CATEGORIES = "settings/categories"
    const val SETTINGS_MERCHANTS = "settings/merchants"
    const val SETTINGS_DESCRIPTIONS = "settings/descriptions"
}

/**
 * Top-level routing. Shows onboarding on first launch when notification access hasn't been
 * granted and the user hasn't dismissed it; otherwise hands off to a NavHost rooted at home.
 */
@Composable
fun AppRoute() {
    val context = LocalContext.current
    val granted by rememberListenerGrantState()
    var dismissed by remember { mutableStateOf(OnboardingPrefs.isDismissed(context)) }

    if (!granted && !dismissed) {
        OnboardingScreen(
            onGrantAccess = { context.openListenerSettings() },
            onSkip = {
                OnboardingPrefs.setDismissed(context)
                dismissed = true
            },
        )
        return
    }

    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
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
