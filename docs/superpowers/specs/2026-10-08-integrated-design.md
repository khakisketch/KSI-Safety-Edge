# KSI Safety Edge — 통합 제품·시스템 설계 및 개발 준비 초안

- 작성일: 2026-10-08
- 상태: **승인·실행 중 — 사용자 지시("모두 개발 개선 진행", 선구현·후검증)에 따라 M0/M1 슬라이스 구현 진행. 이 문서는 설계 기준본이며 구현 진행은 `plans/` 원장에서 추적한다.**
- 대상: `/home/ksi/Desktop/KSI-Projects/KSI-Safety-Edge`
- 원본 패키지: `KSI_Safety_Edge_v1.1_Spatial_Design/` — 이하 `P/`
- 문서 목적: 기존 요구사항·설계의 근거와 공백을 정리하고, 전체 흐름 우선 개발을 위한 제품 계약·모듈 경계·검증 기준을 제안한다.
- 이 문서는 제품 구현 완료, 실기 검증, 운영 전환 또는 상세 구현 계획 승인 기록이 아니다.
- 이 파일은 제품 저장소 `docs/superpowers/specs/`의 기준본이다. 원본 패키지는 출처 snapshot으로 보존하며 수정하지 않는다.

## 1. 합의한 목적과 현재 상태

### 1.1 이번 대화에서 확인한 결정

| 구분 | 확인 내용 | 의미 |
|---|---|---|
| 사용자 요청 | 현재 폴더의 요구사항·PRD를 분석하고 체계적인 설계와 개발 개선 준비 | 조사·설계 준비 범위 |
| 첫 성과 | **전체 흐름 우선** | 모의·재생 데이터로 임무 적용→RC 오프라인 저장→서버 업로드→증거 조회를 먼저 연결; 장비 가용성 검증 병행 |
| 장비 상태 | Matrice 4·Manifold 3·RC Plus 2 전체 장비 시험 가능 | 사용자 진술. 이 세션에서 연결·모델·버전·설치 상태를 직접 확인한 것은 아님 |
| 설계 방향 | Edge / RC / Core / 공유 계약·재생 시험으로 분리 | 공통 적용 단위, 적용 이력, 독립적인 데이터 상태, 기존 Observation v1 보존을 포함한 초안 작성 승인 |
| 문서 작성 | 현재 계획 폴더에 Markdown 통합 설계 초안 작성 | 작성 후 문서 검토가 남아 있음. 프로젝트 반영·구현은 Build 전환 후 해당 승인 범위에서 진행 |

### 1.2 우리가 만들 제품

**인터넷이 없어도 현장에서 사람·중장비를 관측하고 증거를 보존하며, 연결 복구 후 당시 판단 근거를 재현할 수 있는 드론 기반 안전 관측 제품.**

- 조종사: DJI 영상·비행 상태와 AI 상태를 구분하고, 연결 없이 기록하며 필요할 때만 의견을 남긴다.
- 안전관리자: 사건뿐 아니라 당시 임무·구역·정책·모델·관측 공백과 증거를 조회한다.
- 관리자·외부 에이전트: 권한 범위 안에서 근거와 데이터 한계를 질의한다.
- 공간 판단: 사람 검출, 지면 위치 추정, 거리 관계, 위험정책 평가를 별개로 다룬다.
- 사업·제품 성공의 첫 증거: 한 번의 관측 여정이 중단·복구 후에도 같은 출처와 설정으로 조회되는 것.

### 1.3 실제 확인한 구현 수준

- 현재 폴더는 Git 저장소가 아니며, 제품 애플리케이션 소스·실행 서버·APK·DPK가 들어 있지 않다.
- `P/docs/00~20`: 설계 문서 21개. JSON Schema 8개, JSON 예제 8개, Python 검사 스크립트 2개.
- 루트와 패키지 안 Word PRD는 동일 파일이다. 루트 아키텍처 PNG도 패키지 안 PNG와 같다.
- ZIP 60개 파일과 풀린 패키지가 바이트 단위로 일치한다. manifest에 열거된 58개 파일의 해시가 모두 일치한다.
- `QUALITY_CHECKS.md`와 `MANIFEST.sha256` 자체는 해당 manifest 검사 대상이 아니다.
- 기존 `khakisketch/ksi-platform`의 실제 코드·운영 상태는 이번 조사에서 확인하지 않았다. 패키지의 과거 조사 설명을 현재 구현 증거로 취급하지 않는다.

## 2. 근거, 우선순위와 조사 재현성

### 2.1 요구사항의 권위

1. 이번 사용자의 명시적 결정.
2. 검토·승인된 제품 설계와 향후 개별 의사결정.
3. 원본 패키지에서는 `01_MASTER_PRD.md`와 `14~19`가 v1.1 변경 범위의 우선 설명.
4. `02~13`은 기존 세부 설계로 활용하되 변경분과 대조.
5. 스키마·예제·검사 통과는 구현할 계약의 현재 상태 증거이며, 누락된 제품 의도를 대신 결정하지 않는다.

본 문서의 새로운 엔터티·상태·계약 버전은 **제안**이다. 과거 FR 번호의 내용을 추측해서 복원하거나 원본 DRAFT를 운영 승인으로 격상하지 않는다.

### 2.2 조사한 소스의 SHA256

| 파일 | SHA256 |
|---|---|
| 원본 ZIP | `8de78a5831bebafc750f39cd8d4934b4d44b2933f3fcd8815b4542d5529e77f5` |
| `P/MANIFEST.sha256` | `f00745c753fd3f491585a777fc591f014e34324adaa61db86f26adee6fdbf089` |
| `P/docs/01_MASTER_PRD.md` | `35579c623c49d874b2146eb0a854849b82fa8d744c7398dbe5e3d94215fa1c3b` |
| `P/contracts/openapi.yaml` | `aaa37222083abc01616fd410c3e45935e45343edaed2aa263f84bcb833a3eb4b` |
| Word PRD — 루트/패키지 공통 | `f47526f164c03467c15cb99eaff09e987819c75fa4af8606e45d1fd0d6bef6ca` |

### 2.3 실행한 읽기 전용 검사

작업 위치: `P/`. 필요한 Python 패키지는 이미 사용 가능했으며 새 의존성을 설치하지 않았다.

```text
python3 -B tests/validate_contracts.py
PASS: schemas=5, examples=5, negative_cases=10, api_paths=22 / exit 0

python3 -B tests/test_spatial_contracts.py
PASS: schemas/examples 3개, negative cases 3개, 예제 의미 일관성 / exit 0

sha256sum -c MANIFEST.sha256
58/58 OK / exit 0
```

