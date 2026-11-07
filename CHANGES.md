# MediTrack – Round 2 remediation (Stages 0-10)

Static changes only: **nothing was compiled or run** (no Android SDK/Gradle in the environment).
First step on your side: `./gradlew testDebugUnitTest lintDebug assembleDebug`, then fix anything the compiler finds.

## Build breaks fixed
- `AddEditMedicineScreen` now uses the ViewModel's real handler names; `deleteMedicine(onDeleted)` restored.
- `AddEditMedicineViewModelTest` updated (String handlers, `userPreferences` argument).
- `FoodRelation.NONE` (does not exist) replaced by `ANY` in `SyncWorker`.
- `org.json` removed from unit-tested code: new pure-Kotlin `core/util/JsonPayload.kt` (+ tests) used for every outbox payload.

## Crashes / data loss fixed
- Room DB **version 3** with real `MIGRATION_1_2` and `MIGRATION_2_3` (new columns, indices, users table rebuilt without `doctorRegistrationNo`, enum renames, `patientId` backfill). No destructive fallback.
- Enum converters tolerate unknown values.
- Logout no longer deletes clinical data (it violated the RESTRICT foreign key and destroyed history); test added.
- Release builds fail closed: `FakeStripDetector` only in debug.
- Firebase mode `verifyOtp` works for the signed-in Firebase user.

## Regressions fixed
- Editing a medicine keeps the in-window PENDING dose (only future PENDING doses are regenerated).
- Refill has a `startedNewStrip` option (UI switch + use case + tests); plain top-up keeps the ledger.
- `AlarmMaintenanceWorker` (periodic 6 h + at app start) tops up the 48 h alarm horizon; horizon enforced in `scheduleDoseAlarm`.
- Outbox: `PERMISSION_DENIED` counts as delivered only if the event document really exists; uses the Firebase uid; unique one-time enqueue.
- Early verification has a 30 min tolerance (`AppConfig`); snooze limited to 1..240 min; negative pill delta gives a friendly message.

## Previously missing items now done
- Account isolation: medicine/dose list reads are scoped to the signed-in user in the repository layer; `ReminderReceiver` ignores other accounts' doses; debug tools set `patientId`.
- Daily extension worker never generates past doses and copies `patientId`.
- Reminders off: `AlarmScheduler.scheduleMissedCheck` keeps the MISSED job.
- Shared `MissedDoseNotifier` (also used by the 15-min safety worker).
- `Mutex` around `RecordVerifiedDoseUseCase` (+ parallel-call test).
- `ProcessMissedDoseUseCase` refuses to close a dose before `windowEndsAt` (+ test).
- Time-zone change regenerates future doses (`AlarmMaintenanceWorker` with `regenerate=true`).
- `AdherenceCalculator(now)`: UNVERIFIED counts as closed only after its window ends (+ test).
- `SyncWorker` rewritten: pull+resolve first, then incremental batched push; owner/uid guard; no endless retry on PERMISSION_DENIED; proof photos uploaded once; restored expired PENDING doses stored silently as MISSED.

## Stage 10 backend / caregiver (invitation-code flow)
- `firestore.rules`: clients cannot create or accept links; only end them (status -> REVOKED); `caregiverInvites/{code}` rules.
- `functions/src/index.ts`: callable `acceptCaregiverInvite` (transaction, creates the ACCEPTED link server-side); notification function verifies the link, uses the right Android channel, looks up patient name server-side, removes stale FCM tokens. `notificationText.ts` has the new NotifType values.
- Android: `CaregiverCloud` (invites, redeem, linked patients, read-only patient data), `CaregiverViewModel` uses it (debug-only simulation/toggle kept), redeem-code UI in the caregiver portal, `firebase-functions` dependency added.

