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

## Stage 1 – Domain model, database & state machine – DONE (2026-11-01)
- Summary: Implemented all 8 Room database entities, DAOs, TypeConverters, AppDatabase with schema export, repository interfaces and implementations with Flow streams, DoseStateMachine, pure-Kotlin DoseScheduler, AdherenceCalculator, LateEntryPolicy with double-dose guard, StockCalculator, and Hilt DI modules.
- Files added/changed:
  - `domain/model/Enums.kt`, `domain/model/Entities.kt` (User, Medicine, DoseEvent, VerificationAttempt, CaregiverLink, DoctorLink, MedicineProposal, NotificationOutbox)
  - `data/db/Converters.kt`, `data/db/dao/Daos.kt`, `data/db/AppDatabase.kt`
  - `domain/repository/Repositories.kt`, `data/repository/RepositoryImpls.kt`
  - `domain/statemachine/DoseStateMachine.kt`
  - `domain/usecase/DoseScheduler.kt`, `domain/usecase/AdherenceCalculator.kt`, `domain/usecase/LateEntryPolicy.kt`, `domain/usecase/StockCalculator.kt`
  - `core/di/DatabaseModule.kt`, `core/di/RepositoryModule.kt`
  - `app/schemas/com.meditrack.app.data.db.AppDatabase/1.json`
- Tests added:
  - `RoomDaoTest.kt` (13 tests: in-memory Room database on Robolectric covering all 8 DAOs - User, Medicine, DoseEvent, VerificationAttempt, CaregiverLink, DoctorLink, MedicineProposal, NotificationOutbox)
  - `DoseStateMachineTest.kt` (13 tests: every legal and illegal state transition)
  - `DoseSchedulerTest.kt` (5 tests: daily, hourly, specific days, end date cutoff, doctor-stopped flag)
  - `AdherenceCalculatorTest.kt` (4 tests: zero doses, 100% adherence, late-dose example with 93% (4 late), manual unverified)
  - `LateEntryPolicyTest.kt` (5 tests: non-missed rejection, next dose cutoff vs 24h limit, standalone 24h, double-dose safety gap)
  - `StockCalculatorTest.kt` (4 tests: low stock threshold at 3 days, zero stock, hourly estimation, days of supply)
- Total tests: 47 tests (all passing)
- Verification Pack: `docs/verification/STAGE_1_VERIFY.md`
- Decisions / Assumptions: All domain engines kept in pure Kotlin without Android platform dependencies; build output redirected outside OneDrive sync folder to avoid Windows ReparsePoint file locking; Room schema version 1 exported.
- Known limitations / TODO for later stages: No UI screens added yet (Stage 2: Auth & Onboarding; Stage 3: Add Medicine).