Primary가 파일·검사 코드를 읽고 기본 검사 및 메모리 내 변형 검사를 실행했다. 독립 `test-runner` 세션 `ses_ee5bf9d27ffe56A4mZ9U6KLU5I`가 기본 검사·해시·문서/API 불일치를 재확인했고, 후속 단일 검사에서 아래 8개 변형의 스키마 수용을 재현했다. 제품 런타임·장비·UI 검증은 수행하지 않았다.

| 변형 입력 | 현재 JSON Schema 검증 결과 |
|---|---|
| 검증된 거리 4.2m의 구간을 `[5,3]`으로 역전 | ACCEPTED |
| `VERIFIED` 거리이지만 프레임 시간 근거가 `UNVERIFIED` | ACCEPTED |
| 요약 구간의 종료 시각 < 시작 시각 | ACCEPTED |
| 검증된 거리 쌍 수 0인데 최단 검증 거리 1m 존재 | ACCEPTED |
| Polygon 마지막 점이 첫 점과 다름 | ACCEPTED |
| `REVOKE_PREVIOUS`인데 취소 대상 ID 없음 | ACCEPTED |
| 한 정책 안에 같은 `rule_id` 중복 | ACCEPTED |
| RC 최종 수신 순번 > Edge 최종 생성 순번 | ACCEPTED |

이는 **스키마만으로 의미 제약을 보장하지 못함**을 뜻한다. 운영 서버의 취약점이나 실제 제품 오작동이 입증된 것은 아니다. 순서·참조 무결성 등은 도메인 검증기로 처리할 수 있으나, 현재 패키지에는 그 구현과 종합 시험이 없다.

### 2.4 작성된 초안의 자체·독립 점검

- Primary가 초안 전체를 다시 읽고 범위·내부 일관성·승인 상태·미확정 항목 표시를 점검했다.
- 같은 독립 test-runner 세션이 `python3 -B` 읽기 전용 검사로 문서의 5개 소스 해시 일치, F01~F12/E01~E12/D01~D08 표 ID의 존재·유일성, 코드 블록 짝을 확인했다. manifest도 다시 58/58 일치했다.
- 독립 문서 검토에서 구현·실기 완료 또는 작성된 문서 승인으로 오인할 중대한 모순은 발견되지 않았다. 이 점검은 사용자 설계 승인과 별개다.
- 이 문단은 점검 결과의 기록이며 제품 요구사항이나 검사 대상 원본을 변경하지 않는다.

## 3. 개발 전 해소해야 할 공백

| ID | 발견 내용·근거 | 제품 영향 | 준비 단계의 조치 |
|---|---|---|---|
| F01 | `01_MASTER_PRD.md:53`은 이전 FR-001~006, 008~019, 021~024를 참조하지만 해당 전체 정의가 패키지에 없음 | 전체 범위·검수 누락 가능 | 이전 자료를 확보하거나 확인 가능한 내용을 신규 추적표로 통합. 미복원 항목은 출처 미확보로 표시 |
| F02 | `08_TEST_AND_ACCEPTANCE.md` G0~G9와 `01/18` G0~G5의 같은 번호가 다른 뜻. `18`에는 별도 G5 절도 없음 | 서로 다른 검증을 같은 PASS로 오해 | 이름 기반 검증 ID로 정규화하고 이전 번호 매핑 유지 |
| F03 | `05`의 POST flights, GET review-jobs가 OpenAPI에 없음. `20`의 `/v1/...`과 OpenAPI 기본 경로도 다름 | 클라이언트·서버 개발 계약 불일치 | 하나의 base path와 작업 목록, typed 요청·응답을 확정 |
| F04 | spatial batch `items`와 risk-policy `policy`가 단순 object이고 신규 스키마를 참조하지 않음 | 정의된 구조를 API 입구에서 강제 못 함 | 스키마 연결과 실제 요청 검증·부정 입력 시험 추가 |
| F05 | 8개 의미 불일치 입력이 스키마를 통과 | 거리·시간·취소·순번 검증 미완성 | 스키마/도메인/참조 검증의 책임을 명시하고 부정 사례를 공용 벡터로 관리 |
| F06 | `MissionSnapshot`은 정책·캘리브레이션 등 적용 묶음을 표현하지 않음. `MissionApplication`은 설명만 있고 계약 없음 | 당시 어떤 조합이 적용됐는지 재현 불완전 | 별도 MissionBundle/MissionApplication 계약 제안 |
| F07 | manifest v1은 단일 mission/boot만 표시. 재부팅·비행 중 설정 변경·나중에 회수한 증거의 정형 이력 계약 부족 | 복구 후 출처/설정/완료 상태가 모호 | FlightSession·설정 적용 구간·불변 manifest 개정 모델 제안 |
| F08 | Observation telemetry 참조에 실제 표본 ID/값이 없고 별도 표본 계약도 없음. ENU 기준점·tracker epoch·공간 근거 계약 부족 | 계산을 동일 입력으로 재생하기 어려움 | 불변 표본·좌표 기준·GeometryContext와 완결된 재생 fixture 설계 |
| F09 | 해시 문자열 형식만 검사. 직렬화 규칙·해시 필드 제외·언어 간 golden vector 없음 | C++/Kotlin/Python에서 같은 내용의 해시가 다를 수 있음 | 버전 있는 canonicalization과 해시 범위를 공통 계약으로 정의 |
| F10 | `PRD_PRINT_SOURCE.md:62` 및 Word FR-020B가 `OFF`에서 잘림 | 주요 요구사항이 출력본에서 손실 | 원본 Markdown 기준 복구, 파이프 포함 표 변환 회귀검사 |
| F11 | `02`의 APPLIED ACK 유실 시 Edge/RC 합의 복구와 영속 적용 상태를 세부화할 필요 | 적용 완료를 잘못 표시하거나 다른 설정 재적용 가능 | 상태 조회·멱등 APPLY·현재 boot 확인·적용 경계 기록 |
| F12 | 수치 KPI·실제 장비/렌즈/지면 조건·모델 파일·기존 코드가 미확인 | 실기 정확도·재사용·일정 확정 불가 | 장비/모델 능력표와 별도 검증 데이터 계획 작성 |

기본 계약 검사 PASS를 이유로 F01~F12를 닫지 않는다. F01과 F12의 외부 입력은 모의·재생 계약 준비 전체를 막지는 않는다.

## 4. 범위와 제품 불변 조건

### 4.1 초기 제품의 목표 범위