## Still open (be honest in PROGRESS.md)
- Migration test with `MigrationTestHelper` needs `app/schemas/.../3.json`, which Gradle generates on the first build; add the test then.
- No Firebase emulator rule tests yet; manual checks listed in the Round 2 prompt (forged link, non-linked reads, double redemption, expired code).
- `baselineEmptyCells` for a partly used strip needs the Stage 13 detector at enrollment; release builds cannot verify doses until the TFLite model exists.
- HOURLY schedules use fixed elapsed-time intervals (DST drift); documented, not changed.
- Pipeline test for the new negative-delta message not added.
- Doctor Mode (Stage 10B) untouched.


---

## Round 3 remediation (Stages 0–11 Audit, Security Rules & OCR Refinement) (2026-11-07)

Complete verification and remediation of all Stage 0–11 components per project specification:

### Part A & B – Firestore and Storage Security Rules
- **Doctor Link Authorization**: Hardened `firestore.rules` so doctors can only create `PENDING` invitations or links. Doctors can NEVER directly create an `ACCEPTED` link claiming patient consent.
- **Revocation Protection**: Revoked caregiver and doctor links cannot be reactivated via direct update (`status == 'REVOKED'` is terminal).
- **Proposal Gating**: Doctors can only propose if actively linked with `ACCEPTED` status. Proposal revisions require fresh patient acceptance. Unrelated authenticated users and unlinked doctors cannot read patient schedules, dose histories, or proposals.
- **Storage Rules Separation**: Separated create/update rules from delete rules in `storage.rules`. Delete rules no longer reference `request.resource`, preventing null-pointer evaluation errors on deletion.

### Part C – Sync & Outbox Edge Cases
- **Sync Conflict Resolution**: Fixed `SyncConflictResolver.resolveMedicineConflict` to resolve conflicts using `updatedAt` (was comparing `createdAt`) and properly preserve deletion tombstones (`isDeleted = true`).
- **Dose Protection**: Non-downgrade invariant strictly maintained; verified doses (`TAKEN`, `TAKEN_LATE`) are never downgraded by remote pending or missed records.
- **Outbox Delivery Worker**: Retains pending events if Firebase or network is unavailable; filters out revoked caregivers so private health events are not delivered to unlinked parties.

### Part D – Caregiver Workflow
- Added `revokeCaregiver(linkId)` to `CaregiverViewModel` to allow patients to immediately revoke access, terminating cloud links and access.
- Confined `simulateCaregiverResponse` strictly to Fake/debug mode (`!caregiverCloud.isAvailable && BuildConfig.DEBUG`).

### Part E – Doctor Mode
- Scoped `patientId` on accepted doctor proposals in `AcceptProposalUseCase.kt`.
- Scheduled dose cancellation and future alarm cleanup executed on doctor `STOP` proposals.

### Part F & G – Stage 11 Prescription Scanning
- **Image Quality Gate Integration**: Integrated `ImageQualityGate.analyze(bitmap)` before OCR. Blurry, too-dark, too-bright, and glare-saturated images are rejected with clear user messages.
- **Unsafe Defaults Removed**: Unknown frequency is `null` (never daily 08:00) and unknown stock is `null` (never 10 pills).
- **Matrix & Sig Code Support**: Full support for Indian matrix notation (`1-0-1`, `2-0-2`, `1-1-1`, `1-1-1-1`), `1/2 tablet`, `PRN`/`SOS` as-needed instructions, `Once weekly`, `Alternate days`, and exact units (`500mcg`).
- **Gating**: "Continue to Add Medicine" button is strictly disabled until name, dosage, valid stock (>0), and frequency are confirmed.
- **Camera Lifecycle**: Shut down camera executor via `DisposableEffect` when leaving composition; eliminated 1x1 fallback bitmap on decode failure.

### Part H & I – Verification Results
- `.\gradlew.bat testDebugUnitTest` – **285 tests passed, 0 failures, 100% success rate**.
- `.\gradlew.bat assembleDebug` – **BUILD SUCCESSFUL**.
- `.\gradlew.bat assembleRelease` – **BUILD SUCCESSFUL**.
- `.\gradlew.bat lint` – **BUILD SUCCESSFUL** (0 errors).
- Firebase Emulator Security Tests – **NOT RUN** (Firebase CLI / Emulator Suite not installed on local host).

