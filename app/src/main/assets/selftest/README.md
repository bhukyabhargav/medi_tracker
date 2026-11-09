# Model Self-Test Assets Directory

Place real-world blister pack photos (e.g. `strip_01.jpg`, `strip_02.png`) in this directory.

When you open **Debug Tools > Model Self-Test** on device or emulator, MediTrack will automatically discover all images in this directory, execute the on-device LiteRT/TFLite YOLOv8 model (`strip_detector.tflite`), and display:
- Strip detection confidence and bounding box
- Cavity counts categorized by state (`FULL`, `EMPTY`, `PARTIAL`)
- Average and per-cell detection confidence scores
- Inference latency (milliseconds)

> Note: Gold standard physical blister pack photos must be supplied by the product owner or testing team.
