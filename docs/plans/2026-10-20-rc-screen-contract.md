# RC 화면 구성 & UI/UX 계약 v1

- 작성: 2026-10-20 · 상태: **개발용 화면 기준안·일부 네이티브 반영. 최신 배치의 사용자 채택·실장비 렌더·인수 미검증.**
- 기준 제품 정의(사용자 확정 2026-10-20): DJI Pilot 2 수준의 필수 비행·촬영·임무 운용 경험과 KSI 고유 AI 안전
  관측·위험 판단·현장 대응·증거 기록을 **하나의 RC 앱**에서 제공하는 통합 현장 안전 운용 시스템.
  현장 작업 흐름: `현장·임무 준비 → 비행·촬영 → AI 안전 관측 → 위험 확인·대응 → 귀환·착륙 → 기록 보존 →
  Core 동기화·사후 검토`.
- 상위: `docs/product-state.md`(제품 정의·상태 구분) · `docs/plans/2026-10-20-product-gap-analysis.md`
  (확정 방향·G1~G8·S0~S7·§9 계약 결정) · `docs/plans/2026-10-17-rc-operations-screen-contract.md`
  (B-1 행동 계약·상태 축·AT-01~06) · `docs/plans/2026-10-16-field-zone-designation-contract.md`(현장 구역 지정).
- 이 문서는 **빌드 스펙**이다. 새 제품 개념을 제안하지 않으며, `contracts/` 바이트 불변을 바꾸지 않고,
  실장비·인수 완료를 주장하지 않는다.

## 0. 공통 전제 (모든 화면)

- **RC 논리 기준(2026-10-22 밀도 보정)**: 이전 실기 관찰(`product-state.md`의 MOP 왕복 항목)에서
  창은 1920×1200px @400dpi = **768×480dp**였다. 설계 물리 px→dp는 **0.4배**이며,
  48dp 조작은 1920 물리 시안에서 **120px 이상**이다. 1920 web px를 Android dp로 1:1 복사하지 않는다.
  웹은 768×480 논리 배치와 1920×1200(2.5배 표시)을 같은 구성으로 검토한다. 960×600은 축소 미리보기다.
  이번 호스트 작업에서는 시스템 inset·Android 렌더·실제 터치를 재측정하지 않았다.
- **현재 네이티브 내비게이션(구현 사실, 채택 확정 아님):** 좌측 레일 5목적지 `관측`(btnNavObserve) · `임무`(btnNavMission) ·
  `기록`(btnNavReview) · `증거`(btnNavEvidence) · `설정`(btnNavSettings) — `activity_main.xml:29-81`.
  한 앱에서 작업을 완결하는 것이 목표이며, 이를 활성 세션 중 모든 탐색을 막으라는 뜻으로 해석하지 않는다.
  화면 이동과 기록 수명주기는 분리하고 실제 유지 근거 없이 지속을 주장하지 않는다. 전송 관리는 예외 진입점,
  영상 확대는 운용 화면 내부 조작이다. 운용 화면 우선 진입·GPS 추천은 별도 제안이며 자동 확정하지 않는다.
- **정직 규칙:** 사건 0건≠안전, 시각은 RC 수신시각(근사). 실제 데이터와 시험 입력이 섞일 수
  있는 앱에서는 출처를 구분한다 — 현재 `strings.xml:clock_unverified/top_receipt_note`,
  상단 `[SIMULATED]`(`tvTopSim`·`tvCockpitSim`). 실제 기기·운용 서버에 연결하지 않는 독립 디자인 시안은
  화면 계약 D9에 따라 배지 대신 보고서/메타데이터에 출처와 검증 한계를 기록한다.
  색 단독 구분 금지(■/▲/· + 굵은 글씨 병기 — `MainActivity.kt:renderOpsStatus`).
- **터치·시인성:** 모든 조작 타깃은 **48×48dp 이상**(높이만 검사한 결과로 폭까지 통과했다고 쓰지 않는다).
  실외 1400 nits 기준: 본문 ≥12sp, 상태값 13~14sp bold, 영상 위 오버레이 금지(영상은 `videoFrame` 전체,
  상태는 띠·데크로 분리).
- **짧은 가로 화면 공통 규칙(round45)**: 최소 터치·글자를 축소하지 않고 가변 열을 shrinking한다.
  읽기 본문·기술 식별자·지원 조작은 경계가 있는 내부 세로 스크롤/접힘으로 도달한다.
  임무의 다음 행동과 세 단계, 운용의 Edge 실행 요약은 스크롤 밖이다. 색 대비 검사는 sRGB 보조 검사이며
  1400nit 햇빛·야간·장갑 운용 적합성의 증거가 아니다.
- **파일·심볼 표기:** 아래 표의 "출처"는 실제 파일·심볼이다. "미구현"은 발명하지 않고 그대로 표기한다.

| 공통 요소 | Android view id | 출처 |
|---|---|---|
| 좌측 내비 레일 | `navRail`, `btnNavObserve/Mission/Review/Evidence/Settings` | `activity_main.xml:10-91`, `MainActivity.kt:149-155` |
| 상단 바(제목·SIMULATED·수신시각) | `appHeader`, `tvPageTitle`, `tvTopSim`, `tvTopUtc` | `activity_main.xml:99-148`, `MainActivity.kt:1030-1037` |
| 운용 책임머리(비행 조종 경계) | `tvFlightBoundary` = "DJI Pilot 2" | `activity_main.xml:473-483`, `strings.xml:cockpit_flight_boundary` |
| KSI 세션 상태기 | `RcSession`(단일 운용 상태) + 격리 `DemoSession` | `RcApp.kt:session/demoSession`, `RcSession.kt` |

---

## 1. 홈 / 현장 선택

### 1-1. 화면 목적
조종자가 **이번에 들어갈 현장을 1탭으로 열고**, 운용 중이면 **운용 화면으로 즉시 복귀**한다.
이 화면에서는 기록 시작·구역 적용·명령 전송을 하지 않는다(열기 ≠ 시작 ≠ 적용 — home-hub round39 원칙).

### 1-2. 진입·이탈과 내비게이션
| 항목 | 계약 |
|---|---|
| 진입 | 앱 기동 기본면. 활성 세션이 있으면 운용(`pageObserve`)으로 직행(현행 `MainActivity.kt:332-334`). |
| 이 화면에서 나감 | 현장 카드 `현장 열기` → 현장 준비(§2). `운용으로 복귀`(세션 중) → 운용(§3). |
| 역할·근거 | 5목적지 중 어느 것도 아님 — **진입 분기점**. 운용 중에는 이 화면을 경유하지 않고 운용에 머문다. |
| 금지 | 홈에서 세션 자동 시작·번들 자동 적용·명령 버튼 배치 금지. |

### 1-3. 구성 요소 (1차/2차/3차)
| 계층 | 요소 | Android view id | 터치·시인성 |
|---|---|---|---|
| 1차 | 운용 중 카드(세션 현장명 + `운용으로 복귀`) | 신규 필요 → `btnResumeOps`(제안) | 48dp primary, 14sp bold |
| 1차 | 최근 방문 현장 카드 + `현장 열기` | 신규 필요 → `btnOpenSite`(제안, 목록은 `RecyclerView`) | 48dp ghost, 14sp |
| 2차 | 전체 현장 링크 (`전체 현장`) | 신규 필요 → `btnAllSites`(제안) | 48dp link |
| 2차 | 기기 연결 요약 (상태 미확인 고지) | 기존 `tvDeviceInfo` 재사용 + 신규 `btnDeviceInfo` | 48dp, 13sp |
| 3차 | 현장 검색 (현장 3곳 이상 대비) | 신규 필요 → `etSiteSearch`(제안) | 48dp 입력 |