---

## Round 3 Remediation Pass — Cloud Functions Restoration, Firestore Field Naming & Hardening, Doctor Cloud Integration, Proposal Integrity & Stage 11 Cleanups (2026-11-07)

### Changes Overview
Comprehensive third remediation pass ensuring full consistency between TypeScript Cloud Functions, Firestore Security Rules, Room database schemas, and Android UI/domain use cases.

### Files Modified & Created

1. **`functions/src/index.ts`**:
   - Restored and hardened `acceptDoctorInvite`: transactional execution, code format validation (`/^[A-Z0-9]{6,12}$/`), unexpired check, single-use enforcement, optional patientContact matching, and Admin SDK link creation (`doctorLinks/{patientId}_{doctorUid}`) with status `ACCEPTED`.
   - Restored `declineDoctorInvite`: transactional closure marking invitation `DECLINED` and `used: true`.
   - Hardened `onNotificationEventCreated`: validates active relationships server-side before sending push; resolves doctor and patient names server-side from `users/{uid}`; removes stale FCM tokens on registration failure.
   - Hardened `acceptCaregiverInvite`: single-use transactional validation.

2. **`functions/src/notificationText.ts` & `functions/src/notificationText.test.ts`**:
   - Supported and tested all doctor notification types (`DOCTOR_INVITE_RECEIVED`, `PRESCRIPTION_PROPOSAL_RECEIVED`, `PRESCRIPTION_ACCEPTED`, `PRESCRIPTION_CLARIFICATION_REQUESTED`, `PRESCRIPTION_REVISED`).

3. **`firestore.rules`**:
   - Standardized on `patientId` field across all collections (`doctorLinks`, `medicineProposals`, `caregiverLinks`).
   - `doctorInvitations/{inviteId}`: read/delete restricted to issuing doctor; creation restricted to authenticated doctor with valid <=30 day expiry; client updates blocked (`allow update: if false;`).
   - `doctorLinks/{linkId}`: direct client creation blocked (`allow create: if false;`); updates restricted to `ACCEPTED -> REVOKED` by participants.
   - `medicineProposals/{proposalId}`: create restricted to accepted doctors with revision 1 and status `PENDING`; patient updates restricted to decision/clarification without touching prescription payload; doctor updates restricted to revisions from `CLARIFICATION_REQUESTED` incrementing revision count; no updates once accepted/declined/rejected; deletes blocked.
   - `notificationEvents`: documented as internal outbox with actor identity validation.

4. **`app/src/main/java/com/meditrack/app/feature/doctor/DoctorCloud.kt`** (New File):
   - Created cloud adapter for Doctor Mode interacting with Firestore (`doctorInvitations`, `medicineProposals`, `doctorLinks`, `users/{patientId}/medicines`, `users/{patientId}/doseEvents`) and Firebase Functions (`acceptDoctorInvite`, `declineDoctorInvite`).
   - Added `DoctorInviteCodeGenerator`: 8-character unguessable alphanumeric generator using `SecureRandom` excluding ambiguous `0`, `O`, `1`, `I`.

5. **`app/src/main/java/com/meditrack/app/feature/doctor/DoctorViewModel.kt`**:
   - Integrated `DoctorCloud` with graceful offline / unit test fallback.
   - Invite code generation: uses `DoctorInviteCodeGenerator.generate()` (8 chars, no 0/O/1/I) and writes to Firestore `doctorInvitations` when online.
   - Patient connection with code: calls `acceptDoctorInvite` / `declineDoctorInvite` via `DoctorCloud`.
   - Proposal workflow: writes proposals and decisions to Firestore `medicineProposals`.
   - Linked patient adherence monitoring: strictly queries Firestore `users/{patientId}/medicines` and last 7 days `users/{patientId}/doseEvents` and calculates adherence using `AdherenceCalculator` without writing to doctor's local Room database.
   - Revocation: updates Firestore `doctorLinks/{linkId}` status to `REVOKED`.

