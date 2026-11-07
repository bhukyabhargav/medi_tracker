# MediTrack — Round 4 Remediation Changes (Stages 10B, 12, 13, 14)

This document details all changes and new files introduced during the Round 4 remediation pass on MediTrack (`com.meditrack.app`).

---

## 1. Summary of Changes by Problem Area

### Part A: Stage 13 Evaluation Honesty, Runtime Guards & Model Hygiene
- **`ml/reports/evaluation_report.md` & `ml/model_card.md`**:
  - Removed impossible precision/recall/mAP metrics for `cell_partial` (it had 0 training and validation instances in the public dataset). Stated: `"cell_partial: not trained in public dataset, never predicted; app treats PARTIAL as unknown/needs retake"`.
  - Recomputed all metrics directly from a real evaluation run (`ml/evaluate.py`) across 59 validation images and 432 cell instances, saved to `ml/reports/eval_results.json`.
  - Removed all claims regarding 320 own-photo gold test images. Stated explicitly that the gold test set is **MISSING** (open human labeling task).
  - Corrected model format and size everywhere to exact 12.0 MB Float32 FlatBuffer (`strip_detector.tflite`). Removed claims of INT8/FP16 quantization.
  - Relabeled 11.9–13.1 ms latency benchmark as **host CPU, not device**. Added physical device benchmark procedure to `docs/verification/STAGE_13_VERIFY.md` marked **NOT RUN**.
- **`ml/simulate_verification.py` & `ml/reports/simulation_results.json`**:
  - Implemented verification simulation script evaluating 200 blister verification attempts with blister pill ledger comparisons and conservative confidence guards.
  - Ran simulation and saved results to `ml/reports/simulation_results.json` (155/200 valid ledger intake detections, 45 retakes/rejections). Described honestly as a synthetic simulation on cropped dataset images.
- **`core/config/VerificationConfig.kt`**:
  - Added conservative runtime thresholds: `minCellConfidence = 0.60f` and `maxCellCountTolerance = 1`.
- **`feature/verification/domain/DetectorModels.kt` & `TfliteStripDetector.kt`**:
  - Added `stripConfidence: Float? = null` to `Detection`.
  - Handled Float32 buffer normalization and strip boundary margin calculation.
- **`feature/verification/pipeline/VerificationPipeline.kt`**:
  - Added conservative runtime guards:
    1. Rejects with `RejectReason.BLURRY` if strip confidence $< 0.50$ or any cell confidence $< 0.60$.
    2. Rejects with `RejectReason.WRONG_PILL_COUNT` if total detected cells deviates from baseline by $> 1$ cell.
    3. Treats `cell_partial` as unknown / retake prompt.
- **Unit Tests**:
  - `VerificationPipelineTest.kt`: Added tests for strip confidence rejection, cell confidence rejection, cell count drift rejection, and updated partial cell handling.
  - `VerificationCoordinatorTest.kt`: Added tests for cell count discrepancy retake flow.
  - `VerificationModuleTest.kt`: Added tests verifying release builds strictly bind `TfliteStripDetector` without fallback to `FakeStripDetector`, and missing model fails closed.

---

### Part B: Stage 12 SQLCipher Room Database Encryption
- **`gradle/libs.versions.toml` & `app/build.gradle.kts`**:
  - Added `net.zetetic:sqlcipher-android:4.5.4` and `androidx.sqlite:sqlite-ktx:2.4.0`.
- **`core/security/DatabaseKeyManager.kt`**:
  - Created key manager generating a 32-byte (256-bit) cryptographically secure random passphrase.
  - Wrapped passphrase using hardware-backed Android Keystore AES-256 GCM (`KeystoreSecurityManager`) with alias `MediTrackMasterKey` and stored in private preferences.
- **`data/db/DatabaseEncryptionMigrator.kt`**:
  - Created migration utility that inspects the database header for plaintext SQLite magic bytes (`"SQLite format 3\000"`).
  - Migrates legacy plaintext database to encrypted format using `sqlcipher_export`.
- **`core/di/DatabaseModule.kt`**:
  - Integrated `SupportOpenHelperFactory(passphrase)` into Room database builder.
- **`data/db/AppDatabase.kt`**:
  - Incremented version to 4. Added `MIGRATION_3_4` adding `supersedesProposalId` to `medicine_proposals`.
  - Exported schema to `app/schemas/com.meditrack.app.data.db.AppDatabase/4.json`.
