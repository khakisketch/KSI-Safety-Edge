# 평가(Asessment)의 RC 전달 — 계약 확장 제안 (초안, 미승인)

- 작성: 2026-10-24 · 상태: **제안(Proposal)** — stage1 계획 §2의 "신규 버전/확장 계약과
  명시적 어댑터·검사를 먼저 제안한다" 경로다. 승인 전 구현·계약 파일 변경 없음.
- 근거: `2026-10-23-autonomous-observation-stage1.md` §2·§7, ADR-0002 후속,
  `2026-10-23-product-conformance.md` #8(봉인→수집→영수증 종단은 호스트 검증, RC 수신 미검증).

## 1. 문제

Edge는 자동 평가(Assessment)와 전후 원본 창(ContextWindow)을 봉인하고, 수집 경로로 Core
영수증까지 확인했다(호스트 종단). 그러나 **RC 저장소에는 평가가 도착하지 않는다**:

- MOP wire는 `OBSERVATION`·`DETECTION_EVENT`만 전송한다(`mop_tick_evidence.cpp` —
  `evidence_artifact_ids`는 ADR-0002대로 정직하게 빈 값).
- RC ingest는 두 타입만 받고(`EvidenceIngest`), 기록 화면의 이벤트 행은 kind·시각·품질만
  표시할 수 있다. 판정(ASSESSED/UNDETERMINED+사유)·규칙/모델 버전·전후 프레임 참조는
  **데이터 자체가 RC에 없어** 표시할 수 없다 — 발명 없이는 채울 수 없는 공백이다.

## 2. 옵션

| 옵션 | 내용 | 장점 | 비용·위험 |
|---|---|---|---|
| **A. MOP 첨가 메시지** | 새 타입 `ASSESSMENT_REPORT`(tick·assessment_id·disposition·reason_codes·rule/policy·model digest·window_id·artifact refs)를 `.vN` 신규 계약으로 추가. Edge emit + RC ingest+store + 양측 시험 | 비행 중 실시간 표시. 작은 payload. 기존 타입 불변(첨가) | 양측 동시 배포 필요. version-skew 규칙(구형 RC의 미지 타입 무시 정책) 명시 필요 |
| **B. Core 경유 리뷰 풀** | 세션 후 RC가 Core에서 평가·프레임 참조를 조회(ADR-0002 후속 "review pull") | wire 불변. 오프라인 비행 중 표시는 불가 | 새 Core 읽기 엔드포인트+RC fetch+오프라인 상태 표시. 현장 즉시 검토엔 늦음 |
| **C. 혼합** | A=평가 사실(실시간), B=프레임 바이트(사후 풀) | 각 축에 맞는 운반책. 프레임 대용량이 wire를 누르지 않음 | 두 경로 모두 구현·시험 |

## 3. 권고

**C(혼합) — 1단계로 A만.** 평가 사실은 작고(≤数KB) 실시간 가치가 크다(기록 화면·감독 패널이
즉시 판정·사유를 표시). 프레임 바이트는 봉인 revision 경로(수집→Core)가 이미 검증돼 있으므로
wire로 옮기지 않고, 사후 리뷰 풀(B)을 별도 단위로 둔다.

- 계약: `contracts/schemas/observation-assessment-report.v1.schema.json` 신규(첨가,
  기존 불변) + `contracts/mop` 확장은 **버전 정책에 따라 .vN 파일 추가** 방식.
  `payload_sha256`·canonical JSON 규칙 준수.
- Edge: `AutonomousProcessor`의 assessment artifact를 MOP emit에 연결(봉인과 무관하게
  전송 — 봉인 실패 시에도 평가 사실 전송은 별도 축). emitting은 SHADOW 표기 유지.
- RC: ingest에 타입 추가(미지 타입은 기존 "unsupported" 유지 — 스키마 버전 협상은
  envelope `schema_version`으로), store 신규 파일(예: `assessments.ndjson`), 기록 뷰어
  이벤트 행에 판정·사유·버전 표시 + 전후 참조는 artifact refs로만(바이트 없으면 미수신).
- 정직 규칙: SHADOW 평가는 위험 확정·경보 아님. 평가 미도착=미확인(0건 아님).
  전후 프레임 참조와 바이트 보유는 별개 표시.

## 4. 착수 전 필요한 결정 (사용자)

1. A안 신규 MOP 타입 추가 승인(계약 .vN 첨가 — 기존 파일 불변).
2. 전송 범위: 모든 평가 vs 트리거 평가만(용량·빈도 상한 — 실측 뒤 확정).
3. B안(사후 리뷰 풀) 별도 단위 승인 여부.

승인되면 구현 단위는: 계약 스키마+예제+양측 어댑터 시험 → Edge emit → RC ingest/store →
웹·네이티브 뷰어 표시 → 호스트 종단(fixtures) 재검증.
