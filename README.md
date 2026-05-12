# TxTracker / Tally

A hobby project that serves my own personal use, or like-minded people who need something like this. Disclaimer: this project is completely vibecoded and doesn't follow any well-planned structure beyond my own experience as a programmer. I just needed an Android app to fix a personal problem, and I find it fun to document the process.

## Introduction

I've been recording expenses in an Excel sheet for years, and occasionally life gets in the way and I miss some transactions. At the same time, in recent years Malaysia has gone maybe 95% cashless - everything goes through card or phone, both of which generate a notification on the phone anyway. So I thought, I need an app on my Android phone to:

1. Read relevant notifications and record "Paid MYR XX.XX to 'Merchant'"
2. Auto-categorize transactions based on merchant and timing
3. Provide CSV export so my existing Excel sheet can still be updated

## Features

1. Passive transaction capture via `NotificationListenerService`
  - The core system of the app; captures notifications from all packages (a flag that can be toggled off, on by default)
  - When "capture all" is on, approving a pending transaction saves its package into a map
  - When "capture all" is off, only packages in the map have their notifications tracked
  - Ships with a default set of packages to track, covering Malaysian wallet and banking apps
  - Per-source approval tiers (`SourceTier`) so untrusted apps don't pollute the data

2. Heuristic + permissive parsers for amount / merchant / direction
  - Keyword matching is the main method for recording transactions
  - Some manual work is needed at the start to approve transactions, assign a category, and add a note to the merchant if needed
  - Once this manual setup is saved into the map, the logic should automate similar transactions in the future
  - **A lot of real-life testing is required**
  - Categorization rules, merchant normalization, and description mappings

3. Manual entry and edit sheets for cash / corrections
  - CSV export and full JSON backup / restore
  - Optional Google Drive cloud sync via `WorkManager`

4. Biometric app lock

## Tech stack

- **Kotlin** + **Jetpack Compose** (Material 3), `minSdk` 26 / `targetSdk` 34, JDK 17
- **MVVM** with `ViewModel` + `StateFlow`, **Hilt** for DI
- **Room** for persistence (schemas checked in under `app/schemas/`)
- **WorkManager** for background cloud sync
- **Google Sign-In** + **OkHttp** for the Drive REST API
- **kotlinx.serialization** (JSON) for backup files, **kotlinx.datetime** for time handling
- **AndroidX Biometric** for the app lock
- Tests: JUnit, Truth, Turbine, MockK, Compose UI test, Hilt testing

Exact versions live in `gradle/libs.versions.toml`.

## Project layout

```
app/src/main/java/cy/txtracker/
├── TxApp.kt                  # Application + WorkManager config
├── ui/                       # Compose screens, ViewModels, navigation
│   ├── home/                 # transaction list + summaries
│   ├── edit/  manual/        # edit + manual-entry bottom sheets
│   ├── onboarding/           # listener-grant flow
│   ├── lock/                 # biometric lock
│   ├── settings/             # categories, merchants, descriptions, sources, cloud
│   └── theme/  format/
├── service/                  # NotificationListenerService + ingestor
├── parsing/                  # heuristic + permissive notification parsers
├── domain/                   # categorization, description engines, time buckets
├── data/                     # Room entities, DAOs, repository, converters
├── cloud/                    # Google Drive client, sync worker, scheduler
├── export/                   # CSV exporter, JSON backup / importer
└── di/                       # Hilt modules
```

## Notes for contributors

- After installing, grant **Settings → Notifications → Notification access →
  TxTracker**.
- Room schemas are checked in (`app/schemas/`); bump the DB version and
  generate a new schema when changing entities.
- Set up your own Google Cloud Console project to use the cloud sync feature with the Drive API
