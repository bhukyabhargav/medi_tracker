# Stage 9 Verification Pack – Stock / Refill Management

## 1. Overview
Stage 9 delivers intelligent pill stock tracking, automatic threshold evaluation, low-stock notifications, caregiver outbox synchronization, and refilling capabilities per the specification in `README.md`:
1. **Deterministic Single-Authority Decrement Rule**:
   - Pill stock decrements **strictly and only** via `RecordVerifiedDoseUseCase` (for both verified on-time and verified late doses).
   - Skipped, missed, unverified, and manual-unverified dose events **never** decrement stock.
   - Duplicate decrements are impossible: closed doses (TAKEN/TAKEN_LATE) are rejected by `DoseStateMachine` before any decrement logic is reached.
2. **Low-Stock Evaluation & Alerting**:
   - Evaluated after every decrement using `StockCalculator.isLow(remaining, pillsPerDay, LOW_STOCK_DAYS_THRESHOLD)`.
   - Fires a notification **only once** on the transition from not-low to low (prevents repeated notification spam on subsequent doses).
   - Enqueues a `LOW_STOCK` event in `NotificationOutbox` if a caregiver is connected.
   - If no caregiver is connected, alerts the patient locally and prompts them to connect a caregiver.
3. **Refill Actions & Banners**:
   - Out of stock (0 pills remaining) displays a persistent red warning banner on the Home dashboard and Medicine list.
   - Low stock displays an amber warning banner on Home dashboard and Medicine list.
   - "Refill" dialog on Medicine card allows adding pills (+10, +30, +60, +100, or custom amount), which updates `currentStock` and resets the `lowStockNotified` state so future depletions alert again.
4. **Strict Scope Compliance**:
   - Zero store/pharmacy searches and zero email sending anywhere in the codebase (proven by static grep analysis).

---

## 2. Automated Unit Tests Summary (Section B)

All business rules for stock management, single authority decrement, threshold evaluation, and single-alert transitions are covered by automated unit tests executed via `./gradlew.bat testDebugUnitTest`:

| Test Class | Test Name | Invariant Tested |
|---|---|---|
| `StockDecrementAuthorityTest` | `verifiedNormalDose_decrementsStockExactlyOnce` | On-time verified dose decrements stock exactly once. |
| `StockDecrementAuthorityTest` | `verifiedLateDose_decrementsStockExactlyOnce` | Late verified dose decrements stock exactly once via single authority. |
| `StockDecrementAuthorityTest` | `duplicateVerifiedDose_doesNotDecrementStockTwice` | Re-verifying an already taken dose is rejected and decrements 0 pills. |
| `StockDecrementAuthorityTest` | `skippedDose_doesNotDecrementStock` | Skipped dose never decrements stock. |
| `StockDecrementAuthorityTest` | `missedDose_doesNotDecrementStock` | Missed dose engine never decrements stock. |
| `StockDecrementAuthorityTest` | `unverifiedDose_exhaustedStrikes_doesNotDecrementStock` | Dose failing 3 verification strikes never decrements stock. |
| `StockDecrementAuthorityTest` | `manualUnverifiedDose_doesNotDecrementStock` | Dose marked as manual-unverified never decrements stock. |
| `ProcessStockDecrementUseCaseTest` | `decrement_decreasesStock_andEvaluatesLowStock` | Decrement when stock remains above threshold; no alert, no outbox event. |
| `ProcessStockDecrementUseCaseTest` | `decrement_whenCrossingThreshold_withCaregiver_triggersSingleAlertAndOutbox` | When stock drops below $pillsPerDay \times 3$, triggers patient notification and enqueues `LOW_STOCK` outbox row. |
| `ProcessStockDecrementUseCaseTest` | `decrement_whenAlreadyLow_doesNotSpamAlertOrOutbox` | Subsequent decrements while already in low state do NOT fire another alert or enqueue duplicate outbox event. |
| `ProcessStockDecrementUseCaseTest` | `decrement_whenCrossingThreshold_withoutCaregiver_triggersAlertAndCaregiverPrompt` | When crossing threshold without a connected caregiver, local alert fires and `promptCaregiver` is true without queuing outbox row. |
| `ProcessStockDecrementUseCaseTest` | `stockNeverDropsBelowZero` | Stock is clamped at 0 when decrement exceeds available stock. |
| `ProcessStockDecrementUseCaseTest` | `thresholdBoundary_exactlyAtThreshold_isNotLow_oneBelow_isLow` | Stock equal to $pillsPerDay \times 3$ is NOT low; stock 1 pill below threshold IS low. |
| `RecordVerifiedDoseUseCaseTest` | `recordVerifiedDose_onTime_transitionsToTaken_decrementsStock_andCancelsAlarm` | On-time dose transitions to TAKEN, cancels alarm, and decrements stock. |
| `RecordVerifiedDoseUseCaseTest` | `recordVerifiedDose_late_transitionsToTakenLate_decrementsStock_andCancelsAlarm` | Late dose transitions to TAKEN_LATE, cancels alarm, and decrements stock. |
| `RecordVerifiedDoseUseCaseTest` | `recordVerifiedDose_failsWhenDoseNotFound` | Fails cleanly when dose ID does not exist; no stock change. |
| `RecordVerifiedDoseUseCaseTest` | `recordVerifiedDose_failsWhenAlreadyTaken_andDoesNotDecrementStock` | Fails when dose already closed; zero stock decrement. |
| `RefillStockUseCaseTest` | `refill_increasesCurrentStock_andResetsLowStockNotified` | Refill adds pills to `currentStock` and resets `lowStockNotified` to false. |
| `RefillStockUseCaseTest` | `refill_rejectsNonPositivePillCounts` | Adding 0 or negative pills returns failure with `IllegalArgumentException`. |
| `RefillStockUseCaseTest` | `refill_returnsFailureWhenMedicineNotFound` | Attempting to refill a non-existent medicine returns failure. |
| `StockCalculatorTest` | `daily_frequency_detects_low_stock_below_3_days_threshold` | Asserts exact boundary (6 pills not low, 5 pills low for 2 pills/day). |
| `StockCalculatorTest` | `zero_stock_is_always_low` | 0 stock always evaluates to low stock. |

