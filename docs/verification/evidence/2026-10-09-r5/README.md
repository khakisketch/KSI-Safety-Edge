# R5 recordings — `replay_run` on the Jetson (2026-10-09)

Produced by `edge/tools/replay_run` on the Manifold 3 (aarch64, TensorRT
8.5.2 + CUDA 11.4), build mode `RelWithDebInfo` (the default pinned since
the R5 CMake change), fp16 engine `dfine-s-coco.fp16.op16.engine`
(sha256 `4cf3419b4734dcbfba2c50d727796015a3f5b10cd174bc6d1a67fedef5478f33`),
corpus frames (7) in `manifest.txt` with the synthetic UNcalibrated policy
`policy.txt` — a pipeline demonstration, never metric evidence.

| File | What it is |
|---|---|
| `replay_estimated.ndjson` | policy run over 7 frames: 149 detections, 120 spatial observations, 54 SHADOW episode CREATED events |
| `estimated_b.ndjson` | second identical run — tick lines byte-identical to `replay_estimated.ndjson` |
| `estimated_stages.ndjson` | identical run with `--stage-times` (engine stage means in the stat line) |
| `replay_withheld.ndjson` | 6 frames without exposure/position telemetry, same policy: detections kept, `spatial_status=NOT_ELIGIBLE`, 0 observations, 0 episodes |
| `withheld_manifest.txt` | the manifest used by the withheld recording |
| `manifest.txt`, `policy.txt` | the estimated-run manifest + synthetic policy |

Reproducibility method: `grep '"type":"tick"' FILE | sha256sum` —
`replay_estimated` / `estimated_b` / `estimated_stages` all equal
`ec2ccf07cd3e717174728a3948bd977462fa8eb0b80490879adb6881ba98c04d`.

Cross-build note (finding from the R5 verification round): an identical
run with an unset build type (-O0) produced the same detection set and
stat counts but different float values (Δscore ≤ ~3e-3, boxes ≤ ~0.2 px)
and ~2× slower ticks — the optimization level changes FP codegen and can
flip 1-uint8 preprocess boundary pixels (`preprocess_dump` byte compare:
0.03 % of tensor bytes). `edge/CMakeLists.txt` now pins the default build
type (`RelWithDebInfo`) so recordings and measurements are
mode-explicit.

`harness/tests/test_r5_e2e.py` consumes the estimated/withheld recordings
through the M1 RC-emulator flow (apply → store → upload → Core query);
the bridge is `harness/src/ksi_harness/replay_evidence.py`.
