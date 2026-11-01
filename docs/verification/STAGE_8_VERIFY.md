# Stage 8 Verification Pack – Medication History & Calendar

## 1. Overview
Stage 8 delivers comprehensive, read-only audit views over the patient's dose events with adherence metrics and calendar-based compliance tracking:
1. **Medication History Screen (`HistoryScreen.kt`)**:
   - Filterable by time range: **Today**, **Past 7 Days (Week)**, **Past 30 Days (Month)**.
   - **Adherence summary card**: displays adherence percentage (`92% (4 late)`) and counts for Taken, Missed, Skipped, and Unverified.
   - Itemized dose records displaying medicine name, dosage, scheduled time, intake time, status chip, and proof photo indicator.
   - Dose detail dialog with full timestamps, notes (such as unverified intake warnings), and proof photo loaded from app-private storage via Coil.
2. **Medication Calendar Screen (`CalendarScreen.kt`)**:
   - Monthly grid with per-day color-coded status dots.
   - Day selection displaying that day's scheduled doses, dosage, food instructions, and intake statuses.
   - Direct action for missed doses: "I took this late" button if within late entry window, or "Locked" indicator if expired.
   - Medicine detail dialog with "Back to Calendar" and "Back to Home" navigation.
3. **Debug Seeder Tool**:
   - One-tap button in Debug Tools to generate 30 days (50 doses) of deterministic sample history to verify adherence metrics on device.

---

## 2. Status Mapping Table (Section D)

The following table maps every possible dose status to its visual chip representation, color coding, and behavior across History and Calendar:

| Status / Condition | History Chip Label | Calendar Status Chip | Calendar Day Dot Color | Adherence Calculation | Proof Photo Display |
|---|---|---|---|---|---|
| `TAKEN` (on time) | `Taken` (Green) | `Taken` (Green) | **Green** (if all taken) | Counted in `verifiedTaken` | Yes (if captured) |
| `TAKEN_LATE` (late verification) | `Taken Late` (Orange) | `Taken Late` (Orange) | **Orange** | Counted in `verifiedTaken` | Yes |
| `MISSED` (unresolved) | `Missed` (Red) | `Missed` (Red) | **Red** | Counted in `closedDoses` denominator only | No |
| `MISSED` + `manualUnverified` | `Reported without photo` (Amber) | `Reported without photo` (Amber) | **Red** | Counted in denominator only (**never** in numerator) | No |
| `SKIPPED` | `Skipped` (Grey) | `Skipped` (Grey) | **Grey** (if all skipped/none taken) | Counted in `closedDoses` denominator only | No |
| `UNVERIFIED` | `Unverified` (Orange) | `Unverified` (Orange) | **Orange** | Counted in `closedDoses` denominator only | Yes |
| `PENDING` | `Pending` (Blue) | `Pending` (Blue) | **Grey** (not closed) | Not counted in adherence | No |

---

## 3. Seeded Data Verification Table (Section C)

Tapping **"Seed 30 Days of Sample History"** in Debug Tools generates 50 closed doses with known distributions across the past 25 days:

| Metric | Expected Value | Formula / Source |
|---|---|---|
| **Total Closed Doses** | `50` | `verifiedTaken + missed + skipped + unverified` |
| **Taken (On-Time)** | `42` | `count(status == TAKEN)` |
| **Taken (Late)** | `4` | `count(status == TAKEN_LATE)` |
| **Missed** | `4` (including 1 manual unverified) | `count(status == MISSED)` |
| **Skipped** | `0` | `count(status == SKIPPED)` |
| **Unverified Closed** | `0` | `count(status == UNVERIFIED)` |
| **Verified Taken** | `46` | `takenOnTime + takenLate` ($42 + 4 = 46$) |
| **Adherence Percentage** | `92%` | $46 / 50 \times 100 = 92.0\%$ |
| **Summary Display Text** | `92% (4 late)` | `"$percentageFormatted ($takenLate late)"` |

---

## 4. Manual Verification Steps (On Real Device / Emulator)

### Scenario 1: Seed Data and Verify History UI
1. Open **MediTrack** $\rightarrow$ tap the top-right Bug icon (or long-press the title "MediTrack") to open **Debug Tools**.
2. Scroll to the **History & Calendar (Stage 8)** section and tap **"Seed 30 Days of Sample Data"**.
3. Confirm toast/status: `"Seeded 50 sample doses (42 taken, 4 late, 4 missed -> 92% adherence)"`.
4. Return to Home $\rightarrow$ tap **History** in Quick Actions.
5. Switch filter chips:
   - Tap **"Today"**: shows doses scheduled today.
   - Tap **"Past 7 Days"**: shows doses for the past week with updated adherence card.
   - Tap **"Past 30 Days"**: verify adherence card displays:
     - Adherence: `92% (4 late)`
     - Taken: `46`
     - Missed: `4`
     - Skipped: `0`
6. Tap any dose record in the list $\rightarrow$ verify detail dialog appears with scheduled time, intake time, and status.

### Scenario 2: Verify Calendar Screen & Day Dot Colors
1. From Home, tap **Calendar** in Quick Actions.
2. Verify Month view:
   - Days with only taken doses display a **Green** dot.
   - Days with late or unverified doses display an **Orange** dot.
   - Days with missed doses display a **Red** dot.
   - Days with no doses display a **Grey** dot.
3. Tap on a day with a **Red** dot:
   - The selected day's dose list appears below the calendar.
   - If the missed dose is recent (< 24 hours), verify the button **"I took this late"** is displayed.
   - If the missed dose is older (> 24 hours), verify the lock icon and **"Locked"** indicator are displayed.
4. Tap **"I took this late"**:
   - Opens the verification camera screen in Late Entry mode.
   - Verify that completing verification updates the dose to `Taken Late` with an orange chip and recalculates adherence.
5. Tap on any dose card:
   - Verify the detail dialog opens with dose details and a **"Back to Calendar"** button.
   - Tap **"Back to Calendar"** $\rightarrow$ returns smoothly to the month view.

---

## 5. Automated Unit Tests (Section B)

All range queries, adherence calculation, and day color rules are rigorously tested:
- `HistoryViewModelTest.kt`:
  - `empty state returns no items and dash adherence`: passes empty state validation.
  - `setting time range triggers range queries for Today Week and Month`: verifies exact timestamp boundaries for all 3 range selections.
  - `seeded data produces exactly 92 percent adherence with 4 late and 4 missed`: mathematically asserts formula invariants against seeded doses.
  - `selecting an item updates selectedItem state`: tests item selection and detail formatting.
- `CalendarViewModelTest.kt`:
  - `computeDayStatus accurately maps status according to priority rules`: tests green/orange/red/grey day dot logic.
  - `month pagination changes current month correctly`: tests month increment/decrement.
  - `selecting date filters doses for that day and computes late entry lock state`: tests date filtering and `canLogLate` vs `isLocked` calculation.
