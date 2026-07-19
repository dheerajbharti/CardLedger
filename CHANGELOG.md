# Changelog

## 1.3.0

- Split the UI into two tabs: **Ledger** and **Connection**.
- Kept **Sync now** on the main Ledger tab for quick access.
- Added a top-right privacy eye button to hide or reveal digits across totals, breakdowns, and transaction cards.
- Refreshed the visual theme with a serene landscape-inspired palette reminiscent of peaceful mountain paintings.
- Updated the launcher icon to a landscape-inspired finance icon.
- Preserved background auto-sync and manual sync behavior.
- Continued support for estimated INR totals and foreign-currency INR estimation.

## 1.2.0

- Fixed transaction ordering for ICICI alerts whose body time omits AM/PM.
- Added time disambiguation using Gmail received time.
- Corrected foreign-currency INR estimation when new transactions arrive on the same day.

## 1.1.0

- Forced a light, high-contrast visual theme.
- Added lively indigo, teal, and orange visual accents.
- Enlarged and vertically separated controls.
- Fixed status-bar/title overlap with system window insets.
- Made statement breakdown prominent and readable.
- Added exact/estimated INR debit values.
- Added estimated INR totals and coverage reporting.
- Added original-currency totals alongside INR totals.
- Added INR fields to CSV export.
- Changed periodic background sync from 12 hours to approximately 6 hours.
- Added stale-on-launch catch-up sync and UI refresh polling.
- Added unit tests for INR estimation.
