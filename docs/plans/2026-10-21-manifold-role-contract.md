# 매니폴드(Manifold 3 · KSI Safety Edge) 역할·책임·기능 계약 — 2026-10-21

상태: **정본.** 이 문서는 "매니폴드가 무엇을 하는 곳인가"를 고정한다.

> **배포 가림:** 단말 식별자·사내망/USB-LAN 주소·현장망 상세를 `[REDACTED: ...]`로 가렸다. 의미·판정은 유지된다.
2026-10-23 사용자 승인 개정: 관측·자동 평가·판정불가·증거화가 기본이고, 능동 재관측은 독립 선택 기능이다.
현재 추가하는 카메라 제어기는 모의 포트만 사용하는 코어 코드다. 실제 PSDK 자동 조작과 ACTIVE 허용은 별도 검증 전 금지다.
현행 개발 범위는 `2026-10-23-autonomous-observation-stage1.md`와 `2026-10-23-active-reobservation-stage2.md`를 따른다.
코드·계약을 바꾸려면 이 문서를 먼저 고치고, 코드 차이를 붙여 제시한다.

- 상위: `docs/product-state.md` · `docs/specs/2026-10-08-integrated-design.md`
- 짝이 되는 문서: `docs/plans/2026-10-21-edge-operation-contract.md`(RC→Edge **제어 경로**),
  `docs/plans/2026-10-21-screen-contract.md`(RC 화면), `docs/plans/2026-10-20-product-gap-analysis.md` §10
- 근거 코드: `edge/`(Edge 코어 C++), `edge/psdk-app/`(Manifold에서 도는 PSDK 앱),
  `android-rc/`(RC), `core/`(Core 서버)
- 상태 표기: **[H]** 호스트 검증됨 · **[D]** 구현됐으나 실기 미검증 · **[P]** 계획(미구현)

---

## 1. 역할 (Role) — 한 문장

> **매니폴드는 "비행체에서 떨어져 나온 유일한 관측·분석·증거 생성 노드"이다.**
> 기체의 위치·고도·경로를 제어하지 않고, 화면을 내지 않으며 현장 전체의 안전을 보장하지 않는다.
> **관측·품질 기반 평가와 판정불가 처리·원본 증거화를 자동 수행한다.**
> SHADOW 후보 평가 완료를 확정 Incident·위험 Alert·사람의 확인으로 바꾸지 않는다.

이 세 단어가 배제 responsibilities(§3)에서 하나씩、实现이 §4가 된다.

## 2. 3자 역할 분담 (누가 무엇의 정본인가)

| 축 | RC Plus 2 (KSI 앱) | **매니폴드 (Edge)** | Core (서버) |
|---|---|---|---|
| 기체 명령·조작 | **정본** (MSDK) | ✗ 관여하지 않음 | ✗ |
| 관측 입력(영상·자세) | 중계 | **수집·처리의 원천** | ✗ |
| 위험 후보·자동 평가 | 감독·예외 대응 | **정본 산출** (SHADOW·판정불가·근거) | 보관·사후 검토 |
| 안전 확정 판정 | ✗ (조종자 판단) | ✗ | ✗ (보관·대조만) |
| 실행 상태 정본 | 표시 | **정본** (`MISSION_STATUS` 6종) | ✗ |
| 증거 원본 생성 | 요청·수신 | **정본 생성** | ✗ |
| 증거 영속 보존 | 전송·ACK | 예비 스풀(링크 단절 대비) | **정본 보존** (`COMMITTED`) |
| 현장/구역 정의 | 입력·요청 | **적용** | 정본 저장 |
| 보고·통계·검토 | ✗ (비교·보고 안 함) | ✗ | **정본** |
| 오탐 표시·조치 이력 | 표시·기록 | ✗ | 정본 관리 |

**요약**: RC는 *감독하고 필요할 때 개입한다*. 매니폴드는 *관측·평가·증거화한다*. Core는 *보존하고 대조한다*.

## 3. 책임 (Responsibility) —了什么를 책임지고, 무엇을 책임지지 않는가

### 3-1. 매니폴드가 책임지는 것 (이것을 못 하면 실패다)

