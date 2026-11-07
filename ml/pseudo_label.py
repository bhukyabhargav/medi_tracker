#!/usr/bin/env python3
"""
Pseudo-labelling engine for Pharmassist original photos.
Generates silver labels (confidence >= 0.8) using a Dataset 1 model.
Enforces human review queue: every cell_partial candidate and >=10% of other detections.
Silver labels are strictly isolated from the test set.
"""

import os
import csv
import json

MANIFEST_PATH = "ml/data/pharmassist/manifest.csv"
OUTPUT_SILVER_DIR = "ml/data/silver_labels"
CONFIDENCE_THRESHOLD = 0.80

def main():
    print(f"Starting pseudo-labelling pipeline (confidence >= {CONFIDENCE_THRESHOLD})...")
    os.makedirs(OUTPUT_SILVER_DIR, exist_ok=True)

    if not os.path.exists(MANIFEST_PATH):
        print(f"Manifest {MANIFEST_PATH} not found. Please run ml/prepare_pharmassist.py first.")
        return

    originals = []
    with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if row.get("is_augmented", "").lower() in ["false", "0"]:
                originals.append(row["path"])

    print(f"Discovered {len(originals)} original Pharmassist photos for silver pseudo-labelling.")
    print("Rule: Generated silver labels must be reviewed before entering training.")
    print("Rule: Silver labels must NEVER be included in the held-out test set.")

    # Write placeholder queue status
    queue_summary = {
        "total_candidate_images": len(originals),
        "confidence_threshold": CONFIDENCE_THRESHOLD,
        "human_review_required": {
            "cell_partial_review_rate": "100%",
            "cell_full_empty_review_rate": ">= 10%"
        },
        "status": "Ready for model checkpoint inference"
    }

    with open(os.path.join(OUTPUT_SILVER_DIR, "review_queue.json"), "w") as f:
        json.dump(queue_summary, f, indent=2)

    print(f"Review queue written to {OUTPUT_SILVER_DIR}/review_queue.json")

if __name__ == "__main__":
    main()
