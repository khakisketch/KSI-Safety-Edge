# H1 — 장비 능력 검증 계획 (V-CAPABILITY)

- 작성: 2026-10-08 · 상태: **준비 패키지 완료(계획·수집기·기록 서식·재사용 평가) — 실행은 장비 접속·세션 필요**
- 목적: 실제 Matrice 4 / Manifold 3 / RC Plus 2에서 **무엇이 가능한지 실측**하고, 그 결과로 M2 범위·설계 가정을 확정한다.
- 상위 근거: 스펙 `docs/superpowers/specs/2026-10-08-integrated-design.md` §12.3, 원본 `18_SPATIAL_VALIDATION_PLAN.md` G0, 검증 ID `V-CAPABILITY`.
- 이 계획은 **능력 측정**이다. ACTIVE 경고·비행 제어·운영 배포를 승인하지 않는다.

## 0. 실행 원칙

1. **읽기 먼저.** 텔레메트리·카메라·시간·저장을 먼저 측정하고, 기체/짐벌에 대한 쓰기(제어) 시험은 별도 세션·별도 승인으로 분리한다.
2. **증거 없이는 PASS 없음.** 각 항목은 `PASS / FAIL / NOT_RUN`과 증거 파일(로그·CSV·스크린샷·해시)을 남긴다.
3. **안전 경계 유지.** 프로펠러 분리·비행 없음 세션이 기본. DJI 비행 안전 기능과 기존 운영 배포를 건드리지 않는다.
4. **정리(비밀정보).** 시리얼·토큰·앱 자격증명은 마스킹. 원본 SN은 별도 보관, 기록에는 마스킹본만.
5. **단계 의존성.** 앞 단계 FAIL 시 뒤 단계 결과를 신뢰하지 않는다(중단 규칙 §4).

## 1. 참조 자산 (외부 로컬, 참조 전용 — 수정 금지)

| 자산 | 위치(로컬) | 활용 |
|---|---|---|
| DJI 스파이크 절차(8단계) | `~/Desktop/KSI-Projects/KSI-Site-Intelligence-Platform/docs/dji/SPIKE_PROCEDURE.md` | 표준 실행 순서·증거 규율 |
| 실기 세션 playbook | `…/KSI-Site-Intelligence-Platform/docs/hil/HARDWARE_SESSION_PLAYBOOK.md` | 운영 세부·과거 세션 로그 |
| PSDK 콜백 수집기(C) | `…/KSI-Site-Intelligence-Platform-manifold3-shadow/edge/psdk-tap` | Liveview/FC raw NDJSON 수집 → 시간 정합 분석 |
| Shadow DPK·경계 게이트 | `…/KSI-Site-Intelligence-Platform/edge/psdk-app` | DPK 빌드·설치·안전 심볼 게이트 후보 |
| PSDK fetch/lock | `…/KSI-Site-Intelligence-Platform/tools/edge/fetch_psdk.py`, `edge/third_party/PSDK_LOCK.yaml` | PSDK 확보·버전 고정 방식 |

재사용 여부·범위는 별도 재사용 평가로 확정한다(평가 결과: `docs/verification/reuse-assessment.md`).

## 2. 준비물 체크리스트 (세션 전)

- [ ] 기체(4E/4T)·매니폴드 3·RC Plus 2 전원·케이블·프로펠러 분리 상태
- [ ] 매니폴드 네트워크 접속(SSH/noVNC) 가능 — 접속 방법은 기존 playbook 참조
- [ ] PSDK 확보(`PSDK_LOCK.yaml` 버전) + `dji_sdk_app_info.h` 준비(보유 시) — **없으면 H1-2를 NOT_RUN으로 기록**
- [ ] 세션 기록자·운영자, 스크린 캡처 도구
- [ ] 결과 저장 위치: `docs/verification/records/SESS-YYYYMMDDTHHMMSSZ-<short>/`
- [ ] 개인정보·시리얼 마스킹 규칙 적용 확인

## 3. 실행 단계

### H1-1 환경 인벤토리 (실행: `scripts/h1/collect_manifold_env.sh`)

- 수집: 매니폴드 이미지/JetPack/L4T·커널·아키텍처·컴파일러, PSDK 설치 흔적·버전, USB 구성, 네트워크 어댑터, 저장공간, 열·메모리, 기체/RC/앱 버전.
- 합격: 기준선 기록 완료(없으면 이후 전부 중단).
- 증거: `env.md` + `env.json`(스크립트 출력), RC 화면 스크린샷(버전 정보).

### H1-2 공식 PSDK 샘플 빌드·기동

- 공식 샘플(또는 기존 `psdk-tap`) 빌드 → 기동 → 라이브러리 로드 확인.
- 합격: 빌드 로그·기동 로그, 에러코드 0.
- 실패 시: 툴체인 문제와 KSI 코드 문제를 분리할 수 없으므로 이후 단계 중단.

### H1-3 기체 연결·FC/짐벌 텔레메트리 읽기

- 확인 토픽(스펙 `03 §2`): 위치·고도(기준 명시), quaternion, 속도, 각속도, 짐벌 자세, GNSS/RTK 품질, flight mode.
- 측정: 수신률, 표본 age, **동일값 반복 vs 신규 표본 구분**(캐시 읽기 식별), topic timestamp 의미(로컬 수신인지 센서 시간인지).
- 합격: 토픽별 스키마·수신률·timestamp 의미 기록. 미제공 토픽은 명시적 NOT_PROVIDED.

