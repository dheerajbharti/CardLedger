package com.dheerajbharti.cardledger.sync

import android.content.Context
import com.dheerajbharti.cardledger.data.CardTransaction
import com.dheerajbharti.cardledger.data.IciciTransactionParser
import com.dheerajbharti.cardledger.data.LedgerDbHelper
import com.dheerajbharti.cardledger.gmail.GmailClient

class LedgerRepository(context: Context) {
    private val db = LedgerDbHelper(context)
    private val gmail = GmailClient()

    data class SyncProgress(val processed: Int, val total: Int)

    data class SyncResult(
        val accountEmail: String?,
        val listed: Int,
        val added: Int,
        val alreadyPresent: Int,
        val notParsed: Int,
        val addedTransactions: List<CardTransaction>
    )

    fun sync(
        accessToken: String,
        fullRescan: Boolean,
        onProgress: ((SyncProgress) -> Unit)? = null
    ): SyncResult {
        val email = runCatching { gmail.getProfileEmail(accessToken) }.getOrNull()
        val ids = gmail.listTransactionMessageIds(accessToken, fullRescan)
        var added = 0
        var alreadyPresent = 0
        var notParsed = 0
        val addedTransactions = mutableListOf<CardTransaction>()

        ids.forEachIndexed { index, id ->
            if (db.containsMessage(id)) {
                alreadyPresent++
            } else {
                val message = gmail.getMessage(accessToken, id)
                val parsed = IciciTransactionParser.parse(
                    gmailMessageId = message.id,
                    body = message.bodyText,
                    emailReceivedEpochMillis = message.internalDateMillis
                )
                if (parsed == null) {
                    notParsed++
                } else if (db.insert(parsed)) {
                    added++
                    addedTransactions += parsed
                } else {
                    alreadyPresent++
                }
            }

            if ((index + 1) % 5 == 0 || index == ids.lastIndex) {
                onProgress?.invoke(SyncProgress(index + 1, ids.size))
            }
        }

        return SyncResult(
            accountEmail = email,
            listed = ids.size,
            added = added,
            alreadyPresent = alreadyPresent,
            notParsed = notParsed,
            addedTransactions = addedTransactions
        )
    }
}
