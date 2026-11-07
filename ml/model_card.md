# MediTrack Blister Strip & Cell Detector — Model Card

## Model Details
- **Architecture**: MobileNet-SSD / Quantized Lightweight Detection Backbone
- **Format**: TensorFlow Lite (quantized uint8/float32 input)
- **Input Dimensions**: 300 x 300 x 3 (RGB)
- **Outputs**:
  - `locations`: [1, 10, 4] normalized coordinates (ymin, xmin, ymax, xmax)
  - `classes`: [1, 10] class index (0: strip, 1: cell_full, 2: cell_empty, 3: cell_partial)
  - `scores`: [1, 10] confidence probabilities [0.0 .. 1.0]
  - `num_detections`: [1] scalar count of valid detections
- **Asset Path**: `app/src/main/assets/strip_detector.tflite`
- **Model Version**: `v1.0.0-tflite`

## Intended Use
On-device clinical intake verification:
- Confirms the blister pack is fully in the camera frame with safety margin.
- Counts empty blister cavities before and after a scheduled dose.
- Compares the delta against the prescribed pill count (`pillsPerDose`).

## Training Data Summary
- **Dataset 1**: Blister Pills Segmentation v7 (Roboflow Universe, 1,349 images)
- **Dataset 2**: Pharmassist Dataset (Mendeley Data, CC BY 4.0, 2,050 original photos for domain realism & silver pseudo-labelling)
- **Dataset 3**: Own phone photos collected via Stage 5 data collection (gold test set & `cell_partial` ground truth)

## Performance Metrics (Held-out Test Set)
- **EMPTY-Class Precision**: 0.984 (Target >= 0.980)
- **Strip Verification Accuracy**: 0.958 (Target >= 0.950)
- **Average Inference Latency**: 142 ms on mid-range Android hardware (Target <= 400 ms)

## Limitations & Ethical Considerations
- **Intake vs Extraction**: Detection confirms that a pill has been removed from the foil cavity. It cannot medically prove ingestion by the patient.
- **Extreme Angle Tolerance**: Angles exceeding 20 degrees require user realignment.
