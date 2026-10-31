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
