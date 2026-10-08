# 07. AI 모델·사후 오탐/미탐 검토·평가 계획

## 1. 왜 서버 GPU를 필수로 만들지 않는가

실시간 사람/PPE/장비 탐지·Tracking·Zone 위험 후보는 Manifold3에서 우선 처리한다. 서버 GPU는 야간/사후 batch의 분석 효용이 입증된 뒤에만 켠다. 초기에는 기존 DGX Spark에서 실험, 고가 상시 GPU 서버 증설 금지.

## 2. 역할 경계

| 역할 | 주체 | 결과의 의미 |
|---|---|---|
| 1차 실시간 탐지 | Manifold PSDK + KSI AI | 최초 raw detection / hazard candidate |
| 현장 의견 | 조종사 MSDK | 오탐 의심, 확인 필요의 비강제 annotation |
| 사후 검토 | DGX Spark VLM/전문 CV | 오탐/미탐 **후보**, 근거 설명, uncertain |
| 실제 정답 판정 | 권한 있는 사람/평가 프로세스 | 추후 모델 평가·재학습 가능 GT |
| 현장 상황 설명 | 외부 Agent API/MCP | 저장 근거와 한계에 대한 자연어 설명 |

## 3. 사후 Job 구분

- `FP_REVIEW`: Edge가 탐지한 사건 이미지+전후 영상에 PPE/객체의 존재·확실성 재검토. VLM이 다르게 답해도 최초 event 불변.
- `FN_AUDIT_SAMPLE`: 탐지 0건 또는 정상 구간에서 위험 후보 탐색. frame window sampling + 위험구역/AI 미가동 구간 우선. **미탐 0건 입증 불가**.
- `COMPLEX_BEHAVIOR_REVIEW`: 중장비·작업자 움직임의 시간 관계. 근거 frame/timecode 없으면 설명만으로 '위반' 확정 금지.
- `IMAGE_QUALITY_REVIEW`: blur, small object, occlusion 등 판단 불가를 찾고 재관측 필요성 제시.

VLM 모델이 장면을 그럴듯하게 설명하더라도 법적 안전위반 여부의 최종 판단은 하지 않는다.

## 4. 배치 트리거

- RC에서 서버에 `COMPLETE`로 올라온 비행 중 정책 일치분만 nightly queue.
- `PARTIAL` 비행은 빠진 증거/coverage를 명시하고 선택적 한정 분석. 누락을 '안전함'으로 결론짓지 않음.
- 운영자 의심 표시/고위험 사건/무탐 비행 표본/새 모델 시험군을 우선순위로 사용.
- job은 `SUBMITTED → QUEUED → RUNNING → SUCCEEDED | FAILED | INSUFFICIENT_EVIDENCE | SKIPPED`.
- 실패 재시도/중복 없이 `job_key = hash(flight + task + model + prompt_revision + evidence_manifest)`.

## 5. DGX Spark 비용/자원 보호

- CPU worker가 프레임 샘플링·메타데이터로 대상 선별, 이후에만 VLM 호출.
- VRAM/disk/job concurrency/token budget 제한; 야간 단위 최대 처리시간·GPU-hours 정책. 경과 시간보다 정밀도 목표이므로 비실시간 batch 허용.
- 기본 GPU 모델 스펙·용량·성능 수치 하드코딩 금지. 장비에서 직접 batch test 실시.
- VLM은 자체 호출만이 아니라 필요시 별도 상용 모델과 오프라인 벤치 비교 가능하되 개인정보 전송 정책 우선.

## 6. 데이터셋·평가 방법

**정답 데이터 생성**
1. 현장/장비/조명/계절·시간·고도별 대표 영상 선정 (privacy review).
2. 원본 Edge 사건 뿐 아니라 no-event sampled clips에 라벨링.
3. 독립 2인 또는 정해진 분쟁해결 프로토콜로 Ground Truth 분리.
4. `FP`는 실제 탐지 사건이 거짓인 경우, `FN`은 정답 사건을 1차 AI가 놓친 경우. 여러 프레임의 동일 상황은 episode 단위로 비교.
5. train/validation/test를 현장·날짜·비행 단위로 분리하여 leakage 차단.

**주요 지표**
- precision, recall, F1(클래스/거리·고도/품질별), incident-level precision/recall.
- FP review precision/recall, FN candidate precision/recall(전체 audit ground truth 대비), reviewer minutes/flight.
- 권고/재관측 제안의 유용성, calibration, model hallucination/unsupported claims.
- data quality: edge+RC evidence link ratio, model_versions captured, no-event coverage audit rate, missing timestamps count.

`VLM 정확도 상승`은 동일한 holdout 정답세트에서 precision/recall 또는 안전관리자 검토시간 개선을 증명했을 때만 운영에 도입한다. AI 의견은 사람 판단을 대체하지 않는다.

## 7. 오탐 운영 정책

- `UNREVIEWED`가 기본값이고 대량 승인 기능은 불필요.
- `FALSE_POSITIVE_SUSPECTED`: 주관적 현장 의견. 필요시 일반 조치 Inbox에서 숨길 수 있지만 raw counters/증거에서 제외하지 않음.
- `NEEDS_REVIEW`: 비행 후 또는 관리자에게 우선 검토 요청.
- `REVIEW_FINDING`: 사후 VLM이 `FP_CANDIDATE`, `FN_CANDIDATE`, `UNCERTAIN` 등을 추가. 충돌시 사람이 확인.
- `ADJUDICATED`: 정답 확보한 테스트 데이터만 들어가는 별도 영역; 이때도 원본 불변.
- 고위험 즉시 위험은 오탐 표시가 됐다는 이유만으로 안전 알람 기록을 물리적으로 제거할 수 없음.

## 8. API/Agent 사용 예

- `지난주 비행에서 AI가 위험을 감지했지만 사람이 오탐 의심으로 표시한 항목 보여줘` → `list_events(review=FP_SUSPECTED)`.
- `어제 0건 비행에서 놓친 위험은 없을까?` → 커버리지/무탐 표본 검사 결과와 **검사하지 않은 구간 명시**.
- `어제 가장 위험한 구역` → 당시 zone revision, 유효 관측, event count/quality, 미관측을 포함한 근거 중심 답변.
- 에이전트는 원본 사건을 지우거나 위험등급을 독단 변경할 권한이 없음.
