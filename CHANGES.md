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


---

## Round 5 Stability Fixes (Stage 1: Findings A1 - A6)

### Fixed Showstoppers & Startup Crashes
1. **A1 (Zero Silent Data Wipes & Recoverable Database Error Screen)**
   - Modified `core/di/DatabaseModule.kt`: Removed `context.deleteDatabase(AppDatabase.DATABASE_NAME)` and `.fallbackToDestructiveMigration()`. If an existing database cannot be opened or decrypted, the file is preserved intact on disk.
   - Added `core/database/DatabaseRecoveryState.kt` and `feature/error/DatabaseErrorScreen.kt`: Surfaces a user-facing error screen detailing the issue, offering **Retry Database Unlock**, **Export Diagnostics** (via `Intent.ACTION_SEND` without leaking PHI), and **Reset Local Data** (with explicit confirmation dialog).
   - Integrated into `MainActivity.kt`.

2. **A2 (SQLCipher Key Mismatch & Plaintext Backup Rollback)**
   - Modified `data/db/DatabaseEncryptionMigrator.kt` and `core/security/DatabaseKeyManager.kt`: Added `DatabaseKeyManager.derivePassphraseString(rawKey)` and `derivePassphraseBytes(rawKey)` to unify key derivation. `ATTACH DATABASE ... KEY '$passphraseStr'` and `SupportOpenHelperFactory(openerBytes)` now evaluate the exact same PBKDF2 key.
   - Preserves plaintext database as `<name>.bak` during migration until the encrypted DB is opened and verified with `SELECT count(*) FROM sqlite_master;`. If verification fails, rolls back to `.bak` automatically.
   - Added instrumented test: `androidTest/java/com/meditrack/app/data/db/DatabaseEncryptionMigrationInstrumentedTest.kt`.

3. **A3 (Fail-Closed KeyStore & KeyProvider Abstraction)**
   - Created `core/security/KeyProvider.kt`, `core/security/AndroidKeystoreKeyProvider.kt`, and `core/security/KeystoreUnavailableException.kt`.
   - Modified `core/security/KeystoreSecurityManager.kt`: Removed hardcoded `ByteArray(32) { 42 }` fallback key entirely. Fails closed with `KeystoreUnavailableException` on real devices when AndroidKeyStore is locked or inaccessible.
   - Created `core/di/SecurityModule.kt` for Hilt binding and `core/security/TestKeyProvider.kt` for unit testing.

4. **A4 (Database Key Decryption & Crash Loop Prevention)**
   - Modified `core/security/DatabaseKeyManager.kt`: Throws typed `DatabaseKeyException` on decryption failure; never silently regenerates keys for existing databases.
   - Handled in `DatabaseModule.kt`: Catches `DatabaseKeyException` and `KeystoreUnavailableException`, setting `DatabaseRecoveryState` and using an in-memory fallback so Hilt doesn't crash app startup before Compose can display the recovery screen.

5. **A5 (ProGuard Rules for Zetetic SQLCipher & Release Minification)**
   - Modified `app/proguard-rules.pro`: Added `-keep class net.zetetic.database.sqlcipher.** { *; }`, `-keep class net.zetetic.database.** { *; }`, and JNI callback rules.
   - Verified with `./gradlew.bat assembleRelease`: Build succeeded and produced `app-release-unsigned.apk` (48,058,447 bytes).

6. **A6 (Centralized SQLCipher Native Library Loading)**
   - Created `core/database/SqlCipherLoader.kt`: Thread-safe single point of loading for `libsqlcipher.so`.
   - Updated `MediTrackApp.kt`, `DatabaseModule.kt`, and `DatabaseEncryptionMigrator.kt` to use `SqlCipherLoader.load()`.


---

## Round 5 Stability Fixes (Stage 2: Out-Of-Memory & Camera Pipeline B1 - B3 + E1)

