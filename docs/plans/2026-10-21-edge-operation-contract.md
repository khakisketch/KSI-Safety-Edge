# Edge 작업 운용 계약 — RC 요청이 실제 Manifold 실행을 바꾸는 경로 (2026-10-21)

상태: **사용자 확정(2026-10-21) 결정의 구현 계약.** 실장비 검증 전 완료를 주장하지 않는다.
2026-10-23 개정: 자동 관측·평가·원본 증거화의 완료에 매건 운영자 응답을 요구하지 않는다.
선택적 능동 카메라 제어 권한과 AI 관측 실행은 독립이다. 현재 카메라 코어는 모의 포트만 허용한다.
관측/평가/추가 관측/증거/Incident/Alert와 로컬/RC/Core 보존을 합치지 않는다. MOP 동결 바이트는 변경하지 않는다.

### MOP 스트림 핀 결정 (사용자 승인 2026-10-29)

`contracts/mop/edge-to-rc.mopv1.hex`의 HEAD(482653a) 바이트는 과거 수신 호환성 핀으로 보존한다.
동일 PREPARE→APPLY→STATUS_QUERY→START 시나리오의 현 C++ producer 전체 프레임 핀은
`edge/tests/fixtures/edge-to-rc-current.mopv1.hex`이며 `mop_e2e`가 전체 바이트와 프레임·digest를
검사한다. 과거 핀도 digest와 현대 Kotlin 수신·저장 경로로 검사한다. 두 스트림은 START 전 상태의
`running` 및 그 payload digest와 파생 프레임 길이만 다르다. `contracts/mop/README.md`의 현재 C++
producer가 기존 핀과 전체 동일하다는 역사적 기술은 이 사례에 더 이상 적용되지 않는다. 실제 START/STOP은
별도 회귀 시험으로 검증하며 bool만으로 실행 확인이나 정상 마감을 주장하지 않는다. 이것은 MOP v2,
혼합 배포 전체 호환성 또는 실기 승인이나 검증을 뜻하지 않는다.

- 상위: `docs/product-state.md` · `docs/plans/2026-10-20-product-gap-analysis.md` §10 ·
  `docs/specs/2026-10-08-integrated-design.md` §8 · `docs/plans/2026-10-17-rc-operations-screen-contract.md`
- 근거 사용자 결정(2026-10-21): ① 첫 위험 = **사람–중장비 접근**, 초기 판정 = 지정 작업구역 침입
  ② 링크 단절 시 **Manifold 로컬 관측 계속** ③ `COMMITTED` = **증거의 영속 보존 기준**,
  세션 결과는 보존 성공과 분리.

## 1. 책임과 경로 (확정)

```text
RC Plus 2 KSI 앱 ──MSDK MOP──▶ 기체↔RC 링크 ──PSDK MOP──▶ Manifold 3 KSI Edge
   ├ 기체·카메라 명령(MSDK)              └ 분석·공간·위험 평가 → 관측·상태 응답
   └ 관측 임무 설정·시작·중지·확인
```

- RC는 **요청과 확인**을 한다. 실행 상태의 정본은 **Manifold**다.
- 분석은 Manifold, 비행 안정화·보호는 DJI, 보존·검토는 Core. AI가 기체를 명령하지 않는다.
- 현장 인터넷·Edge→Core 직접 전송을 전제로 바꾸지 않는다.
- 설치 후 인자 없는 기본 실행은 **MOP 0 서비스 대기·기본 종료기한 없음·자동 분석 없음**이다.
  APPLY와 명시 START 전에는 분석하지 않고, SESSION_STOP은 분석을 중지할 뿐 서비스를 종료하지 않는다.
  `--standalone` 진단은 명시적으로 선택하며 유한 시간이다. 실제 자격증명·엔진 경로/모델 ID는
  신뢰된 초기 배포 입력이고, RC가 임의 원격 셸이나 미검증 모델을 자동 설치하는 범위가 아니다.

## 2. 런타임 상태 어휘 (Edge가 보고, RC가 표시)

