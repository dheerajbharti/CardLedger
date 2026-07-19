package com.dheerajbharti.cardledger

import com.dheerajbharti.cardledger.data.IciciTransactionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IciciTransactionParserTest {
    @Test
    fun parsesForeignCurrencyTransaction() {
        val body = """
            Dear Customer,

            Your ICICI Bank Credit Card XX5000 has been used for a transaction of BRL 99.90 on Jul 19, 2026 at 12:12:21. Info: OPENAI *CHATGPT SUBSCR.

            The Available Credit Limit on your card is INR 47,137.03 and Total Credit Limit is INR 50,000.00.
        """.trimIndent()

        val parsed = IciciTransactionParser.parse("gmail-1", body, 100L)
        assertNotNull(parsed)
        assertEquals("5000", parsed!!.cardLast4)
        assertEquals("BRL", parsed.currency)
        assertEquals("99.9", parsed.amount)
        assertEquals("OPENAI *CHATGPT SUBSCR", parsed.merchant)
        assertEquals("47137.03", parsed.availableLimitInr)
        assertEquals("50000.00", parsed.totalLimitInr)
    }

    @Test
    fun parsesInrTransaction() {
        val body = """
            Dear Customer,
            Your ICICI Bank Credit Card XX5000 has been used for a transaction of INR 50.00 on Jul 18, 2026 at 08:26:06. Info: ZOMATO CYBS.
            The Available Credit Limit on your card is INR 49,023.88 and Total Credit Limit is INR 50,000.00.
        """.trimIndent()

        val parsed = IciciTransactionParser.parse("gmail-2", body, 200L)
        assertNotNull(parsed)
        assertEquals("INR", parsed!!.currency)
        assertEquals("50", parsed.amount)
        assertEquals("ZOMATO CYBS", parsed.merchant)
    }
}
