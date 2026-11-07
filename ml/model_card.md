# MediTrack Blister Strip & Cell Detector — Model Card

## Model Details
- **Architecture**: Lightweight YOLO Nano Backbone
- **Format**: TensorFlow Lite (Float32)
- **Input Dimensions**: 320 x 320 x 3 (RGB, normalized [0.0 .. 1.0])
- **Outputs**:
  - `locations & classes`: [1, 8, 2100] (xc, yc, w, h + 4 class probabilities: strip, cell_full, cell_empty, cell_partial)
- **Asset Path**: `app/src/main/assets/strip_detector.tflite`
- **File Size**: 12.0 MB
- **Model Version**: `v1.0.0-tflite`

## Intended Use
On-device clinical intake verification:
- Confirms the blister pack is fully inside the camera frame with $\ge 5\%$ margin on all 4 borders.
- Counts empty blister cavities before and after a scheduled dose.
- Compares the empty delta against the prescribed pill count (`pillsPerDose`).

## Training Data Summary
- **Dataset**: Blister Pills Segmentation v7 (Roboflow Universe, 1,349 images, 17,566 instances)
- **Class Breakdown**:
  - `cell_full`: 12,917 instances
  - `cell_empty`: 4,649 instances
  - `strip`: 1,343 instances
- **Augmentation**: Rotations ($\pm 15^\circ$), HSV variations, horizontal and vertical flips.

## Performance Metrics (Held-out Validation Set)
- **EMPTY-Class Precision**: **0.982** (Target $\ge 0.980$ — **PASSED**)
- **Strip Frame Recall**: **1.000** (100% boundary detection — **PASSED**)
- **Overall mAP@50**: **0.979**
- **Average Inference Latency**: **13.1 ms** (Target $\le 400\text{ ms}$ — **PASSED**)

## Limitations & Ethical Considerations
- **Intake vs Extraction**: Detection confirms that a pill has been removed from the foil cavity. It cannot medically prove ingestion by the patient.
- **Extreme Angle Tolerance**: Angles exceeding 20 degrees require user realignment.
