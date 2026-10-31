# Running MediTrack in Android Studio

## 1. One-time setup
1. Install the latest stable **Android Studio** (it bundles the JDK and Android SDK tools).
2. *Settings → Build, Execution, Deployment → Build Tools → Gradle* → **Gradle JDK = Embedded JDK** (JBR 17 or newer).
3. *Tools → SDK Manager* → install the latest **Android SDK Platform** (API 34/35), **Build-Tools**, **Platform-Tools**, and **Emulator**.
4. *Tools → Device Manager → Create Device* → pick a **Pixel** phone → choose a system image **with Google Play**, API **34 or newer** → Finish.
5. (Optional) Connect a physical Android phone with USB debugging enabled.

## 2. Open, sync, run
1. *File → Open* → select this repository's root folder (`MedTracker - BioDesign`) → **Trust Project**.
2. Wait for **Gradle Sync** to finish. Status: *"Gradle sync finished"* with zero errors.
3. Select your device/emulator in the target device dropdown.
4. Select the **`app`** run configuration.
5. Press **Run ▶**.
6. The app will launch to the placeholder Home screen displaying **MediTrack**.

## 3. Reaching Debug Tools
- In debug builds, you can reach the **Debug Tools** screen by:
  - Tapping the bug icon in the top right of the top bar, or
  - Long-pressing the "MediTrack" title in the top bar.
- On the Debug Tools screen, you will see the build stage identifier: **"MediTrack – Stage 0 build"**.

## 4. Logcat Filter
- In Android Studio, open the **Logcat** tab at the bottom.
- Enter the filter: `tag:MediTrack`
- On startup you will see:
  `D/MediTrack: MediTrack application initialized - Stage 0 bootstrap`

## 5. Command-Line Builds
```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lint
```
