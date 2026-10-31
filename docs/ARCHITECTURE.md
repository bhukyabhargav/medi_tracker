# MediTrack – Architecture

## Package Structure (Package-by-Feature)
```
com.meditrack.app/
├── core/
│   ├── config/      # AppConfig.kt (single source of truth for all tunable constants)
│   ├── di/          # Dagger-Hilt modules and dependency injection
│   ├── navigation/  # Screen definitions and navigation graphs
│   ├── time/        # TimeProvider abstraction for deterministic unit testing
│   ├── ui/theme/    # Material 3 theme (Light & Dark), Typography, Colors
│   └── util/        # Shared core utilities and constants (LOG_TAG, etc.)
├── data/
│   ├── db/          # Room database, entities, DAOs, and type converters
│   ├── prefs/       # DataStore preference managers
│   └── repository/  # Repository implementations (offline-first Room source of truth)
├── domain/
│   ├── model/       # Domain models, enums (DoseStatus, RejectReason, etc.)
│   ├── statemachine/# DoseStateMachine and ProposalStateMachine
│   └── usecase/     # Business logic use cases (RecordVerifiedDose, AdherenceCalculator, etc.)
└── feature/
    ├── auth/        # Login, create account, OTP verification
    ├── onboarding/  # First-run walkthrough, consent, permission setup
    ├── home/        # Home dashboard (Today's medicines, upcoming doses, quick actions)
    ├── medicine/    # Add medicine form, enrollment photo, medicine management
    ├── reminder/    # Alarms, notifications, snooze, skip, missed engine
    ├── verification/# Camera capture, image quality gate, cell detector, ledger compare
    ├── history/     # Medication logs, status chips, adherence summary
    ├── calendar/    # Monthly calendar view with status dots
    ├── stock/       # Stock tracking, low-stock triggers, refill
    ├── caregiver/   # Caregiver link, invitation, caregiver monitoring view
    ├── scan/        # Medicine / prescription scanner + OCR prefill
    ├── settings/    # Profile, privacy consent, data export/deletion
    └── debug/       # Debug Tools screen (debug builds only)
```

## Key Architectural Principles
1. **Offline-First:** Room DB is the single source of truth. UI observes StateFlow from Room. Network sync is handled in the background by WorkManager.
2. **Deterministic & Unit-Testable:** All business logic (DoseStateMachine, AdherenceCalculator, LateEntryPolicy, ImageQualityGate) lives in pure Kotlin classes with zero Android platform imports.
3. **Ledger Rule:** Dose intake verification strictly evaluates whether `newEmpty - previousEmpty == pillsPerDose`. Dose status transitions to `TAKEN` only upon verified proof.
4. **Outbox Pattern:** Asynchronous external events (such as caregiver alerts) are written to `NotificationOutbox` table and delivered independently.
5. **Centralized Tunables:** All configurable thresholds and constants reside exclusively in `core/config/AppConfig.kt`.