| # | 책임 | 근거 코드 |
|---|---|---|
| R1 | **실제 입력만으로** 관측을 만든다. 추측·합성·재구성 관측 금지 | `perception/pipeline.cpp`, `capture/frame_queue.cpp` |
| R2 | **탐지 안 함과 탐지 결과 없음을 구분**해 보고한다 | `perception/detector.hpp`(`NOT_RUN` / `INFERENCE_FAILURE` / `OK`+빈 목록) |
| R3 | 시간 정합의 **등급을 낮게 유지**한다. 수신 근접 ≠ 촬영 시각 정합 | `time/matching.cpp`(`kReceiveNearest` / `kSourceVerified`) |
| R4 | 위험 후보는 **SHADOW**로만 낸다. ACTIVE는 명시적 승인 + `METRIC_VERIFIED` 없이는 불출력 | `risk/engine.cpp`(ACTIVE 가드) |
| R5 | **명시 시작 없이는 분석하지 않는다.** 적용(MISSION_APPLY) + 시작(SESSION_START) 없이는 게이트 닫힘 | `psdk-app/src/main.cpp` 게이트, `runtime/session_runner.cpp` |
| R6 | 링크가 끊겨도 **로컬 관측을 계속**한다(통신 ≠ 입력) | `transport/durable_spool.cpp`, 계약 §4 |
| R7 | 증거를 **원본 그대로** 만들고, 보존 성공을 세션 결과와 분리해 보고한다 | `transport/mop_evidence.cpp`, `mop_tick_evidence.cpp` |
| R8 | 실행 상태를 **6종 어휘로** 보고한다. `running` bool만으로 "분석 중"이라 말하지 않는다 | `runtime/session_runner.hpp`(`RuntimeState` 6종) |
| R9 | **재부팅 후 과거 `RUNNING`을 복원하지 않는다.** 기록은 보존한다 | 계약 §4-4, `psdk-app` 게이트 |

### 3-2. 매니폴드가 책임지지 않는 것 (이것을 하지 않는 것이 정상이다)

| # | 비책임 | 왜 | 대신 누가 |
|---|---|---|---|
| N1 | **비행 제어·순찰 변경** | AI의 위치·고도·경로 변경과 순찰 중단/취소는 제외. 선택적 관측 카메라 조절은 별도 허용/검증 경계이며 현재 모의 포트만 구현 | DJI·조종자 |
| N2 | **미승인 위험 확정·경고 발화** | 자동 평가·SHADOW 에피소드는 허용되지만 Incident/Alert는 별도 정책·품질·규칙 승인 없이는 생성/발송하지 않음 | 검증·승인된 정책, 사람의 현장 조치 |
| N3 | **조종 화면·UI** | 매니폴드엔 화면이 없다 | RC |
| N4 | **보고서·통계·비교** | 데이터가 아니라 판단·보존의 노드다 | Core 웹 |
| N5 | **현장·구역 수집** | 구역은 RC가 Core에서 받아 매니폴드에 **적용**한다 | Core → RC → Edge |
| N6 | **자동 분석 시작** | 인자 없는 DPK 기본은 MOP 대기 서비스다 | RC의 START 명령 |
| N7 | **모델·엔진 자동 설치/다운로드** | 실행 시점에 모델을 받지 않는다. 배포 입력으로만 설치된다 | 배포 담당(수동) |
| N8 | **Core로 직접 전송** | 현장 인터넷 없음 전제. 전송은 RC가 한다 | RC |
| N9 | **기체 연결성 판단의 정본** | 기체 연결은 DJI/RC의 사실 | RC(MSDK) |
| N10 | **ACTIVE 승격** | 승인 게이트 없이는 SHADOW가 정본 | 사용자 승인(별도 게이트) |

### 3-3. 책임 경계가 애매할 때의 판정 규칙 (이 규칙이 문서의 목적다)

1. **누가 그 사실의 근원(source of truth)인가?** — Edge가 직접 본 것만 Edge의 책임이다.
   (영상·자세는 Edge가 본다 → Edge 책임. 기체 전원은 RC/PSDK → RC 책임.)
2. **그 판단을 누가 되돌릴 수 있는가?** — 되돌릴 수 없는 판단(안전 확정, 기체 명령)은 Edge의 책임이 아니다.
3. **그 사실을 잃었을 때 무엇이 남는가?** — 아무것도 남지 않는다면 그것은 보관해야 하는 사실이 아니라
   **관측이지 않은 것**이다(0건 ≠ 안전).
4. **이 판단을 사람이 곧바로 믿을 근거가 있는가?** — 없다면 Edge는 판단을 내지 않고 **근거**만 낸다.
5. 규칙 4가 어긋나면: Edge는 SHADOW로 낮추거나, 근거를 붙이거나, 판정을 하지 않는다. 셋 중 하나.

