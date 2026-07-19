package com.dheerajbharti.cardledger.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dheerajbharti.cardledger.AppPreferences
import com.dheerajbharti.cardledger.MainActivity
import com.dheerajbharti.cardledger.R
import com.dheerajbharti.cardledger.data.CardTransaction
import com.dheerajbharti.cardledger.ui.TransactionAdapter
import java.math.BigDecimal

object NotificationHelper {
    private const val CHANNEL_ID = "ledger_auto_sync"
    private const val CHANNEL_NAME = "Automatic ledger updates"
    private const val NOTIFICATION_ID = 1401

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when background Gmail sync adds card transactions"
            }
        )
    }

    fun notifyTransactionsAdded(context: Context, transactions: List<CardTransaction>) {
        if (transactions.isEmpty()) return
        val prefs = context.getSharedPreferences(AppPreferences.UI_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(
                AppPreferences.KEY_SYNC_NOTIFICATIONS,
                AppPreferences.DEFAULT_SYNC_NOTIFICATIONS
            )
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        createChannel(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val totals = transactions.groupBy { it.currency }.mapValues { (_, items) ->
            items.fold(BigDecimal.ZERO) { total, tx -> total + tx.amount.toBigDecimal() }
        }
        val totalText = totals.entries.joinToString(" • ") { (currency, amount) ->
            TransactionAdapter.formatMoney(currency, amount.toPlainString())
        }
        val title = if (transactions.size == 1) {
            "A new transaction joined your ledger"
        } else {
            "${transactions.size} new transactions joined your ledger"
        }
        val privacyMode = prefs.getBoolean(AppPreferences.KEY_PRIVACY_MODE, false)
        val body = when {
            privacyMode -> "Automatic sync added ${transactions.size} transaction${if (transactions.size == 1) "" else "s"}. Open Card Ledger to review."
            totalText.isBlank() -> "Automatic sync completed successfully."
            else -> "$totalText added during automatic sync."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