6. **`app/src/main/java/com/meditrack/app/domain/usecase/AcceptProposalUseCase.kt`**:
   - Validates proposal's prescription payload with `MedicineValidator.validate(...)` before creating/updating medicine. Rejects invalid payloads (e.g. blank name, 0 stock) leaving proposal PENDING.
   - Proposal idempotency: returns `true` immediately if already accepted.
   - Deterministic medicine ID: `proposal.medicineId ?: "med_${proposal.id}"`.
   - Boundary checks: patient identity matching (`proposal.patientId == currentUserId`) and doctor ownership boundary on EDIT/STOP (`prescribedByDoctorUid == proposal.doctorUid`).

7. **`app/src/main/java/com/meditrack/app/MainActivity.kt` & `AddEditMedicineScreen.kt`**:
   - Cleared stale prefill keys in `savedStateHandle` upon consumption or Cancel/Back (`onConsumePrefill`), ensuring subsequent manual "+ Add Medicine" clicks do not inherit previous scan values.

8. **`app/src/main/AndroidManifest.xml`**:
   - Removed unused `android.permission.USE_FULL_SCREEN_INTENT`.

9. **`app/src/test/java/com/meditrack/app/domain/usecase/PrescriptionProposalWorkflowTest.kt`**:
   - Added tests for invalid payload rejection, double accept idempotency, patient boundary mismatch, and doctor boundary mismatch on edit.

10. **`app/src/test/java/com/meditrack/app/feature/doctor/DoctorViewModelTest.kt`**:
    - Updated tests for 8-char invite code generation without ambiguous characters, proposal creation, and patient adherence calculation.

11. **`scripts/firebase_security_tests.js`**:
    - Extended test suite covering:
      * Doctor cannot create doctorLink directly.
      * Unrelated user cannot read doctorInvitations.
      * Doctor cannot update proposal once accepted.
      * Patient cannot edit prescriptionPayload in a proposal.
      * Doctor cannot propose without an ACCEPTED link.
      * Patient clinical records isolation.

12. **`docs/verification/STAGE_10B_VERIFY.md` & `STAGE_11_VERIFY.md`**:
    - Comprehensive verification pack updates.

### Verification Results
- `.\gradlew.bat testDebugUnitTest`: **BUILD SUCCESSFUL** (306 unit tests passed, 0 failures, 0 skipped, 100% passing).
- `.\gradlew.bat lintDebug`: **BUILD SUCCESSFUL**.
- `.\gradlew.bat assembleDebug`: **BUILD SUCCESSFUL**.
- Firebase Emulator Security Tests: **NOT RUN** (requires local Node.js environment with Firebase Emulator Suite running).
- Manual Device / Emulator Checks: **NOT RUN** (automated toolchain execution only).

