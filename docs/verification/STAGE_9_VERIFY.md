# Stage 9 Verification Pack – Stock / Refill Management

## 1. Overview
Stage 9 delivers intelligent pill stock tracking, automatic threshold evaluation, low-stock notifications, caregiver outbox synchronization, and refilling capabilities:
1. **Deterministic Decrement Rule**:
   - Pill stock decrements **strictly and only** when a dose is verified (`RecordVerifiedDose` on-time or late verification).
   - Skipped, missed, unverified, and manual unverified dose events **never** decrement stock.
2. **Low-Stock Evaluation & Alerting**:
   - Evaluated after every decrement using `StockCalculator.isLow(remaining, pillsPerDay, LOW_STOCK_DAYS_THRESHOLD)`.
   - Fires a notification **only once** on the transition from not-low to low (prevents repeated notification spam on subsequent doses).
   - Enqueues a `LOW_STOCK` event in `NotificationOutbox` if a caregiver is connected.
   - If no caregiver is connected, alerts the patient locally and prompts them to connect a caregiver.
3. **Refill Actions & Banners**:
   - Out of stock (0 pills remaining) displays a persistent red warning banner on the Home dashboard and Medicine list.
   - Low stock displays an amber warning banner.
   - "Refill" dialog on Medicine card allows adding pills (+10, +30, +60, +100, or custom amount), which updates `currentStock` and resets the `lowStockNotified` state so future depletions alert again.
4. **Strict Scope Compliance**:
   - Zero store/pharmacy searches and zero email sending anywhere in the codebase.

---

## 2. Unit Tests Summary (Section B)

All business rules for stock management, threshold evaluation, and single-alert transitions are covered by automated unit tests:

| Test Class | Test Name | Invariant Tested |
|---|---|---|
| `ProcessStockDecrementUseCaseTest` | `decrement_decreasesStock_andEvaluatesLowStock` | Normal decrement when stock remains above threshold; no alert, no outbox event. |
| `ProcessStockDecrementUseCaseTest` | `decrement_whenCrossingThreshold_withCaregiver_triggersSingleAlertAndOutbox` | When stock drops below $pillsPerDay \times 3$, triggers patient notification and enqueues `LOW_STOCK` outbox row. |
| `ProcessStockDecrementUseCaseTest` | `decrement_whenAlreadyLow_doesNotSpamAlertOrOutbox` | Subsequent decrements while already in low state do NOT fire another alert or enqueue duplicate outbox event. |
| `ProcessStockDecrementUseCaseTest` | `decrement_whenCrossingThreshold_withoutCaregiver_triggersAlertAndCaregiverPrompt` | When crossing threshold without a connected caregiver, local alert fires and `promptCaregiver` is true without queuing outbox row. |
| `ProcessStockDecrementUseCaseTest` | `decrement_clampsAtZero` | Stock does not drop below 0 when decrement exceeds available stock. |
| `RecordVerifiedDoseUseCaseTest` | `decrementsStockAndEnqueuesOutboxWhenLow` | Integration test showing `RecordVerifiedDose` invokes stock decrement and triggers low stock alert when threshold reached. |
| `RecordVerifiedDoseUseCaseTest` | `unverifiedDoseDoesNotDecrementStock` | Verifies that unverified doses do not decrement stock. |
| `RefillStockUseCaseTest` | `refill_increasesCurrentStock_andResetsLowStockNotified` | Refill adds pills to `currentStock` and resets `lowStockNotified` to false. |
| `RefillStockUseCaseTest` | `refill_rejectsNonPositivePillCounts` | Adding 0 or negative pills returns failure with `IllegalArgumentException`. |
| `RefillStockUseCaseTest` | `refill_returnsFailureWhenMedicineNotFound` | Attempting to refill a non-existent medicine returns failure. |

---

## 3. Manual Verification Steps (Section C)

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
   - Current stock drops from `6` to `5` pills ($5 < 6.0 \rightarrow$ **becomes low**).
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
