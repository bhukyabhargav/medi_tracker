# MediTrack - Round 2 Remediation Changes

## Summary
This document provides a comprehensive log of all code changes, architecture enhancements, and bug fixes applied during the second remediation pass on the MediTrack codebase (`com.meditrack.app`).

---

## 1. Build Breaks & Compilation Fixes
- **`app/src/main/java/com/meditrack/app/feature/medicine/AddEditMedicineViewModel.kt`**:
  - Restored `deleteMedicine(onDeleted: () -> Unit)` calling `saveMedicineUseCase.deleteMedicine(id)`.
  - Restored string text change event handlers (`onPillsPerDoseTextChange`, `onInitialStockTextChange`, `onIntervalHoursTextChange`) and boolean reminder handler (`onReminderToggle(Boolean)`), harmonizing ViewModel API with Compose UI.
- **`app/src/test/java/com/meditrack/app/feature/medicine/AddEditMedicineViewModelTest.kt`**:
  - Updated constructor call to inject `UserPreferences`.
  - Updated method invocations to match `String` handlers for text input fields and verified deletion callback behavior.
- **`app/src/main/java/com/meditrack/app/core/util/JsonPayload.kt` (NEW)**:
  - Replaced unmocked Android framework `org.json.JSONObject` with a lightweight, pure-Kotlin JSON serializer and escape engine to eliminate "Method put in org.json.JSONObject not mocked" crashes in plain JVM unit tests.
  - Safely escapes quotes (`\"`), backslashes (`\\`), newlines (`\n`), tabs (`\t`), and control characters.
- **`app/src/test/java/com/meditrack/app/core/JsonPayloadTest.kt` (NEW)**:
  - Unit tests verifying JSON serialization with strings containing quotes, backslashes, unicode characters, and newlines.
- **`app/src/main/java/com/meditrack/app/domain/usecase/ProcessStockDecrementUseCase.kt`**:
  - Replaced `org.json.JSONObject` with `JsonPayload.build(...)`.
