# Verification Pack: Stage 6 – Verification Pipeline & Taken Integration

> **Status: Stage 6 Implemented, Fully Tested (146/146 Unit Tests Passing), Lint Clean, Debug APK Built**

---

## Part A – Plain-Language Explanation ("How it works")

Stage 6 implements the complete dose verification engine and integrates it with intake logging, ledger tracking, stock counts, and caregiver notification outboxes.

> **Medicine Identity Architecture**: Medicine identity is established directly from the scheduled `Medicine` and `DoseEvent` records in the database. The blister verification pipeline does **not** perform OCR medicine-name matching, eliminating false rejections caused by cropped, folded, unreadable, or missing packaging text on the blister strip.

```mermaid
flowchart TD
    Photo["Captured Photo (from CaptureScreen)"] --> Quality["Step 1: ImageQualityGate\n(Blur, Too Dark, Too Bright, Glare)"]
    Quality -->|Fail| QualityFail["Free Retake (Attempts NOT consumed)"]
    
    Quality -->|Pass| DupCheck["Step 2: Duplicate Photo Check\npHash Hamming Distance <= 10 vs History"]
    DupCheck -->|Duplicate| DupFail["Reject: DUPLICATE_PHOTO\n(Consumes 1 Attempt)"]
    
    DupCheck -->|Unique| CellDet["Step 3: Strip Cell Detection\n(FakeStripDetector in Debug / TFLite in Release)"]
    CellDet -->|stripFullyInFrame == false| FrameFail["Reject: STRIP_NOT_FULLY_VISIBLE\n(Consumes 1 Attempt)"]
    
    CellDet -->|stripFullyInFrame == true| Ledger["Step 4: Ledger Rule Comparison\nnewEmpty - previousEmpty == pillsPerDose?"]
    
    Ledger -->|delta == 0| ZeroPills["Reject: PILLS_NOT_REMOVED\n(Consumes 1 Attempt)"]
    Ledger -->|delta != pillsPerDose| CountFail["Reject: WRONG_PILL_COUNT\n(Consumes 1 Attempt)"]
    
    DupFail --> StrikeCounter{"Attempts Check\n(newAttempts < 3?)"}
    FrameFail --> StrikeCounter
    ZeroPills --> StrikeCounter
    CountFail --> StrikeCounter
    
    StrikeCounter -->|Attempts < 3| RetryAllowed["Retake Allowed Card\n'X retakes remaining'"]
    StrikeCounter -->|Attempt == 3| ThreeStrikes["Status -> UNVERIFIED\nCancel Alarm\nEnqueue DOSE_UNVERIFIED Outbox (if caregiver connected)\nExhausted Card"]
    
    Ledger -->|delta == pillsPerDose| PassWindow{"Intake Window Check"}
    PassWindow -->|Within Window| PassTaken["Status -> TAKEN\nRecord takenAt & verifiedPhotoPath\nDecrement Stock\nCancel Alarm\n'Dose recorded ✔'"]
    PassWindow -->|Window Expired| LateCheck{"LateEntryPolicy.canLogLate?"}
    LateCheck -->|Yes| LateTaken["Status -> TAKEN_LATE\nlateEntry = true\nDecrement Stock\nCancel Alarm"]
    LateCheck -->|No| MissedDose["Status -> MISSED\n'Intake window expired'"]
```

---

### Verification Pipeline Check Order & Decision Table

Every photo undergoes exactly 4 checks in this strict order. Early failure halts pipeline execution immediately:

