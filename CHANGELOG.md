# Changelog

## 1.4.1

- Fixed category-classification precedence for otherwise-unknown foreign-currency merchants containing generic words such as SHOP or STORE.
- Preserved meaningful categories for recognized foreign merchants such as OpenAI and Amazon.
- Added regression tests for foreign fallback and recognized retail brands.


## 1.4.0

- Added automatic transaction categories and merchant-specific category rules.
- Added editable categories by tapping transaction cards.
- Added monthly category budgets and budget progress tracking.
- Added recurring-subscription detection and annualized subscription estimates.
- Added a full Insights tab.
- Added biometric/device-credential app protection.
- Added automatic privacy mode when leaving the app.
- Added optional screenshot and screen-recording protection using secure-window mode.
- Added Android 13+ notification permission handling.
- Added notifications when background sync imports new transactions.
- Added non-destructive SQLite migration from the 1.3 database.
- Added seasonal landscape palettes, moving sync clouds, paintbrush progress, evergreen budget trees, spending mountains, and milestone messages.
- CSV export now includes category.

## 1.3.0

- Split the UI into Ledger and Connection tabs.
- Added the privacy eye button.
- Added the landscape-inspired theme and launcher icon.

## 1.2.0

- Fixed ambiguous AM/PM handling using Gmail received timestamps.

## 1.1.0

- Added estimated INR totals, statement breakdowns, light high-contrast UI, CSV enhancements, and approximately six-hour automatic sync.
