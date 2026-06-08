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

    @Test
    fun rejected_package_parseable_notification_goes_to_pool_not_parsed() {
        val decision = pipeline.decide(
            packageName = "com.google.android.gm",
            rawText = "Paid RM12.00 to Coffee Shop",
            rewrittenText = "Paid RM12.00 to Coffee Shop",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
            isRejected = true,
        )

        assertThat(decision).isInstanceOf(CaptureDecision.Pooled::class.java)
        val pooled = decision as CaptureDecision.Pooled
        assertThat(pooled.packageName).isEqualTo("com.google.android.gm")
        assertThat(pooled.amountMinor).isEqualTo(1200L)
        assertThat(pooled.currency).isEqualTo("MYR")
        assertThat(pooled.rawText).isEqualTo("Paid RM12.00 to Coffee Shop")
        assertThat(pooled.rewrittenText).isNull()
    }

    @Test
    fun non_rejected_package_parseable_notification_still_parses() {
        val decision = pipeline.decide(
            packageName = "com.google.android.gm",
            rawText = "Paid RM12.00 to Coffee Shop",
            rewrittenText = "Paid RM12.00 to Coffee Shop",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
            isRejected = false,
        )

        assertThat(decision).isInstanceOf(CaptureDecision.Parsed::class.java)
    }

    @Test
    fun rejected_package_amount_only_text_still_goes_to_pool() {
        val decision = pipeline.decide(
            packageName = "com.google.android.gm",
            rawText = "Lunch yesterday RM50 split reminder",
            rewrittenText = "Lunch yesterday RM50 split reminder",
            postedAt = now,
            symbolDefaults = emptyMap(),
            capturedAt = now,
            isRejected = true,
        ) as CaptureDecision.Pooled

        assertThat(decision.amountMinor).isEqualTo(5000L)
        assertThat(decision.currency).isEqualTo("MYR")
    }
}
