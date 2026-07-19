package com.dheerajbharti.cardledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.math.BigDecimal

class LedgerDbHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        createTransactionsTable(db)
        createSupportingTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN category TEXT NOT NULL DEFAULT '${SpendingCategory.OTHER.name}'"
            )
            db.execSQL(
                "ALTER TABLE $TABLE_TRANSACTIONS ADD COLUMN category_manual INTEGER NOT NULL DEFAULT 0"
            )
            createSupportingTables(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) backfillAutomaticCategories(db)
    }

    private fun createTransactionsTable(db: SQLiteDatabase) {
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
                category TEXT NOT NULL DEFAULT '${SpendingCategory.OTHER.name}',
                category_manual INTEGER NOT NULL DEFAULT 0,
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
        db.execSQL(
            "CREATE INDEX idx_transaction_category ON $TABLE_TRANSACTIONS(category)"
        )
    }

    private fun createSupportingTables(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_transaction_category ON $TABLE_TRANSACTIONS(category)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MERCHANT_RULES (
                merchant_key TEXT PRIMARY KEY,
                category TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_BUDGETS (
                category TEXT PRIMARY KEY,
                monthly_limit TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun backfillAutomaticCategories(db: SQLiteDatabase) {
        data class PendingUpdate(val id: String, val category: SpendingCategory, val manual: Boolean)
        val updates = mutableListOf<PendingUpdate>()
        db.query(
            TABLE_TRANSACTIONS,
            arrayOf("gmail_message_id", "merchant", "currency", "category", "category_manual"),
            "category_manual = 0",
            null,
            null,
            null,
            null
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("gmail_message_id")
            val merchantIndex = cursor.getColumnIndexOrThrow("merchant")
            val currencyIndex = cursor.getColumnIndexOrThrow("currency")
            val categoryIndex = cursor.getColumnIndexOrThrow("category")
            while (cursor.moveToNext()) {
                val current = SpendingCategory.fromStored(cursor.getString(categoryIndex))
                val merchant = cursor.getString(merchantIndex)
                val currency = cursor.getString(currencyIndex)
                val rule = getMerchantRule(db, merchant)
                val resolved = rule ?: CategoryClassifier.classify(merchant, currency)
                if (resolved != current || rule != null) {
                    updates += PendingUpdate(cursor.getString(idIndex), resolved, rule != null)
                }
            }
        }
        updates.forEach { update ->
            db.update(
                TABLE_TRANSACTIONS,
                ContentValues().apply {
                    put("category", update.category.name)
                    put("category_manual", if (update.manual) 1 else 0)
                },
                "gmail_message_id = ?",
                arrayOf(update.id)
            )
        }
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
        val rule = getMerchantRule(readableDatabase, transaction.merchant)
        val category = rule ?: CategoryClassifier.classify(transaction.merchant, transaction.currency)
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
            put("category", category.name)
            put("category_manual", if (rule != null) 1 else 0)
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
            val categoryIndex = cursor.getColumnIndexOrThrow("category")
            val manualIndex = cursor.getColumnIndexOrThrow("category_manual")

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
                    emailReceivedEpochMillis = receivedEpoch,
                    category = SpendingCategory.fromStored(cursor.getString(categoryIndex)),
                    categoryManual = cursor.getInt(manualIndex) == 1
                )
            }
        }
        return results
    }

    @Synchronized
    fun getAllTransactions(): List<CardTransaction> = getTransactions(0L, Long.MAX_VALUE)

    @Synchronized
    fun setMerchantCategory(merchant: String, category: SpendingCategory) {
        val key = CategoryClassifier.normalizeMerchant(merchant)
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertWithOnConflict(
                TABLE_MERCHANT_RULES,
                null,
                ContentValues().apply {
                    put("merchant_key", key)
                    put("category", category.name)
                    put("updated_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            val matchingIds = mutableListOf<String>()
            readableDatabase.query(
                TABLE_TRANSACTIONS,
                arrayOf("gmail_message_id", "merchant"),
                null,
                null,
                null,
                null,
                null
            ).use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow("gmail_message_id")
                val merchantIndex = cursor.getColumnIndexOrThrow("merchant")
                while (cursor.moveToNext()) {
                    if (CategoryClassifier.normalizeMerchant(cursor.getString(merchantIndex)) == key) {
                        matchingIds += cursor.getString(idIndex)
                    }
                }
            }
            matchingIds.forEach { id ->
                writableDatabase.update(
                    TABLE_TRANSACTIONS,
                    ContentValues().apply {
                        put("category", category.name)
                        put("category_manual", 1)
                    },
                    "gmail_message_id = ?",
                    arrayOf(id)
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    private fun getMerchantRule(db: SQLiteDatabase, merchant: String): SpendingCategory? {
        val key = CategoryClassifier.normalizeMerchant(merchant)
        db.query(
            TABLE_MERCHANT_RULES,
            arrayOf("category"),
            "merchant_key = ?",
            arrayOf(key),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                SpendingCategory.fromStored(cursor.getString(0))
            } else {
                null
            }
        }
    }

    @Synchronized
    fun getBudgets(): Map<SpendingCategory, BigDecimal> {
        val result = linkedMapOf<SpendingCategory, BigDecimal>()
        readableDatabase.query(
            TABLE_BUDGETS,
            arrayOf("category", "monthly_limit"),
            null,
            null,
            null,
            null,
            "category ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val category = SpendingCategory.fromStored(cursor.getString(0))
                val value = cursor.getString(1).toBigDecimalOrNull() ?: continue
                result[category] = value
            }
        }
        return result
    }

    @Synchronized
    fun setBudget(category: SpendingCategory, monthlyLimit: BigDecimal?) {
        if (monthlyLimit == null || monthlyLimit <= BigDecimal.ZERO) {
            writableDatabase.delete(TABLE_BUDGETS, "category = ?", arrayOf(category.name))
            return
        }
        writableDatabase.insertWithOnConflict(
            TABLE_BUDGETS,
            null,
            ContentValues().apply {
                put("category", category.name)
                put("monthly_limit", monthlyLimit.stripTrailingZeros().toPlainString())
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun clearTransactions() {
        writableDatabase.delete(TABLE_TRANSACTIONS, null, null)
    }

    @Synchronized
    fun clearEverything() {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(TABLE_TRANSACTIONS, null, null)
            writableDatabase.delete(TABLE_MERCHANT_RULES, null, null)
            writableDatabase.delete(TABLE_BUDGETS, null, null)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "card_ledger.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE_TRANSACTIONS = "transactions"
        private const val TABLE_MERCHANT_RULES = "merchant_category_rules"
        private const val TABLE_BUDGETS = "budgets"
    }
}
