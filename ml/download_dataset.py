#!/usr/bin/env python3
"""
Downloads Dataset 1 (Blister Pills Segmentation v7) from Roboflow Universe.
Requires ROBOFLOW_API_KEY environment variable. Never commits the API key.
"""

import os
import sys

def main():
    api_key = os.environ.get("ROBOFLOW_API_KEY")
    if not api_key:
        print("ERROR: ROBOFLOW_API_KEY environment variable is not set.")
        print("Please obtain an API key from https://app.roboflow.com and export ROBOFLOW_API_KEY=<your_key>")
        sys.exit(1)

    try:
        from roboflow import Roboflow
    except ImportError:
        print("ERROR: roboflow package not installed. Run: pip install roboflow")
        sys.exit(1)

    print("Authenticating with Roboflow Universe...")
    rf = Roboflow(api_key=api_key)
    project = rf.workspace("blisterdetection").project("blister-pills-segmentation")
    print("Downloading version 7 (YOLOv8 format)...")
    dataset = project.version(7).download("yolov8", location="ml/data/roboflow_v7")
    print(f"Dataset downloaded successfully to: {dataset.location}")

if __name__ == "__main__":
    main()
