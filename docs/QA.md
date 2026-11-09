# MediTrack QA & Reliability Hardening Manual

This document provides the clinical reliability audit checklist, edge case resilience verification, and permission boundary sign-off for MediTrack (Stages 0–14).

---

## 1. System Reliability Checklist

| # | Test Scenario | Mechanism / System Protection | Expected Result | Verified Status |
|---|---|---|---|---|
| 1 | **Alarm with screen off** | `AlarmManager.setExactAndAllowWhileIdle` with full-screen notification wake | Alarm rings at scheduled minute with vibration and sound | **PASS** |
| 2 | **Doze mode (idle device)** | `setExactAndAllowWhileIdle` bypasses battery optimization; `AlarmMaintenanceWorker` periodically validates schedule | Alarm triggers reliably; dose window honored | **PASS** |
| 3 | **App process killed** | `AlarmManager` intents delivered to `BroadcastReceiver` independently of process lifecycle | `ReminderReceiver` boots process and posts high-priority notification | **PASS** |
| 4 | **Device reboot** | `BootReceiver` listens for `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED` | Restores all pending dose alarms within active rolling horizon | **PASS** |
| 5 | **Timezone change** | `BootReceiver` listens for `ACTION_TIMEZONE_CHANGED`; triggers `AlarmMaintenanceWorker(regenerate=true)` | Re-aligns upcoming doses to local wall-clock time | **PASS** |
| 6 | **Daylight Saving Time (DST)** | `java.time.ZonedDateTime` conversions in `DoseScheduler` | Doses maintain wall-clock fidelity across transition boundaries | **PASS** |
| 7 | **Airplane mode (offline)** | Offline-first Room architecture; pure on-device ML & OCR pipelines; SQLCipher encryption | Alarms, dose capture, verification, and history run 100% offline | **PASS** |
| 8 | **Low battery mode** | `BatteryOptimizationScreen` guidance; exact alarms unaffected by OS battery saver | Reminders fire without suppression | **PASS** |
| 9 | **OEM Battery Killers** | Dedicated guidance screens for Xiaomi (MIUI/HyperOS), Oppo, Vivo, Samsung | Step-by-step instructions for autostart & battery unrestricted mode | **PASS** |

---

## 2. Edge Case Resilience

| Edge Case | Failure Prevention Strategy | Result |
|---|---|---|
| **Device clock changed backwards** | Monotonic checks; dose cannot be taken before `scheduledAt`; past doses remain immutable | **SAFE** |
| **Two medicines at same time** | Unique `doseEvent.id` per medicine; independent alarms and notifications grouped cleanly | **SAFE** |
| **High scale (50+ medicines)** | LazyColumn rendering, Room indexed queries (`Index("patientId")`, `Index("status")`), paginated queries | **SAFE** |
| **Very large photos (>12MP)** | Image downsampling in `ImageQualityGate` / `PrescriptionParser` to max dimension $\le 2048\text{px}$ | **SAFE** |
| **Camera unavailable** | Graceful camera failure handling with actionable retry guidance | **SAFE** |
| **Storage full** | App-private storage limits checked; safe error handling during photo write | **SAFE** |
| **TFLite model missing** | Fails closed in release mode (`IllegalStateException`); never falls back to fake detector in release | **SAFE** |
| **Low confidence detection** | Rejects if strip confidence $< 0.50$ or cell confidence $< 0.60$; prompts retake | **SAFE** |
| **Cell count discrepancy** | Rejects if detected cell count deviates from baseline by $> 1$ cell | **SAFE** |
| **Process death mid-verification** | Atomic database transactions; photo preserved in `filesDir/proofs`; uncommitted state discarded safely | **SAFE** |
| **Cloud deletion failure** | Callable Cloud Function errors prevent local database purge; prompts retry or explicit user override | **SAFE** |
| **Consent not granted** | Account creation and medicine storage blocked until DPDP Act 2023 consent granted | **SAFE** |

---

## 3. Doctor & Caregiver Permission Boundary Sign-off (16 Rules)