## 4. 기능 (Functions) — 책임의 구현 단위

| # | 기능 | 입력 | 출력 | 실패/저하 의미 | 상태 |
|---|---|---|---|---|---|
| F1 | 영상 입력 수집 | PSDK 라이브뷰 RGB | `BoundedFrameQueue`(bounded, 콜백 안전) | 큐 포화 = 프레임 유실 → 관측 공백으로 보고 | **[H]** |
| F2 | 시간 출처·정합 | 기구 자세/위치 링, 수신시각 | 매칭 등급(`RECEIVE_NEAREST`/`SOURCE_VERIFIED`), 보간 포즈 | 등급 하강 = 공간 판정 불가 | **[H]** |
| F3 | 검출(감지) | 전처리된 RGB 640×640 | `Detection` + 모델 id/digest | `NOT_RUN`(미시도) / `INFERENCE_FAILURE`(실패) / `OK`+빈 목록(정상적 무검출) | **[H]** (D-FINE 실추론은 **[D]**: 엔진 배포 필요) |
| F4 | 추적 | 검출 | `TrackRecord`(OBSERVED/MISSED/HELD) | 궤적 소실은 관측 공백과 구분 | **[H]** |
| F5 | 공간 추정 | 검증된 포즈 + 박스 | `GEO_ESTIMATE`(추정치) / `WITHHELD`(사유 포함) | 임의 추정 금지, 사유를 남긴다 | **[H]** |
| F6 | 위험 후보 산출 | 지상 관측 + 구역 + 규칙 | `PROXIMITY_CANDIDATE` / `ZONE_CANDIDATE` (SHADOW) | 구역 형상 없음 = `zone_geometry_unavailable` | **[H]** |
| F7 | 세션 관리 | 후보 이벤트 | 에피소드 상태·종료 사유, 세션 결과 | 종료 사유가 결과의 근거 | **[H]** |
| F8 | 설정 적용 | MISSION_APPLY 번들 | `effective_config_hash`, `config_applied` | 적용 거부 = `policy_install_rejected`, 분석 미시작 | **[H]** |
| F9 | 실행 상태 보고 | 러너 내부 상태 | `MISSION_STATUS` + 8개 선택 필드 | 6종 어휘로만 | **[H]** |
| F10 | MOP 운용 서비스 | RC의 MOP 명령 | 관측·사건·증거 발신, ACK, HEARTBEAT | 요청했는데 채널 실패 = **게이트 유지 + 실패 보고** (자동 분석 금지) | **[D]** |
| F11 | 증거 생성·전송 | 틱 기록 | `EVIDENCE_TRANSFER`, `ARTIFACT_ANNOUNCE/CHUNK`, `FLIGHT_SUMMARY` | 전송 실패 = 스풀에 남김, 재시도 | **[D]** |
| F12 | 내구 스풀 | 발신 대기 메시지 | `<state_dir>/spool.log` (append-only, fsync) | fsync 실패 전에 정상 보고 금지 | **[H]** |
| F13 | 실행 증거 기록 | 매 틱 | NDJSON(`meta`/`frame`/`stat`) | 증거가 없으면 재생 불가 — 별도 표기 | **[D]** |

**F10~F13이 실机的 경로다.** F1~F9·F12는 호스트에서 검증됐지만, **RC↔Manifold 실왕복은
아직 한 번도 없다**(매니폴드가 현재 연결되지 않음 — §7).

## 4-1. 배치와 통신 경로 (구조는 확정 — 매체·중계·검증 상태 명시)

사용자 제시 구조(2026-10-21):

```text
RC Plus 2
 └─ KSI Android 앱 (MSDK)
       │
       │ DJI 무선 링크
       ▼
Matrice 4D
       │
       │ PSDK 연결
       ▼
Manifold 3
 └─ KSI Edge (PSDK)
```

**이 구조는 맞다.** 우리 코드와 DJI의 지원 방식이 일치한다:

| 우리 코드 | DJI 측 | 의미 |
|---|---|---|
| `MsdkDroneLink`: `PipelineManager.connectPipeline(id=0, PipelineDeviceType.PAYLOAD, STABLE)` | MSDK MOP 파이프라인 | `PAYLOAD` = **기체 짝짓 보드(Manifold)** 대상 |
| `mop_service.cpp`: `DjiMopChannel_Init/Create(TRANS_RELIABLE)/Bind(0)` | PSDK MOP 채널 | **같은 채널 id 0** 양쪽이 짝을 이룬다 |

