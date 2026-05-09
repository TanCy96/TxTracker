package cy.txtracker.data

import cy.txtracker.domain.TimeBucket
import kotlinx.datetime.Instant

internal fun txAt(
    occurredAt: Instant,
    amountMinor: Long = 1250,
    merchant: String = "MCDONALDS",
    categoryId: Long? = null,
    description: String? = null,
    bucket: TimeBucket = TimeBucket.MIDDAY,
    sourceApp: String = "com.google.android.apps.nbu.paisa.user",
    dedupeKey: String = "k-${occurredAt.toEpochMilliseconds()}-$merchant-$amountMinor",
    direction: Direction = Direction.OUT,
    rawText: String? = "RM 12.50 paid to $merchant",
    currency: String = "MYR",
): Transaction = Transaction(
    amountMinor = amountMinor,
    currency = currency,
    merchantRaw = merchant,
    merchantNormalized = merchant.uppercase(),
    categoryId = categoryId,
    description = description,
    occurredAt = occurredAt,
    timeBucket = bucket,
    sourceApp = sourceApp,
    rawText = rawText,
    direction = direction,
    createdAt = occurredAt,
    notificationDedupeKey = dedupeKey,
)