- 현장·위험구역의 불변 버전과 임무 사전 준비, 오프라인 캐시·수정 충돌 처리.
- 사람과 검증된 중장비 class map에 대한 탐지·추적. `person/excavator/dump_truck`은 원본의 시험 대상이며 실제 모델 지원은 별도 확인.
- 유효한 조건에서 지면 접점·점유 영역·사람/장비 거리·구역 관계 계산; 부족한 조건은 null과 이유 기록.
- 위험정책의 OFF/SHADOW/ACTIVE 분리, SHADOW 기본값, 규칙별 실측 검증과 운영 승인.
- RC 로컬 관측·영상·증거 저장, 장애 복구, 재개 가능한 서버 업로드.
- 당시 설정과 원본 증거 조회, 관측 공백 표시, 선택적 운영자 의견, read-only API/MCP.

### 4.2 유지할 경계

1. Edge의 서버 직접 업로드 경로를 추가하지 않는다. RC가 서버 업로드의 주체다.
2. DJI 라이브 영상과 Edge 추론 프레임을 같은 프레임이라고 가정하지 않는다.
3. LLM/VLM은 비행 후 선택 검토에만 참여한다. 실시간 안전 판단·비행 제어 경로에 넣지 않는다.
4. 원본 탐지, 조종사 의견, 사후 모델 의견, 사람이 검증한 평가 라벨은 별도 계층이다.
5. 관측 유실·가림·품질 저하는 안전 확인이 아니다. `0 incidents`만으로 안전을 표현하지 않는다.
6. 기존 운영 플랫폼의 데이터·Cloud API·롤백 경로는 별도 대조와 승인 없이 변경하지 않는다.
7. 자동 비행 제어, 개인 식별, 동적 붐/버킷 3D 위험범위, 모든 프레임 영구 저장은 초기 범위 밖이다.

### 4.3 단계 구분

- 첫 개발 단위: 실제 거리 정확도를 주장하지 않는 재생 기반 전체 흐름.
- 후속 초기 제품: 실제 탐지·RC 기록·업로드 전체 흐름 + 공간관계 SHADOW.
- 운영 경고: 별도 물리 검증과 규칙별 승인 후.
- 후속 후보: PPE 최적화, SD 원본 회수, DGX 사후 VLM, 장비 동적 작업범위 등은 각 범위와 근거를 다시 확정.

## 5. 통합 요구사항 추적표 — 초안

아래 `REQ-*`는 이번 통합을 위한 신규 추적 ID다. 정의가 없는 이전 FR 번호를 대체했다고 주장하지 않는다. 원본 출처와 수용 기준을 먼저 연결하며, 모든 항목의 제품 구현 상태는 이 폴더 기준 **미구현/미확인**이다.

| ID | 요구사항 | 원본 근거 | 소유 | 합격 증거 |
|---|---|---|---|---|
| REQ-01 | 오프라인 현장·임무와 zone revision 사용 | 00 B03/B10, 04 §4 | RC/Core | 네트워크 없이 캐시 사용, 최신 서버판과 당시판 구분 |
| REQ-02 | 임무·정책·모델의 원자적 적용과 ACK | 01 §6, 02 §4, FR-028 | Edge/RC | 부분 전송·ACK 유실·재부팅 중 잘못된 READY 0 |
| REQ-03 | 영상·센서 표본의 시각/출처 보존 | FR-025, 15 §1~3 | Edge | 원표본 기반 재생, 캐시 읽기를 신규 측정으로 오인 0 |
| REQ-04 | 사람/중장비 탐지·추적과 class map | FR-007-R1, 16 | Edge/AI | 클래스별 평가, ID-switch·가림·reset 계측 |
| REQ-05 | 거리와 지면/카메라 품질 추정 | FR-020A, FR-025 | Geometry | golden vectors, 독립 실측, 유효하지 않으면 null |
| REQ-06 | 구역 내부/외부/경계불확실 관계 | 01 P0-4, 17 §4 | Geometry/Domain | 당시 revision, 경계 오차 포함 판정 |
| REQ-07 | SHADOW와 ACTIVE 및 episode 분리 | FR-020B, FR-029, 17 | Edge/Domain | 미승인 Active 0, CREATE 중복 0, 유실 종료 구별 |
| REQ-08 | 리소스 저하와 관측 공백 계측 | FR-026, 16 §E | Edge/RC | 과열/입력 drop/AI 장애의 구간과 이유 |
| REQ-09 | MOP 재전송·영속 ACK·순번 복구 | FR-029, 02 §4 | Edge/RC | 중복·역순·단절 주입, 복구 또는 명시적 gap |
| REQ-10 | RC 영상/증거 보관과 crash recovery | 04 §5~6 | RC | 강제종료·용량부족·segment 손상 후 상태 보존 |
| REQ-11 | 해시 검증·재개 업로드·완료 확인 | 05 §4, 08 T21~25 | RC/Core | blob/DB 부분 실패에서 잘못된 완료·GC 0 |
| REQ-12 | 원본·의견·검증 라벨 분리 | 06 §5, 07 | Core/RC | 오탐 표시·취소로 원본 값/해시 변경 0 |
| REQ-13 | 증거와 당시 공간/설정 조회 | 05 §6, 20 | Core/Agent | 원출처 참조 및 공백/미업로드 상태 포함 |
| REQ-14 | 조직·현장·기기 권한과 오프라인 권한 | 04 §9, 11 §1 | Core/RC | 타 조직 접근 거부, 만료·분실 시나리오 |
| REQ-15 | 배포 산출물·모델·설정 추적과 복원 | 11 §6, 13 | 운영/QA | 소스·APK·DPK·모델·schema digest 대응 |
| REQ-16 | 승인 조건을 독립 물리 증거에 결부 | FR-027, 18 | QA/제품책임자 | 환경별 GT·오차·holdout과 규칙별 승인 기록 |

상세 요구사항에는 정상 흐름, 예외 흐름, 입력/출력, 권한, 오류 코드, 데이터 보존, 검증 ID를 붙인다. 오래된 요구사항의 원문 확보/재확인 없이 “전체 FR 추적 완료”로 표시하지 않는다.

## 6. 시스템 설계

### 6.1 책임과 인터페이스

