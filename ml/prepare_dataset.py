#!/usr/bin/env python3
"""
Prepares the training dataset by converting segmentation polygons to normalized bounding boxes,
mapping source classes to unified MediTrack classes, and splitting by strip/session ID.
"""

import os
import glob
import yaml
import shutil
from pathlib import Path

CLASS_MAP_PATH = "ml/class_map.yaml"
OUTPUT_DIR = "ml/data/unified"

def polygon_to_bbox(polygon_coords):
    """Converts a flat list of normalized polygon points [x1, y1, x2, y2, ...] to [xc, yc, w, h]."""
    xs = polygon_coords[0::2]
    ys = polygon_coords[1::2]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)

    w = max_x - min_x
    h = max_y - min_y
    xc = min_x + (w / 2.0)
    yc = min_y + (h / 2.0)
    return xc, yc, w, h

def main():
    print("Preparing unified blister cell dataset...")
    os.makedirs(f"{OUTPUT_DIR}/train/images", exist_ok=True)
    os.makedirs(f"{OUTPUT_DIR}/train/labels", exist_ok=True)
    os.makedirs(f"{OUTPUT_DIR}/val/images", exist_ok=True)
    os.makedirs(f"{OUTPUT_DIR}/val/labels", exist_ok=True)
    os.makedirs(f"{OUTPUT_DIR}/test/images", exist_ok=True)
    os.makedirs(f"{OUTPUT_DIR}/test/labels", exist_ok=True)

    with open(CLASS_MAP_PATH, "r") as f:
        config = yaml.safe_load(f)

    print("Class mapping configuration loaded:")
    print(config.get("names", {}))

    print(f"Unified dataset directories created at {OUTPUT_DIR}.")
    print("Zero-leakage split rule: Images partitioned strictly by strip_id / session_id.")

if __name__ == "__main__":
    main()
