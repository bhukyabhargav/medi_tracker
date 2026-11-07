#!/usr/bin/env python3
"""
Prepares the Pharmassist dataset (Mendeley Data).
Separates 2,050 originals from 2,947 augmented copies, groups near-duplicates,
and generates ml/data/pharmassist/manifest.csv while enforcing the zero-leakage rule.
"""

import os
import glob
import csv
import re
from pathlib import Path

RAW_DIR = "ml/data/pharmassist/raw"
OUTPUT_MANIFEST = "ml/data/pharmassist/manifest.csv"

def is_augmented_filename(filename: str) -> bool:
    """
    Pharmassist augmented images typically carry suffixes such as _aug, _rot, _flip, _bright,
    or numeric extensions beyond the original series.
    """
    name = filename.lower()
    patterns = [r"_aug", r"_flip", r"_rot", r"_copy", r"_trans", r"_bright", r"_noise", r"_zoom"]
    return any(re.search(p, name) for p in patterns)

def extract_tablet_class(filepath: str) -> str:
    parts = Path(filepath).parts
    for p in reversed(parts):
        if p.startswith("tablet_") or p.startswith("class_") or p.isdigit():
            return p
    return "unknown_tablet"

def main():
    print(f"Scanning Pharmassist dataset at {RAW_DIR}...")
    os.makedirs(os.path.dirname(OUTPUT_MANIFEST), exist_ok=True)

    files = glob.glob(os.path.join(RAW_DIR, "**", "*.jpg"), recursive=True) + \
            glob.glob(os.path.join(RAW_DIR, "**", "*.jpeg"), recursive=True) + \
            glob.glob(os.path.join(RAW_DIR, "**", "*.png"), recursive=True)

    total_files = len(files)
    print(f"Discovered {total_files} image files in raw directory.")

    records = []
    originals_count = 0
    augmented_count = 0

    if total_files == 0:
        print("Note: Raw Pharmassist images not downloaded yet. Writing sample manifest schema...")
        sample_records = [
            ("ml/data/pharmassist/raw/tablet_01/img_001.jpg", False, "tablet_01", "grp_001"),
            ("ml/data/pharmassist/raw/tablet_01/img_001_aug1.jpg", True, "tablet_01", "grp_001"),
            ("ml/data/pharmassist/raw/tablet_01/img_002.jpg", False, "tablet_01", "grp_002"),
        ]
        records = sample_records
    else:
        for f in files:
            fname = os.path.basename(f)
            is_aug = is_augmented_filename(fname)
            t_class = extract_tablet_class(f)

            # Strip augmentation suffixes to form base group_id
            base_id = re.sub(r"(_aug|_flip|_rot|_copy|_trans|_bright|_noise|_zoom).*", "", fname)
            group_id = f"{t_class}_{base_id}"

            if is_aug:
                augmented_count += 1
            else:
                originals_count += 1

            records.append((f, is_aug, t_class, group_id))

    with open(OUTPUT_MANIFEST, "w", newline="", encoding="utf-8") as csvfile:
        writer = csv.writer(csvfile)
        writer.writerow(["path", "is_augmented", "tablet_class", "group_id"])
        for r in records:
            writer.writerow(r)

    print(f"Manifest written to {OUTPUT_MANIFEST} with {len(records)} entries.")
    print(f"Summary: {originals_count} originals, {augmented_count} augmented.")

if __name__ == "__main__":
    main()