### 다듬은 구조 (매체·중계 역할·데이터 경로)

```text
[RC Plus 2] KSI Android 앱 (MSDK)
   ├─ MSDK: 기체 명령·영상 요청 ────────[DJI 무선 링크]──────▶ [Matrice 4D]
   └─ MOP 채널 0 (PAYLOAD) ────────────[DJI 무선 → 기체 중계]──┐
                                                                 │  │
   ◀──(MOP 응답: 관측·상태·증거·심사 결과)───────────────────────┘  │
                                                                    │
[Manifold 3] KSI Edge (PSDK)                                      │
   ├─ PSDK: 영상(liveview)·자세/위치 ──[USB: l4tbr0/rndis0]──▶ [Matrice 4D]
   └─ MOP 채널 0 (DjiMopChannel_Bind(0)) ◀──────────────────────────┘
```

**세 가지 구분을 지킨다.**

1. **Matrice 4D는 처리 노드가 아니라 중계(relay)다.** 우리 Edge는 기체와 대화하지 않는다.
   기체는 MOP 채널을 RC↔Manifold 사이로 넘겨준다. 기체 연결성 판단의 정본도 RC/PSDK다(N9).
2. **관측 데이터는 MOP 경로가 아니다.** 영상·자세는 **기체 → Manifold(USB/PSDK)** 로 간다.
   MOP는 **RC↔Edge의 제어·보고 채널**이다(명령·상태·관측·증거 보고).
3. **각 구간의 매체가 다르다.** 무선(DJI 무선) / USB(l4tbr0·rndis0) / DJI MOP(기체 중계).

### 구간별 현재 상태 (2026-10-21 실측)

| 구간 | 매체 | 상태 |
|---|---|---|
| RC ↔ 기체 | DJI 무선 링크 | ❌ 미검증 (우리 패키지 DJI 키 부재 → MSDK 미초기화) |
| 기체 ↔ Manifold | **USB** (`l4tbr0` [REDACTED: USB-LAN /24], `rndis0` [REDACTED: USB-LAN /24]) | ❌ H1-1 실측 **NO-CARRIER**(기체 미접속) |
| RC ↔ Manifold (MOP) | DJI MOP 채널 0, **기체 중계 경유** | ❌ **한 번도 없음** — 계약 계층만 호스트 검증(§7-1) |
| RC ↔ Manifold (대역) | TCP / 사내 LAN [REDACTED: private /24] | ✅ **가능**(RC wlan0 [REDACTED: RC LAN address], Manifold eth0 [REDACTED: manifold LAN address] — 같은 /24 실측) |

### 가장 큰 미검증 가정

> **"MSDK MOP 파이프라인(PAYLOAD)이 DJI 무선을 통해 기체를 거쳐 PSDK MOP 채널로 도달한다."**

이것이 DJI가 지원하는 방식이지만 **우리가 한 번도 확인하지 않았다.** 실기 게이트 1순위다.

실패하면(대안, 코드 준비 상태):
- **대안 A — LAN 대역**: Manifold를 현장 WiFi/사내망에 붙이고 RC와 **TCP**로 직접.
  `MopByteTransport`가 전송 계층 seam이라 `MopService`(DJI 채널)만 소켓으로 바꾸면 된다.
  psdk-app은 `MopService : MopByteTransport`이므로 변경 범위가 작다.
- **대안 B — USB 직접**: Manifold를 RC에 USB로 물리면 RC에 RNDIS 인터페이스가 생긴다.

**지금 즉시 가능한 실연결**: Manifold를 **전원 ON + 사내 LAN에 연결**하면(기체 없이) RC↔Edge를
TCP로 붙일 수 있다. DJI 무선 중계 검증이 남지만, **우리 MOP 계약과 RC/Edge 양쪽 코드**는 실기기에서
굴러간다. RC는 지금 사내 WiFi([REDACTED: RC LAN address])에 있고 Manifold eth0([REDACTED: manifold LAN address])와 같은 /24다.

## 5. 인터페이스 계약

### 5-1. RC ↔ 매니폴드 (DJI MOP 채널 0)

