package com.dheerajbharti.cardledger.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object InsightsCalculator {
    data class DaySpend(val date: LocalDate, val amount: BigDecimal)

    data class Insights(
        val thisMonthTotal: BigDecimal,
        val lastMonthTotal: BigDecimal,
        val monthChangePercent: BigDecimal?,
        val averageDailySpend: BigDecimal,
        val topMerchant: Pair<String, BigDecimal>?,
        val topCategory: Pair<SpendingCategory, BigDecimal>?,
        val internationalSpend: BigDecimal,
        val highestSpendingDay: DaySpend?,
        val weekdaySpend: BigDecimal,
        val weekendSpend: BigDecimal,
        val categoryTotals: Map<SpendingCategory, BigDecimal>,
        val subscriptions: List<SubscriptionDetector.SubscriptionInsight>,
        val yearTotal: BigDecimal
    )

    fun calculate(transactions: List<CardTransaction>, nowMillis: Long = System.currentTimeMillis()): Insights {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val thisMonth = YearMonth.from(now)
        val lastMonth = thisMonth.minusMonths(1)
        val thisYear = now.year

        fun amount(tx: CardTransaction): BigDecimal = tx.inrAmount?.toBigDecimalOrNull() ?: BigDecimal.ZERO

        val thisMonthTransactions = transactions.filter {
            YearMonth.from(Instant.ofEpochMilli(it.transactionEpochMillis).atZone(zone)) == thisMonth
        }
        val lastMonthTransactions = transactions.filter {
            YearMonth.from(Instant.ofEpochMilli(it.transactionEpochMillis).atZone(zone)) == lastMonth
        }
        val yearTransactions = transactions.filter {
            Instant.ofEpochMilli(it.transactionEpochMillis).atZone(zone).year == thisYear
        }

        val thisMonthTotal = thisMonthTransactions.fold(BigDecimal.ZERO) { total, tx -> total + amount(tx) }
        val lastMonthTotal = lastMonthTransactions.fold(BigDecimal.ZERO) { total, tx -> total + amount(tx) }
        val monthChange = if (lastMonthTotal > BigDecimal.ZERO) {
            thisMonthTotal.subtract(lastMonthTotal)
                .multiply(BigDecimal("100"))
                .divide(lastMonthTotal, 1, RoundingMode.HALF_UP)
        } else null
        val elapsedDays = now.dayOfMonth.coerceAtLeast(1)
        val averageDaily = thisMonthTotal.divide(BigDecimal(elapsedDays), 2, RoundingMode.HALF_UP)

        val merchantTotals = thisMonthTransactions.groupBy { it.merchant }.mapValues { (_, items) ->
            items.fold(BigDecimal.ZERO) { total, tx -> total + amount(tx) }
        }
        val categoryTotals = thisMonthTransactions.groupBy { it.category }.mapValues { (_, items) ->
            items.fold(BigDecimal.ZERO) { total, tx -> total + amount(tx) }
        }.toList().sortedByDescending { it.second }.toMap(linkedMapOf())

        val dayTotals = thisMonthTransactions.groupBy {
            Instant.ofEpochMilli(it.transactionEpochMillis).atZone(zone).toLocalDate()
        }.mapValues { (_, items) -> items.fold(BigDecimal.ZERO) { total, tx -> total + amount(tx) } }

        var weekday = BigDecimal.ZERO
        var weekend = BigDecimal.ZERO
        thisMonthTransactions.forEach { tx ->
            val day = Instant.ofEpochMilli(tx.transactionEpochMillis).atZone(zone).dayOfWeek
            if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
                weekend += amount(tx)
            } else {
                weekday += amount(tx)
            }
        }

        return Insights(
            thisMonthTotal = thisMonthTotal,
            lastMonthTotal = lastMonthTotal,
            monthChangePercent = monthChange,
            averageDailySpend = averageDaily,
            topMerchant = merchantTotals.maxByOrNull { it.value }?.toPair(),
            topCategory = categoryTotals.maxByOrNull { it.value }?.toPair(),
            internationalSpend = thisMonthTransactions
                .filter { !it.currency.equals("INR", ignoreCase = true) }
                .fold(BigDecimal.ZERO) { total, tx -> total + amount(tx) },
            highestSpendingDay = dayTotals.maxByOrNull { it.value }?.let { DaySpend(it.key, it.value) },
            weekdaySpend = weekday,
            weekendSpend = weekend,
            categoryTotals = categoryTotals,
            subscriptions = SubscriptionDetector.detect(transactions),
            yearTotal = yearTransactions.fold(BigDecimal.ZERO) { total, tx -> total + amount(tx) }
        )
    }
}
