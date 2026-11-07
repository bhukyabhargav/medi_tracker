#!/usr/bin/env python3
"""
Model Training Script for MediTrack Blister Cell Detection.
Fine-tunes a lightweight YOLOv8 nano detector on the unified dataset
(1,349 images, 17,566 annotated blister cells and strips).
Seed set for deterministic reproducibility.
"""

import os
import sys
from ultralytics import YOLO

def main():
    print("=" * 60)
    print("MediTrack: Training Blister Strip & Cell Detector (Stage 13)")
    print("Dataset: ml/data.yaml (1,349 images, 17,566 instances)")
    print("Classes: 0: strip, 1: cell_full, 2: cell_empty, 3: cell_partial")
    print("=" * 60)

    model = YOLO("yolov8n.pt")
    results = model.train(
        data="ml/data.yaml",
        epochs=5,
        imgsz=320,
        batch=16,
        seed=42,
        project="ml/runs",
        name="blister_detector",
        degrees=15.0,
        fliplr=0.5,
        flipud=0.2,
        hsv_h=0.015,
        hsv_s=0.7,
        hsv_v=0.4,
        verbose=True
    )
    print("Training complete. Best model saved in ml/runs/blister_detector/weights/best.pt")

if __name__ == "__main__":
    main()