- 베이스라인: `design/rc-pilot-shell/mockups/home-hub.html` round39 비대칭 홈(H+V·V 지오메트리 유지).
  단, 목업의 현장명·구역·날짜·상태는 가상 예시이며 실제 저장소와 연결되지 않는다 — 구현은 아래 §1-4만 쓴다.

### 1-4. 데이터 출처
| 요소 | 출처 | 비고 |
|---|---|---|
| 현장 목록 | `FileRcStore.sites()` — `MainActivity.kt:602-607`의 `spSite` 소스와 동일 | 미구현: 홈용 현장 카드 어댑터(현재는 임무 화면 스피너만 있음) |
| 최근 방문 현장 | 합의(2026-10-23): 기존 `FileRcStore.sites()` 목록 + "최근 방문 없음" 표시. GNSS 근접 추정을 방문으로 쓰지 않으며, 명시적 방문 이력은 방문 정의·보존기간 확정 후 별도 판단 | 발명 금지 — 없으면 "최근 방문 없음" 표시 |
| 활성 세션 유무 | `session.activeFlight != null` — `MainActivity.kt:332` | 실 기체 비행 상태와 별도임을 함께 표시 |
| 기기 연결 요약 | `session.link` / `app.videoSource` 상태 읽기 전용 | 조회만, 제어 아님 |

### 1-5. 상태 행렬
| 상태 | 문구 | 조종자 조치 |
|---|---|---|
| 운용 중(세션 활성) | "운용 중 · {현장명} · 실제 기체 상태 아님" | `운용으로 복귀` → §3 |
| 대기(세션 없음·현장 있음) | "최근 방문 · 현재 작업 아님" | `현장 열기` → §2 |
| 현장 없음 | "현장 없음 · 먼저 동기화하세요" (현행 문구 `MainActivity.kt:805` 재사용) | 설정·동기화 안내 → §6 |
| 링크·영상 미확인 | "연결 상태 미확인 — 이 화면은 조회하지 않습니다" | 연결 안내만, 자동 재시도 없음 |

### 1-6. 조작
이 화면에 명령 없음. `현장 열기`·`운용으로 복귀`는 화면 이동일 뿐 `AircraftCommandPort`·`MissionController`
호출을 발생시키지 않는다.

### 1-7. 화면 간 데이터 흐름
홈은 **선택한 `site_id`만** 현장 준비(§2)에 넘긴다. 번들·리비전·세션은 §2에서 결정하며,
홈이 예단하지 않는다.

### 1-8. 미구현·후속 (S순서 연결)
| 항목 | 순서 |
|---|---|
| 홈 화면 자체 | S2(통합 운용 화면) 이후 — 현재 `pageMission`이 기동 분기를 겸하므로 후순위 |
| 최근 방문 기록 저장소 | S3(로컬 영속 패턴 `OperatorActionLog`와 동일 NDJSON append-only로 신설) |

---

## 2. 현장 준비 = 임무 준비·구역 (pageMission)

### 2-1. 화면 목적
조종자가 **비행 전에** `동기화 → 번들 선택 → 준비 → 적용 → 로컬 기록 시작`을 순서대로 끝내고,
**Edge 적용 상태(APPLIED 실확인)와 관측 가능 조건·저장 공간**을 확인한다.

### 2-2. 진입·이탈과 내비게이션
| 항목 | 계약 |
|---|---|
| 진입 | 홈 현장 카드, 또는 내비 `임무`. 활성 세션 중에는 읽기 전용(구역 편집·적용 잠금 — home-hub lockbox 원칙). |
| 이탈 | 준비 완료 → 운용(§3)으로 이동. 기록 시작(`btnStartFlight`) 후에는 운용이 주 화면. |
| 역할·근거 | 10-17 계약 4목적지 중 `임무`. 순서 고정: 동기화 → 번들 → 준비 → 적용 → 기록 시작(G17 주석 `MainActivity.kt:729-737`). |
| 금지 | 세션 활성 중 구역 편집·재적용 금지. 준비 생략하고 운용 진입 시 "준비 미완"을 운용 상단에 표시. |

### 2-3. 구성 요소

**round45 배치 보정**: 768×480dp에서 레일 76dp·공통 머리 48dp, 임무 패딩 8dp·열 간격 8dp를
기준으로 한다. 우측 256dp 명령부는 본문 스크롤과 분리하며 `준비` 48dp → `적용` 48dp →
`KSI 로컬 기록 시작` 56dp의 **세 행**을 항상 배치한다(간격 8dp). 비활성도 숨김이 아니라 이유를 표시한다.
현장/번들·동기화·리비전·재조정은 좌측 내부 스크롤로 탐색한다. 단계의 가시성은 준비 완료나 실제 적용을 뜻하지 않는다.
Android는 검증된 웹의 논리 수치·구조를 옮기는 구현이며 **이 배치의 Android 렌더는 미검증**이다.
`tvMissionNextAction`(신규)은 최대 2줄의 다음 행동만 표시하고(`MissionNextAction` 순수 모델),
긴 3축 사실 `tvMissionReadiness`는 `missionContentScroll`에 보존한다. 제목/다음 행동/조작 위쪽 스택이
긴 상태 설명 때문에 팽창하지 않도록 분리한다. `missionCommandDock`에는 긴 ID/전체 준비 사실을 넣지 않는다.

| 계층 | 요소 | Android view id (실존) | 터치·시인성 |
|---|---|---|---|
| 1차 | 현장 스피너 / 번들 스피너 + ID 요약 | `spSite`, `spBundle`, `tvMissionSite`, `tvMissionBundle` | 48dp 스피너 |
| 1차 | 동기화 2버튼 + 로컬반영 칩 | `btnSyncSites`, `btnSyncBundle`, `tvMissionSyncResult` | 48dp, 칩은 서버 판단 금지 문구 포함 |
| 1차 | 준비·적용 / 기록 시작 / 추가확인·관측세션 | `btnPrepare`, `btnApply`, `btnStartFlight`(56dp), `btnReconcile`, `btnStart/btnStop` | 전부 48dp+ |
| 1차 | 고정 명령부 다음 행동 | `tvMissionNextAction` | 13sp bold, 최대2줄, `mission_next_*`; 미확인을 준비 완료로 바꾸지 않음 |
| 2차 | 준비 상태 3축(동기화·번들·기록가능, 좌측 스크롤) | `tvMissionReadiness` | 13sp, `mission_readiness_*` 문구 전체 보존 |
| 2차 | 리비전·구역·기록ID·상태·시작시각 | `tvMissionRevision/Zone/FlightId/FlightState/Started` | 13sp mono |
| 3차 | 세션 경계 고지·링크 상태 | `mission_session_boundary` 고지, `tvStatus`, `tvLink` | 주의색 배경 고지 |

구역 지도·점찍기 편집 UI는 10-16 구역 계약(`field-zone-designation-contract.md`)을 따르며
**캐시/저장/적용 분리·SHADOW** 원칙 유지. 지도 캐시 없음 예시 문구(`map_placeholder`) 재사용.

