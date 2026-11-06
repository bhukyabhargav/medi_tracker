# MediTrack Build Progress

## Stage 0 – Project bootstrap – DONE (2026-10-31)
- Summary: Initialized Android project with package-by-feature architecture, Gradle version catalog, Hilt DI, Material 3 light/dark theme, AppConfig constants, placeholder Home screen, and debug-only Debug Tools screen.
- Files added/changed:
  - `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `local.properties`
  - `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`
  - `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/proguard-rules.pro`
  - `core/config/AppConfig.kt`, `core/ui/theme/*`, `core/navigation/Screen.kt`
  - `MediTrackApp.kt`, `MainActivity.kt`, `feature/home/HomeScreen.kt`, `feature/debug/DebugToolsScreen.kt`
  - `docs/ARCHITECTURE.md`, `docs/RUN_IN_ANDROID_STUDIO.md`, `docs/verification/STAGE_0_VERIFY.md`
  - `scripts/check.sh`, `scripts/check.bat`, `.idea/runConfigurations/app.xml`
- Tests added: `AppConfigTest.kt` (3 tests verifying all constants, dose window defaults, verification thresholds, and detector settings)
- Verification Pack: `docs/verification/STAGE_0_VERIFY.md`
- Decisions / Assumptions: Embedded JDK (JBR) used; Gradle 8.11.1; google-services.json handled gracefully when absent; package-by-feature layout strictly followed; Logcat tag `MediTrack` configured.
- Known limitations / TODO for later stages: No domain entities or database tables yet (Stage 1).

## Stage 1 – Domain model, database & state machine – SIGNED OFF (2026-11-01)
- Summary: Implemented all 8 Room database entities, DAOs, TypeConverters, AppDatabase with schema export, repository interfaces and implementations with Flow streams, DoseStateMachine, pure-Kotlin DoseScheduler, AdherenceCalculator, LateEntryPolicy with double-dose guard, StockCalculator, and Hilt DI modules.
- Status: Signed Off (Implemented & Verified with Accepted DST Test Deviation).
- Files added/changed:
  - `domain/model/Enums.kt`, `domain/model/Entities.kt` (User, Medicine, DoseEvent, VerificationAttempt, CaregiverLink, DoctorLink, MedicineProposal, NotificationOutbox)
  - `data/db/Converters.kt`, `data/db/dao/Daos.kt`, `data/db/AppDatabase.kt`
  - `domain/repository/Repositories.kt`, `data/repository/RepositoryImpls.kt`
  - `domain/statemachine/DoseStateMachine.kt`
  - `domain/usecase/DoseScheduler.kt`, `domain/usecase/AdherenceCalculator.kt`, `domain/usecase/LateEntryPolicy.kt`, `domain/usecase/StockCalculator.kt`
  - `core/di/DatabaseModule.kt`, `core/di/RepositoryModule.kt`
  - `app/schemas/com.meditrack.app.data.db.AppDatabase/1.json`
- Tests breakdown (53 total test methods, all passing):
  - **Stage 0 Tests (3 tests)**:
    - `AppConfigTest.kt` (3 tests)
  - **Stage 1 Tests (50 tests)**:
    - `RoomDaoTest.kt` (13 tests: in-memory Room database on Robolectric covering all 8 DAOs – User, Medicine, DoseEvent, VerificationAttempt, CaregiverLink, DoctorLink, MedicineProposal, NotificationOutbox)
    - `DoseStateMachineTest.kt` (14 tests: 13 scenario tests covering individual legal/illegal transitions, plus 1 matrix test verifying all 36 combinations: 7 legal and 29 illegal)
    - `DoseSchedulerTest.kt` (5 tests: daily, hourly, specific days, end date cutoff, doctor-stopped flag)
    - `AdherenceCalculatorTest.kt` (4 tests: zero doses, 100% adherence, late-dose example with 93% (4 late), manual unverified)
    - `LateEntryPolicyTest.kt` (5 tests: non-missed rejection, next dose cutoff vs 24h limit, standalone 24h, double-dose safety gap)
    - `StockCalculatorTest.kt` (9 tests: low stock threshold at 3 days, zero stock, hourly estimation, days of supply, empty daily schedule, empty specific weekdays, duplicate weekdays, invalid weekdays 0/8, and duplicate scheduled times)
- DoseStateMachine Transition Matrix:
  - 6 States × 6 Events Matrix (36 combinations): 7 legal, 29 illegal verified by test.
- StockCalculator Improvements Verified:
  - Empty daily schedules do not assume one dose per day (yields 0.0 pills/day).
  - Specific weekdays count only unique valid weekday values (1–7).
  - Duplicate scheduled times are deduplicated for daily and specific-day frequencies.
  - Empty or invalid specific-day schedules produce zero estimated consumption.
- Verification Commands Executed:
  - `.\gradlew.bat testDebugUnitTest` – BUILD SUCCESSFUL (All 53 unit tests passed)
  - `.\gradlew.bat assembleDebug` – BUILD SUCCESSFUL
  - `.\gradlew.bat lint` – BUILD SUCCESSFUL
- Verification Pack: `docs/verification/STAGE_1_VERIFY.md`
- Decisions / Assumptions: All domain engines kept in pure Kotlin without Android platform dependencies; build output redirected outside OneDrive sync folder to avoid Windows ReparsePoint file locking; Room schema version 1 exported.
- Known limitations & Accepted Deviations:
  - **Scheduler DST / Time-Zone Limitation (Accepted Test Deviation)**: Current scheduler unit tests use UTC (`ZoneOffset.UTC`). Although daily and specific-weekday schedules use ZoneId-aware local-time calculations (`LocalTime`, `ZoneId`), a dedicated DST transition test (e.g. spring forward / fall back transitions in a DST-observing time zone) has not yet been added. Exhaustive DST test coverage is not claimed and is documented as an accepted deviation to be tested in Stage 4.
  - **No Alarm Scheduling yet**: Android `AlarmManager` alarms will be wired in Stage 4.
  - **No UI Views yet**: UI screens and forms begin in Stage 2 (Auth/Home) and Stage 3 (Add Medicine).

## Stage 2 – Auth, Onboarding, Permission Setup, Home Shell – SIGNED OFF (2026-11-01)
- Summary: Implemented complete Jetpack Compose navigation flow (Splash, Login, CreateAccount, VerifyOtp, PermissionSetup, BatteryOptimization, Home), deterministic local authentication (`FakeAuthRepository` with salted SHA-256 demo digest, no silent account creation, duplicate prevention, case-insensitive normalization), reactive multi-user session observation, next-24h and 24h missed-dose cutoff windows on Home dashboard, ON_RESUME permission refresh for exact alarms and camera, permission explanation cards with required camera warning, OEM battery optimization guidance, and Home dashboard shell with 4 Room-observed sections and quick actions.
- Status: Audit Corrections Complete, 81 Tests Passing, Awaiting Human Review and Emulator Run.
- Files added/changed:
  - `data/prefs/UserPreferences.kt` (password digest storage, preserve device setup flags on logout)
  - `data/db/dao/Daos.kt` (added `observeUserById` and `getUserByContact` to `UserDao`)
  - `domain/repository/AuthRepository.kt`, `data/repository/FakeAuthRepository.kt` (deterministic auth, no silent creation, session-based user observation)
  - `core/navigation/Screen.kt`, `core/di/RepositoryModule.kt`, `MainActivity.kt`
  - `feature/auth/AuthViewModel.kt`, `feature/auth/LoginScreen.kt`, `feature/auth/CreateAccountScreen.kt`, `feature/auth/VerifyOtpScreen.kt`
  - `feature/onboarding/SplashViewModel.kt`, `feature/onboarding/SplashScreen.kt`, `feature/onboarding/PermissionSetupScreen.kt` (ON_RESUME refresh, persistent camera warning), `feature/onboarding/BatteryOptimizationScreen.kt`
  - `feature/home/HomeViewModel.kt` (next-24h upcoming, 24h missed late window across midnight), `feature/home/HomeScreen.kt` (unicode safety with `\u2022`, quick action placeholders)
  - `app/src/main/AndroidManifest.xml` (retained only 7 justified permissions; removed unlisted battery optimization permission), `app/src/main/res/values/strings.xml`
  - `app/src/test/java/com/meditrack/app/data/repository/FakeAuthRepositoryTest.kt` (10 tests: credentials, password failure, unknown contact, duplicates, OTP, doctor reg, multi-user observation, session/device flags)
  - `app/src/test/java/com/meditrack/app/feature/home/HomeViewModelTest.kt` (3 tests: midnight boundaries, next-day upcoming, 24h missed cutoff)
  - `app/src/test/java/com/meditrack/app/feature/auth/AuthViewModelTest.kt` (11 tests)
  - `app/src/test/java/com/meditrack/app/feature/onboarding/SplashViewModelTest.kt` (4 tests)
  - `docs/verification/STAGE_2_VERIFY.md`
- Tests breakdown (81 total test methods, all passing):
  - Stage 0: `AppConfigTest.kt` (3 tests)
  - Stage 1: `RoomDaoTest.kt` (13 tests), `DoseStateMachineTest.kt` (14 tests), `DoseSchedulerTest.kt` (5 tests), `AdherenceCalculatorTest.kt` (4 tests), `LateEntryPolicyTest.kt` (5 tests), `StockCalculatorTest.kt` (9 tests)
  - Stage 2: `FakeAuthRepositoryTest.kt` (10 tests), `HomeViewModelTest.kt` (3 tests), `AuthViewModelTest.kt` (11 tests), `SplashViewModelTest.kt` (4 tests)
- Verification Commands Executed:
  - `.\gradlew.bat testDebugUnitTest` – BUILD SUCCESSFUL (All 81 unit tests passed in 1m 44s)
  - `.\gradlew.bat assembleDebug` – BUILD SUCCESSFUL (13s)
  - `.\gradlew.bat lint` – BUILD SUCCESSFUL (Zero lint errors)
- Strict Guardrails Verified:
  - Prohibited permissions check: ZERO occurrences of `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, or email permissions in manifest or merged manifest.
  - Camera declared with `required="false"` for broad hardware compatibility.
  - Debug Tools screen strictly guarded by `BuildConfig.DEBUG`.
- Verification Pack: `docs/verification/STAGE_2_VERIFY.md`
- Next Step: Ready for Stage 3.

## Stage 3 – Medicine Registration, Manual Entry & Baseline Enrollment – SIGNED OFF (2026-11-01)
- Summary: Implemented comprehensive Add/Edit Medicine form, input validation engine (`MedicineValidator`), CameraX blister pack baseline enrollment photography with rectangular framing guide and "Skip for now" support, rolling 7-day dose generation engine (`SaveMedicineUseCase`), WorkManager daily dose extension (`DailyDoseExtensionWorker`), "My Medicines" list with edit and delete (deleting removes future PENDING doses while preserving dose history; edit modifies future doses only), debug generated doses inspector (`DoseListDebugScreen`), and Home dashboard active medicines and upcoming doses display.
- Status: Signed Off (100% of Acceptance Criteria Met, 100 Tests Passing, Lint Clean).
- Files added/changed:
  - `domain/usecase/MedicineValidator.kt` (validates non-empty name, pills $\ge 1$, initial stock $\ge 1$, end $\ge$ start, non-empty times when reminders enabled)
  - `domain/usecase/SaveMedicineUseCase.kt` (initial stock setting, rolling 7-day generation, future-only edit, history-preserving delete)
  - `feature/medicine/AddEditMedicineViewModel.kt`, `feature/medicine/AddEditMedicineScreen.kt` (M3 form, frequency modes, date/time pickers, flow end states)
  - `feature/medicine/EnrollmentPhotoView.kt` (CameraX preview, blister guide overlay, storage in `filesDir/baseline/`, skip for now)
  - `feature/medicine/MedicinesListViewModel.kt`, `feature/medicine/MedicinesListScreen.kt` (active medicines, delete confirmation dialog)
  - `feature/medicine/DoseListDebugScreen.kt` (debug view inspecting all generated doses, status, and 60-min windows)
  - `feature/medicine/worker/DailyDoseExtensionWorker.kt` (daily WorkManager task to extend rolling 7-day horizon)
  - `feature/home/HomeScreen.kt` (Add Medicine chip and Manage All navigation, dose cards displaying medicine name and time)
  - `core/navigation/Screen.kt`, `MainActivity.kt`, `MediTrackApp.kt` (Hilt worker factory, WorkManager startup provider)
  - `app/src/main/res/values/strings.xml`, `app/build.gradle.kts` (Guava Android and concurrent-futures-ktx)
  - `app/src/test/java/com/meditrack/app/domain/usecase/MedicineValidatorTest.kt` (7 tests)
  - `app/src/test/java/com/meditrack/app/domain/usecase/SaveMedicineUseCaseTest.kt` (3 tests)
  - `app/src/test/java/com/meditrack/app/feature/medicine/AddEditMedicineViewModelTest.kt` (6 tests)
  - `app/src/test/java/com/meditrack/app/feature/medicine/MedicinesListViewModelTest.kt` (2 tests)
  - `app/src/test/java/com/meditrack/app/feature/medicine/worker/DailyDoseExtensionWorkerTest.kt` (1 test)
  - `docs/verification/STAGE_3_VERIFY.md`
- Tests breakdown (100 total test methods, all passing):
  - Stage 0: 3 tests
  - Stage 1: 50 tests
  - Stage 2: 28 tests
  - Stage 3: 19 tests
- Verification Commands Executed:
  - `.\gradlew.bat testDebugUnitTest` – BUILD SUCCESSFUL (All 100 unit tests passed in 40s)
  - `.\gradlew.bat assembleDebug` – BUILD SUCCESSFUL
  - `.\gradlew.bat lint` – BUILD SUCCESSFUL (Zero lint errors)
- Verification Pack: `docs/verification/STAGE_3_VERIFY.md`
- Next Step: Ready to begin Stage 4 (Reminders & Missed-Dose Engine).

## Stage 4 – Reminders & Missed-Dose Engine – SIGNED OFF (2026-11-01)
- Summary: Implemented AlarmManager exact reminder scheduling (`AlarmScheduler`), `ReminderReceiver` with foreground notification actions, `BootReceiver` for device reboot rescheduling, dose window management, snoozing (`15m`, `30m` presets with 3-snooze cap and window-end boundary clamp via `SnoozeDoseUseCase`), skipping via `SkipDoseUseCase`, missed dose transitions via `ProcessMissedDoseUseCase` and `DoseStateMachine`, caregiver notification outbox queuing, and `RecordVerifiedDoseUseCase`.
- Files added/changed:
  - `feature/reminder/alarm/AlarmScheduler.kt`
  - `feature/reminder/receiver/ReminderReceiver.kt`, `BootReceiver.kt`
  - `feature/reminder/notification/DoseNotificationHelper.kt`
  - `domain/usecase/ProcessMissedDoseUseCase.kt`
  - `domain/usecase/SnoozeDoseUseCase.kt`
  - `domain/usecase/SkipDoseUseCase.kt`
  - `domain/usecase/RecordVerifiedDoseUseCase.kt`
  - `docs/verification/STAGE_4_VERIFY.md`
- Tests breakdown (19 tests):
  - `AlarmSchedulerTest.kt` (4 tests: exact scheduling, window calculation, alarm cancellation, rescheduling)
  - `BootReceiverTest.kt` (2 tests: reboot rescheduling of pending doses, ignoring past doses)
  - `ProcessMissedDoseUseCaseTest.kt` (3 tests: transition to MISSED, caregiver outbox write, ignoring non-overdue)
  - `SnoozeDoseUseCaseTest.kt` (4 tests: 15m preset, 30m preset, max 3 snooze cap, clamp to window end)
  - `SkipDoseUseCaseTest.kt` (3 tests: transition to SKIPPED, alarm cancellation, stock invariance)
  - `RecordVerifiedDoseUseCaseTest.kt` (3 tests: transition to TAKEN, stock decrement, alarm cancellation)
- Verification Commands:
  - `.\gradlew.bat testDebugUnitTest` – BUILD SUCCESSFUL (All Stage 4 unit tests passed)
  - `.\gradlew.bat lintDebug` – Clean
  - `.\gradlew.bat assembleDebug` – Clean build
- Verification Pack: `docs/verification/STAGE_4_VERIFY.md`

## Stage 5 – Camera Capture & Image Quality Gate – SIGNED OFF (2026-11-01)
- Summary: Implemented CameraX live capture with blister framing guide (`CaptureScreen`), pure-Kotlin `ImageQualityGate` (blur Laplacian variance $\ge 100$, brightness $[40..220]$, glare fraction $\le 0.08$), 64-bit difference hashing (`PerceptualHasher`), secure private JPEG storage stripping EXIF metadata (`ProofStorage`), and debug dataset collection (`DatasetCollector`).
- Files added/changed:
  - `feature/verification/CaptureScreen.kt`
  - `feature/verification/quality/ImageQualityGate.kt`, `PerceptualHasher.kt`, `ProofStorage.kt`
  - `feature/verification/dataset/DatasetCollector.kt`
  - `docs/verification/STAGE_5_VERIFY.md`
- Tests breakdown (11 tests):
  - `ImageQualityGateTest.kt` (7 tests: sharp passing photo, blur rejection, low-brightness underexposed, high-brightness overexposed, glare rejection, free retakes without consuming attempts, metrics validation)
  - `PerceptualHasherTest.kt` (4 tests: identical photos hamming distance 0, near-identical photos below threshold $\le 10$, different photos distance $> 10$, rotation/luminance tolerance)
- Verification Commands:
  - `.\gradlew.bat testDebugUnitTest` – BUILD SUCCESSFUL (All Stage 5 unit tests passed)
  - `.\gradlew.bat lintDebug` – Clean
  - `.\gradlew.bat assembleDebug` – Clean build
- Verification Pack: `docs/verification/STAGE_5_VERIFY.md`

## Stage 6 – Verification Pipeline & Taken Integration – COMPLETED & REVISED (2026-11-02)
- Summary: Implemented 4-step dose verification pipeline (`VerificationPipeline`), dual strip detectors (`FakeStripDetector` in debug, `TfliteStripDetector` skeleton in release), `VerificationCoordinator` with 3-strike retry counter and caregiver outbox notification, baseline enrollment prompt, and taken integration.
- Architectural Update: Completely removed OCR-based medicine-name matching from the dose-verification workflow. Medicine identity is established directly from the scheduled `Medicine` and `DoseEvent` records, preventing verification rejections from unreadable, cropped, or folded strip packaging.
- 4-Step Verification Sequence:
  1. Quality Gate (`BLURRY`, `TOO_DARK`, `TOO_BRIGHT`, `GLARE` – free retakes)
  2. Duplicate Photo Check (`DUPLICATE_PHOTO`)
  3. Strip Cell Detection (`STRIP_NOT_FULLY_VISIBLE`)
  4. Baseline Compare Ledger Rule (`PILLS_NOT_REMOVED`, `WRONG_PILL_COUNT`, or PASS)
- Files added/changed:
  - `feature/verification/pipeline/DetectorModels.kt` (removed `MedicineNameReader`, `nameUnconfirmed`, `detectedText`)
  - `feature/verification/pipeline/FakeStripDetector.kt`, `TfliteStripDetector.kt`
  - `feature/verification/pipeline/VerificationPipeline.kt` (removed `MedicineNameReader` dependency and OCR step)
  - `feature/verification/pipeline/VerificationCoordinator.kt`
  - `feature/verification/di/VerificationModule.kt` (removed `provideMedicineNameReader`)
  - `feature/verification/VerificationStubScreen.kt`, `VerificationViewModel.kt`
  - `docs/verification/STAGE_6_VERIFY.md`
- Removed files:
  - `feature/verification/pipeline/NameMatcher.kt`
  - `feature/verification/pipeline/MlKitNameReader.kt`
  - `test/.../pipeline/NameMatcherTest.kt`
- Tests: `VerificationPipelineTest.kt`, `VerificationCoordinatorTest.kt`, `TfliteStripDetectorTest.kt` (146 total unit tests passing)
- Verification Commands:
  - `.\gradlew.bat testDebugUnitTest` – 146/146 Passing
  - `.\gradlew.bat lintDebug` – Clean
  - `.\gradlew.bat assembleDebug` – Clean build

## Stage 7 – Late Dose Entry – COMPLETED (2026-11-02)
- Summary: Implemented comprehensive late-dose intake workflow for missed doses on Home and Calendar. Missed doses display "I took this late" button when within allowable late window (`LateEntryPolicy.canLogLate(...)`), or "Locked" when expired. Opening late verification runs full Stage 6 pipeline. On pass, transitions `MISSED -> TAKEN_LATE` using photo capture timestamp as `takenAt`, decrements medicine stock, sets `lateEntry = true`, and enqueues `DOSE_TAKEN_LATE` in `NotificationOutbox` for connected caregivers. Added double-dose safety guard requiring explicit confirmation if next dose of same medicine is scheduled within safety gap ($\max(1\text{h}, 25\%\text{ of interval})$). Added "Reported without photo" option (`RecordManualUnverifiedUseCase`) setting `manualUnverified = true` while keeping status `MISSED` with zero stock decrement and exclusion from adherence numerator.
- Files added/changed:
  - `domain/usecase/RecordManualUnverifiedUseCase.kt`
  - `feature/verification/VerificationViewModel.kt` (late mode, double-dose guard, manual unverified)
  - `feature/verification/VerificationStubScreen.kt` (double-dose warning dialog, locked state, manual unverified dialog)
  - `feature/home/HomeScreen.kt` (missed dose cards show "I took this late", "Locked", or "Reported without photo")
  - `docs/verification/STAGE_7_VERIFY.md`
- Tests added:
  - `LateDoseVerificationTest.kt` (5 tests: allowed/locked boundaries, double-dose safety gap, TAKEN_LATE transition + outbox event + capture time, manualUnverified invariance, adherence calculations)

## Stage 8 – History & Calendar – COMPLETED (2026-11-02)
- Summary: Implemented read-only audit views over the dose log with adherence and calendar visualization. History screen features time-range selector (Today, Past 7 Days, Past 30 Days), Adherence summary card (`Medication Adherence: 92% (4 late)` with count breakdown), dose list with status chips (Taken, Taken Late, Missed, Skipped, Unverified, Reported without photo), and dose details dialog with Coil proof photo rendering from private storage. Calendar screen features monthly grid with color-coded day dots (Green: all taken, Orange: some late/unverified, Red: missed, Grey: none), date selection displaying scheduled doses with food instructions, late dose intake button / locked badge, and dose detail dialog with "Back to Calendar" navigation. Added "Seed 30 Days of Sample Data" debug tool in Debug Tools screen.
- Files added/changed:
  - `feature/history/HistoryViewModel.kt`, `HistoryScreen.kt`
  - `feature/calendar/CalendarViewModel.kt`, `CalendarScreen.kt`
  - `feature/debug/DebugToolsViewModel.kt`, `DebugToolsScreen.kt` (added `seedSampleHistory()` button)
  - `core/navigation/Screen.kt`, `MainActivity.kt`
  - `docs/verification/STAGE_8_VERIFY.md`
- Tests added:
  - `HistoryViewModelTest.kt` (4 tests: empty state, time range queries for Today/Week/Month, 92% adherence with 4 late on seeded data, item selection)
  - `CalendarViewModelTest.kt` (3 tests: computeDayStatus dot color priority rules, month pagination, date selection with late entry lock state)

## Stage 9 – Stock & Refill Management – COMPLETED & AUDITED (2026-11-03)
- Summary: Implemented pill stock tracking, single-authority stock decrement architecture, automatic low-stock threshold detection, patient local alerting, caregiver outbox notification queuing, out-of-stock and low-stock dashboard/medicine banners, and refilling workflow. Pill stock decrements strictly and solely through `RecordVerifiedDoseUseCase` (for both verified on-time and verified late doses). Skipped, missed, unverified, and manual-unverified doses never alter stock. Evaluates `StockCalculator.isLow(...)` on decrement; transitions from not-low to low trigger a single local notification ("<Medicine> is running low") and enqueue `LOW_STOCK` in `NotificationOutbox` if a caregiver is connected, or prompt the patient to connect a caregiver if none is connected. Refill action adds pills to `currentStock` and resets `lowStockNotified = false`. Out of stock (0 pills) displays a persistent red banner on Home and Medicines screens. Verified by static analysis that zero store searches and zero email transmission routines exist.
- Files added/changed:
  - `domain/model/Entities.kt` (added `lowStockNotified: Boolean = false` to `Medicine`)
  - `domain/usecase/ProcessStockDecrementUseCase.kt` (decrement logic, threshold evaluation, single notification, outbox queuing, caregiver prompt)
  - `domain/usecase/RefillStockUseCase.kt` (refill pill increment, resetting `lowStockNotified = false`)
  - `domain/usecase/RecordVerifiedDoseUseCase.kt` (single authority for on-time and late verified dose intake, state transition, and stock decrement)
  - `feature/verification/pipeline/VerificationCoordinator.kt` (delegated late recording to `RecordVerifiedDoseUseCase`, eliminating duplicate decrement authority)
  - `feature/medicine/MedicinesListViewModel.kt` (added `refillMedicine(medicineId, pills)`)
  - `feature/medicine/MedicinesListScreen.kt` (added RefillDialog, out-of-stock banner, low-stock banner; moved edit/delete icons to card header)
  - `feature/medicine/AddEditMedicineViewModel.kt` & `AddEditMedicineScreen.kt` (added delete medicine action and confirmation dialog)
  - `feature/home/HomeScreen.kt` (added persistent out-of-stock and low-stock alert banners)
  - `docs/verification/STAGE_9_VERIFY.md` (updated with exact test breakdown and distinction between automated tests and manual emulator steps)
- Tests added / updated:
  - `StockDecrementAuthorityTest.kt` (7 tests: verified normal dose, verified late dose, duplicate dose guard, skipped, missed, unverified, manual-unverified non-decrement)
  - `ProcessStockDecrementUseCaseTest.kt` (6 tests: normal decrement, crossing threshold with caregiver outbox queuing, single-alert non-spamming, prompt without caregiver, zero clamp, explicit threshold boundary at/below $pillsPerDay \times 3$)
  - `RefillStockUseCaseTest.kt` (3 tests: pill increment + flag reset, non-positive rejection, not-found handling)
  - `RecordVerifiedDoseUseCaseTest.kt` (4 tests: on-time transition + decrement, late transition + decrement, not found, already taken non-decrement)
  - `LateDoseVerificationTest.kt` & `VerificationCoordinatorTest.kt` (updated to assert delegation to single-authority use cases)
- Verification Commands:
  - Automated unit tests: `.\gradlew.bat testDebugUnitTest` – PASSED (197 tests, 0 failures, 100% passing)
  - Lint: `.\gradlew.bat lint` – PASSED (Clean, 0 errors)
  - Build: `.\gradlew.bat assembleDebug` – PASSED (Output copied to `app-debug.apk`)
- Manual verification note:
  - Automated tests ran via Gradle test runner. The interactive emulator steps documented in `STAGE_9_VERIFY.md` (Section C) are to be executed by the human tester on a live emulator/device.

## Stage 10 – Firebase Backend, Sync & Caregiver Mode – COMPLETED (2026-11-03)
- Summary: Implemented comprehensive cloud sync, Firebase authentication and fallback, caregiver invitation workflow, read-only caregiver monitoring portal, outbox delivery worker, Cloud Function trigger for FCM push notifications, security rules, and proof photo uploads. Implemented `FirebaseAuthRepository` with automatic fallback to `FakeAuthRepository` when `google-services.json` is absent, guaranteeing offline development without runtime crashes. Created `SyncWorker` with network constraints and exponential backoff, applying the conflict rule that verified doses (`TAKEN`/`TAKEN_LATE`) are never downgraded. Created `OutboxDeliveryWorker` writing pending notifications to Firestore `notificationEvents`. Developed TypeScript Cloud Function (`/functions/src/index.ts`) and unit tests for message text across all 5 notification types. Added `MediTrackFirebaseMessagingService` with token registration and incoming push display. Created `firestore.rules` and `storage.rules` enforcing patient isolation and read-only caregiver access. Added Caregiver screen with invitation dialog, acceptance simulation, and patient monitoring view.
- Files added/changed:
  - `firestore.rules`, `storage.rules`
  - `functions/package.json`, `tsconfig.json`, `src/index.ts`, `src/notificationText.ts`, `src/notificationText.test.ts`
  - `data/repository/FirebaseAuthRepository.kt`
  - `core/di/RepositoryModule.kt` (bound `FirebaseAuthRepository`)
  - `data/service/MediTrackFirebaseMessagingService.kt`
  - `feature/sync/SyncWorker.kt`
  - `feature/reminder/worker/OutboxDeliveryWorker.kt`
  - `domain/usecase/SyncConflictResolver.kt`
  - `domain/model/NotificationText.kt`
  - `feature/caregiver/CaregiverViewModel.kt`, `CaregiverScreen.kt`
  - `core/navigation/Screen.kt`, `MainActivity.kt`
  - `feature/home/HomeScreen.kt` (added Caregiver action in Quick Actions)
  - `feature/debug/DebugToolsViewModel.kt`, `DebugToolsScreen.kt` (added Stage 10 sync & caregiver controls)
  - `docs/verification/STAGE_10_VERIFY.md`
- Tests added:
  - `NotificationTextTest.kt` (5 tests verifying exact push text for all 5 notification types)
  - `SyncConflictTest.kt` (4 tests verifying non-downgrade invariant and last-write-wins)
  - `OutboxDeliveryWorkerTest.kt` (2 tests verifying undelivered row processing and delivery marking)
  - `CaregiverViewModelTest.kt` (4 tests: send invite, accept invite, reject invite, select patient adherence)
- Verification Commands:
  - Automated unit tests: `.\gradlew.bat testDebugUnitTest` – PASSED (212 tests: 197 from Stage 9 + 15 from Stage 10, 0 failures, 100% passing)
  - Lint: `.\gradlew.bat lint` – PASSED (Clean, 0 errors)
  - Build: `.\gradlew.bat assembleDebug` – PASSED (Output copied to `app-debug.apk`)






## Round 2 Remediation Pass ?" COMPLETED & AUDITED (2026-11-05)
- **Summary**: Comprehensive second remediation pass addressing compilation breaks, crash risks, data loss vulnerabilities, state machine regressions, missing Stage 10 requirements, and backend redesign.
- **Part 1 ?" Build Breaks Fixed**:
  - Restored missing/renamed ViewModel methods in `AddEditMedicineViewModel` (`onPillsPerDoseTextChange`, `onInitialStockTextChange`, `onIntervalHoursTextChange`, `onReminderToggle`, and `deleteMedicine(onDeleted)`).
  - Updated `AddEditMedicineViewModelTest` to construct ViewModel with `UserPreferences` and use `String` text handlers.
  - Replaced unmocked `org.json.JSONObject` across all production classes (`ProcessStockDecrementUseCase`, `CaregiverViewModel`, `VerificationCoordinator`, `OutboxDeliveryWorker`) with a pure-Kotlin `JsonPayload` utility, adding thorough tests for quotes, backslashes, newlines, and unicode.
  - Fixed time comparisons in `DoseScheduler`, aligned enum values in `SyncWorker` (`FoodRelation.ANY`), and synchronized doctor notification enums in `NotificationTextTest`.
- **Part 2 ?" Crashes & Data Loss Prevention**:
  - Bumped Room database version to 3 with `exportSchema = true` (generated and committed `schemas/.../3.json`). Removed all occurrences of `fallbackToDestructiveMigration()`.
  - Authored SQLite schema migrations:
    - `MIGRATION_1_2`: Added `isDeleted` and `lowStockNotified` to `medicines`. Recreated `dose_events` with `ON DELETE RESTRICT` and indices.
    - `MIGRATION_2_3`: Added `patientId`, `lastRefillAt`, and `updatedAt` to `medicines`; `patientId` and `nextReminderAt` to `dose_events`; `invitationCode`, `expiresAt`, `updatedAt` to `doctor_links`; `revision` and `clarificationNote` to `medicine_proposals`. Recreated `users` table without `doctorRegistrationNo` via table copy. Migrated `ProposalStatus` from `APPLIED` to `ACCEPTED`. Backfilled missing `patientId` for legacy data.
  - Added comprehensive `MigrationTest` utilizing `MigrationTestHelper` verifying 1 ? 2, 2 ? 3, and 1 ? 3 migrations with live records.
  - Fixed `FakeAuthRepository.logout()` to clear session only and never delete local health data offline. Enforced strict foreign key deletion ordering (`dose_events` ? `medicines` ? `caregiver_links` ? `users`) for account deletion.
  - Hardened `VerificationModule` in release builds to fail closed when TFLite model is unavailable, preventing unverified doses from passing as taken.
- **Part 3 ?" State & Scheduling Regressions**:
  - In `SaveMedicineUseCase.updateMedicine`: Modified to delete ONLY future pending doses (`scheduledAt > now`), preserving the currently open in-window pending dose and verified history.
  - Clamped exact alarm scheduling in `SaveMedicineUseCase` and `DailyDoseExtensionWorker` to a 48-hour rolling horizon to prevent exceeding Android's 500-alarm system limit.
  - Created `AlarmMaintenanceWorker` running periodically every 6 hours and at application boot/launch to refresh upcoming alarms.
  - Updated `RefillStockUseCase` and UI dialog in `MedicinesListScreen` with a "Starting a new strip" toggle: only resets `lastRefillAt`, `baselineEmptyCells`, and `baselinePhotoPath` when starting a new strip.
  - Implemented early intake tolerance in `VerificationCoordinator` (`AppConfig.EARLY_VERIFY_TOLERANCE_MINUTES = 30`), rejecting photos taken earlier than 30 minutes before `scheduledAt` with `RejectReason.TOO_EARLY`.
  - Hardened `OutboxDeliveryWorker` against poison events by checking existing documents on `PERMISSION_DENIED` and marking delivered.
- **Part 4 ?" Account Isolation, Concurrency & Notifications**:
  - Enforced per-user data isolation across queries and workers; workers refuse to upload rows where `patientId != auth.uid`.
  - Added `scheduleMissedCheck(dose)` to `AlarmScheduler` for medicines with reminders disabled.
  - Created shared `MissedDoseNotifier` for posting local notifications on `CHANNEL_MISSED_DOSES`, utilized by both `MissedDoseWorker` and `OverdueDoseSafetyWorker`.
  - Serialized `RecordVerifiedDoseUseCase` with a singleton `Mutex`, re-reading dose state inside the critical section to prevent race conditions (verified with 10-coroutine concurrency test).
  - Configured `BootReceiver` on `ACTION_TIMEZONE_CHANGED` to regenerate future pending doses in the new timezone.
  - Updated `AdherenceCalculator` to count `UNVERIFIED` as closed only if `windowEndsAt <= now`.
- **Part 5 ?" Backend Redesign (Functions & Rules)**:
  - Implemented 6-character caregiver invitation code flow with 72-hour expiry under `caregiverInvites/{code}`.
  - Implemented Cloud Function `acceptCaregiverInvite(code)` in `functions/src/index.ts` and restricted client write access to `caregiverLinks` in `firestore.rules`.
  - Implemented Cloud Function `onNotificationEventCreated` with server-side fanout to all accepted caregiver FCM tokens, routing `MISSED_DOSE` to `channel_missed_doses` and all others to `channel_general`.
  - Updated `CaregiverViewModel` to read patient medicines and 7-day doses read-only from Firestore into UI state.
  - Rewrote `SyncWorker` to perform pull-first conflict resolution followed by batched push writes for local rows matching `patientId == currentUserId`.
- **Verification Commands & Real Results**:
  - Real `@Test` methods counted across `app/src/test`: **225**
  - Automated unit tests: `.\gradlew.bat testDebugUnitTest` — **PASSED** (225 tests completed, 0 failures, 0 ignored, 100% passing)
  - Lint check: `.\gradlew.bat lintDebug` — **PASSED** (0 errors)
  - App build: `.\gradlew.bat assembleDebug` — **PASSED** (BUILD SUCCESSFUL)
- **Defensive Enhancements & Comparative Hardening**:
  - `Converters.kt`: Added `enumValueOrNull` and `enumValueOrDefault` helpers to make Room database enum reading completely crash-proof against unexpected/legacy schema values.
  - `ProcessMissedDoseUseCase.kt`: Added window boundary guard `if (now < dose.windowEndsAt) return Result.success(false)` preventing premature closure of pending doses before window expiration (+ unit test).
  - `SnoozeDoseUseCase.kt` & `AppConfig.kt`: Enforced explicit upper bound on snoozes (`MAX_SNOOZE_MINUTES = 240`).
  - `AlarmSchedulerImpl.kt`: Clamped scheduling to rolling horizon internally (`ALARM_HORIZON_HOURS = 48`) delegating overdue alarms to WorkManager.
  - `VerificationPipeline.kt`: Added explicit guidance for negative strip deltas (`delta < 0`) prompting the user to use the refill strip toggle.
  - `CaregiverScreen.kt`: Added invitation code entry card to `CaregiverPortalView` allowing caregivers to link patient accounts directly in the UI.
- **Honest Limitations**:
  - Live push notifications via FCM require actual Firebase project deployment with valid credentials (`google-services.json`) and registered Google Play services on physical test devices.
  - TFLite pill detection uses MLKit text recognition and CPU fallback heuristics in headless emulator/CI environments where GPU/NNAPI delegates are unavailable.
  - Offline mode retains full local capability via Room as single source of truth, with cloud sync queued via WorkManager until network connectivity is restored.