- **Documentation & Tests**:
  - `docs/verification/STAGE_12_VERIFY.md` (Section D): Documented encryption approach and exact `adb` / `sqlite3` verification commands (marked **NOT RUN**).
  - `AppDatabaseMigrationTest.kt`: Added Room schema migration test `migrate3To4_addsSupersedesProposalIdToMedicineProposals`.
  - `DatabaseEncryptionTest.kt`: Unit tests for key generation, wrapping/unwrapping, and header inspection.

---

### Part C: Stage 12 Cloud-Governed Account Deletion
- **`functions/src/index.ts`**:
  - Implemented callable Cloud Function `deleteAccountData` (Admin SDK) for authenticated callers:
    * Recursively deletes `users/{uid}` and subcollections (`medicines`, `doseEvents`).
    * Deletes `caregiverLinks` and `doctorLinks` where caller is patient or doctor.
    * Deletes `caregiverInvites` and `doctorInvitations` owned by caller.
    * Deletes `medicineProposals` where caller is patient or doctor (honoring retention rule: a doctor's proposal record is deleted only when the doctor deletes their account).
    * Deletes `notificationEvents` referencing caller.
    * Purges Storage `proofs/{uid}/*`.
    * Deletes Firebase Auth user.
    * Returns structured response `{ success: boolean, deletedItems: string[], errors: string[] }`.
- **`data/service/AccountCloudDeleter.kt` & `domain/usecase/DeleteAccountUseCase.kt`**:
  - Callable client implementation calling `deleteAccountData`.
  - If cloud deletion fails or returns errors, the app **never claims false success** or wipes local data without consent.
  - Catches `RecentLoginRequiredException` and surfaces re-authentication requirement.
  - Provides `forceLocalOnly` override allowing user to clear local data if permanently offline.
- **Unit Tests**:
  - `DeleteAccountUseCaseTest.kt`: Tests for cloud success, partial cloud failure preservation, recent-login propagation, and offline force local wipe.

---

### Part D: Doctor Mode Proposal Revision & Firestore Security Rules
- **`firestore.rules`**:
  - Allowed doctors to update proposals from `CLARIFICATION_REQUESTED` to `SUPERSEDED` changing strictly `status` and `resolvedAt` (`affectedKeys().hasOnly(['status', 'resolvedAt'])`).
  - Required replacement proposal to provide `supersedesProposalId` and document-level `revision`.
  - Kept proposal deletion strictly denied for clients (`delete: false`).
- **`data/db/Entities.kt` & `data/repository/DoctorCloud.kt`**:
  - Added `supersedesProposalId: String? = null` to `MedicineProposal`.
  - Synchronized `supersedesProposalId` and document-level `revision` to Firestore documents.
- **`domain/usecase/ReviseProposalUseCase.kt` & `feature/doctor/DoctorViewModel.kt`**:
  - Configured `ReviseProposalUseCase` to set `supersedesProposalId = oldProposalId` and `revision = oldProposal.revision + 1`.
  - Updated `DoctorViewModel` to supply `resolvedAt` when marking old proposal as `SUPERSEDED`.
- **`scripts/firebase_security_tests.js`**:
  - Added 5 security test cases verifying doctor can only supersede from `CLARIFICATION_REQUESTED`, cannot alter payload during superseding, and replacement proposal must reference old proposal.

---

### Part E: Stage 14 Release Hardening, R8 & Signing
- **`app/build.gradle.kts`**:
  - Enabled `isMinifyEnabled = true` and `isShrinkResources = true` on release build type.
  - Added release signing configuration template reading keystore path and passwords from environment variables or `local.properties` (never committed).
- **`app/proguard-rules.pro`**:
  - Added comprehensive keep rules for SQLCipher (`net.zetetic.database.sqlcipher.**`), Room SQLite, Dagger-Hilt, TensorFlow Lite, ML Kit OCR, Firebase Auth/Firestore/Storage/Functions, and domain models.
- **`AndroidManifest.xml`**:
  - Verified clean permissions: strictly zero location permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`), no `READ_MEDIA_*` permissions, minimal receiver exports.
- **`docs/verification/STAGE_14_VERIFY.md`**:
  - Documented R8 configuration, signing template, permission audit, and physical device smoke test procedure (marked **NOT RUN**).

---

### Part F: Consent-First Architecture (DPDP Act 2023)
- **`feature/onboarding/SplashViewModel.kt` & `MainActivity.kt`**:
  - First-run flow strictly ordered: **Welcome $\to$ Privacy Consent $\to$ Create Account / Login**.
  - Routes directly to `Screen.PrivacyConsent.route` if `isPrivacyConsentGiven == false`.
- **`feature/auth/AuthViewModel.kt`, `FakeAuthRepository.kt`, `FirebaseAuthRepository.kt`**:
  - Hard-gated account creation against `isPrivacyConsentGiven`: throws exception / returns error if consent has not been granted.
  - Guaranteed no user row or Firestore user document is created prior to consent.
- **Unit Tests**:
  - `SplashViewModelTest.kt`, `AuthViewModelTest.kt`, `FakeAuthRepositoryTest.kt` validating consent gating.

---

### Part G: Repository Hygiene & Verification Truthfulness
- **`.gitignore`**:
  - Added `ml/data/`, `ml/tflite_export/`, `runs/`, `*.pt`, `*.apk`.
- **Documentation Synchronized**:
  - `docs/ARCHITECTURE.md`, `docs/QA.md`, and `PROGRESS.md` updated to reflect exact real metrics, SQLCipher encryption, exact TFLite flatbuffer size, and 359 passing unit tests.

---

## 2. Inventory of New and Modified Files

### Modified Files:
- `app/build.gradle.kts`
- `app/proguard-rules.pro`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/meditrack/app/MainActivity.kt`
- `app/src/main/java/com/meditrack/app/core/config/VerificationConfig.kt`
- `app/src/main/java/com/meditrack/app/core/di/DatabaseModule.kt`
- `app/src/main/java/com/meditrack/app/core/di/RepositoryModule.kt`
- `app/src/main/java/com/meditrack/app/data/db/AppDatabase.kt`
- `app/src/main/java/com/meditrack/app/data/db/Entities.kt`
- `app/src/main/java/com/meditrack/app/data/repository/DoctorCloud.kt`
- `app/src/main/java/com/meditrack/app/data/repository/FakeAuthRepository.kt`
- `app/src/main/java/com/meditrack/app/data/repository/FirebaseAuthRepository.kt`
- `app/src/main/java/com/meditrack/app/domain/usecase/DeleteAccountUseCase.kt`
- `app/src/main/java/com/meditrack/app/domain/usecase/ReviseProposalUseCase.kt`
- `app/src/main/java/com/meditrack/app/feature/auth/AuthViewModel.kt`
- `app/src/main/java/com/meditrack/app/feature/doctor/DoctorViewModel.kt`
- `app/src/main/java/com/meditrack/app/feature/onboarding/SplashViewModel.kt`
- `app/src/main/java/com/meditrack/app/feature/verification/domain/DetectorModels.kt`
- `app/src/main/java/com/meditrack/app/feature/verification/pipeline/TfliteStripDetector.kt`
- `app/src/main/java/com/meditrack/app/feature/verification/pipeline/VerificationPipeline.kt`
- `app/src/test/java/com/meditrack/app/domain/usecase/DeleteAccountUseCaseTest.kt`
- `app/src/test/java/com/meditrack/app/feature/verification/pipeline/VerificationCoordinatorTest.kt`
- `app/src/test/java/com/meditrack/app/feature/verification/pipeline/VerificationPipelineTest.kt`
- `firestore.rules`
- `functions/src/index.ts`
- `gradle/libs.versions.toml`
- `ml/model_card.md`
- `ml/reports/evaluation_report.md`
- `scripts/firebase_security_tests.js`
- `.gitignore`
- `docs/ARCHITECTURE.md`
- `docs/QA.md`
- `docs/verification/STAGE_12_VERIFY.md`
- `docs/verification/STAGE_13_VERIFY.md`
- `docs/verification/STAGE_14_VERIFY.md`
- `PROGRESS.md`

### New Files:
- `app/schemas/com.meditrack.app.data.db.AppDatabase/4.json`
- `app/src/androidTest/java/com/meditrack/app/data/db/AppDatabaseMigrationTest.kt`
- `app/src/main/java/com/meditrack/app/core/security/DatabaseKeyManager.kt`
- `app/src/main/java/com/meditrack/app/data/db/DatabaseEncryptionMigrator.kt`
- `app/src/main/java/com/meditrack/app/data/service/AccountCloudDeleter.kt`
- `app/src/test/java/com/meditrack/app/core/di/VerificationModuleTest.kt`
- `app/src/test/java/com/meditrack/app/core/security/DatabaseEncryptionTest.kt`
- `app/src/test/java/com/meditrack/app/data/repository/FakeAuthRepositoryTest.kt`
- `app/src/test/java/com/meditrack/app/domain/security/DoctorProposalSecurityTest.kt`
- `app/src/test/java/com/meditrack/app/feature/auth/AuthViewModelTest.kt`
- `app/src/test/java/com/meditrack/app/feature/onboarding/SplashViewModelTest.kt`
- `ml/evaluate.py`
- `ml/reports/eval_results.json`
- `ml/simulate_verification.py`
- `ml/reports/simulation_results.json`
- `CHANGES.md`
