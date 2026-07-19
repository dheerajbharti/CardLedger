# Card Ledger 1.4 update checklist

1. Back up `E:\Credit_Card_App\CardLedgerAndroid`.
2. Extract the 1.4 update ZIP into the existing project and replace matching files.
3. Keep the existing `local.properties`, debug keystore, and Gradle wrapper JAR.
4. Run `gradlew.bat clean testDebugUnitTest assembleDebug`.
5. Confirm `app\build\outputs\apk\debug\app-debug.apk` exists.
6. Confirm the phone appears in `adb devices` as `device`.
7. Install using `adb install -r` without uninstalling the existing app.
8. Grant notification permission on first launch if you want background-sync alerts.
9. Open Connection → Privacy & notifications to enable biometric protection or adjust screenshot protection.
10. Tap transactions to correct categories; open Budgets to set monthly limits.
