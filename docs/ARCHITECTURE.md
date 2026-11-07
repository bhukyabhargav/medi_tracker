# MediTrack – System Architecture (Stages 0–14)

## 1. Package Structure (Package-by-Feature)
```
com.meditrack.app/
├── core/
│   ├── config/      # AppConfig.kt (single source of truth for tunable constants) & VerificationConfig.kt
│   ├── di/          # Dagger-Hilt modules and dependency injection
│   ├── navigation/  # Screen definitions and navigation graphs
│   ├── security/    # KeystoreSecurityManager (hardware-backed AES-256 GCM)
│   ├── time/        # TimeProvider abstraction for deterministic unit testing
│   ├── ui/theme/    # Material 3 theme (Light & Dark), Typography, Colors
│   └── util/        # Shared core utilities (JsonPayload, LOG_TAG, etc.)
├── data/
│   ├── db/          # Room database (v3), entities, DAOs, and type converters
│   ├── prefs/       # DataStore preference managers (UserPreferences)
│   └── repository/  # Repository implementations (offline-first Room source of truth)
├── domain/
│   ├── model/       # Domain models, enums (DoseStatus, RejectReason, NotifType)
│   ├── statemachine/# DoseStateMachine and ProposalStateMachine
│   └── usecase/     # Pure Kotlin business logic (RecordVerifiedDose, AdherenceCalculator, ExportData, DeleteAccount, etc.)
└── feature/
    ├── auth/        # Login, create account, OTP verification (Firebase + Fake)
    ├── onboarding/  # First-run walkthrough, consent, permission setup, battery guidance
    ├── home/        # Home dashboard (Today's medicines, upcoming doses, stock alerts, quick actions)
    ├── medicine/    # Add/Edit medicine form, enrollment photo, schedule generation, refill
    ├── reminder/    # Alarms, notifications, snooze, skip, missed dose engine, daily extension
    ├── verification/# Camera capture, image quality gate, TfliteStripDetector, ledger compare
    ├── history/     # Medication logs, status chips, adherence summary
    ├── calendar/    # Monthly calendar view with status dots
    ├── stock/       # Stock tracking, low-stock triggers, refill
    ├── caregiver/   # Caregiver link, invitation, caregiver cloud monitoring
    ├── doctor/      # Doctor Mode, patient adherence review, prescription proposals
    ├── scan/        # ML Kit prescription scanner + pure-Kotlin clinical parser + prefill
    ├── settings/    # Profile, privacy consent, data export, account deletion, screen security
    └── debug/       # Debug Tools screen (debug builds only)
```

---

## 2. Key Architectural Principles

1. **Offline-First Data Flow**: Room DB is the single source of truth. UI observes reactive Kotlin Flows from Room DAOs. WorkManager synchronizes changes when connectivity is available.
2. **Deterministic & Platform-Independent Business Logic**: `DoseStateMachine`, `ProposalStateMachine`, `AdherenceCalculator`, `LateEntryPolicy`, `ImageQualityGate`, and `PrescriptionParser` reside in pure Kotlin with zero Android platform imports.
3. **Pill Ledger Invariant**: Verification strictly evaluates `newEmpty - previousEmpty == pillsPerDose`. A dose transitions to `TAKEN` only upon photographic verification.
4. **Doctor Mode Governance ("Doctor Proposes, Patient Decides")**:
   - A doctor cannot write to a patient's active medication or dose ledger.
   - Proposals (`ADD`, `EDIT`, `STOP`) remain `PENDING` until the patient explicitly accepts them on their device.
5. **Security & Cryptography**:
   - Sensitive archives and tokens use hardware-backed Android Keystore AES-256 GCM (`MediTrackMasterKey`).
   - Blister photos are stored strictly in app-private storage (`context.filesDir`, mode `MODE_PRIVATE`).
   - Sensitive verification screens enforce screenshot prevention (`FLAG_SECURE`).
6. **On-Device Machine Learning (Stage 13)**:
   - `TfliteStripDetector` executes on-device TFLite inference (`strip_detector.tflite`).
   - Detects `strip` (boundary checking for full frame margin), `cell_full`, `cell_empty`, and `cell_partial`.
   - Quality bar: EMPTY-class precision $\ge 0.98$ to prevent false intake recordings.
