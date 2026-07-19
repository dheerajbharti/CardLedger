# Card Ledger 1.1 update checklist

1. Replace the old source folder with this version, or copy the changed files into the existing project.
2. Keep `C:\Users\Lenovo\.android\debug.keystore`; do not regenerate it.
3. Confirm `local.properties` contains:

   ```properties
   sdk.dir=C:/Users/Lenovo/AppData/Local/Android/Sdk
   ```

4. In PowerShell:

   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   .\gradlew.bat clean testDebugUnitTest assembleDebug
   ```

5. Connect the phone and verify:

   ```powershell
   $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
   & $adb devices
   ```

6. Update the installed app while preserving data:

   ```powershell
   & $adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
   ```

7. Open Card Ledger. Existing transactions should remain. The new UI will calculate INR estimates immediately from the stored available-limit values.
8. On HyperOS, enable Autostart and set Card Ledger battery use to No restrictions for more reliable six-hour background scans.