- **`app/src/main/java/com/meditrack/app/feature/caregiver/CaregiverViewModel.kt`**:
  - Replaced `org.json.JSONObject` with `JsonPayload.build(...)`.
  - Added imports for `kotlinx.coroutines.tasks.await`, `DoseStatus`, and `FrequencyType`.
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/VerificationCoordinator.kt`**:
  - Replaced `org.json.JSONObject` with `JsonPayload.build(...)`.
- **`app/src/main/java/com/meditrack/app/feature/reminder/worker/OutboxDeliveryWorker.kt`**:
  - Replaced `org.json.JSONObject` with `JsonPayload`.
- **`app/src/main/java/com/meditrack/app/domain/usecase/DoseScheduler.kt`**:
  - Resolved `ChronoZonedDateTime` vs `Instant` comparison mismatch in interval scheduler.
- **`app/src/main/java/com/meditrack/app/feature/sync/SyncWorker.kt`**:
  - Fixed unresolved enum reference `FoodRelation.NONE` to `FoodRelation.ANY`.
- **`gradle/libs.versions.toml` & `app/build.gradle.kts`**:
  - Added `firebase-functions = { group = "com.google.firebase", name = "firebase-functions-ktx" }`.
  - Configured `testOptions.unitTests.isIncludeAndroidResources = true` and added schemas to asset directories.

---

## 2. Database Migrations & Crash / Data Loss Prevention
- **`app/src/main/java/com/meditrack/app/core/di/DatabaseModule.kt`**:
  - Removed `fallbackToDestructiveMigration()`. Database upgrades now strictly use schema migrations without data wipes.
- **`app/src/main/java/com/meditrack/app/data/db/AppDatabase.kt`**:
  - Bumped database version from 2 to 3 with `exportSchema = true`.
  - Implemented `MIGRATION_1_2`:
    - Added `isDeleted` and `lowStockNotified` columns to `medicines`.
    - Recreated `dose_events` with `ON DELETE RESTRICT` and created index on `medicineId`, `scheduledAt`, and `status`.
  - Implemented `MIGRATION_2_3`:
    - Added `patientId`, `lastRefillAt`, `updatedAt` to `medicines`.
    - Added `patientId`, `nextReminderAt` to `dose_events`.
    - Added `invitationCode`, `expiresAt`, `updatedAt` to `doctor_links`.
    - Added `revision`, `clarificationNote` to `medicine_proposals`.
    - Recreated `users` table without `doctorRegistrationNo` using SQLite table copy.
    - Migrated legacy `ProposalStatus` from `APPLIED` to `ACCEPTED`.
    - Migrated legacy outbox types (`DOCTOR_INVITE` -> `DOCTOR_INVITE_RECEIVED`, etc.).
    - Backfilled missing `patientId` on `medicines` and `dose_events` from the active local user.
- **`app/schemas/com.meditrack.app.data.db.AppDatabase/3.json` (NEW)**:
  - Generated and committed Room schema JSON for database version 3.
- **`app/src/test/java/com/meditrack/app/data/db/MigrationTest.kt` (NEW)**:
  - Added Robolectric tests with `MigrationTestHelper` verifying 1 -> 2, 2 -> 3, and 1 -> 3 sequential migrations with live table data.
- **`app/src/main/java/com/meditrack/app/data/repository/FakeAuthRepository.kt` & `FirebaseAuthRepository.kt`**:
  - Modified `logout()` to clear session tokens only, preserving offline health data.
  - Implemented `deleteAccount(userId)` deleting records in strict foreign key order (`dose_events` -> `verification_attempts` -> `medicines` -> `caregiver_links` -> `users`).
- **`app/src/main/java/com/meditrack/app/data/db/dao/Daos.kt`**:
  - Added `@Query("DELETE FROM users WHERE id = :id") suspend fun deleteById(id: String)` to `UserDao`.
- **`app/src/main/java/com/meditrack/app/feature/verification/di/VerificationModule.kt`**:
  - Hardened release builds: removed `fakeStripDetector` fallback. Release builds without a TFLite model fail closed and return a clean unavailable message rather than marking doses `TAKEN`.

---

## 3. State & Scheduling Regressions Fixed
- **`app/src/main/java/com/meditrack/app/domain/usecase/SaveMedicineUseCase.kt`**:
  - `updateMedicine()` now deletes ONLY future pending doses (`scheduledAt > now`), preserving open in-window pending doses and past verified history.
  - Clamped alarm scheduling to 48 hours (`now + 48h`) to prevent exceeding Android's 500-alarm system limit.
  - When reminders are disabled or beyond 48 hours, delegates to `alarmScheduler.scheduleMissedCheck(dose)`.
  - Prevents past doses from being generated if `startDate` is in the past.
- **`app/src/main/java/com/meditrack/app/feature/medicine/worker/DailyDoseExtensionWorker.kt`**:
  - Extended doses for active medicines clamped to 48-hour alarm horizon.
  - Generates from `maxOf(now, latestScheduled + 1000L)` when the app was idle for > 7 days, preventing backlog of past missed doses.
- **`app/src/main/java/com/meditrack/app/feature/reminder/worker/AlarmMaintenanceWorker.kt` (NEW)**:
  - Periodic worker running every 6 hours and enqueued at app startup in `MediTrackApp.kt` to reschedule upcoming dose alarms and missed checks.
- **`app/src/main/java/com/meditrack/app/domain/usecase/RefillStockUseCase.kt` & UI**:
  - Added `newStrip: Boolean = false` parameter.
  - Only resets `lastRefillAt`, `baselineEmptyCells`, and `baselinePhotoPath` if `newStrip == true`.
  - Added "Starting a new strip" checkbox to `MedicinesListScreen` refill dialog.
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/VerificationCoordinator.kt`**:
  - Added early tolerance check (`AppConfig.EARLY_VERIFY_TOLERANCE_MINUTES = 30`). Photo submissions earlier than 30 minutes before `scheduledAt` are rejected with `RejectReason.TOO_EARLY`.
