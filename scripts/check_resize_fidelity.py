"""Quantify resize fidelity: PIL (RTDetrImageProcessor) vs the ACTUAL C++
PreprocessRgb output (via edge/tools/preprocess_dump), and its effect on
D-FINE detections (ORT fp32).

The C++ resampling is now PIL-compatible (antialias-scaled support on
downscale + PIL's uint8 intermediate quantization); golden tests in
edge/tests/test_dfine.cpp pin actual PIL values. This script verifies the
end-to-end fidelity on real assets and a synthetic 1080p worst case.

Requirements (host, not committed): onnxruntime, numpy, Pillow, the
exported D-FINE ONNX, and the built `preprocess_dump` binary. Env:
  PREPROCESS_DUMP   path to the preprocess_dump binary (required)
  DFINE_ONNX        default: /tmp/opencode/dfine-export/dfine-s-coco.op16.onnx
  SI_TEST_ASSETS    default: SI models/test-assets
"""
import os
import subprocess
import tempfile

import numpy as np
import onnxruntime as ort
from PIL import Image

SI = "/home/ksi/Desktop/KSI-Projects/KSI-Site-Intelligence-Platform"
ONNX = os.environ.get("DFINE_ONNX",
                      "/tmp/opencode/dfine-export/dfine-s-coco.op16.onnx")
ASSETS = os.environ.get("SI_TEST_ASSETS", f"{SI}/models/test-assets")
DUMP = os.environ.get("PREPROCESS_DUMP", "")
sess = ort.InferenceSession(ONNX, providers=["CPUExecutionProvider"])


def cpp_tensor(img):
    """Runs the C++ PreprocessRgb (preprocess_dump) and returns 1x3x640x640."""
    if not DUMP:
        raise SystemExit("set PREPROCESS_DUMP to the preprocess_dump binary")
    arr = np.asarray(img, dtype=np.uint8)
    h, w = arr.shape[0], arr.shape[1]
    with tempfile.TemporaryDirectory() as td:
        ip = os.path.join(td, "in.rgb")
        op = os.path.join(td, "out.f32")
        arr.tofile(ip)
        subprocess.run([DUMP, "--input", ip, "--width", str(w),
                        "--height", str(h), "--out", op],
                       check=True, capture_output=True)
        data = np.fromfile(op, dtype=np.float32)
    return data.reshape(1, 3, 640, 640)


def pil_tensor(img):
    a = np.asarray(img.resize((640, 640), Image.BILINEAR),
                   dtype=np.float32) / 255.0
    return np.transpose(a, (2, 0, 1))[None, ...]


def decode(logits, boxes):
    sc = 1.0 / (1.0 + np.exp(-logits[0]))
    best = sc.max(-1)
    cls = sc.argmax(-1)
    sel = best > 0.05
    cx, cy, w, h = boxes[0][:, 0], boxes[0][:, 1], boxes[0][:, 2], boxes[0][:, 3]
    xy = np.stack([(cx - w / 2) * 640, (cy - h / 2) * 640,
                   (cx + w / 2) * 640, (cy + h / 2) * 640], 1)
    dets = [(int(c), float(s), float(b[0]), float(b[1]), float(b[2]), float(b[3]))
            for c, s, b in zip(cls[sel], best[sel], xy[sel])]
    return sorted(dets, key=lambda d: -d[1])


def iou(a, b):
    x1, y1 = max(a[0], b[0]), max(a[1], b[1])
    x2, y2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def compare(name, img):
    ta, tb = pil_tensor(img), cpp_tensor(img)
    dt = float(np.abs(ta - tb).max())
    mt = float(np.abs(ta - tb).mean())
    la, ba = sess.run(None, {"pixel_values": ta})
    lb, bb = sess.run(None, {"pixel_values": tb})
    da, db = decode(la, ba), decode(lb, bb)
    gating = [d for d in da if d[1] >= 0.2]
    matched = 0
    worst = (1.0, 0.0)
    for e in gating:
        best_m = (0.0, 0.0)
        for c in db:
            if c[0] != e[0]:
                continue
            i = iou(e[2:6], c[2:6])
            if i > best_m[0]:
                best_m = (i, abs(e[1] - c[1]))
        ok = best_m[0] >= 0.5 and best_m[1] <= 0.05
        matched += 1 if ok else 0
        if not ok and best_m[0] < worst[0]:
            worst = best_m
    top = ""
    if da and db:
        top = (f" top1 PIL cls={da[0][0]} s={da[0][1]:.4f} vs "
               f"C++ cls={db[0][0]} s={db[0][1]:.4f} "
               f"IoU={iou(da[0][2:6], db[0][2:6]):.4f}")
    miss = "" if matched == len(gating) else \
        f" worst_miss IoU={worst[0]:.3f} dscore={worst[1]:.3f}"
    print(f"{name}: tensor max|d|={dt:.5f} mean|d|={mt:.6f} | "
          f"gating {matched}/{len(gating)}{miss};{top}")


def main():
    for f in ["onnx-corpus/single_person.png", "onnx-corpus/non_person_car.png",
              "person.jpg"]:
        compare(f, Image.open(f"{ASSETS}/{f}").convert("RGB"))
    base = Image.open(f"{ASSETS}/person.jpg").convert("RGB")
    compare("person 1920x1080 NEAREST (synthetic worst case)",
            base.resize((1920, 1080), Image.NEAREST))


if __name__ == "__main__":
    main()