| 영역 | 책임 모듈 | 주요 입력 → 출력 | 고장 격리 |
|---|---|---|---|
| Edge Capture | SDK adapter, timebase, typed rings | 영상/FC/camera → 불변 표본 | 수집 실패를 geometry·AI 품질에 전파 |
| Edge Perception | detector, tracker, resource governor | 프레임/모델 → scoped tracks | 선택 PPE/분석 빈도 축소, 공백 계측 |
| Edge Spatial | geometry, relation, policy, episode | 표본/적용 설정 → 관계·평가·사건 | 유효하지 않은 공간 판단 보류 |
| Edge Delivery | bounded spool, MOP adapter | 원본 기록 → RC 전달/재전송 | 직접 서버 대체 전송 없음 |
| RC Domain | mission cache, application coordinator | 사용 임무 → 적용 확인 | DJI 링크/Edge 링크/서버 상태 독립 |
| RC Data | local DB/WAL, evidence recorder, uploader | MOP/영상 → 영속 기록/서버 전송 | 비행 기록과 업로드 실패 분리 |
| Core | identity/site/config/ingest/evidence/query | RC 업로드 → 불변 증거·업무 조회 | GPU·배치 없이 핵심 기능 수행 |
| Test Harness | replay producer, fake transport, fault injector | 고정 fixture → 정답/장애 시나리오 | SDK adapter 없이도 도메인 재현 |

### 6.2 배치 형태 제안

- Core는 초기 **모듈형 단일 백엔드**로 구성한다. API·도메인·저장 어댑터를 분리하되 초기부터 마이크로서비스로 분산하지 않는다.
- C/C++ Edge, Kotlin Android RC, Python Core는 원본 패키지의 후보 구조를 따른다. 실제 도구/버전은 기존 자산 대조 후 고정한다.
- PostgreSQL과 S3-compatible Object Storage는 제안된 저장 경계다. 운영 인스턴스·bucket·인증정보는 현재 미확정이다.
- RC UI와 비즈니스 상태·저장·동기화 엔진을 분리하여, UI 완성 전에도 재생 시험을 할 수 있게 한다.
- Fake transport와 실제 MOP transport는 같은 메시지 계약을 사용한다. 테스트용 경로가 별도 제품 업로드 경로가 되지 않게 한다.
- 최초 개발 구조 후보: `contracts/`, `edge/`, `android-rc/`, `core/`, `test-harness/`, `tests/`, `docs/`. 현재 생성하지 않는다.

### 6.3 전체 흐름

```text
현장/구역/정책 준비 → RC 오프라인 캐시
  → MissionBundle PREPARE → Edge 검증 → APPLY → 적용 이력/ACK
  → 실제 또는 재생 영상·표본 → 탐지/추적 → 공간관계/정책 평가
  → 원본 관측·사건·증거 manifest → MOP → RC 영속 저장 → ACK
  → 비행 종료/업로드 조건 충족 → Core 업로드·객체 무결성 검증
  → 불변 manifest commit → RC readback 확인
  → 사용자/API/MCP에서 당시 설정·원본·품질·의견 조회
```

DJI 원영상은 별도 경로다. 서버 장애나 배치 분석 대기는 이미 허용된 오프라인 관측·로컬 기록을 중단시키지 않는다.

## 7. 데이터와 계약 설계

### 7.1 원본 보존과 추가 계약

`Observation v1`은 묵시적으로 수정하지 않는다. 새로운 근거는 별도 버전 계약과 참조로 연결한다. API가 허용하는 스키마 버전과 migration/adapter를 명시한다.

| 계약 후보 | 필수 책임 |
|---|---|
| MissionBundle v1 | MissionSnapshot·RiskPolicy revisions·모델 profile·캘리브레이션/지면 참조·보관 정책을 묶고 전체 digest로 적용 |
| MissionApplication v1 | bundle hash, flight/source session, 적용 대상 boot, effective sequence, 적용 결과/제외 사유 |
| FlightSession v1 | 한 flight 안의 여러 Edge 실행 세션, tracker/stream epoch, 시작·종료·연속성 단절 |
| TelemetrySample / FrameContext v1 | 실제 표본 값·단위·좌표계·표본 ID·시각 근거·프레임 크기·stream ID |
| GeometryContext v1 | ENU 기준점/수직 기준·pose 표본 참조·calibration/terrain revision·계산 버전·오차 근거 |
| SpatialRelation / ZoneRelation | 사람↔장비 거리와 객체↔구역 관계를 각각 표현. 검증 등급·불확실·보류 사유 포함 |
| RiskEvaluation / EpisodeEvent | 사용 policy/rule, 관측 근거, SHADOW/ACTIVE, 상태 전이와 종료 이유 |
| RuleActivationGrant | 승인한 규칙·모델/기체/렌즈/환경 범위·검증 증거·승인 주체·유효성 |
| EvidenceLink / CoverageWindow | event/observation/artifact의 연결과 관측·영상·AI 공백 |
| FlightUploadManifest v2 후보 | 복수 source session/적용 구간, 필수/선택 자료, 불변 manifest 개정과 이전 개정 참조 |

모든 계약을 첫 단계에 완전 구현하지 않는다. 첫 단계는 적용/기록/업로드/조회에 필요한 최소 계약을 구체화하고, 공간·승인 계약은 경계와 테스트 입력을 먼저 정한다.

### 7.2 식별자·시간·정밀도

- `flight_id`는 비행 전체, `edge_boot_id`는 원본 생성 실행 세션, `camera_stream_id/tracker_epoch`는 프레임·track 유효 범위다.
- 보관된 옛 사건을 새 연결에서 재전송해도 **원본 생성 boot/시각/ID를 바꾸지 않는다**. 현재 전송 연결의 ID는 별도다.
- 프레임 시퀀스, 전송 채널 시퀀스, 정책 적용 경계는 의미가 다르므로 같은 `seq`로 대체하지 않는다.
- boot가 다른 monotonic 값은 직접 비교하지 않는다. 서버 수신 UTC는 촬영 시각이 아니다.
- 새 계약은 uint64/ns의 언어 간 정밀도 보존 방식을 명시한다. decimal string 방식이 후보이며, 기존 v1 정수는 lossless decode·원본 보존이 필요하다.
- offline zone/site ID는 서버 canonical ID의 별칭 매핑으로 연결한다. 서버 ID 할당을 위해 이미 적용한 snapshot 내용·해시를 다시 쓰지 않는다.

### 7.3 해시와 공통 검증

