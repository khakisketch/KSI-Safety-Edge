# A-2 — 안전관리자 오탐 의견(검토 콘솔 write) 구현 기록

2026-10-13 · 결정: **새 계약 없이 기존 동결 계약으로 구현**
상태: **완료(과거 기록)** — 아래 구현·검증은 완료됐다. 계약 사실(`operator-annotation.v1` append-only,
read/write 토큰 분리)은 현행 유지이며, 운영 인증·신원은 배포 전 보안 게이트 소관이다.

## 조사 결과 (새 계약 불필요 근거)

- `operator-annotation.v1` 스키마는 호출자 유형을 제한하지 않는다 — `actor_id`/`client_id`(uuid)로
  주체를 구분하고, `annotation_type` = FALSE_POSITIVE_SUSPECTED / NEEDS_REVIEW / REVOKE_PREVIOUS.
- 서버에 `POST /events/{eventId}/annotations`(append-only, 동일 idempotent, 취소는
  REVOKE_PREVIOUS 새 기록)와 `GET …/annotations`가 이미 존재. 권한은 기존 토큰 모델
  (rc 스코프 = write, read = 조회 전용).
- → A-2는 **콘솔 UI + 토큰 운용**만으로 가능. **서버 변경 0.**

## 구현 (검토 콘솔 `/review`)

- 사건 카드에 “오탐 의견 (append-only)” 패널:
  - 기존 주석 이력(annotation_id·유형·actor·시각·사유·supersedes) 조회
  - 작성 폼: 유형 3종 · REVOKE_PREVIOUS 선택 시 취소 대상 선택 · 사유(≤1000자)
- **작성 토큰 분리**: “작성(rc 스코프) 토큰” 필드(sessionStorage, 탭 범위) — 읽기 토큰만
  있으면 이력 조회만; 작성 시도는 403 안내로 실패(조용한 실패 없음).
- 검토자/콘솔 ID: 최초 사용 시 브라우저(localStorage)에서 uuid 생성·보관, 화면에 8자 표기.
- LAN http(비보안 컨텍스트) 대비 uuid 폴백 포함.
- 헤더 고지 갱신: 읽기/작성 토큰 구분, append-only(삭제 없음), 운영 인증은 별도 보안 게이트.

## 보존·보안

- 새 서버 엔드포인트·스키마·권한 없음. 정직 표기 원칙(사건 0건≠안전 등) 불변.
- 콘솔은 여전히 데이터·토큰·id를 정적으로 내장하지 않음(테스트로 고정).
- 운영 배포 전 사용자 인증·권한·HTTPS 게이트는 그대로(dev 토큰 모델).

## 검증

- Python **119/119** — 신규 2건: ①콘솔 작성 훅 존재+토큰 비내장 ②read 토큰 POST 403 /
  잘못된 본문 422(검증이 조회·기록보다 먼저 — 부분 기록 없음).
- 인라인 JS `node --check` 통과.
- 실사용 스모크(비행→사건→의견 기록)는 Core 실행 환경에서 가능 — 실기/운영은 게이트.

## 미구현(의도)

- 운영용 인증/권한(배포 전 보안 게이트), 검토자 신원 관리(조직 디렉터리), 주석 알림·통계.
