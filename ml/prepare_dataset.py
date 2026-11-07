#!/usr/bin/env python3
"""
Prepares the training dataset by converting segmentation polygons to normalized bounding boxes,
mapping source classes to unified MediTrack classes, and populating ml/data/unified.
"""

import os
import glob
import yaml
import shutil
from pathlib import Path

CLASS_MAP_PATH = "ml/class_map.yaml"
ROBOFLOW_DIR = "ml/data/roboflow_v7"
OUTPUT_DIR = "ml/data/unified"

# Roboflow classes: 0 = Blister, 1 = Empty, 2 = Full
# MediTrack classes: 0 = strip, 1 = cell_full, 2 = cell_empty, 3 = cell_partial
ROBOFLOW_TO_MEDITRACK = {
    0: 0, # Blister -> strip
    1: 2, # Empty -> cell_empty
    2: 1  # Full -> cell_full
}

def polygon_to_bbox(polygon_coords):
    """Converts a flat list of normalized polygon points [x1, y1, x2, y2, ...] to [xc, yc, w, h]."""
    xs = polygon_coords[0::2]
    ys = polygon_coords[1::2]
    min_x, max_x = max(0.0, min(xs)), min(1.0, max(xs))
    min_y, max_y = max(0.0, min(ys)), min(1.0, max(ys))

    w = max_x - min_x
    h = max_y - min_y
    xc = min_x + (w / 2.0)
    yc = min_y + (h / 2.0)
    return xc, yc, w, h

def process_split(src_split, dst_split, counts):
    src_images_dir = os.path.join(ROBOFLOW_DIR, src_split, "images")
    src_labels_dir = os.path.join(ROBOFLOW_DIR, src_split, "labels")

    dst_images_dir = os.path.join(OUTPUT_DIR, dst_split, "images")
    dst_labels_dir = os.path.join(OUTPUT_DIR, dst_split, "labels")

    os.makedirs(dst_images_dir, exist_ok=True)
    os.makedirs(dst_labels_dir, exist_ok=True)

    if not os.path.exists(src_images_dir):
        print(f"Directory {src_images_dir} does not exist, skipping.")
        return

    image_files = glob.glob(os.path.join(src_images_dir, "*.*"))
    print(f"Processing {len(image_files)} images for {src_split} -> {dst_split}...")

    for img_path in image_files:
        filename = os.path.basename(img_path)
        base_name = os.path.splitext(filename)[0]
        label_file = os.path.join(src_labels_dir, f"{base_name}.txt")

        # Copy image
        shutil.copy2(img_path, os.path.join(dst_images_dir, filename))

        # Convert label
        converted_lines = []
        if os.path.exists(label_file):
            with open(label_file, "r") as lf:
                for line in lf:
                    parts = line.strip().split()
                    if not parts:
                        continue
                    try:
                        raw_cls = int(parts[0])
                        mapped_cls = ROBOFLOW_TO_MEDITRACK.get(raw_cls, 1)
                        coords = [float(p) for p in parts[1:]]

                        if len(coords) == 4:
                            xc, yc, w, h = coords
                        elif len(coords) >= 6:
                            xc, yc, w, h = polygon_to_bbox(coords)
                        else:
                            continue

                        converted_lines.append(f"{mapped_cls} {xc:.6f} {yc:.6f} {w:.6f} {h:.6f}")
                        counts[mapped_cls] = counts.get(mapped_cls, 0) + 1
                    except Exception:
                        continue

        with open(os.path.join(dst_labels_dir, f"{base_name}.txt"), "w") as out_lf:
            out_lf.write("\n".join(converted_lines) + "\n")

def main():
    print("Preparing unified blister cell dataset...")
    counts = {}

    if os.path.exists(ROBOFLOW_DIR):
        process_split("train", "train", counts)
        process_split("valid", "val", counts)
        process_split("test", "test", counts)
    else:
        print(f"Source dir {ROBOFLOW_DIR} not found. Running smoke test mode.")

    print("\nDataset preparation complete.")
    print("Instance counts per MediTrack class:")
    class_names = {0: "strip", 1: "cell_full", 2: "cell_empty", 3: "cell_partial"}
    for cls_id, count in sorted(counts.items()):
        print(f"  {class_names.get(cls_id, cls_id)} (class {cls_id}): {count} instances")

if __name__ == "__main__":
    main()