| 상태 | 의미 | 입증 근거 |
|---|---|---|
| `REQUEST_ACCEPTED` | 명령 식별자·형식 유효 | ACK |
| `CONFIG_APPLIED` | 지정 설정 버전이 **실제 엔진에 설치**됨 | `effective_config_hash` = 설치된 설정 해시, `config_applied=true` |
| `STARTING` | 기동 중, 아직 처리 프레임 없음 | `processed_frame_count` 0 |
| `RUNNING` | 실제 프레임 처리 중 | `processed_frame_count` 증가, `last_processed_frame_age_ms` 최신, 입력 유효 |
| `DEGRADED` | 실행 중이나 입력·추론 불완전 | `degraded_reasons` + `input_state` |
| `STOPPED` | 처리 종료·근거 마감 | 명시 중지/착륙/기간 만료/공간 부족 |

`running` 기존 bool은 **“시작을 요청받았고 중지되지 않음”** 의미로 남긴다. 실행 증거가 아니다.
RC는 `running=true`만으로 “분석 중”을 말하지 않는다.

## 3. MISSION_STATUS payload 확장 (선택 필드 — 부재는 "모름")

`MISSION_STATUS`는 기존 필드를 유지하고 아래를 **선택으로** 추가한다. 부재는 `false`/`0`이 아니라 **모름**으로
해석한다(오해 소지 방지). RC→Edge 방향의 메시지·타입·프레이밍은 바뀌지 않는다.

| 필드 | 타입 | 뜻 |
|---|---|---|
| `runtime_epoch` | string | Edge 프로세스 실행 세대. RC는 재연결 후 대조에 사용 |
| `runtime_state` | string | §2 어휘 6종 |
| `config_applied` | bool | 번들 digest가 실제 엔진에 설치됨 |
| `effective_config_hash` | string\|null | 설치된 설정 해시 |
| `processed_frame_count` | int | 실행 세대 내 실제 처리 프레임 수 |
| `last_processed_frame_age_ms` | int\|null | 마지막 처리 경과 |
| `degraded_reasons` | string[] | 저하 원인 코드 |
| `input_state` | object | `video`/`telemetry`/`engine` 각 `ok\|stale\|lost\|failed\|absent` |

`degraded_reasons` 코드: `video_input_lost`, `video_input_stale`, `telemetry_absent`,
`telemetry_stale`, `engine_failed`, `zone_geometry_unavailable`, `policy_install_rejected`,
`session_capacity_exceeded`.

## 4. Edge 런타임 제어 (G9)

1. **설치**: 적용된 번들에서 `mission_snapshot.zones`와 `risk_policy.rules`를 읽어 엔진 규칙·구역으로
   설치한다. 설치 결과는 `effective_config_hash`와 `config_applied`로 보고한다.
2. **실행**: `SESSION_START` 승인 후에만 프레임을 처리한다. `SESSION_STOP` 승인 후에는 처리하지 않고
   최종 요약을 마감한다. **명시 시작 없이는 처리하지 않는다.**
3. **입력 장애 ≠ 통신 장애**: MOP 단절은 로컬 관측을 멈추지 않는다. 영상 입력 단절은 `DEGRADED`로
   처리하며 관측을 계속 시도하되 "0건"을 안전 신호로 만들지 않는다.
4. **재부팅**: 재시작 후 과거 `RUNNING`을 복원하지 않는다. `runtime_epoch`가 바뀌고, 영상 입력·추론
   엔진을 재검증한 뒤에만 새 실행 상태를 보고한다. 기록은 보존한다.
5. **ACTIVE 금지**: `risk_policy.rules[].mode == "ACTIVE"`는 설치하지 않고
   `policy_install_rejected`로 저하 처리한다. 활성 승인은 별도 게이트다.
6. **공간 부족**: 구역 규칙에 필요한 WGS84→현장 좌표 변환 입력이 없으면 구역 판정은 보류하고
   `zone_geometry_unavailable`로 알린다. **"침입 없음"으로 표시하지 않는다.** 탐지 사실은 유지한다.

## 5. RC 동작 규칙

- `ACK ≠ 실행 확인`. `CONFIG_APPLIED`과 `RUNNING`은 Edge가 보고한 값으로만 표시한다.
- 링크 단절 시: **통신 단절**과 **마지막 확인 실행 시각**을 표시하고 현재 상태를 정상으로 추정하지 않는다.
- 재연결 시: `runtime_epoch`·설정 해시·마지막 처리 지점·미전송 기록을 대조한다. 불일치면 재적용·재시작을
  요구하고, 임의로 정상화하지 않는다.
