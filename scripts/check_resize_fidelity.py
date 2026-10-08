"""Quantify resize-fidelity difference: PIL (RTDetrImageProcessor) vs the
C++ PreprocessRgb resampling, and its effect on D-FINE detections (ORT fp32).

Why this exists: the C++ preprocessing must approximate the validated
reference preprocessing (HF RTDetrImageProcessor = PIL resize + /255).
The W9 smoke inputs are identity 640x640, so resampling differences never
surfaced there. This script measures them on real assets and on a
synthetic 1080p worst case.

Requirements (host, not committed): onnxruntime, numpy, Pillow, and the
exported D-FINE ONNX. Defaults point at the W9-FU workspace; override via
env: DFINE_ONNX, SI_TEST_ASSETS.

NOTE (W10): the "our" side below replicates the current C++ formula in
Python. Once the C++ resampling is aligned with PIL, switch this side to
the C++ dump tool output (edge/tools/preprocess_dump) so the comparison
is against the actual implementation, not a replica.
"""
import os

import numpy as np
import onnxruntime as ort
from PIL import Image

SI = "/home/ksi/Desktop/KSI-Projects/KSI-Site-Intelligence-Platform"
ONNX = os.environ.get("DFINE_ONNX",
                      "/tmp/opencode/dfine-export/dfine-s-coco.op16.onnx")
ASSETS = os.environ.get("SI_TEST_ASSETS", f"{SI}/models/test-assets")
sess = ort.InferenceSession(ONNX, providers=["CPUExecutionProvider"])


def our_bilinear_tensor(img):
    """Replicates the current C++ PreprocessRgb formula (2-tap, half-pixel)."""
    arr = np.asarray(img, dtype=np.float32)
    sh, sw = arr.shape[0], arr.shape[1]
    dw = dh = 640
    sx = (np.arange(dw, dtype=np.float64) + 0.5) * sw / dw - 0.5
    sy = (np.arange(dh, dtype=np.float64) + 0.5) * sh / dh - 0.5
    x0 = np.floor(sx).astype(np.int64)
    fx = (sx - x0).astype(np.float32)
    y0 = np.floor(sy).astype(np.int64)
    fy = (sy - y0).astype(np.float32)
    lo_x, hi_x = x0 < 0, x0 >= sw - 1
    lo_y, hi_y = y0 < 0, y0 >= sh - 1
    x0 = np.clip(x0, 0, sw - 1)
    y0 = np.clip(y0, 0, sh - 1)
    fx = np.where(lo_x | hi_x, np.float32(0.0), fx).astype(np.float32)
    fy = np.where(lo_y | hi_y, np.float32(0.0), fy).astype(np.float32)
    x1 = np.minimum(x0 + 1, sw - 1)
    y1 = np.minimum(y0 + 1, sh - 1)
    v00 = arr[np.ix_(y0, x0)]
    v10 = arr[np.ix_(y0, x1)]
    v01 = arr[np.ix_(y1, x0)]
    v11 = arr[np.ix_(y1, x1)]
    top = v00 + (v10 - v00) * fx[None, :, None]
    bot = v01 + (v11 - v01) * fx[None, :, None]
    out = (top + (bot - top) * fy[:, None, None]) * np.float32(1.0 / 255.0)
    return np.transpose(out, (2, 0, 1))[None, ...]


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
    ta, tb = pil_tensor(img), our_bilinear_tensor(img)
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
               f"our cls={db[0][0]} s={db[0][1]:.4f} "
               f"IoU={iou(da[0][2:6], db[0][2:6]):.4f}")
    miss = "" if matched == len(gating) else \
        f" worst_miss IoU={worst[0]:.3f} dscore={worst[1]:.3f}"
    print(f"{name}: tensor max|d|={dt:.4f} mean|d|={mt:.5f} | "
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
