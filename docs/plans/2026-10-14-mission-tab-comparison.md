# 임무(비행 전 준비) 탭 비교 — DJI Pilot 2 / DJI Fly / QGC / KSI

작성: 2026-10-14 · 범위: 본 파일만 신규 작성 (다른 파일 미수정)

## 1. 전제: Apples-to-Oranges 선언

- KSI Safety Edge의 임무 탭은 **비행 계획(flight planning)이 아니다**.
  제품 경계상 비행 제어는 DJI Pilot 2가 담당하고, KSI는
  **현장(site)/번들(bundle) 동기화 → 적용 → 로컬 기록 세션 시작**만 한다
  (근거: `docs/plans/2026-10-12-b1-operator-flow-contract.md` §화면 동선,
  `design/rc-pilot-shell/field-shell.html` 문구 "실제 기체 비행 제어 없음").
- 따라서 비교 대상 중 **정당한 유사(analog)** 는 계획 편집기가 아니라 다음이다:
  (a) 임무 가져오기/동기화 (Pilot 2 클라우드 노선, QGC Open/Save/Download,
  Fly 노선 라이브러리, UgCS Upload),
  (b) 이륙 전 준비 상태 확인 (Pilot 2 preflight checklist, Fly pre-flight checklist,
  QGC 업로드 전 feasibility check),
  (c) 세션 시작 (QGC Fly View에서 mission start, Fly GO, UgCS Auto mode,
  KSI 로컬 기록 시작).
- **범위 밖**: 웨이포인트 편집, 매핑 파라미터(고도/중복도/짐벌),
  지오펜스/패턴 도구는 KSI 비교 대상이 아니다. 아래 표에서 KSI 열이 "없음"인 것은
  결함이 아니라 제품 경계다.

## 2. KSI 임무 탭의 현재 구성 (확인됨 — 저장소 출처)

- 진입점: 좌측 내비 `임무 · 비행 전 준비` → `01 / 비행 전` 페이지.
- 표시: 사이트·선택 번들·미션 리비전 ID(번들에 고정, 예시 `SIM-REV-A-003`)·구역·
  비행 ID·기록 상태·시작 시각(알 수 없음)·시각 기준(RC 수신시각 근사) — 8셀.
- 동작 수: **주 1 (로컬 기록 시작) + 보조 8** (현장 동기화·번들 내려받기·준비·적용·재조정·
  관측 세션 시작·관측 세션 정지·로컬 기록 종료) — 총 버튼 9개 (실측, 2026-10-14).
  헤더 고지: "KSI 기록 준비만 확인 · 비행 안전 승인이 아닙니다".
- 상태 피드백: `inline-state` 한 줄 유지
  ("실행 전 · 동기화 결과는 알 수 없습니다. 이 화면은 서버에 연결되지 않습니다") —
  토스트가 사라져도 남는다 (round6 기록).

## 3. 비교표

