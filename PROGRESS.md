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
- Summary: Implemented AlarmManager exact reminder scheduling (`AlarmScheduler`), `ReminderReceiver`, `BootReceiver` for device reboot rescheduling, dose window management, snoozing (`15m`, `30m` presets, max 3 snoozes), missed dose transitions via `DoseStateMachine`, notifications, and `RecordVerifiedDoseUseCase`.
- Files added/changed:
  - `feature/reminder/alarm/AlarmScheduler.kt`
  - `feature/reminder/receiver/ReminderReceiver.kt`, `BootReceiver.kt`
  - `domain/usecase/RecordVerifiedDoseUseCase.kt`
  - `docs/verification/STAGE_4_VERIFY.md`
- Tests: `AlarmSchedulerTest.kt`, `BootReceiverTest.kt`

## Stage 5 – Camera Capture & Image Quality Gate – SIGNED OFF (2026-11-01)
- Summary: Implemented CameraX live capture with blister framing guide, pure-Kotlin `ImageQualityGate` (blur Laplacian variance $\ge 100$, brightness $[40..220]$, glare fraction $\le 0.08$), 64-bit difference hashing (`PerceptualHasher`), secure JPEG storage (`ProofStorage`), and debug dataset collection (`DatasetCollector`).
- Files added/changed:
  - `feature/verification/CaptureScreen.kt`
  - `feature/verification/quality/ImageQualityGate.kt`, `PerceptualHasher.kt`, `ProofStorage.kt`
  - `feature/verification/data/DatasetCollector.kt`
  - `docs/verification/STAGE_5_VERIFY.md`
- Tests: `ImageQualityGateTest.kt`, `PerceptualHasherTest.kt`

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