- 재전송: `msg_id`가 명령 식별자다. 동일 명령 재전송은 새 세션을 만들지 않고 **기존 상태를 보고**한다.
- 대응 기록 없음은 "대응 0건"이라는 세션 사실로 남긴다. 대응을 지어내지 않는다.

## 6. `COMMITTED`와 세션 결과 분리

- `COMMITTED` = **Core가 해당 세션의 필수 기록과 무결성을 확인하고 영속 저장을 완료한 상태.**
  업로드 시도·파일 수신만으로는 아니다.
- RC는 Core의 커밋 영수증을 **로컬에 저장한 뒤에야** 업로드 완료를 표시한다. 응답 유실 시 재전송으로
  같은 영수증을 받아 저장한다.
- 세션 결과는 별도 축이다.

  | 결과 | 뜻 |
  |---|---|
  | `SUCCESS` | 설정 적용·실행 확인·관측 기록·영상 근거가 모두 있고 결측 없음 |
  | `FAILED` | 실행이 실패했거나 필수 기록이 결측 |
  | `INTERRUPTED` | 링크·프로세스 재시작 등으로 세션이 중단 |
  | `UNKNOWN` | 판단 근거 부족 |

  **보존 성공(`COMMITTED`)은 세션 결과(`SUCCESS`)를 뜻하지 않는다.** 장애 기록까지 온전히 보존된 실패
  세션도 `COMMITTED`될 수 있고, 그 결과는 `FAILED`/`INTERRUPTED`다. L1 성공 집계는 `SUCCESS`만 센다.
- 세션 결과의 Core 노출(조회 계약)은 §9-1/§9-2 결정대로 후속이다. 이번에는 RC 로컬과 `FLIGHT_LOG`
  아티팩트로 남기고 Core가 세션 결과를 추정해 말하지 않는다.

### L1 필수 기록 묶음 (5)

| 묶음 | 내용 |
|---|---|
| 세션·설정 | 세션 ID, 기체·Edge 식별자, 번들 digest, 적용 설정 해시·버전 |
| 실행 이력 | 명령 ID, 실행 세대, 시작·중지·재시작, 상태 전이, 처리 프레임 수, 입력 장애 구간 |
| 관측 결과 | 실제 관측 구간, 탐지·판정, **0건이면 0건이라는 사실**, 결과별 품질·근거 |
| 운영자 대응 | RC 확인·보류·전달 요청과 시각, 대응 0건이면 그 사실 |
| 영상·증거 | 검증 가능한 실제 영상 기록, 필수 증거, 파일 해시·출처 |

원본 영상 전량을 MOP로 실시간 복제할 필요는 없다. RC가 생성한 영상 또는 검증된 다른 경로의 영상이
Core에 저장되어 있고 해시·출처·구간 연결이 있으면 충족한다.

## 7. 개발 단위와 인수 기준

| 단위 | 내용 | 인수 기준 |
|---|---|---|
| A (edge) | 번들→엔진 설치, 시작/중지 실제 제어, 런타임 사실 보고 | 호스트 테스트: 미적용 상태 무처리, 시작 후 처리 증가, 중지 후 무처리, 상태 6종·저하 원인·입력 상태 보고 |
| B (RC core/ports/msdk) | MISSION_STATUS 파싱, 런타임 상태 모델, 세션 결과 분류, 업로드 완료 판정 | 호스트 테스트: ACK≠실행, 상태 6종 표시값, 결측 시 결과 `FAILED`, 커밋 영수증 저장 후에만 완료 |
| C (RC app) | 준비(구역·리비전·적용)와 운용(Edge 실행 상태·입력 단절·분석 결과 없음) 표시 | 레이아웃 계약 테스트: 기체 필수 정보 비가림, 상태 6종·저하 사유 표시, 0건≠안전 문구 |
| D (harness/core) | 파이썬 하네스 정합, Core `COMMITTED`·PARTIAL 의미 불변 확인 | 기존 E01~E12·계약 테스트 green, PARTIAL은 durable commit 없음 |

## 8. 남는 경계

- L2(공간판정 정확도·거리 추정·동적 접근)와 L3(제한 경고 운용)은 범위 밖. 첫 위험의 판정 정확도를
  이 계약이 입증하지 않는다.
