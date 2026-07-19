# Card Ledger 1.2.0

- Fixes ICICI transaction times whose email body omits AM/PM.
- Resolves the time against Gmail's received timestamp.
- Corrects already-recorded v1.1 transactions at read time; no data clearing or Gmail rescan is required.
- Restores available-limit INR estimation when a later afternoon transaction had previously been misordered as morning.