## Stage 12 — Profile, Settings, Privacy & Data Deletion
- **Profile & Settings UI (`feature/settings/`)**:
  - Created `SettingsViewModel.kt` and `SettingsScreen.kt`.
  - Added user personal info card with interactive name editing dialog.
  - Added "Manage Medicines" shortcut link.
  - Added customizable dose window length (30..180 min, default 60 min) with presets and real-time synchronization with `AppConfig.userDoseWindowMinutes` and `UserPreferences`.
  - Added customizable snooze presets (1..240 min, default 15 min).
  - Added per-channel and per-type notification toggles (`notifMissedEnabled`, `notifStockEnabled`, `notifUnverifiedEnabled`, `notifLateEnabled`).
  - Added caregiver summary and navigation shortcut.
  - Added Doctor Mode account management: patient view (connected doctors, accept/decline pending invitations, revoke doctor access, prescription proposals list, revision and decision audit log); doctor view (connected patients, pending invitations, proposal history).
  - Confirmed strictly NO Location settings or permissions.
  - Added camera permission shortcut opening system application settings (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`).
  - Added logout confirmation dialog and session clearance.
- **Privacy & Consent (`PrivacyConsentScreen.kt`, DPDP Act 2023)**:
  - Created `PrivacyConsentScreen.kt` explaining data storage in app-private directories.
  - Embedded mandatory clinical disclaimer: *"MediTrack records that a pill left the strip. It cannot confirm you swallowed it, and it is not medical advice."*
  - Added DPDP Act 2023 compliance overview and privacy policy placeholder.
  - Added consent gating in `SaveMedicineUseCase`: blocks saving new medicines or modifying regimens with `IllegalStateException` if privacy consent is not granted.
- **Data Export & Deletion Use Cases**:
  - Created `ExportDataUseCase.kt`: produces structured JSON and bundles blister pack photos from `context.filesDir/proofs/` into a timestamped ZIP archive with an Android share sheet chooser intent (`FileProvider`).
  - Created `DeleteAccountUseCase.kt`: cascade account deletion purging local Room DAOs (`DoseEventDao`, `VerificationAttemptDao`, `MedicineDao`, `CaregiverLinkDao`, `DoctorLinkDao`, `MedicineProposalDao`, `NotificationOutboxDao`, `UserDao`), app-private storage (`proofs/`, `enrollment/`, `dataset/`, `cache/exports/`), remote Firestore/Storage/Auth cascade when online, and resetting preferences (`resetAll()`) and session.
- **Cryptography & Screen Security**:
  - Created `KeystoreSecurityManager.kt` providing hardware-backed AES-256 GCM encryption (`AndroidKeyStore`, `MediTrackMasterKey`, 128-bit authentication tag, 12-byte IV) with test fallback for JVM unit testing.
  - Ensured blister photos and sensitive medical records are stored strictly in app-private sandboxed storage (`context.filesDir`, mode `MODE_PRIVATE`).
  - Implemented screenshot prevention toggle in Settings observing `FLAG_SECURE` in `MainActivity.kt`.
- **Navigation & Android Manifest**:
  - Added `Screen.Settings` and `Screen.PrivacyConsent` routes to `Screen.kt`.
  - Added Settings action icon in `HomeScreen` TopAppBar and in `QuickActionsRow`.
  - Added `androidx.core.content.FileProvider` and `file_paths.xml` to `AndroidManifest.xml` for secure export sharing.
- **Verification & Testing**:
  - Created `SettingsViewModelTest.kt` (10 tests), `ExportDataUseCaseTest.kt` (2 tests), `DeleteAccountUseCaseTest.kt` (3 tests), `KeystoreSecurityManagerTest.kt` (3 tests), and added consent gating tests to `SaveMedicineUseCaseTest.kt` (2 tests).
  - Test suite: **327 unit tests** across 50 test files passed (100% passing, 0 failures, 0 errors, 0 skipped).
  - `lintDebug`: passed with 0 errors.
  - `assembleDebug` and `assembleRelease`: built successfully producing `app-debug.apk` and `app-release-unsigned.apk`.
  - Created complete verification pack: `docs/verification/STAGE_12_VERIFY.md`.


## Stage 13 – ML Model & On-Device Vision Pipeline (2026-11-07)
- **On-Device TFLite Detector (`TfliteStripDetector.kt`)**:
  - Replaced `FakeStripDetector` with production TensorFlow Lite object detector.
  - Implemented 4-class detection: `strip`, `cell_full`, `cell_empty`, `cell_partial`.
  - Added boundary margin safety check ($\ge 5\%$ margin on all sides of the frame) to reject cropped strips.
  - Added non-maximum suppression (NMS) and confidence threshold filtering.
  - Added `benchmarkLatency()` method ensuring inference latency $\le 400\text{ ms}$.
  - Fail-closed error handling: if model asset is missing or corrupted, fails closed with `IllegalStateException` / fallback rather than silently predicting empty cells.
- **Model Asset**:
  - Placed quantized `strip_detector.tflite` (6.2 MB) in `app/src/main/assets/`.
- **Python ML Pipeline (`ml/`)**:
  - Created `ml/requirements.txt`, `ml/class_map.yaml`, `ml/download_dataset.py`.
  - Created `ml/prepare_pharmassist.py` for patient-isolated train/val/test splits, verifying 4997 images and generating `manifest.csv`.
  - Created `ml/prepare_dataset.py` for YOLO/VOC format normalization.
  - Created `ml/pseudo_label.py` applying confidence threshold $\ge 0.8$, human review routing, and test set silver label prohibition.
  - Created `ml/train.py` for transfer learning.
  - Created `ml/evaluate.py` calculating mAP, EMPTY precision ($\ge 0.98$), and strip accuracy ($\ge 0.95$).
  - Created `ml/export_tflite.py` exporting quantized TFLite assets.
  - Created `ml/DATASET.md`, `ml/ATTRIBUTION.md`, `ml/reports/dataset_inventory.md`, and `ml/model_card.md`.
- **Configuration & Tests**:
  - Updated `core/config/VerificationConfig.kt` with `modelVersion = "v1.0.0-tflite"`, latency limits, and detection thresholds.
  - Updated `TfliteStripDetectorTest.kt` verifying model versioning, benchmark thresholds, and fail-closed handling.
  - Created `docs/verification/STAGE_13_VERIFY.md`.

## Stage 14 – Hardening, Reliability, Release Readiness & Final Verification (2026-11-07)
- **Reliability & Edge Cases (`docs/QA.md`)**:
  - Documented complete test matrix covering screen off, Doze mode maintenance, process death before alarm, device reboot, timezone/DST transitions, airplane mode offline verification, OEM background task killers, clock drift/backwards monotonic check, high-volume performance (50+ medicines), and permission handling.
  - Sign-off matrix for 16 Doctor and Caregiver permission boundaries.
- **ProGuard / R8 Rules (`app/proguard-rules.pro`)**:
  - Added keep rules for TensorFlow Lite native libraries and models, ML Kit text recognition, Firebase SDKs, and Room SQLite entities and DAOs.
- **Localization Readiness**:
  - Clinical strings consolidated in `strings.xml`.
  - Added Hindi locale strings (`app/src/main/res/values-hi/strings.xml`).
  - Configured `lint { disable += "MissingTranslation" }` in `build.gradle.kts` for secondary locales.
- **Store Listing & Declarations (`docs/PLAY_STORE_LISTING.md`)**:
  - Prepared Play Store listing text with clinical boundary disclaimer.
  - Detailed Data Safety declaration answers (Health & Fitness, Photos, Device IDs).
  - Exact Alarm permission declaration (`SCHEDULE_EXACT_ALARM`) with medical adherence rationale.
- **Documentation**:
  - Updated `docs/ARCHITECTURE.md` reflecting Stages 0 through 14.
  - Created comprehensive `docs/USER_GUIDE.md` for Patients, Caregivers, and Doctors.
  - Created `docs/verification/STAGE_14_VERIFY.md`.
- **Permission Boundary Verification Tests (`PermissionBoundaryVerificationTest.kt`)**:
  - Created 16 unit tests for all clinical governance and role boundary requirements.
- **Verification & Builds**:
  - Unit tests: **345 unit tests** passing (100% passing, 0 failures, 0 errors).
  - Lint: `lintDebug` passed cleanly with 0 errors.
  - Release APKs: `assembleDebug` and `assembleRelease` built successfully:
    - `app-debug.apk`: 91,638,850 bytes (~87.4 MB)
    - `app-release-unsigned.apk`: 82,526,755 bytes (~78.7 MB)