- 실장비: MOP 실기 통신(A9-4), 대역폭·지연·안정성, Manifold 재부팅, 기체 착륙 시 종료.
- RC는 Edge를 원격 함수 호출로 구동하지 않는다. KSI 명령을 Manifold가 검증·실행·응답한다.
- 최초 DPK 설치·기동은 RC 앱 범위 밖이다. L1은 Edge 서비스가 기동 가능한 상태를 전제로 한다.

## 9. 기존 KSI Safety Platform과의 관계 (2026-10-21 확인)
사용자가 제공한 Platform↔Edge 비교(중앙 GPU 분석 vs 기체 탑재 SHADOW)를 코드 기준으로 확인한 결과다.

### 9-1. 이 저장소에 있는 것 (확인됨)

| 항목 | 위치 | 상태 |
|---|---|---|
| Detection→Tracking→Geometry→SpatialRelation→RiskPolicy 체인 | `edge/src/perception`·`edge/src/geometry`·`edge/src/spatial`·`edge/src/risk`·`edge/src/runtime` | 존재. R1~R5 합성 Replay 통과, 실영상 미검증 |
| 규칙 운영 모드와 관측 결과의 축 분리 | `RiskRule.mode`(OFF/SHADOW/ACTIVE) · `spatial-relation.v1`(`metric_status` VERIFIED/SHADOW_ESTIMATE/UNAVAILABLE, `geometry_level` PIXEL_ONLY…METRIC_VERIFIED) | **이미 분리되어 있음.** 하나의 상태값으로 합치지 않는다 |
| 거리 불확실성 표현 | `spatial-relation.v1.distance_interval_m` + `quality_reasons` | 존재. 추정 거리 4m·오차 2~7m이면 임계 5m 확정 불가와 같은 판정이 데이터로 표현됨 |
| RC 로컬 보존 → Core 업로드, 보존≠수신 | `rc-core`·`core`(`durable_commit_id` null이면 PARTIAL) | 존재. §6의 COMMITTED 정의와 동일 원칙 |
| 실행 상태 머신 | `MissionState` + `EdgeRuntimeStatus` | **두 번째 독립 상태 머신을 만들지 않았다.** MissionState(명령 수명주기)와 EdgeRuntimeStatus(Manifold 실행 사실)를 분리 |

### 9-2. 재사용 이력과 추가 조사 경계

- 중앙 Platform의 RTMP/RTSP·MediaMTX intake, PPE 2단계 Screening→Verifying 전체 실행 경로,
  인시던트·리포트 서비스가 이 Core에 통합되어 있다는 근거는 없다. 사용자 제공 비교 문서만으로
  구현 완료나 동일 모델을 확정하지 않는다.
- 다만 **이 저장소에 재사용 코드가 없다는 이전 서술은 과도했다.**
  `docs/plans/2026-10-08-reuse-audit-and-porting.md`는 격리 감사와 `frame_queue`·PSDK 글루 등
  모듈 채택 이력을 보유한다. §0의 독립 Detection·시간 출처·공간 엔진 경계는 현행이다.
- 재사용 방법을 스키마 공유 하나로 제한하지 않는다. 이미 채택한 코드를 보존하고, 추가 모델·모듈은
  라이선스·클래스/시간 의미·의존성·품질을 확인한 뒤 격리 평가한다. 중앙 운영 코드·비행 제어·
  다른 제품의 Mission/Core 동기화 계약을 통째로 가져오지 않는다.
- 추가 교차 실행 비교에는 동일 입력·모델/전처리 버전·실제 원본 접근이 필요하다.
  정규화된 관측 계약은 공통 의미·정책·근거 계보를 맞추는 접점이지, 코드 재사용을 금지하는 규칙이 아니다.
- "Core가 기존 인시던트·리포트 기능을 유지한다"는 서술은 **본 저장소의 Core에 적용되지 않는다.**
  본 Core는 ingest·events·annotations·review 중심이며 인시던트/리포트 기능이 없다.

### 9-3. 문서에서 그대로 채택한 판단

- SHADOW는 품질 검사를 생략하는 모드가 아니라 **정식 규칙 계산을 시도하고 승격만 하지 않는 모드**다.
  근거 부족 시 SHADOW에서도 판정 미정(미보유/UNDETERMINED)으로 남겨야 한다. → §4.6의
  `zone_geometry_unavailable` 보류 규칙과 동일.
