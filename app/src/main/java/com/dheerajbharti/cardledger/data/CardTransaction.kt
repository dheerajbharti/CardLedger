package com.dheerajbharti.cardledger.data

enum class InrAmountSource {
    EXACT_INR_ALERT,
    AVAILABLE_LIMIT_DIFFERENCE,
    UNAVAILABLE
}

data class CardTransaction(
    val gmailMessageId: String,
    val cardLast4: String,
    val currency: String,
    val amount: String,
    val merchant: String,
    val transactionEpochMillis: Long,
    val transactionLocalText: String,
    val availableLimitInr: String?,
    val totalLimitInr: String?,
    val emailReceivedEpochMillis: Long,
    val inrAmount: String? = null,
    val inrAmountSource: InrAmountSource = InrAmountSource.UNAVAILABLE,
    val category: SpendingCategory = SpendingCategory.OTHER,
    val categoryManual: Boolean = false
)
