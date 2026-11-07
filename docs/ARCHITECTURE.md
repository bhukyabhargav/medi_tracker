# MediTrack – System Architecture (Stages 0–14)

## 1. Package Structure (Package-by-Feature)
```
com.meditrack.app/
├── core/
│   ├── config/      # AppConfig.kt & VerificationConfig.kt (tunable constants)
│   ├── di/          # Dagger-Hilt modules and dependency injection
│   ├── navigation/  # Screen definitions and navigation graphs
│   ├── security/    # KeystoreSecurityManager (hardware-backed AES-256 GCM) & DatabaseKeyManager
│   ├── time/        # TimeProvider abstraction for deterministic unit testing
│   ├── ui/theme/    # Material 3 theme (Light & Dark), Typography, Colors
│   └── util/        # Shared core utilities (JsonPayload, LOG_TAG, etc.)
├── data/
│   ├── db/          # Encrypted Room database (v4 with SQLCipher), entities, DAOs, migrators
│   ├── prefs/       # DataStore preference managers (UserPreferences)
│   ├── service/     # AccountCloudDeleter & Cloud Function caller
│   └── repository/  # Repository implementations (offline-first Room source of truth)
├── domain/
│   ├── model/       # Domain models, enums (DoseStatus, RejectReason, NotifType)
│   ├── statemachine/# DoseStateMachine and ProposalStateMachine
│   └── usecase/     # Pure Kotlin business logic (RecordVerifiedDose, AdherenceCalculator, ExportData, DeleteAccount, etc.)
└── feature/
    ├── auth/        # Login, create account, OTP verification (Firebase + Fake)
    ├── onboarding/  # First-run walkthrough, DPDP consent, permission setup, battery guidance
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
2. **Encrypted at Rest (SQLCipher + Keystore)**:
   - Database is encrypted using SQLCipher for Android (`net.zetetic:sqlcipher-android`) with `SupportOpenHelperFactory`.
   - Passphrase is a cryptographically random 32-byte key generated once and wrapped with hardware-backed Android Keystore AES-256 GCM (`MediTrackMasterKey`).
   - Legacy plaintext databases are migrated seamlessly via `DatabaseEncryptionMigrator.migrateIfPlaintext`.
3. **Deterministic & Platform-Independent Business Logic**: `DoseStateMachine`, `ProposalStateMachine`, `AdherenceCalculator`, `LateEntryPolicy`, `ImageQualityGate`, and `PrescriptionParser` reside in pure Kotlin with zero Android platform imports.
4. **Pill Ledger Invariant & Vision Guard**:
   - Verification strictly evaluates `newEmpty - previousEmpty == pillsPerDose`. A dose transitions to `TAKEN` only upon photographic verification.
   - Conservative runtime guards reject captures if strip confidence $< 0.50$, cell confidence $< 0.60$, or total detected cell count diverges from baseline by $> 1$ cell.
5. **Doctor Mode Governance ("Doctor Proposes, Patient Decides")**:
   - A doctor cannot directly write to a patient's active medication or dose ledger.
   - Proposals (`ADD`, `EDIT`, `STOP`) remain `PENDING` until the patient explicitly accepts them on their device.
   - Revisions transition old proposals from `CLARIFICATION_REQUESTED` to `SUPERSEDED` with explicit revision numbering and `supersedesProposalId` reference.
6. **Data Sovereignty & Cloud-Governed Account Deletion**:
   - Consent-First Flow: First launch requires DPDP Act 2023 consent before account creation or local/cloud data persistence.
   - Account deletion triggers Admin SDK callable Cloud Function `deleteAccountData`, cascade-purging user documents, subcollections, links, proposals, Storage photos, and Firebase Auth records, with structured reporting and retry on failure.
7. **On-Device Machine Learning (Stage 13)**:
   - `TfliteStripDetector` executes on-device TFLite inference (`strip_detector.tflite`, 12.0 MB Float32 flatbuffer).
   - Detects `strip`, `cell_full`, and `cell_empty`. Note: `cell_partial` is not trained in the public dataset and is treated conservatively as unknown/needs retake.
   - Quality bar achieved on validation split: EMPTY precision $0.982$, strip recall $1.000$, mAP50 $0.979$, host CPU inference latency $11.9\text{ ms}$.
8. **Release Hardening (Stage 14)**:
   - ProGuard/R8 bytecode obfuscation (`isMinifyEnabled = true`) and resource shrinking (`isShrinkResources = true`).
   - Release signing template decoupled into environment variables and `local.properties`.
   - Strict permission boundary: zero location permissions, zero external storage bloat.