- "탐지 사실 / 공간 관계 / 위험 판정" 3단 분리는 이미 `detection-event`·`observation`·`spatial-relation`·
  `risk-policy` 스키마와 `RuleOutput`으로 분리되어 있다. **PPE의 확정 위험과 공간 관계의 확정 위험을
  같은 의미로 합치지 않는다.**
- ACTIVE 승격은 규칙별 독립 승인이다. 근접 판정 통과가 구역 침입을 자동 허용하지 않는다.

### 9-4. 이 저장소에서 새로 생기는 작업 (문서 대비)

| 항목 | 이유 | 처리 |
|---|---|---|
| 탐지·Tracking 일치 검증(중앙 vs Manifold) | 비교 대상의 실제 버전·모델·동일 입력을 특정해야 함 | 기존 재사용 감사·채택 이력을 재사용한다. 동일 입력의 교차 실행을 로컬 한쪽 시험으로 대체하지 않는다 |
| PPE 2단계 재검증 | Edge v1.1은 사람·장비 우선, PPE는 P1 | 범위 밖. Edge는 사람–중장비 접근에 집중 |
| 지면 좌표 정확도·경계 판정 측정 | 실측 GT 필요 | L2. 현장 GT 확보가 막는 입력 |
| 공간 규칙의 ACTIVE 승격 | 규칙별 검증 필요 | L3. 별도 게이트 |

## 10. 빈 데모·프레이밍 번들과 실제 현장 번들 구분

edge 번들 파서가 RC가 실제로 만드는 바이트를 파싱해 확인한 결과(교차 언어 시험 `rc_bundle`):

- `contracts/mop/rc-to-edge.mopv1.hex`의 `MISSION_PREPARE`에 실린 번들은 **프레이밍용 축약본**이다.
  `mission_snapshot` = `{site_id, mission_revision_id, content_sha256}`, `risk_policy` = `{site_id, revision_id}`.
  **구역 배열도 규칙 배열도 없다.**
- `DemoFeed.demoBundle`·`DemoSession`의 `zones:[]`·`rules:[]`는 **격리된 시뮬레이션 경로**다.
  이를 제품 전체의 프로덕션 입력이라고 한 이전 설명은 부정확했다.
- 실제 앱에는 `MissionSync.listSiteBundles/syncBundle`로 서버의 구역·규칙 있는 번들을 검증·저장하고
  선택·적용하는 경로가 이미 있다. 여기에 `MissionBundleBuilder`와 명시적인 로컬 구성 조작을 추가해
  저장된 불변 구역 리비전으로 RC_LOCAL 번들을 구성·적용하도록 했다.
- 따라서 모든 운용의 규칙 수가 0건이라고 단정하지 않는다. **실제로 선택·설치한 번들**을 근거로
  SHADOW·OFF·거부된 ACTIVE·기하 보류와 규칙 0건을 구분한다. 공간 판단의 실측 유효성은 별개다.

### 10-1. 파서의 판정

프로덕션 설치는 동결된 `mission-bundle.v1`의 필수 필드를 따른다. `zones`/`rules`는 **명시적 빈 배열**일 때만
빈 설치로 해석하고, 필수 배열 부재·잘못된 타입은 거부한다. 앞선 구현에서 프레이밍용 축약 픽스처를
받으려고 필수 배열 부재를 빈 설치로 허용한 것은 잘못된 완화였다. 전송 프레이밍 시험 자료가 런타임
계약보다 우선하지 않는다. 축약 픽스처는 전송 시험으로 보존하고, 실제 설치 적합성은 완전한 RC_LOCAL
번들로 별도 검증한다. 명시적 빈 설치와 규칙 있는 설치는 표시에서 구분한다(§10-2).

### 10-2. 표시 규칙 — 위험 규칙 0건

- RC가 보낸 규칙 수와 실제 설치 규칙 수는 같다고 단정하지 않는다. ACTIVE 거부·OFF 비활성·기하 보류를
  구분하고, 요청 번들에 대응하는 **실제 설치 해시**가 일치할 때만 설치를 확인한다. `effective_config_hash`는
  bundle digest와 다른 계산이므로 두 문자열을 직접 비교하지 않는다. 요청 수락 ACK·프로토콜 APPLIED만으로
  실제 엔진 설치·공간 판단 가능을 보증하지 않는다.
