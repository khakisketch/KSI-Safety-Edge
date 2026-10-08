# 11. 운영·보안·데이터 거버넌스

## 1. 사용자/기기 모델

- Tenant(org)→Site→WorkArea→Flight/Zone/Event, default-deny tenant separation.
- 사용자 권한 제안: `ORG_ADMIN`, `SITE_MANAGER`, `PILOT`, `REVIEWER`, `READ_ONLY_AGENT`; 현장·업무 역할은 조직 기준 최소 권한.
- RC/Manifold/aircraft device pairing: 등록/키 발급/폐기/재발급, 하드웨어 시리얼은 식별 수단이지 인증 자체가 아님.
- 비행 시 pilot local authenticated session + cached permissions; RC가 오프라인인 기간의 토큰 만료 정책은 별도 확정. 관리자 revoke 후 오프라인 캐시를 즉시 막을 수 없다는 한계를 명시.

## 2. 영상·위치·개인정보

- 사람 얼굴, 신체, 식별 가능한 차량번호, 위치·작업기록은 보호해야 할 개인정보 또는 업무정보가 될 수 있음. 적용 법령/개별 현장 계약/근로자 고지 필요성은 실제 운영 환경에서 별도 법률 검토.
- 접근 권한: 영상을 조회/다운로드할 수 있는 role, 목적, 만료 URL, 전체 조회 audit.
- 기기 저장: DB와 파일 암호화(Android Keystore 키 관리); 잠금화면, debug log 개인정보 마스킹, 임시 캐시 GC 정책.
- 서버: TLS, S3 SSE/at-rest encryption, strict signed URL TTL, bucket/list 권한·object prefix tenant validation.
- 보존/삭제: evidence 원본의 진본성 보호, 설정 가능한 retention 및 legal hold/삭제 요구 프로세스. 영구 불변은 '일반 사용자가 탐지 내용을 덮어쓰지 못함'이지 개인정보 법률상의 삭제 불가를 의미하지 않음.
- 모델 학습: 이용목적/계약/비식별·승인 없이 실무 영상 데이터를 자동 학습 재사용하지 않음.

## 3. 저장/삭제 정책

- Manifold: 순환 ring + 미전송 metadata/snapshot bounded spool. Edge ACK 후 오래된 데이터 우선 정리. 회수 불가 gap 명시.
- RC: local evidence queue/flight videos + flight manifest; `DURABLY_COMMITTED`와 정책 확인 전 미업로드 삭제 금지. 장기 영구 저장 장소 아님.
- 서버: PostgreSQL transactional immutable record + Object Storage; retention by org/site/asset class, backups, restore rehearsal, checksum audits.
- camera SD: RC proxy와 별도; 자동 회수 안 됐으면 `ORIGINAL_NOT_COLLECTED`로 상태 표현.

## 4. 관측성과 운영 대시보드

- PSDK: frame fps, input topic Hz, matching skew/uncertainty, geometry reject reasons, detection latency, buffer gaps, model profile, thermal/throttling, MOP heartbeat/retry, edge_free_bytes.
- RC: DJI link availability, MOP last event sequence/ack gap, video recorder segment/state, storage quota, queued GB and age, upload progress, Wi-Fi/4G policy status, app restart/crash.
- Core: uncommitted upload sessions, orphan blobs, SHA mismatch, partial flights, time to upload, API 401/403/500, tenant denied counts, object store availability.
- Review: queue latency, batch GPU-hours, failure rate, unsupported-claim rate, label discordance.
- Alerts: incomplete > configured hours, RC storage below threshold, missing event seq, edge sensor unavailable, retention nearly over.

## 5. 현장 장애 Runbook

| 상황 | RC의 즉시 UX | 복구 절차 |
|---|---|---|
| 인터넷 단절 | '서버 오프라인 · 로컬 기록 중' | 비행 계속, 착륙 후 Wi-Fi sync |
| DJI/RC 링크 유실 | DJI 기체 안전에 우선, AI event 수신 불가 | Pilot/DJI 절차, 재연결 후 seq reconcile |
| Manifold AI 과열/중단 | 'AI 중단 · 영상은 별도' | AI restart/reprofile, missing AI interval 보존 |
| 위치 불확실 | '위치 판단 보류' | GNSS/RTK/카메라 조건 재관측, geo Active 금지 |
| RC 저장 부족 | 출발 전 경고, 비행 중 recorder incomplete | 서버 ACK된 파일만 정리, 원본 삭제 방지 |
| 업로드 중 앱 종료 | LOCAL_PROTECTED / resume | WorkManager/foreground conditions로 재시도 |
| 서버 객체 해시 불일치 | 일부 업로드/완료 아님 | artifact 재업로드, 원본 SHA 조사 |
| 동시 위험구역 수정 | 충돌 배지/비행 당시 snapshot 유지 | 관리자 merge resolution branch 작성 |
| VLM 평가 충돌 | '사후 AI 의견' 분리 | 사람 검토/라벨 확정 전 원본 변경 X |

## 6. 배포/롤백

- Artifact SBOM/버전: PSDK app checksum, Android APK hash, Server image digest, Model weights SHA, DB migration version, schema version.
- Canary: synthetic replay→simulation→ground physical shadow→bounded field trial, per-rollout rollback point.
- 기존 `ksi-platform` rollback 보호: Cloud API path, explicit `TELEMETRY_SOURCE_MODE`, no Active promotion; 새로운 실험은 default feature off/별도 namespace.
- DB migration reversible/canary migration, Object Storage lifecycle 변경은 double-check.
- privacy/security incident 대응: credentials rotate, RC revoke, signed URL revoke/reissue, audit export, evidence hold.

## 7. 법/운영 한계

드론 비행승인/촬영승인·현장 출입, 산업안전/개인정보 의무 준수는 별도 운영 프로세스다. KSI Safety의 AI/비행 영상만으로 법적 의무 이행 또는 현장의 안전성을 보장한다고 표현하지 않는다.
