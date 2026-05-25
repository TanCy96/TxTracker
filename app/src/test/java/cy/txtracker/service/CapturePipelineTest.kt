package cy.txtracker.service

import com.google.common.truth.Truth.assertThat
import cy.txtracker.parsing.HeuristicExtractor
import kotlinx.datetime.Instant
import org.junit.Test

class CapturePipelineTest {

    private val pipeline = CapturePipeline(HeuristicExtractor())
    private val now = Instant.parse("2026-05-25T12:00:00Z")

    @Test
    fun heuristic_success_wins_over_pool() {
        val decision = pipeline.decide(
            packageName = "com.chat",
            rawText = "Paid RM12.00 to Coffee Shop",
            rewrittenText = "Paid RM12.00 to Coffee Shop",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
        )

        assertThat(decision).isInstanceOf(CaptureDecision.Parsed::class.java)
        val parsed = (decision as CaptureDecision.Parsed).parsed
        assertThat(parsed.merchantRaw).isEqualTo("Coffee Shop")
        assertThat(parsed.rawText).isEqualTo("Paid RM12.00 to Coffee Shop")
    }

    @Test
    fun amount_only_text_goes_to_pool_for_any_package() {
        val decision = pipeline.decide(
            packageName = "com.chat",
            rawText = "Lunch yesterday RM50 split reminder",
            rewrittenText = "Lunch yesterday RM50 split reminder",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
        ) as CaptureDecision.Pooled

        assertThat(decision.packageName).isEqualTo("com.chat")
        assertThat(decision.amountMinor).isEqualTo(5000L)
        assertThat(decision.currency).isEqualTo("MYR")
        assertThat(decision.rawText).contains("Lunch")
        assertThat(decision.rewrittenText).isNull()
    }

    @Test
    fun amountless_text_drops() {
        val decision = pipeline.decide(
            packageName = "com.chat",
            rawText = "Your password was changed",
            rewrittenText = "Your password was changed",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
        )

        assertThat(decision).isEqualTo(CaptureDecision.Dropped)
    }

    @Test
    fun rewritten_text_is_used_for_parsing_and_raw_text_is_retained() {
        val decision = pipeline.decide(
            packageName = "com.bank",
            rawText = "Paid RM12.00 CTA",
            rewrittenText = "Paid RM12.00 to Coffee Shop",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
        )

        assertThat(decision).isInstanceOf(CaptureDecision.Parsed::class.java)
        val parsed = (decision as CaptureDecision.Parsed).parsed
        assertThat(parsed.merchantRaw).isEqualTo("Coffee Shop")
        assertThat(parsed.rawText).isEqualTo("Paid RM12.00 CTA")
    }
}
