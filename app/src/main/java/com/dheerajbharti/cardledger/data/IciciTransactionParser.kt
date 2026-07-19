package com.dheerajbharti.cardledger.data

import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object IciciTransactionParser {
    private val bankZone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val transactionDateFormat =
        DateTimeFormatter.ofPattern("MMM d, uuuu HH:mm:ss", Locale.ENGLISH)

    private val transactionPattern = Regex(
        pattern = """Credit Card\s+XX(\d{4})\s+has been used for a transaction of\s+([A-Z]{3})\s+([\d,]+(?:\.\d+)?)\s+on\s+([A-Za-z]{3}\s+\d{1,2},\s+\d{4})\s+at\s+(\d{1,2}:\d{2}:\d{2})\.\s*Info:\s*(.+?)(?=\.\s*The Available Credit Limit|\s+The Available Credit Limit|$)""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val availableLimitPattern = Regex(
        """Available Credit Limit on your card is INR\s+([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    private val totalLimitPattern = Regex(
        """Total Credit Limit is INR\s+([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    fun parse(
        gmailMessageId: String,
        body: String,
        emailReceivedEpochMillis: Long
    ): CardTransaction? {
        val normalizedBody = body
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .trim()

        val match = transactionPattern.find(normalizedBody) ?: return null
        val (last4, currencyRaw, amountRaw, dateRaw, timeRaw, merchantRaw) = match.destructured

        val amount = try {
            BigDecimal(amountRaw.replace(",", "")).stripTrailingZeros().toPlainString()
        } catch (_: NumberFormatException) {
            return null
        }

        val transactionEpoch = try {
            LocalDateTime.parse("$dateRaw $timeRaw", transactionDateFormat)
                .atZone(bankZone)
                .toInstant()
                .toEpochMilli()
        } catch (_: Exception) {
            return null
        }

        val merchant = merchantRaw
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.')
            .ifBlank { "Unknown merchant" }

        val availableLimit = availableLimitPattern.find(normalizedBody)
            ?.groupValues?.getOrNull(1)
            ?.replace(",", "")

        val totalLimit = totalLimitPattern.find(normalizedBody)
            ?.groupValues?.getOrNull(1)
            ?.replace(",", "")

        return CardTransaction(
            gmailMessageId = gmailMessageId,
            cardLast4 = last4,
            currency = currencyRaw.uppercase(Locale.ENGLISH),
            amount = amount,
            merchant = merchant,
            transactionEpochMillis = transactionEpoch,
            transactionLocalText = "$dateRaw $timeRaw IST",
            availableLimitInr = availableLimit,
            totalLimitInr = totalLimit,
            emailReceivedEpochMillis = emailReceivedEpochMillis
        )
    }
}
