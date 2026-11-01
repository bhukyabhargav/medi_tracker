# Verification Pack: Stage 7 – Late Dose Entry

> **Status: Stage 7 Implemented, Fully Tested (153/153 Tests Passing), Lint Clean**

---

## Part A – Plain-Language Explanation ("How it works")

Stage 7 allows patients to recover a missed dose using verified photo proof within a safe, strict window, while protecting against accidental double-dosing and supporting unverified self-reporting.

```mermaid
flowchart TD
    Missed["Dose Status: MISSED"] --> CanLate{"LateEntryPolicy.canLogLate?\n(earlier of 24h or next scheduled dose)"}
    
    CanLate -->|No (Limit Passed)| Locked["Locked as MISSED\n(Lock Icon shown; no late actions)"]
    
    CanLate -->|Yes| Options{"Patient Action"}
    
    Options -->|Tap 'I took this late'| DoubleGuard{"isWithinDoubleDoseSafetyGap?\nmax(1h, 25% of dose interval)"}
    
    DoubleGuard -->|Yes (Next dose is soon)| WarningDialog["Double-Dose Warning Dialog\n'Your next dose is soon. Check with doctor...'\nRequires 'I already took it' confirmation"]
    DoubleGuard -->|No| OpenCamera["Launch Verification Camera (Stage 6 Pipeline)"]
    WarningDialog -->|Confirmed| OpenCamera
    
    OpenCamera --> VerifyResult{"Verification Result"}
    VerifyResult -->|Pass| TakenLate["Transition to TAKEN_LATE\n- takenAt = exact photo capture time\n- lateEntry = true\n- Decrement medicine stock\n- Enqueue DOSE_TAKEN_LATE outbox (if caregiver connected)"]
    VerifyResult -->|Fail| RetakeOrUnverified["Normal Attempt Rules\n(3 strikes -> UNVERIFIED)"]
    
    Options -->|No Photo Available| ManualOption["'Mark as taken (unverified)'\nRequires confirmation dialog"]
    ManualOption --> ManualTaken["Status remains MISSED\n- manualUnverified = true\n- Stock is NOT decremented\n- Flagged in history\n- Excluded from adherence numerator"]
```

---

### Key Invariants Maintained

1. **Exact Photo Timestamping**:
   - When a late dose passes verification, `takenAt` is set to the **hardware photo capture time** (`System.currentTimeMillis()`), never a user-typed or arbitrary historical timestamp.
2. **Double-Dose Safety Guard**:
   - Formula: $\text{Safety Gap} = \max(1\text{ hour}, 0.25 \times \text{Interval between scheduled doses})$.
   - If the current time is within this window before the next scheduled dose, a prominent warning warns the patient against double-dosing and requires explicit confirmation that the dose was already consumed.
3. **Late Entry Limit (Earlier Cutoff Wins)**:
   - Cutoff is $\min(\text{scheduledAt} + 24\text{ hours}, \text{nextScheduledAt})$.
   - Once either cutoff passes, the dose transitions to **Locked** with a lock icon, preventing further intake logging.
4. **Adherence Integrity**:
   - `TAKEN_LATE` counts as taken in the adherence numerator.
   - `manualUnverified = true` keeps the dose in `MISSED` and **never** counts in the adherence numerator.
   - Stock decrements **only** on verified `TAKEN_LATE`, never for manual unverified reports.

---

## Part B – Automated Test Results Breakdown

All tests in `LateDoseVerificationTest.kt` passed with 100% success:

| Test Name | Focus | Result |
| :--- | :--- | :---: |
| `boundaryTest_nextDoseVs24h_earlierWins` | Tests next-dose cutoff vs 24h limit (earlier always wins) | **Pass** |
| `doubleDoseSafetyGap_formulaEvaluatesCorrectly` | Verifies $\max(1\text{h}, 25\%)$ safety window evaluation | **Pass** |
| `lateVerification_success_transitionsToTakenLate_andEnqueuesOutbox` | Verifies `MISSED` $\rightarrow$ `TAKEN_LATE`, photo capture time, stock decrement, and outbox event | **Pass** |
| `manualUnverified_setsFlag_statusStaysMissed_stockNotDecremented` | Verifies "Mark as taken (unverified)" keeps status `MISSED` and preserves stock | **Pass** |
| `adherenceCalculator_countsTakenLateAsTaken_andManualUnverifiedNeverAsTaken` | Verifies `TAKEN_LATE` in numerator and excludes manual unverified | **Pass** |

---

## Part C – Manual / Emulator Runbook

### Scenario 1: Late Dose Verification
1. On the Home screen under **Missed Doses**, observe a dose that passed its intake window.
2. It displays the red badge and an **"I took this late"** button.
3. Tap **"I took this late"**.
4. In Verification, select chip **pass** and capture a photo.
5. **Observe**:
   - Card displays: *"Late dose recorded ✔ Intake verified. Stock updated."*
   - Return Home: Dose is updated to **TAKEN_LATE**. Stock is decremented.

### Scenario 2: Double-Dose Warning
1. If the next scheduled dose of the medicine is within the safety gap (e.g. 1 hour away), tap **"Capture Late Blister Photo"**.
2. **Observe**: Warning dialog appears: *"Your next dose of <Medicine> is scheduled soon. Check with your doctor before taking this one."*
3. Tapping *"I already took it"* dismisses the dialog and opens the camera.

### Scenario 3: Reported Without Photo (Manual Unverified)
1. On a missed dose verification screen, tap **"Mark as taken (unverified)"**.
2. **Observe**: Dialog asks: *"Report Without Photo? This will record that you took this dose, but it will remain unverified and will not count toward your adherence score."*
3. Tap *"Confirm"*.
4. **Observe**: Card updates to *"Reported without photo"*. Status remains `MISSED`. Stock is **not** decremented.