- **규칙 0건 + 적용됨 → RC는 "위험 규칙 0건 — 위험 판정 없음"으로 표시한다.**
  "설정 적용됨 · 관측 실행 중"만으로 위험 평가가 도는 것처럼 보이면 안 된다(§2의 정직 규칙과 동일 축).
- 규칙이 있어도 `ACTIVE`는 표시하지 않는다(ACTIVE 승인은 별도 게이트).
- 규칙 0건은 **오류가 아니라 상태**다. 이를 "설정 실패"로 표시하지 않는다.

### 10-3. 다음 구현 단위 (구역 지정 → 번들 → 적용)

`docs/plans/2026-10-16-field-zone-designation-contract.md`의 동선을 네이티브에서 잇는다.
지도 타일 캐시 출처는 미결(계약 §기술 방향)이므로, 우선 **기존 현장의 저장된 구역 리비전**을
번들에 담아 적용·확인하는 경로를 잇는다. 새 구역을 지도에서 그리는 편집기는 그 다음 단위다.

- 구역 리비전(불변) + 위험 규칙(SHADOW) → 번들 `mission_snapshot.zones[]`·`risk_policy.rules[]` 구성
- 번들 digest → PREPARE/APPLY → `MISSION_APPLIED` 확인 → **규칙 수·해시 표시**
- 규칙 0건 상태를 그대로 노출(§10-2)

#### 10-3-1. 로컬 번들 구성 규칙 (구현 단위 E)

| 항목 | 규칙 |
|---|---|
| 원본 | `origin=RC_LOCAL`, `created_by`는 조종자 식별자(없으면 기기 식별자) |
| 임무 스냅샷 | 저장된 `mission_snapshot`(불변)에서 가져온다. **RC가 임무를 새로 발명하지 않는다** |
| 구역 | 저장된 **불변 구역 리비전**만 참조한다. 새 리비전을 만들거나 덮어쓰지 않는다 |
| 위험 규칙 | 기본은 `ZONE_INTRUSION`(지정 작업구역)·`mode=SHADOW`. `PERSON_EQUIPMENT_PROXIMITY`는 호출자가 임계값을 명시할 때만 |
| 임계값 | RC가 임의로 정하지 않는다. 기본 `required_geometry_level`은 보정되지 않은 현장에 정직한 값을 쓴다(`METRIC_VERIFIED`가 아님) |
| ACTIVE | **생성하지 않는다.** ACTIVE 승인은 별도 게이트 |
| digest | 계약 규칙(§Digest rule)대로 JCS SHA-256. `content_sha256`·`bundle_sha256` 모두 |
| 표시 | 구성 결과의 **구역 리비전 id 수와 규칙 수**를 그대로 노출(§10-2) |

#### 10-3-2. 실행 상태의 실시간성 (구현 단위 E)

- Edge 실행 상태는 **주기적 `MISSION_STATUS_QUERY`**로 갱신한다. 응답이 왔을 때만 화면이 갱신된다.
- 상태 조회는 **명령 상태 기계를 전진시키지 않는다**(`MissionState` 불변). 조회 자체는 idempotent.
- 링크 단절은 `noteLinkLost()`로 **세션 중단 근거**가 되지만, **세션이 이미 종료된 뒤의 단절은 중단이 아니다**.

#### 10-3-3. 대응 이력의 조회 경로 (구현 단위 H)

- `operator-response.v1`을 **신규 스키마로** 추가한다(기존 스키마 불변 — `contracts/README.md` 버전 정책).
- Core는 대응 행위를 **append-only**로 보관·조회한다. **세션 판정이나 안전 상태를 Core가 추정하지 않는다.**
- RC는 이미 만든 대응 객체를 그대로 전송한다. 저장 경로(`FLIGHT_LOG` 아티팩트)와 조회 경로(`operator-response.v1`)는
  **같은 사실을 두 통로로 담는 것**이며, 어느 한쪽의 성공이 다른 쪽의 성공을 대신하지 않는다.
- **증명이 없으면 상위 전달 상태는 수락하지 않는다**: `SENT_CONFIRMED`는 `transmission_proof`,
  `RECEIVED_CONFIRMED`는 `receipt_proof`, `COMPLETED_CONFIRMED`는 `completion_proof`를 요구하며,
  없으면 스키마 검증 단계에서 거부된다.

