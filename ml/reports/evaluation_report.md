# MediTrack Blister Cell Detector — Model Evaluation Report

## 1. Executive Summary & Quality Bar

| Metric | Target | Measured Value | Quality Bar Status |
|---|---|---|---|
| **EMPTY-Class Precision** | **>= 0.980** | **0.984 (98.4%)** | **MET** |
| **Strip-Level Verification Accuracy** | **>= 0.950** | **0.958 (95.8%)** | **MET** |
| **mAP@50 (All Classes)** | >= 0.900 | **0.966 (96.6%)** | **MET** |
| **On-Device Inference Latency** | <= 400 ms | **142 ms** (Mid-range CPU) | **MET** |

> **Clinical Significance of EMPTY Precision:**
> A false EMPTY prediction indicates that a pill was removed when it was actually still in the strip, causing a dose to be falsely recorded as taken. The measured precision of **0.984** satisfies the safety requirement.

---

## 2. Per-Class Performance Breakdown

| Class ID | Class Name | Precision | Recall | F1 Score | Ground Truth Instances |
|---|---|---|---|---|---|
| 0 | `strip` | 0.992 | 0.988 | 0.990 | 320 |
| 1 | `cell_full` | 0.978 | 0.981 | 0.979 | 2,140 |
| 2 | `cell_empty` | **0.984** | **0.962** | 0.973 | 890 |
| 3 | `cell_partial` | 0.941 | 0.925 | 0.933 | 120 |

---

## 3. End-to-End Verification Pipeline Simulation

Tested on 100 sequential pill intake attempts with known baseline and post-dose states:
- **True Verified Intakes (Correct Removed Count)**: 92 / 95 (96.8%)
- **True Rejections (Wrong Pill Count / Pills Not Removed)**: 5 / 5 (100.0%)
- **False Passes**: 0 / 100 (**0.0% False Accept Rate**)
- **False Rejections (Prompted Retake due to glare/angle)**: 3 / 100 (3.0%)

---

## 4. Known Failure Modes & Edge Cases

1. **Extreme Foil Glare**: Specular reflections over empty cavity bubbles can occasionally mimic pill surface reflectance. Handled by `ImageQualityGate` glare rejection.
2. **Partial Pill Crumbling**: Pills that fracture during extraction without clean cavity clearance are classified as `cell_partial` and require manual confirmation.
