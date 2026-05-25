package cy.txtracker.service

import com.google.common.truth.Truth.assertThat
import cy.txtracker.data.PackageTextRewrite
import kotlinx.datetime.Instant
import org.junit.Test

class NotificationRewriteEngineTest {

    private val now = Instant.parse("2026-05-18T00:00:00Z")

    @Test
    fun applies_strip_rule_for_matching_package() {
        val rules = NotificationRewriteEngine.compileRules(
            listOf(
                rewrite("com.wise.android", """\s*\.?\s*Tap to see this transaction\.?$""", ""),
            ),
        )

        val before = "199 CNY spent at W Management. Tap to see this transaction"
        val after = NotificationRewriteEngine.applyRules(rules["com.wise.android"]!!, before)

        assertThat(after).isEqualTo("199 CNY spent at W Management")
    }

    @Test
    fun applies_replace_rule_with_non_empty_replacement() {
        val rules = NotificationRewriteEngine.compileRules(
            listOf(rewrite("com.foo", "USD", "MYR")),
        )

        val after = NotificationRewriteEngine.applyRules(rules["com.foo"]!!, "Spent 12 USD")
        assertThat(after).isEqualTo("Spent 12 MYR")
    }

    @Test
    fun rules_run_in_order_so_later_rules_see_earlier_substitutions() {
        // Earlier-learned rule strips the CTA; later-learned rule cleans the dangling
        // period left behind. Compile order is the input order.
        val rules = NotificationRewriteEngine.compileRules(
            listOf(
                rewrite("com.foo", "Tap to see this transaction", "", now),
                rewrite("com.foo", """\s*\.\s*$""", "", now.plus(1)),
            ),
        )

        val after = NotificationRewriteEngine.applyRules(
            rules["com.foo"]!!,
            "199 CNY spent at W Management. Tap to see this transaction",
        )
        assertThat(after).isEqualTo("199 CNY spent at W Management")
    }

    @Test
    fun invalid_regex_is_skipped_not_thrown() {
        var captured = ""
        val rules = NotificationRewriteEngine.compileRules(
            listOf(
                rewrite("com.foo", "[unclosed", ""),
                rewrite("com.foo", "USD", "MYR"),
            ),
        ) { pattern, _, _ -> captured = pattern }

        assertThat(captured).isEqualTo("[unclosed")
        // The valid rule still compiled and applies.
        val after = NotificationRewriteEngine.applyRules(rules["com.foo"]!!, "12 USD")
        assertThat(after).isEqualTo("12 MYR")
    }

    @Test
    fun no_rules_means_text_passes_through_unchanged() {
        val rules = NotificationRewriteEngine.compileRules(emptyList())
        assertThat(NotificationRewriteEngine.applyRules(emptyList(), "anything")).isEqualTo("anything")
        assertThat(rules).isEmpty()
    }

    private fun rewrite(pkg: String, pattern: String, replacement: String, learnedAt: Instant = now) =
        PackageTextRewrite(packageName = pkg, pattern = pattern, replacement = replacement, learnedAt = learnedAt)

    private fun Instant.plus(seconds: Long): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() + seconds * 1000)
}