- 해시 문서에는 대상 필드, 자기 digest 필드 제외 규칙, UTF-8·숫자 직렬화·배열 순서·null/누락 구별을 명시한다.
- RFC 8785 기반 정규화는 새 설정 계약의 후보이며 언어별 지원과 숫자 제약을 검증한 후 확정한다. 원본 v1 payload는 원본 bytes/hash와 의미 검증을 분리한다.
- 예제의 반복 `aaaa.../bbbb...` 해시는 형식 예시다. 실제 무결성 fixture는 내용에서 계산한 정답 hash를 가진다.
- Schema validator: 자료형·필수값·enum·조건부 필드.
- Domain validator: 거리 하한≤대표값≤상한, 시간 시작≤종료, Polygon 유효성, 취소 대상 존재, 중복 rule ID, 범위·수량 관계.
- Reference validator: tenant/site/flight/boot/track/observation/evidence/설정 참조의 소유·일치·존재 확인.
- Runtime guard: 현재 센서 품질·승인 범위·적용 이력·장치 상태에 따른 ACTIVE 허용 여부. `mode=ACTIVE` 문자열만으로 허용하지 않는다.

### 7.4 세 가지 완료 상태

| 축 | 설명 | 예 |
|---|---|---|
| capture completeness | 실제 촬영·관측 자료의 완결성 | 관측 완결 / 영상·AI 공백 / 알 수 없음 |
| transfer completeness | 확정된 업로드 manifest 대비 서버 저장 | 로컬 전용 / 전송 중 / 일부 저장 / 영속 commit |
| analysis quality/state | 수집 자료의 분석 여부와 신뢰 | 미분석 / SHADOW / 품질 저하 / 검증된 공간 근거 |

AI가 중단된 구간을 기록한 비행도 **존재하는 자료의 업로드는 완료**될 수 있다. 이때 관측 공백은 유지된다. SD 원본이 선택 자료라면 미회수 상태를 표시하되 필수 manifest 완료와 구별한다.

## 8. 임무·MOP·오프라인 복구

### 8.1 설정 적용 상태기계

```text
DISCONNECTED → LINKED → PREPARING → PREPARED → APPLYING → APPLIED → RUNNING
                                           ↘ REJECTED / STATUS_UNKNOWN
```

- PREPARE는 지원 버전·모델·zone·정책·해시·크기를 검사한다. 실패 시 기존 적용 설정을 훼손하지 않는다.
- APPLY는 명령 ID와 bundle digest를 이용해 멱등 처리한다. 실제 적용 완료는 원자적으로 기록한다.
- APPLIED 응답 유실 시 RC는 `STATUS_UNKNOWN`으로 표시하고 현재 boot/bundle을 조회한다. “보냈음”을 READY로 표시하지 않는다.
- 비행 중 변경은 새 적용 이력과 시작 순번을 만들고 원본 사건의 당시 설정을 보존한다.
- 새 Edge 실행 세션에서는 이전 ACK를 그대로 신뢰하지 않는다. 실제 적용 상태 확인·재적용 후 분석 준비 상태로 복귀한다.

### 8.2 영속 전송 계약

- 제어·ACK·health, 사건 metadata, snapshot/chunk의 우선순위와 큐를 분리한다.
- RC의 메시지 ACK는 DB/WAL의 영속 기록 이후에만 보낸다. 파일 증거의 ACK는 bytes 검증과 파일/DB 연결이 복구 가능한 상태가 된 후 보낸다.
- `last_received` 하나로 중간 누락을 숨기지 않는다. 채널/원본 세션별 연속 영속 ACK watermark와 gap ranges를 사용한다.
- 같은 ID·같은 hash는 멱등 성공, 같은 ID·다른 hash는 충돌이다. 재전송된 원본 provenance는 그대로 유지한다.
- MOP 자체 reliable 전달과 애플리케이션의 영속 저장 ACK를 구별한다.
- 최대 메시지/청크 크기, inflight window, 재시도, timeout, 압축 여부는 장비 측정과 메모리 예산을 근거로 고정한다.

### 8.3 유한 저장공간의 실패 정책

- Edge spool은 상한이 있다. snapshot 부하를 먼저 낮추고 metadata 및 손실 이력 공간을 예약한다.
- 이미 영속 ACK된 자료는 정책에 따라 정리한다. 미ACK 자료를 무손실이라고 약속하지 않는다.
- 실제 공간 고갈로 보존 불가능하면 명시적인 gap/health 상태와 원본 범위 정보를 남긴다. spool 상한 밖의 장애는 bounded test의 무손실 주장 대상이 아니다.
- RC는 비행 전 예상 용량을 확인하고, 비행 중 부족 시 기록 저하·공백을 표시한다. 미업로드 자료를 정상 자동 GC로 삭제하지 않는다.
- 프록시 파일의 임시 기록→확정·해시→manifest 연결 순서와 crash 복구를 시험한다. 720p/15fps/1~2Mbps/5분 분할은 원본의 실험 출발값이며 확정 성능이 아니다.

## 9. 서버 ingest·API·저장 설계

### 9.1 일관된 API 표면

- 제안 base path: `/api/v1/edge`로 통일하고 `20`의 예시 경로를 매핑한다. 기존 제품 라우트와 실제 충돌 검사는 별도다.
- 현장/zone revision, mission bundle/application, flight/session, ingest, observation/spatial/episode/evidence, annotation, coverage의 typed 계약을 연결한다.
- POST flights와 flight 목록을 제공할지, ingest가 등록을 겸할지 하나로 결정한다. 본 초안은 명시적 flight 등록·조회 목록을 추천한다.
- 선택적 review-job API는 후속 범위로 구분한다. 문서에 있는 API를 OpenAPI에 누락시키거나 P0 완료에 혼합하지 않는다.
- 목록 pagination/filter, not-found/권한/버전/충돌/해시 오류, 재시도 가능성, request ID를 공통 응답 규칙으로 정의한다.
- OpenAPI 검증은 경로 수 세기에서 확장해 외부 참조 해소·요청/응답 schema·실제 route와의 일치까지 검사한다.

### 9.2 영속 업로드 완료

1. RC가 불변 manifest 개정을 확정하고 서버 ingest session을 연다.
2. 서버가 권한·flight/config 참조와 expected artifacts를 검사한다.
3. RC가 누락 metadata와 객체/chunk를 전송한다. 서버는 기존 저장 상태를 반환하여 재개한다.
4. 서버는 실제 객체의 크기·SHA256을 검증한다. multipart ETag나 검증 없는 HEAD만으로 내용 일치를 주장하지 않는다.
5. 필수 metadata/객체 참조와 검증 결과를 확인한 후 DB에서 manifest 개정의 commit을 고정한다.
6. 응답 유실은 같은 ingest/manifest로 조회·재시도한다. DB와 Object Storage는 분산 원자 트랜잭션이라고 가정하지 않고 staging·재조정으로 복구한다.
7. RC는 commit ID·manifest hash·필수 자료 완료를 확인해 전송 완료 처리한다. 로컬 삭제는 별도 보존 정책을 만족할 때만 한다.

