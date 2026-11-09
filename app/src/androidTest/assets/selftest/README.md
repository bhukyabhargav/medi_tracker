# Instrumented Verification Assets Directory

Place 5+ real-world blister pack photos (e.g. `strip_01.jpg` through `strip_05.jpg`) in this directory.

`RealStripDetectorInstrumentedTest` runs on-device inference on each image and validates that:
1. Strip presence is detected with confidence >= 0.50.
2. Total detected cell count matches expected physical cavity counts within tolerance (+/- 1 cell).
3. No native interpreter segmentation faults or out-of-memory errors occur.
