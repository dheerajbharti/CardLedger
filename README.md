# Card Ledger for Android — version 1.1.0

Card Ledger is a private Android application that reads ICICI credit-card transaction-alert emails through Gmail read-only authorization, extracts transaction fields, and stores the ledger locally in the app's private database.

## Version 1.1 improvements

- Bright, light-only interface with high-contrast text and larger, full-width controls.
- Correct system-bar insets so the title no longer overlaps the phone status bar.
- Visible monthly/yearly statement breakdown.
- Automatic background scan approximately every 6 hours when a network is available.
- A silent catch-up scan when the app opens and the last scan is older than 30 minutes.
- Automatic screen refresh after a background scan updates the local database.
- Estimated INR debit for foreign-currency transactions using the change in ICICI's available INR credit limit.
- Estimated INR total for each selected statement period.
- Original-currency totals remain visible separately.
- CSV export now includes `estimated_inr_amount` and `inr_amount_source`.

## INR calculation

For an INR alert, the exact amount in the email is used.

For a foreign-currency alert, Card Ledger calculates:

```text
previous alert's available INR limit - current alert's available INR limit
```

Example from the supplied data:

```text
Previous available limit: ₹49,023.88
Current available limit:  ₹47,137.03
Estimated INR debit:       ₹1,886.85
Original transaction:      BRL 99.90
```

This is deliberately labelled an **estimate**. Repayments, refunds, fees, reversals, a missing alert, or activity on another card sharing the combined credit limit can affect the difference.

## Privacy

The app:

- Requests `https://www.googleapis.com/auth/gmail.readonly` only.
- Does not store your Gmail password, OTP, CVV, PIN, complete card number, or full email body.
- Stores only parsed transaction fields in the app-private database.
- Prevents duplicate entries using Gmail's message ID.
- Does not transmit your ledger to a separate server.

## Build prerequisites

- Android Studio with Android SDK Platform 35.
- Android SDK Build Tools 35.x.
- Android Studio's bundled JDK 17 or newer.
- Gradle 8.9 wrapper.

Create `local.properties` on Windows:

```properties
sdk.dir=C:/Users/Lenovo/AppData/Local/Android/Sdk
```

Install the verified Gradle wrapper:

```powershell
powershell -ExecutionPolicy Bypass -File .\bootstrap-wrapper.ps1
```

The official Gradle 8.9 wrapper JAR SHA-256 used by the script is:

```text
498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17
```

Build:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat clean assembleDebug
```

APK output:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Update an existing installation without losing data

Build version 1.1 using the same Windows debug keystore that signed version 1.0, then run:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

Because the package name and signing certificate are unchanged, Android updates the existing application and preserves its local ledger and Gmail connection.

## Google OAuth configuration

Android OAuth client values for this project:

```text
Package: com.dheerajbharti.cardledger
```

Register the SHA-1 produced by:

```powershell
.\gradlew.bat signingReport
```

Keep the Gmail account that receives ICICI alerts under **Google Auth Platform → Audience → Test users** while the OAuth project is in Testing.

## Background synchronization

After the first successful manual scan, Card Ledger schedules unique WorkManager background work approximately every 6 hours with a connected-network constraint. Android may defer the precise execution time because of Doze and battery optimization. On Xiaomi/HyperOS, granting **Autostart** and setting battery use to **No restrictions** improves reliability.

If Google requires interactive authorization again, the background worker records that reconnection is needed. Open the app and press **Reconnect Gmail**.

## Controls

- **Sync now** — scans recent ICICI transaction emails.
- **Reconnect Gmail** — renews Google authorization and performs a full scan.
- **Full rescan of Gmail** — checks all matching historical emails without duplicating existing records.
- **Export statement as CSV** — exports the selected period, including INR estimates.
- **Clear local transaction data** — deletes parsed local data only.
- **Disconnect Gmail & erase data** — revokes Gmail access, cancels auto-sync, and clears local data.

## Scope limitation

The parser currently targets ICICI purchase-alert wording containing:

```text
has been used for a transaction of
```

It does not yet classify refunds, reversals, bill payments, EMI conversion, card fees, cash withdrawals, or PDF statements. Treat Card Ledger as a personal spending tracker rather than the bank's legally authoritative statement.
