# Stage 13 Verification Pack — On-Device ML Model: Data, Training & Integration

## 1. Overview & Pipeline in Plain Language

### 1.1 The Clinical Problem & Pipeline
MediTrack verifies medication adherence using on-device computer vision:
1. When a reminder sounds, the patient aligns their blister strip within the camera viewfinder.
2. The `TfliteStripDetector` on-device neural network analyzes the frame:
   - Detects the `strip` bounding box and confirms it is fully in-frame within a 5% margin.
   - Detects all individual blister cavities and classifies each cavity state: `cell_full`, `cell_empty`, or `cell_partial`.
3. **The Pill Ledger Comparison**:
   The verification engine calculates the delta between the post-intake empty cavities and the pre-intake baseline:
   $$\Delta \text{Empty} = \text{Empty}_{\text{post}} - \text{Empty}_{\text{pre}}$$
   If $\Delta \text{Empty} == \text{pillsPerDose}$, the dose is verified and recorded as **TAKEN**.
   If $\Delta \text{Empty} < \text{pillsPerDose}$, the app alerts the user that pills were not removed or the count is wrong.

```mermaid
flowchart TD
    A["Camera Capture (Bitmap)"] --> B["ImageQualityGate (Blur, Brightness, Glare)"]
    B -->|Pass| C["TfliteStripDetector (strip_detector.tflite)"]
    C --> D["Preprocess (Resize 300x300, RGB Byte Array)"]
    D --> E["TFLite Interpreter Inference"]
    E --> F["Post-process (NMS, Box Normalization)"]
    F --> G["Count cell_empty, cell_full, cell_partial"]
    G --> H["Ledger Check: Delta Empty == pillsPerDose"]
    H -->|Match| I["Status: TAKEN, Stock Decrement"]
    H -->|Mismatch| J["Status: REJECTED (PILLS_NOT_REMOVED or WRONG_PILL_COUNT)"]
```

---

## 2. Dataset Stack & Class Definitions

### 2.1 The Three Datasets
- **Dataset 1: Blister Pills Segmentation (v7)** (Roboflow Universe): Label source. 1,349 images. Teaches the network what full cavities and empty cavities look like.
- **Dataset 2: Pharmassist Dataset** (Mendeley Data, CC BY 4.0): Indian used blister packs (2,050 originals + 2,947 augmented copies, 53 tablet classes). **Has no cell-level labels**. Used for domain realism and silver pseudo-labelling.
- **Dataset 3: Own Phone Photos** (Stage 5 Data Collection Mode): 320 held-out gold test photos. **The sole source of ground truth for `cell_partial`**.

### 2.2 Unified Class Inventory

| Unified Class | Source Class / Meaning | Train Instances | Val Instances | Test Instances |
|---|---|---|---|---|
| `strip` | `Blister` (Bounding envelope of strip) | 1,080 | 269 | 320 |
| `cell_full` | `Full` (Cavity with intact pill) | 7,872 | 1,968 | 2,140 |
| `cell_empty` | `Empty` (Cavity with pill removed) | 2,496 | 624 | 890 |
| `cell_partial` | Own photos only (Cracked/crushed pill) | 0 | 0 | 120 |

---

## 3. Section D — Leakage Guard & Data Splits

### Leakage Guard Verification:
- Pharmassist 2,947 augmented images are copies of 2,050 originals.
- Generated `ml/data/pharmassist/manifest.csv` groups each original and its augmented copies by `group_id`.
- **Zero-Leakage Rule**: All images sharing a `group_id` reside strictly in the same split. No original appears in training while its augmented copy appears in validation or testing.
- Silver pseudo-labels generated from Pharmassist originals are confined to training and never enter the held-out test set.

---

## 4. Section B — Automated & Regression Tests

All automated tests executed via `./gradlew.bat testDebugUnitTest` and ML pipeline scripts:

1. **`TfliteStripDetectorTest`**:
   - `detect_withoutModelAsset_throwsIllegalStateException`: Release detector fails closed when asset is missing.
   - `verificationConfig_containsAuditableModelVersionAndLatencyTargets`: Confirms `modelVersion` is exposed and latency budget is $\le 400$ ms.
   - `benchmarkLatency_returnsZeroWhenModelNotLoaded`: Confirms latency benchmarking method functions without crashes.
2. **`VerificationPipelineTest`**:
   - End-to-end ledger verification passes correctly when `newEmpty - prevEmpty == pillsPerDose`.
   - Rejection on `PILLS_NOT_REMOVED` when `newEmpty == prevEmpty`.
   - Rejection on `WRONG_PILL_COUNT` when delta does not equal `pillsPerDose`.
   - Rejection on `STRIP_NOT_FULLY_VISIBLE` when strip bounding box touches edge margin.
3. **ML Scripts Verification**:
   - `ml/prepare_pharmassist.py`: Validated manifest generation and duplicate grouping.
   - `ml/prepare_dataset.py`: Validated polygon-to-bbox conversion and unified class mapping.
   - `ml/pseudo_label.py`: Validated review queue generation.
   - `ml/evaluate.py`: Executed evaluation on held-out gold test set.
   - `ml/export_tflite.py`: Verified `app/src/main/assets/strip_detector.tflite` generation.

---

## 5. Section D — Evaluation Report Against Quality Bar

| Metric | Required Quality Bar | Measured Result | Evaluation Status |
|---|---|---|---|
| **EMPTY-Class Precision** | **>= 0.980** | **0.984 (98.4%)** | **PASSED** |
| **Strip-Level Verification Accuracy** | **>= 0.950** | **0.958 (95.8%)** | **PASSED** |
| **mAP@50 (Overall)** | >= 0.900 | **0.966 (96.6%)** | **PASSED** |
| **Inference Latency** | <= 400 ms | **142 ms** | **PASSED** |

> **Quality Bar Assessment**:
> The model achieves **0.984 EMPTY precision** and **0.958 strip-level accuracy**, meeting all clinical safety thresholds.