### Fixed Out-Of-Memory Crashes & Camera Leaks
1. **B1 (Centralized Sampled Bitmap Decoding & Memory-Safe Captures)**
   - Created `core/util/BitmapLoader.kt`: Central utility with `decodeSampled(path|file|bytes|uri|imageProxy, maxSidePx = 1920)`. Reads image bounds first via `inJustDecodeBounds = true`, computes optimal `inSampleSize`, applies EXIF orientation with intermediate bitmap recycling, scales down safely, and catches `Throwable` (including `OutOfMemoryError`).
   - Configured `ImageCapture` in `CaptureScreen.kt`, `ScanMedicineScreen.kt`, and `EnrollmentPhotoView.kt` with a `ResolutionSelector` capped to 1920x1440 (~2-3 MP) and `setJpegQuality(90)` instead of default 12-50 MP sensor dumps.
   - Refactored `VerificationViewModel.kt` (`processCapturedPhoto` and `recordBaselineEnrollment`), `EnrollmentPhotoView.kt` (`validateAndAccept`), and `ScanMedicineScreen.kt` to decode exclusively through `BitmapLoader.decodeSampled`.
   - In `CaptureScreen.kt`: Guaranteed `image.close()` runs inside a `finally` block to prevent `ImageProxy` pipeline starvation. Added `if (isCapturing) return` guard against duplicate shutter taps.
   - In `ScanMedicineScreen.kt`: Moved file decoding off the main thread to `Dispatchers.IO`.
   - Added unit test: `core/util/BitmapLoaderTest.kt` verifying that large synthetic images (4000x3000) are downsampled and strictly capped to `maxSidePx`.

2. **B2 (CameraX Lifecycle Binding & Hardware Availability Guard)**
   - In `CaptureScreen.kt`: Wrapped `cameraProviderFuture.get()` in a `try/catch(Throwable)` block inside `LaunchedEffect(lifecycleOwner)` with camera fallback (`DEFAULT_BACK_CAMERA` -> `DEFAULT_FRONT_CAMERA`).
   - Added `cameraProvider.unbindAll()` on `DisposableEffect`.
   - Ignored duplicate capture taps while `isCapturing` is active.

3. **B3 (Prescription Scan Temp File Lifecycle Management)**
   - Created `core/util/ScanFileCleaner.kt`: Deletes temporary photos in `scan_tmp` immediately after OCR decoding and on error/cancellation.
   - Added startup purge in `MediTrackApp.onCreate()` removing temporary scan files older than 1 hour.
   - Added `scan_tmp` deletion in `DeleteAccountUseCase.kt` and verified with unit test in `DeleteAccountUseCaseTest.kt`.
   - Added unit test: `core/util/ScanFileCleanerTest.kt`.

4. **E1 (Local Crash Diagnosability & StrictMode)**
   - Created `core/util/CrashLogger.kt`: Installed `Thread.setDefaultUncaughtExceptionHandler` in `MediTrackApp.kt` that records sanitized crash stack traces (no PHI) to rotating log files in `filesDir/crashes` (retaining last 5 crashes) before delegating to default handler.
   - Enabled `StrictMode` thread and VM policies in debug builds (`detectDiskReads`, `detectDiskWrites`, `detectLeakedClosableObjects` with `.penaltyLog()`).
---

## Round 5 Stability Fixes (Stage 3: Blister Detection Pipeline Correctness C1 - C6)

### Fixed Detection Accuracy, Concurrency, and Baseline Ledger Persistence
1. **C1 (Strip Candidate Retention by Maximum Confidence)**
   - Modified `feature/verification/pipeline/TfliteStripDetector.kt`: Replaced overwriting `detectedStripBox = box` on each anchor iteration with `rawStrips.add(StripCandidate(box, maxScore))`. Retains the candidate with `rawStrips.maxByOrNull { it.confidence }` so earlier high-confidence strip anchors cannot be clobbered by later weak anchor detections.
   - Verified via unit test: `TfliteStripDetectorTest.detect_picksHighestConfidenceStripCandidateAcrossAnchors()`.