| Step | Check | Condition / Rule | Reject Reason | User-Facing Message | Attempts Consumed? |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **1** | **Quality Gate** | Mean luminance < 40, > 220, glare > 0.08, or blur variance < 100 | `BLURRY`, `TOO_DARK`, `TOO_BRIGHT`, `GLARE` | Friendly guidance (e.g. *"Photo is blurry. Hold steady..."*) | **No** (0 attempts) |
| **2** | **Duplicate Check** | 64-bit dHash Hamming distance $\le 10$ against last 20 verified/attempt photos for this medicine | `DUPLICATE_PHOTO` | *"Duplicate photo detected. Please take a new photo of your blister strip."* | **Yes** (1 attempt) |
| **3** | **Strip Detection** | `stripFullyInFrame == false` or bounding box clipped | `STRIP_NOT_FULLY_VISIBLE` | *"The blister strip is not fully in frame. Please align the entire strip within the guide."* | **Yes** (1 attempt) |
| **4a** | **Pills Not Removed** | $\Delta = \text{newEmpty} - \text{previousEmpty} = 0$ | `PILLS_NOT_REMOVED` | *"No new pills removed. Please push out your dose."* | **Yes** (1 attempt) |
| **4b** | **Wrong Pill Count** | $\Delta = \text{newEmpty} - \text{previousEmpty} \ne \text{pillsPerDose}$ | `WRONG_PILL_COUNT` | *"Expected <N> pill(s) removed, but counted <M>."* | **Yes** (1 attempt) |
| **4c** | **Ledger Pass** | $\Delta = \text{newEmpty} - \text{previousEmpty} == \text{pillsPerDose}$ | *(None – PASSED)* | *"Dose verified successfully."* | None (Success) |

> **Partial Cell Rule**: Cells detected as `PARTIAL` are treated strictly as `FULL` (unconsumed), preventing premature pass if a pill was only partially pressed out.

---

### Attempt Handling & 3-Strikes Outbox Rule

1. **Attempts 1 & 2**:
   - The user is shown an inline error card highlighting the exact reason (`friendlyMessage`) along with remaining attempts (*"2 retakes remaining"* or *"1 retake remaining"*).
   - Dose remains in `PENDING` state. Stock is **never** decremented.
2. **Attempt 3 (Exhausted)**:
   - Dose is transitioned via `DoseStateMachine` to `UNVERIFIED`.
   - Dose alarm is cancelled in `AlarmScheduler`.
   - If `caregiverRepository.hasConnectedCaregiver(currentUserId) == true`, an event of type `DOSE_UNVERIFIED` is enqueued into `NotificationOutbox` with payload:
     ```json
     {"doseId":"<id>", "medicineId":"<id>", "reason":"<REASON>"}
     ```
   - User is shown the Exhausted state: *"We couldn't verify this dose. Your caregiver has been notified."*
3. **Bypass Prevention Invariant**:
   - Marking a dose as `TAKEN` can **only** occur via `RecordVerifiedDoseUseCase` with a passing `VerificationResult`.
   - Doses in `UNVERIFIED`, `SKIPPED`, or `MISSED` cannot be transitioned to `TAKEN` without valid verification.

---

### Dual Detector Implementations (Debug vs Release)

- **`FakeStripDetector` (Debug)**:
  - Bound in `VerificationModule` for debug builds.
  - Interactive "Force Result" chips in the Verification UI allow developers/testers to trigger any state:
    - `FORCE_PASS`
    - `FORCE_PILLS_NOT_REMOVED`
    - `FORCE_WRONG_COUNT`
    - `FORCE_STRIP_NOT_FULLY_VISIBLE`
    - `FORCE_LOW_CONFIDENCE`
- **`TfliteStripDetector` (Release)**:
  - Bound in `VerificationModule` for release builds.
  - In release builds where `assets/strip_detector.tflite` is not yet bundled (arrives in Stage 13), it throws an `IllegalStateException`.
  - The UI gracefully catches this and presents: *"Verification temporarily unavailable"*, refusing to mark doses as `TAKEN`.

---

## Part B – Automated Test Results

All 146 unit tests passed with 0 failures:

### Stage 6 Test Suite Breakdown:

