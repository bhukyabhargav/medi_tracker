# MediTrack ML Pipeline: Blister Cell Detection & State Classification

This directory contains the complete machine learning pipeline for MediTrack's on-device pill intake verification model:
1. **Dataset Stack Architecture**:
   - **Dataset 1: Blister Pills Segmentation (v7)** from Roboflow Universe (`Empty`, `Full`, `Blister`).
   - **Dataset 2: Pharmassist Dataset** (Mendeley Data, CC BY 4.0, 53 tablet classes, 2,050 originals + 2,947 augmented copies) used for strip domain realism and silver pseudo-labelling.
   - **Dataset 3: Own Phone Photos** collected via Stage 5 data-collection mode, serving as the gold held-out test set and the sole ground-truth source for `cell_partial`.
2. **Unified Class Taxonomy**:
   - `0: strip` — Bounding box of the complete blister pack.
   - `1: cell_full` — Blister cavity containing an intact medicine pill.
   - `2: cell_empty` — Blister cavity where the foil has been ruptured and the pill is absent.
   - `3: cell_partial` — Blister cavity where the pill is partially removed, damaged, or cracked.
3. **Execution Scripts**:
   - `download_dataset.py`: Roboflow download using environment variable `ROBOFLOW_API_KEY`.
   - `prepare_pharmassist.py`: Groups originals and augmented copies; generates `manifest.csv` respecting the zero-leakage rule.
   - `prepare_dataset.py`: Polygon-to-bounding-box conversion, coordinate normalization, and strip-level split.
   - `pseudo_label.py`: Generates silver labels ($\ge 0.8$ confidence) from Pharmassist originals for human review.
   - `train.py`: Lightweight detector training (YOLO nano / MobileNet-SSD) with clinical augmentations (glare, blur, shadows).
   - `evaluate.py`: Evaluates mAP, per-class metrics, and strict EMPTY-class precision ($\ge 0.98$).
   - `export_tflite.py`: Exports trained model to optimized, quantized `strip_detector.tflite` for Android deployment.
