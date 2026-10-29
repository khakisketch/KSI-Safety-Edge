# 다음 모델 권장안 (장치 타당성 포함)

> **현행 보완:** 2026-10-27 사용자는 DJI 기본 감지와 기존 모델을 먼저 비교하고 신규 모델
> 구매·파인튜닝을 보류하는 방향에 동의했다. 아래 PPE 우선순위는 당시 제안이다.
> 아래 `MIT ✅`는 모델 카드의 표시이지, 기반 YOLO·실제 변환 산출물의 상용 권리까지
> 확인했다는 뜻이 아니다. [권리·업체 확인](2026-10-27-existing-model-rights-review.md).

Date: 2026-10-27. Question: which model to implement first, and what the
drone needs to operate as an actual robot (incl. VLM).

## 장치 타당성 (Manifold 3 실측, TensorRT 8.5.2, fp16)

| 후보 (KSI-CCTV 형제 모델) | 클래스 | TRT 빌드 | 추론 mean/p95 | 출력 |
|---|---|---|---|---|
| `yolo26_ppe.onnx` (6.1 MB) | 10: Hardhat/Mask/NO-Hardhat/NO-Mask/NO-Safety Vest/Person/Safety Cone/Safety Vest/machinery/vehicle | ✅ PASS | **4.17 / 4.68 ms** | 1×14×8400 (NMS 필요) |
| `yolo26_fire_smoke.onnx` (5.3 MB) | 2: Fire/Smoke | ✅ PASS | **4.65 / 5.19 ms** | 1×6×8400 (NMS 필요) |

둘 다 640×640 입력(opset 17, NMS 미포함 — CPU NMS 디코더 신규 필요).
D-FINE(20.3ms)과 병렬로 돌려도 33.3ms 안에 듦. 정확도는 **두 모델 모두
우리 도메인에서 미검증** (아래 계보 참조).

## 계보·라이선스 (도입可否 판단)

- **PPE**: `Hansung-Cho/yolov8-ppe-detection`, **MIT** ✅. 보고 성능
  mAP@0.5 0.744 / P 0.831 / R 0.685 (작성자 주장, 미검증). 제한사항 명시됨
  (도메인 편향·가림·저해상도 원거리). "징계·생명안전 단독근거 금지" —
  우리 SHADOW-only 원칙과 일치.
- **화재**: sayedgamal99 YOLO11n (Fire/Smoke, P 0.806/R 0.717/mAP@0.5 0.770
  작성자 주장). **LICENSE 파일 없음 — 제품 사용 불가.** 저자 허락 또는
  라이선스 명시 대체 모델 확보가 선행 조건.

## 권장: 첫 모델 = PPE, 2단계 분류기로

1. **PPE를 첫 번째로**: 라이선스 ✅ + 장치 실행 ✅ + 보고 성능 존재 +
   매일 관측(안전모/조끼)에 직결. 화재는 라이선스 해결 후 두 번째.
2. **아키텍처: person ROI crop 위 2단계 분류기** (전면 병렬 탐지기 아님):
   D-FINE이 person을 찾으면 → 기존 ROI crop 경로로 잘라 → PPE 모델로
   헬멧/조끼 판정. 이유: (a) ROI 인프스트럭처 재사용, cooldown으로
   호출율 제한, (b) person 귀속이 명확 (전면 병렬은 track 연결 문제 발생),
   (c) 실패해도 D-FINE 경로에 영향 없음.
3. **필요한 신규 코드**: YOLO 출력 디코드 + CPU NMS 어댑터
   (host-tested, DfineDetectorPort 패턴), 2단계 호출 배선 (명시적 opt-in
   정책), SHADOW-only 출력 (판정·경보 없음).
4. **도입 게이트** (순서 고정): 현장 사진 holdout 평가 → TensorRT 변환 →
   장치 지연·정확도 실측 → SHADOW 배포. 정확도 수치 없이 절대 켜지 않음.

## "로봇으로서의 드론"에 필요한 것 — 범위 정정

우리 제품 정본상 드론은 **자율로봇이 아니다**: 비행은 DJI 영역이고,
우리는 관측·평가·판정불가·증거만 한다. VLM/LLM은 **비행 후 검토 전용**
(원칙 3) — 실시간 판단 참여 금지. 이 경계를 바꾸는 것은 제품 재정의이며
별도 승인 사항이다.

그 경계 안에서 로봇답게 동작하는 데 필요한 것:

| 필요 | 상태 |
|---|---|
| 사람 탐지 (D-FINE-S fp16) | ✅ 운용 |
| PPE 2단계 (위 권장안) | ⬜ 다음 구현 |
| 화재 (라이선스 해결 후) | ⬜ 그 다음 |
| 건설장비 specialist | ❌ 없음 — COCO truck/car 주장 금지, 학습부터 |
| 공간 grounding (calibration/ENU) | ❌ 없음 — 실측 GT와 함께 확보 |
| 라이브 카메라 경로 | ❌ 기체 대기 |
| VLM (온보드 실시간) | ✖️ 제품 원칙상 제외. 검토용 VLM은 호스트/서버에서만 |

온보드 VLM을 기술적으로만 보면 Orin NX에서 소형 VLM 실행 자체는
가능하겠지만, 지연·전력·정확도가 미증명이고 제품 원칙이 금지하므로
추진하지 않는다. CCTV의 VLM은 연기 보조용이며 그쪽도 보존 미검증이다.
