# Deploy Card Ledger 1.4 over an existing installation

## 1. Back up the current project

```powershell
Copy-Item `
  "E:\Credit_Card_App\CardLedgerAndroid" `
  "E:\Credit_Card_App\CardLedgerAndroid-backup-v1.3" `
  -Recurse
```

## 2. Apply the update patch

Extract `CardLedgerAndroid-v1.4-update.zip`. Copy the contents of the extracted `CardLedgerAndroid-v1.4-update` folder into:

```text
E:\Credit_Card_App\CardLedgerAndroid
```

Choose **Replace the files in the destination**.

The patch does not replace `local.properties`, the Gradle wrapper JAR, or the debug keystore.

## 3. Build

```powershell
cd E:\Credit_Card_App\CardLedgerAndroid

$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

.\gradlew.bat --stop
.\gradlew.bat clean testDebugUnitTest assembleDebug
```

Expected APK:

```text
E:\Credit_Card_App\CardLedgerAndroid\app\build\outputs\apk\debug\app-debug.apk
```

## 4. Connect the phone

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
```

The phone must appear with status `device`.

## 5. Install as an update

```powershell
& $adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
```

Do not uninstall the existing app. The `-r` update preserves the existing local database and Gmail authorization state.

## 6. First launch after update

- Android may request notification permission. Grant it to receive background-sync updates.
- Screenshot protection is enabled by default. Change it in the Connection tab if required.
- Biometric protection is optional and must be enabled in the Connection tab.
- Existing transactions are automatically assigned categories during the database migration.
- Tap any transaction card to correct its category. The app remembers that merchant rule.
- Open Budgets and tap a category to set a monthly limit.