| 측면 | DJI Pilot 2 (공식 문서) | DJI Fly (공식 문서) | QGC (공식 문서) | KSI 임무 탭 |
|---|---|---|---|---|
| 진입점 | 홈 `Flight Route` → 노선 종류 선택 (Waypoint/Area/Linear/Mapping) + 카메라뷰 내 Preflight Check | 홈 `Go Fly` → 카메라뷰; Waypoint 아이콘(좌측)으로 노선 모드 진입 | 상단 뷰 전환 `Plan` / `Fly` 분리 | 좌측 내비 `임무 · 비행 전 준비` |
| 계획–실행 분리 | 분리: 노선 라이브러리(로컬/클라우드 동기화)에서 계획·저장 후 카메라뷰에서 실행 + 체크리스트 | 약한 분리: 카메라뷰 안에서 계획→GO 업로드→실행; 노선 라이브러리 저장/정렬 | 강한 분리: Plan View에서 편집·Upload 후 Fly View에서만 실행 (Fly에서 수정 불가) | 계획 없음: 동기화→적용→로컬 기록 시작만; 지도는 없음 |
| 단계 수 / 주 동작 | 계획: 노선 생성→기체·페이로드 선택→영역/점 찍기→파라미터 수십 종→저장→체크리스트→실행 (10단계 이상, 컨트롤 수십 개) | 계획: 웨이포인트 지정(최대 200개)→개별 설정(고도·속도·헤딩·짐벌·줌·호버)→노선 설정(전체 속도·종료 동작·신호 끊김)→GO→일시정지/계속/중지 | 계획: Plan 진입→항목 추가→파라미터 편집→Upload→Fly 전환→실행 (4대 단계, 항목당 편집기) | 동기화 2 → 준비·적용·재조정 3 → 세션·기록 3 (표시 8셀, 버튼 9개: 주 1 + 보조 8) |
| 상태 피드백 | Preflight checklist 항목별 pass/fail (failsafe·스틱 모드·RTH 고도·장애물 거리 등) + 노선 업로드 진행 | pre-flight checklist 모달(매 비행 전 전체 확인) + 업로드 중 빨간 취소 버튼 + 실행 중 시간/웨이포인트/거리 표시 | Upload/Save 버튼 하이라이트(미저장·미업로드 구분) + 노선 계산 상태 + 업로드·실행 전 feasibility check 실패 시 시작 차단 | `inline-state` 지속 문구 + "알 수 없음" 명시; 성공 위장 금지 (서버 상태 추측 금지 계약) |
| 비행 중 화면 관계 | 카메라뷰/맵뷰로 전환, AR 투영·주석·라이브 상태 | 카메라뷰 유지, 일시정지/계속 오버레이 | Fly View 별도 화면 (진행 추적 표시, 편집 불가) | 조종 화면과 분리; 임무 탭은 비행 중 조작을 요구하지 않음 ("DJI Pilot 2에서 조종") |
| 비행 후 검토 위치 | Album / FlightHub 2 미디어·매핑 출력 | Playback (촬영 직후 미리보기) | Analyze View (로그 다운로드·재생·지오태깅) | 별도 `기록 · 비행 후 검토` 탭 (로컬 비행 목록 + 증거 + 동기화 분리) |

## 4. 복잡도 판정: KSI가 더 단순한가?

**예. 정당한 유사(a)(b)(c) 범위로 한정하면 KSI 임무 탭이 가장 단순하다.**

- 선택 수: Pilot 2 노선 생성 시 기체·페이로드·노선 종류·고도 모드·촬영 파라미터
  (수십 개) vs KSI는 번들 1개 선택 + 버튼 9개 중 주 동작 1개.
- 단계 수: Fly 웨이포인트도 개별 설정 7종(카메라·고도·속도·헤딩·짐벌·줌·호버) ×
  최대 200점 + 노선 설정 5종 vs KSI는 동기화→적용→시작 3단계.
- 인지 부하: QGC는 Plan/Fly 문맥 전환 + 항목별 편집기 + 업로드 상태 추적 vs
  KSI는 단일 페이지, 지도·수치 편집 없음.
- 단, 단순함의 이유가 **기능 우월이 아니라 범위 축소**(비행 계획 없음)임을 명시한다.
  웨이포인트 편집 자체와 비교하면 범주 오류다.

## 5. 권장 (시각 스타일 처방 금지)

1. **주 동작 1개 유지 + 보조 접기** — keep-as-is.
   현재 `로컬 기록 시작` 단일 primary + 나머지 보조 위계는 Fly의 GO 단일 패턴 및
   QGC의 Upload 하이라이트 패턴과 일치한다. 더 쪼개지 말 것.
2. **준비 상태를 체크리스트형 3줄로 고정** (동기화→번들→기록 가능) — small change.
   Pilot 2/Fly의 pre-flight checklist를 차용하되 항목은 KSI 사실만
   (번들 ID·Core 미설정·기록 상태). PASS/FAIL 대신 현재의 사실·알-수-없음 어휘 유지.
   *사용자 결정 필요: 문구 3줄 확정.*
