# MediTrack Blister Cell Detector — Model Evaluation Report

## 1. Executive Summary & Quality Bar

| Metric | Target | Measured Value | Benchmark Hardware | Quality Bar Status |
|---|---|---|---|---|
| **EMPTY-Class Precision** | $\ge 0.980$ | **0.982** (98.2%) | Validation Set (117 imgs) | **MET** |
| **Strip Frame Recall** | $\ge 0.950$ | **1.0** (100.0%) | Validation Set (117 imgs) | **MET** |
| **`cell_full` Precision** | $\ge 0.950$ | **0.977** (97.7%) | Validation Set (117 imgs) | **MET** |
| **Overall mAP@50** | $\ge 0.900$ | **0.979** (97.9%) | Validation Set (117 imgs) | **MET** |
| **Inference Latency** | $\le 400	ext{ ms}$ | **13.1 ms** | **Host CPU (Intel i7-13650HX), not device** | **MET ($>30\times$ faster)** |
| **On-Device Physical Benchmark** | $\le 400	ext{ ms}$ | *Not measured* | Physical Android Device | **NOT RUN** |

---

## 2. Dataset Counts & Audit (from manifest)

- **Total Images**: 1,349 (1,173 train, 117 val, 59 test)
- **Instances Evaluated**:
  - `strip`: 1,343 instances
  - `cell_full`: 12,917 instances
  - `cell_empty`: 4,649 instances
  - `cell_partial`: **0 instances** (Absent in public dataset)
- **Gold Phone Test Set (Stage 13.B)**: **MISSING (Human task pending per README)**. Real metrics above originate from the public Roboflow v7 held-out validation set.

---

## 3. Class-by-Class Results

| Class | Precision | Recall | mAP@50 | Notes |
|---|---|---|---|---|
| `strip` (0) | 0.99 | 1.0 | 0.995 | Verified whole strip boundary |
| `cell_full` (1) | 0.977 | 0.901 | 0.949 | Unbroken foil with intact pill |
| `cell_empty` (2) | **0.982** | 0.975 | 0.992 | **Safety Critical**: High precision protects against false intake |
| `cell_partial` (3) | *Not trained* | *Not trained* | *Not trained* | **Not trained, never predicted by model weights**. The Android app treats any partial state as ambiguous requiring retake. |

---

## 4. Verification Simulation

Ran `ml/simulate_verification.py` on validation set images:
- **Ledger Rule Simulation**: 155 / 200 passed expected delta.
- **Patient Intake Note**: Simulation conducted on static dataset pairs; does not represent physical patient usage.
- **Model Asset**: Float32 FlatBuffer (`12.0 MB` / 12,626,120 bytes) at `app/src/main/assets/strip_detector.tflite`.