한 ingest session의 manifest는 바꾸지 않는다. 뒤늦은 SD 원본·증거는 같은 flight에 새 불변 manifest 개정/부속 자료로 연결하며, 이전 commit과 원본 의미를 덮어쓰지 않는다. 이 변경을 v1의 같은 flight/다른 manifest 충돌 규칙에 몰래 끼워 넣지 않고 버전 정책으로 명시한다.

### 9.3 권한·보관·조회

- 모든 metadata/객체 참조에 tenant/site 소유권 검사를 적용한다. 요청 `org_id`만 신뢰하지 않는다.
- 최초 등록·현장 권한 동기화와 오프라인 사용은 다른 단계다. 권한 유효기간·만료 중 비행의 처리 정책은 실제 운영 결정이 필요하다.
- 운영자 annotation 취소도 append-only 기록이다. 취소 대상의 event·작성 권한·선행 기록을 확인한다.
- 원본의 불변성은 일반 업무 수정 금지를 뜻하며, 보존 만료·승인된 삭제·legal hold는 별도 운영 절차다.
- 백업/복원은 DB만이 아니라 객체와 참조 일치를 포함한다. 보존 기간·복구 시간·저장 용량 목표는 현장 규모를 확보한 뒤 확정한다.
- Agent 응답은 증거 ID, 적용 설정, 완결성, 품질, 미관측 정보를 포함한다. 단순 건수만으로 안전을 단정하지 않는다.

## 10. 인식·공간·위험정책 설계

- 공통 detector+tracker를 기본 후보로 삼고, 기존 모델을 실제 Manifold 런타임·class map·정량화 정확도로 평가한다.
- 하나의 영상 수집/프레임 경로를 공유한다. SDK의 native decoded image 경로와 직접 decode 경로 중 실측 적합한 하나를 선택한다.
- 검출 점수와 geometry 품질을 분리한다. bbox 중심 간 픽셀 거리를 실제 장비 표면거리로 표현하지 않는다.
- 발점/장비 footprint가 가려지거나 지면·렌즈·시간 근거가 부족하면 거리값과 이유를 명확히 구별한다.
- 공간 SHADOW의 초기 지면 모델은 측정 가능한 평탄 현장을 추천한다. 경사·복층·DSM 지원은 운영 현장 조건을 확인해 별도 범위를 정한다.
- 동일 기체 자세·시간 오차가 두 객체에 미치는 상관관계를 고려해 거리 불확실성을 평가한다. 독립 오차라고 임의 가정하거나 단순 고정 ±값을 검증 구간처럼 제시하지 않는다.
- `RiskEvaluation` 계산, SHADOW 평가 기록, 운영 Incident/Alert 발행을 분리한다.
- `UNKNOWN/OBSERVATION_LOST`는 관측 단절이며 `EXIT_CONFIRMED`와 다르다. track 재할당만으로 과거 episode를 합치지 않는다.
- ACTIVE는 승인 환경과 현재 조건이 모두 충족되어야 한다. 범위를 벗어난 렌즈/줌/모델/시간 품질에서는 보류/격하한다.
- zone과 proximity는 별도 활성화 승인이다. 임계 거리와 허용 오차는 현장 정책·실측을 근거로 정하며 전국 공통값을 하드코딩하지 않는다.

## 11. 화면 작업에 전달할 기능 계약

이 절은 화면 배치·스타일 설계가 아니라 사용자 행동과 상태의 요구사항이다. 실제 RC/웹 시각 설계·소스·렌더 검수는 연결된 디자인 작업 공간에서 수행한다. 이번에는 디자인 프로젝트나 렌더 경로를 확인·실행하지 않았다.

| 흐름 | 필수 행동·표시 | 관찰 가능한 실패 기준 |
|---|---|---|
| 임무 준비 | 현장·설정 버전, 권한/캐시/저장 상태, Edge 적용 결과 확인 | 전송만 했는데 적용 완료로 표시 |
| 비행 중 | DJI 기능 우선, Edge 상태·품질/수신 공백 구별, 선택적 의견 | AI UI가 조작을 가리거나 모든 감지에 응답 강제 |
| 비행 종료 | 사건·영상·AI 공백·로컬 보호·업로드 대기 표시 | 사건 0건을 점검 완료/안전으로 표현 |
| 동기화 | 네트워크/정책/인증 대기, 재개, 서버 commit 확인 | toast만으로 완료, 필수 파일 누락 은폐 |
| 증거 조회 | 추론 원본/RC 영상/운영자 의견/사후 의견 구별 | RC 프록시를 정확한 추론 프레임으로 표시 |
| 구역 충돌 | base revision과 분기를 확인하고 해결 | 조용한 최신 덮어쓰기, 과거 snapshot 변경 |

실제 화면 설계 전에 RC 실측 해상도·가용 공간·터치 조건과 웹 지원 viewport를 고정한다. 해당 화면의 정상/빈/오류/로딩/오프라인/키보드·포커스 상태를 렌더 리뷰 및 기능 시험에 연결한다. 필요한 소스·렌더 접근을 확인하기 전 디자인 완료를 주장하지 않는다.

## 12. 검증 체계와 첫 인수 기준

### 12.1 번호 충돌을 피하는 검증 ID — 제안

| 검증 ID | 내용 | 과거 문서 매핑 |
|---|---|---|
| V-CONTRACT | 스키마/API/의미/참조 계약과 공용 벡터 | 08 L0/L1 |
| V-CAPABILITY | 장비·SDK·영상/시간/통신/저장 실제 가용성 | 18 G0, 08 G0/G1 일부 |
| V-PERCEPTION | 탐지·추적·성능·열·가림·reset | 18 G1, 08 G2 |
| V-GEOMETRY | 독립 GT와 좌표·거리·오차 검증 | 18 G2, 08 G3 |
| V-POLICY | SHADOW 위험정책·episode 평가 | 18 G3 |
| V-E2E | RC offline→복구→서버 commit→근거 조회 | 18 G4, 08 G4~G7 |
| V-ACTIVE-ZONE / V-ACTIVE-PROXIMITY | 규칙별 환경·품질·승인 | 01 G5, 18 §7 |
| V-LEGACY | 기존 운영 경계/권위 source/롤백 보호 | 08 G9, 12 |

이름 매핑으로 이전 시험을 삭제하지 않는다. 원본 08의 T01~T30과 18의 fault matrix를 요구사항별 시험 목록에 연결한다.

