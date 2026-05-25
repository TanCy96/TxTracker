package cy.txtracker.service

import android.util.Log
import cy.txtracker.data.PackageTextRewrite
import cy.txtracker.data.PackageTextRewriteDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Owns the per-package raw-text rewrite map and applies it to incoming notification
 * text before the parser runs. Keeps an in-memory `Map<packageName, List<Regex>>`
 * (sourced reactively from [TransactionRepository.observeRewrites]) so the listener
 * path stays allocation-free in the steady state.
 *
 * Apply order is by learnedAt-ASC (DAO contract). Invalid regex strings are skipped
 * at compile-time rather than blocking ingest — the listener path must stay defensive.
 */
@Singleton
class NotificationRewriteEngine @Inject constructor(
    dao: PackageTextRewriteDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Compiled regex pairs grouped by package. Empty list (or missing key) means
     * "no rewrite for this package" — [apply] short-circuits in that case.
     */
    private val compiled: StateFlow<Map<String, List<CompiledRewrite>>> =
        dao.observeAll()
            .map { rewrites -> compile(rewrites) }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Rewrites the incoming notification body for [packageName]. */
    fun apply(packageName: String, text: String): String =
        applyRules(compiled.value[packageName].orEmpty(), text)

    private fun compile(rows: List<PackageTextRewrite>): Map<String, List<CompiledRewrite>> =
        compileRules(rows) { pattern, pkg, err ->
            Log.w(TAG, "Skipping invalid rewrite '$pattern' for $pkg: ${err.message}")
        }

    internal data class CompiledRewrite(val regex: Regex, val replacement: String)

    internal companion object {
        const val TAG = "NotificationRewriteEngine"

        /**
         * Compiles raw rewrite rows into per-package regex lists. Invalid patterns are
         * skipped (caller is invoked with the offender for logging). Exposed for tests.
         */
        internal fun compileRules(
            rows: List<PackageTextRewrite>,
            onInvalid: (pattern: String, packageName: String, error: Throwable) -> Unit = { _, _, _ -> },
        ): Map<String, List<CompiledRewrite>> {
            if (rows.isEmpty()) return emptyMap()
            val byPackage = LinkedHashMap<String, MutableList<CompiledRewrite>>()
            for (row in rows) {
                val regex = runCatching { Regex(row.pattern, RegexOption.IGNORE_CASE) }
                    .onFailure { onInvalid(row.pattern, row.packageName, it) }
                    .getOrNull() ?: continue
                byPackage.getOrPut(row.packageName) { mutableListOf() }
                    .add(CompiledRewrite(regex, row.replacement))
            }
            return byPackage
        }

        /** Pure apply: sequentially substitutes each rule's regex into [text]. */
        internal fun applyRules(rules: List<CompiledRewrite>, text: String): String {
            if (rules.isEmpty()) return text
            var current = text
            for (rule in rules) {
                current = rule.regex.replace(current, rule.replacement)
            }
            return current
        }
    }
}
