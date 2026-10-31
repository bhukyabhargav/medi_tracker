# Verification Pack: Stage 0 – Project Bootstrap

## Part A – Plain-Language Explanation ("How it works")
Stage 0 sets up the core foundation for MediTrack. At this stage:
- The Android project structure is created following a strict **package-by-feature** design.
- The project is configured with modern Jetpack Compose, Material 3, and Dagger Hilt dependency injection.
- Central configuration constants are established in `core/config/AppConfig.kt`. All tunable constants for dose windows, snoozes, verification image thresholds, stock limits, and perceptual hashing are centralized here so there are no magic numbers elsewhere in the codebase.
- The app builds with a placeholder Home screen and a debug-only **Debug Tools** screen (accessible by tapping the bug icon or long-pressing the title bar in debug builds).
- Google Firebase services are declared in the Gradle catalog, but the Google Services Gradle plugin is conditionally applied only if `google-services.json` is present. If the file is not yet provided, the project continues to build and run seamlessly.

**What is NOT done yet:**
- Domain models and Room database (Stage 1).
- Authentication, onboarding, and permission setup (Stage 2).
- Medicine reminder alarms and camera verification (Stages 3–6).

---

## Part B – Automated Tests
Automated tests prove that the centralized configuration rules are defined exactly as required by the specifications.

| Test name | What it proves | Command to run it |
|---|---|---|
| `appConfig_doseWindowAndSnoozeDefaults_areCorrect` | Proves dose window is 60m, snoozes default to [15, 30]m, and max snoozes is 3 | `./gradlew testDebugUnitTest --tests "*AppConfigTest*"` |
| `appConfig_verificationLimits_areCorrect` | Proves max verification attempts is 3 and late dose window is 24h | `./gradlew testDebugUnitTest --tests "*AppConfigTest*"` |
| `appConfig_qualityGateAndDetector_areCorrect` | Proves blur, brightness, glare, pHash, OCR match, and stock thresholds are exact | `./gradlew testDebugUnitTest --tests "*AppConfigTest*"` |

**Negative Test Proof:**
- Running `./gradlew assembleDebug` without `google-services.json` succeeds without any build or plugin failure.

---

## Part C – Manual Test Script (Step-by-step)
Follow these steps on an emulator or connected device:

1. **Step 1: Launch the app**
   - Press **Run ▶ (app)** in Android Studio.
   - **Expected:** The app launches displaying a clean Material 3 TopAppBar with title **"MediTrack"** and a welcome card saying *"Welcome to MediTrack - Your dose verification and medicine companion"*.
2. **Step 2: Open Debug Tools (Icon)**
   - Tap the bug icon in the top right of the top bar.
   - **Expected:** Navigates to the "Debug Tools" screen displaying *"MediTrack – Stage 0 build"*, app version, and the active debug tools list.
3. **Step 3: Return to Home**
   - Tap the top-left Back arrow.
   - **Expected:** Returns to the Home screen.
4. **Step 4: Open Debug Tools (Long Press)**
   - Long-press the "MediTrack" title in the top bar.
   - **Expected:** Navigates to the "Debug Tools" screen.

---

## Part D – Evidence Checklist
- [x] `./gradlew assembleDebug` succeeds; app launches to placeholder Home.
- [x] `./gradlew testDebugUnitTest` succeeds with all unit tests passing.
- [x] Folder structure matches package-by-feature specifications (`core/`, `data/`, `domain/`, `feature/`).
- [x] `AppConfig.kt` exists with all constants from Section 2.4.
- [x] Absence of `google-services.json` does not cause build or sync errors.
- [x] Opens in Android Studio, Gradle Sync succeeds, and Run ▶ launches the app.
- [x] Debug Tools screen is accessible in debug builds.
- [x] No `ACCESS_FINE_LOCATION` or email-related permissions exist in `AndroidManifest.xml`.

**Proof Commands:**
```powershell
# Verify no location permissions declared
Select-String -Path "app\src\main\AndroidManifest.xml" -Pattern "LOCATION" # Expected: empty

# Verify AppConfig exists
Test-Path "app\src\main\java\com\meditrack\app\core\config\AppConfig.kt" # Expected: True
```

---

## Part E – Known Limitations & Risks
- **No dynamic data yet**: Screens currently display placeholder content until Stage 1 (Room DB) and Stage 2 (Auth/Home UI) connect live state.
- **Gradle Network Requirement on First Sync**: On a completely clean machine, Gradle downloads dependencies from Google Maven and Maven Central.

---

## Part F – Run in Android Studio
1. Open Android Studio.
2. Select *File → Open* → browse to the project directory: `MedTracker - BioDesign`.
3. Wait for Gradle Sync to complete with zero errors.
4. In the run configuration dropdown, select **`app`**.
5. Select an emulator running API 26 or newer (API 34+ recommended).
6. Click **Run ▶**.
7. In the **Logcat** tab, filter by `tag:MediTrack`.
   - **Expected Log:** `D/MediTrack: MediTrack application initialized - Stage 0 bootstrap`.
