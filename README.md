# MediTrack – Staged Build Guide (Android)

This document is an execution plan for an AI coding agent. It tells you **what the finished app does**, **what the rules are**, and **how to build it one stage at a time**.

---

## 0. HOW TO USE THIS DOCUMENT (READ FIRST – MANDATORY PROTOCOL)

You are an AI coding agent. Follow this protocol exactly.

1. **Read this whole file once** before writing any code. Sections 1–5 apply to every stage.
2. **Work on ONE stage at a time.** The human will tell you which one: *"Execute Stage N"*. If they don't say, start with the lowest stage not marked DONE in `PROGRESS.md`.
3. **Never start a stage until every earlier stage is DONE.** If a prerequisite is missing, stop and say so.
4. **Never build things from a later stage**, even if it seems convenient. Use the stubs/interfaces this document tells you to create.
5. **Stay inside the stage's scope.** Do not refactor unrelated code, rename things, or add features not listed.
6. **At the end of each stage you must:**
   - Build the project (`./gradlew assembleDebug`) and run tests (`./gradlew testDebugUnitTest`). Both must pass.
   - Check every item in the stage's **Acceptance Criteria**.
   - **Make sure the project opens, syncs and RUNS in Android Studio** (section 5.4). Every stage must leave the app in a state the human can press ▶ Run on and see working. A stage that builds on the command line but crashes on launch is NOT done.
   - **Generate the Verification Pack** (section 5.3) so the human can verify your work without reading the code: automated tests, a plain-language explanation, and a step-by-step manual test script. **A stage without a Verification Pack is NOT done.**
   - Append an entry to `PROGRESS.md` (format in section 5).
   - Reply with the **Stage Report** (format in section 5) and then **STOP**. Wait for the human to say "Proceed to Stage N+1".
7. **If something is ambiguous, unclear, or impossible**, do not guess silently. Pick the simplest option consistent with this document, record it under "Decisions / Assumptions" in `PROGRESS.md`, and mention it in the Stage Report. Only ask the human a question if you are truly blocked.
8. **Human-only steps** (marked 🧑 HUMAN) cannot be done by you – e.g. creating a Firebase project, supplying `google-services.json`, or collecting real photos. When you reach one, write clear instructions for the human in the Stage Report and continue with whatever you can do without it (use stubs/fakes).
9. **Do not invent features, screens, or permissions** that are not in this document.
10. **Use the links** in each stage's "Reference links" and in Appendix E. The links are official entry points; if one does not open or the page has moved, **say so in the Stage Report** and search for the current page – never guess an API from memory when the documentation is available.

---

## 1. PRODUCT OVERVIEW

**MediTrack** is an Android app that helps a patient take medicines on time, **proves each dose was taken using a photo of the medicine strip**, and keeps a **caregiver** informed.

### 1.1 Core idea (the most important rule)

> A dose is recorded as **TAKEN** only if the patient sends a **proper photo of the medicine strip** showing the pill(s) have been **removed**. A blurry/distorted photo, or a photo where pills are still in the strip, is **rejected** and the dose is **not** recorded.

### 1.2 Full feature list (the finished app)

| # | Feature | Summary |
|---|---------|---------|
| F1 | Account | Create account / login (email or phone, password, OTP). Existing-user check. |
| F2 | Permission setup | Notifications, Alarms, Camera (**required**), Photos/Files (optional), Contacts/Phone (optional), Internet. |
| F3 | Home dashboard | Today's medicines, upcoming doses, missed doses, quick actions. |
| F4 | Add medicine | Name, dosage, number of pills, frequency, start date, end date, before/after food, instructions, optional reminder. |
| F5 | Medicine reminder | Notification + alarm at scheduled time. Actions: **Taken / Snooze (15, 30, custom) / Skip**. No response → Missed. |
| F6 | **Photo dose verification** | Tapping **Taken** opens live camera → patient photographs the strip → image checks → record dose only if proper. Up to 3 attempts. |
| F7 | Late dose entry | A dose marked Missed can later be recovered with a verified photo → status **Taken Late**. |
| F8 | Medication history | Taken / Missed / Skipped / Delayed records, per Today/Week/Month, with adherence %. |
| F9 | Medication calendar | Pick a date → see that day's medicines and statuses. |
| F10 | Stock / refill | Track pill count; decrease **only on verified doses**; when low, notify caregiver. |
| F11 | Caregiver mode | Invite a caregiver; caregiver gets notifications (missed dose, low stock, unverified dose, late-taken). |
| F12 | Scan medicine / prescription | Camera + OCR to pre-fill Add Medicine. |
| F13 | Profile / settings | Edit info, reminder & notification settings, privacy, permissions, logout, delete data. |
| F14 | **Doctor mode** | A doctor links to a patient (patient consents), **proposes / changes / stops medicines** for them, and **monitors** doses, adherence and proof photos. Central principle: **The doctor proposes, the patient decides, and MediTrack activates the medicine only after patient acceptance.** Doctor-prescribed medicines still need a verified live photo for every dose. |

### 1.3 Explicitly OUT OF SCOPE (do NOT build)

