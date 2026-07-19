package com.dheerajbharti.cardledger.data

import java.math.BigDecimal

/**
 * Adds an INR debit value without using an external FX service.
 *
 * INR alerts use the exact amount stated in the email. For a foreign-currency alert, the app uses
 * the fall in ICICI's available INR credit limit between the immediately preceding parsed alert
 * and the current alert. This is labelled as an estimate because repayments, refunds, fees,
 * reversals, missing alerts, or another card sharing the combined limit can affect the difference.
 */
object InrEstimator {
    fun enrich(transactions: List<CardTransaction>): List<CardTransaction> {
        var previousAvailable: BigDecimal? = null
        var previousTotal: BigDecimal? = null

        return transactions
            .sortedWith(compareBy<CardTransaction> { it.transactionEpochMillis }.thenBy { it.emailReceivedEpochMillis })
            .map { transaction ->
                val currentAvailable = transaction.availableLimitInr.toDecimalOrNull()
                val currentTotal = transaction.totalLimitInr.toDecimalOrNull()
                val priorAvailable = previousAvailable
                val priorTotal = previousTotal

                val enriched = if (transaction.currency.equals("INR", ignoreCase = true)) {
                    transaction.copy(
                        inrAmount = transaction.amount.toDecimalOrNull()?.plain(),
                        inrAmountSource = InrAmountSource.EXACT_INR_ALERT
                    )
                } else {
                    val sameLimitPool = priorTotal == null || currentTotal == null ||
                        priorTotal.compareTo(currentTotal) == 0
                    val difference = if (sameLimitPool && priorAvailable != null && currentAvailable != null) {
                        priorAvailable.subtract(currentAvailable)
                    } else {
                        null
                    }
                    if (difference != null && difference > BigDecimal.ZERO) {
                        transaction.copy(
                            inrAmount = difference.plain(),
                            inrAmountSource = InrAmountSource.AVAILABLE_LIMIT_DIFFERENCE
                        )
                    } else {
                        transaction.copy(
                            inrAmount = null,
                            inrAmountSource = InrAmountSource.UNAVAILABLE
                        )
                    }
                }

                if (currentAvailable != null) previousAvailable = currentAvailable
                if (currentTotal != null) previousTotal = currentTotal
                enriched
            }
            .sortedWith(compareByDescending<CardTransaction> { it.transactionEpochMillis }
                .thenByDescending { it.emailReceivedEpochMillis })
    }

    private fun String?.toDecimalOrNull(): BigDecimal? =
        this?.replace(",", "")?.toBigDecimalOrNull()

    private fun BigDecimal.plain(): String = stripTrailingZeros().toPlainString()
}