| 방향 | 메시지 | 뜻 |
|---|---|---|
| RC→Edge | `MISSION_PREPARE` / `MISSION_APPLY` | 지시·설치(구역·규칙·모델 바인딩) |
| RC→Edge | `SESSION_START` / `SESSION_STOP` | 분석 시작/중지(중지가 서비스를 종료하지 않음) |
| RC→Edge | `MISSION_STATUS_QUERY` | 상태 조회 |
| Edge→RC | `MISSION_PREPARED` / `MISSION_APPLIED` / `MISSION_APPLY_REJECTED` | 준비·적용·거부 |
| Edge→RC | `MISSION_STATUS` | 실행 상태 정본(6종 + 8개 선택 필드) |
| Edge→RC | `OBSERVATION` / `DETECTION_EVENT` | 관측·사건 |
| Edge→RC | `PROXIMITY_CANDIDATE` / `ZONE_CANDIDATE` | 위험 후보(SHADOW) |
| Edge→RC | `EVIDENCE_TRANSFER` / `ARTIFACT_ANNOUNCE` / `ARTIFACT_CHUNK` | 증거 원본 |
| Edge→RC | `FLIGHT_SUMMARY` / `HEARTBEAT` / `ACK` | 마감 요약·생존·확인 |

채널·프레이밍은 `contracts/mop/` 계약이 정본이다(Edge·RC 양쪽이 같은 규칙).

### 5-2. 매니폴드 ↔ Core

**직접 연결하지 않는다.** Edge → RC(MOP) → Core(업로드). Edge는 현장에 인터넷이 있다고 가정하지 않는다.

### 5-3. 매니폴드의 배포 입력(수동, 자동 아님)

| 입력 | 경로 | 없으면 |
|---|---|---|
| PSDK 트리 | `/open_app/psdk` | 빌드 불가 |
| DJI 자격증명 | `config/dji_sdk_app_info.h` (gitignore) | 시작 시 exit 2 (플레이스홀더 정지) |
| 추론 엔진 | `/open_app/models/dfine.engine` | 모든 틱 `NOT_RUN` — **가짜 정상 스트림 금지** |
| 모델 ID | 빌드 옵션 | 바인딩 미정 → 운영 모델명 임의 생성 금지 |

## 6. 실패·저하의 의미 규칙 (fail-safe)

1. **통신 장애 ≠ 입력 장애.** MOP가 끊겨도 영상·자세가 오면 관측은 계속된다. 통신만 끊기면 후보는
   스풀에 쌓이고, RC 재접속 뒤 재전송된다.
2. **입력 장애 ≠ 위험 없음.** 영상 소실·추론 실패는 `DEGRADED` + 사유 코드다. "0건"은
   "안전"이 아니라 "판단 근거 없음"이다(관측 0건 ≠ 탐지 0건 ≠ 안전).
3. **빈 분석 ≠ 정상.** `NOT_RUN`(미시도) / `INFERENCE_FAILURE`(실패) / `OK`+빈 목록(정상적 무검출)은
   서로 다른 사실이다. 소비자는 앞의 둘을 "물체 없음"으로 읽으면 안 된다.
4. **시간 등급을 올리지 않는다.** 수신 근접으로 정합을 주장하지 않는다. 촬영 시각이 검증되지 않으면
   공간 판정은 보류(`WITHHELD`)된다.
5. **ACTIVE는 승격되지 않는다.** 명시적 승인 + `METRIC_VERIFIED` 없이는 SHADOW다.
6. **보존 성공 ≠ 세션 성공.** 증거가 `COMMITTED`여도 세션 결과는 별개다(보존은 성공, 분석은 실패 가능).
7. **재부팅 후 과거 RUNNING 복원 금지.** `runtime_epoch`가 바뀌고 재검증 후 새 상태를 보고한다.
8. **요청했는데 MOP가 실패하면 자동으로 분석에 들어가지 않는다.** 게이트 유지 + 실패 보고.

## 7. 검증 상태 (무엇이 증명됐고, 무엇이 아직인가)