### H1-4 카메라 Liveview·프레임

- H264 콜백 수신, **디코딩된 이미지 API(Manifold 3 한정) 가용성**, 해상도, frame_id 제공 여부.
- 렌즈/소스 전환 관찰(M4E: VIS/4K, M4T: VIS/IR/4K)과 전환 시 프레임 연속성·캘리브레이션 변경 감지 가능성.
- 합격: 프레임 수신 증거(샘플 캡처·카운트). frame_id 없으면 "없음"을 기록(설계는 이미 null 허용).

### H1-5 Frame↔Telemetry 시간 정합 실측 (핵심)

- 수집: `psdk-tap` 방식 raw NDJSON(프레임 수신시각·telemetry 표본시각) — 수집(C)과 분석(PC) 분리 원칙 유지.
- 시나리오: ① 정지 호버 30초(동일 자세 반복값 포함) ② yaw 급변 ③ 짐벌 이동 ④ 렌즈 전환.
- 측정: frame 수신 ↔ 각 topic 표본 age / skew 분포(p50/p95), 신규 표본 비율, gap.
- 한계 명시: **카메라 노출 시각의 절대 정합은 이 방법으로 확정 불가** → 가능하면 시계(스톱워치 LED) 자극 촬영으로 근사 확인, 불가하면 `UNVERIFIED` 유지.
- 합격: 분포 데이터 확보. 설계 가정(스펙 §15)과 다르면 설계 갱신 트리거(품질 등급 하향·geo 보류 유지).

### H1-6 30분 소크 (열·자원·지속성)

- 30분 연속: 프레임 수신률/드롭, CPU·GPU·메모리, 열(throttling), 디스크.
- 합격: 저하 발생 시 시점·원인 기록. 수치를 성능 보장으로 표현하지 않는다.

### H1-7 RC Plus 2 측 확인

- Pilot 2·Android 버전, KSI MSDK 앱 설치 상태, 저장공간, 오프라인 동작, (앱 없으면 녹화 항목 NOT_RUN).
- 합격: 버전·설치 상태 기록. MSDK 앱 빌드 환경(Android SDK) 확보는 별도 항목.

### H1-8 DPK 빌드·설치·재시작 (H1b — 자체 Edge 앱 필요)

- Safety Edge 자체 DPK/앱이 준비된 뒤 실행. 기존 Shadow DPK의 설치 게이트 미완료 이력 참조.
- 합격: 설치·기동·재시작·버전 확인. 실패 시 원인 분리(툴체인/권한/기체).

## 4. 중단 규칙

| 조건 | 처리 |
|---|---|
| H1-1/2 FAIL | 전체 중단 — 기준선 없음 |
| H1-3/4 FAIL | 이후 단계 중단, 차단 사유 기록 |
| H1-5에서 정합 신뢰 불가 | geo/거리 계층은 `WITHHELD` 유지 — M3 진입 전 해소 필요 |
| 열/저하 심각 | 프로파일 축소 방향 기록, 성능 목표 재협의 |

## 5. 결과 기록·반영

- 세션마다 `docs/verification/templates/h1-run-record.md`로 기록 → `docs/verification/records/<session>/`에 저장.
- 기능 매트릭스(요약):

| 항목 | 상태 |
|---|---|
| H1-1 인벤토리 | **부분 PASS(장치측, 2026-10-08)** — 접속·도구체인·저장·PSDK·설치앱 확인. 기체/RC 버전은 미확인(수동 항목) |
| H1-2 샘플 빌드 | 미착수 — 단, **KSI 자체 코드 스모크 빌드 PASS**(`edge/` 코어, gcc 9.4/cmake 3.16.3, ctest 6/6. 증거: `docs/verification/evidence/2026-10-08-h1-1/`) |
| H1-3 FC/짐벌 | NOT_RUN — 기체 링크 인터페이스(l4tbr0/rndis0) NO-CARRIER, 기체 미접속 상태 |
| H1-4 Liveview/프레임 | NOT_RUN — 상동. DPK 설치 필요(KSI DPK 미설치 확인) |
| H1-5 시간 정합 | NOT_RUN — 장치 시계 이상 발견(RTC=1970, NTP inactive, 호스트 대비 −6일) — 세션 전 정비 필요 |
| H1-6 소크 | NOT_RUN |
| H1-7 RC | NOT_RUN — RC 접근 경로 미확보 |
| H1-8 DPK(H1b) | NOT_RUN — tap DPK 3종 staging 존재(Aug 12), 미설치 |

- 장치 접속 경로: `~/.ssh/config`의 `ksi-manifold`(172.30.1.33, dji, 키 `ksi_manifold_ed25519`). 작업 루트 `/open_app/ksi-dev`(94G 여유).
- 결과는 product-state·원장에 요약하고, 설계 변경이 필요하면 스펙 개정 이력으로 남긴다.
- H1 PASS가 ACTIVE 승인이 아님을 유지: 규칙별 실측(G2/G3)은 별도 게이트.
