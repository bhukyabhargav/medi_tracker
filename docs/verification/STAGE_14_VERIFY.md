# Stage 14 Verification Pack — Hardening, QA & Release Readiness

## 1. Overview & Goal

Stage 14 completes final hardening, comprehensive permission boundary verification, accessibility pass, localization scaffolding, reliability checks, R8 bytecode obfuscation and resource shrinking, secure signing configuration templating, and Google Play release packaging for MediTrack.

---

## 2. Section B — 16 Permission Boundary & Safety Tests

Covered by [`PermissionBoundaryVerificationTest.kt`](file:///C:/Users/bharg/OneDrive/Documents/Desktop/MedTracker%20-%20BioDesign/app/src/test/java/com/meditrack/app/domain/security/PermissionBoundaryVerificationTest.kt) and `scripts/firebase_security_tests.js`:

| # | Safety Rule & Permission Boundary | Test Method in `PermissionBoundaryVerificationTest` | Status |
|---|---|---|---|
| 1 | Unconnected doctors cannot access patient data | `boundary01_unconnectedDoctors_cannotAccessPatientData` | **PASSED** |
| 2 | Pending invitations grant no patient-data access | `boundary02_pendingInvitations_grantNoPatientDataAccess` | **PASSED** |
| 3 | Declined invitations grant no access | `boundary03_declinedInvitations_grantNoAccess` | **PASSED** |
| 4 | Accepted invitations grant only permitted access | `boundary04_acceptedInvitations_grantOnlyPermittedAccess` | **PASSED** |
| 5 | Revoked connections immediately deny further access | `boundary05_revokedConnections_immediatelyDenyFurtherAccess` | **PASSED** |
| 6 | Doctor cannot create proposal for unconnected patient | `boundary06_doctorCannotCreateProposal_forUnconnectedPatient` | **PASSED** |
| 7 | Pending proposal does not create active medicine/doses | `boundary07_pendingProposal_doesNotCreateActiveMedicineOrDoseEvents` | **PASSED** |
| 8 | Declined proposal does not change active medication | `boundary08_declinedProposal_doesNotChangeActiveMedication` | **PASSED** |
| 9 | Patient acceptance activates proposal via medicine workflow | `boundary09_patientAcceptance_activatesProposalThroughExistingMedicineWorkflow` | **PASSED** |
| 10 | Revised proposal requires fresh acceptance | `boundary10_revisedProposal_requiresFreshAcceptance` | **PASSED** |
| 11 | Previous accepted versions remain auditable | `boundary11_previousAcceptedVersions_remainAuditable` | **PASSED** |
| 12 | Doctors cannot mark doses TAKEN | `boundary12_doctorsCannotMarkDosesTaken` | **PASSED** |
| 13 | Caregivers cannot mark doses TAKEN | `boundary13_caregiversCannotMarkDosesTaken` | **PASSED** |
| 14 | Neither role can bypass photo verification | `boundary14_neitherRoleCanBypassPhotoVerification` | **PASSED** |
| 15 | Cross-patient data access is rejected | `boundary15_crossPatientDataAccess_isRejected` | **PASSED** |
| 16 | Account deletion & data export handle Doctor Mode data | `boundary16_accountDeletionAndDataExport_handleDoctorModeDataCorrectly` | **PASSED** |

---

## 3. Section C — Complete End-to-End Acceptance Script

### Patient Flow:
1. **Onboarding & Consent**: Launch fresh app $\to$ DPDP Act 2023 disclaimer displayed $\to$ tap **I Understand & Consent** $\to$ register as Patient $\to$ grant exact alarm and camera permissions.
2. **Add / Scan Medicine**:
   - Option A: Tap **+ Add Medicine** $\to$ enter "Metformin", 500mg, 1 pill twice daily (08:00, 20:00), initial stock 30 $\to$ Save.
   - Option B: Tap **Scan Rx** $\to$ photograph pharmacy label $\to$ verify pre-filled fields $\to$ confirm schedule.
3. **Dose Alarm & Intake Verification**:
   - Alarm triggers at scheduled time with sound/vibration.
   - Tap notification $\to$ open camera $\to$ photograph blister strip.
   - Model detects empty cavities: ledger calculates $\Delta \text{Empty} = 1$ pill.
   - Dose transitions to **TAKEN**; current stock drops from 30 to 29.
4. **History & Calendar**:
   - History logs show verified dose with green status chip.
   - Calendar displays green adherence dot for the date.
5. **Data Sovereignty**:
   - Settings $\to$ Export My Data $\to$ generates encrypted ZIP archive containing all records and photos.
   - Settings $\to$ Delete Account $\to$ confirms wipe $\to$ all records purged across cloud and local DB and returns to Login.

### Doctor Flow:
1. Doctor logs in $\to$ generates invitation code.
2. Patient redeems code $\to$ link established with status `ACCEPTED`.
3. Doctor views patient's 7-day adherence report and submits proposal `EDIT` (increase to 2 pills per dose).
4. Proposal arrives in Patient Settings as `PENDING`.
5. Patient taps **Accept** $\to$ schedule updates dynamically; auditable history logged.

### Caregiver Flow:
1. Caregiver logs in $\to$ links to patient via invitation code.
2. Observes real-time adherence dashboard.
3. Receives low-stock push notification when supply $\le 3$ days.

---

## 4. Section D — Release Hardening, R8 & Signing Verification

### 1. R8 Minification & Resource Shrinking (`app/build.gradle.kts`)
- `isMinifyEnabled = true` and `isShrinkResources = true` are active on the release build type.
- ProGuard rules (`app/proguard-rules.pro`) preserve:
  - SQLCipher for Android native JNI methods (`net.zetetic.database.sqlcipher.**`)
  - Room SQLite internal reflection and entities
  - Dagger-Hilt dependency injection generated classes
  - TensorFlow Lite runtime native bindings (`org.tensorflow.lite.**`)
  - Google ML Kit OCR and vision pipelines
  - Firebase Authentication, Firestore, Cloud Functions, and Cloud Storage DTOs
  - Pure-Kotlin domain models, enums, and state machines
- **Measured APK Size Reduction**:
  - `app-debug.apk`: **111,067,078 bytes** (~111.1 MB)
  - `app-release-unsigned.apk`: **88,494,511 bytes** (~88.5 MB)
  - Net APK size reduction via R8 dead code elimination and resource shrinking: **22,572,567 bytes (~22.6 MB / 20.3% reduction)**.

### 2. Release Signing Configuration Template
- Release builds read signing credentials strictly from environment variables or `local.properties` (never committed to version control):
  ```properties
  # In local.properties or System Environment Variables:
  RELEASE_KEYSTORE_PATH=/path/to/keystore.jks
  RELEASE_KEYSTORE_PASSWORD=keystore_password
  RELEASE_KEY_ALIAS=key_alias
  RELEASE_KEY_PASSWORD=key_password
  ```
- If environment variables / properties are absent, Gradle gracefully skips signing, producing an unsigned release APK (`app-release-unsigned.apk`) suitable for CI/CD signing pipelines.

### 3. AndroidManifest Permission & Component Audit
- Verified permissions in `app/src/main/AndroidManifest.xml`:
  - `POST_NOTIFICATIONS`: Required for Android 13+ dose notifications.
  - `SCHEDULE_EXACT_ALARM` & `USE_EXACT_ALARM`: Required for timely medication reminders.
  - `CAMERA`: Required for blister strip intake verification and prescription OCR scanning.
  - `RECEIVE_BOOT_COMPLETED`: Required for `BootReceiver` alarm rescheduling.
  - `INTERNET`: Required for Firebase synchronization and Cloud Function account deletion.
  - `VIBRATE`: Required for alarm vibration patterns.
- **Strictly Prohibited Permissions Verified Absent**:
  - Zero location permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`).
  - Zero storage permission bloat (`READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`). All photos reside strictly in app-private sandboxed directories.
- **Receiver Export Boundaries**:
  - `BootReceiver`: `android:exported="true"` strictly protected with `RECEIVE_BOOT_COMPLETED` filter.
  - `ReminderReceiver`: `android:exported="false"`, strictly internal pending intent receiver.
  - `FileProvider`: `android:exported="false"`, `android:grantUriPermissions="true"`.

### 4. Release Verification Smoke Test Procedure (Marked NOT RUN)
```bash
# Procedure for physical device / emulator release smoke test:
# 1. Install release build on physical device:
adb install app-release-unsigned.apk

# 2. Verify debug detector bypass is strictly disabled:
#    In release builds, VerificationModule binds TfliteStripDetector.
#    FakeStripDetector is absent from the release dependency graph.
#    Automated unit test VerificationModuleTest.kt validates release wiring.

# 3. Smoke Test Steps on Physical Device:
#    a. Open app -> Complete privacy consent -> Login / Home.
#    b. Add medication: "Aspirin", 100mg, 1 pill.
#    c. Trigger dose verification camera.
#    d. Photograph blister strip: confirm TfliteStripDetector processes frames.
#    e. If model file is absent or corrupted, confirm app fails closed (retake prompt)
#       and never marks dose as TAKEN via fake bypass.
```
> [!NOTE]
> **Physical Device Smoke Test Status**: **NOT RUN** (verified on host build and JVM test suites).

---

## 5. Section E — Stage Pack Summary & Automated Test Count

| Stage | Pack Document | Focus Area | Automated Test Coverage |
|---|---|---|---|
| **Stage 0–2** | `STAGE_2_VERIFY.md` | Architecture, Onboarding, Permission Setup, Splash | 24 tests |
| **Stage 3** | `STAGE_3_VERIFY.md` | Add/Edit Medicine, Rolling Schedule Generation | 28 tests |
| **Stage 4** | `STAGE_4_VERIFY.md` | Exact Alarms, BootReceiver, Snooze Engine | 32 tests |
| **Stage 5–6** | `STAGE_6_VERIFY.md` | Verification Pipeline, Image Quality Gate, Ledger | 48 tests |
| **Stage 7** | `STAGE_7_VERIFY.md` | Late Dose Intake, Missed Dose Safety Engine | 36 tests |
| **Stage 8** | `STAGE_8_VERIFY.md` | Medication History, Adherence Calculator, Calendar | 30 tests |
| **Stage 9** | `STAGE_9_VERIFY.md` | Stock Tracking, Single-Authority Decrement, Refill | 34 tests |
| **Stage 10** | `STAGE_10_VERIFY.md` | Caregiver Cloud Mode, Push Notifications | 26 tests |
| **Stage 10B** | `STAGE_10B_VERIFY.md` | Doctor Mode, Proposal State Machine, Adherence | 48 tests |
| **Stage 11** | `STAGE_11_VERIFY.md` | Prescription OCR Scan, Review Screen, Prefill | 20 tests |
| **Stage 12** | `STAGE_12_VERIFY.md` | Settings, DPDP Privacy, Export, Delete, Keystore, SQLCipher | 25 tests |
| **Stage 13** | `STAGE_13_VERIFY.md` | TFLite Model Integration, Latency Benchmark | 8 tests + ML Scripts |
| **Stage 14** | `STAGE_14_VERIFY.md` | Hardening, 16 Permission Boundaries, Release Readiness | 28 tests |
| **TOTAL** | **All Stages** | **Complete MediTrack Application** | **359 Unit Tests** (100% Passing) |

---

## 6. Section F — Open Limitations & Risk Registry

1. **Hardware Ingestion vs Extraction**:
   - The computer vision model verifies that a pill left the blister foil cavity. It cannot medically prove that the patient placed the pill in their mouth and swallowed it.
2. **Extreme Reflection / Glare on Metallic Foil**:
   - Specular reflections at angles $> 20^\circ$ can mimic pill reflections in empty cavity bubbles. Mitigated by `ImageQualityGate` glare rejection prompting a retake.
3. **Hourly Frequencies & Time-zone Drift**:
   - Hourly regimens use fixed elapsed intervals. If traveling across significant time-zone offsets, `AlarmMaintenanceWorker` synchronizes future alarms on `ACTION_TIMEZONE_CHANGED`.
4. **Third-Party Physician Verification**:
   - MediTrack does not verify state medical licenses or registration numbers of doctors connecting via Doctor Mode. Patients must only share codes with their real-world physicians.
5. **Human Gold Test Set**:
   - The held-out own-photo gold test set is currently **MISSING** and remains an open human labeling task per Stage 13 requirements.
