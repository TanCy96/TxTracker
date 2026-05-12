package cy.txtracker.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists per-currency banner-dismissal state. When the user dismisses the
 * Home banner for GBP, that currency's flag flips to dismissed and the banner
 * stays hidden until a new currency lands (or the user clears the flag by
 * starting a trip via another path).
 */
@Singleton
class CurrencyPrefs @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _dismissed = MutableStateFlow(readDismissed())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    fun markDismissed(currency: String) {
        val set = readDismissed() + currency
        prefs.edit().putStringSet(KEY_DISMISSED, set).apply()
        _dismissed.value = set
    }

    fun clearDismissal(currency: String) {
        val set = readDismissed() - currency
        prefs.edit().putStringSet(KEY_DISMISSED, set).apply()
        _dismissed.value = set
    }

    private fun readDismissed(): Set<String> =
        prefs.getStringSet(KEY_DISMISSED, emptySet()) ?: emptySet()

    private companion object {
        const val FILE = "currency"
        const val KEY_DISMISSED = "dismissed_banner_currencies"
    }
}