### 2-4. 데이터 출처
| 요소 | 출처 |
|---|---|
| 현장·번들 목록 | `session.store.sites()` / `missionBundles()` — `MainActivity.kt:601-657` |
| 번들 digest·리비전·구역 수 | `store.missionBundle(id)` → `mission_snapshot` — `MainActivity.kt:777-825` |
| 준비·적용·재조정 결과 | `session.controller.prepare()/apply()/reconcile()` — `MainActivity.kt:341-345`, `MissionController.kt` |
| 기록 시작 | `RcApp.startFlightRecording()` — `RcApp.kt:84-91` (bundle 세그먼트값, 1~3600 벗어나면 300 fallback) |
| 링크 3독립 사실 | `session.link.state/aircraftConnected/isSimulated` + `internetStatus()` — `MainActivity.kt:854-863` |
| 구역 적용 응답 | `MissionController` 적용 결과 (APPLIED 실확인만 인정 — G2/S2 범위) |

### 2-5. 상태 행렬
| 상태 | 문구(실존 strings 재사용) | 조치 |
|---|---|---|
| 번들 없음 | "이 기기에 선택 가능한 번들이 없습니다. 현장 동기화 후 번들을 내려받으세요" | `현장 동기화` → `번들 내려받기` |
| 동기화 칩 | `미확인 · 로컬 반영` / `성공(로컬 반영) · 서버 상태 별도` / `실패 · 로컬 반영은 알 수 없음` | 서버 판단 금지 고지 병기 |
| 기록 가능 | `현재 기록 중 · 새 기록 시작 불가` / `선택 번들 없음 · 시작 버튼 비활성` / `시작 버튼 활성 · 실제 저장 결과는 별도 확인` | 버튼 활성/비활성과 1:1 |
| 세션 경계 | "세션 시작은 Edge 관측을 시작하는 동작입니다. 기체 이륙·비행 조종은 DJI Pilot 2 권한" | 운용 전 필독, 닫기 불가 고지 |
| 링크 단절 | "링크=… / 기체=미연결 …" 3독립 표시 | 기록 시작은 가능(로컬), 적용 전송은 대기열 |

### 2-6. 조작 (준비 명령 — 비행 명령 아님)
| 조작 | 포트·게이트 | 결과 문구 | 실패 시 복구 |
|---|---|---|---|
| 현장 동기화·번들 내려받기 | Core HTTP (`AndroidCoreTransport`), 업로드 게이트(`coreConfigured`) | `tvMissionSyncResult` 칩 | 실패 칩 + 재시도, 로컬 반영 불명으로 유지 |
| 준비·적용·재조정·관측세션 시작/정지 | `MissionController` (Edge 적용 경로) | `run()` 토스트: "전송됨/대기열/거부" — `MainActivity.kt:708-727` | 거부 사유 표시, 자동 재전송 없음 |
| KSI 로컬 기록 시작 | `RcApp.startFlightRecording` + `SegmentRecorderCore` | 세션 표시 + 세그먼트 번호 이어짐 | 재시작 시 A9-3 잔여 복원("녹화 재개: 재시작 이전 구간 일부는 저장되지 않았습니다") |

### 2-7. 화면 간 데이터 흐름
`site_id → bundle_id → mission_revision_id + zone_revision_ids → flight_id(session_id)`를 운용(§3)·기록(§5)에
그대로 넘긴다. `sessionId = uuid5("session|flight|boot")` — 대응 스냅샷 조인 키(`OperatorActionArtifacts.kt:45-48`).
UI는 이 ID들을 재생성하지 않고 `RcSession` 단일 상태를 읽는다.

### 2-8. 미구현·후속
| 항목 | 순서 |
|---|---|
| 구역 지도 네이티브 표시·점찍기 | S2 이후 — 10-16 계약의 RC 지도 요구, 캐시/저장/적용 분리 준수 |
| Edge 적용 상태의 현장적 표시 강화 | S2 — `MissionController` 결과를 `tvMissionReadiness` 3줄에 반영(현행은 결과 한계) |
| 저장 공간 확인 표시 | S2 — 신규 필요(현재 준비 화면에 없음) |

---

## 3. 운용 = 비행·카메라·AI 관측·대응·기록 (pageObserve)

### 3-1. 화면 목적
조종자가 **앱을 전환하지 않고** 한 화면에서 `비행 상태 파악 → 카메라 조작 → AI 후보 확인 → 기록 상태 확인`
을 끝낸다. 비행 필수 정보가 AI UI에 가려지지 않는다(S2 완료 기준).

### 3-2. 진입·이탈과 내비게이션
| 항목 | 계약 |
|---|---|
| 진입 | 준비 완료 후, 또는 활성 세션 기동 직행. `pageObserve` = 운용 홈. |
| 이탈 | `KSI 세션 종료`(`btnEndFlight`) → 기록 검토(§5). 세션 중 내비 이동 시 경고(운용 이탈 방지). |
| 역할·근거 | 10-17 계약 4목적지 중 `운용`. 상단 고정 비행 안전·제어 + 영상·지도 + AI 관측·기록(S2 구현: 운용 데크). |
| 금지 | 운용 중 임무 재적용·Core 설정 변경 금지. 영상 확대는 내부 전환(`btnViewVideo/Map`)뿐. |

### 3-3. 구성 요소 (구현 구조 기준, 가림 없음)

**2026-10-23 round50/D7 — 웹 현재 구성, 네이티브는 지도 부재 경로:**

- 웹 기본 영상+216×144 논리 미니맵, 확대 시 지도+작은 영상. 접기/복귀·드래그/줌/중앙 복귀와
  지도 없음/로딩/오류/오래됨을 구분한다. 후보 상세에도 지도/영상 맥락과 대응이 남는다.
- 상단은48dp 한 줄 상태(메뉴48dp), 하단은32dp 읽기 전용 계기다. HUD는 상세 패널과 독립된
  전체 폭이며 조작 최소48dp·고도 기준/단위·마지막 확인값/지연을 보존한다. 이 수치는 구현 치수이지 영구 사용자 제약이 아니다.
- 실제 지도 공급사·타일·지리 정합은 미구현이다. 네이티브 MAP 요청은 영상을 숨기지 않고
  모서리168×112dp의 부재 상태/자세히 보기로 응답한다. 실제 지도나 웹 PIP와 같은 기능으로 주장하지 않는다.
- 현재 렌더/검증/한계: `design/rc-pilot-shell/captures/round50-minimap/RECEIPT.md`.

**2026-10-23 round49 컨트롤러 우선 기록 — D6 원칙 유지, 지도/계기 배치는 round50 우선:**

- 웹: 상시 레일·잠금·배너를 기본 화면에서 제거한다(조종은 물리 조종기). 헤더 48dp는 메뉴·
  제어/모드/배터리/기체 연결/위치·홈 사실만 남기고, UTC/RC시각·MOP 표기를 뺀다(수신시각 원본은
  상세·이벤트에 유지). obs 전체 밴드를 걷고 영상 안 48dp 칩 하나로 관측 상태·KSI 기록 상태·
  확정된 미동기화 N건만 보여주며 칩 전체가 상세 진입이다. 영상·필수 계기·조건부 문제만 상시 표시하고,
  HUD는 H/D/속도/VS/방위 5셀이다. 앱 요청은 메뉴 → 기체 조작 보조에서
  요청별로 열고, 기체 연결 미확인 시 보내지 않며 원본 상태를 단정하지 않는다.
  당시 웹 렌더에는 단일 [SIMULATED] 표기를 뒀다(이력; 새 독립 시안에는 D9 적용).
- 네이티브: 고정 도크는 기본 숨김(`gone`)이다. 사진·줌·녹화·짐벌·비행은 메뉴 보조 항목에서
  기존 게이트를 지키는 다이얼로그로 열고, 요청 결과는 다이얼로그 안에서 확인한다(숨은 줄에 쓰지 않음).
  `OpsAssistMenu` 매핑·`commandDock` 0고정비·다이얼로그 복원이 호스트 시험으로 고정된다.
