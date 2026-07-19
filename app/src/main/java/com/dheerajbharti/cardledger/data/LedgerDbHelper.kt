package com.dheerajbharti.cardledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LedgerDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_TRANSACTIONS (
                gmail_message_id TEXT PRIMARY KEY,
                card_last4 TEXT NOT NULL,
                currency TEXT NOT NULL,
                amount TEXT NOT NULL,
                merchant TEXT NOT NULL,
                transaction_epoch_millis INTEGER NOT NULL,
                transaction_local_text TEXT NOT NULL,
                available_limit_inr TEXT,
                total_limit_inr TEXT,
                email_received_epoch_millis INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_transaction_date ON $TABLE_TRANSACTIONS(transaction_epoch_millis DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_transaction_currency ON $TABLE_TRANSACTIONS(currency)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 has no migrations yet. Future releases should add non-destructive migrations here.
    }

    @Synchronized
    fun containsMessage(gmailMessageId: String): Boolean {
        readableDatabase.query(
            TABLE_TRANSACTIONS,
            arrayOf("gmail_message_id"),
            "gmail_message_id = ?",
            arrayOf(gmailMessageId),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    @Synchronized
    fun insert(transaction: CardTransaction): Boolean {
        val values = ContentValues().apply {
            put("gmail_message_id", transaction.gmailMessageId)
            put("card_last4", transaction.cardLast4)
            put("currency", transaction.currency)
            put("amount", transaction.amount)
            put("merchant", transaction.merchant)
            put("transaction_epoch_millis", transaction.transactionEpochMillis)
            put("transaction_local_text", transaction.transactionLocalText)
            put("available_limit_inr", transaction.availableLimitInr)
            put("total_limit_inr", transaction.totalLimitInr)
            put("email_received_epoch_millis", transaction.emailReceivedEpochMillis)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertWithOnConflict(
            TABLE_TRANSACTIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    @Synchronized
    fun getTransactions(startInclusive: Long, endExclusive: Long): List<CardTransaction> {
        val results = mutableListOf<CardTransaction>()
        readableDatabase.query(
            TABLE_TRANSACTIONS,
            null,
            "transaction_epoch_millis >= ? AND transaction_epoch_millis < ?",
            arrayOf(startInclusive.toString(), endExclusive.toString()),
            null,
            null,
            "transaction_epoch_millis DESC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("gmail_message_id")
            val cardIndex = cursor.getColumnIndexOrThrow("card_last4")
            val currencyIndex = cursor.getColumnIndexOrThrow("currency")
            val amountIndex = cursor.getColumnIndexOrThrow("amount")
            val merchantIndex = cursor.getColumnIndexOrThrow("merchant")
            val txEpochIndex = cursor.getColumnIndexOrThrow("transaction_epoch_millis")
            val txTextIndex = cursor.getColumnIndexOrThrow("transaction_local_text")
            val availableIndex = cursor.getColumnIndexOrThrow("available_limit_inr")
            val totalIndex = cursor.getColumnIndexOrThrow("total_limit_inr")
            val receivedIndex = cursor.getColumnIndexOrThrow("email_received_epoch_millis")

            while (cursor.moveToNext()) {
                val storedEpoch = cursor.getLong(txEpochIndex)
                val storedText = cursor.getString(txTextIndex)
                val receivedEpoch = cursor.getLong(receivedIndex)
                val resolvedEpoch = TransactionTimeResolver.resolveStoredAmbiguousText(
                    transactionLocalText = storedText,
                    emailReceivedEpochMillis = receivedEpoch
                ) ?: storedEpoch

                results += CardTransaction(
                    gmailMessageId = cursor.getString(idIndex),
                    cardLast4 = cursor.getString(cardIndex),
                    currency = cursor.getString(currencyIndex),
                    amount = cursor.getString(amountIndex),
                    merchant = cursor.getString(merchantIndex),
                    transactionEpochMillis = resolvedEpoch,
                    transactionLocalText = if (resolvedEpoch == storedEpoch) {
                        storedText
                    } else {
                        TransactionTimeResolver.format(resolvedEpoch)
                    },
                    availableLimitInr = if (cursor.isNull(availableIndex)) null else cursor.getString(availableIndex),
                    totalLimitInr = if (cursor.isNull(totalIndex)) null else cursor.getString(totalIndex),
                    emailReceivedEpochMillis = receivedEpoch
                )
            }
        }
        return results
    }


    @Synchronized
    fun getAllTransactions(): List<CardTransaction> {
        return getTransactions(0L, Long.MAX_VALUE)
    }

    @Synchronized
    fun clearAll() {
        writableDatabase.delete(TABLE_TRANSACTIONS, null, null)
    }

    companion object {
        private const val DATABASE_NAME = "card_ledger.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_TRANSACTIONS = "transactions"
    }
}