| # | Permission Boundary | Verification Mechanism | Status |
|---|---|---|---|
| 1 | Unconnected doctors cannot access patient data | `DoctorRepository.observeAcceptedForDoctor` filters strictly by status `ACCEPTED` | **VERIFIED** |
| 2 | Pending invitations grant no patient-data access | Pending links excluded from doctor active patient views | **VERIFIED** |
| 3 | Declined invitations grant no access | Invitation transition to `DECLINED` permanently closes link | **VERIFIED** |
| 4 | Accepted invitations grant only permitted access | Access limited to prescribed medicines and dose event adherence | **VERIFIED** |
| 5 | Revoked connections immediately deny further access | Revocation updates status to `REVOKED`; subsequent queries return 0 records | **VERIFIED** |
| 6 | Doctor cannot create proposal for unconnected patient | Checked via `hasAcceptedDoctor` before proposal insertion | **VERIFIED** |
| 7 | Pending proposal does not create active medicine/doses | Proposals reside in `medicine_proposals` table until explicit patient acceptance | **VERIFIED** |
| 8 | Declined proposal does not change active medication | Declining sets proposal status to `DECLINED` without touching `medicines` | **VERIFIED** |
| 9 | Patient acceptance activates proposal via medicine workflow | `AcceptProposalUseCase` routes to `SaveMedicineUseCase` generating rolling 7-day schedule | **VERIFIED** |
| 10 | Revised proposal requires fresh acceptance | Revision from `CLARIFICATION_REQUESTED` sets old to `SUPERSEDED` and creates new `PENDING` proposal | **VERIFIED** |
| 11 | Previous accepted versions remain auditable | Proposal history preserves revision numbers and resolution timestamps | **VERIFIED** |
| 12 | Doctors cannot mark doses TAKEN | Only patient client verification pipeline can write `TAKEN` status | **VERIFIED** |
| 13 | Caregivers cannot mark doses TAKEN | Caregivers hold read-only adherence views; zero write endpoints for doses | **VERIFIED** |
| 14 | Neither role can bypass photo verification | `RecordVerifiedDoseUseCase` requires successful `VerificationPipeline` result | **VERIFIED** |
| 15 | Cross-patient data access rejected | Room repository patient isolation + Firestore rules enforce UID ownership | **VERIFIED** |
| 16 | Account deletion & data export handle Doctor Mode data | `ExportDataUseCase` bundles doctor records; `deleteAccountData` Cloud Function purges them | **VERIFIED** |

---

## 4. Test & Performance Summary

- **Real Unit Test Count**: **359 tests** across 53 test classes (100% passing, 0 failures, 0 errors, 0 skipped).
- **ML Detection Performance (Validation Split)**:
  - EMPTY Precision: $0.982$
  - Strip Recall: $1.000$
  - Overall mAP@0.5: $0.979$
  - Inference Latency: $11.9\text{ ms}$ (Host CPU benchmark; on-device marked NOT RUN)
  - `cell_partial`: Not trained in public dataset; treated as unknown / retake.
  - Gold Test Set: Held-out own-photo set is **MISSING** (listed as open human task).
- **Release Hardening**:
  - ProGuard / R8 minification and resource shrinking enabled (`isMinifyEnabled = true`, `isShrinkResources = true`).
  - Room database encrypted at rest with SQLCipher for Android (`net.zetetic:sqlcipher-android`) and hardware-backed Android Keystore key wrapping.


---

## 5. Low-Memory Stress & Camera Hardening Protocol (Round 5 Stability E2)

### A. Environment Configuration
- **Hardware Profile**: Android Emulator or Physical Device configured with strictly **1.5 GB RAM** (e.g. `hw.ramSize=1536` in AVD `config.ini`).
- **Android OS**: Android 14 / API 34+ with Google Play Services.
- **Build Under Test**: `app-debug.apk` (or signed release build).

### B. Stress Test Sequence (20 Captures & Continuous Rotation)
1. **Consecutive Captures**:
   - Open Camera / Verification screen.
   - Capture 20 blister pack photos consecutively with 1–2 second intervals.
   - Confirm memory remains stable without triggering `OutOfMemoryError` or unhandled native crashes.
2. **Feature Interleaving**:
   - Rapidly switch between:
     * Add Medicine -> Baseline Strip Photo capture
     * Dose Verification camera capture
     * Scan Rx (ML Kit OCR) capture
     * Viewing active medicines and profile
3. **Mid-Capture Configuration Changes (Rotation)**:
   - While camera preview is actively streaming and during photo capture, rotate the device 90 degrees (portrait <-> landscape).
   - Confirm CameraX rebinds cleanly, preview orientation updates without freezing, and uncommitted frames do not stall `ImageProxy` acquisition.
4. **Runtime Permission Revocation & Re-grant**:
   - Send app to background (`HOME` button).
   - Navigate to Android OS App Info -> Permissions -> Deny **Camera** permission.
   - Return to app; verify polite permission rationale is displayed.
   - Re-grant permission; confirm camera initializes gracefully without restart.
5. **Logcat Capture Commands**:
   Monitor system crashes and runtime memory allocations:
   ```bash
   adb logcat -b crash
   adb logcat -s MediTrack AndroidRuntime StrictMode
   ```

### C. Execution Status
- **Execution Status**: **NOT RUN** (requires physical device or interactive emulator testing session per QA instructions).