- `CockpitTaskPresentation` 영구 게이트 문구(GATE_LINE/GATE_DETAIL)를 제거하고, 앱 전용 요청
  사유(`REQUEST_DETAIL`: 물리 조종기와 무관)만 요청 상세에 둔다. ACTIVE 승인과 합치지 않는다.
  하단 바의 영구 잠금 행(`tvControlGate`)을 제거하고 준비·조작·복구 진입만 남긴다.
- 웹 논리768×480과 usable768×464 모두에서48×48 조작을 유지한다. 전체 미확인에 정상값을 남기지 않고,
  지연에는 마지막 확인값을 보존한다. 배터리 주의는 수치·다음 행동을 지속 표시한다.
- 최신 증거/한계: `design/rc-pilot-shell/captures/round49-controller-first/` 소유자 검증.
  웹 시연과 네이티브 배치가 픽셀 동일하다는 주장은 하지 않는다. Android 실제 렌더/실기 인수는 미검증이다.

**2026-10-22 round48 비행·관측 교정 — round49에서 상시 레일·영구 잠금 행이 제거됨(기록 유지):**

- 웹: 상시 메뉴 레일을 관측에서 접고 메뉴로 접근한다. 영상 좌측 비행/귀환/잠금 사유,
  우측 사진·녹화 요청·줌±·짐벌 요청을 둔다. 후보 검토/진단이 열려도 양쪽 조작과 핵심 계기가 남는다.
  영상 보기 전환은 비행·AI·기록을 바꾸지 않는다. 비행 명령은 현재 G1b 사유로 차단한다.
- 네이티브: `ObserveChrome`·`btnNavMenu`로 같은 목적지에 접근한다. 구 `commandDock`에는
  `btnPhoto/ZoomIn/ZoomOut`과 `btnOpenRec/OpenGimbal/OpenFlight`, 짧은 `tvCommandResult`만 고정한다.
  `recGroup/gimbalGroup/flightGroup`의 기존 명령 버튼은 필요할 때 다이얼로그로 열고 닫으면 원위치로 돌린다.
  요청 결과 전체도 다이얼로그에서 읽으며 고정 영역을 늘리지 않는다. 비행·카메라 게이트는 변경하지 않는다.
- 고정 명령부의 행/결과 선언 높이는144dp, 여백 포함 리소스 예산은160dp 이하로 검사한다.
  이는 **정적 구조 검사**이며 실제 네이티브 렌더·입력/글꼴/inset 측정이 아니다.
- 웹 논리768×480과 usable768×464 모두에서48×48 조작을 유지한다. 전체 미확인에 정상값을 남기지 않고,
  지연에는 마지막 확인값을 보존한다. 배터리 주의는 수치·다음 행동을 지속 표시한다.
- 최신 증거/한계: `design/rc-pilot-shell/captures/round48-flight-workflow/RECEIPT.md` 소유자 검증.
  웹 시연과 네이티브 배치가 픽셀 동일하다는 주장은 하지 않는다. Android 실제 렌더/실기 인수는 미검증이다.

**2026-10-22 round47 개발 배치 기록 — round48에서 바뀌지 않은 상태·행동 원칙은 유지:**

- `PRODUCT.md`의 4단계 정보 위계(항상 확인/상황별 대응/눌러서 상세/출처 구분)를 적용한다.
  실행 상태 6종·미확인 **요약**은 고정한다. 처리 수·입력 상세·해시·왜 미확인인지·처리 이력은
  `cockpitDetailsScroll`로 옮긴다. 요약을 스크롤에 숨기거나 상세가 고정 명령부를 키우지 않는다.
- Android `cockpitStatusStrip`은 6개 동등 셀이 아니라 `tvTaskEdgeState` + `tvTaskSupport` +
  조건부 `tvTaskAlert`, 영상 복구/상세 진입이다. `CockpitTaskPresentation`은 6상태를 짧은
  표시로 정규화하며 통신/입력 단절·관측 미수신/지연·공백·기록 실패·미동기화·준비 미완을 보존한다.
  핵심 사실 행에는 줄 수 제한/말줄임을 두지 않는다. 실제 줄바꿈·가용 영상 높이는 **Android 렌더 미검증**이다.
- 고정 `edgeRuntimePanel`은 짧은 실행 상태와 0건 오해 방지 문구만 남긴다.
  `tvEdgeRuntimeDetail`·`tvSessionResult`·`tvCommitState`는 상세 스크롤에서 전체를 확인한다.
  원래 고정 패널 자식이라는 소스 검사는 위 새 위치/비가림/전체 값 보존 검사로 갱신한다.
- 하단 `cockpitRecordActionRow`는 준비·조작·영상 복구 진입만 둔다(영구 잠금 행 제거 — round49).
  KSI 요약은 `KSI 기록 대기/중` 중심의 짧은 줄이며 전체 근거는 상세·contentDescription에 둔다.
  앱 요청 불가 사유는 `tvControlGateReason` 상세와 요청 다이얼로그에만 두며 ACTIVE 승인과 합치지 않는다. DJI 제어권과 Edge 작업/RC 기록을 합치지 않는다.
  새 권한·API·명령 게이트 완화 없음. 준비/적용은 `Edge 준비 요청`/`Edge 번들 적용`, 기록은
  `KSI 로컬 기록 시작`이며 `MissionNextAction.ActionMeta`가 대상·요청·실제 변화 경계를 표현한다.
- 웹 셸의 후보 대응은 메모리 시연이며 네이티브의 영속 대응 구현·실장비 증거를 대신하지 않는다.
  A 정상/B 영상 미연결/C SHADOW 후보, 768×480·2000×840·1920×1200 전후 렌더와 측정은
  `design/rc-pilot-shell/captures/round47-task-first/RECEIPT.md`에 있다.

아래 구성표·round45 설명은 이전 구현 기록이며, 변경된 view의 현행 위치/배치는 위 교정을 따른다.

`pageObserve` = `telemetryStrip`(전체 폭) → `cockpitPriorityRow`(전체 폭, 문제일 때만) → `observeWorkRow`(가로) → `cockpitRecordActionRow`.
`observeWorkRow` = `observeLeftColumn`(weight 1) + `opsDeckScroll`(weight 0.35, 가로 스크롤).
`observeLeftColumn` = `videoFrame`(weight 1) → `cockpitStatusStrip`(왼쪽 컬럼 전체 폭). `opsDeckScroll` 안 = 운용 데크(전체 폭 세로 스택).

**round45 도달성 보정**: Edge 요약(`edgeRuntimePanel`)은 `opsRightColumn`에서 `opsDeckScroll`의 형제로 유지한다.
부가 도구/요청 결과를 열 때 명령 띠를 세로로 키워 영상 높이를0으로 만들지 않는다. 웹 시연은 제한된 우측 도구 영역과
옆 영상으로 재배치하고, B-1 상세/도구의 상단은 문제 행을 포함한 실제 Edge 요약 아래를 따른다.
Android 우측 상세 폭은 공통 명령부 치수256dp로 제한하고 영상 여백은 부모 폭의 절반 이하로 제한한다.
이 구조/소스 검사는 **Android 실측 가림 없음 증거가 아니다**.

