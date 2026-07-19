package com.dheerajbharti.cardledger.data

import org.junit.Assert.assertEquals
import org.junit.Test

class InrEstimatorTest {
    @Test
    fun estimatesForeignCurrencyFromAvailableLimitDifference() {
        val transactions = listOf(
            tx("1", "INR", "50", "49023.88", 1L),
            tx("2", "BRL", "99.90", "47137.03", 2L)
        )

        val result = InrEstimator.enrich(transactions)
        val foreign = result.first { it.currency == "BRL" }

        assertEquals("1886.85", foreign.inrAmount)
        assertEquals(InrAmountSource.AVAILABLE_LIMIT_DIFFERENCE, foreign.inrAmountSource)
    }

    @Test
    fun usesExactAmountForInrAlerts() {
        val result = InrEstimator.enrich(listOf(tx("1", "INR", "922.12", "49073.88", 1L)))

        assertEquals("922.12", result.single().inrAmount)
        assertEquals(InrAmountSource.EXACT_INR_ALERT, result.single().inrAmountSource)
    }

    private fun tx(
        id: String,
        currency: String,
        amount: String,
        availableLimit: String,
        epoch: Long
    ) = CardTransaction(
        gmailMessageId = id,
        cardLast4 = "5000",
        currency = currency,
        amount = amount,
        merchant = "Test",
        transactionEpochMillis = epoch,
        transactionLocalText = "",
        availableLimitInr = availableLimit,
        totalLimitInr = "50000.00",
        emailReceivedEpochMillis = epoch
    )
}
