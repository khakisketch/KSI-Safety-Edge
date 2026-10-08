# H1 DPK 세션 실행 패키지 — `ksi-psdk-tap` 관찰 세션

- 작성: 2026-10-08 · 상태: **준비 완료, 실행은 사용자 승인·기체/RC 연결 후**
- 목적: 장치(Manifold 3)에서 tap DPK를 설치·기동해 **H1-3(FC/HARD_SYNC)·H1-4(Liveview)·H1-5(수신 기준 시간 관계)** 의 1차 raw 증거(NDJSON)를 회수한다.
- 원칙: 프로펠러 분리·비행 없음. 쓰기는 **DPK 설치·기동·정지**와 세션 출력 파일에 한정. 기존 파일·설치·서비스 변경 없음.

## 0. 준비물·사전 조건 (사용자 측)

- [ ] 기체(4E/4T) 전원 ON + RC Plus 2 전원 ON, **Manifold가 기체에 연결된 상태** 유지
  - 확인 신호: 장치에서 `ip -br link` → `l4tbr0`/`rndis0`에 carrier(현재 NO-CARRIER = 미접속)
- [ ] **프로펠러 분리** 확인 (읽기 전용 세션이지만 표준 안전 조건)
- [ ] 장치 시계 정비 가능 여부 확인 (현재 −6일·RTC 1970·NTP off; 불가하면 스큐를 세션 기록에 남기고 진행)
- [ ] **승인**: `dji_app_ctl install -i …` + `start/stop` (장치 앱 설치·기동 = 시스템 쓰기)
- [ ] (신규 DPK 재빌드 시에만 필요) `dji_sdk_app_info` 자격증명 — **이번 세션은 기존 빌드 DPK로 불필요**

## 1. 세션 자산 정보 (확인됨)

| 항목 | 값 |
|---|---|
| DPK | `/open_app/ksi-dev/dpk-staging/ksi-psdk-tap_v00.01.00.02.dpk` (Package `ksi-psdk-tap`, platform manifold3, 2026-08-12 빌드) |
| 앱 이름 | `ksi-psdk-tap` (`dji_app_ctl list/status/start/stop` 인자) |
| 무인자 기본값 | 출력 `/tmp/ksi-psdk-tap.ndjson`, 동작 60초(duration), num_samples 무제한 |
| DPK 내용 | PSDK 3.16.0 정적 링크 실행파일(라이브뷰 H264 콜백 + HARD_SYNC 400Hz + perception probe 옵션) |
| 수집 형식 | NDJSON v2: `meta`(clock anchor·capabilities·sensor_us_domain) + `sample`(frame/telemetry ep, pairing.gap_ns) + `stat`(카운터) |

## 2. 실행 절차 (세션 시 순서대로)

```bash
# 0) 접속·사전확인 (읽기)
ssh ksi-manifold 'ip -br link | grep -E "l4tbr0|rndis0"; dji_app_ctl list | head -5'

# 1) 설치 (승인 필요)
ssh ksi-manifold 'dji_app_ctl install -i /open_app/ksi-dev/dpk-staging/ksi-psdk-tap_v00.01.00.02.dpk'

# 2) 상태 확인 (Not Running 예상)
ssh ksi-manifold 'dji_app_ctl status ksi-psdk-tap'

# 3) 기동 → 60초 자동 종료 대기
ssh ksi-manifold 'dji_app_ctl start ksi-psdk-tap; sleep 75; dji_app_ctl status ksi-psdk-tap'

# 4) 출력 회수 (장치 /tmp/ksi-psdk-tap.ndjson)
scp ksi-manifold:/tmp/ksi-psdk-tap.ndjson /tmp/opencode/ksi-audit-20261008/device/SESS-<id>-tap.ndjson

# 5) 이상 시 정지 (강제 종료 필요할 때만)
ssh ksi-manifold 'dji_app_ctl stop ksi-psdk-tap'
```

- **설치 유지**가 기본(재사용). uninstall은 별도 결정 시에만.
- DPK 런타임 로그(콘솔)는 `dji_app_mgr` 계열 로그에 섞일 수 있음 — 실패 시 `/blackbox`·app_mgr 로그 회수(권한 확인).

## 3. 회수 즉시 확인할 것 (판정 전 규율)

1. `stat` 라인의 `frames_received`/`telemetry_received`/`samples_written`/`frames_dropped_no_telemetry`.
2. `meta`의 `capabilities`와 `timestamp_contract.sensor_us_domain` — **현재 tap은 sensor 미검증(unverified)이 정본**.
3. sample 수 부족(<100) 시 H1-5는 `INSUFFICIENT_SAMPLES`로 정직하게 기록(재수집 여부 결정).

## 4. 결과별 의미 (미리 정의한 분기)

| 관찰 | 의미 | 다음 |
|---|---|---|
| frames>0 + telemetry>0 + samples>0 | 라이브뷰 콜백·HARD_SYNC·짝짓기 모두 동작 (H1-3/4 raw 확보) | NDJSON 분석(수신 기준 p50/p95), H1-5 recv 등급 기록 |
| frames>0, telemetry=0 | 라이브뷰만 동작 — HARD_SYNC/기체 링크 문제 분리 필요 | 기체/RC 연결·토픽 여부 조사 |
| 둘 다 0 | PSDK init/기체 링크/activation 문제 | app 로그 회수 → 차단 사유 기록(H1-2·H1-3 BLOCKED) |
| frames>0인데 수십 초 후 중단 | 60초 duration 도달(정상) 또는 링크 단절 | `stat.stop_reason` 확인 |

**불변:** 이 세션의 결과가 좋아도 "노출 시각 검증"·"센서 정합"으로 승격하지 않는다. 센서 근거는 별도 SPIKE(perception raw clock domain 실증) 전까지 `RECEIVE_*` 등급이다.

## 5. 기록·반영

- 세션 기록: `docs/verification/templates/h1-run-record.md` 복사 → `docs/verification/records/<SESS-id>/`.
- NDJSON 원본은 저장소에 커밋하지 않는다(용량·환경정보) — 요약·해시·경로만 기록.
- 완료 후 `docs/verification/h1-capability-plan.md` 매트릭스(H1-3/4/5)와 원장 Evidence Log 갱신.
