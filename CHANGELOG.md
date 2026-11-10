# Changelog - MedTracker Blister Verification Hardening

All notable changes implemented as part of the blister verification hardening tasks (Tasks 1 through 5).

---

## [Hardened Verification Release] - 2026-11-10

### 1. Dual-Level Image Quality Gate (Task 1)
- **`app/src/main/java/com/meditrack/app/feature/verification/quality/ImageQualityGate.kt`**:
  - Implemented `analyzeLevel1`: fast pre-check on original image testing decode integrity, minimum dimensions (`VerificationConfig.globalMinDimensionPx = 100`), and global dark/saturation bounds.
  - Implemented `analyzeBlisterCrop`: crops strictly around the located strip box (+ configurable padding) and evaluates Laplacian variance (blur), brightness, contrast, glare, and effective resolution directly on the blister.
  - Implemented `resolveStripBox` to compute the tight bounding box encompassing either the detector's strip box or detected cavities.
- **`app/src/main/java/com/meditrack/app/core/config/VerificationConfig.kt`**:
  - Added Level-1 and Level-2 configuration parameters: `globalMinDimensionPx`, `globalBrightnessMin`, `globalBrightnessMax`, `stripCropQualityPadding`, `minBlisterResolutionPx`, `blisterBlurVarianceMin`, `blisterBrightnessMin`, `blisterBrightnessMax`, `blisterContrastMin`, `blisterGlareFractionMax`.

### 2. Geometric `stripFullyInFrame` & Cluster Isolation (Task 2)
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/TfliteStripDetector.kt`**:
  - Implemented `computeStripFullyInFrame`: verifies that strip bounding box does not touch/cross outer boundaries within `borderToleranceFraction = 0.015f`, and penalizes cells touching edges within `cellCutoffToleranceFraction = 0.012f`.
  - Replaced naive median-distance filtering with rotation-invariant principal-axis clustering and normalized distance grouping (`d_norm <= 2.2f`), isolating blister strips from tablecloth motifs and patterned fabric noise.
  - Hardened `stripEnclosesCells` to discard hallucinated strip boxes pinned to `(0, 0)`.
- **`app/src/main/java/com/meditrack/app/core/config/VerificationConfig.kt`**:
  - Calibrated `borderToleranceFraction = 0.015f`, `cellCutoffToleranceFraction = 0.012f`, `cellSizeToleranceLow = 0.60f`, `cellSizeToleranceHigh = 1.65f`, `minConsistentCellFraction = 0.75f`.

### 3. Verification Bypass Closures & Concurrency Hardening (Task 3)
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/FakeStripDetector.kt`**:
  - Guarded `detect()` to throw `IllegalStateException` when `!BuildConfig.DEBUG`.
  - Guarded instance and companion `behavior` setters to ignore modifications in release builds.
- **`app/src/main/java/com/meditrack/app/feature/verification/VerificationViewModel.kt`**:
  - Guarded `simulateVerifiedTake` and `setForcedDetectorBehavior` with `if (!BuildConfig.DEBUG) return`.
  - Hardened `useRejectedPhotoAnyway`: requires valid existing photo file on disk, blocks intake when `TOO_EARLY` (`now < dose.scheduledAt - earlyToleranceMillis`), and blocks intake if dose is already closed or skipped.
  - Hardened `recordBaselineEnrollment`: enforces that failure to decode, detector exceptions, or validator rejections leave the existing trusted baseline completely unchanged.
- **`app/src/main/java/com/meditrack/app/domain/usecase/RecordVerifiedDoseUseCase.kt`**:
  - Serialized all verified dose recording through a coroutine `Mutex` (`mutex.withLock`).
  - Wrapped database update operations in safe error handling to ensure failed DB writes never record a verified dose or decrement stock.
- **`app/src/test/java/com/meditrack/app/domain/usecase/RecordVerifiedDoseUseCaseTest.kt`**:
  - Added unit test `failedDbWrite_neverRecordsVerifiedDose_andNeverDecrementsStock`.
- **`app/src/test/java/com/meditrack/app/domain/usecase/RecordManualUnverifiedUseCaseTest.kt`**:
  - Created test suite asserting manual unverified reporting only transitions to `MISSED` with `manualUnverified = true` and never decrements stock.
- **`app/src/test/java/com/meditrack/app/feature/verification/VerificationBypassSecurityTest.kt`**:
  - Added test suite verifying `useRejectedPhotoAnyway` blocks early takes and missing photos, and baseline enrollment preserves immutability upon failures.

