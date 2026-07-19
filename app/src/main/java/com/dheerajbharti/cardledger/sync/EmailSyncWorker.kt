package com.dheerajbharti.cardledger.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

class EmailSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_INITIAL_SYNC_DONE, false)) return Result.success()

        return try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
                .build()
            val authResult = Tasks.await(
                Identity.getAuthorizationClient(applicationContext).authorize(request),
                30,
                TimeUnit.SECONDS
            )
            if (authResult.hasResolution()) {
                prefs.edit().putString(KEY_AUTO_SYNC_STATUS, "Reconnect Gmail to resume automatic sync").apply()
                return Result.success()
            }
            val token = authResult.accessToken ?: return Result.retry()

            val result = LedgerRepository(applicationContext).sync(
                accessToken = token,
                fullRescan = false
            )
            prefs.edit()
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .putString(
                    KEY_AUTO_SYNC_STATUS,
                    "Automatic sync completed: ${result.added} new transaction${if (result.added == 1) "" else "s"}"
                )
                .apply()
            result.accountEmail?.let {
                prefs.edit().putString(KEY_ACCOUNT_EMAIL, it).apply()
            }
            Result.success()
        } catch (error: Exception) {
            prefs.edit()
                .putString(KEY_AUTO_SYNC_STATUS, "Automatic sync will retry when network and Gmail access are available")
                .apply()
            Result.retry()
        }
    }

    companion object {
        const val PREFS = "card_ledger_preferences"
        const val KEY_INITIAL_SYNC_DONE = "initial_sync_done"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_AUTO_SYNC_STATUS = "auto_sync_status"
        const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
        const val AUTO_SYNC_HOURS = 6L
        private const val UNIQUE_PERIODIC_WORK = "icici_email_sync"
        private const val UNIQUE_IMMEDIATE_WORK = "icici_email_sync_now"

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EmailSyncWorker>(AUTO_SYNC_HOURS, TimeUnit.HOURS)
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmailSyncWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context.applicationContext)
            manager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            manager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK)
        }
    }
}
