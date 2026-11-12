#!/usr/bin/env python
"""Export the Roboflow-trained RF-DETR Nano blister model (ml/weights.pt) for on-device use.

Produces app/src/main/assets/rfdetr_blister.onnx, the model that RfdetrStripDetector.kt runs offline.

    pip install rfdetr onnx onnxruntime
    python ml/export_rfdetr_onnx.py

Model facts (read from the checkpoint):
- RF-DETR Nano, input 1x3x384x384, ImageNet mean/std normalization, whole image resized without letterbox
- outputs: dets [1,300,4] (cx,cy,w,h normalized) and labels [1,300,5] (logits)
- class index 3 = empty cavity, 4 = full cavity

The export is dynamically quantized to 8-bit weights (about 35 MB instead of 115 MB). On the reference strip
photo and its variations the quantized model gave the same counts as the full-precision model.
ml/weights.pt is not committed (see .gitignore); download it from the Roboflow model page.
"""
import shutil
import sys
import tempfile
from pathlib import Path

from onnxruntime.quantization import QuantType, quantize_dynamic
from rfdetr import RFDETRNano

ROOT = Path(__file__).resolve().parent.parent
WEIGHTS = ROOT / "ml" / "weights.pt"
TARGET = ROOT / "app" / "src" / "main" / "assets" / "rfdetr_blister.onnx"


def main() -> int:
    if not WEIGHTS.exists():
        print(f"Missing {WEIGHTS}. Download the weights from the Roboflow model page first.")
        return 1
    with tempfile.TemporaryDirectory() as tmp:
        model = RFDETRNano(pretrain_weights=str(WEIGHTS), num_classes=4)
        model.export(output_dir=tmp)
        exported = next(Path(tmp).glob("*.onnx"))
        quantized = Path(tmp) / "quantized.onnx"
        quantize_dynamic(str(exported), str(quantized), weight_type=QuantType.QUInt8)
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(quantized, TARGET)
    print(f"Wrote {TARGET} ({TARGET.stat().st_size / 1e6:.1f} MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
