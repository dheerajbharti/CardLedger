package com.dheerajbharti.cardledger

import com.dheerajbharti.cardledger.data.TransactionTimeResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class TransactionTimeResolverTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    @Test
    fun resolvesEightTwentySixAsEveningWhenEmailArrivesAtEightThirtyOnePm() {
        val received = ZonedDateTime.of(2026, 7, 18, 20, 31, 0, 0, zone)
            .toInstant().toEpochMilli()

        val resolved = TransactionTimeResolver.resolve("Jul 18, 2026", "08:26:06", received)
        assertNotNull(resolved)
        assertEquals(20, Instant.ofEpochMilli(resolved!!).atZone(zone).hour)
    }

    @Test
    fun resolvesThreeNineteenAsAfternoonWhenEmailArrivesAfterThreePm() {
        val received = ZonedDateTime.of(2026, 7, 19, 15, 24, 0, 0, zone)
            .toInstant().toEpochMilli()

        val resolved = TransactionTimeResolver.resolve("Jul 19, 2026", "03:19:00", received)
        assertNotNull(resolved)
        assertEquals(15, Instant.ofEpochMilli(resolved!!).atZone(zone).hour)
    }

    @Test
    fun resolvesTwelveTwelveAsNoonWhenEmailArrivesAtTwelveNineteenPm() {
        val received = ZonedDateTime.of(2026, 7, 19, 12, 19, 0, 0, zone)
            .toInstant().toEpochMilli()

        val resolved = TransactionTimeResolver.resolve("Jul 19, 2026", "12:12:21", received)
        assertNotNull(resolved)
        assertEquals(12, Instant.ofEpochMilli(resolved!!).atZone(zone).hour)
    }
}
