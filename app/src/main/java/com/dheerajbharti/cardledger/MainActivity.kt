package com.dheerajbharti.cardledger

import android.Manifest
import android.accounts.Account
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.dheerajbharti.cardledger.data.CardTransaction
import com.dheerajbharti.cardledger.data.InrEstimator
import com.dheerajbharti.cardledger.data.InsightsCalculator
import com.dheerajbharti.cardledger.data.LedgerDbHelper
import com.dheerajbharti.cardledger.data.SpendingCategory
import com.dheerajbharti.cardledger.databinding.ActivityMainBinding
import com.dheerajbharti.cardledger.databinding.ItemInsightBarBinding
import com.dheerajbharti.cardledger.sync.EmailSyncWorker
import com.dheerajbharti.cardledger.sync.LedgerRepository
import com.dheerajbharti.cardledger.sync.NotificationHelper
import com.dheerajbharti.cardledger.ui.BudgetAdapter
import com.dheerajbharti.cardledger.ui.SubscriptionAdapter
import com.dheerajbharti.cardledger.ui.TransactionAdapter
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.tabs.TabLayout
import java.math.BigDecimal
import java.math.RoundingMode
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
    private lateinit var biometricPrompt: BiometricPrompt

    private val transactionAdapter = TransactionAdapter { showCategoryDialog(it) }
    private val budgetAdapter = BudgetAdapter { showBudgetDialog(it) }
    private val subscriptionAdapter = SubscriptionAdapter()

    private var allTransactions: List<CardTransaction> = emptyList()
    private var currentTransactions: List<CardTransaction> = emptyList()
    private var currentBudgets: Map<SpendingCategory, BigDecimal> = emptyMap()
    private var pendingFullRescan = false
    private var busy = false
    private var lastObservedSync = 0L
    private var privacyMode = false
    private var needsAuthentication = false
    private var authenticationInProgress = false
    private var biometricAction = BiometricAction.UNLOCK
    private var suppressSettingCallbacks = false
    private var budgetProgress = 0f
    private var yearProgress = 0f
    private var syncProgress = -1f
    private var calmMessageOverride: String? = null

    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
            val latestSync = prefs.getLong(EmailSyncWorker.KEY_LAST_SYNC, 0L)
            if (latestSync > 0L && latestSync != lastObservedSync && !busy) {
                lastObservedSync = latestSync
                restoreStatus()
                reloadAllData()
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
                    "transaction_date_ist,card_last4,currency,amount,merchant,category," +
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
                            tx.category.label,
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

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        uiPrefs().edit()
            .putBoolean(AppPreferences.KEY_NOTIFICATION_PERMISSION_ASKED, true)
            .putBoolean(AppPreferences.KEY_SYNC_NOTIFICATIONS, granted)
            .apply()
        suppressSettingCallbacks = true
        binding.notificationSwitch.isChecked = granted
        suppressSettingCallbacks = false
        if (!granted) toast("Automatic-sync notifications remain off")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBiometricPrompt()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowInsetsControllerCompat(window, binding.rootContainer).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        applySystemBarInsets()

        db = LedgerDbHelper(this)
        repository = LedgerRepository(this)
        privacyMode = uiPrefs().getBoolean(AppPreferences.KEY_PRIVACY_MODE, false)
        needsAuthentication = isBiometricEnabled()
        if (needsAuthentication) privacyMode = true

        setupUi()
        syncSettingsUi()
        applyScreenshotProtection()
        NotificationHelper.createChannel(this)
        maybeRequestNotificationPermission()
        updatePrivacyUi()
        restoreStatus()
        reloadAllData()
        startAutomaticSyncIfConfigured()
    }

    override fun onStart() {
        super.onStart()
        statusHandler.removeCallbacks(statusRefresh)
        statusHandler.post(statusRefresh)
        if (isBiometricEnabled() && needsAuthentication && !authenticationInProgress) {
            privacyMode = true
            savePrivacyMode()
            updatePrivacyUi()
            requestBiometric(BiometricAction.UNLOCK)
        }
    }

    override fun onStop() {
        statusHandler.removeCallbacks(statusRefresh)
        if (!isChangingConfigurations && !authenticationInProgress) {
            val biometricEnabled = isBiometricEnabled()
            if (biometricEnabled) needsAuthentication = true
            if (biometricEnabled || isAutoPrivacyEnabled()) {
                privacyMode = true
                savePrivacyMode()
                updatePrivacyUi()
            }
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::db.isInitialized) reloadAllData()
    }

    private fun setupBiometricPrompt() {
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticationInProgress = false
                    needsAuthentication = false
                    if (biometricAction == BiometricAction.ENABLE) {
                        uiPrefs().edit().putBoolean(AppPreferences.KEY_BIOMETRIC, true).apply()
                    }
                    privacyMode = false
                    savePrivacyMode()
                    syncSettingsUi()
                    updatePrivacyUi()
                    reloadAllData()
                    toast("Card Ledger unlocked")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticationInProgress = false
                    if (biometricAction == BiometricAction.ENABLE) {
                        uiPrefs().edit().putBoolean(AppPreferences.KEY_BIOMETRIC, false).apply()
                        syncSettingsUi()
                    }
                    privacyMode = true
                    savePrivacyMode()
                    updatePrivacyUi()
                    if (biometricAction == BiometricAction.UNLOCK) {
                        finish()
                    }
                }

                override fun onAuthenticationFailed() {
                    privacyMode = true
                    savePrivacyMode()
                    updatePrivacyUi()
                }
            }
        )
    }

    private fun requestBiometric(action: BiometricAction) {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val availability = BiometricManager.from(this).canAuthenticate(authenticators)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            toast("Set up a fingerprint, face unlock, PIN, or device password first")
            if (action == BiometricAction.ENABLE) {
                uiPrefs().edit().putBoolean(AppPreferences.KEY_BIOMETRIC, false).apply()
                syncSettingsUi()
            }
            return
        }
        biometricAction = action
        authenticationInProgress = true
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (action == BiometricAction.ENABLE) "Protect Card Ledger" else "Unlock Card Ledger")
            .setSubtitle("Use your biometric or device screen lock")
            .setAllowedAuthenticators(authenticators)
            .build()
        biometricPrompt.authenticate(promptInfo)
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
        binding.budgetList.layoutManager = LinearLayoutManager(this)
        binding.budgetList.adapter = budgetAdapter
        binding.subscriptionList.layoutManager = LinearLayoutManager(this)
        binding.subscriptionList.adapter = subscriptionAdapter

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
                renderStatement()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.mainTabs.removeAllTabs()
        listOf("Ledger", "Insights", "Budgets", "Connection").forEach {
            binding.mainTabs.addTab(binding.mainTabs.newTab().setText(it))
        }
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
        setupSettingsListeners()
    }

    private fun setupSettingsListeners() {
        binding.biometricSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingCallbacks) return@setOnCheckedChangeListener
            if (checked) {
                requestBiometric(BiometricAction.ENABLE)
            } else {
                uiPrefs().edit().putBoolean(AppPreferences.KEY_BIOMETRIC, false).apply()
                needsAuthentication = false
            }
        }
        binding.autoPrivacySwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressSettingCallbacks) {
                uiPrefs().edit().putBoolean(AppPreferences.KEY_AUTO_PRIVACY, checked).apply()
            }
        }
        binding.screenshotSwitch.setOnCheckedChangeListener { _, checked ->
            if (!suppressSettingCallbacks) {
                uiPrefs().edit().putBoolean(AppPreferences.KEY_SCREENSHOT_PROTECTION, checked).apply()
                applyScreenshotProtection()
            }
        }
        binding.notificationSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingCallbacks) return@setOnCheckedChangeListener
            if (checked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                uiPrefs().edit().putBoolean(AppPreferences.KEY_SYNC_NOTIFICATIONS, checked).apply()
            }
        }
    }

    private fun syncSettingsUi() {
        suppressSettingCallbacks = true
        binding.biometricSwitch.isChecked = isBiometricEnabled()
        binding.autoPrivacySwitch.isChecked = isAutoPrivacyEnabled()
        binding.screenshotSwitch.isChecked = uiPrefs().getBoolean(
            AppPreferences.KEY_SCREENSHOT_PROTECTION,
            AppPreferences.DEFAULT_SCREENSHOT_PROTECTION
        )
        val notificationAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        binding.notificationSwitch.isChecked = uiPrefs().getBoolean(
            AppPreferences.KEY_SYNC_NOTIFICATIONS,
            AppPreferences.DEFAULT_SYNC_NOTIFICATIONS
        ) && notificationAllowed
        suppressSettingCallbacks = false
    }

    private fun applyScreenshotProtection() {
        val enabled = uiPrefs().getBoolean(
            AppPreferences.KEY_SCREENSHOT_PROTECTION,
            AppPreferences.DEFAULT_SCREENSHOT_PROTECTION
        )
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = uiPrefs()
        val enabled = prefs.getBoolean(
            AppPreferences.KEY_SYNC_NOTIFICATIONS,
            AppPreferences.DEFAULT_SYNC_NOTIFICATIONS
        )
        val asked = prefs.getBoolean(AppPreferences.KEY_NOTIFICATION_PERMISSION_ASKED, false)
        if (enabled && !asked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateTabContent(position: Int) {
        binding.ledgerTabContent.visibility = if (position == 0) View.VISIBLE else View.GONE
        binding.insightsTabContent.visibility = if (position == 1) View.VISIBLE else View.GONE
        binding.budgetsTabContent.visibility = if (position == 2) View.VISIBLE else View.GONE
        binding.connectionTabContent.visibility = if (position == 3) View.VISIBLE else View.GONE
    }

    private fun togglePrivacyMode() {
        if (privacyMode && isBiometricEnabled()) {
            requestBiometric(BiometricAction.REVEAL)
            return
        }
        privacyMode = !privacyMode
        savePrivacyMode()
        updatePrivacyUi()
        renderAll()
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
        budgetAdapter.setPrivacyMode(privacyMode)
        subscriptionAdapter.setPrivacyMode(privacyMode)
    }

    private fun uiPrefs() = getSharedPreferences(AppPreferences.UI_PREFS, Context.MODE_PRIVATE)
    private fun isBiometricEnabled() = uiPrefs().getBoolean(AppPreferences.KEY_BIOMETRIC, false)
    private fun isAutoPrivacyEnabled() = uiPrefs().getBoolean(
        AppPreferences.KEY_AUTO_PRIVACY,
        AppPreferences.DEFAULT_AUTO_PRIVACY
    )
    private fun savePrivacyMode() {
        uiPrefs().edit().putBoolean(AppPreferences.KEY_PRIVACY_MODE, privacyMode).apply()
    }

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
            binding.accountStatus.text = privacyAware("Connected to $email")
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
            .addOnFailureListener { e -> showError("Could not request Gmail access", e) }
    }

    private fun performSync(accessToken: String, fullRescan: Boolean) {
        setBusy(true, if (fullRescan) "Scanning all matching ICICI emails…" else "Scanning recent ICICI emails…")
        syncProgress = 0f
        updateLandscapes(syncing = true)
        executor.execute {
            try {
                val result = repository.sync(accessToken, fullRescan) { progress ->
                    runOnUiThread {
                        syncProgress = if (progress.total == 0) 1f else progress.processed.toFloat() / progress.total
                        val progressText = "Painting the ledger: ${progress.processed} of ${progress.total} emails…"
                        binding.syncStatus.text = privacyAware(progressText)
                        showMainSyncStatus(progressText)
                        updateLandscapes(syncing = true)
                    }
                }
                val prefs = getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean(EmailSyncWorker.KEY_INITIAL_SYNC_DONE, true)
                    .putLong(EmailSyncWorker.KEY_LAST_SYNC, System.currentTimeMillis())
                    .putString(EmailSyncWorker.KEY_AUTO_SYNC_STATUS, "Automatic sync is scheduled in the background")
                    .apply()
                result.accountEmail?.let { prefs.edit().putString(EmailSyncWorker.KEY_ACCOUNT_EMAIL, it).apply() }
                EmailSyncWorker.schedule(this)

                runOnUiThread {
                    syncProgress = -1f
                    calmMessageOverride = "Your ledger is up to date. Everything is right where it belongs."
                    restoreStatus()
                    setBusy(
                        false,
                        "Added ${result.added}; already recorded ${result.alreadyPresent}; unmatched ${result.notParsed}."
                    )
                    reloadAllData()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    syncProgress = -1f
                    showError("Email sync failed", e)
                    updateLandscapes(syncing = false)
                }
            }
        }
    }

    private fun reloadAllData() {
        if (!::db.isInitialized) return
        executor.execute {
            val transactions = InrEstimator.enrich(db.getAllTransactions())
            val budgets = db.getBudgets()
            runOnUiThread {
                allTransactions = transactions
                currentBudgets = budgets
                renderAll()
            }
        }
    }

    private fun renderAll() {
        renderStatement()
        renderInsights()
        renderBudgets()
        restoreStatus()
    }

    private fun renderStatement() {
        if (!::binding.isInitialized) return
        val range = selectedPeriod().range()
        val transactions = allTransactions.filter {
            it.transactionEpochMillis >= range.first && it.transactionEpochMillis < range.second
        }
        currentTransactions = transactions
        val originalTotals = originalCurrencyTotals(transactions)
        val estimatedTotal = transactions.mapNotNull { it.inrAmount?.toBigDecimalOrNull() }
            .fold(BigDecimal.ZERO) { total, value -> total.add(value) }
        val coveredCount = transactions.count { it.inrAmount != null }
        val breakdown = buildBreakdown(selectedPeriod(), transactions)

        transactionAdapter.submitList(transactions)
        binding.emptyView.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
        binding.transactionList.visibility = if (transactions.isEmpty()) View.GONE else View.VISIBLE
        binding.countValue.text = privacyAware("${transactions.size} ${if (transactions.size == 1) "transaction" else "transactions"}")
        binding.totalValue.text = privacyAware(
            if (transactions.isEmpty() || coveredCount == 0) "—" else TransactionAdapter.formatInr(estimatedTotal.toPlainString())
        )
        binding.coverageValue.text = privacyAware(
            when {
                transactions.isEmpty() -> "No transactions in this period"
                coveredCount == transactions.size -> "All ${transactions.size} transactions included in the INR total"
                else -> "$coveredCount of ${transactions.size} transactions included in the INR total"
            }
        )
        binding.originalTotalsValue.text = privacyAware(
            if (originalTotals.isEmpty()) "—" else originalTotals.entries.joinToString("  •  ") {
                TransactionAdapter.formatMoney(it.key, it.value.toPlainString())
            }
        )
        binding.breakdownValue.text = privacyAware(breakdown.ifBlank { "—" })
    }

    private fun renderInsights() {
        val insights = InsightsCalculator.calculate(allTransactions)
        binding.insightMonthTotal.text = privacyAware(TransactionAdapter.formatInr(insights.thisMonthTotal.toPlainString()))
        binding.insightMonthChange.text = privacyAware(
            insights.monthChangePercent?.let {
                val direction = if (it >= BigDecimal.ZERO) "higher" else "lower"
                "${it.abs().stripTrailingZeros().toPlainString()}% $direction than last month"
            } ?: "A comparison appears after last month has recorded spending"
        )
        binding.insightAverageDaily.text = privacyAware(
            "Average per day: ${TransactionAdapter.formatInr(insights.averageDailySpend.toPlainString())}"
        )
        binding.insightTopMerchant.text = privacyAware(
            insights.topMerchant?.let { "Top merchant: ${it.first}  •  ${TransactionAdapter.formatInr(it.second.toPlainString())}" }
                ?: "Top merchant: —"
        )
        binding.insightTopCategory.text = privacyAware(
            insights.topCategory?.let { "Top category: ${it.first.emoji} ${it.first.label}  •  ${TransactionAdapter.formatInr(it.second.toPlainString())}" }
                ?: "Top category: —"
        )
        binding.insightInternational.text = privacyAware(
            "International spending: ${TransactionAdapter.formatInr(insights.internationalSpend.toPlainString())}"
        )
        binding.insightHighestDay.text = privacyAware(
            insights.highestSpendingDay?.let {
                "Highest-spending day: ${it.date.format(DateTimeFormatter.ofPattern("dd MMM"))}  •  ${TransactionAdapter.formatInr(it.amount.toPlainString())}"
            } ?: "Highest-spending day: —"
        )
        binding.insightWeekdayWeekend.text = privacyAware(
            "Weekdays ${TransactionAdapter.formatInr(insights.weekdaySpend.toPlainString())}  •  Weekends ${TransactionAdapter.formatInr(insights.weekendSpend.toPlainString())}"
        )

        binding.insightCategoryContainer.removeAllViews()
        val maxCategory = insights.categoryTotals.values.maxOrNull()?.max(BigDecimal.ONE) ?: BigDecimal.ONE
        insights.categoryTotals.forEach { (category, amount) ->
            val row = ItemInsightBarBinding.inflate(LayoutInflater.from(this), binding.insightCategoryContainer, false)
            row.insightCategory.text = "${category.emoji}  ${category.label}"
            row.insightAmount.text = privacyAware(TransactionAdapter.formatInr(amount.toPlainString()))
            row.insightProgress.progress = amount.multiply(BigDecimal("100"))
                .divide(maxCategory, 0, RoundingMode.HALF_UP).toInt().coerceIn(0, 100)
            binding.insightCategoryContainer.addView(row.root)
        }
        if (insights.categoryTotals.isEmpty()) {
            val empty = android.widget.TextView(this).apply {
                text = "No categorized transactions this month yet."
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 14f
                setPadding(0, 12, 0, 12)
            }
            binding.insightCategoryContainer.addView(empty)
        }

        subscriptionAdapter.submitList(insights.subscriptions)
        binding.subscriptionsEmpty.visibility = if (insights.subscriptions.isEmpty()) View.VISIBLE else View.GONE
        binding.subscriptionList.visibility = if (insights.subscriptions.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderBudgets() {
        val zone = ZoneId.of("Asia/Kolkata")
        val thisMonth = YearMonth.now(zone)
        val monthTransactions = allTransactions.filter {
            YearMonth.from(Instant.ofEpochMilli(it.transactionEpochMillis).atZone(zone)) == thisMonth
        }
        val spends = monthTransactions.groupBy { it.category }.mapValues { (_, items) ->
            items.mapNotNull { it.inrAmount?.toBigDecimalOrNull() }.fold(BigDecimal.ZERO) { total, value -> total.add(value) }
        }
        val rows = SpendingCategory.entries.map { category ->
            BudgetAdapter.BudgetRow(category, spends[category] ?: BigDecimal.ZERO, currentBudgets[category])
        }
        budgetAdapter.submitList(rows)

        val totalBudget = currentBudgets.values.fold(BigDecimal.ZERO) { total, value -> total.add(value) }
        val spentAgainstBudgets = currentBudgets.keys.fold(BigDecimal.ZERO) { total, category ->
            total + (spends[category] ?: BigDecimal.ZERO)
        }
        budgetProgress = if (totalBudget > BigDecimal.ZERO) {
            spentAgainstBudgets.divide(totalBudget, 4, RoundingMode.HALF_UP).toFloat()
        } else 0f

        val insights = InsightsCalculator.calculate(allTransactions)
        yearProgress = if (totalBudget > BigDecimal.ZERO) {
            insights.yearTotal.divide(totalBudget.multiply(BigDecimal("12")), 4, RoundingMode.HALF_UP).toFloat()
        } else {
            (insights.yearTotal.divide(BigDecimal("100000"), 4, RoundingMode.HALF_UP)).toFloat().coerceAtMost(1.4f)
        }
        binding.budgetSummary.text = privacyAware(
            when {
                totalBudget.compareTo(BigDecimal.ZERO) == 0 -> "Tap a category to plant your first monthly budget."
                budgetProgress < 0.60f -> "${TransactionAdapter.formatInr(spentAgainstBudgets.toPlainString())} of ${TransactionAdapter.formatInr(totalBudget.toPlainString())}. Plenty of evergreen room remains."
                budgetProgress < 0.85f -> "${TransactionAdapter.formatInr(spentAgainstBudgets.toPlainString())} of ${TransactionAdapter.formatInr(totalBudget.toPlainString())}. A few clouds are gathering, but the trail is comfortable."
                budgetProgress <= 1f -> "${TransactionAdapter.formatInr(spentAgainstBudgets.toPlainString())} of ${TransactionAdapter.formatInr(totalBudget.toPlainString())}. The sunset is warm; spend thoughtfully."
                else -> "${TransactionAdapter.formatInr(spentAgainstBudgets.toPlainString())} of ${TransactionAdapter.formatInr(totalBudget.toPlainString())}. A little rain arrived—budgets can always be replanted."
            }
        )
        binding.calmMessage.text = privacyAware(calmMessageOverride ?: buildCalmMessage(insights.yearTotal))
        updateLandscapes(syncing = busy)
    }

    private fun buildCalmMessage(yearTotal: BigDecimal): String {
        val milestone = when {
            yearTotal >= BigDecimal("100000") -> "You crossed the ₹1,00,000 mountain this year."
            yearTotal >= BigDecimal("50000") -> "You crossed the ₹50,000 mountain this year."
            yearTotal >= BigDecimal("25000") -> "You crossed the ₹25,000 ridge this year."
            yearTotal >= BigDecimal("10000") -> "Your first ₹10,000 hill is now part of the landscape."
            else -> "Every transaction is simply another brushstroke in the bigger picture."
        }
        return milestone
    }

    private fun updateLandscapes(syncing: Boolean) {
        val month = LocalDate.now(ZoneId.of("Asia/Kolkata")).monthValue
        binding.landscapeView.setState(month, budgetProgress, yearProgress, syncProgress, syncing)
        binding.budgetLandscapeView.setState(month, budgetProgress, yearProgress, -1f, false)
    }

    private fun showCategoryDialog(transaction: CardTransaction) {
        val categories = SpendingCategory.entries
        val labels = categories.map { "${it.emoji}  ${it.label}" }.toTypedArray()
        val selected = categories.indexOf(transaction.category).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Categorize ${privacyAware(transaction.merchant)}")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val category = categories[which]
                executor.execute {
                    db.setMerchantCategory(transaction.merchant, category)
                    runOnUiThread {
                        dialog.dismiss()
                        toast("${transaction.merchant} will now use ${category.label}")
                        reloadAllData()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBudgetDialog(row: BudgetAdapter.BudgetRow) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "Monthly amount in INR"
            setText(row.limit?.stripTrailingZeros()?.toPlainString().orEmpty())
            setSelection(text.length)
            setPadding(24, 12, 24, 12)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${row.category.emoji} ${row.category.label} budget")
            .setMessage("Set a monthly limit. Leave it blank or use Remove to delete the budget.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove") { _, _ ->
                executor.execute {
                    db.setBudget(row.category, null)
                    runOnUiThread { reloadAllData() }
                }
            }
            .setPositiveButton("Save", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim().toBigDecimalOrNull()
                if (value == null || value <= BigDecimal.ZERO) {
                    input.error = "Enter a positive amount"
                } else {
                    executor.execute {
                        db.setBudget(row.category, value)
                        runOnUiThread {
                            dialog.dismiss()
                            reloadAllData()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun originalCurrencyTotals(transactions: List<CardTransaction>): LinkedHashMap<String, BigDecimal> {
        val totals = linkedMapOf<String, BigDecimal>()
        transactions.forEach { tx ->
            totals[tx.currency] = totals.getOrDefault(tx.currency, BigDecimal.ZERO).add(BigDecimal(tx.amount))
        }
        return totals.entries
            .sortedWith(compareBy<Map.Entry<String, BigDecimal>> { if (it.key == "INR") 0 else 1 }.thenBy { it.key })
            .associateTo(linkedMapOf()) { it.key to it.value }
    }

    private fun buildBreakdown(period: StatementPeriod, transactions: List<CardTransaction>): String {
        if (transactions.isEmpty()) return ""
        val zone = ZoneId.of("Asia/Kolkata")
        val useYears = period == StatementPeriod.ALL_TIME
        val groups = linkedMapOf<String, MutableList<CardTransaction>>()
        transactions.forEach { tx ->
            val date = Instant.ofEpochMilli(tx.transactionEpochMillis).atZone(zone)
            val key = if (useYears) Year.from(date).toString() else
                YearMonth.from(date).format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.ENGLISH))
            groups.getOrPut(key) { mutableListOf() }.add(tx)
        }
        return groups.entries.joinToString("\n\n") { (label, items) ->
            val estimated = items.mapNotNull { it.inrAmount?.toBigDecimalOrNull() }.fold(BigDecimal.ZERO) { total, value -> total.add(value) }
            val coverage = items.count { it.inrAmount != null }
            val originals = originalCurrencyTotals(items).entries.joinToString("  •  ") { (currency, amount) ->
                TransactionAdapter.formatMoney(currency, amount.toPlainString())
            }
            buildString {
                append(label).append("\n")
                if (coverage > 0) append(TransactionAdapter.formatInr(estimated.toPlainString())).append(" estimated INR")
                else append("INR estimate unavailable")
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
        updateLandscapes(syncing = isBusy)
    }

    private fun showMainSyncStatus(message: String) {
        binding.mainSyncStatus.visibility = View.VISIBLE
        binding.mainSyncStatus.text = privacyAware(message)
    }

    private fun confirmClearData() {
        AlertDialog.Builder(this)
            .setTitle("Clear local ledger?")
            .setMessage("This deletes parsed transactions but keeps your category rules and budgets. It does not delete Gmail messages.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Clear") { _, _ ->
                executor.execute {
                    db.clearTransactions()
                    getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .remove(EmailSyncWorker.KEY_INITIAL_SYNC_DONE)
                        .remove(EmailSyncWorker.KEY_LAST_SYNC)
                        .remove(EmailSyncWorker.KEY_AUTO_SYNC_STATUS)
                        .apply()
                    EmailSyncWorker.cancel(this)
                    runOnUiThread {
                        binding.mainSyncStatus.visibility = View.GONE
                        restoreStatus()
                        reloadAllData()
                    }
                }
            }
            .show()
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect Gmail?")
            .setMessage("This revokes Gmail permission and erases the local ledger, budgets, and category rules. It does not delete Gmail messages.")
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
            db.clearEverything()
            getSharedPreferences(EmailSyncWorker.PREFS, Context.MODE_PRIVATE).edit().clear().apply()
            EmailSyncWorker.cancel(this)
            runOnUiThread {
                binding.mainSyncStatus.visibility = View.GONE
                setBusy(false, "Gmail disconnected and local ledger erased.")
                restoreStatus()
                reloadAllData()
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

    private fun csvEscape(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    private fun privacyAware(text: String): String = if (privacyMode) text.replace(Regex("\\d"), "•") else text
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        statusHandler.removeCallbacks(statusRefresh)
        executor.shutdown()
        db.close()
        super.onDestroy()
    }

    private enum class BiometricAction { UNLOCK, REVEAL, ENABLE }

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
                THIS_MONTH -> { startDate = today.withDayOfMonth(1); endDate = startDate.plusMonths(1) }
                LAST_MONTH -> { endDate = today.withDayOfMonth(1); startDate = endDate.minusMonths(1) }
                LAST_THREE_MONTHS -> { endDate = today.plusDays(1); startDate = today.minusMonths(3) }
                LAST_SIX_MONTHS -> { endDate = today.plusDays(1); startDate = today.minusMonths(6) }
                LAST_TWELVE_MONTHS -> { endDate = today.plusDays(1); startDate = today.minusMonths(12) }
                THIS_YEAR -> { startDate = today.withDayOfYear(1); endDate = startDate.plusYears(1) }
                LAST_YEAR -> { endDate = today.withDayOfYear(1); startDate = endDate.minusYears(1) }
                ALL_TIME -> return 0L to Long.MAX_VALUE
            }
            return startDate.atStartOfDay(zone).toInstant().toEpochMilli() to
                endDate.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    }
}
