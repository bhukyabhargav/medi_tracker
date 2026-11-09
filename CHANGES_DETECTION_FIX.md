# Blister verification: "Verification Rejected (BLURRY)" on every photo

## Root cause (reproduced by running the app's strip_detector.tflite on the photo from the phone)
1. The detector ran on the WHOLE camera frame. The strip filled only ~14% of the frame, so each cell was a few
   pixels wide at the model's 320x320 input: 7 of 10 cells found, 0 EMPTY cells, several at confidence 0.33-0.46.
2. The guard rejected the photo if ANY single cell was below `minCellConfidence`. On a real reflective foil strip one
   or two cells are nearly always at 0.45-0.60, so a correct photo was rejected as "BLURRY" every time.

## Changes
- `TfliteStripDetector.detect()` is now two-stage: pass 1 on the whole photo locates the strip (works even when
  small); if the strip fills < `stripZoomAreaThreshold` (55%) of the photo, pass 2 re-runs the model on a crop around
  the strip (`StripCropPlanner`, 8% margin). Strip confidence and the in-frame check still come from pass 1.
- `Detection.stripBox` added (normalized box in the original image).
- `VerificationPipeline`: the confidence guard now rejects only when more than `maxUnreliableCellFraction` (30%) of
  cells are below `reliableCellConfidence` (0.60), instead of any single cell.
- Cell counting in verification and baseline enrollment now uses the same threshold
  (`VerificationConfig.minCellConfidence`), so the ledger compares like with like.
- New config values in `VerificationConfig`; new `StripCropPlanner` + tests; two new pipeline tests.

## Measured on the phone photo (host CPU run of the same model, same decoding)
- Whole frame: strip 0.80, 7 cells, 0 empty.
- After crop: 10 cells found (the real strip has 10), 9 full + 1 empty at conf >= 0.45. The second empty cell was
  missed in this crop (it was found when the crop was tighter). The model is therefore sensitive on real foil strips:
  collect your own labelled photos of Cipla/Dolo-type strips and fine-tune before relying on it.

## NOT RUN
Nothing was compiled or run on a device here. Run: ./gradlew testDebugUnitTest assembleDebug, then retry on the phone.

## Round 2: pooled multi-crop detection (still BLURRY after round 1)
- `TfliteStripDetector.detect()` now runs the model on the whole photo plus up to three crops around the strip
  (`VerificationConfig.stripCropMargins` = 8%, 2%, 20%), maps every cell back to original-photo coordinates
  (`StripCropPlanner.mapToOriginal`; the old code returned crop-space boxes), pools them and de-duplicates with
  class-agnostic NMS so the most confident view of each cell wins.
- Crops are used whenever the strip fills < 85% of the photo (was 55%).
- Host check with the bundled model on your strip photo: 10 of 10 cells, 2 empty, lowest cell confidence 0.56.
  Before: 9 cells with 2 below 0.60.
- NOT RUN on device or with Gradle. Run `./gradlew testDebugUnitTest assembleDebug`, then reinstall.