| 계층 | 요소 | Android view id (실존) | 터치·시인성 |
|---|---|---|---|
| 1차 | 텔레메트리 띠(제어주체·모드·배터리·링크·위치) | `telemetryStrip`, `tvTeleAuthority/Mode/Battery/Links/PositionValue` | 읽기 전용, 필드별 신선도, minHeight 48dp |
| 1차 | 우려 우선 행(문제만) + 상세 열기 | `cockpitPriorityRow`, `tvPrimaryAlert/Action`, `btnPriorityDetails` | 48dp, 전체 폭(영상 위가 아님) |
| 1차 | 라이브 영상 + 지도 플레이스홀더 (좌측 컬럼) | `videoFrame`, `surfaceVideo`, `tvVideoPlaceholder`, `tvMapPlaceholder`, `tvVideoStatus`, `tvMapStatus` | 박스 오버레이 금지 |
| 2차 | 6칸 상태 스트립 (영상 아래, 좌측 컬럼 전체 폭) | `cockpitStatusStrip`, `tvKsiRecordStatus`, `tvMopStatus`, `tvAircraftStatus`, `tvRecorderStatus`, `tvEvidenceStatus`, `tvAiPeek` + `tvObservationGapPeek` | 14sp bold |
| 2차 | 운용 데크: 영상/지도 전환 | `btnViewVideo`, `btnViewMap`, `tvMapStatus` | 48dp |
| 2차 | 운용 데크: 기체 명령 3종 | `btnTakeoff`, `btnLand`, `btnRth` | 48dp, §3-6 게이트 |
| 2차 | 운용 데크: 카메라 7종 | `btnGimbalUp/Down`, `btnZoomIn/Out`, `btnPhoto`, `btnRecStart/Stop` | 48dp |
| 2차 | AI 패널(제목·상태·후보 목록·노트2) | `aiPanel`, `tvAiState`, `aiCandidateList`, `tvAiOverlayNote`, `tvAiMapNote` | 읽기 13/12/11sp. 후보 탭 = 선택 상태만 변경(AT-02) |
| 2차 | 대응 입력(선택 후보·확인/보류/전달요청·메모·결과) | `responsePanel`, `tvResponseCandidate`, `etResponseNote`, `tvResponseResult` | 48dp, AI 영역 안(비-modal). §4 참조 |
| 2차 | 명령 결과 1줄 | `tvCommandResult` (minHeight 48dp) | 요청≠확인 문구 |
| 3차 | 우측 패널(세션 종료·상세·진단) | `rightStatusPanel`, `btnEndFlight`, `tvOpsStatus`, `btnTogglePanel`, `cockpitDiagnostics` | `tvOpsStatus`는 B-1 항목 렌더 |

### 3-4. 데이터 출처
| 요소 | 출처 (파일·심볼) |
|---|---|
| 텔레메트리 띠 5셀 | `TelemetryStripPresenter.present(accumulator.snapshot(), …)` — `TelemetryStripPresenter.kt:44-82`. 제어주체는 패스스루(`authorityValue`, 출처 미연결 시 `미확인`) |
| 비행 모드·배터리·위치 | `FlightTelemetryAccumulator` 스냅샷 — `rc-ports/FlightTelemetryAccumulator.kt` (MSDK 읽기 전용 `MsdkFlightTelemetrySource`, 카메라 명령 없음) |
| 링크 독립 3사실 | `session.link` + `DroneLink.State` + `internetStatus()` — `MainActivity.kt:854-863` |
| 영상 입력 상태 | `VideoFreshnessModel.evaluate(source.state, …)` — `VideoFreshnessModel.kt`, `MainActivity.kt:1039-1107` (`frameMappingVerified=false` 상시 → 박스 표시 안 함) |
| AI 후보 목록 | `AiObservationPanel.render(Input(ObservationModel.eventCards…))` — `AiObservationPanel.kt:53-101`, `ObservationModel.kt:52-78` |
| 6칸·우선행·B-1 항목 | `OperatorStatus.evaluate(Input…)` → `CockpitStatusPresenter.present` — `MainActivity.kt:919-1028`, `CockpitStatusPresenter.kt` |
| 기체·카메라 명령 | `AircraftCommandPort.request()` + `CommandGate.evaluate()` + `AircraftCommandUi` 문구 — `AircraftCommand.kt`, `AircraftCommandUi.kt` |
| 대응 이력 작성 | **미구현** — 모델(`ObservationResponse.kt`)·저장소(`OperatorActionLog.kt`)는 있으나 운용 화면 입력 UI 없음 |

### 3-5. 상태 행렬 (핵심 — 반드시 지킬 문구)
| 상태 | 문구 | 조치 |
|---|---|---|
| 정상(관측 수신) | AI: "SHADOW 후보 N건 · 경고 아님 · 객체 관측 M건" / "ACTIVE N건 …"(S6 전에는 ACTIVE 표시 금지) | 후보 탭 → §4 대응 |
| 미확인(unknown) | 제어주체 `미확인`(보고 출처 미연결) · 동기화 칩 `미확인 · 로컬 반영` · 텔레메트리 `미수신/수신 시각 미확인` | 추측 표시 금지, 조회 경로 안내 |
| 링크 단절 | "링크 단절 · 명령 전송 불가" · MOP 지연 `지연 N초(올림)` | 비행 명령 차단, 카메라는 기체 연결 시 가능, 기록은 로컬 계속 |
| AI 관측 불가 | "관측 미수신 — 분석 결과 알 수 없음 · 사건 0건과 다릅니다 · 안전 확인 아님"(`AiObservationPanel.kt:64-72`) | **≠ 위험 없음**. 관측 공백을 함께 표현(원칙 5) |
| 기록 중단 | "녹화 오류" 우선행 + `tvRecorderStatus` · "녹화 재개: 재시작 이전 구간 일부는 저장되지 않았습니다" | **≠ 비행 중단**. 기록 공백을 기록(§5에서 대조) |
| 세션 없음 | "기록 없음 · 활성 KSI 세션 없음" / "종료할 활성 세션 없음" | 준비(§2)로 복귀 |
| SHADOW≠ACTIVE | 후보행 kind `SHADOW/객체 관측` ≠ `ACTIVE`; `ObservationResponse.clearsRisk=false`, `authorizesActive=false` | 확인≠해소, 후보선택≠경고승인 |

#### 3-5-1. Edge(Manifold) 실행 상태 표시 (2026-10-21 계약 반영)
`docs/plans/2026-10-21-edge-operation-contract.md` §2 상태 6종를 **AI 관측 칸**에 그대로 표시한다.
`running` bool이 아니라 Edge가 보고한 런타임 상태만 근거로 쓴다.

| Edge 상태 | 표시 문구(요지) | 금지 |
|---|---|---|
| `REQUEST_ACCEPTED` | "요청 수신 · 실행 시작 전" | 실행 중으로 표현 금지 |
| `CONFIG_APPLIED` | "설정 적용됨 · 관측 시작 전" | 관측 중으로 표현 금지 |
| `STARTING` | "관측 기동 중" | 처리 프레임 0을 실행으로 표현 금지 |
| `RUNNING` | "관측 실행 중 · 처리 N프레임" | — |
| `DEGRADED` | "관측 저하 · <사유>" + 입력 상태 | 저하를 정상으로 축약 금지 |
| `STOPPED` | "관측 중지" | 중지와 실패를 구분 |
| 미수신(옵션 필드 부재) | "Edge 실행 상태 미확인" | **absent를 false/0으로 읽지 않는다** |

- 통신 단절과 입력 단절은 **다른 문구**로 구분한다: "Edge 통신 단절 · 마지막 확인 실행 시각 …" vs
  "영상 입력 단절 · 관측 저하".