### 4. Structured Results, Codes & Retry Hints (Task 4)
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/VerificationFailureReason.kt`**:
  - Created enum with 8 structured failure reasons: `BLISTER_NOT_FOUND`, `BLISTER_PARTIALLY_VISIBLE`, `BLISTER_TOO_SMALL`, `IMAGE_BLURRY`, `EXCESSIVE_GLARE`, `POOR_EXPOSURE`, `INSUFFICIENT_RESOLUTION`, `AMBIGUOUS_CELL_DETECTION`.
  - Each reason defines `code`, `userMessage`, `retryHint`, `isQualityGate`, and `mappedRejectReason`.
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/DetectorModels.kt`**:
  - Updated `VerificationResult` to include `failureReason` and `retryHint`.
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/VerificationCoordinator.kt`**:
  - Updated `CoordinatorResult.Failed` and `CoordinatorResult.Exhausted` with `failureReason` and `retryHint`.
  - Maintained invariant that quality-gate rejections do not consume verification attempts.
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/VerificationPipeline.kt`**:
  - Wired structured failure reasons and hints into all rejection branches.

### 5. Baseline Geometry Signature & Room Database Migration (Task 5)
- **`app/src/main/java/com/meditrack/app/domain/model/BaselineGeometrySignature.kt`**:
  - Created model for extracting and comparing rotation-invariant geometric signatures (`totalCellCount`, `normalizedCenters`, `relativeCellSize`, `estimatedRows`, `estimatedColumns`, `stripAspectRatio`, `baselineVersion`).
- **`app/src/main/java/com/meditrack/app/domain/model/Entities.kt`**:
  - Added baseline signature columns and helper accessors to `Medicine` entity: `baselineCellCount`, `baselineCentersJson`, `baselineCellSizeRatio`, `baselineRows`, `baselineCols`, `baselineAspectRatio`, `baselineVersion`.
- **`app/src/main/java/com/meditrack/app/data/db/AppDatabase.kt`**:
  - Incremented database version from 4 to 5.
  - Implemented `MIGRATION_4_5` preserving all existing user medicine records.
  - Generated and exported `app/schemas/com.meditrack.app.data.db.AppDatabase/5.json`.
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/VerificationPipeline.kt`**:
  - Integrated signature comparison: rejects presented strips that significantly deviate in cavity count, aspect ratio, or grid arrangement from the enrolled baseline.
- **`app/src/test/java/com/meditrack/app/data/db/AppDatabaseMigrationTest.kt`**:
  - Added unit test suite validating `MIGRATION_4_5` SQLite schema migration, column defaults, and `Medicine` signature persistence.
- **`app/src/test/java/com/meditrack/app/feature/verification/pipeline/VerificationPipelineTest.kt`**:
  - Added unit tests for structured failure codes and hints, layout mismatch detection, and rotation-invariant matching.

### 6. Edge-Anchoring & Cavity Clustering Optimization (False Rejection Remediation)
- **`app/src/main/java/com/meditrack/app/feature/verification/pipeline/TfliteStripDetector.kt`**:
  - **Multi-pass Crop Box Selection**: Derived crop box directly from detected cavities (`deriveStripBox(first.cells, VerificationConfig.stripCropMarginFraction)`) when `first.cells.size >= minBlisterCells`, preventing pass 2 from zooming in on tablecloth or border noise pinned to `(0, 0)`.
  - **Normalized Cavity Clustering Distance**: Updated graph clustering adjacency from hardcoded `2.2f` to `VerificationConfig.maxCellClusterDistance = 2.35f`. This prevents long 10-cavity strips (e.g. Cipla) from being fractured into small fragments where background fabric patterns could outscore them.
  - **Size Pre-filtering**: Leveraged `VerificationConfig.cellSizeToleranceLow..VerificationConfig.cellSizeToleranceHigh` (0.60f..1.65f) before building the cavity graph, rejecting extreme spurious border detections.
  - **Cluster Ranking**: Prioritized real blister clusters (`comp.size * 50f`) so genuine blister packs (6–15 cavities) decisively win over small 4-cavity fabric patterns.
- **`app/src/main/java/com/meditrack/app/core/config/VerificationConfig.kt`**:
  - Added `maxCellClusterDistance: Float = 2.35f`.
- **Validation**:
  - Real Image 1 (`media_1794297633687_da2f4d18.jpg` - Cipla 10-cavity strip): `in_frame = true`, `presence = true`, 8 FULL, 2 EMPTY (Total: 10 cavities detected).
  - Real Image 2 (`media_1794297648647_06e761a3.jpg` - 15-cavity silver strip): `in_frame = true`, `presence = true`, 12 FULL, 9 EMPTY (Total: 21 detections including foil depressions).
  - Resolved user-reported dialog: `"No Blister Strip Found: Blister strip is partially outside the photo"` shown in `media_1794317619494_86202abb.jpg` and `media_1794317619497_b0859e79.jpg`.
