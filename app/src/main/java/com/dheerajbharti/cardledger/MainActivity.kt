package com.dheerajbharti.cardledger

import android.accounts.Account
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.dheerajbharti.cardledger.data.CardTransaction
import com.dheerajbharti.cardledger.data.InrEstimator
import com.dheerajbharti.cardledger.data.LedgerDbHelper
import com.dheerajbharti.cardledger.databinding.ActivityMainBinding
import com.dheerajbharti.cardledger.sync.EmailSyncWorker
import com.dheerajbharti.cardledger.sync.LedgerRepository
import com.dheerajbharti.cardledger.ui.TransactionAdapter
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.tabs.TabLayout
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var db: LedgerDbHelper
    private lateinit var repository: LedgerRepository
    private val transactionAdapter = TransactionAdapter()
    private var currentTransactions: List<CardTransaction> = emptyList()
    private var pendingFullRescan = false
    private var busy = false
    private var lastObservedSync = 0L
    private var privacyMode = false

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
            val latestSync = prefs.getLong(EmailSyncWorker.KEY_LAST_SYNC, 0L)
            if (latestSync > 0L && latestSync != lastObservedSync && !busy) {
                lastObservedSync = latestSync
                restoreStatus()
                reloadStatement()
            }
            statusHandler.postDelayed(this, 30_000L)
        }
    }

    private val authorizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        try {
            val intent = activityResult.data
                ?: throw IllegalStateException("Google authorization returned no data")
            val authorizationResult = Identity.getAuthorizationClient(this)
                .getAuthorizationResultFromIntent(intent)
            val token = authorizationResult.accessToken
                ?: throw IllegalStateException("Google authorization returned no access token")
            performSync(token, pendingFullRescan)
        } catch (e: ApiException) {
            showError("Gmail authorization was not completed", e)
        } catch (e: Exception) {
            showError("Could not read the authorization result", e)
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.appendLine(
                    "transaction_date_ist,card_last4,currency,amount,merchant," +
                        "estimated_inr_amount,inr_amount_source,available_limit_inr,total_limit_inr"
                )
                currentTransactions.forEach { tx ->
                    writer.appendLine(
                        listOf(
                            tx.transactionLocalText,
                            tx.cardLast4,
                            tx.currency,
                            tx.amount,
                            tx.merchant,
                            tx.inrAmount.orEmpty(),
                            tx.inrAmountSource.name,
                            tx.availableLimitInr.orEmpty(),
                            tx.totalLimitInr.orEmpty()
                        ).joinToString(",") { csvEscape(it) }
                    )
                }
            } ?: throw IllegalStateException("Unable to open the selected file")
            toast("CSV statement exported")
        } catch (e: Exception) {
            showError("CSV export failed", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.rootContainer).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        applySystemBarInsets()

        db = LedgerDbHelper(this)
        repository = LedgerRepository(this)
        privacyMode = getUiPrefs().getBoolean(KEY_PRIVACY_MODE, false)

        setupUi()
        updatePrivacyUi()
        restoreStatus()
        reloadStatement()
        startAutomaticSyncIfConfigured()
    }

    override fun onStart() {
        super.onStart()
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.post(statusRefresh)
    }

    override fun onStop() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::db.isInitialized) reloadStatement()
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
    }

    private fun setupUi() {
        binding.transactionList.layoutManager = LinearLayoutManager(this)
        binding.transactionList.adapter = transactionAdapter

        val periodLabels = StatementPeriod.entries.map { it.label }
        binding.periodSpinner.adapter = ArrayAdapter(
            this,
            R.layout.item_spinner_selected,
            periodLabels
        ).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        binding.periodSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                reloadStatement()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.mainTabs.removeAllTabs()
        binding.mainTabs.addTab(binding.mainTabs.newTab().setText("Ledger"))
        binding.mainTabs.addTab(binding.mainTabs.newTab().setText("Connection"))
        binding.mainTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = updateTabContent(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        updateTabContent(0)

        binding.privacyButton.setOnClickListener { togglePrivacyMode() }
        binding.connectButton.setOnClickListener { authorizeAndSync(fullRescan = true) }
        binding.syncButton.setOnClickListener {
            val prefs = getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
            authorizeAndSync(fullRescan = !prefs.getBoolean(EmailSyncWorker.KEY_INITIAL_SYNC_DONE, false))
        }
        binding.fullRescanButton.setOnClickListener { authorizeAndSync(fullRescan = true) }
        binding.exportButton.setOnClickListener {
            if (currentTransactions.isEmpty()) {
                toast("There are no transactions to export for this period")
            } else {
                val period = selectedPeriod()
                val date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                exportLauncher.launch("card-ledger-${period.fileTag}-$date.csv")
            }
        }
        binding.clearButton.setOnClickListener { confirmClearData() }
        binding.disconnectButton.setOnClickListener { confirmDisconnect() }
    }

    private fun updateTabContent(position: Int) {
        val showLedger = position == 0
        binding.ledgerTabContent.visibility = if (showLedger) View.VISIBLE else View.GONE
        binding.connectionTabContent.visibility = if (showLedger) View.GONE else View.VISIBLE
    }

    private fun togglePrivacyMode() {
        privacyMode = !privacyMode
        getUiPrefs().edit().putBoolean(KEY_PRIVACY_MODE, privacyMode).apply()
        updatePrivacyUi()
        restoreStatus()
        reloadStatement()
    }

    private fun updatePrivacyUi() {
        binding.privacyButton.setImageResource(
            if (privacyMode) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        )
        binding.privacyButton.contentDescription = if (privacyMode) {
            "Show amounts and digits"
        } else {
            "Hide amounts and digits"
        }
        transactionAdapter.setPrivacyMode(privacyMode)
    }

    private fun getUiPrefs() = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    private fun startAutomaticSyncIfConfigured() {
        val prefs = getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(EmailSyncWorker.KEY_INITIAL_SYNC_DONE, false)) return

        EmailSyncWorker.schedule(this)
        val lastSync = prefs.getLong(EmailSyncWorker.KEY_LAST_SYNC, 0L)
        if (System.currentTimeMillis() - lastSync > 30 * 60 * 1000L) {
            EmailSyncWorker.runNow(this)
        }
    }

    private fun restoreStatus() {
        val prefs = getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
        val email = prefs.getString(EmailSyncWorker.KEY_ACCOUNT_EMAIL, null)
        val initialDone = prefs.getBoolean(EmailSyncWorker.KEY_INITIAL_SYNC_DONE, false)
        val lastSync = prefs.getLong(EmailSyncWorker.KEY_LAST_SYNC, 0L)
        val autoStatus = prefs.getString(EmailSyncWorker.KEY_AUTO_SYNC_STATUS, null)
        lastObservedSync = lastSync

        if (email != null) {
            binding.accountStatus.text = "Connected to $email"
            binding.connectButton.text = "Reconnect Gmail"
        } else {
            binding.accountStatus.text = "Gmail is not connected"
            binding.connectButton.text = getString(R.string.connect_gmail)
        }

        binding.syncButton.isEnabled = !busy
        binding.fullRescanButton.isEnabled = !busy
        binding.connectButton.isEnabled = !busy
        binding.clearButton.isEnabled = !busy
        binding.disconnectButton.isEnabled = !busy

        binding.autoSyncStatus.text = privacyAware(
            if (initialDone) {
                "AUTO-SYNC ON  •  ABOUT EVERY ${EmailSyncWorker.AUTO_SYNC_HOURS} HOURS"
            } else {
                "AUTO-SYNC STARTS AFTER YOUR FIRST SCAN"
            }
        )
        binding.syncStatus.text = privacyAware(
            when {
                lastSync > 0L && !autoStatus.isNullOrBlank() ->
                    "Last sync: ${formatInstant(lastSync)}\n$autoStatus"
                lastSync > 0L -> "Last sync: ${formatInstant(lastSync)}"
                else -> "Connect your Google account to scan ICICI transaction alerts."
            }
        )

        if (!busy && binding.mainSyncStatus.text.isNullOrBlank()) {
            binding.mainSyncStatus.visibility = View.GONE
        }
    }

    private fun authorizeAndSync(fullRescan: Boolean) {
        if (busy) return
        pendingFullRescan = fullRescan
        setBusy(true, "Requesting read-only Gmail access…")

        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(EmailSyncWorker.GMAIL_READONLY_SCOPE)))
            .build()

        Identity.getAuthorizationClient(this)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        setBusy(false, "Authorization could not be started")
                        return@addOnSuccessListener
                    }
                    authorizationLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } else {
                    val token = result.accessToken
                    if (token == null) {
                        setBusy(false, "Google did not return an access token")
                    } else {
                        performSync(token, fullRescan)
                    }
                }
            }
            .addOnFailureListener { e ->
                showError("Could not request Gmail access", e)
            }
    }

    private fun performSync(accessToken: String, fullRescan: Boolean) {
        setBusy(true, if (fullRescan) "Scanning all matching ICICI emails…" else "Scanning recent ICICI emails…")
        executor.execute {
            try {
                val result = repository.sync(accessToken, fullRescan) { progress ->
                    runOnUiThread {
                        val progressText = "Scanning email ${progress.processed} of ${progress.total}…"
                        binding.syncStatus.text = privacyAware(progressText)
                        showMainSyncStatus(progressText)
                    }
                }
                val prefs = getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(EmailSyncWorker.KEY_INITIAL_SYNC_DONE, true)
                    .putLong(EmailSyncWorker.KEY_LAST_SYNC, System.currentTimeMillis())
                    .putString(
                        EmailSyncWorker.KEY_AUTO_SYNC_STATUS,
                        "Automatic sync is scheduled in the background"
                    )
                    .apply()
                result.accountEmail?.let {
                    prefs.edit().putString(EmailSyncWorker.KEY_ACCOUNT_EMAIL, it).apply()
                }
                EmailSyncWorker.schedule(this)

                runOnUiThread {
                    restoreStatus()
                    setBusy(
                        false,
                        "Added ${result.added}; already recorded ${result.alreadyPresent}; unmatched ${result.notParsed}."
                    )
                    reloadStatement()
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Email sync failed", e) }
            }
        }
    }

    private fun reloadStatement() {
        if (!::db.isInitialized) return
        val period = selectedPeriod()
        val range = period.range()
        executor.execute {
            val allTransactions = InrEstimator.enrich(db.getAllTransactions())
            val transactions = allTransactions.filter {
                it.transactionEpochMillis >= range.first && it.transactionEpochMillis < range.second
            }
            val originalTotals = originalCurrencyTotals(transactions)
            val estimatedTotal = transactions.mapNotNull { it.inrAmount?.toBigDecimalOrNull() }
                .fold(BigDecimal.ZERO) { total, value -> total.add(value) }
            val coveredCount = transactions.count { it.inrAmount != null }
            val breakdown = buildBreakdown(period, transactions)
            val countText = "${transactions.size} ${if (transactions.size == 1) "transaction" else "transactions"}"
            val totalText = if (transactions.isEmpty() || coveredCount == 0) {
                "—"
            } else {
                TransactionAdapter.formatInr(estimatedTotal.toPlainString())
            }
            val coverageText = when {
                transactions.isEmpty() -> "No transactions in this period"
                coveredCount == transactions.size -> "All ${transactions.size} transactions included in the INR total"
                else -> "$coveredCount of ${transactions.size} transactions included in the INR total"
            }
            val originalTotalsText = if (originalTotals.isEmpty()) {
                "—"
            } else {
                originalTotals.entries.joinToString("  •  ") {
                    TransactionAdapter.formatMoney(it.key, it.value.toPlainString())
                }
            }

            runOnUiThread {
                currentTransactions = transactions
                transactionAdapter.setPrivacyMode(privacyMode)
                transactionAdapter.submitList(transactions)
                binding.emptyView.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
                binding.transactionList.visibility = if (transactions.isEmpty()) View.GONE else View.VISIBLE
                binding.countValue.text = privacyAware(countText)
                binding.totalValue.text = privacyAware(totalText)
                binding.coverageValue.text = privacyAware(coverageText)
                binding.originalTotalsValue.text = privacyAware(originalTotalsText)
                binding.breakdownValue.text = privacyAware(breakdown.ifBlank { "—" })
            }
        }
    }

    private fun originalCurrencyTotals(transactions: List<CardTransaction>): LinkedHashMap<String, BigDecimal> {
        val totals = linkedMapOf<String, BigDecimal>()
        transactions.forEach { tx ->
            totals[tx.currency] = totals.getOrDefault(tx.currency, BigDecimal.ZERO)
                .add(BigDecimal(tx.amount))
        }
        return totals.entries
            .sortedWith(compareBy<Map.Entry<String, BigDecimal>> { if (it.key == "INR") 0 else 1 }.thenBy { it.key })
            .associateTo(linkedMapOf()) { it.key to it.value }
    }

    private fun buildBreakdown(
        period: StatementPeriod,
        transactions: List<CardTransaction>
    ): String {
        if (transactions.isEmpty()) return ""
        val zone = ZoneId.of("Asia/Kolkata")
        val useYears = period == StatementPeriod.ALL_TIME
        val groups = linkedMapOf<String, MutableList<CardTransaction>>()

        transactions.forEach { tx ->
            val date = Instant.ofEpochMilli(tx.transactionEpochMillis).atZone(zone)
            val key = if (useYears) {
                Year.from(date).toString()
            } else {
                YearMonth.from(date).format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH))
            }
            groups.getOrPut(key) { mutableListOf() }.add(tx)
        }

        return groups.entries.joinToString("\n\n") { (label, items) ->
            val estimated = items.mapNotNull { it.inrAmount?.toBigDecimalOrNull() }
                .fold(BigDecimal.ZERO) { total, value -> total.add(value) }
            val coverage = items.count { it.inrAmount != null }
            val originals = originalCurrencyTotals(items).entries.joinToString("  •  ") { (currency, amount) ->
                TransactionAdapter.formatMoney(currency, amount.toPlainString())
            }
            buildString {
                append(label)
                append("\n")
                if (coverage > 0) {
                    append(TransactionAdapter.formatInr(estimated.toPlainString()))
                    append(" estimated INR")
                } else {
                    append("INR estimate unavailable")
                }
                append("  •  ${items.size} tx")
                append("\nOriginal: $originals")
            }
        }
    }

    private fun selectedPeriod(): StatementPeriod {
        val index = binding.periodSpinner.selectedItemPosition.coerceAtLeast(0)
        return StatementPeriod.entries.getOrElse(index) { StatementPeriod.THIS_MONTH }
    }

    private fun setBusy(isBusy: Boolean, status: String) {
        busy = isBusy
        binding.connectButton.isEnabled = !isBusy
        binding.syncButton.isEnabled = !isBusy
        binding.fullRescanButton.isEnabled = !isBusy
        binding.clearButton.isEnabled = !isBusy
        binding.disconnectButton.isEnabled = !isBusy
        binding.syncStatus.text = privacyAware(status)
        showMainSyncStatus(status)
    }

    private fun showMainSyncStatus(message: String) {
        binding.mainSyncStatus.visibility = View.VISIBLE
        binding.mainSyncStatus.text = privacyAware(message)
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear local ledger?")
            .setMessage("This deletes only the parsed transactions stored by Card Ledger. It does not delete any Gmail messages.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                executor.execute {
                    db.clearAll()
                    getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .remove(EmailSyncWorker.KEY_INITIAL_SYNC_DONE)
                        .remove(EmailSyncWorker.KEY_LAST_SYNC)
                        .remove(EmailSyncWorker.KEY_AUTO_SYNC_STATUS)
                        .apply()
                    EmailSyncWorker.cancel(this)
                    runOnUiThread {
                        binding.syncStatus.text = "Local ledger cleared. Run a full scan to rebuild it."
                        binding.autoSyncStatus.text = privacyAware("AUTO-SYNC STARTS AFTER YOUR FIRST SCAN")
                        binding.mainSyncStatus.visibility = View.GONE
                        restoreStatus()
                        reloadStatement()
                    }
                }
            }
            .show()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect Gmail?")
            .setMessage(
                "This revokes Card Ledger's Gmail permission and deletes its local ledger. " +
                    "It does not delete any Gmail messages."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect") { _, _ -> disconnectAndErase() }
            .show()
    }

    private fun disconnectAndErase() {
        val prefs = getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
        val email = prefs.getString(EmailSyncWorker.KEY_ACCOUNT_EMAIL, null)
        if (email.isNullOrBlank()) {
            eraseAllLocalState()
            return
        }

        setBusy(true, "Revoking Gmail access…")
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setScopes(listOf(Scope(EmailSyncWorker.GMAIL_READONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(this)
            .revokeAccess(request)
            .addOnSuccessListener { eraseAllLocalState() }
            .addOnFailureListener { error -> showError("Could not disconnect Gmail", error) }
    }

    private fun eraseAllLocalState() {
        executor.execute {
            db.clearAll()
            getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
            EmailSyncWorker.cancel(this)
            runOnUiThread {
                binding.accountStatus.text = "Gmail is not connected"
                binding.connectButton.text = getString(R.string.connect_gmail)
                binding.autoSyncStatus.text = privacyAware("AUTO-SYNC STARTS AFTER YOUR FIRST SCAN")
                binding.mainSyncStatus.visibility = View.GONE
                setBusy(false, "Gmail disconnected and local ledger erased.")
                reloadStatement()
            }
        }
    }

    private fun showError(prefix: String, error: Throwable) {
        setBusy(false, "$prefix: ${error.message ?: error.javaClass.simpleName}")
        AlertDialog.Builder(this)
            .setTitle(prefix)
            .setMessage(error.message ?: error.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatInstant(epochMillis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)
        return formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }

    private fun csvEscape(value: String): String {
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun privacyAware(text: String): String {
        if (!privacyMode) return text
        return text.replace(Regex("\\d"), "•")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusRefresh)
        executor.shutdown()
        db.close()
        super.onDestroy()
    }

    private enum class StatementPeriod(val label: String, val fileTag: String) {
        THIS_MONTH("This month", "this-month"),
        LAST_MONTH("Last month", "last-month"),
        LAST_THREE_MONTHS("Last 3 months", "last-3-months"),
        LAST_SIX_MONTHS("Last 6 months", "last-6-months"),
        LAST_TWELVE_MONTHS("Last 12 months", "last-12-months"),
        THIS_YEAR("This year", "this-year"),
        LAST_YEAR("Last year", "last-year"),
        ALL_TIME("All time", "all-time");

        fun range(): Pair<Long, Long> {
            val zone = ZoneId.of("Asia/Kolkata")
            val today = LocalDate.now(zone)
            val startDate: LocalDate
            val endDate: LocalDate
            when (this) {
                THIS_MONTH -> {
                    startDate = today.withDayOfMonth(1)
                    endDate = startDate.plusMonths(1)
                }
                LAST_MONTH -> {
                    endDate = today.withDayOfMonth(1)
                    startDate = endDate.minusMonths(1)
                }
                LAST_THREE_MONTHS -> {
                    endDate = today.plusDays(1)
                    startDate = today.minusMonths(3)
                }
                LAST_SIX_MONTHS -> {
                    endDate = today.plusDays(1)
                    startDate = today.minusMonths(6)
                }
                LAST_TWELVE_MONTHS -> {
                    endDate = today.plusDays(1)
                    startDate = today.minusMonths(12)
                }
                THIS_YEAR -> {
                    startDate = today.withDayOfYear(1)
                    endDate = startDate.plusYears(1)
                }
                LAST_YEAR -> {
                    endDate = today.withDayOfYear(1)
                    startDate = endDate.minusYears(1)
                }
                ALL_TIME -> return 0L to Long.MAX_VALUE
            }
            return startDate.atStartOfDay(zone).toInstant().toEpochMilli() to
                endDate.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }

    companion object {
        private const val UI_PREFS = "card_ledger_ui"
        private const val KEY_PRIVACY_MODE = "privacy_mode"
    }
}