3. **동기화 결과 상태를 칩 1개로 상시 노출** (예: `미확인/실패/성공(로컬 반영)`) — small change.
   QGC의 Upload 하이라이트(미업로드 구분) 패턴 차용. 서버 성공으로 읽히지 않게
   "로컬 반영" 한정 문구 필수. *사용자 결정 필요: 칩 어휘.*
4. **준비·적용·재조정 3버튼을 1차 흐름으로 묶기** (현재는 동등 나열) — needs user decision.
   UgCS의 Upload→Auto 순서 고정처럼 `준비 → 적용 → 시작` 순서를 시각 순서로만 명시.
   동작 합침·자동화는 금지(계약상 수동 확인 흐름).
5. **"비행 조종은 DJI Pilot 2에서" 고지를 기록 시작 버튼 근처에 유지** — keep-as-is.
   모드 혼동 방지 문구는 모든 비교 대상에 없는 KSI 고유 안전 장치다. 제거 금지.

## 6. 출처 및 확실성 구분

접근일: 2026-10-14. 공식 문서 vs 커뮤니티 vs 추정을 구분한다.

**확인됨(출처):**
- QGC Plan/Fly 분리·Upload 흐름·Fly 수정 불가:
  `https://docs.qgroundcontrol.com/master/en/qgc-user-guide/plan_view/plan_view.html`,
  `.../fly_view/fly_view.html`; PX4 임무 개요 `https://docs.px4.io/v1.16/en/flying/missions`.
- DJI Fly Waypoint 모드 (최대 200점, 개별 설정 7종, 노선 설정, GO 업로드,
  일시정지/계속, 라이브러리 저장, KML 미지원):
  `https://support.dji.com/help/content?customId=en-us03400007343...` (DJI 공식 지원).
- DJI Fly pre-flight checklist (매 비행 전 전체 확인) 및 카메라뷰 구조:
  DJI Neo/Mavic Air 2 사용자 매뉴얼 (`dl.djicdn.com` PDF), DJI Fly 카메라뷰 지원 문서.
- Pilot 2 노선 종류(Waypoint/Area/Linear/Mapping)·클라우드 동기화·체크리스트 존재:
  DJI Mavic 3E/3T 매뉴얼 (`dl.djicdn.com`), FlightHub 2 매뉴얼
  (`fh.dji.com/user-manual/en/flight-route-library/sync-flight-routes.html`),
  Propeller Aero의 Pilot 2 Terrain/Linear 노선 안내 (커뮤니티·호환 보고).
- UgCS Upload→Auto 실행, 노선 계산 상태(녹색 체크/황점/적색 느낌표):
  `https://manuals-ugcs.sphengineering.com/docs/...` (공식 매뉴얼).
- KSI 현황: `design/rc-pilot-shell/field-shell.html` (§임무, L1180–1244),
  `captures/round6-full-sweep/after/light-02-mission-1920.png`,
  `docs/plans/2026-10-12-b1-operator-flow-contract.md`.

**추정 (미확인):**
- Pilot 2 RC 실기 화면의 정확한 체크리스트 항목 수·클릭 수는 실기 캡처 없이 추정
  (매뉴얼은 항목 예시만 나열). "10단계 이상·수십 컨트롤"은 문서 열거 기반 추정치.
- Fly 구버전(QuickShots만 있던 시기)과 신버전 Waypoint 공존 여부는 기종·펌웨어별로
  다르며, 본 보고서는 공식 지원 문서의 현행 Waypoint 기술을 기준으로 한다.
- Autel Explorer는 문서 접근이 UgCS 경유(KML 내보내기) 수준에 그쳐 본 비교의
  제4 대상으로 UgCS를 채택하고 Autel 네이티브 흐름은 미검증으로 둔다.
