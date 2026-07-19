# Validation performed for 1.4

- Parsed all Android XML resources successfully.
- Checked that every ViewBinding field referenced in Kotlin has a matching layout ID.
- Compiled the non-Android Kotlin data logic with Kotlin/JVM 2.x.
- Executed smoke tests for merchant classification, subscription detection, and insight totals.
- Added JUnit tests for category classification and recurring-subscription detection.

A complete Android APK was not built in the generation environment because it does not contain Android SDK Platform 35 or the project's downloaded Gradle dependencies. Run the documented Gradle build on the existing Windows/Android Studio setup before installing.