### 12.2 첫 전체 흐름의 합격 조건

| ID | 시험 | 합격 조건 |
|---|---|---|
| E01 | 임무·정책 묶음 준비/적용 | 같은 bundle hash가 Edge 적용 이력과 조회 결과에 연결 |
| E02 | APPLY 응답 유실·Edge 재시작 | 잘못된 READY 0, 실제 상태 확인 후 복구 |
| E03 | 오프라인 관측·증거 저장 | 서버 없이 기록, 재시작 후 원본 ID/hash·미전송 상태 복구 |
| E04 | MOP 중복·역순·중간 누락 | 중복 row 0, gap 감지; 보유 범위에서는 재전송으로 해소 |
| E05 | 복구 불가능한 누락 | 누락 범위·이유와 관측 영향 표시, 정상 완결로 은폐 0 |
| E06 | 업로드 중단·응답 유실·재시도 | 같은 manifest/객체의 중복 commit 0, 재개 가능 |
| E07 | 필수 객체 hash 불일치/DB 또는 blob 부분 실패 | 영속 완료·미업로드 GC 0, 실패/대기 상태 유지 |
| E08 | 비행 중 설정 변경·새 Edge 실행 세션 | 각 원본 사건에 해당 적용 구간/출처 연결, 옛 사건 덮어쓰기 0 |
| E09 | 서버 최신 zone 변경 후 옛 비행 조회 | 당시 snapshot·정책·모델·증거 재현 |
| E10 | 사건 0건 + AI 공백 | 업로드 완료와 관측 불완전이 동시에 올바르게 표현 |
| E11 | annotation 등록·취소 | 원본 불변, 취소 대상·권한 검증 |
| E12 | 타 조직 조회/객체 참조 주입 | 데이터와 객체 URL 접근 거부 |

재생에서의 정확한 기대 건수·hash는 고정 fixture로 비교한다. E2E 재생 PASS와 실제 RC/기체 PASS를 별도 열로 기록한다. “무손실”은 시험한 저장/전송 상한 내의 결과로만 표현한다.

### 12.3 실제 장비 시험 패키지

1. 장비 모델·펌웨어·SDK·APK/DPK·모델 digest와 연결 경로를 기록한다. 운영 설치 변경은 별도 실행 권한 안에서 수행한다.
2. PSDK 영상/디코딩된 프레임·FC/짐벌·lens/zoom·timestamp의 가용성과 의미를 계측한다.
3. MOP의 실효 처리량·우선순위·재연결과 비행/UI 영향, RC 기록 가능 시간을 확인한다.
4. 정지 자세 신규 표본, cached read, 급회전, 렌즈 변경, 늦은 telemetry를 포함한다.
5. 이후 독립 GT·장비 footprint·실측 거리·조건별 평가 데이터를 확보한다.
6. 탐지 precision/recall·episode 오탐/미탐·거리 p95·지연/열 목표를 승인한 뒤 별도 holdout에서 검증한다. 같은 데이터로 임계치를 맞추고 합격까지 선언하지 않는다.

장비 가용성 확인과 시험 절차 준비가 실제 비행·배포 실행 승인을 의미하지 않는다. 설치/현장 시험에 필요한 구체적 접근·실행 범위를 확인한다.

## 13. 개발 단계와 의존성 — 실행 전 개략

이 절은 개발 순서 설계이며 파일별 변경·커밋·명령을 정한 상세 구현 계획이 아니다. 작성된 설계 검토 후 상세 계획으로 분해한다.

| 단계 | 성과 | 시작 의존성 | 종료 증거 |
|---|---|---|---|
| M0 설계·계약 기준선 | FR 통합, 공통 계약/상태/해시/오류/참조 규칙 | 본 문서 검토, 누락 출처의 처리 결정 | 주요 모순 해결 목록과 개발 가능한 첫 계약 |
| M1 재생 전체 흐름 | 임무→로컬 저장→업로드→조회, 장애 복구 | M0 첫 계약 | E01~E12 해당 재생 시험과 저장/readback 증거 |
| 병행 H1 장비 능력 확인 | 실제 영상·시간·MOP·RC 저장 Matrix | 시험 접근·장비/SDK 확인 | V-CAPABILITY 측정 또는 불가 사유 |
| M2 실제 탐지 전체 흐름 | 실제 Edge 탐지→RC→Core 근거 조회 | M1, H1, 대상 모델 | 실제 E2E와 탐지/열/저장 실측 |
| M3 공간 SHADOW | 지면/거리/zone 관계와 episode 평가 | 실제 표본, calibration/지면/GT | V-GEOMETRY·V-POLICY 결과와 미검증 조건 |
| M4 제한된 규칙 ACTIVE | 승인 환경 안의 zone/proximity 개별 경고 | M3 기준/holdout/운영 승인 | 각 규칙별 승인·격하·롤백 증거 |
| 후속 후보 | 선택 PPE·SD 회수·사후 VLM·확장 현장 | 유효 자료와 별도 우선순위 | 기능별 효용·비용·품질 근거 |

Core/RC의 기록·동기화 도메인은 장비 알고리즘과 독립적으로 진행할 수 있다. SDK 실제 capability가 다르면 adapter와 해당 기능 범위를 조정하되, 다른 단계까지 근거 없이 멈추거나 성공 처리하지 않는다.

첫 구현 계획은 M0의 필요한 정비와 M1에 집중한다. 전체 제품을 한 번에 구현하는 거대 계획이나 실기 전 Active 구현 완료 주장으로 확장하지 않는다.

## 14. 문서 체계와 프로젝트 반영안

Build에서 문서 검토 결과를 반영할 때의 **후보 배치**다. 현재 폴더를 Git으로 초기화하거나 아래 파일을 생성한 것은 아니다.

| 문서 | 역할 |
|---|---|
| `docs/superpowers/product-state.md` | 제품 목표·현재 단계·사용자 인수·후속 범위 요약 |
| `docs/superpowers/specs/...-integrated-design.md` | 승인된 통합 설계와 출처/변경 근거 |
| `docs/requirements/traceability.md` | FR/REQ→모듈→계약→시험→실측 증거 |
| `docs/architecture/` | 시스템 경계, 데이터/시간/기하, 적용/전송/저장 상태기계 |
| `contracts/` | 기계 계약·버전 정책·공용 golden vectors |
| `docs/verification/` | capability matrix, 실제 장비 절차, 환경별 실측/인수 기준 |
| `docs/adr/` | 중요한 선택·대안·제약·승인 근거 |
| `docs/superpowers/plans/...` | 승인 범위의 상세 구현 작업·진행·실제 검사 증거 |