- **0건 관측과 분석 미수신을 구분**한다: "관측 0건(분석 수행됨)" ≠ "분석 결과 없음".
- **위험 규칙 0건을 숨기지 않는다**: 적용된 번들의 위험 규칙이 0건이면 "위험 규칙 0건 — 위험 판정 없음"
  을 함께 표시한다. 관측은 동작해도 위험 평가가 돌지 않는 상태다(`2026-10-21-edge-operation-contract.md` §10.2).
- 링크 단절 중에는 현재 상태를 정상으로 추정하지 않는다(마지막 확인 시각만 표시).

### 3-6. 조작 (명령·게이트·결과 문구)
| 조작 | 포트·게이트 | 결과 문구(요청≠확인) | 실패 시 복구 |
|---|---|---|---|
| 이륙·착륙·RTH | `AircraftCommandPort.Command.TAKEOFF/LAND/RETURN_HOME` + `CommandGate.evaluate(owner, ownerConfirmed, linkUp, …)` | `AircraftCommandUi.outcomeText`: "요청 전달됨 · 실행 결과는 기체 상태로 확인" 등 5종 | 차단 시 `blockedLine`(사유 명시). **현재는 `owner=UNKNOWN` 상시 → 항상 차단(G1b). DJI 체계가 비행 권한 보유** |
| 짐벌·줌·사진·기체녹화 | 동일 포트 CAMERA 6종. 게이트는 기체 연결만 요구(AI 장애 무관) | 동일 5종 + `[SIMULATED]`(bench) | `기체 연결 없음 · 카메라 명령 불가`, 거부 시 "기체가 요청을 거절함 · 기체 상태 확인" |
| 영상/지도 전환 | `ObserveView.State`(보기 상태만) — AT-05 | 보기 오류는 해당 영역에만, 비행·AI·기록 불변 | 재시도 버튼, 상태 변경 없음 |
| 상태·세션 상세 열기 | 읽기 전용 패널 | — | 우측256dp 기준, surface 여백은 `minOf(명령부+간격, 부모폭/2)`로 제한(`MainActivity.setFlightPanelOpen`). Android 렌더 미검증 |

### 3-7. 화면 간 데이터 흐름
운용은 **읽기 전용 통합**이다: `RcSession`(세션·저장소) + `PlatformLinks`(영상·텔레메트리·명령)를 다시 읽을 뿐
자체 복사본을 두지 않는다(`MainActivity.kt:109-120` 주석). 운용이 만드는 영속 기록은 §4 대응(신규)과
`FileRcStore` 관측뿐이다.

### 3-8. 미구현·후속
| 항목 | 순서 |
|---|---|
| 대응(확인·보류·전달 요청) 작성 UI | **구현 완료(2026-10-20)** — §4 |
| AI 후보 선택 상호작용 | **구현 완료(2026-10-20)** — `ShadowCandidateSelection` 연결(표시 전용, AT-02) |
| 지도 실표시 | S2 이후 — 현재 `tvMapPlaceholder`("DJI Pilot 2에서 확인") |
| ACTIVE 경고 활성화 | S6 별도 게이트 — 규칙별 정확도·오경보·지연·대응 검증 전 금지 |
| 제어권 확인 경로(G1b) | S7 — DJI 제어권 보고 없이는 비행 명령 영구 차단 |

---

## 4. 대응 조작 (운용 내 패널 — 독립 화면 아님)

### 4-1. 화면 목적
조종자가 AI 후보 1건에 대해 **최소 입력으로** `확인(CONFIRM) · 보류(HOLD) · 전달 요청(ESCALATE_REQUEST)` 중
하나를 남기고, 그 사실이 **언제·무슨 근거로** 저장됐는지 즉시 확인한다.

### 4-2. 진입·이탈과 내비게이션
운용 AI 패널의 후보행에서 진입하는 **하위 시트**이며 목적지 이동이 아니다. 저장 후 운용으로 복귀한다.
세션 없이는 진입 불가.

### 4-3. 구성 요소 (2026-10-20 구현 반영 — AI 영역 inline 패널)
| 계층 | 요소 | Android view id | 터치·시인성 |
|---|---|---|---|
| 1차 | 후보 요약(종류·품질·증거 요약) | `tvResponseCandidate` (신규) | 13sp bold |
| 1차 | 3버튼: 확인·보류·전달 요청 | `btnRespondConfirm/Hold/Escalate` (신규) | **48dp**, 간격 4dp+, 장갑 조작 |
| 2차 | 짧은 메모(≤280자, 선택) | `etResponseNote` (신규, maxLength 280) | 48dp 입력 |
| 2차 | 저장 결과 문구(전달 상태 고지) | `tvResponseResult` (신규) | `operatorClaim` verbatim |
| 2차 | 불변 고지(확인≠위험 해소·SHADOW 확인≠ACTIVE 승인) | `tvResponseNotice` (신규) | 상시 표시 |

### 4-4. 데이터 출처
| 요소 | 출처 |
|---|---|
| 후보·품질·증거 요약 | `AiObservationPanel.CandidateRow(eventId/kind/quality/evidenceSummary)` — `AiObservationPanel.kt:32-38` |
| 후보 선택(표시 전용) | `ShadowCandidateSelection.select` — `rc-ports/.../ShadowCandidateSelection.kt` |
| 대응 행위 조립 | `CandidateResponse.buildResponse` (신규, `rc-app/.../CandidateResponse.kt`) — Action·EvidenceRef·DeliveryState 결정 |
| 근거 참조(EvidenceRef) | `ObservationResponse.EvidenceRef(sourceId/receivedAtUtc/policyId/policyVersion)` — `ObservationResponse.kt:73-85` |
| 세션·부트 id | `OperatorActionArtifacts.sessionIdFor(flightId, boot)` — `rc-core/.../OperatorActionArtifacts.kt` |
| 영속 저장 | `RcSession.operatorActions.append(OperatorAction)` → `OperatorActionLog` (NDJSON fsync, id 멱등) |
| 전달 상태 문구 | `ObservationResponse.operatorClaim/effectiveDelivery` — `ObservationResponse.kt:136-184` |

### 4-5. 상태 행렬
| 상태 | 문구(verbatim — 의역 금지) | 조치 |
|---|---|---|
| 확인 저장 성공 | "전달 요청만 저장됨 · 전송 완료 아님"(`LOCAL_ONLY`) | 위험 상태 불변 고지 병기 |
| 전달 요청 저장(오프라인) | "전달 요청 저장됨 · 전송되지 않음"(`REQUESTED`) | 전송 경로 결정(§9-B)까지 대기 |
| 증거 누락 | 응답 작성 불가 — "연결 증거 없음"을 공백으로 표시 | 공백 기록 유지, 안전 주장 금지 |
| 저장 실패 | "저장 실패 · 확인 완료로 표시하지 않음" (AT-01) | 미확인 유지, 재시도 |
| 증명 없는 상위 주장 | `effectiveDelivery`가 `UNKNOWN`으로 강등 → "상태 미확인 · 전송·수신·완료 주장 불가" | 증명 참조 없이 SENT 이상 표시 금지 |

불변: `clearsRisk=false`(확인≠위험 해소), `authorizesActive=false`(SHADOW 확인≠ACTIVE 승인).

### 4-6. 조작
| 조작 | 포트·게이트 | 결과 문구 | 실패 시 복구 |
|---|---|---|---|
| 확인·보류 | `OperatorActionLog.append` (로컬 전용) | `LOCAL_ONLY` 문구 | 실패 시 미확인 유지(AT-01) |
| 전달 요청 | 동일 + `delivery=REQUESTED`, proof 없음 | `REQUESTED` 문구 — **전송 아님** | Core 조회 경로(§9-1) 결정 전까지 로컬 전용 |