---

## 3. Manual Emulator Verification Steps (Section C)

> [!NOTE]
> The steps below are manual verification instructions to be performed interactively on an Android emulator or physical device.

### Scenario: Setting Medicine with 5 Pills & Verifying Until Low Stock
1. **Prepare Medicine**:
   - In MediTrack, tap **Medicines** $\rightarrow$ tap **+ Add Medicine** (or edit an existing medicine).
   - Name: `Amoxicillin`
   - Dosage: `500mg`
   - Frequency: 2 times daily (8:00 AM, 8:00 PM), 1 pill per dose $\rightarrow$ Daily rate = 2.0 pills/day.
   - Low stock threshold = $2.0 \times 3 \text{ days} = 6.0 \text{ pills}$. (Low when $< 6.0$).
   - Set Initial Stock = `6 pills`.
   - Save medicine.
2. **First Dose Verification (Stock: 6 $\rightarrow$ 5)**:
   - On Home screen, tap **Take Dose** on the scheduled Amoxicillin dose.
   - In debug builds with Fake detector, verify the dose.
   - Dose transitions to `TAKEN`.
   - Current stock drops from `6` to `5` pills ($5 < 6.0 \rightarrow$ **crosses into Low Stock**).
   - **Expected Result**:
     - Local notification appears: `"Amoxicillin is running low."`
     - If caregiver connected: row added to `notification_outbox` with `type = LOW_STOCK`.
     - Low stock amber banner appears on Home and Medicine screens: `"Low Stock (5 pills remaining) — Refill soon"`.
3. **Second Dose Verification (Stock: 5 $\rightarrow$ 4 - No Spam)**:
   - Verify the next dose of Amoxicillin.
   - Stock drops from `5` to `4` pills.
   - **Expected Result**:
     - No duplicate local notification is posted.
     - No duplicate outbox row is enqueued.
     - Stock count updates to `4 pills remaining`.
4. **Refill Action**:
   - Go to **Medicines** $\rightarrow$ find Amoxicillin $\rightarrow$ tap **Refill**.
   - Select `+30` chips (or enter custom amount).
   - Tap **Confirm Refill**.
   - **Expected Result**:
     - Current stock becomes `34 pills`.
     - Low stock warning banner disappears.
     - `lowStockNotified` is reset to `false`.
5. **Out of Stock Banner**:
   - For a medicine with 1 pill remaining, verify dose.
   - Stock reaches `0`.
   - **Expected Result**:
     - Persistent red banner appears: `"Out of Stock (0 pills remaining) — Refill required"`.

---

## 4. Architectural Proof: No Store Searches or Email Sending (Section D)

Per the Stage 9 specification, MediTrack does not implement store locators, commercial pharmacy integrations, or email sending.

### Static Code Analysis (`grep` verification)
Command:
```bash
git grep -i "store" app/src/main
```
Result: Matches only standard data storage concepts (e.g. `Room`, `filesDir`, `DataStore`, `AppDatabase`). Zero retail/store search APIs.

Command:
```bash
git grep -i "mail" app/src/main
```
Result: Matches only `User.email` property in domain/auth entities for user identity. Zero email transmission clients (`javax.mail`, SendGrid, SMTP, etc.).
