# MediTrack Blister Strip & Cell Detector — Model Card

## Model Details
- **Architecture**: Lightweight YOLO Nano Backbone
- **Format**: TensorFlow Lite (Float32 FlatBuffer)
- **Asset Size**: 12.0 MB (12,626,120 bytes)
- **Input Dimensions**: 320 x 320 x 3 (RGB, normalized [0.0 .. 1.0])
- **Outputs**:
  - `locations & classes`: [1, 8, 2100] (xc, yc, w, h + class scores: strip, cell_full, cell_empty, cell_partial)
- **Asset Path**: `app/src/main/assets/strip_detector.tflite`
- **Model Version**: `v1.0.0-tflite`

## Intended Use
On-device clinical intake verification:
- Confirms the blister pack is fully inside the camera frame with $\ge 5\%$ margin on all 4 borders.
- Counts empty blister cavities before and after a scheduled dose.
- Compares the empty delta against the prescribed pill count (`pillsPerDose`).

## Training & Validation Data
- **Dataset**: Blister Pills Segmentation v7 (Roboflow Universe, 1,349 images, 17,566 instances)
  - Train: 1,173 images
  - Val: 117 images
  - Test: 59 images
- **Class Breakdown**:
  - `cell_full`: 12,917 instances
  - `cell_empty`: 4,649 instances
  - `strip`: 1,343 instances
  - `cell_partial`: **0 instances** (not trained in current model weights)
- **Gold Phone Test Set (Stage 13.B)**: **MISSING (Open human task per README)**. Real metrics below come from the held-out validation split.

## Performance Metrics (Held-out Validation Split: 117 images)
- **EMPTY-Class Precision**: **0.982** (Target $\ge 0.980$ — **MET**)
- **Strip Frame Recall**: **1.000** (Target $\ge 0.950$ — **MET**)
- **`cell_full` Precision**: **0.977** (97.7%)
- **`cell_partial`**: *Not trained / not predicted* (App treats any partial state as unknown/requires retake).
- **Overall mAP@50**: **0.979**
- **Host CPU Inference Latency**: **11.9 ms** (Host CPU Intel Core i7-13650HX, not device)
- **On-Device Physical Benchmark**: **NOT RUN** (Requires physical Android device session)

## Limitations & Clinical Governance
- **Intake vs Extraction**: Detection confirms that a pill has been removed from the foil cavity. It cannot medically prove ingestion by the patient.
- **Ambiguous Foil State**: If crushed/partially extracted foil is encountered, the app fails closed and prompts for manual review/retake.