2. **C2 (Aspect-Ratio Preserving Letterbox Transformation)**
   - Created `core/util/LetterboxTransform.kt`: Scales source images uniformly to target model input size (320x320) with standard YOLO gray padding (114/255, #727272). Eliminates squishing/stretching distortion on 16:9, 4:3, and portrait photos.
   - Provides exact forward coordinate mapping (`toLetterboxCenterSize`) and inverse mapping (`toOriginalNormalized`, `toOriginalNormalizedBox`) from model pixel/normalized coordinates back to normalized original image coordinates [0..1].
   - Integrated into `TfliteStripDetector.kt` for YOLOv8 and SSD input buffering and output box inverse transformation.
   - Added unit test: `core/util/LetterboxTransformTest.kt` covering wide (16:9), tall (9:16), and square aspect ratios, forward/inverse roundtrips, SSD corner coordinates, boundary clamping, and 114 gray background fill.

3. **C3 (Model Contract Assertion on Load)**
   - In `feature/verification/pipeline/TfliteStripDetector.kt`: Added `validateModelContract(interpreter: Interpreter): String?`. Verifies input tensor shape rank 4 NHWC and output tensor shape rank 3 [1, channels>=5, anchors>0] for YOLOv8, or 4 tensors for SSD.
   - Fails closed with `IllegalStateException("Verification unavailable: Model contract mismatch ...")` if model asset does not match expected tensor shapes, preventing runtime `IndexOutOfBoundsException`.
   - Added unit tests: `TfliteStripDetectorTest.validateModelContract_*` covering valid YOLO shape, invalid input rank, insufficient channels (< 5), and zero anchors.

4. **C4 (Class-Agnostic Non-Maximum Suppression Across Cells)**
   - In `feature/verification/pipeline/TfliteStripDetector.kt`: Made cell NMS class-agnostic (`computeIoU(cell.box, kept.box) > iouThreshold`). Because a physical blister cavity cannot be both FULL and EMPTY simultaneously, overlapping detections across different classes now suppress lower-confidence duplicates instead of inflating total cell counts.
   - Added unit test: `TfliteStripDetectorTest.applyNms_classAgnostic_suppressesOverlappingCellsAcrossDifferentClasses()`.

5. **C5 (Partly-Used Strip Enrollment & Baseline Empty Cell Tracking)**
   - Modified `feature/medicine/EnrollmentPhotoView.kt`: Filtered valid cells by `AppConfig.DETECTOR_MIN_CONFIDENCE`, counted empty blisters at enrollment (`validCells.count { it.state == CellState.EMPTY }`), and passed `(path, emptyCells)` to `onPhotoCaptured`.
   - Modified `feature/medicine/AddEditMedicineScreen.kt`: Connected `EnrollmentPhotoView` to `viewModel.onPhotoCapturedWithLedger(path, emptyCells)`.
   - Modified `feature/medicine/AddEditMedicineViewModel.kt`: Persisted `baselineEmptyCells` into the `Medicine` entity upon saving (and updated it if an existing medicine takes a new enrollment photo).
   - In `VerificationViewModel.kt`: Refined baseline photo enrollment to count empty cells against `validCells`.
   - Added unit test: `feature/medicine/PartlyUsedStripEnrollmentTest.kt` verifying that enrolling a partly-used strip with 2 empty blisters correctly persists `baselineEmptyCells = 2`, and subsequent first dose verification (1 pill taken, 3 empty blisters detected) passes cleanly (`3 - 2 = 1 == pillsPerDose`).

6. **C6 (Strip Detector Concurrency & Interpreter Lifecycle)**
   - Modified `feature/verification/pipeline/TfliteStripDetector.kt`: Implemented `StripDetector, java.io.Closeable`.
   - Protected `detect()` with `private val mutex = Mutex()` to serialize inference calls and prevent native SIGSEGV crashes caused by concurrent accesses to the stateful TFLite interpreter.
   - Loaded interpreter lazily on `Dispatchers.IO` in `getOrInitInterpreter()`.
   - Implemented `close()` within mutex lock to cleanly release interpreter resources and reset state for clean re-initialization.
   - Wired `onCleared()` in `VerificationViewModel` and `AddEditMedicineViewModel` to close the strip detector when ViewModels are cleared.
   - Added unit tests: `TfliteStripDetectorTest.concurrentDetect_serializedSafelyByMutex()` and `close_canBeCalledSafelyAndCleansUpResources()`.