### 4-7. 화면 간 데이터 흐름
대응 1건 = `{responseId, candidateId(eventId), siteId, sessionId(§2 조인 키), EvidenceRef, actedAtUtc}`를
`operator_actions.ndjson`에 append → 부트별 스냅샷 → `FLIGHT_LOG` 아티팩트로 비행과 함께 업로드
(`OperatorActionArtifacts.ensureForBoot`, 계약 변경 0). 기록 검토(§5)는 `summary()` 카운트로 읽는다.

### 4-8. 미구현·후속
| 항목 | 순서 |
|---|---|
| 대응 작성 UI | **구현 완료(2026-10-20)** — AI 영역 inline 패널(`responsePanel`). 남음: 대응 이력 목록 조회·수정 UI, Core 조회(§9-1) |
| Core 조회·전달 상태 전이 | §9-1 결정(B 권고 `operator-response.v1` 신설) 후 — S4 |
| 세션 없는 경우의 대응 | 진입 불가(거부 사유 표시·무기록) — 구현됨 |

---

## 5. 기록 검토 = 당시 설정·관측 근거·전송 상태 (pageReview)

### 5-1. 화면 목적
조종자·검토자가 **비행 후** `당시 설정(리비전·구역) + 관측 근거(사건·품질·증거) + 전송 상태(로컬/서버)`를
대조하고, 사건 0건을 안전으로 읽지 않는다.
합의(2026-10-23): 기본은 **평가 우선 + 점진 공개** — 세션 요약 카드(숫자·칩) → 한 줄 평가 행(상태점·사유칩) →
 펼침에서만 사유 상세·썸네일·digest. 증거 단독 탐색은 기록 내 필터로 제공한다.
합의(2026-10-24): 사건은 **종류(위험구역 침입·중장비 접근·안전모 미착용 등 판정 종류)별**로 보여주고,
 임의 소구역명으로 소분류하지 않는다. 구역 연결은 지정된 위험구역 리비전으로만 표시하며,
 없는 구역명·소구역명을 발명하지 않는다.
합의(2026-10-26): 탐색 단위는 관측 이벤트이며 순찰은 기록의 묶음이다. 현장 미연결 일반 비행은
 날짜·비행 세션으로 찾는다. 영상 기반 관측과 공간 기반 판정은 함께 탐색하되 상세에서 근거 조건을 구분한다.

### 5-2. 진입·이탈과 내비게이션
| 항목 | 계약 |
|---|---|
| 진입 | 내비 `기록`, 세션 종료 후 자동 유도, 또는 `btnOpenEvidence` → 증거 화면 경유. |
| 이탈 | 증거·동기화(§5 하위)로 이동하거나 홈으로 복귀. 사후 검토(Core)는 별도 채널. |
| 역할·근거 | 10-17 계약 4목적지 중 `비행 기록`. 검토 시 표시 기준 고지(`review_criteria_note`). |

### 5-3. 구성 요소
| 계층 | 요소 | Android view id (실존) | 터치·시인성 |
|---|---|---|---|
| 1차 | 비행 목록 + 이전/다음 | `tvFlights`, `spHistoryFlight`, `btnFlightPrev/Next` | 48dp |
| 1차 | 비행 상세(리비전·소요·공백) | `tvFlightDetail` | 13sp, 스크롤 분리 |
| 1차 | 아티팩트 현황 | `tvFlightArtifacts` | 12sp muted |
| 2차 | 사건 목록 + 이전/다음 | `tvFlightEvents`, `btnEventPrev/Next` | 48dp |
| 2차 | 사건 상세 + 오탐 의심 토글 | `tvEventDetail`, `btnFlagFalsePositive` | 48dp, 조종사 등록 필요 |
| 2차 | 증거 보기(로컬) | `btnViewEvidence` + `ivEvidence`, `tvEvidenceMeta` | 로컬 우선 |
| 3차 | 대응 이력 요약 | 신규 필요 → `tvResponseSummary`(제안, `OperatorActionSummary` 표시) | 13sp |

### 5-4. 데이터 출처
| 요소 | 출처 |
|---|---|
| 비행·사건·아티팩트 | `FileRcStore` — `FlightHistoryModel.EventRow`, `ObservationModel.eventCards(store, flightId)` |
| 사건 시각 | Edge 모노토닉 원형 + RC 수신시각(근사) — 변환 금지 |
| 소요 시간 | `review_duration_line_format` "기록 소요 … · RC 수신시각 간격" |
| 오탐 의견 | 기존 `pendingAnnotations` + `operatorName/Org`(`AppConfig`) — 사후 검토 의견 채널(3종 enum) |
| 대응 이력 | `OperatorActionLog.summary()`(byAction/byDelivery, effective 기준) — **표시만, 화면은 §4에서 작성** |
| 서버 대조 | `isArtifactComplete/isArtifactSynced` + Core COMMITTED — 로컬≠서버 분리 표시 |

### 5-5. 상태 행렬
| 상태 | 문구 | 조치 |
|---|---|---|
| 사건 없음 + 관측 있음 | "분석됨(관측 N) · 사건 0건" + "사건 0건≠안전 확인" 고지 | 공백 구간 함께 제시 |
| 관측 없음 | "관측 미수신 — 분석 결과 알 수 없음" | 안전 판정 불가 명시 |
| 증거 불완전 | `로컬 x/y · 서버 z/y` (`evidenceSummary`) | 미동기화 목록으로 유도 |
| 로컬≠서버 | "로컬 수신 완료와 서버 커밋은 별도 상태입니다"(`evidence_state_explainer`) | 서버 응답 전 동기화 완료 표시 금지 |
| 대응 있음 | byAction/byDelivery 카운트 + effective 문구 | CONFIRM 카운트를 해소로 읽지 않음 |

### 5-6. 조작
| 조작 | 포트·게이트 | 결과 문구 | 실패 시 복구 |
|---|---|---|---|
| 비행·사건 이전/다음 | 로컬 탐색(외부 명령 없음) | — | 범위 끝에서 유지 |
| 오탐 의심 표시/해제 | 기존 annotation 경로(조종사 등록 필요) | 저장 성공/실패 | 실패 시 미표시 유지 |
| 증거 보기(로컬) | 로컬 바이트 렌더 | 완전성 별도 표시 | 불완전 시 그 사실 표시 |

### 5-7. 화면 간 데이터 흐름
§2의 `flight_id·revision` + §3의 관측·대응 + `FLIGHT_LOG` 스냅샷을 **읽기 전용**으로 재현한다.
이 화면은 새 사실을 만들지 않는다(오탐 의견 제외 — 기존 채널).

### 5-8. 미구현·후속
| 항목 | 순서 |
|---|---|
| 대응 이력 요약 표시 | S3 후속(§4 UI와 함께) |
| Core 대응 조회(t1→t2 재분석 연결) | S4 — §9-1/§9-2 결정 후(`review-finding` 경로 신설 시) |

---

## 6. 기기·설정 (pageSettings + 증거·동기화)

### 6-1. 화면 목적
조종자가 **비행 전**에 `Core 연결·조종사 표시·권한·앱 상태`를 확정하고, **비행 후**에
`업로드 큐·재개·대조`를 끝낸다. 운용 중에는 들어오지 않는다.

### 6-2. 진입·이탈과 내비게이션
| 항목 | 계약 |
|---|---|
| 진입 | 내비 `설정`·`증거`. 전송 관리는 예외 진입점(운용 중 접근 가능 — 유일한 예외). |
| 이탈 | 준비(§2)로 복귀. Core 저장 후 자동 재개(`SyncScheduler.enqueuePending`). |
| 역할·근거 | 10-17 계약 4목적지 중 `설정` + 증거·동기화. `page_title_settings`, `page_title_evidence`. |