- 기존 원본 패키지는 출처 snapshot으로 보존하고, 새 문서가 무엇을 대체/보완하는지 링크한다.
- 동일 PRD 본문을 수동으로 여러 벌 관리하지 않는다. Markdown 기준본에서 Word를 생성하고 FR·표·상태 문구 손실을 검사한다.
- 코드 통합 대상과 실제 저장소 경로가 확정되기 전 다른 제품 기록·브랜치·설정을 변경하지 않는다.
- UI 산출물은 별도 디자인 작업 공간의 실제 소스·렌더 증거와 연결하고, 기능 검증·사용자 인수와 구분한다.

## 15. 결정·외부 입력 등록부

| ID | 현재 상태 | 필요한 입력/추천 | 막히는 범위 |
|---|---|---|---|
| D01 | 사용자 확인 | 전체 흐름 우선 + 실제 장비 검증 병행 | 해결 |
| D02 | 사용자 진술만 확인 | 전체 장비 사용 가능. 기체 4E/4T, 실제 SDK/펌웨어/앱과 연결 위치를 확인 | 장비 실행·지원 버전 고정 |
| D03 | 원문 미확보 | v1.0 전체 FR 확보, 없으면 신규 추적표를 제품 요구사항으로 검토 | 전체 FR 복원 완료 주장 |
| D04 | 코드 미확인 | 독립 Edge 작업을 기본으로 하되 기존 모델/SDK/권한 재사용 후보를 실제 코드로 평가 | 재사용 범위·통합 비용 |
| D05 | 설계 제안 | 최초 공간 실험은 측정 가능한 평탄 현장·고정 렌즈를 추천; 대상 현장 확인 | geometry 운영 범위 |
| D06 | 운영 결정 대기 | 오프라인 권한 기간, 보존 용량/기간, 권한 역할, 현장/RC 수·비행량 | 상용 운영 설정·용량 목표 |
| D07 | 측정·승인 대기 | 위험 임계치·허용 오차·활성화 책임자, 독립 평가 데이터 | ACTIVE 허용 |
| D08 | 현재 미연결 | 디자인 프로젝트·제품 UI 소스·렌더 검수 경로 확인 | 시각적 구현/검수 |

D03~D08 전체를 한 번에 사용자에게 답하도록 요구하지 않는다. 각 입력이 해당 단계에 필요해질 때 추천안과 근거를 제시하고 결정하며, 독립적으로 가능한 작업은 진행한다.

## 16. 외부 기술 조사와 한계

2026-10-08 조회. Context7으로 DJI SDK 문서를 검색하고 아래 공식 소스의 본문을 확인했다. 버전 표기는 조회한 문서의 상태이며 실제 장비 설치 버전이 아니다.

- DJI PSDK README: https://github.com/dji-sdk/Payload-SDK/blob/master/README.md — 3.16.0 표기, Matrice 4E/4T camera status push 등 변경 설명.
- DJI PSDK Liveview header: https://github.com/dji-sdk/Payload-SDK/blob/master/psdk_lib/include/dji_liveview.h — Matrice 4 camera source enum, Manifold 3 `DjiLiveview_StartImageStream`, image width/height/frameId 확인. 해당 image-info 구조 자체에는 노출 timestamp가 없으므로 노출 시각 정확성 보장으로 해석할 수 없다.
- DJI MSDK Android README: https://github.com/dji-sdk/Mobile-SDK-Android-V5/blob/dev-sdk-main/README.md — 5.18.0 표기와 Matrice 4 Enterprise 지원 목록 확인.
- DJI MSDK 문서: https://github.com/dji-sdk/mobile-sdk-doc-v5/blob/sdk_releases/v_5.18.0/api-reference/en/android-api/Components/IMediaDataCenter/ICameraStreamManager.html — 영상 stream listener/CameraStreamManager 설명을 Context7으로 확인.

원본 패키지의 DJI Liveview/Liveview AI 튜토리얼 URL은 이번 직접 fetch에서 본문이 반환되지 않았다. 따라서 “Pilot AI 표시 기능의 특정 기체 제한”은 이번 조사에서 최신 제한까지 독립 재검증했다고 말하지 않는다. 필요한 기능별 공식 지원표와 실제 SDK/펌웨어 조합을 H1에서 확인한다.

외부 문서에 API가 존재하는 사실은 실제 카메라·렌즈·RC에서 요구 지연/동시 사용/저장 동작이 통과했다는 증거가 아니다.

## 17. 준비 완료 판정과 다음 인계

### 완료한 조사

- [x] 설계 문서 21개, PRD Word/출력 원본, 스키마 8개, 예제 8개, 검사 코드·주요 아키텍처 그림 대조.
- [x] 원본 패키지 해시·ZIP/복사본 일치 확인.
- [x] 기존 검사 재실행 및 8개 의미 제약 공백 재현, 독립 검사 확인.
- [x] 전체 흐름 우선과 장비 시험 가능 여부, 통합 설계 방향·초안 작성에 대한 사용자 응답 반영.
- [x] 시스템·데이터·복구·검증·단계별 개발 준비 초안 작성.

### 아직 완료가 아닌 것

- [ ] **이 작성된 문서에 대한 사용자 검토·승인.** 대화에서 초안 작성을 승인한 것과 구별한다.
- [ ] 누락 FR 처리, 첫 단계 상세 계약/스키마/API 및 파일별 구현 계획 확정.
- [ ] 제품 저장소·실행 도구·기존 코드/모델의 실제 대조와 프로젝트 문서 반영.
- [ ] SDK 설치·장비 입출력·실제 RC/서버 전체 흐름·공간 정확도 검증.
- [ ] 제품 UI 디자인·렌더 검수, 사용자 end-to-end 인수, 운영 배포.

**지금 사용 가능한 수준:** 원본의 문제와 개선 구조, 첫 개발 목표와 검증 기준을 검토할 수 있는 통합 설계 초안.

**다음 순서:** 작성된 설계 검토 → 첫 단계 상세 구현 계획 → Build 전환 후 승인된 프로젝트에 문서·계약 반영 및 개발. 장비/외부 입력은 영향을 받는 단계별로 확인한다.

전체 “개발 준비 완료”는 작성된 설계와 첫 단계 계약·실행계획이 검토되고 실제 작업 대상이 확인됐을 때 기록한다. 이 초안 작성만으로 해당 상태를 선언하지 않는다.