- ❌ Finding nearby medical stores / pharmacies.
- ❌ Emailing or contacting stores, ordering medicine, online ordering.
- ❌ **Location permission** and **Email permission** – do not request or declare them.
- ❌ Selecting dose photos from the gallery (verification photos must be **live camera only**).
- ❌ Any claim that the app confirms the patient *swallowed* the pill. It only confirms the pill left the strip.
- ❌ **Verification of medical licences, registration numbers, medical certificates, or qualification verification** (selecting the Doctor role merely identifies the user's app role; MediTrack does not certify doctors; the patient is responsible for independently choosing a qualified healthcare professional).

> Low stock behaviour is: **notify the caregiver** (and show an alert to the patient). Nothing else.

---

## 2. TECH STACK & GLOBAL RULES

### 2.1 Stack (use exactly this unless blocked)

| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository pattern, single Gradle module `app`, **package-by-feature** |
| DI | Hilt |
| Navigation | Navigation Compose |
| Local DB | Room (source of truth, app works offline) |
| Preferences | DataStore |
| Async | Kotlin Coroutines + Flow |
| Background | WorkManager (jobs), AlarmManager (exact dose alarms) |
| Camera | CameraX |
| Image analysis | Pure-Kotlin quality gate, TensorFlow Lite / LiteRT detector, ML Kit Text Recognition (bundled/on-device) |
| Backend | Firebase: Auth, Firestore, Storage, Cloud Messaging (FCM), Cloud Functions (TypeScript) |
| Images | Coil |
| Testing | JUnit4, MockK, Turbine, Robolectric (only where needed) |

Use the **latest stable** versions, managed in `gradle/libs.versions.toml` (version catalog).

### 2.2 App identity

- Application ID / package: `com.meditrack.app`
- `minSdk = 26`, `targetSdk` and `compileSdk` = latest stable required by Google Play.
- Light and dark theme supported.

### 2.3 Global rules (apply to EVERY stage)

- **Offline-first:** UI reads from Room. Network sync is a background concern.
- **All times stored as UTC epoch millis**; displayed in device local time zone.
- **No hard-coded user-visible strings** – use `strings.xml`.
- **All tunable numbers** live in one file: `core/config/AppConfig.kt` (see 2.4). No magic numbers elsewhere.
- **Business logic lives in pure Kotlin classes** (no Android imports) so it is unit-testable. Android code is a thin wrapper.
- **No secrets in source.** `google-services.json` is supplied by the human and is git-ignored.
- **Accessibility:** content descriptions on icons/buttons, minimum 48dp touch targets, readable text sizes (patients may be elderly).
- **Privacy:** health data and photos are sensitive. Never log medicine names, photos, or personal info to Logcat in release builds.
- **Every ViewModel exposes a single `StateFlow<UiState>`** and handles events via functions. Keep composables stateless.

### 2.4 `AppConfig.kt` constants (create in Stage 0, use everywhere)

```kotlin
object AppConfig {
    // Dose window
    const val DOSE_WINDOW_MINUTES = 60            // time after scheduled time before a dose becomes MISSED
    val SNOOZE_PRESETS_MINUTES = listOf(15, 30)   // plus custom (list cannot be const)
    const val MAX_SNOOZES_PER_DOSE = 3

    // Verification
    const val MAX_VERIFICATION_ATTEMPTS = 3
    const val LATE_DOSE_MAX_HOURS = 24            // late entry allowed until next scheduled dose OR this many hours, whichever is EARLIER

    // Image quality gate (tunable)
    const val QUALITY_ANALYSIS_MAX_SIDE_PX = 640
    const val BLUR_LAPLACIAN_VARIANCE_MIN = 100.0
    const val BRIGHTNESS_MEAN_MIN = 40.0          // 0..255
    const val BRIGHTNESS_MEAN_MAX = 220.0
    const val GLARE_PIXEL_FRACTION_MAX = 0.08     // fraction of near-white pixels

    // Detector
    const val DETECTOR_MIN_CONFIDENCE = 0.5f

    // Duplicate photo detection
    const val PHASH_MAX_HAMMING_DISTANCE = 5

    // Medicine-name match
    const val NAME_MATCH_MIN_SIMILARITY = 0.6

    // Stock
    const val LOW_STOCK_DAYS_THRESHOLD = 3        // low when remaining pills < pillsPerDay * this
}
```


---

## 3. DOMAIN MODEL (shared by all stages)

### 3.1 Entities (Room)

- **User**: `id`, `name`, `email`, `phone`, `role` (`PATIENT` | `CAREGIVER` | `DOCTOR`), `createdAt`. Selecting `DOCTOR` identifies the user's app role; MediTrack does not verify medical licences, registration certificates, or qualifications.
- **Medicine**: `id`, `name`, `dosageText`, `pillsPerDose` (Int ≥ 1), `frequencyType`, `timesOfDay` (list of local times), `startDate`, `endDate?`, `foodRelation` (`BEFORE` | `AFTER` | `ANY`), `instructions`, `reminderEnabled`, `initialStock`, `currentStock`, `baselineEmptyCells` (Int, from enrollment photo), `baselinePhotoPath?`, `prescribedByDoctorUid?`, `prescribedByDoctorName?`, `stoppedByDoctor` (Boolean, default false), `createdAt`.
- **DoseEvent**: one row per scheduled dose. `id`, `medicineId`, `scheduledAt`, `windowEndsAt`, `status` (see 3.2), `takenAt?`, `verifiedPhotoPath?`, `verificationAttempts`, `emptyCellsAfter?`, `snoozeCount`, `lateEntry` (Boolean), `manualUnverified` (Boolean), `updatedAt`.
- **VerificationAttempt**: `id`, `doseEventId`, `photoPath`, `result` (`PASSED` | `REJECTED`), `rejectReason?`, `perceptualHash`, `createdAt`.
- **CaregiverLink**: `id`, `patientId`, `caregiverName`, `caregiverContact`, `relationship`, `status` (`PENDING` | `ACCEPTED`), `caregiverUid?`.
- **DoctorLink**: `id`, `patientId`, `doctorUid?`, `doctorName`, `doctorContact`, `status` (`PENDING` | `ACCEPTED` | `REVOKED`), `invitationCode?`, `expiresAt?`, `createdAt`, `updatedAt`. A doctor sees **nothing** about a patient unless status is `ACCEPTED`. Revocation immediately terminates doctor access.
- **MedicineProposal**: `id`, `patientId`, `doctorUid`, `type` (`ADD` | `EDIT` | `STOP`), `medicineId?`, `payloadJson` (the medicine fields being proposed), `status` (`PENDING` | `ACCEPTED` | `DECLINED` | `CLARIFICATION_REQUESTED` | `SUPERSEDED`), `revision` (Int), `clarificationNote?`, `createdAt`, `resolvedAt?`. **A doctor never writes a `Medicine` row directly – only a proposal.** The patient's app turns an accepted proposal into a real change. Revisions create a new version requiring fresh acceptance.
- **NotificationOutbox**: `id`, `type` (see 3.4), `payloadJson`, `createdAt`, `delivered` (Boolean). **Any feature that must notify a caregiver or doctor/patient only writes a row here.** A later stage delivers them.

### 3.2 Dose status values and allowed transitions

`PENDING` → `TAKEN` (verified photo inside window)
`PENDING` → `SKIPPED` (patient taps Skip)
`PENDING` → `UNVERIFIED` (3 failed photo attempts, still inside window)
`PENDING` / `UNVERIFIED` → `MISSED` (window ends with no verified photo, or no response)
`MISSED` → `TAKEN_LATE` (verified photo submitted later, within late-entry limit)
`MISSED` → `MISSED` + `manualUnverified = true` (patient reports taking it, no photo; **never counts as taken**)

Any other transition is **illegal** – implement a `DoseStateMachine` that throws/returns an error on illegal transitions and unit-test every legal and illegal case.

### 3.3 Verification rejection reasons (enum `RejectReason`)

`BLURRY`, `TOO_DARK`, `TOO_BRIGHT`, `GLARE`, `STRIP_NOT_FULLY_VISIBLE`, `WRONG_MEDICINE`, `PILLS_NOT_REMOVED`, `WRONG_PILL_COUNT`, `DUPLICATE_PHOTO`.
Each maps to a clear, friendly string shown to the patient (e.g. BLURRY → "The photo is blurry. Hold steady and try again.").

### 3.4 Notification types (enum `NotifType`)

`MISSED_DOSE`, `LOW_STOCK`, `DOSE_UNVERIFIED`, `DOSE_TAKEN_LATE`, `CAREGIVER_INVITE`, `DOCTOR_INVITE_RECEIVED`, `DOCTOR_INVITE_ACCEPTED`, `DOCTOR_INVITE_DECLINED`, `DOCTOR_INVITE_REVOKED`, `PRESCRIPTION_PROPOSAL_RECEIVED`, `PRESCRIPTION_ACCEPTED`, `PRESCRIPTION_DECLINED`, `PRESCRIPTION_CLARIFICATION_REQUESTED`, `PRESCRIPTION_REVISED`.

### 3.5 Adherence formula

```
verifiedTaken = count(TAKEN) + count(TAKEN_LATE)
closedDoses   = verifiedTaken + count(MISSED) + count(SKIPPED) + count(UNVERIFIED that are closed)
adherence %   = verifiedTaken / closedDoses * 100      (0 closed doses → show "—")
```
Display as `92% (4 late)`. `manualUnverified` doses count as MISSED and are shown separately as "reported without photo".

### 3.6 How the app tracks pills taken (the "ledger" rule)

The app does **not** compare today's photo to yesterday's photo picture-by-picture. It keeps a running count of **how many blister cells were empty the last time a dose was verified**. This count is the *ledger*.

- `Medicine.baselineEmptyCells` = empty cells in the **enrollment photo** (taken when the strip is added; usually 0 for a new strip).
- `DoseEvent.emptyCellsAfter` = empty cells counted in the photo that **verified** that dose.
- `previousEmpty` for a new dose = `emptyCellsAfter` of the **most recent verified dose** (TAKEN or TAKEN_LATE) of that medicine; if none yet, use `baselineEmptyCells`.

**Rule:** a dose passes only if `newEmpty − previousEmpty == pillsPerDose`.

| Result of `newEmpty − previousEmpty` | Meaning | Outcome |
|---|---|---|
| exactly `pillsPerDose` | the right number of new pills were removed | **PASS** → dose TAKEN, ledger updated to `newEmpty` |
| 0 | nothing new removed (same strip state, or an old photo) | reject `PILLS_NOT_REMOVED` |
| not equal (fewer or more) | wrong amount removed (e.g. several pills pushed out at once) | reject `WRONG_PILL_COUNT` |

**Worked example** (strip of 10 pills, 1 pill per dose, 1 dose per day):

| Day | Event | Empty cells in photo | previousEmpty | Difference | Result |
|---|---|---|---|---|---|
| Day 0 | Enrollment photo (new strip) | 0 | – | – | `baselineEmptyCells = 0` |
| Day 1 | Patient taps Taken, sends photo | 1 | 0 | 1 | ✅ TAKEN, ledger = 1 |
| Day 2 | Photo | 2 | 1 | 1 | ✅ TAKEN, ledger = 2 |
| Day 3 | Patient sends the *same photo as Day 2* | 2 | 2 | 0 | ❌ `PILLS_NOT_REMOVED` (also caught earlier by the duplicate-photo hash check) |
| Day 3 | No valid photo by end of window | – | – | – | ⏰ MISSED (ledger stays 2) |
| Day 3 (late) | Patient later sends a good photo | 3 | 2 | 1 | ✅ TAKEN_LATE, ledger = 3 |
| Day 4 | Photo | 4 | 3 | 1 | ✅ TAKEN, ledger = 4 |

Key properties:
- A **missed** day does not change the ledger, so the next verified dose is still compared against the last *verified* state.
- The ledger moves **only** on a passing verification – never on Skip, Missed, Unverified or manual entry.
- When a **new strip** is started (refill), the patient takes a new enrollment photo; the ledger resets to that photo's empty count.
- The check is **count-based** in the first version. (Optional later hardening: also require that the newly empty cell was FULL in the previous verified photo, by storing normalised cell positions.)
- Limitation to state to users: the app proves a pill **left the strip**, not that it was swallowed.

---

## 4. USER FLOW REFERENCE (final flow)

```
START → Splash → Existing user?
   YES → Login | NO → Create account → Login
→ Permission setup → HOME DASHBOARD → 10 branches:

1 Add Medicine  2 Medicine Reminder  3 Pill Intake Verification (Photo)
4 Medication History  5 Stock/Refill  6 Scan Medicine/Prescription
7 Medication Calendar  8 Caregiver Mode  9 Profile/Settings
10 Doctor Mode (patient: link/revoke a doctor, review proposals; doctor: patient list, prescribe, monitor)
```

**Branch 2 → 3 (the key path):**
Scheduled time → notification/alarm → user response:
- **Taken** → Branch 3 (photo). Dose is **not** recorded yet.
- **Snooze** → reschedule (15/30/custom) → notify again.
- **Skip** → mark Skipped.
- **No response / no verified photo by end of window** → Missed → missed-dose alert → caregiver notified if connected.

**Branch 3:**
Camera permission? (no → ask; if denied, dose stays PENDING) → open live camera → capture strip photo → quality gate → detect cells → compare with baseline → match medicine → **Image proper?**
- YES → record dose TAKEN, save photo as proof, update history, decrease stock.
- NO → reject with reason, dose not recorded → retries left? YES → retake; NO → dose UNVERIFIED, notify caregiver "Dose could not be verified."

**Branch 5 (stock):** every verified dose decreases stock → if low: show alert to patient AND notify caregiver. If no caregiver connected: prompt patient to add one (Branch 8).

---

## 5. REPORTING FORMATS

### 5.1 `PROGRESS.md` (create in Stage 0, append after each stage)

```markdown
## Stage N – <title> – DONE (<date>)
- Summary: <2-4 lines>
- Files added/changed: <list of key files>
- Tests added: <list>
- Verification Pack: docs/verification/STAGE_N_VERIFY.md
- Decisions / Assumptions: <list or "none">
- Known limitations / TODO for later stages: <list or "none">
```

### 5.2 Stage Report (your final reply for each stage)

1. **What I built** (short).
2. **Acceptance criteria checklist** – each item ✅ or ❌ with a one-line note.
3. **Build + test result** (pass/fail, counts, and the exact commands to re-run them).
4. **Plain-language explanation** (the "How it works" part of the Verification Pack, summarised in ≤ 10 lines).
5. **How to verify it yourself** – the path to `docs/verification/STAGE_N_VERIFY.md` and the 3–5 most important manual checks.
6. **Android Studio run check** – exactly what you ran and what the human should see when they press ▶ Run (see 5.4). State honestly which parts **you executed** and which parts **the human must execute**.
7. **Decisions/assumptions made.**
8. **🧑 Human actions needed** (if any).
9. **"Stage N complete. Waiting for 'Proceed to Stage N+1'."**


### 5.3 Verification Pack (MANDATORY for every stage)

The human must be able to **check your work without reading code**. At the end of every stage, create the file `docs/verification/STAGE_N_VERIFY.md` (N = stage number) containing **all six parts below**. Be concrete – no vague statements like "works as expected".

**Part A – Plain-language explanation ("How it works")**
- Written for a non-programmer. Max ~1 page.
- What was built, what it does, and **how it connects to the previous stage**.
- A simple flow in words or a small ASCII/Mermaid diagram of what happens (e.g. "alarm fires → notification → user taps Taken → camera opens…").
- For every important rule, explain **why** it exists (e.g. "stock only drops after a verified photo, so a missed dose can never reduce stock").
- List **what is NOT done yet** (stubs, fakes, later stages) so nothing is mistaken for finished.

**Part B – Automated tests (generated by you)**
- Write tests for **every** rule and edge case the stage introduces – not just the happy path. Include boundary values and illegal/failure cases.
- Test names must read like sentences (e.g. `rejects_photo_when_no_pill_removed`, `dose_cannot_become_taken_without_passing_verification`).
- In the pack, give a **table**: `Test name | What it proves | Command to run it`.
- Give the exact commands, e.g. `./gradlew testDebugUnitTest --tests "*DoseStateMachineTest"`, and the expected result ("all N tests pass").
- Include at least one **negative test** per stage that proves a forbidden thing cannot happen (e.g. "a dose cannot be TAKEN without a passing verification", "no location permission is declared").
- Where logic depends on time, use a fake clock so tests are deterministic.

**Part C – Manual test script (step-by-step for the human)**
- Numbered steps a person can follow on an emulator/phone, each with an **Expected result**. Format:
  `Step 3: Tap "Snooze 15 min" → Expected: notification disappears and reappears after 15 min; dose still Pending.`
- Cover the happy path **and** at least 3 failure/edge scenarios for the stage.
- Include any **debug tools** you added (seed-data button, fake detector "force result" menu, fake clock) and how to use them to trigger hard-to-reach situations quickly (e.g. "force the detector to return 'no pills removed'").
- State what test data/accounts to use (e.g. fake OTP `123456`).

**Part D – Evidence checklist**
- A checkbox list the human ticks off, mapped 1-to-1 to the stage's Acceptance Criteria. Next to each item say **how to see it** (which test, which manual step, or which screen).
- Where useful, add **proof commands**, e.g. `grep -ri "ACCESS_FINE_LOCATION" app/src` → expected: no results.

**Part E – Known limitations & risks**
- Anything that is approximate, untested on real devices, assumed, or likely to break (e.g. "alarm reliability not yet tested on Xiaomi devices").
- Any threshold/constant you chose and where to change it.

**Part F – Run in Android Studio (see section 5.4)**
- Exact steps to open, sync and run **this stage's** build, and exactly what appears on screen.
- The screens/paths to tap through to reach this stage's feature, in order.
- The Logcat filter and the log lines that prove the feature ran (e.g. `tag:MediTrack` → `Alarm scheduled for dose 12`).
- Any adb commands useful for this stage (from Appendix D).

**Rules for the pack**
- Tests must **actually run and pass** before you report the stage done. Paste the real result summary (counts), never invent numbers.
- If a test cannot be written (e.g. needs real hardware or a real model), say so explicitly and put the equivalent check into Part C as a manual step instead.
- Never weaken or delete an earlier stage's test to make a new stage pass. If an old test must change, explain why in Part E.
- Keep every earlier stage's tests running: the Stage Report must state the **total** test count and that **all earlier tests still pass** (regression check).
- If you find a bug in an earlier stage, **report it** and fix it with a test, don't hide it.

### 5.4 Android Studio Run Check (MANDATORY for every stage)

The human will open the project in **Android Studio** and press **Run ▶** after every stage. Your job is to make that work first time.

**Requirements – the project must always:**
1. **Open** via *File → Open* on the project root with no manual edits, and **Gradle Sync must finish with zero errors**.
2. Include the **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/*`) committed, so no separate Gradle install is needed.
3. Use only the **JDK bundled with Android Studio** (`Gradle JDK = Embedded JDK`/JBR 17 or newer). Never require a custom JDK path.
4. Have a shared Run configuration named **`app`** that launches `MainActivity` (commit `.idea/runConfigurations/app.xml`, or make sure the default config is auto-created).
5. **Launch without crashing** on an emulator running Android 8.0+ (API 26+) and on a recent emulator (API 34+), reaching the screens that exist at this stage.
6. Never depend on files the human doesn't have. If something is missing (e.g. `google-services.json`, an ML model), the app must **start anyway** using a Fake/graceful message – never crash on startup.
7. Show a visible in-app **version/stage label** in debug builds (e.g. on the Debug Tools screen: "MediTrack – Stage N build") so the human knows which stage they are running.
8. Keep a debug-only **Debug Tools** screen (create in Stage 0, extend each stage) listing every debug helper added so far (seed data, fake clock, fake detector, shorten dose window, trigger alarm now, etc.).
9. Log key events with a single Logcat tag **`MediTrack`** in debug builds (no sensitive data).

**What you (the AI agent) must do before reporting the stage done:**
- Run `./gradlew assembleDebug testDebugUnitTest lint` and report results.
- If an Android SDK/emulator is available in your environment, install and launch the app and report a **screenshot or Logcat excerpt**.
- **If you cannot launch an emulator, say so plainly.** Mark the run check as *"Executed by agent: build + unit tests only. Emulator run: NOT executed – human to run"*. **Never claim you ran the app on a device if you did not.**
- Review your own code for launch-time risks: missing Hilt bindings, missing manifest entries/permissions/receivers, un-registered services, wrong `applicationId`, missing `android:exported` flags (required on Android 12+), and crashes in `Application.onCreate`. Fix them before reporting.

**What the human will do (put these steps in Part F of the pack):**
1. Android Studio → *File → Open* → select the project folder → wait for Gradle Sync.
2. Select an emulator (or USB device) in the device dropdown.
3. Press **Run ▶ (app)**.
4. Follow Part C (manual test script) and compare with the expected results.
5. If it fails, copy the **Logcat** error and the **Build** output back to the agent, who must fix it and re-run the checks.

**Failure handling:** if the human reports a crash or sync error, the agent's first action is to reproduce it from the log, write a **regression test where possible**, fix it, and update the Verification Pack. Do not start the next stage until the run check passes.

---

# STAGES

> Stage dependency overview:
> 0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 10B → 11 → 12 → 13 → 14
> Stage 13 (ML training) can run **in parallel** by a human/data team after Stage 5, but the app uses a fake detector until it lands.

---

## STAGE 0 – Project bootstrap

**Goal:** An empty but correctly structured, building Android project with all tooling in place.

**Tasks**
1. Create an Android project: Kotlin, Compose, Material 3, package `com.meditrack.app`, `minSdk 26`.
2. Set up the Gradle **version catalog** with all libraries from section 2.1 (Compose, Hilt, Navigation, Room + KSP, DataStore, WorkManager, CameraX, ML Kit text recognition, TFLite/LiteRT, Coil, Coroutines, Firebase BoM *declared but plugin applied only if `google-services.json` exists*, JUnit, MockK, Turbine).
3. Create the **package-by-feature structure** under `com.meditrack.app`:
   ```
   core/        (config, di, util, time, ui theme, navigation)
   data/        (db, repository, prefs)
   domain/      (model, usecase, statemachine)
   feature/
     auth/ onboarding/ home/ medicine/ reminder/ verification/
     history/ calendar/ stock/ caregiver/ scan/ settings/
   ```
4. Create `AppConfig.kt` exactly as in section 2.4.
5. Create the Material 3 theme (light + dark) and an empty `MainActivity` with `NavHost` showing a placeholder Home screen.
6. Set up Hilt (`@HiltAndroidApp` application class).
7. Create `.gitignore` (include `google-services.json`, `local.properties`, `*.keystore`).
8. Create `PROGRESS.md` and a short `docs/ARCHITECTURE.md` describing the structure.
9. Add a CI-style check script `scripts/check.sh` that runs `./gradlew assembleDebug testDebugUnitTest lint`.
10. Commit the **Gradle wrapper**; set `gradle.properties` sensibly (AndroidX on, parallel build, enough JVM heap); make the project open with the **Embedded JDK**.
11. Create `docs/RUN_IN_ANDROID_STUDIO.md` (copy and adapt Appendix D) and a shared Run configuration `app`.
12. Create the debug-only **Debug Tools screen** (reachable from a long-press on the app title or a debug menu) showing "MediTrack – Stage 0 build", app version, and an empty list where later stages add their tools. Configure Logcat tag `MediTrack`.

**Acceptance criteria**
- [ ] `./gradlew assembleDebug` succeeds; app launches to a placeholder Home.
- [ ] `./gradlew testDebugUnitTest` succeeds (add one trivial test).
- [ ] Folder structure matches step 3.
- [ ] `AppConfig.kt` exists with all constants.
- [ ] No Firebase plugin error when `google-services.json` is absent.
- [ ] **Opens in Android Studio, Gradle Sync succeeds, and Run ▶ launches the app on an emulator** showing the placeholder Home screen.
- [ ] Debug Tools screen opens in debug builds and is absent in release builds.

**Do NOT:** write any feature logic yet.

**Reference links:**
- Android Studio: https://developer.android.com/studio
- Jetpack Compose: https://developer.android.com/jetpack/compose
- Material 3: https://m3.material.io
- Hilt: https://developer.android.com/training/dependency-injection/hilt-android
- Gradle version catalogs: https://docs.gradle.org/current/userguide/platforms.html

**Verification Pack for this stage** (create `docs/verification/STAGE_0_VERIFY.md` per section 5.3; it must include at least):
- A: explain the folder structure and what each package will hold.
- C: steps to build, launch and see the placeholder Home screen.
- D: proof that `google-services.json` missing does not break the build.

---

## STAGE 1 – Domain model, database & state machine

**Goal:** All data structures, the Room database, repositories, and the dose state machine – fully unit-tested, no UI.

**Tasks**
1. Implement all entities, DAOs and the `AppDatabase` from section 3.1 (use type converters for lists, enums, `LocalTime`).
2. Implement repositories: `MedicineRepository`, `DoseRepository`, `VerificationRepository`, `CaregiverRepository`, `OutboxRepository`. Expose `Flow`s for lists.
3. Implement `DoseStateMachine` (section 3.2). API suggestion: `fun transition(current: DoseStatus, event: DoseEvent): Result<DoseStatus>`.
4. Implement `DoseScheduler` (pure Kotlin): given a `Medicine` and a date range, **generate `DoseEvent`s** (scheduledAt, windowEndsAt = scheduledAt + `DOSE_WINDOW_MINUTES`). Support frequencies: daily at N times, every X hours, specific weekdays. Respect start/end dates.
5. Implement `AdherenceCalculator` (section 3.5).
6. Implement `LateEntryPolicy`: `fun canLogLate(dose, nextScheduledAt, now): Boolean` using `LATE_DOSE_MAX_HOURS` (earlier of next scheduled dose or max hours).
7. Implement `StockCalculator`: `isLow(medicine, pillsPerDay)` using `LOW_STOCK_DAYS_THRESHOLD`.
8. Provide Hilt modules for DB and repositories.

**Acceptance criteria**
- [ ] Unit tests cover: every legal and illegal state transition; scheduler for each frequency type (including DST/time-zone edge and end-date cutoff); adherence math (incl. 0 closed doses, late doses, manual-unverified); late-entry policy boundaries; stock low/not-low.
- [ ] Room schema exported; DAO tests (Robolectric or instrumented-free in-memory) pass.
- [ ] No Android UI code added.

**Do NOT:** build screens, alarms, or network code.

**Reference links:**
- Room: https://developer.android.com/training/data-storage/room
- Kotlin coroutines & Flow: https://kotlinlang.org/docs/coroutines-overview.html

**Verification Pack for this stage** (create `docs/verification/STAGE_1_VERIFY.md` per section 5.3; it must include at least):
- B: tests for every legal and illegal dose transition (list them in the table), each scheduler frequency type, adherence examples with worked numbers, late-entry boundaries.
- A: explain the dose states with a state diagram and a worked adherence example (e.g. 46 taken, 4 late, 4 missed → result).
- C: a debug screen or test-data seeder is NOT required; instead give a small table of sample inputs → expected outputs the human can compare against the tests.

---

## STAGE 2 – Auth, onboarding, permission setup, home shell

**Goal:** Everything from app start up to the Home Dashboard.

**Tasks**
1. Navigation graph: Splash → (Login | Create Account) → Permission Setup → Home.
2. **Auth abstraction:** `interface AuthRepository` with `login`, `createAccount`, `verifyOtp`, `currentUser`, `logout`. Create a **`FakeAuthRepository`** (local, in-memory/Room) now. A Firebase implementation comes in Stage 10. Fields: email/phone, password, role (`PATIENT` | `CAREGIVER` | `DOCTOR`), OTP (fake code `123456` in debug).
3. **Screens:** Splash (checks session), Login (Email/Phone, Password, OTP option), Create Account (Name, Email/Phone, Password, Role picker [Patient / Caregiver / Doctor], Verify OTP → then Login).
   - **No Doctor Registration Number:** Remove any requirement for a Doctor Registration Number, medical licence, or registration certificate verification from account creation. Selecting Doctor merely identifies the user's app role; MediTrack does not verify or certify doctors.
   - **No automatic patient access:** A Doctor account does not automatically grant access to any patient's health information.
   - **Do NOT implement:** doctor invitations, patient linking, or prescription proposals in Stage 2. These belong in Stage 10 / Stage 10B.
4. **Permission Setup screen** (one explanation card per permission, each with a Grant button):
   - Notifications (runtime on Android 13+)
   - Alarms (exact-alarm access: use `SCHEDULE_EXACT_ALARM`; detect `canScheduleExactAlarms()` and deep-link to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`)
   - Camera (**required** – explain "needed to verify each dose"; app must not allow continuing to dose verification without it, but may let the user continue onboarding and re-prompt later)
   - Photos/Files (optional – use the system photo picker; no storage permission needed)
   - Contacts/Phone (optional – for picking a caregiver)
   - Internet (declared in manifest; nothing to request)
   - **Do not** add Location or Email permissions.
5. Home Dashboard (UI shell reading from Room): sections *Today's Medicines*, *Upcoming Doses* (next 24 hours), *Missed Doses* (within 24-hour late window), *Quick Actions* (Add Medicine, Scan, Calendar, History, Caregiver, Profile). Empty states for each.
6. Add a battery-optimisation guidance screen (shown once after permission setup): explains why reminders may fail on some phones and links to the system battery settings.
7. Declare permissions in `AndroidManifest.xml`: `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`, `CAMERA`, `INTERNET`, `USE_FULL_SCREEN_INTENT` (optional), `READ_CONTACTS` (optional). Mark camera hardware `required="false"`.

**Acceptance criteria**
- [ ] New user can create an account (fake) with role selection (Patient, Caregiver, Doctor) without medical licence/registration number verification, log in, go through permission setup, reach Home.
- [ ] Returning user skips straight to Home (session persisted).
- [ ] Denying optional permissions does not block the flow; denying Camera shows a clear "needed for dose verification" message.
- [ ] No Location/Email permission anywhere.
- [ ] ViewModel unit tests for login/create-account validation (no medical registration number validation).

**Do NOT:** implement real backend auth, doctor invitations, patient linking, prescription proposals, medicine screens, or alarms.

**Reference links:**
- Navigation Compose: https://developer.android.com/develop/ui/compose/navigation
- Notification runtime permission: https://developer.android.com/develop/ui/views/notifications/notification-permission
- Exact alarm permission: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- DataStore: https://developer.android.com/topic/libraries/architecture/datastore

**Verification Pack for this stage** (create `docs/verification/STAGE_2_VERIFY.md` per section 5.3; it must include at least):
- C: step-by-step: create account (Patient/Doctor/Caregiver, no registration number required) with fake OTP `123456` → permissions → Home; then kill and reopen app (should skip login); deny Camera and show the message.
- D: proof commands that Location/Email permissions are absent from the manifest and code.
- B: ViewModel validation tests (empty fields, bad email, wrong OTP; confirms no doctor registration number validation).

---

## STAGE 3 – Add medicine (Branch 1)

**Goal:** User can add, edit, list and delete medicines, with optional reminders and a baseline enrollment photo.

**Tasks**
1. Add Medicine screen with fields: Medicine Name, Dosage, Pills **per dose**, Frequency (times of day), Start Date, End Date, Before/After Food, Instructions, **Total pills in strip/pack (initial stock)**, "Set reminder?" toggle → time(s) & frequency.
2. Validation (empty name, pills ≥ 1, end ≥ start, at least one time if reminder on).
3. **Enrollment photo step** (after the form, before save): "Take a photo of the FULL, unopened (or current) strip." Use a simple CameraX capture screen **placeholder** (full quality gate arrives in Stage 5). Save the image to app-private storage and store `baselinePhotoPath`. Allow "Skip for now" (verification will then ask for the enrollment photo the first time).
4. On save: insert `Medicine`, generate upcoming `DoseEvent`s via `DoseScheduler` (generate rolling 7 days ahead; a WorkManager job extends it daily – implement the job now), set `currentStock = initialStock`.
5. Medicines list screen with edit/delete (deleting removes future PENDING doses, keeps history).
6. Flow end states: "SAVE MEDICINE" → "MEDICINE ADDED" → Back to Home.

**Acceptance criteria**
- [x] Adding a medicine creates correct future DoseEvents (verify in a unit test and manually in the app).
- [x] Edit changes future doses only.
- [x] Baseline photo saved and referenced; skipping is allowed.
- [x] Home dashboard shows today's doses from the DB.
- [x] Unit tests for validation and dose generation integration.

**Do NOT:** schedule alarms yet (Stage 4).

**Reference links:**
- Room: https://developer.android.com/training/data-storage/room
- WorkManager: https://developer.android.com/topic/libraries/workmanager
- CameraX: https://developer.android.com/media/camera/camerax

**Verification Pack for this stage** (create `docs/verification/STAGE_3_VERIFY.md` per section 5.3; it must include at least):
- C: add a medicine with 2 times/day for 3 days and show the generated dose list (add a debug 'show generated doses' screen or log view); edit it and show only future doses change.
- B: test that doses respect start/end dates and frequency; validation failures.
- A: explain what the baseline (enrollment) photo is and why it is needed later.

---

## STAGE 4 – Reminders & missed-dose engine (Branch 2)

**Goal:** Reliable alarms/notifications, Snooze/Skip, and automatic Missed handling. "Taken" will route to verification but verification itself is a **stub** until Stage 6.

**Tasks**
1. `AlarmScheduler`: schedule an **exact alarm** per upcoming PENDING dose (`setExactAndAllowWhileIdle` / `setAlarmClock` as appropriate). Cancel/reschedule when a dose changes. Re-schedule everything after device reboot (`BOOT_COMPLETED` receiver) and after app update.
2. `ReminderReceiver` → posts a high-priority notification (channel "Dose reminders") text e.g. "Time to take <Medicine>" with actions **Taken**, **Snooze**, **Skip**. Optionally a full-screen intent.
3. **Snooze:** choose 15 / 30 / custom minutes → reschedule → notify again. Increment `snoozeCount`; cap at `MAX_SNOOZES_PER_DOSE`. Snooze must not push past `windowEndsAt`.
4. **Skip:** transition to `SKIPPED`.
5. **Taken:** open the (stubbed) verification screen route `verification/{doseId}`. Create this route now with a placeholder screen containing a debug button "Simulate verified" which calls the real `RecordVerifiedDose` use case (create that use case now: sets TAKEN, `takenAt`, decreases stock, enqueues nothing else yet). In release builds the placeholder must show "Verification not available yet".
6. **Missed engine:** for every dose, schedule a `WorkManager` one-time job at `windowEndsAt`. When it fires and the dose is still `PENDING`/`UNVERIFIED` → transition to `MISSED`, post a local "Missed dose" notification, and **write a `MISSED_DOSE` row to `NotificationOutbox`** (only if a caregiver link exists with status ACCEPTED; otherwise skip).
7. A safety-net periodic worker (every 15 min) that closes any overdue PENDING doses (covers killed-process cases).
8. Notification channels: "Dose reminders" (high), "Missed doses" (high), "General" (default).

**Acceptance criteria**
- [ ] With the screen off and app closed, an alarm fires at the scheduled time (manual test on a real device/emulator; document how you tested).
- [ ] Snooze reschedules and re-notifies; Skip marks SKIPPED; no action for the window length marks MISSED and posts the missed notification.
- [ ] Reboot reschedules alarms (test via BootReceiver unit/instrumented test or documented manual test).
- [ ] Outbox row is written for missed dose when a caregiver is connected.
- [ ] Unit tests for: snooze limits, missed-engine transitions, scheduler reschedule logic.

**Do NOT:** implement camera verification, caregiver delivery, or history UI.

**Reference links:**
- Schedule alarms: https://developer.android.com/develop/background-work/services/alarms
- WorkManager: https://developer.android.com/topic/libraries/workmanager
- Notification channels: https://developer.android.com/develop/ui/views/notifications/channels
- adb: https://developer.android.com/tools/adb

**Verification Pack for this stage** (create `docs/verification/STAGE_4_VERIFY.md` per section 5.3; it must include at least):
- C: debug tool to **shorten the dose window** (e.g. 1 minute) and a **fake clock / 'trigger alarm now'** button so the human can test alarm, Snooze, Skip and automatic Missed in minutes, not hours.
- C: manual steps for screen-off, app-closed and reboot scenarios, each with expected results.
- B: tests for snooze cap, snooze not passing window end, missed engine, outbox event only when caregiver is connected.

---

## STAGE 5 – Camera capture & image quality gate (Branch 3, part 1)

**Goal:** A reliable live-camera capture screen plus an automatic image-quality check. This catches "distorted" photos before any ML.

**Tasks**
1. `verification/CaptureScreen`: CameraX preview with a **strip-shaped framing guide overlay**, a capture button, flash toggle, and tips ("Lay the strip flat, whole strip visible, good light"). **Live capture only – no gallery button.**
2. Camera permission handling inside this flow: if denied → "Ask permission" → if still denied → show message "Dose stays pending. A photo is needed to record a dose." and return Home.
3. **`ImageQualityGate`** (pure Kotlin, no OpenCV required): downscale to `QUALITY_ANALYSIS_MAX_SIDE_PX`, convert to grayscale, then compute:
   - **Blur:** variance of the Laplacian → reject if `< BLUR_LAPLACIAN_VARIANCE_MIN` (`BLURRY`).
   - **Brightness:** mean luminance → `TOO_DARK` / `TOO_BRIGHT` using min/max constants.
   - **Glare:** fraction of near-white pixels `> GLARE_PIXEL_FRACTION_MAX` → `GLARE`.
   Return `QualityResult(passed, reason?, metrics)`.
4. On quality failure, show the friendly reason and let the user retake immediately **without consuming a verification attempt** (quality failures before analysis are "free"; only photos that reach the detector count as attempts – decide and document this in PROGRESS.md; default = free).
5. Save captured JPEGs to app-private storage (`filesDir/proofs/`), compress to ≤ ~1.5 MB, strip EXIF location (there is none requested, but do not write any).
6. **`PerceptualHasher`** (pHash/dHash, 64-bit) – implemented now, used in Stage 6.
7. **Data-collection mode (debug builds only):** a hidden settings switch that lets a tester save a photo together with a label (strip type, #full cells, #empty cells) to `filesDir/dataset/`, and an export button that zips the folder. Purpose: collect training data for Stage 13. 🧑 HUMAN will use this to gather photos.
8. Replace the Stage 3 enrollment-photo placeholder with this capture screen + quality gate.

**Acceptance criteria**
- [ ] Unit tests with **synthetic bitmaps** (generated in test): sharp image passes; Gaussian-blurred image fails BLURRY; near-black fails TOO_DARK; near-white fails TOO_BRIGHT; image with a large white blob fails GLARE.
- [ ] pHash: identical images → distance 0; slightly re-compressed same image → distance ≤ `PHASH_MAX_HAMMING_DISTANCE`; different images → distance larger.
- [ ] Capture screen works on device; no gallery access path exists.
- [ ] Debug data-collection mode works; release build does not contain it.

**Do NOT:** run any ML model; record doses.

**Reference links:**
- CameraX: https://developer.android.com/media/camera/camerax
- Android emulator camera & controls: https://developer.android.com/studio/run/emulator

**Verification Pack for this stage** (create `docs/verification/STAGE_5_VERIFY.md` per section 5.3; it must include at least):
- B: synthetic-image tests (sharp / blurred / dark / bright / glare) and pHash tests; list the measured metric values and thresholds in the pack.
- C: steps to photograph a sharp strip, a deliberately blurred one, a dark one and one with glare, and what message each should show.
- D: proof that no gallery button exists in the capture flow and that the data-collection mode is absent in release builds.

---

## STAGE 6 – Verification pipeline & Taken integration (Branch 3, part 2)

**Goal:** The complete "photo → decision" pipeline, with a **pluggable detector** (fake now, real model in Stage 13), integrated into the Taken flow.

**Tasks**
1. Define interfaces (pure Kotlin where possible):
   ```kotlin
   enum class CellState { FULL, EMPTY, PARTIAL }
   data class Cell(val box: RectF, val state: CellState, val confidence: Float)
   data class Detection(val cells: List<Cell>, val stripFullyInFrame: Boolean)
   interface StripDetector { suspend fun detect(bitmap: Bitmap): Detection }
   interface MedicineNameReader { suspend fun readText(bitmap: Bitmap): String }   // OCR
   ```
2. Implement **`FakeStripDetector`** (configurable via constructor/test) and a **`TfliteStripDetector` skeleton** that loads `assets/strip_detector.tflite` if present and otherwise throws a clear "model not installed" error. A Hilt binding picks Fake in debug (with a debug "force result" menu) and Tflite in release.
3. Implement **`MlKitNameReader`** (on-device ML Kit text recognition) + `NameMatcher` (normalised Levenshtein similarity vs `Medicine.name`, threshold `NAME_MATCH_MIN_SIMILARITY`).
4. Implement **`VerificationPipeline.verify(photo, medicine, doseEvent, history)`** returning `VerificationResult(passed, rejectReason?, emptyCellsAfter?)`. Steps **in this exact order** (stop at first failure):
   1. Quality gate (Stage 5) → `BLURRY`/`TOO_DARK`/`TOO_BRIGHT`/`GLARE`.
   2. Duplicate check: pHash distance ≤ `PHASH_MAX_HAMMING_DISTANCE` to any previous attempt/proof photo for this medicine → `DUPLICATE_PHOTO`.
   3. Detect cells. Confidence below `DETECTOR_MIN_CONFIDENCE` cells are ignored. `stripFullyInFrame == false` → `STRIP_NOT_FULLY_VISIBLE`.
   4. Medicine match: OCR text. If text is read **and** similarity < threshold → `WRONG_MEDICINE`. If no text is read, **do not fail** – set a `nameUnconfirmed` flag stored on the attempt.
   5. **Baseline compare (the ledger rule – see section 3.6 for the full explanation and worked example):** `previousEmpty` = `emptyCellsAfter` of the last verified dose for this medicine, or `baselineEmptyCells` if none. `newEmpty` = EMPTY cells now.
      - `newEmpty - previousEmpty == pillsPerDose` → pass.
      - `newEmpty - previousEmpty == 0` → `PILLS_NOT_REMOVED`.
      - `newEmpty - previousEmpty` ≠ `pillsPerDose` (fewer or more) → `WRONG_PILL_COUNT`.
      - PARTIAL cells count as FULL (not removed).
5. **Attempt handling** (`VerificationCoordinator`):
   - Save every attempt in `VerificationAttempt` (including pHash and result).
   - On PASS: call `RecordVerifiedDose` (sets `TAKEN`, `takenAt`, saves `verifiedPhotoPath`, `emptyCellsAfter`, decreases `currentStock` by `pillsPerDose`) – **only if the dose is still within its window**; if the window already ended, route to the late-dose logic (Stage 7) instead.
   - On FAIL: increment `verificationAttempts`; if `< MAX_VERIFICATION_ATTEMPTS` → allow retake; else transition to `UNVERIFIED` and **enqueue a `DOSE_UNVERIFIED` outbox event** ("Dose could not be verified.").
   - Remaining time rule: if the window ends while the patient is mid-verification, the final state is `MISSED` (Stage 4 engine) unless the verification already passed.
6. **Verification UI:** Capture → "Checking your photo…" → Result screen. On pass: "Dose recorded ✔" and Home. On fail: the friendly reason, attempts left, **Retake** button. After the last attempt: "We couldn't verify this dose. Your caregiver has been notified."
7. If the medicine has no baseline photo yet (skipped in Stage 3), the first verification asks for an **enrollment photo of the full strip** *before* the dose photo.
8. Update the Stage 4 placeholder route to use the real pipeline. Keep the debug "force result" controls **debug-only**.

**Acceptance criteria**
- [ ] Unit tests (with `FakeStripDetector`, fake OCR, fake clock) cover **every** `RejectReason`, the exact check order, pass path, retry counting, the 3rd-failure → UNVERIFIED + outbox event, stock decrement only on pass, duplicate-photo rejection, and window-ended routing.
- [ ] A dose is **never** TAKEN without a passing `VerificationResult` (add a test that tries bypassing and fails).
- [ ] End-to-end manual test in debug using the fake detector: pass, each failure type, and 3-strike flow.
- [ ] Release build refuses to run without a model file and shows a graceful "Verification temporarily unavailable" message (does **not** mark doses taken).

**Do NOT:** train a model (Stage 13) or send notifications to caregivers (Stage 10).

**Reference links:**
- ML Kit text recognition (Android): https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- LiteRT (TensorFlow Lite): https://ai.google.dev/edge/litert

**Verification Pack for this stage** (create `docs/verification/STAGE_6_VERIFY.md` per section 5.3; it must include at least):
- B: one test **per RejectReason**, a test for the exact check order, a pass-path test, the 3-strikes → UNVERIFIED test, stock-only-on-pass test, duplicate-photo test, and a **bypass test** proving a dose can't become TAKEN without a passing result.
- C: using the debug 'force detector result' menu, walk through: pass, pills not removed, wrong count, strip not fully visible, wrong medicine, duplicate photo, and three failures in a row (caregiver event queued).
- A: include a **decision table** (check → condition → reject reason → message shown) so the human can review the rules at a glance.

---

## STAGE 7 – Late dose entry

**Goal:** A missed dose can be recovered with a verified photo.

**Tasks**
1. Dashboard "Missed Doses" and Calendar day view show each missed dose with a **"I took this late"** button when `LateEntryPolicy.canLogLate(...)` is true; otherwise show "Locked".
2. Tapping opens the same verification flow (Stage 6) in **late mode**. The pipeline is identical; the baseline compare uses the same rules.
3. On PASS in late mode: transition `MISSED → TAKEN_LATE`, set `takenAt` to the **photo capture time (not user-typed)**, decrease stock, set `lateEntry = true`, and enqueue a **`DOSE_TAKEN_LATE`** outbox event ("Missed dose was taken late (verified).") if a caregiver is connected.
4. **Double-dose guard:** if another dose of the same medicine is scheduled within a safety gap (default `max(1 hour, 25% of the interval between doses)` – put the formula in `LateEntryPolicy`), show a warning dialog: "Your next dose is soon. Check with your doctor before taking this one." The user may still log a late photo only if they confirm they **already** took it; do not encourage taking it now.
5. **"Reported without photo" option:** if the user has no photo, allow "Mark as taken (unverified)" → sets `manualUnverified = true`, status stays `MISSED`, never counts in adherence numerator, flagged in history. Requires a confirmation dialog.
6. After the late-entry limit passes, the dose is **locked** as `MISSED` (UI shows lock icon; no actions).

**Acceptance criteria**
- [ ] Unit tests: allowed/locked boundaries (next-dose vs 24 h, earlier wins), capture-time timestamp used, adherence counts TAKEN_LATE as taken, manualUnverified never counts as taken, outbox event written.
- [ ] Double-dose warning appears in the right situations (test the policy function).
- [ ] UI flows work for late mode.

**Do NOT:** allow editing history times manually.

**Reference links:**
- (no new external references – reuses Stages 1, 5 and 6)

**Verification Pack for this stage** (create `docs/verification/STAGE_7_VERIFY.md` per section 5.3; it must include at least):
- B: boundary tests for the late-entry limit (next dose vs 24 h, earlier wins), capture-time timestamp, TAKEN_LATE counted as taken, manual-unverified not counted.
- C: using the shortened window/fake clock: let a dose go Missed → log it late with a passing photo → see Taken Late; repeat after the limit to see it locked; try the double-dose warning.
- A: explain the full state diagram including MISSED → TAKEN_LATE.

---

## STAGE 8 – History & calendar (Branches 4 and 7 of the flow numbering: History, Calendar)

**Goal:** Read-only views over the dose log with adherence.

**Tasks**
1. **History screen:** time-range selector (Today / Week / Month); list of records with status chips: Taken, Taken Late, Missed, Skipped, Unverified, "Reported without photo". Tap a record to see details and the saved proof photo (if any).
2. **Adherence summary card:** `Medication Adherence: 92% (4 late)` plus counts `Taken: 46  Missed: 4  Skipped: 0` computed by `AdherenceCalculator`.
3. **Calendar screen:** month view with per-day dots (green all taken, orange some late/unverified, red missed, grey none). Select a date → list that day's medicines with dosage, time, food instructions, status. Select a medicine → detail → "Back to Calendar" → "Back to Home".
4. Proof photos are displayed with Coil from app-private storage.

**Acceptance criteria**
- [ ] Unit tests for the range queries and the day-colour logic.
- [ ] Numbers on the summary match the DB (test with seeded data).
- [ ] Empty states for no data.

**Do NOT:** add editing of past doses.

**Reference links:**
- (no new external references – uses Room queries from Stage 1)

**Verification Pack for this stage** (create `docs/verification/STAGE_8_VERIFY.md` per section 5.3; it must include at least):
- B: tests for range queries (Today/Week/Month), calendar day-colour logic, adherence numbers on seeded data.
- C: a debug 'seed 30 days of sample data' button, then compare the screen numbers against a table of expected values in the pack.
- D: map each history/calendar status to where it appears.

---

## STAGE 9 – Stock / refill management (Branch 5)

**Goal:** Track pill stock and raise low-stock alerts.

**Tasks**
1. Stock is decremented **only** by `RecordVerifiedDose` (verified on-time or late). Skipped/missed/unverified/manual-unverified doses never change stock. Confirm this in tests.
2. After every decrement, evaluate `StockCalculator.isLow(...)` (`remaining < pillsPerDay * LOW_STOCK_DAYS_THRESHOLD`).
3. When it **becomes** low (transition from not-low to low – do not spam on every dose):
   - Show a local alert to the patient: "<Medicine> is running low."
   - **Enqueue a `LOW_STOCK` outbox event** ("<Medicine> is running low – refill needed.") if a caregiver is connected.
   - If **no caregiver** is connected: show a prompt "Add a caregiver so they can be notified" linking to Caregiver screen.
4. Stock UI: on the medicine detail show remaining pills and an editable "Refilled – add pills" action that adds to `currentStock` and resets the low flag.
5. Out of stock (0) shows a persistent banner.

**Acceptance criteria**
- [ ] Unit tests: decrement rules, low threshold boundary, single alert per low-transition, refill resets, outbox event only when caregiver connected.
- [ ] No code anywhere searches for stores or sends emails (grep check in the stage report).

**Do NOT:** deliver notifications to a caregiver device (Stage 10).

**Reference links:**
- (no new external references – uses the outbox from Stage 1)

**Verification Pack for this stage** (create `docs/verification/STAGE_9_VERIFY.md` per section 5.3; it must include at least):
- B: tests that stock decreases only on verified doses (not skipped/missed/unverified/manual), single low-stock alert per transition, refill resets, outbox event only if caregiver connected.
- C: set a medicine with 5 pills, verify doses (fake detector) until it becomes low; expected: alert once, outbox row created.
- D: proof by `grep` that nothing searches for stores or sends email.

---

## STAGE 10 – Firebase backend, sync & caregiver mode (Branch 8)

**Goal:** Real accounts, cloud sync, caregiver invitations and delivery of every outbox notification.

🧑 **HUMAN prerequisite:** create a Firebase project, enable Auth (Email/Password + Phone), Firestore, Storage, Cloud Messaging, Cloud Functions; add the Android app (`com.meditrack.app`) and place `google-services.json` in `app/`. If it is missing, deliver everything possible with the Fake implementations and clearly list the missing steps.

**Tasks**
1. `FirebaseAuthRepository` implementing the Stage 2 interface (email/phone + OTP). Keep the Fake for tests/debug.
2. **Sync layer:** a `SyncWorker` (WorkManager, network-constrained, with backoff) that pushes local changes (medicines, dose events, caregiver links) to Firestore and pulls caregiver-visible data. Room stays the source of truth. Conflict rule: **last-write-wins by `updatedAt`**, except a verified dose is never downgraded.
3. **Proof photos:** upload verified photos to Storage at `proofs/{patientUid}/{doseId}.jpg` after sync; keep local copy.
4. **Caregiver mode UI:** Home → Caregiver → Add family member/caregiver (Name, Phone/Email, Relationship) → Send invitation → Caregiver accepts? (NO → "Invitation remains pending"; YES → "Caregiver connected").
5. **Caregiver role app mode:** a caregiver who logs in sees a list of linked patients, each patient's today's doses/statuses, adherence, and notifications. Read-only.
6. **Outbox delivery:** `OutboxDeliveryWorker` writes each undelivered outbox row to Firestore `notificationEvents/{id}` with `{type, patientUid, caregiverUid, medicineName, doseId, createdAt}` then marks it delivered.
7. **Cloud Function (TypeScript, in `/functions`)** `onCreate(notificationEvents)` → looks up the caregiver's FCM token → sends a push with the correct text per `NotifType`:
   - MISSED_DOSE: "Medicine dose has been missed."
   - LOW_STOCK: "<Medicine> is running low – refill needed."
   - DOSE_UNVERIFIED: "Dose could not be verified."
   - DOSE_TAKEN_LATE: "Missed dose was taken late (verified)."
   - CAREGIVER_INVITE: "<Name> invited you to be their caregiver."
8. Register/refresh FCM tokens; show the incoming push as a notification in the caregiver's app.
9. **Security rules** (`firestore.rules`, `storage.rules`): a patient reads/writes only their data; a caregiver reads only linked patients' data **and** proof photos; nobody else can read. Include rule tests (Firebase emulator) if feasible.

**Acceptance criteria**
- [ ] Offline: outbox rows persist and are delivered when the network returns (test with WorkManager test utilities).
- [ ] Each of the 5 notification types produces the right push text (unit-test the function's message builder).
- [ ] Rules reject cross-user access (emulator test or documented manual test).
- [ ] App still fully works with no `google-services.json` using Fakes (debug).

**Do NOT:** put any secret keys in the repo; add chat or other features.

**Reference links:**
- Firebase docs: https://firebase.google.com/docs
- Firebase console: https://console.firebase.google.com
- Firebase Auth: https://firebase.google.com/docs/auth
- Cloud Firestore security rules: https://firebase.google.com/docs/firestore/security/get-started
- Cloud Storage: https://firebase.google.com/docs/storage
- Cloud Messaging (Android): https://firebase.google.com/docs/cloud-messaging/android/client
- Cloud Functions: https://firebase.google.com/docs/functions
- Emulator Suite: https://firebase.google.com/docs/emulator-suite

**Verification Pack for this stage** (create `docs/verification/STAGE_10_VERIFY.md` per section 5.3; it must include at least):
- B: tests for the message text of each notification type, offline outbox persistence and later delivery, conflict rule (verified dose never downgraded).
- C: two-device (or two-account) walkthrough: invite → accept → trigger missed dose / low stock / unverified → caregiver receives each push; plus an offline test (airplane mode then reconnect).
- D: security-rules proof (emulator tests or manual attempts to read another user's data/photos that must be denied).
- A: include a data-flow diagram: app → Room → outbox → Firestore → Cloud Function → FCM → caregiver phone.

---

## STAGE 10B – Doctor mode (Branch 10)

**Goal:** A doctor can link to a patient (with the patient's explicit consent), create prescription proposals for that patient, and monitor their doses, adherence and proof photos. Every doctor-prescribed dose still needs a live verified photo.

🧑 **HUMAN prerequisite:** same Firebase setup as Stage 10. Without `google-services.json`, build everything with Fakes (a fake doctor account and fake link) and list the missing steps.

### Core Design Principle

> **The doctor proposes, the patient decides, and MediTrack activates the medicine only after patient acceptance.**
>
> MediTrack will support three account roles: **Patient**, **Doctor**, and **Caregiver**. MediTrack does **not** verify the medical qualification, licence number, registration certificate, or licensing status of a doctor. Selecting the Doctor role only identifies the user's app role. The patient is responsible for independently choosing a qualified healthcare professional. A Doctor account does not automatically grant access to any patient's health information.

### 10.A Doctor–Patient connection and invitation
- **Invitation options:**
  - A doctor can invite a patient using the patient's registered email address or phone number.
  - A patient can also connect by entering a doctor's unique invitation code or scanning an invitation QR code generated by the doctor.
  - **No public directory:** To protect patient privacy, MediTrack does not expose a public searchable patient directory.
- **Doctor identity in invitation:** The invitation clearly identifies the doctor using their account name and available contact information.
- **Consent gating:** The patient can explicitly **Accept** or **Decline**.
  - A pending or declined invitation grants **zero access** to patient information.
  - Only an accepted connection (`DoctorLink` status = `ACCEPTED`) grants the doctor specifically permitted patient-data access.
- **Revocation:** A patient can **Revoke** doctor access at any time. Revocation immediately terminates doctor access through both application logic and Firebase security rules. Revoking does not delete medicines the doctor already prescribed (they become patient-managed).
- **Security:** Invitations are secure, single-use, and expiring (default 7 days).

### 10.B Doctor dashboard
- **Connected patients view:** List of patients who have explicitly accepted invitations. Doctors see **no data** for patients with pending, declined, or revoked links.
- **Per-patient monitoring (read-only):**
  - Current medicines and stock levels.
  - Today's doses and statuses.
  - Upcoming doses and missed doses.
  - Adherence statistics (`AdherenceCalculator`, section 3.5) and history (Today/Week/Month).
  - Proof photos (read-only, loaded from Storage).
  - Prescription proposals and their statuses.
- **Strict read-only boundary:** Doctors cannot edit patient-owned medicines, verified dose history, stock, or adherence records.

### 10.C Prescription proposal workflow
- A doctor can create a prescription proposal for an actively connected patient (`DoctorLink` = `ACCEPTED`).
- **Proposal fields:**
  - Medicine Name, Dosage, Pills per dose, Frequency (times of day), Duration / Start and End dates, Before/after food instructions, Additional instructions.
  - **No enrollment photo is taken by the doctor**; the patient captures the baseline enrollment photo upon first intake verification (Stage 6).
- **Proposal statuses:**
  - `PENDING`: Waiting for patient review.
  - `ACCEPTED`: Approved by patient; converted to active medicine.
  - `DECLINED`: Rejected by patient; archived with no effect on active medicines.
  - `CLARIFICATION_REQUESTED`: Patient requested additional clarification before deciding.
  - `SUPERSEDED`: Replaced by a newer revision.
- **Audit trail:** Each proposal records `id`, `patientId`, `doctorUid`, `createdAt`, proposal contents, `revision` number, and decision audit metadata.

### 10.D Patient prescription approval
- **Patient review actions:** A patient reviewing a proposal on Home or Profile can:
  1. **Accept:** Validates proposal fields and converts it into a real active `Medicine` in Room, generates scheduled doses via `DoseScheduler`, schedules reminder alarms (Stage 4), and marks the proposal `ACCEPTED`. The medicine is badged with `"Prescribed by Dr <name>"`.
  2. **Decline:** Marks the proposal `DECLINED`. A declined proposal **never** affects active medicines, dose scheduling, stock, or adherence.
  3. **Request Clarification:** Sets status to `CLARIFICATION_REQUESTED` with a recorded clarification note. MediTrack does not introduce a chat system; clarification is represented by this structured status and recorded note.
- **Safety rules:**
  - A proposal must **not** create an active `Medicine` or `DoseEvent` while pending.
  - `STOP` proposals: When a doctor proposes stopping a medicine, the patient is immediately notified. Stopping can be reviewed or applied to cancel future pending doses, while preserving full past dose history.
  - **Revisions:** If a doctor revises an accepted proposal, it creates a new revision (`revision + 1`) and requires fresh patient acceptance. An existing accepted prescription is never silently overwritten.
  - Accepted edits affect **future doses only**. Past dose history is never rewritten.

### 10.E Doctor and caregiver permission boundaries
- **Doctors cannot mark a dose TAKEN.**
- **Caregivers cannot mark a dose TAKEN.**
- **Neither doctors nor caregivers can bypass photo verification.** Every dose still requires a live photo verified by the quality gate and detector.
- **Doctors cannot alter verified dose history, stock, or adherence.**
- **Caregivers remain strictly read-only.**
- **Doctors can create prescription proposals only for actively connected patients.**
- **Patients retain complete ownership and control of their medication records.**
- **Patients can revoke doctor or caregiver access at any time.**
- **Enforcement:** Enforced strictly by Firebase Security Rules (`firestore.rules`, `storage.rules`). Client-side UI restrictions alone are insufficient.

### 10.F Data model and backend
- **Stage 1 model preservation:** Preserves existing Stage 1 `DoctorLink` and `MedicineProposal` Room entities without casual schema alterations.
- **Firestore collections:**
  - `doctorLinks/{linkId}`: `{ id, patientUid, doctorUid, doctorName, doctorContact, status ("PENDING"|"ACCEPTED"|"REVOKED"), createdAt, updatedAt }`
  - `doctorInvitations/{inviteId}`: `{ inviteCode, doctorUid, doctorName, doctorContact, patientContact?, expiresAt, used }`
  - `medicineProposals/{proposalId}`: `{ id, patientUid, doctorUid, type ("ADD"|"EDIT"|"STOP"), medicineId?, payloadJson, status ("PENDING"|"ACCEPTED"|"DECLINED"|"CLARIFICATION_REQUESTED"|"SUPERSEDED"), revision, clarificationNote?, createdAt, resolvedAt? }`
  - `medicineProposalRevisions/{revisionId}`: Historical revisions and patient decisions audit log.
- **Security rules summary:**
  - `match /doctorLinks/{linkId}`: Patient and doctor can read; patient can update status to ACCEPTED, DECLINED, or REVOKED; doctor can create invitations.
  - `match /medicineProposals/{proposalId}`: Doctor can create only if actively connected (`status == 'ACCEPTED'`); patient can read and update status; doctor cannot write directly to `medicines` or `doses`.
  - `match /proofs/{patientUid}/{doseId}.jpg`: Doctor can read only if actively connected; cannot write or delete.

### 10.G Notifications
Extend the notification outbox (`NotificationOutbox`) and Cloud Functions (`onCreate`) for doctor events:
- `DOCTOR_INVITE_RECEIVED`: "<name> wants to connect as your doctor on MediTrack. Review the request."
- `DOCTOR_INVITE_ACCEPTED`: "<patientName> accepted your doctor connection."
- `DOCTOR_INVITE_DECLINED`: "<patientName> declined your doctor connection."
- `DOCTOR_INVITE_REVOKED`: "Doctor access has been revoked."
- `PRESCRIPTION_PROPOSAL_RECEIVED`: "Dr <name> proposed a prescription for <Medicine>. Review and accept."
- `PRESCRIPTION_ACCEPTED`: "<patientName> accepted the prescription proposal for <Medicine>."
- `PRESCRIPTION_DECLINED`: "<patientName> declined the prescription proposal for <Medicine>."
- `PRESCRIPTION_CLARIFICATION_REQUESTED`: "<patientName> requested clarification on <Medicine>."
- `PRESCRIPTION_REVISED`: "Dr <name> updated the prescription proposal for <Medicine>. Review the new revision."
- Preserves all existing caregiver notification types (`MISSED_DOSE`, `LOW_STOCK`, `DOSE_UNVERIFIED`, `DOSE_TAKEN_LATE`, `CAREGIVER_INVITE`).

**Acceptance criteria**
- [ ] A doctor account can be created without licence or registration-number verification; it accesses only the doctor dashboard and connected patient list.
- [ ] A doctor sees **no data** for a patient with a `PENDING`, `DECLINED`, or `REVOKED` link (verified by tests + manual check).
- [ ] An accepted `ADD` proposal creates the medicine, generates future doses and alarms; a declined one creates nothing.
- [ ] An accepted `EDIT` changes future doses only; past dose history is untouched.
- [ ] A `STOP` proposal cancels future pending doses and alarms while preserving dose history.
- [ ] A doctor-prescribed dose still becomes `TAKEN` only after passing live photo verification (verified by bypass test).
- [ ] Doctors and caregivers cannot mark doses taken, modify stock, or alter history (verified by security rules and unit tests).
- [ ] Prescription revisions create a new version requiring fresh patient acceptance without silent overwrite.
- [ ] Clarification requests update proposal status and record clarification notes without requiring a chat system.
- [ ] Revoking a connection terminates doctor access immediately in app logic and Firebase security rules.
- [ ] Each doctor and caregiver notification type produces the correct push text.
- [ ] App still fully works offline and with no `google-services.json` using Fakes (debug).
- [ ] Opens in Android Studio, Gradle Sync succeeds, and Run ▶ launches the app.

**Do NOT:** build chat or video consultation, e-prescriptions with legal validity, drug-interaction checking, doctor licence/credential verification, or any Location/Email permission.

**Reference links:**
- Cloud Firestore security rules: https://firebase.google.com/docs/firestore/security/get-started
- Cloud Storage security: https://firebase.google.com/docs/storage/security
- Cloud Messaging (Android): https://firebase.google.com/docs/cloud-messaging/android/client
- Cloud Functions: https://firebase.google.com/docs/functions
- Emulator Suite: https://firebase.google.com/docs/emulator-suite
- Room migrations: https://developer.android.com/training/data-storage/room/migrating-db-versions

**Verification Pack for this stage** (create `docs/verification/STAGE_10B_VERIFY.md` per section 5.3; it must include at least):
- A: Flow diagram (doctor → proposal → Firestore → Cloud Function → FCM → patient → Accept → Room → doses/alarms) and explanation of why doctors write proposals rather than medicines; table of permissions (patient / caregiver / doctor); explicit statement that doctors are **not verified**.
- B: Tests for `ProposalStateMachine` (all legal and illegal transitions), accept-ADD generating doses, accept-EDIT affecting future doses only, STOP removing future doses and keeping history, access denied for `PENDING`/`DECLINED`/`REVOKED` links, message text per notification type, and the **bypass test** (doctor-prescribed dose cannot be TAKEN without a passing verification).
- C: Two-account walkthrough: doctor invites patient → patient accepts → doctor proposes prescription → patient requests clarification → doctor revises → patient accepts → dose alarms → patient verifies with photo → doctor monitors dose and proof photo; then patient revokes access and doctor loses all access.
- D: Security-rules proof (emulator tests or manual attempts) that a doctor cannot read unlinked patients and cannot write medicines, doses, stock, or proofs.
- E: Documented limitations (no licence verification, no chat system, no legal e-prescribing).

---

## STAGE 11 – Scan medicine / prescription (Branch 6)

**Goal:** Camera + OCR to pre-fill Add Medicine.

**Tasks**
1. Home → "Scan Medicine / Prescription" → camera permission check (NO → ask → if denied back to Home) → open camera → capture image (reuse CameraX capture + quality gate) → OCR (ML Kit) → **Extract Information:** Medicine Name, Dosage, Frequency (regex/heuristics, in a pure-Kotlin `PrescriptionParser`).
2. Show extracted fields in an editable form. "Correct?" → **YES:** continue to Add Medicine pre-filled; **NO:** user edits manually.
3. Also allow choosing an existing image via the **system photo picker** (this is the *only* place gallery images are allowed; never for dose verification).
4. Back to Home when done.

**Acceptance criteria**
- [ ] `PrescriptionParser` unit tests with at least 10 sample text snippets (name/dosage/frequency variants like "Paracetamol 500mg twice daily", "1-0-1", "BD", "OD").
- [ ] Pre-filled Add Medicine screen opens correctly; user can edit all fields.
- [ ] Gallery picking is available here and nowhere in the verification flow.

**Reference links:**
- ML Kit text recognition: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Android photo picker: https://developer.android.com/training/data-storage/shared/photopicker

**Verification Pack for this stage** (create `docs/verification/STAGE_11_VERIFY.md` per section 5.3; it must include at least):
- B: 10+ parser test cases listed in a table (input text → extracted name/dosage/frequency).
- C: scan a real or printed prescription/strip, edit wrong fields, confirm pre-filled Add Medicine.
- D: proof that gallery picking exists only in the scan flow.

---

## STAGE 12 – Profile, settings, privacy & data deletion (Branch 9)

**Goal:** Settings screen, Doctor Mode account management, and privacy compliance basics.

**Tasks**
1. **Profile/Settings screen:** View/Edit Personal Information, Medicines, Reminder Settings (default snooze presets, dose window length within allowed range), Notification Settings (per channel + per notification type), Caregiver, Privacy, Camera permission shortcut, Account Settings.
   - **Patient-facing settings:**
     - View connected doctors.
     - View and respond to pending doctor invitations.
     - Revoke doctor access at any time.
     - View pending, accepted, declined, and superseded prescription proposals.
     - View prescription revision and decision audit history.
   - **Doctor-facing settings:**
     - View connected patients.
     - View pending invitations.
     - View prescription proposal history and statuses.
   - **No Location setting.**
2. **Logout?** YES → logout → Login/Welcome; NO → back to Home.
3. **Privacy / consent:** first-run consent screen explaining that health data and photos are stored; link to a privacy policy placeholder page; consent flag stored. Clear note: "MediTrack records that a pill left the strip. It cannot confirm you swallowed it, and it is not medical advice."
4. **Data export & deletion:**
   - **"Export my data":** Generates an archive (JSON + photos zip) containing patient profile, active medicines, dose history, caregiver links, doctor connections, invitations, prescription proposals, and decision audit logs.
   - **"Delete my account and data":** Permanently deletes local DB/files and executes cloud deletion: Firestore documents (patient data, doctor connections, invitations, prescription proposals, audit history), Storage photos, caregiver links, and auth account, subject to documented retention and deletion policy. Confirmation dialogs required.
5. Encrypt the Room database (SQLCipher or Android keystore-backed approach – choose and document) and store proof photos in app-private storage only.
6. Disable screenshots on the verification result and proof-photo screens (`FLAG_SECURE`) – optional but recommended; document the decision.

**Acceptance criteria**
- [ ] Deletion removes all local data and issues cloud deletion calls, including doctor links, invitations, proposals, and audit records (verify with fakes/tests).
- [ ] Data export includes doctor connections, invitations, prescription proposals, and audit history.
- [ ] Patient-facing and doctor-facing settings allow managing connections, invitations, proposals, and revocations.
- [ ] Consent must be given before any data is stored.
- [ ] Settings changes take effect (dose window, snooze presets) and are covered by tests.

**Reference links:**
- SQLCipher for Android: https://www.zetetic.net/sqlcipher/sqlcipher-for-android/
- India Digital Personal Data Protection Act, 2023 (MeitY): https://www.meity.gov.in

**Verification Pack for this stage** (create `docs/verification/STAGE_12_VERIFY.md` per section 5.3; it must include at least):
- B: tests for deletion (local + fake cloud calls covering doctor links/proposals), consent gating, settings affecting dose window and snooze presets.
- C: export data and open the zip (verify doctor mode data is included); delete account and confirm everything is gone; try using the app without consent.
- D: state the encryption approach chosen and how to confirm the DB file is unreadable without the key.

---

## STAGE 13 – ML model: data, training & integration

**Goal:** Replace `FakeStripDetector` with a real on-device model that detects blister cells and whether each is FULL, EMPTY or PARTIAL.

🧑 **HUMAN prerequisites:** (1) create a free **Roboflow account** and supply the API key via environment variable `ROBOFLOW_API_KEY` or `roboflow.api.key=YOUR_KEY` in `local.properties` (loaded into `BuildConfig.ROBOFLOW_API_KEY`, never commit it); (2) check the dataset licence on its Roboflow page (see 13.A); (3) collect and label your own phone photos (see 13.B). The AI agent builds the *pipeline and tooling*; the human supplies/validates real photos.

### 13.A Datasets to use (open source) – THE DATASET STACK

No single public dataset provides **per-cell full/empty labels on real used strips photographed with a phone**. Therefore MediTrack uses a **stack of two public datasets plus the team's own photos**. Each has a different job – do not use one for the other's job.

| # | Dataset | Job | Has cell-level labels? |
|---|---|---|---|
| **1** | **Blister Pills Segmentation** (Roboflow Universe) | **Label source** – teaches the model what a FULL cell and an EMPTY cell look like | **Yes** (`Empty`, `Full`, `Blister`) |
| **2** | **Pharmassist Dataset** (Mendeley Data, CC BY 4.0) | **Real-world realism** – used / partially used strips photographed by phone in India under varied angles, light and backgrounds | **No** (only one tablet class per image) |
| **3** | **Own photos** (Stage 5 data-collection mode) | **Gold test set** and `cell_partial` labels | Yes (you label them) |

#### Dataset 1 – Blister Pills Segmentation (label source)

| Item | Value |
|---|---|
| Source | Roboflow Universe – https://universe.roboflow.com/blisterdetection/blister-pills-segmentation |
| Roboflow workspace / project / version | `blisterdetection` / `blister-pills-segmentation` / version `7` |
| Size | 1,349 images |
| Classes (as published) | `Empty`, `Blister`, `Full` |
| Annotation type | Instance segmentation (polygons) |
| Licence | **Verify on the dataset page and record it in `ml/DATASET.md` before use.** Do not assume. |

**Download (agent writes `ml/download_dataset.py`):**
```python
import os
from roboflow import Roboflow          # pip install roboflow
rf = Roboflow(api_key=os.environ["ROBOFLOW_API_KEY"])
project = rf.workspace("blisterdetection").project("blister-pills-segmentation")
dataset = project.version(7).download("yolov8")   # or "coco-segmentation"; keep polygons
print(dataset.location)
```
- Do **not** commit downloaded images to git (add `ml/data/` to `.gitignore`).

**Class mapping to MediTrack's unified classes** (create `ml/class_map.yaml`; the agent must **open at least 30 random samples and confirm what `Blister` means** – whole strip or a single cell – and write the confirmed meaning in `DATASET.md`; the human reviews it):

| Unified class (used by the app) | Source class | Notes |
|---|---|---|
| `strip` | `Blister` (if it marks the whole strip) | If `Blister` marks single cells instead, derive a strip box from the union of all cell boxes in the image |
| `cell_full` | `Full` | A cell that still contains a pill |
| `cell_empty` | `Empty` | A cell whose pill has been removed |
| `cell_partial` | – (**not in this dataset**) | Must come from your own photos (13.B), e.g. pills half pushed out, cracked foil |

- Convert segmentation polygons to bounding boxes for the detector (`prepare_dataset.py`), or train a segmentation model; keep one consistent choice and document it.

#### Dataset 2 – Pharmassist (real-world used strips)

| Item | Value |
|---|---|
| Source | Mendeley Data – https://data.mendeley.com/datasets/zbnhmbbymd/1 (DOI: `10.17632/zbnhmbbymd.1`) |
| Size | 4,997 images: **2,050 original photos + 2,947 augmented copies** |
| Content | Used / torn / partially used tablet blister packs of **53 tablet types**, photographed by phone at multiple locations in Chennai, India (Sept–Dec 2025), with varied viewing angles, orientations, lighting and backgrounds |
| Labels | **One tablet class per image only** – no cell boxes, no full/empty labels |
| Licence | **CC BY 4.0** (confirmed on the dataset page; attribution required) |
| Authors | Kapilesh N, Tharaka P, Eesha C, Rekha D, Bhargavi R (Vellore Institute of Technology) – cite as listed on the page |

**Download (human step 🧑):** open the link → **Download All** → unzip into `ml/data/pharmassist/raw/`. (Mendeley has no simple key-based download; do not script around it.)

**What it is used for (and what it is NOT used for):**
1. **Strip detection & domain realism:** train/fine-tune the `strip` detector and test whether the quality gate and cell detector survive real-world angles, glare and clutter.
2. **Pseudo-labelling (agent builds `ml/pseudo_label.py`):** train a first model on Dataset 1 → run it on the **original** Pharmassist photos → keep predictions with confidence ≥ 0.8 as *silver* labels → the **human reviews every `cell_partial` candidate and at least 10% of the rest** → reviewed labels may join the training set. Silver labels never enter the test set.
3. **Realism validation set:** the human hand-labels ~300 Pharmassist originals (`full` / `empty` / `partial`) as an extra **validation** set (not the final test).
4. **OCR / medicine-name matching tests:** use the strips to test the Stage 6 name reader (note: tablet-class names are not the printed text; read the foil text for real checks).
5. It is **NOT** a source of `cell_empty` / `cell_full` ground truth on its own, and its 53 tablet classes are **not** used as MediTrack classes.

**Leakage rule (important):** the 2,947 augmented images are copies of the 2,050 originals. Use **originals only** (discard the provided augmented copies and do your own augmentation), or keep every original together with its augmented copies in the **same** split. Never let an original appear in train and its augmented copy in validation/test.

**Prepare (agent writes `ml/prepare_pharmassist.py`):** verify counts (expect 4,997 files), detect which files are originals vs augmented (inspect the file naming; if unclear, use perceptual hashing to group near-duplicates), and write `ml/data/pharmassist/manifest.csv` with columns `path, is_augmented, tablet_class, group_id`. Record how originals were identified in `DATASET.md`.

#### Required documentation (all datasets)
- `ml/DATASET.md` – source, version, licence, size, class meanings, the confirmed meaning of `Blister`, known gaps.
- `ml/ATTRIBUTION.md` – name, authors, URL/DOI, licence, version, download date for **every** dataset used (CC BY 4.0 requires attribution; also show the attribution in the app's "About / Licences" screen).
- `ml/reports/dataset_inventory.md` – image counts, instances per unified class, image-size stats, and a contact sheet of 30 random labelled samples per dataset.

#### Optional supplementary Roboflow datasets
Use only if the stack above is not enough; each needs its own licence check, class map and attribution entry.

| Dataset | Use | Link |
|---|---|---|
| Medication Blister Classification (KNOK) | extra full/empty strips | https://universe.roboflow.com/knok/computer-vision-for-medication-blister-classification |
| Pill_Dataset_600 / Pill_Dataset_800 (Phms Workspace) | `Blisterpack`, `Good pill`, `Missing pill` (map `Missing pill` → `cell_empty`) | https://universe.roboflow.com/phms-workspace-ptgtz/pill_dataset_600 |
| blister_pack_28th_aug_2023 (SASTRA) | `empty_pills` class | https://universe.roboflow.com/sastra-qv7ma/blister_pack_28th_aug_2023 |
| blister packet identification (SASTRA Deemed University) | `empty pill` class | https://universe.roboflow.com/sastra-deemed-university/blister-packet-identification |
| pill (cc-f6245) | `empty_blister`, `outof_blister` | https://universe.roboflow.com/cc-f6245/pill-l5nxe |
| Blister_Pill_Detection (varun-sundar) | small extra set (129 images) | https://universe.roboflow.com/varun-sundar/blister_pill_detection |

**Not suitable for cell-state training (do not use):** NIH C3PI (lab photos of single pills; https://datadiscovery.nlm.nih.gov/d/5jdf-gdqh), CURE (single pill per image), Ultralytics medical-pills (single `pill` class, AGPL-3.0), VAIPE (multi-pill intake photos, not blister cells; https://arxiv.org/abs/2303.09782).

### 13.B Own data (REQUIRED – public data alone is not enough)

Public blister datasets look like factory/quality-control images; the app will see **phone photos of used strips in everyday light**. Therefore:
- Use the Stage 5 debug **data-collection mode** to collect your own labelled photos (target 1,500+): many strip types, lighting, backgrounds, angles ≤ 20°, partially pushed-out pills (`cell_partial`), crinkled foil, glare, and hard negatives.
- **Training mix:** Dataset 1 (labels) → add reviewed silver labels from Dataset 2 (realism) → fine-tune on public + own data.
- **Test set = own phone photos only**, split by **strip/session** (never by photo), and never used for training. Reported metrics must come from this set, not from the public data.
- Human labels and reviews the `cell_partial` class and a sample of the others.

**Tasks (AI agent)**
1. Create a `ml/` directory (Python) with: `README`, `requirements.txt`, `prepare_dataset.py`, `train.py`, `evaluate.py`, `export_tflite.py`.
2. **Dataset spec** (document in `ml/DATASET.md`): target **1,500–3,000 photos** initially. Cover many strip types and foil colours, 5–10 pill counts per strip, different phones, lighting (daylight, bulb, shadow), backgrounds, angles ≤ 20°, partially pushed-out pills, crinkled foil, and **hard negatives** (photo of a printed picture of a strip, empty hand, random objects). Labels per cell: `full`, `empty`, `partial`, plus a `strip` bounding box. Format: YOLO (txt) or COCO; train/val/test split by **strip/session**, not by photo, to avoid leakage.
3. `train.py`: fine-tune a **pretrained lightweight detector** (YOLO nano/small family or an EfficientDet-Lite/MobileNet-SSD equivalent) with augmentation (blur, rotation ±15°, brightness, glare overlays, shadows). Config in YAML. Seed everything for reproducibility.
4. `evaluate.py`: report **precision/recall per class**, mAP, and especially **"EMPTY precision"** (a false EMPTY records a dose that wasn't taken) and an **end-to-end test** that runs the full `VerificationPipeline` logic on held-out strips with known before/after empty counts. Output a markdown report with confusion matrices and failure examples.
5. `export_tflite.py`: export to TFLite (float16 or int8 quantised) and write `model_card.md` (training data summary, metrics, known limitations).
6. **Android integration:** implement `TfliteStripDetector` fully (preprocess, run, NMS if needed, map outputs to `Cell`/`Detection`, compute `stripFullyInFrame` from the strip box vs. image bounds with a margin). Place the model in `app/src/main/assets/strip_detector.tflite`. Add an on-device **latency benchmark** (target ≤ 400 ms on a mid-range phone) and a regression test that loads a few fixture images and checks cell counts.
7. Add a feature flag + remote-config-ready hook `VerificationConfig.modelVersion` stored with each attempt for auditing.

**Quality bar (document actual numbers; do not fake them)**
- EMPTY-class precision ≥ **0.98** on the held-out test set.
- Strip-level end-to-end accuracy (correct pass/fail on removed-count) ≥ **0.95**.
- If the bar isn't met, say so plainly in the report and list the next data to collect. Do **not** ship a model that misses the bar.

**Acceptance criteria**
- [ ] `ml/download_dataset.py` downloads **Blister Pills Segmentation (v7)** using `ROBOFLOW_API_KEY` from the environment; no key or images are committed.
- [ ] 🧑 Pharmassist is downloaded manually into `ml/data/pharmassist/raw/`; `ml/prepare_pharmassist.py` produces `manifest.csv` (4,997 files, originals vs augmented identified) and the split respects the **leakage rule**.
- [ ] `ml/pseudo_label.py` exists; silver labels are marked as silver and are never in the test set; the human review step is documented with counts reviewed.
- [ ] `ml/class_map.yaml`, `ml/DATASET.md` (incl. confirmed meaning of `Blister` and the licence), `ml/ATTRIBUTION.md` and `ml/reports/dataset_inventory.md` exist and are filled with real values.
- [ ] `ml/` scripts run end-to-end on the provided dataset (or on a tiny synthetic sample if the real one is missing, clearly labelled as a smoke test).
- [ ] `TfliteStripDetector` replaces the fake in release builds; debug still allows the fake.
- [ ] Evaluation report committed with real numbers.
- [ ] Verification pipeline tests from Stage 6 still pass.

**Reference links:**
- Blister Pills Segmentation: https://universe.roboflow.com/blisterdetection/blister-pills-segmentation
- Pharmassist (Mendeley Data): https://data.mendeley.com/datasets/zbnhmbbymd/1
- Roboflow docs: https://docs.roboflow.com
- Ultralytics docs (note AGPL-3.0 licence): https://docs.ultralytics.com
- LiteRT: https://ai.google.dev/edge/litert
- CVAT (labelling): https://www.cvat.ai
- Label Studio (labelling): https://labelstud.io

**Verification Pack for this stage** (create `docs/verification/STAGE_13_VERIFY.md` per section 5.3; it must include at least):
- A: explain the pipeline in plain language (photo → cells → empty count → compare against the ledger, section 3.6), the datasets used (Blister Pills Segmentation + own photos), and what the metrics mean (especially EMPTY precision).
- A: include the dataset inventory (counts per unified class) and state clearly that `cell_partial` comes only from own data and that Pharmassist has no cell labels (it is used for realism, pseudo-labels and validation only).
- D: a table showing which images went to train / validation / test per dataset, with a test proving **no Pharmassist original and its augmented copy are in different splits**.
- B: smoke tests for the scripts, on-device fixture-image tests, a regression test on the end-to-end pass/fail logic.
- C: a printed **test sheet**: the human photographs strips at known states (full, 1 removed, 2 removed, blurry, wrong angle) and records the app's result next to the expected one.
- D: the evaluation report with **real numbers** against the stated quality bar; say plainly if the bar is not met.

---

## STAGE 14 – Hardening, QA & release readiness

**Goal:** Make it dependable and Play-ready.

**Tasks**
1. **Reliability tests (manual checklist in `docs/QA.md`):** alarms with screen off, Doze mode, app killed, reboot, time-zone change, DST change, airplane mode, low battery mode, Xiaomi/Oppo/Vivo/Samsung battery-saver guidance.
2. **Edge cases:** device clock changed backwards (use monotonic checks where possible; never allow marking a future dose as taken); two medicines at the same time; many medicines (50+) performance; very large photos; camera unavailable; storage full; model missing; process death mid-verification.
3. **Security review & permission boundary verification:**
   - Security review checklist: no secrets, no logging of sensitive data in release, backups excluded for sensitive files (`android:allowBackup="false"` or backup rules).
   - **Doctor & Caregiver permission boundary verification (automated tests & emulator rules):**
     1. Unconnected doctors cannot access patient data.
     2. Pending invitations grant no patient-data access.
     3. Declined invitations grant no access.
     4. Accepted invitations grant only permitted access.
     5. Revoked connections immediately deny further access.
     6. A doctor cannot create a proposal for an unconnected patient.
     7. A pending proposal does not create an active medicine or dose events.
     8. A declined proposal does not change active medication.
     9. Patient acceptance activates the proposal through the existing medicine workflow.
     10. A revised proposal requires fresh acceptance.
     11. Previous accepted versions remain auditable.
     12. Doctors cannot mark doses TAKEN.
     13. Caregivers cannot mark doses TAKEN.
     14. Neither role can bypass photo verification.
     15. Cross-patient data access is rejected by Firebase security rules.
     16. Account deletion and data export handle Doctor Mode data correctly.
4. **Performance:** cold start, scrolling lists, verification latency; add baseline benchmark notes.
5. **Accessibility pass:** TalkBack labels, large font scale, colour contrast.
6. **Localisation readiness:** all strings externalised; add at least English; structure ready for Hindi/Bengali.
7. **Release engineering:** R8/ProGuard rules (keep TFLite/ML Kit/Firebase), signing config via `local.properties`/env (no keys in repo), version codes, app icon, adaptive icon, Play-store listing text draft, **data-safety form notes**, and a **health-app/exact-alarm declaration** note (🧑 HUMAN submits to Google Play).
8. **Disclaimers:** in-app and store listing – not a medical device, not medical advice, verification shows the pill left the strip only; MediTrack does not verify doctors or certifications.
9. Final run of `scripts/check.sh`; update `docs/ARCHITECTURE.md` and write `docs/USER_GUIDE.md`.

**Acceptance criteria**
- [ ] `docs/QA.md` checklist filled with results; failures listed with owners.
- [ ] All unit tests pass; lint has no errors.
- [ ] All 16 doctor and caregiver permission boundary tests pass (security rules + unit tests).
- [ ] Release build (`assembleRelease`) succeeds with minification and runs the full flow.
- [ ] **Fresh-clone test:** clone the repo into an empty folder, open in Android Studio, sync, press Run ▶ on a clean emulator → app launches and the full end-to-end script in the Verification Pack passes. Document the exact steps and result.
- [ ] Final `PROGRESS.md` lists every stage DONE and every open limitation.

**Reference links:**
- Google Play Console: https://play.google.com/console
- Shrink/obfuscate with R8: https://developer.android.com/build/shrink-code
- Android Studio: https://developer.android.com/studio
- Firebase Emulator security rules testing: https://firebase.google.com/docs/rules/unit-testing

**Verification Pack for this stage** (create `docs/verification/STAGE_14_VERIFY.md` per section 5.3; it must include at least):
- B: automated tests report covering the 16 permission boundary and Doctor Mode safety cases, including emulator rules tests.
- C: the full end-to-end acceptance script covering all features in one run-through, with expected results (patient, caregiver, and doctor flows).
- D: a signed-off checklist from `docs/QA.md`; a summary table of all stage packs and total test count.
- E: consolidated list of every open limitation and risk across stages.

---

## APPENDIX A – Quick prompt to give an AI agent

> You are building the MediTrack Android app. Read `README.md` fully. Follow the protocol in section 0. **Execute Stage N only.** When finished: build, run all tests, make sure the project opens, syncs and runs in Android Studio, generate the Verification Pack (`docs/verification/STAGE_N_VERIFY.md`: plain-language explanation, automated tests, manual test script, evidence checklist, limitations, Android Studio run steps), update `PROGRESS.md`, give the Stage Report, then stop and wait.

## APPENDIX B – Stage → flow-branch map

| Stage | Flow branch(es) |
|---|---|
| 2 | Onboarding, Permission setup, Home |
| 3 | 1 – Add Medicine |
| 4 | 2 – Medicine Reminder |
| 5, 6 | 3 – Pill Intake Verification (Photo) |
| 7 | 3 (late mode) + Missed handling |
| 8 | 4 – Medication History, 7 – Medication Calendar |
| 9 | 5 – Stock / Refill |
| 10 | 8 – Caregiver Mode (+ backend) |
| 10B | 10 – Doctor Mode |
| 11 | 6 – Scan Medicine / Prescription |
| 12 | 9 – Profile / Settings |
| 13 | ML model for Branch 3 |
| 14 | Hardening / release |

## APPENDIX C – Definition of "done" for the whole project

- Every stage has a Verification Pack in `docs/verification/`, and all automated tests (all stages combined) pass.
- The project opens in Android Studio, syncs cleanly and runs on an emulator and a real phone from a fresh clone.
- Every dataset used is credited in `ml/ATTRIBUTION.md` and in the app's Licences screen, and no test image was ever used for training.
- A patient can add a medicine, get an alarm, take a photo of the strip, and the dose is recorded **only** when the photo is clear and shows the right number of pills removed.
- Rejected photos never create a TAKEN record; three failures notify the caregiver.
- Missed doses can be recovered only with a verified photo and are marked **Taken Late**.
- Stock decreases only on verified doses; low stock notifies the caregiver.
- A linked doctor can propose, change and stop medicines and monitor doses; a doctor can never mark a dose taken, edit history, or see a patient who has not accepted (or has revoked) the link.
- No location, store search, or email-ordering functionality exists anywhere.
- History, calendar and adherence match the stored data.
- The app works offline and syncs when online; caregiver notifications arrive via FCM.

## APPENDIX D – Running MediTrack in Android Studio (copy into `docs/RUN_IN_ANDROID_STUDIO.md`)

### D.1 One-time setup
1. Install the latest stable **Android Studio** (it bundles the JDK and Android SDK tools).
2. *Settings → Build, Execution, Deployment → Build Tools → Gradle* → **Gradle JDK = Embedded JDK** (JBR 17+).
3. *Tools → SDK Manager* → install the latest **Android SDK Platform**, **Build-Tools**, **Platform-Tools**, and **Emulator**.
4. *Tools → Device Manager → Create Device* → pick a **Pixel** phone → choose a system image **with Google Play** (needed for FCM/notifications), API **34 or newer** → Finish. Also create one **API 26** device later for old-version checks.
5. (Optional but recommended) Use a **real Android phone**: enable *Developer options → USB debugging*, connect by USB, and accept the prompt. Real phones are much better for camera, alarms and battery behaviour.

### D.2 Open, sync, run
1. *File → Open* → select the project root folder → **Trust** the project.
2. Wait for **Gradle Sync** to finish (bottom status bar). Expected: *"Gradle sync finished"* with no red errors.
3. Pick the emulator/phone in the top device dropdown.
4. Press **Run ▶** (configuration `app`). The first build can take several minutes.
5. Expected: the app installs, launches, and shows the screens that exist for the current stage (see that stage's Verification Pack, Part F).

### D.3 Command-line equivalents (also what the AI agent runs)
```
./gradlew assembleDebug            # build the debug APK
./gradlew testDebugUnitTest        # run all unit tests
./gradlew lint                     # static checks
./gradlew installDebug             # install on connected device/emulator
```
Test reports: `app/build/reports/tests/testDebugUnitTest/index.html`.

### D.4 Watching the app run (Logcat)
- Open **Logcat** (bottom tab) → filter `tag:MediTrack` → select your app's process.
- Key lines to expect appear in each stage's Part F (e.g. "Alarm scheduled…", "Verification result: REJECTED reason=PILLS_NOT_REMOVED").

### D.5 Testing special features on an emulator
| Feature | How to test on the emulator | Note |
|---|---|---|
| Notifications (Android 13+) | Accept the permission prompt; if missed: *Settings → Apps → MediTrack → Notifications* | – |
| Exact alarms | *Settings → Apps → Special app access → Alarms & reminders* → allow MediTrack if required | Use the Debug Tools "Trigger alarm now" button to avoid waiting |
| Alarm list | `adb shell dumpsys alarm` and look for `com.meditrack.app` | Shows scheduled alarms |
| Reboot reschedule | Cold-boot the emulator, or `adb shell am broadcast -a android.intent.action.BOOT_COMPLETED` (may require device/root support) | Prefer a real reboot |
| Doze mode | `adb shell dumpsys deviceidle force-idle` (and `adb shell dumpsys deviceidle unforce` to reset) | Alarms should still fire |
| Camera | Emulator → *Extended controls (⋯) → Camera* → use **Virtual scene** or a **Webcam** | **Strip verification must be finally tested on a real phone** |
| Time/date changes | *Settings → System → Date & time* (disable automatic) | Use for missed-dose and late-entry tests, or the Debug Tools fake clock |
| Offline mode | Emulator toolbar/quick settings → turn on **Airplane mode** | For sync/outbox tests |
| Two-user caregiver test | Two emulators, or one emulator + one real phone, each signed in as a different account | FCM needs Google Play images |

### D.6 Common problems and fixes
| Symptom | Likely cause | Fix |
|---|---|---|
| Sync error: "SDK location not found" | Missing `local.properties` | Android Studio creates it; or set `sdk.dir` |
| Sync error: Java version / JDK | Wrong Gradle JDK | Set Gradle JDK to **Embedded JDK** |
| "Plugin google-services … missing google-services.json" | Firebase file not added | Stage 10 only: place `google-services.json` in `app/`, or use the Fake backend in debug |
| App crashes immediately | Missing Hilt binding / manifest entry / permission | Read the Logcat stack trace; send it to the agent |
| Alarms never fire | Exact-alarm access off, or battery restrictions | Allow *Alarms & reminders*; disable battery optimisation for the app |
| Notifications don't show | Permission denied or channel muted | Re-enable in app notification settings |
| Camera black on emulator | No virtual scene/webcam selected | Set *Extended controls → Camera* |
| "INSTALL_FAILED_…" | Old build conflicts | Uninstall the app from the emulator and Run again |

### D.7 What to send back to the AI agent if something fails
1. The **Build** output (full text of the first error).
2. The **Logcat** crash trace (`FATAL EXCEPTION` section).
3. Which stage, which device/API level, and which step of the manual test script failed.

## APPENDIX E – All reference links in one place

> Official entry points. If a link has moved or does not open, report it in the Stage Report and search for the current page.

**Datasets (ML)**
| Name | Link | Licence / note |
|---|---|---|
| Blister Pills Segmentation (primary label source) | https://universe.roboflow.com/blisterdetection/blister-pills-segmentation | Verify licence on page |
| Pharmassist (real-world used strips) | https://data.mendeley.com/datasets/zbnhmbbymd/1 | CC BY 4.0 |
| Medication Blister Classification (KNOK) | https://universe.roboflow.com/knok/computer-vision-for-medication-blister-classification | Verify |
| Pill_Dataset_600 | https://universe.roboflow.com/phms-workspace-ptgtz/pill_dataset_600 | CC BY 4.0 |
| Pill_Dataset_800 | https://universe.roboflow.com/phms-workspace-ptgtz/pill_dataset_800 | CC BY 4.0 |
| blister_pack_28th_aug_2023 (SASTRA) | https://universe.roboflow.com/sastra-qv7ma/blister_pack_28th_aug_2023 | CC BY 4.0 |
| blister packet identification (SASTRA) | https://universe.roboflow.com/sastra-deemed-university/blister-packet-identification | CC BY 4.0 |
| pill (cc-f6245) | https://universe.roboflow.com/cc-f6245/pill-l5nxe | Verify |
| Blister_Pill_Detection | https://universe.roboflow.com/varun-sundar/blister_pill_detection | CC BY 4.0 |
| NIH C3PI (not suitable) | https://datadiscovery.nlm.nih.gov/d/5jdf-gdqh | ODbL; sunset 2018 |
| Ultralytics medical-pills (not suitable) | https://docs.ultralytics.com/datasets/detect/medical-pills | AGPL-3.0 |
| VAIPE paper (not suitable) | https://arxiv.org/abs/2303.09782 | – |

**ML tooling**
- Roboflow docs: https://docs.roboflow.com
- Ultralytics docs (AGPL-3.0 – review before commercial use): https://docs.ultralytics.com
- LiteRT / TensorFlow Lite: https://ai.google.dev/edge/litert
- ML Kit text recognition (Android): https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- CVAT: https://www.cvat.ai · Label Studio: https://labelstud.io

**Android**
- Android Studio: https://developer.android.com/studio
- Jetpack Compose: https://developer.android.com/jetpack/compose · Material 3: https://m3.material.io
- Navigation Compose: https://developer.android.com/develop/ui/compose/navigation
- Hilt: https://developer.android.com/training/dependency-injection/hilt-android
- Room: https://developer.android.com/training/data-storage/room
- DataStore: https://developer.android.com/topic/libraries/architecture/datastore
- WorkManager: https://developer.android.com/topic/libraries/workmanager
- Schedule alarms: https://developer.android.com/develop/background-work/services/alarms
- Exact alarm changes: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- Notification runtime permission: https://developer.android.com/develop/ui/views/notifications/notification-permission
- Notification channels: https://developer.android.com/develop/ui/views/notifications/channels
- CameraX: https://developer.android.com/media/camera/camerax
- Photo picker: https://developer.android.com/training/data-storage/shared/photopicker
- Emulator: https://developer.android.com/studio/run/emulator · adb: https://developer.android.com/tools/adb
- R8 shrinking: https://developer.android.com/build/shrink-code
- Gradle version catalogs: https://docs.gradle.org/current/userguide/platforms.html
- Kotlin coroutines: https://kotlinlang.org/docs/coroutines-overview.html

**Firebase / backend**
- Docs: https://firebase.google.com/docs · Console: https://console.firebase.google.com
- Auth: https://firebase.google.com/docs/auth
- Firestore rules: https://firebase.google.com/docs/firestore/security/get-started
- Storage: https://firebase.google.com/docs/storage
- Cloud Messaging (Android): https://firebase.google.com/docs/cloud-messaging/android/client
- Cloud Functions: https://firebase.google.com/docs/functions
- Emulator Suite: https://firebase.google.com/docs/emulator-suite

**Release & compliance**
- Google Play Console (health-app declaration, data-safety form, exact-alarm declaration live under *App content* / *Policy*): https://play.google.com/console
- SQLCipher for Android: https://www.zetetic.net/sqlcipher/sqlcipher-for-android/
- India DPDP Act, 2023 (MeitY): https://www.meity.gov.in