| 기능 | 호스트 검증 | 실기 검증 |
|---|---|---|
| F1~F9, F12 | ✅ `ctest` 28/28 (ASan+UBSan 포함) | ❌ 없음 |
| F10 MOP **계약 계층** | ✅ **가상 Manifold 왕복(2026-10-21)** — 아래 §7-1 | ❌ DJI MSDK 전송 계층 |
| F10 MOP **전송 계층**(MSDK↔USB) | ❌ | ❌ **한 번도 없음** |
| F11 증거 실송신 | ✅ 계약·호스트 시험 | ❌ 없음 |
| F13 실행 증거 NDJSON | ✅ | ❌ 없음 (엔진 없이 `NOT_RUN`만 가능) |
| D-FINE 실추론 | ✅ 전처리/디코드 골든 값 | ❌ 엔진 미배포 |
| 세션 실데이터 | ✅ 리플레이 경로 | ❌ 없음 |

### 7-1. 가상 Manifold(호스트 Edge 대역) — 2026-10-21

실제 Manifold가 없는 동안에도 **MOP 계약 계층**을 실행 가능한 상태로 확인하기 위해,
`edge/tools/mop_server`(**실제 C++ Edge 엔진**을 TCP로 노출)와
`scripts/mop_edge_probe.py`(가상 RC)를 만들었다. `bash scripts/mop_loopback_check.sh`로 돌린다.

확인된 것(왕복 1회):
`MISSION_PREPARE → MISSION_PREPARED`, `MISSION_APPLY → MISSION_APPLIED`,
`SESSION_START → ACK`, `MISSION_STATUS_QUERY → MISSION_STATUS`,
`SESSION_STOP → ACK`, 그리고 **상태 8개 선택 필드**가 실제 값으로 도착
(`runtime_state=DEGRADED`, `config_applied=True`, `effective_config_hash=…`,
`processed_frame_count=0`, `input_state={video:absent, telemetry:absent, engine:absent}`,
`degraded_reasons=[video_input_lost, telemetry_absent, engine_absent]`, `running=True`,
`application_id`, `edge_boot_id`).

**아직 증명되지 않은 것**(정직):
- DJI MSDK 전송 계층(RC→MSDK→USB→Manifold). 바이트 포맷만 같은 **대역**이다.
- **Android RC 앱의 MOP 클라이언트**가 이 서버에 붙는 것(아직 링크가 없다).
- 관측·사건·증거 실송신(F11), D-FINE 실추론, 세션 실데이터.

부수 확인: **재전송은 같은 `msg_id`로 온다**(계약대로). 수신측은 `msg_id`로 중복 제거해야 한다.
호스트 대역은 카메라·엔진이 없으므로 입력을 `absent`로 보고하고 `processed_frame_count=0`을
보낸다 — "가짜 정상 스트림"을 만들지 않는다(§6-2).

**매니폴드 실기 접근은 현재 막혀 있다**: [REDACTED: manifold LAN address]이 RC·호스트 양쪽에서 ARP 실패(무응답).
같은 [REDACTED: private /24] 사내망이므로 **전원·배선** 문제로 보인다. RC의 USB [REDACTED: USB device]는
**매니폴드가 아니다**(인터페이스 전부 class 0xff, 네트워크 인터페이스 0개, 더미 시리얼 — RC 내부 장치).
해결: 전원 ON + LAN 배선(eth0 정적 [REDACTED: private IPv4 /24]) 또는 RC에 USB 연결(RNDIS).
또한 2026-10-08 H1-1에서 확인된 **장치 시계 −6일·RTC 1970·NTP inactive** 미정비 문제가 남아 있다.

## 8. 다음 단계 (이 계약이 정한 다음)

0. (가능) **RC 앱에 TCP 링크를 붙여** 가상 Manifold와 실결합한다 — DJI MSDK 없이도
   RC 측 MOP 클라이언트(프레이밍·엔벨로프·중복제거·상태 표시)가 검증된다.
   단 **device flavor의 실경로는 MSDK 파이프라인**이므로 TCP 링크는 개발/데모용이다.
1. **매니폴드 접속 복구** → §7의 실기 검증 열을 채운다(우선순위 1).
2. **DPK 배포**: `build_dpk.sh` → `.dpk` → `dji_app_mgr` 설치 → **인자 없는 기본 실행**이
   MOP 0 채널 대기를 하는지 확인(계약상 기본값).
3. **F10/F11 실왕복**: RC→Edge 명령 1회, Edge→RC 관측·증거 수신 1회, ACK·재전송 1회.
4. **엔진 배포 + D-FINE 실추론**: `NOT_RUN`이 아닌 실제 `OK` 틱이 나오는지.
5. 그때까지 **매니폴드의 역할은 코드로 고정되어 있고 실증은 비어 있다**는 사실을 문서로 유지한다.