- **`app/src/main/java/com/meditrack/app/feature/reminder/worker/OutboxDeliveryWorker.kt`**:
  - Handled `PERMISSION_DENIED` errors by performing a `get()` check on Firestore. If document already exists, marks delivered locally to prevent infinite retry loops on poison events.

---

## 4. Multi-Account Isolation & Concurrency
- **Multi-Account Isolation**:
  - Filtered queries and workers by `UserPreferences.currentUserId`.
  - `SyncWorker` filters local medicines and doses to upload only rows matching `patientId == currentUserId && patientId.isNotBlank()`.
- **`app/src/main/java/com/meditrack/app/feature/reminder/notification/MissedDoseNotifier.kt` (NEW)**:
  - Centralized notification helper posting local missed-dose notifications on `CHANNEL_MISSED_DOSES`.
  - Shared across both `MissedDoseWorker` and `OverdueDoseSafetyWorker`.
- **`app/src/main/java/com/meditrack/app/domain/usecase/RecordVerifiedDoseUseCase.kt`**:
  - Synchronized dose state transitions with a singleton `Mutex`, re-reading dose entity inside the lock to avoid concurrent photo submission races.
  - Added 10-coroutine concurrency test in `RecordVerifiedDoseUseCaseTest.kt`.
- **`app/src/main/java/com/meditrack/app/feature/reminder/receiver/BootReceiver.kt`**:
  - On `Intent.ACTION_TIMEZONE_CHANGED`, queries active medicines and regenerates future doses in the updated timezone.
- **`app/src/main/java/com/meditrack/app/domain/usecase/AdherenceCalculator.kt`**:
  - Added `now: Long = System.currentTimeMillis()`. Closed `UNVERIFIED` doses only count toward denominator when `windowEndsAt <= now`.

---

## 5. Backend & Cloud Functions Redesign
- **`functions/src/index.ts`**:
  - Added callable Cloud Function `acceptCaregiverInvite(data: { code: string })` validating 72-hour expiry and writing to `caregiverLinks/{patientId}_{caregiverUid}`.
  - Updated `onNotificationEventCreated` trigger to fan out FCM push notifications server-side to all accepted caregivers.
  - Corrected notification routing: `MISSED_DOSE` routes to `channel_missed_doses`, and other alerts route to `channel_general`.
- **`firestore.rules`**:
  - Enforced that clients cannot directly create or modify `caregiverLinks`.
  - Restricted `caregiverInvites` write access to the authenticated patient.
- **`app/src/main/java/com/meditrack/app/feature/sync/SyncWorker.kt`**:
  - Implemented pull-first architecture with conflict resolution (`SyncConflictResolver`), followed by batched push writes.

---

## 6. Verification Results & Defensive Hardening
- **Unit Test Count**: Exactly **225** `@Test` methods counted across `app/src/test` (including `processMissedDose_doesNothingBeforeWindowEnds`).
- **`./gradlew.bat testDebugUnitTest`**: **PASSED** (225 tests completed, 0 failures, 0 ignored, 100% passing).
- **`./gradlew.bat lintDebug`**: **PASSED** (0 lint errors).
- **`./gradlew.bat assembleDebug`**: **PASSED** (Debug APK generated successfully).
- **Defensive Enhancements Incorporated**:
  - `Converters.kt`: Tolerant enum mapping (`enumValueOrNull` / `enumValueOrDefault`) guarding against crashes on unexpected DB values.
  - `ProcessMissedDoseUseCase.kt`: Added guard preventing early closure before `windowEndsAt`.
  - `SnoozeDoseUseCase.kt` & `AppConfig.kt`: Enforced 240-minute maximum snooze boundary.
  - `AlarmSchedulerImpl.kt`: Added internal 48-hour rolling alarm horizon clamp.
  - `VerificationPipeline.kt`: Added friendly guidance message for negative pill deltas.
  - `CaregiverScreen.kt`: Added invitation code redemption card to `CaregiverPortalView`.
