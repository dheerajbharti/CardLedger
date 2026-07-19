# Card Ledger 1.4 for Android

Card Ledger is a private Android spending tracker that reads ICICI credit-card transaction alerts through read-only Gmail authorization, parses transaction fields, and stores the resulting ledger locally on the phone.

## Version 1.4 highlights

- Automatic merchant categories with remembered merchant corrections.
- Monthly category budgets with progress, remaining amount, and overspend status.
- Recurring-subscription detection from repeated monthly payment patterns.
- Insights for monthly total, comparison with last month, average daily spend, top merchant/category, international spending, highest-spending day, and weekday/weekend split.
- Biometric or device-lock protection.
- Automatic digit hiding whenever the app leaves the foreground.
- Optional screenshot and screen-recording protection.
- Notifications when background auto-sync adds transactions.
- Four tabs: Ledger, Insights, Budgets, and Connection.
- Seasonal landscape visuals, moving clouds during sync, a paintbrush sync stroke, budget trees, spending mountains, and calm milestone messages.
- Existing 6-hour WorkManager auto-sync, manual Sync Now, CSV export, duplicate prevention, and foreign-currency INR estimation are retained.

## Data and privacy

The app does not store Gmail passwords, OTPs, CVVs, full card numbers, or full email bodies. It stores only parsed transaction data in the app-private SQLite database. Gmail access uses the `gmail.readonly` OAuth scope.

Screenshot protection is enabled by default in 1.4. It can be changed under **Connection → Privacy & notifications**.

## Updating from 1.3

Use the 1.4 update patch and install the rebuilt APK with `adb install -r`. Do not uninstall the existing app first; uninstalling removes the local database.

Detailed instructions are in `DEPLOY_V1.4.md`.

## Build requirements

- Android Studio with Android SDK Platform 35
- Build Tools 35.x
- JDK 17 or newer; Android Studio's bundled JBR 21 is supported
- Gradle 8.9

## Gradle wrapper bootstrap

Run:

```powershell
powershell -ExecutionPolicy Bypass -File .\bootstrap-wrapper.ps1
```

The verified Gradle 8.9 wrapper JAR SHA-256 is:

```text
498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17
```

## Important limitation

Card Ledger remains an email-derived personal ledger, not the legally authoritative ICICI statement. Repayments, refunds, reversals, fees, missing alerts, and shared credit-limit activity can affect foreign-currency INR estimates. Reconcile important totals with the official bank statement.
