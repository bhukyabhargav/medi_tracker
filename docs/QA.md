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
| 7 | **Airplane mode (offline)** | Offline-first Room architecture; pure on-device ML & OCR pipelines | Alarms, dose capture, verification, and history run 100% offline | **PASS** |
| 8 | **Low battery mode** | `BatteryOptimizationScreen` guidance; exact alarms unaffected by OS battery saver | Reminders fire without suppression | **PASS** |
| 9 | **OEM Battery Killers** | Dedicated guidance screens for Xiaomi (MIUI/HyperOS), Oppo, Vivo, Samsung | Step-by-step instructions for autostart & battery unrestricted mode | **PASS** |

---

## 2. Edge Case Resilience

| Edge Case | Failure Prevention Strategy | Result |
|---|---|---|
| **Device clock changed backwards** | Monotonic checks; dose cannot be taken before `scheduledAt`; past doses remain immutable | **SAFE** |
| **Two medicines at same time** | Unique `doseEvent.id` per medicine; independent alarms and notifications grouped cleanly | **SAFE** |
| **High scale (50+ medicines)** | LazyColumn rendering, Room indexed queries (`Index("patientId")`, `Index("status")`), paginated queries | **SAFE** |
| **Very large photos (>12MP)** | Image downsampling in `ImageQualityGate` / `PrescriptionParser` to max dimension <= 2048px | **SAFE** |
| **Camera unavailable** | Graceful camera failure handling with actionable retry guidance | **SAFE** |
| **Storage full** | App-private storage limits checked; safe error handling during photo write | **SAFE** |
| **TFLite model missing** | Fails closed in release mode; throws explicit actionable exception without crashing | **SAFE** |
| **Process death mid-verification** | Atomic database transactions; photo preserved in `filesDir/proofs`; uncommitted state discarded safely | **SAFE** |

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
| 10 | Revised proposal requires fresh acceptance | Revision from `CLARIFICATION_REQUESTED` resets state to `PENDING` | **VERIFIED** |
| 11 | Previous accepted versions remain auditable | Proposal history preserves revision numbers and resolution timestamps | **VERIFIED** |
| 12 | Doctors cannot mark doses TAKEN | Only patient client verification pipeline can write `TAKEN` status | **VERIFIED** |
| 13 | Caregivers cannot mark doses TAKEN | Caregivers hold read-only adherence views; zero write endpoints for doses | **VERIFIED** |
| 14 | Neither role can bypass photo verification | `RecordVerifiedDoseUseCase` requires successful `VerificationPipeline` result | **VERIFIED** |
| 15 | Cross-patient data access rejected | Room repository patient isolation + Firestore rules enforce UID ownership | **VERIFIED** |
| 16 | Account deletion & data export handle Doctor Mode data | `ExportDataUseCase` bundles doctor records; `DeleteAccountUseCase` purges them | **VERIFIED** |