| Test Class | Tests | Status | Key Coverage |
| :--- | :---: | :---: | :--- |
| **`TfliteStripDetectorTest`** | 1 | Pass | Release detector properly throws `IllegalStateException` when model asset is absent |
| **`VerificationPipelineTest`** | 12 | Pass | Quality gate reject (`BLURRY`, `TOO_DARK`), duplicate hash reject (`DUPLICATE_PHOTO`), strip not in frame (`STRIP_NOT_FULLY_VISIBLE`), `PILLS_NOT_REMOVED`, `WRONG_PILL_COUNT`, ledger pass, partial cell treated as full, confidence $< 0.5$ filtered, baseline fallback, passes without any OCR text extraction, hidden/cropped name does not cause rejection, no medicine name comparison executed |
| **`VerificationCoordinatorTest`** | 6 | Pass | Pass verification transitions to `TAKEN` + updates stock; failures increment attempts (1 & 2); 3rd strike transitions to `UNVERIFIED` + outbox enqueue with caregiver; skips outbox without caregiver; window expiration routes to `LateEntryPolicy`; bypass protection |
| **Existing Unit Tests (Stages 0–5)** | 127 | Pass | Full regression test suite passing across auth, home, medicine, reminder, scheduling, quality gate, perceptual hasher |
| **Total** | **146** | **100% Pass** | |

---

## Part C – Manual / Emulator Runbook

To test Stage 6 on device or emulator with `app-debug.apk`:

### Prerequisites
1. Open the app and log in or start as Patient.
2. Add a medicine (e.g. *"Paracetamol 500mg"*, 1 pill per dose, 10 stock, baseline empty cells = 0).
3. Tap on the dose card from the Home screen or Dose List to navigate to `verification/{doseId}`.

### Test Scenarios:

#### Scenario 1: Missing Baseline Enrollment
1. For a newly added medicine without a baseline photo, open the verification screen.
2. **Observe**: UI prompts *"Baseline Strip Photo Required - Please take a baseline photo of your blister strip before your first dose verification."*
3. Tap *"Take Baseline Photo"*, capture a clear photo of the full strip.
4. **Observe**: Baseline is enrolled and the screen transitions directly to dose verification.

#### Scenario 2: Debug Force Pass (Normal Intake)
1. In `verification/{doseId}`, scroll down to the **DEBUG CONTROLS** section at the bottom.
2. Select the chip **Force Pass**.
3. Tap *"Take Photo"* or *"Take Another Photo"*, capture a clear photo.
4. **Observe**:
   - "Analyzing strip..." progress spinner appears.
   - Result card turns green: *"Dose verified! Recorded as taken."*
   - Return to Home screen: Dose shows **TAKEN** badge with checkmark.
   - In Medicine details: Stock decreased from 10 to 9.

#### Scenario 3: Debug Force "Pills Not Removed" (Attempt 1 & 2)
1. Select a pending dose.
2. In Debug Controls, select chip **Force Pills Not Removed**.
3. Capture a photo.
4. **Observe**:
   - Result card turns red: *"No pills were removed from the strip. Please remove your dose before taking the photo."*
   - *"Attempt 1 of 3: 2 retakes remaining."*
   - Dose remains **PENDING**. Stock remains unchanged.
5. Capture a second photo with same chip.
6. **Observe**:
   - *"Attempt 2 of 3: 1 retake remaining."*

#### Scenario 4: 3rd Strike Exhaustion & Caregiver Alert
1. Continuing from Scenario 3 (2 attempts recorded), capture a 3rd photo with **Force Pills Not Removed**.
2. **Observe**:
   - Card turns warning orange/red: *"Verification Exhausted (3/3 attempts used)"*.
   - Message: *"We couldn't verify this dose. Your caregiver has been notified."*
   - Retake button is disabled. Return home.
   - Dose shows **UNVERIFIED** badge.
   - Alarm is cancelled. Stock is **not** decremented.
   - `NotificationOutbox` has recorded a `DOSE_UNVERIFIED` event.

#### Scenario 5: Debug Force "Strip Not In Frame"
1. In Debug Controls, select chip **Force Strip Not Fully Visible**.
2. Capture a photo.
3. **Observe**: Rejection with message *"The blister strip is not fully in frame. Please align the entire strip within the guide."*

#### Scenario 6: Duplicate Photo Check
1. In normal capture mode, take a photo of an identical object twice in succession.
2. **Observe**: 2nd attempt is rejected with *"Duplicate photo detected. Please take a new photo of your blister strip."*
