package com.dheerajbharti.cardledger.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * ICICI's alert body uses a 12-hour clock but omits AM/PM. Gmail's received timestamp is used to
 * select the AM or PM interpretation that is closest to when the alert was delivered.
 */
object TransactionTimeResolver {
    private val bankZone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val ambiguousFormat =
        DateTimeFormatter.ofPattern("MMM d, uuuu H:mm:ss", Locale.ENGLISH)
    private val displayFormat =
        DateTimeFormatter.ofPattern("MMM d, uuuu hh:mm:ss a z", Locale.ENGLISH)
    private val storedAmbiguousPattern = Regex(
        """^([A-Za-z]{3}\s+\d{1,2},\s+\d{4})\s+(\d{1,2}:\d{2}:\d{2})\s+IST$"""
    )

    fun resolve(
        dateRaw: String,
        timeRaw: String,
        emailReceivedEpochMillis: Long
    ): Long? {
        val parsed = runCatching {
            LocalDateTime.parse("$dateRaw $timeRaw", ambiguousFormat)
        }.getOrNull() ?: return null

        val hour = parsed.hour
        if (hour == 0 || hour > 12) {
            return parsed.atZone(bankZone).toInstant().toEpochMilli()
        }

        val amHour = if (hour == 12) 0 else hour
        val pmHour = if (hour == 12) 12 else hour + 12
        val candidates = listOf(
            parsed.withHour(amHour).atZone(bankZone).toInstant().toEpochMilli(),
            parsed.withHour(pmHour).atZone(bankZone).toInstant().toEpochMilli()
        )

        // Prefer a transaction not later than the email. A ten-minute allowance covers minor
        // clock skew; the closest candidate then resolves the omitted AM/PM marker.
        val futureAllowanceMillis = 10 * 60 * 1000L
        return candidates.minWithOrNull(
            compareBy<Long> {
                if (it <= emailReceivedEpochMillis + futureAllowanceMillis) 0 else 1
            }.thenBy { abs(emailReceivedEpochMillis - it) }
        )
    }

    fun resolveStoredAmbiguousText(
        transactionLocalText: String,
        emailReceivedEpochMillis: Long
    ): Long? {
        val match = storedAmbiguousPattern.matchEntire(transactionLocalText.trim()) ?: return null
        return resolve(
            dateRaw = match.groupValues[1],
            timeRaw = match.groupValues[2],
            emailReceivedEpochMillis = emailReceivedEpochMillis
        )
    }

    fun format(epochMillis: Long): String =
        displayFormat.format(Instant.ofEpochMilli(epochMillis).atZone(bankZone))
}