### 6-3. 구성 요소
| 계층 | 요소 | Android view id (실존) | 터치·시인성 |
|---|---|---|---|
| 1차 | Core URL·토큰 + 저장 | `etCoreUrl`, `etCoreToken`, `btnSaveCore`, `tvUploadGate` | 48dp 입력·버튼 |
| 1차 | 조종사 표시(이름·소속) + 저장 | `etOperatorName/Org`, `btnSaveOperator`, `tvOperator` | 인증 아님 고지 |
| 1차 | 업로드: 선택 비행 재시도·서버 확인 | `btnUpload`, `btnServerState`, `tvSync` | 48dp |
| 2차 | 로컬 증거 현황·설명 | `tvEvidence`, `evidence_state_explainer` | 13sp |
| 2차 | 시간 고지·앱 정보·권한 안내 | `tvTopUtc`, `tvAppVersion`, `tvDeviceInfo`, `section_permissions` | 12~13sp |
| 3차 | 앱의 개발자 도구·데모(격리) | `developerTools`, `btnDemoTick/NewFlight`, `tvDemo` | 앱의 `[SIMULATED]` 출처 표시. 독립 시안은 D9 |

### 6-4. 데이터 출처
| 요소 | 출처 |
|---|---|
| Core 설정·토큰 | `AppConfig(coreUrl/coreToken, Keystore)` + `coreTokenStorageError` — `RcApp.kt`, `MainActivity.kt:412-433` |
| 업로드 실행·재개 | `EvidenceUploader` + `SyncScheduler.enqueuePending` + `SyncWorker` — `MainActivity.kt:451-456` |
| 대응 스냅샷 업로드 | `OperatorActionArtifacts.ensureForBoot` (FLIGHT_LOG, required) — 응답 없으면 아티팩트 미생성 |
| 권한 | 첫 실행 권한 요청 결과 — `MainActivity.kt:137-148` ("권한 미승인: … 일부 SDK 기능이 제한") |
| 데모 | `DemoSession`(물리 격리 저장소, 업로드 경로 없음) |

### 6-5. 상태 행렬
| 상태 | 문구(실존) | 조치 |
|---|---|---|
| Core 미설정 | "업로드 비활성: Core URL과 토큰이 필요합니다" / "업로드 비활성 — 보안 저장소 오류" | 입력 수정, 토큰 재입력 |
| 동기화 대기 | "자동 동기화 재개 예약: {id}" | 재시작 복원 확인 |
| 토큰 저장 실패 | "보안 저장소 오류 — 토큰이 저장되지 않았습니다" | 저장 성공으로 표시 금지 |
| 서버 상태 불명 | "서버 상태 알 수 없음" / "서버에서 가져오기는 동작 예시(서버 데이터는 알 수 없음)" | COMMITTED 전 완료 주장 금지 |
| 데모 | "DEMO (격리 저장소, 업로드 없음)" | 운용 저장소와 혼합 금지 |

### 6-6. 조작
| 조작 | 포트·게이트 | 결과 문구 | 실패 시 복구 |
|---|---|---|---|
| Core 저장 | `coreConfigured` 게이트(S0) | "Core 설정 저장" / "자동 동기화 예약" | 게이트 미충족 시 비활성 유지 |
| 업로드 재시도 | `uploadNow(selectedFlightId)` | `tvSync` 보고서 | 동일 msg_id 재전송, 봉투당 1회 |
| 서버 확인 | 조회 전용(판단 아님) | 상태 표시만 | 추측 문구 금지 |

### 6-7. 화면 간 데이터 흐름
설정은 `coreUrl/token → coreConfigured → §2 동기화 게이트 → §5 대조`로 흐른다.
대응 스냅샷은 설정 화면 조작 없이 비행 업로드에 동봉된다.

### 6-8. 미구현·후속
| 항목 | 순서 |
|---|---|
| 운영 보안 게이트(G1~G7 해당분: 인증·조직권한·Keystore·보존) | G8/S7 — `production-security-gate.md` 결정 필요 |
| 대응 전달 상태 전이 UI | S4 — §9 결정 후 |

---

## 7. 개발 착수 순서 (S0~S7 연결)

| 순서 | 화면 작업 | 완료 기준(호스트 검증 가능) |
|---|---|---|
| S0 | 본 계약·gap·product-state 정합 | 문서만, 코드·계약 변경 없음 — **완료** |
| S1 | §3 명령 버튼을 `AircraftCommandPort+CommandGate+AircraftCommandUi`에 배선(완료분 유지) | 호스트 테스트 green, 결과 5종 구분. 실기 미검증 명시 |
| S2 | §1 홈 분기 + §2 준비 3줄·칩 + §3 데크 가림없음 + §6 저장공간 표시(신규) | 레이아웃 계약 테스트 유지, 48dp·문구 verbatim |
| S3 후속 | **§4 대응 작성 UI(최우선)** + §5 대응 요약 + `OperatorActionLog` 연결 | append id멱등·재시작 복원·FLIGHT_LOG 동봉 테스트 |
| S4 | §5 Core 대응 조회·`review-finding` 연결 | §9-1/§9-2 계약 결정 후 착수 |
| S5 | 전 화면 실기 렌더·햇빛·장갑·A9-4 1회 완주 | 외부 입력(장치·앱키·Manifold) 후. 없이는 완료 판정 금지 |
| S6 | ACTIVE 표시·경고 활성화 | 별도 규칙별 게이트 통과 전 금지 |
| S7 | §3 비행 3버튼 실사용 | 제어권 확인 경로 확보 후. 그전까지 영구 차단(G1b) |

## 8. 이번 계약이 정하지 않은 것 (결정 필요)

| # | 미결정 | 소유·근거 |
|---|---|---|
| 1 | 대응 이력 Core 조회·전송 계약(A/B/C) | Core 측 — gap §9-1, 권고 B(`operator-response.v1` 신설) |
| 2 | 재분석 결과 경로·변경 영향 계산 주체 | Core+Edge — gap §9-2 (Edge가 영향 계산, Core는 append-only 보관) |
| 3 | 운영 보안 게이트(G1~G7 중 결정 필요 항목) | 별도 — `production-security-gate.md` |
| 4 | 구역 지도 네이티브 표시 방식(타일·캐시·좌표계) | 10-16 구역 계약 소유 — 본 계약은 분리·SHADOW 원칙만 계승 |
| 5 | 텔레메트리 모드 표시명 매핑·갱신 주기·실기 글자 크기 | A9-4 확인 항목 — 10-17 계약 §8 잔여 |
| 6 | Pilot 2 동시 실행·전환 동작 | 복구 대책으로 가정 금지 — 실기 검증 전까지 미결정 유지 |
| 7 | 현장 파일럿·GT·첫 시험 범위 | 사용자 인수 시 확정 — L2 holdout·calibration 전제 |

---
*기준 논리 창 768×480dp landscape, 1920×1200 물리 시안은 2.5배 표시. 960×600 표기는 축소 미리보기.
연결 앱의 상태 문구는 `AircraftCommandUi`·`ObservationResponse.operatorClaim`·`strings.xml`의
verbatim을 따르며, 의역으로 "아님/미확인" 절을 떨어뜨리는 것을 금지한다. 독립 디자인 시안은
D9에 따라 표현을 정리하되 실제 상태·안전 의미는 보존한다. 실장비 검증 없이는 어떤 화면도
구현 완료로 쓰지 않는다.*
