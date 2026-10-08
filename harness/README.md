# KSI Safety Edge — Test Harness (M1)

재생 기반 전체 흐름 검증 하네스. 실제 DJI 장비 없이 **임무 적용 → RC 오프라인 기록 → 서버 업로드 → 증거 조회**와
장애 복구를 결정적으로 재현한다.

## 구성

| 모듈 | 역할 |
|---|---|
| `clock.py` | 가상 시계(monotonic ns). 모든 시각은 결정적 |
| `mop.py` | MOP envelope(JSON) + `FaultTransport`(drop/duplicate/reorder/delay/disconnect/report_lost) + `pump()` |
| `edge_replay.py` | Edge 세션 재생: PREPARE/APPLY/STATUS, 부팅·재부팅, 메시지 방출 |
| `rc_store.py` | RC 로컬 저장(SQLite WAL). **영속 commit 후 ACK** |
| `rc_sim.py` / `rc_upload.py` | RC 시뮬레이터: 적용 합의, flight-session 합성, 업로드(재개·중단·손상 주입) |
| `core_client.py` | Core HTTP 클라이언트(OpenAPI 표면) |
| `fixtures.py` / `flight_fixtures.py` | 결정적 fixture: world/A(일반)/B(0건+AI gap)/C(중간 재설정·2회 부팅) |

## 실행

```bash
bash scripts/setup_env.sh
bash scripts/run_all_tests.sh              # 전체(contracts+libs+core+harness)
.venv/bin/python -m pytest harness/tests -q  # 하네스만
```

## E01~E12 매핑 (`harness/tests/`)

| 시나리오 | 테스트 |
|---|---|
| E01 임무 적용·digest 일치 | `test_e2e.py::test_e01_apply_digest_matches_and_application_recorded` |
| E02 APPLY ACK 유실/재합의/재부팅 | `test_e02a_dropped_apply_reply...` / `test_e02b_persistent_apply_drop...` / `test_e02c_reboot_reapply...` |
| E03 완전 오프라인·재시작 복원 | `test_e03_offline_record_restart_then_upload` |
| E04 중복·역순·지연 exactly-once | `test_e04_duplicate_reorder_delay_exactly_once` |
| E05 회복 불가 gap → manifest | `test_e05_unrecoverable_gap_reaches_manifest_and_commit` |
| E06 업로드 중단·재개·멱등 완결 | `test_e06_abort_resume_commit_and_idempotent_recomplete` |
| E07 아티팩트 손상 → PARTIAL → 정정 | `test_e2e2.py::test_e07_corrupt_artifact_partial_then_fixed_commit` |
| E08 비행 중 재설정·2회 부팅 | `test_e08_midflight_reconfig_and_second_boot` |
| E09 비행 후 zone v2 → 과거 유지 | `test_e09_zone_revision_after_flight_preserves_history` |
| E10 0건+AI gap 정직 표현 | `test_e10_zero_events_with_ai_gap_is_honest_complete` |
| E11 annotation append-only·원본 불변 | `test_e11_annotations_append_only_event_immutable` |
| E12 교차 조직 접근 차단 | `test_e12_cross_org_access_denied` |

단위: `test_mop.py`(dedup/ack/retry/gap), `test_offline_store.py`(commit-before-ACK·재시작), `test_apply.py`(적용·재부팅).

## 동작 규칙(고정)

- 수신측은 **영속 저장 후** ACK. 같은 `msg_id` 재전송은 중복 저장 없이 응답만 재생.
- 재시도 소진 시 Edge가 `HEARTBEAT.unrecoverable_gaps`로 보고 → RC가 gap을 기록 → manifest `gaps`에 포함.
- flight-session은 RC가 (flight, edge_boot_id)별로 합성(첫/마지막 메시지 시각).
- 업로드 순서: flight 등록 → applications → sessions → batches → boot별 manifest ingest(open→chunk→resume→complete).
- RC는 서버 `COMMITTED` 확인 후에만 아티팩트를 `SYNCED`로 표시(토스트 완료 금지).

## 알려진 한계·편차

- 전송은 in-proc 가상 MOP(실기 MOP 대체 아님). 실기 처리량·지연은 H1에서 검증.
- E12 편차: `tok-orgB-read`(read 전용)로 PUT 시 서버는 org 조회 전 **403**(스코프 게이트)을 반환.
  rc 스코프의 orgB 토큰(`tok-orgB-rc`)으로는 교차 org 리소스에 **404**. 존재 여부 누출 없음 — 의도된 동작으로 기록.
- 개발 중 수정된 프로토콜 버그 2건: (1) 오류 후 재시도 메시지가 dedup으로 삼켜지던 문제(processed 플래그로 재처리),
  (2) `mark_unrecoverable`이 진행 중(retry 대기) 항목까지 조기 포기하던 문제(재시도 소진 항목에만 적용).
