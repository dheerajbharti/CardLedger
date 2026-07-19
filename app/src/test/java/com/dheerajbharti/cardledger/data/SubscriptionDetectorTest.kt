package com.dheerajbharti.cardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SubscriptionDetectorTest {
    @Test
    fun detectsMonthlyRecurringMerchant() {
        val zone = ZoneId.of("Asia/Kolkata")
        fun tx(id: String, month: Int) = CardTransaction(
            gmailMessageId = id,
            cardLast4 = "5000",
            currency = "INR",
            amount = "999",
            merchant = "STREAMING SERVICE",
            transactionEpochMillis = LocalDateTime.of(2026, month, 10, 12, 0).atZone(zone).toInstant().toEpochMilli(),
            transactionLocalText = "",
            availableLimitInr = null,
            totalLimitInr = null,
            emailReceivedEpochMillis = 0,
            inrAmount = "999",
            inrAmountSource = InrAmountSource.EXACT_INR_ALERT,
            category = SpendingCategory.SUBSCRIPTIONS
        )

        val result = SubscriptionDetector.detect(listOf(tx("1", 5), tx("2", 6), tx("3", 7)))
        assertEquals(1, result.size)
        assertEquals("STREAMING SERVICE", result.first().merchant)
        assertTrue(result.first().estimatedAnnualInr!!.toPlainString().startsWith("11988"))
    }
}
