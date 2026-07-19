package com.dheerajbharti.cardledger.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object SubscriptionDetector {
    data class SubscriptionInsight(
        val merchant: String,
        val occurrences: Int,
        val typicalInrAmount: BigDecimal?,
        val nextExpectedEpochMillis: Long,
        val estimatedAnnualInr: BigDecimal?
    )

    fun detect(transactions: List<CardTransaction>): List<SubscriptionInsight> {
        val zone = ZoneId.of("Asia/Kolkata")
        return transactions
            .groupBy { CategoryClassifier.normalizeMerchant(it.merchant) }
            .mapNotNull { (_, merchantTransactions) ->
                val sorted = merchantTransactions.sortedBy { it.transactionEpochMillis }
                if (sorted.size < 2) return@mapNotNull null

                val distinctMonths = sorted.map {
                    YearMonth.from(Instant.ofEpochMilli(it.transactionEpochMillis).atZone(zone))
                }.distinct()
                if (distinctMonths.size < 2) return@mapNotNull null

                val dayIntervals = sorted.zipWithNext { first, second ->
                    ChronoUnit.DAYS.between(
                        Instant.ofEpochMilli(first.transactionEpochMillis).atZone(zone).toLocalDate(),
                        Instant.ofEpochMilli(second.transactionEpochMillis).atZone(zone).toLocalDate()
                    )
                }.filter { it in 20..45 }
                if (dayIntervals.isEmpty()) return@mapNotNull null

                val inrAmounts = sorted.mapNotNull { it.inrAmount?.toBigDecimalOrNull() }
                val typical = if (inrAmounts.size >= 2) median(inrAmounts) else null
                if (typical != null) {
                    val maxDeviation = inrAmounts.maxOf {
                        it.subtract(typical).abs().divide(typical.max(BigDecimal.ONE), 4, RoundingMode.HALF_UP)
                    }
                    if (maxDeviation > BigDecimal("0.35")) return@mapNotNull null
                }

                val medianInterval = dayIntervals.sorted()[dayIntervals.size / 2]
                val last = sorted.last()
                val nextExpected = Instant.ofEpochMilli(last.transactionEpochMillis)
                    .atZone(zone)
                    .plusDays(medianInterval)
                    .toInstant()
                    .toEpochMilli()

                SubscriptionInsight(
                    merchant = sorted.last().merchant,
                    occurrences = sorted.size,
                    typicalInrAmount = typical,
                    nextExpectedEpochMillis = nextExpected,
                    estimatedAnnualInr = typical?.multiply(BigDecimal("12"))
                )
            }
            .sortedBy { it.nextExpectedEpochMillis }
    }

    private fun median(values: List<BigDecimal>): BigDecimal {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            sorted[middle - 1].add(sorted[middle]).divide(BigDecimal("2"), 2, RoundingMode.HALF_UP)
        } else {
            sorted[middle]
        }
    }
}
