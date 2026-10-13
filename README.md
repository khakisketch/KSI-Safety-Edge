# KSI Safety Edge — 오프라인 우선 드론 안전 관측 시스템

[![CI](https://github.com/khakisketch/ksi-safety-edge/actions/workflows/ci.yml/badge.svg)](https://github.com/khakisketch/ksi-safety-edge/actions/workflows/ci.yml)
![Python 3.12](https://img.shields.io/badge/Python-3.12-blue)
![FastAPI](https://img.shields.io/badge/FastAPI-Core%20API-009688)
![C++ Edge](https://img.shields.io/badge/C%2B%2B-Edge%20PSDK-00599C)
![Android RC](https://img.shields.io/badge/Android-RC%20MSDK-3DDC84)

> 인터넷이 없어도 현장에서 사람·중장비를 관측·기록하고,
> 연결 복구 후 **당시의 설정과 증거를 그대로 재현**하는 드론 기반 안전 관측 시스템입니다.

**개발: 주식회사 케이에스아이 ｜ 문의: 043-288-4860**

---

## 제품 상태 (공개 시점 기준)

| 구분 | 상태 |
|---|---|
| 재생 시나리오 검증 (E01~E12) | 재생 환경에서 확인 — 사용자 인수 미실시 |
| 실제 기체·RC 검증 | 진행 중 (실기 검증 전) |
| 운영 배포 (인증·권한·HTTPS) | 보안 게이트 준비 전 |

- 해결하려는 문제: 네트워크가 없는 현장에서도 사람·중장비 관측 기록이 끊기지 않고, 연결 복구 후 당시 설정과 증거를 그대로 조회할 수 있어야 합니다.
- 현재 확인된 근거: 재생 하네스에서 `임무 적용 → 오프라인 기록 → 재시작 복원 → 업로드 → 조회` 전 구간과 장애 시나리오(E01~E12)를 재현합니다. Python·C++·Android 3축 CI가 `main` 푸시마다 실행됩니다.
- 현장 적용 전 남은 것: 실제 기체·RC 실기 검증, 사용자 인수, 운영 인증·권한·HTTPS 게이트입니다.

---

## 한눈에 보기

| 구성 | 역할 | 기술 |
|---|---|---|
| **Edge (기체 탑재)** | Matrice 4 + Manifold 3, 사람·중장비 탐지·추적·공간 분석 | C++ (PSDK), ONNX / TensorRT |
| **RC (조종기)** | 오프라인 기록·미션 적용·복구 후 업로드 | Android (MSDK), Kotlin, SQLite WAL |
| **Core (서버)** | 증거 수집·데이터 점검·의견 기록(개발·검증용) | Python FastAPI, SQLite / 파일 오브젝트 저장 |

![시스템 아키텍처](spatial_architecture.png)

- Edge → 서버 직접 업로드 없음. **RC가 유일한 업로드 주체** (오프라인 우선 원칙)
- 공간 판단은 **SHADOW(참고 표시)** 가 기본. 자동 경고(ACTIVE)는 검증된 규칙에서만 허용
- “사건 0건 ≠ 안전” — 관측 공백(커버리지·품질 사유)을 함께 표시하는 정직 표기

## 설계된 현장 운용 흐름

> 아래는 설계된 운용 흐름(목표 동작)입니다. RC→Core 전송의 종단간 검증은 별도 절차이며,
> 실기 검증·사용자 인수 전입니다.

```
① 비행 전 준비 (RC) → ② 비행 중 오프라인 관측·기록 (Edge+RC)
→ ③ 연결 복구 후 업로드 (RC→Core) → ④ 비행 검토·의견 기록 (Core 웹)
```

1. **임무 준비** — 사이트·미션 번들·구역 정책을 RC에 동기화하고 Edge에 적용합니다.
2. **비행 관측** — 네트워크 없이도 탐지·추적·공간 관측을 수행하고 RC 로컬 DB에 영속 기록합니다.
3. **증거 동기화** — Wi-Fi/4G 복구 후 업로드 큐가 재개·중복방지·무결성 검증과 함께 Core에 전송합니다.
4. **데이터 점검** — 업로드 상태·관측 커버리지·사건별 근거·증거 원본을 조회합니다 (설계된 흐름이며 운영자 검토 서비스는 별개입니다).

## 화면 소개 (시연 화면)

> 아래 화면은 시연 환경 캡처이며, 화면 우측 상단의 `[SIMULATED]` 표기를 유지합니다.
> 현장명은 예시(`SIM-FLT-024` 등)이며 특정 실현장을 지칭하지 않습니다.

### RC 조종(관측) — 오프라인 상태에서도 운용 상태·AI 관측 피드를 확인

![RC 조종 관측 화면](design/rc-pilot-shell/captures/round3-review/01-observe-normal.png)

- 좌: 운용 상태(비행·MOP 링크·기체·인터넷·레코더·미동기화·AI 관측·요약)
- 중: 라이브 뷰 + MOP 연결/해제/재전송, 프레임↔관측 매핑 미검증 시 정직 표시
- 우: MOP 송수신 카운터 + AI 관측 피드(`[DETECT]`/`[SHADOW]`는 신호 표시이며 안전 판정이 아님)

### Core 데이터 점검 — 개발자용 시연 화면 (운영자 검토 아님)

![Core 데이터 점검 화면](design/review-console/captures/01-flight-review.png)

- Core API가 반환하는 `upload_state`·`COMPLETE/PARTIAL`·커버리지·사건 수·증거 수를 그대로 보여주는 개발자 진단 화면입니다.
- RC 업로드를 실행하거나 RC→Core 전달을 증명하지 않으며, 운영자용 검토 서비스와는 별개입니다.

<details>
<summary>더 보기: 임무 준비 · A-2 의견 기록 · 증거 응답 조회</summary>

### RC 임무 준비 — 오프라인에서도 준비 상태 확인, 예시 동작만 표시

![RC 임무 준비 화면](design/rc-pilot-shell/captures/round3-review/04-mission-prep.png)

- 사이트 동기화·번들 다운로드·준비/적용/정합(예시 동작이며 실제 임무·기체·서버에 변경을 보내지 않음)

### A-2 의견 기록 — 개발·검증용 쓰기 경로 (운영 검토 아님)

![A-2 의견 기록 화면](design/review-console/captures/02-opinion-workbench.png)

- 의견 유형(오탐 의심·재검토 필요 등) + 사유 기록, 정정은 `REVOKE_PREVIOUS` 새 기록으로만 가능합니다.
- 주의: 기록 시 연결된 Core에 실제 append-only 레코드가 추가됩니다. 운영자의 안전 검토 행위를 뜻하지 않습니다.

### 증거 응답 조회 — Core API 반환 확인

![증거 응답 조회 화면](design/review-console/captures/03-evidence-verified.png)

- 사건 참조 ID와 Core `/evidence`·`/objects` API 응답이 맞는지 조회·대조하는 화면입니다.
- SHA-256·manifest 기준 검증 결과를 표시하며, 파일 내용·출처의 육안 확인을 대신하지 않습니다.

</details>

## 기술적 특징

- **계약 우선 설계**: JSON Schema 13종 + OpenAPI 40여 개 오퍼레이션으로 Edge/RC/Core 경계를 고정하고, Python·C++·Kotlin이 동일 계약으로 검증됩니다.
- **결정적 재생 하네스**: 장비 없이 `임무 적용 → 오프라인 기록 → 재시작 복원 → 업로드 → 조회` 전 구간과 장애(E01~E12: ACK 유실·중복·역순·중단재개·손상정정·교차조직 차단 등)를 재현합니다.
- **3축 CI**: 모든 `main` 푸시마다 Python(contracts·core·harness) + Edge C++ 빌드/테스트 + Android 단위테스트·벤치 APK를 검증합니다.
- **무결성 보장**: 멱등 ingest·digest 검증·감사 로그·교차 조직 접근 시 존재 여부 비노출(404) 원칙.

## 저장소 구성

| 경로 | 내용 |
|---|---|
| `contracts/` | JSON Schema·OpenAPI·계약 정책 (경계의 기준본) |
| `libs/ksi_contracts/` | canonical digest·도메인 검증 공용 패키지 |
| `edge/` | 기체 탑재 관측 코어 (C++) |
| `android-rc/` | RC 앱 (MSDK 셸·로컬 저장·업로드) |
| `core/` | 서버 API + 비행 검토 웹 콘솔 |
| `harness/` | 재생·시뮬레이터·장애 주입·E2E 시험 |
| `design/` | 화면 캡처·설계 기록 (포트폴리오 이미지 출처) |
| `docs/` | ADR·계획·검증 기록 |

## 빠른 시작 (개발용)

```bash
bash scripts/setup_env.sh        # .venv 생성 + 패키지 설치
source .venv/bin/activate
bash scripts/run_all_tests.sh    # Python 테스트 전체 (contracts·libs·core·harness)

# Core 서버 실행 예시
KSI_DB_URL="sqlite:///$(pwd)/core/var/ksi_core.db" \
KSI_OBJECT_ROOT="$(pwd)/core/var/objects" \
  python -m uvicorn ksi_core.app:create_app --factory --port 8000
```

- 검토 콘솔: `GET /review` (정적 화면, 조회는 기존 읽기 API만 호출; A-2 의견 기록은 별도 개발·검증용 쓰기 경로이며 기록 시 서버 데이터가 실제로 변경됩니다)
- 운영 배포 전 인증·권한·HTTPS는 별도 보안 게이트가 필요합니다.

## 문의

- **주식회사 케이에스아이 (KSI Corporation)**
- **전화: 043-288-4860**
- 제품 도입·시연·기술 문의는 전화로 연락해 주세요.