## 11. 실행 상태 표기 규칙 (구현 단위 C·E)

- Edge 상태는 **주기적 `MISSION_STATUS_QUERY`**로만 갱신된다. 보고가 없으면 미확인.
- **규칙 0건 + 적용됨** → "위험 규칙 0건 — 위험 판정 없음(관측만 동작)". 관측이 동작해도 위험 평가는 돌지 않는다.
- **통신 단절 ≠ 입력 단절**: "Edge 통신 단절 · 마지막 확인 실행 시각 …" / "영상 입력 단절 · 관측 저하".
- **0건 관측(분석 수행됨) ≠ 분석 결과 없음**.
- 여기서 **탐지 객체/사건 0건**과 **수신된 관측 원문 0개**도 다르다. 성공한 추론 tick은 탐지 목록이
  비어 있어도 Observation을 만든다. 실행 카운터만 있고 관측 원문이 아직 없으면 결과 미수신이며,
  빈 장면으로 확정하거나 세션 SUCCESS로 분류하지 않는다. SHADOW 규칙 설치는 공간 판정 유효성 확인과도 별개다.
- **세션 결과 ≠ 보존 성공**: `COMMITTED`는 증거 영속 보존이며 `SUCCESS`와 다르다. 실패 세션도 `COMMITTED`될 수 있다.
- **업로드 완료 ≠ 안전**: 완료 표시는 커밋 영수증 저장 이후에만.

## 12. 통합 재검토 인수 보강

- START/STOP은 수신 순서를 보존한다. 동일 poll 사이에 START→STOP이면 최종 상태는 중지,
  STOP→START이면 최종 상태는 시작이다. APPLY와 START가 함께 도착해도 START를 소실하지 않는다.
- 현대 런타임 보고에서 중지 확인은 정확한 `STOPPED`만 인정한다. STARTING·CONFIG_APPLIED·미확인을
  단순히 RUNNING이 아니라는 이유로 중지 완료로 취급하지 않는다.
- MOP 단절 중에도 관측·이벤트·상태 원문을 영속 큐에 기록하고 같은 ID·출처·순번으로 재전송한다.
  RC의 영속 수신 ACK 전에는 삭제하지 않는다. 큐 용량·디스크 오류는 명시적으로 보고하며 조용히 버리지 않는다.
- 전송 큐에서 **이미 영속 ACK된 payload만** digest tombstone으로 압축할 수 있다. 같은 ID의 중복/충돌
  판별을 유지하고 미ACK 원문은 보존한다. 이는 RC 미업로드 증거나 원본 audit의 자동 삭제 정책이 아니다.
  metadata까지 용량을 초과하면 정상으로 숨기지 않고 보존 저하/실패로 처리한다.
- 실제 프로세스 재시작은 새 boot/runtime epoch를 가진다. 이전 기록의 출처는 그대로 회수하지만 실행은
  자동 복원하지 않는다. 테스트의 `Reboot()` 호출만으로 실제 프로세스 재생성을 검증했다고 하지 않는다.
- 영상이 한 장도 오지 않아도 입력 건강 상태가 갱신되어야 한다. 실제 로드한 모델의 digest가 관측 출처에
  들어가야 하며, 빈 값이나 임의 상수로 계약을 통과시키지 않는다.
- 세션 마감은 실제 STOPPED 확인 또는 사용자가 명확히 선택한 미확인 로컬 마감으로 구분한다.
  미확인 원격 세션이 남으면 새 현장·정책으로 자동 전환하지 않는다. 영상 마감·인제스트·마감 원장 기록
  이후 업로드를 예약한다. 대응 0건도 마감 메타데이터로 기록하며 가짜 대응 객체를 만들지 않는다.
- 늦은 대응은 증거 아티팩트가 이미 COMMITTED여도 자동 동기화 대상이다. 구조화 대응 경로의 영속 영수증을
  별도로 확인하며, Core 보존 ACK를 현장 전달·수신·조치 완료의 증명으로 재사용하지 않는다.
- 새 API는 `contracts/extensions/operator-responses.openapi.yaml`에 명시한다. 동결 기본 OpenAPI 바이트를
  보존하고, 생성된 API의 경로가 기본 계약과 확장 계약의 정확한 합집합인지 검사한다. 새 경로를 숨겨
  기본 계약 시험을 통과시키지 않는다.
