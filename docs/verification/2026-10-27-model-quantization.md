# Manifold 3 모델·양자화 전략 (장치 실측 근거)

Date: 2026-10-27. Hardware: NVIDIA Orin NX (Tegra234, Ampere sm_87),
JetPack (CUDA 11.4, TensorRT 8.5.2, NvInfer 8.5.2). trtexec available at
`/usr/src/tensorrt/bin/trtexec` (not on PATH).

## 계보 (장치에서 직접 검증)

- `model_id`: `ksi-person-detector-dfine-s-coco`
- `weights_revision`: `f79e65b5fbb33ceb9d3ebba042955d7410c608f8`
- `dfine-s-coco.onnx` sha256 = manifest `onnx_sha256`
  (`96b8e0f...503aba3e7`) — 장치에서 재계산 일치 확인.
- Opset 17, outputs fixed to logits,pred_boxes. D-FINE (DETR 계열,
  NMS-free — 후처리 NMS 비용이 없어 Edge에 적합).

## 양자화 판정

Orin NX(Ampere)는 FP32/FP16/INT8을 지원하고, FP8은 지원하지 않는다
(Hopper/Ada 이상). 장치 실측 (measurement (실측 원본 비공개 보존)):

| 정밀도 | mean / p95 | 판정 |
|---|---|---|
| FP16 (운용) | 20.3 / 25.6 ms | 채택 — 33.3 ms 안에 듦 |
| FP32 (진단용) | 32.4 / 36.2 ms | 단독으로 주기 초과 — 운용 불가 |
| INT8 | 미구현 | 아래 사유로 미채택 |

- FP16↔FP32 검출 비교: 게이팅 검출(점수≥0.2) 클래스 동일·점수차 ≤0.0056.
  꼬리만 재정렬 (fp16 노이즈 플로어, 문서화된 범위).
- **INT8를 지금 도입하지 않는 이유**: (1) calibration 세트 없음 —
  대표 현장 입력이 없어 trtexec calibration 자체가 불가, (2) 정확도
  하네스·GT 없음 — 소형 인물 정확도 하락을 검증할 수단 없음, (3) 현재
  headroom에서 불필요 (정상 추정 31/33.3ms). INT8은 calibration 세트 +
  정확도 하네스 + GT가 갖춰진 뒤에 재검토.

## 필요한 모델과 상태

| 모델 | 상태 | 비고 |
|---|---|---|
| 사람 탐지 (D-FINE-S COCO, fp16) | ✅ 운용 | 위 실측 |
| PPE (헬멧/조끼) | ❌ 없음 | 학습 필요. COCO person ≠ PPE 판정 — 주장 금지 |
| 화재/연기 | ❌ 없음 | 학습 필요 |
| 건설장비 specialist (굴착기 등) | ❌ 없음 | COCO truck/car를 굴착기로 주장 금지 |

새 모델 도입에 필요한 것 (현재 없음): 데이터셋, 학습 인프라·파이프라인
(Core review.py는 기록 전용이며 학습 플랫폼이 아님), 독립 GT,
배포 게이트. 순서: 데이터 → 학습 → 정확도 검증 → TensorRT 변환 →
장치 지연·정확도 실측 → 게이트.
