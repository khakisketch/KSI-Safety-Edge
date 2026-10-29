---
name: KSI Safety Edge RC Native
description: Current Android visual vocabulary, not a record of design acceptance. Native geometry uses dp and typography uses sp.
colors:
  ksi-bg: "#F3F5F6"
  ksi-surface: "#FFFFFF"
  ksi-fg: "#17242C"
  ksi-muted: "#52616B"
  ksi-border: "#D2DBE0"
  ksi-primary: "#1D6154"
  ksi-on-primary: "#FFFFFF"
  ksi-accent: "#299236"
  ksi-surface-subtle: "#EAF0F1"
  ksi-video-bg: "#0C151B"
  ksi-video-fg: "#EEF2F5"
  ksi-video-grid: "#39444C"
  ksi-problem: "#AC1B18"
  ksi-problem-bg: "#FFEBE8"
  ksi-attention: "#864F00"
  ksi-attention-bg: "#FEF0D8"
  ksi-problem-video: "#FF9F96"
  ksi-attention-video: "#FFD18A"
---

# Design System: KSI Safety Edge RC Native

> 공개 인계본: 디자인 캡처 및 현장 원본은 비공개 보존하며 이 문서만으로 시각 인수를 뜻하지 않는다.

## 2026-10-28 · 디자인 검토와 운용 표시

실제 기기·운용 데이터/명령 서버에 연결하지 않는 독립 시안은 화면을 실제 과업 중심으로 보여주고 시안임을 알리는
배지·반복 설명이나 같은 뜻의 대체 배지를 두지 않는다. 제작·입력·자산 출처와 검증 한계는
작업 보고서/캡처 메타데이터에 기록하고, 자산의 라이선스 조건을 따른다. 연결된 앱에서 모의
입력과 실제 보고가 섞일 수 있을 때의 출처 식별과 미수신·실패·요청/실제 결과 구분은 유지한다.
아래의 라운드별 시안 표기는 당시 결과이지 새 독립 시안의 의무가 아니다. 화면 계약 D9 참고.

## 2026-10-27 · round92 임무 + 공통 디자인 (Mac bench 개발본)

사용자가 선택한 범위는 **임무 + 공통 디자인부터**다. 기존 색 역할·시스템 sans·4목적지·영상 중심
비행을 유지한다. 전면 재브랜딩이나 운용 RC 설치가 아니다. 코드 버전은 a19/code16을 유지하며
개발 APK는 hash로 구별한다. 근거는 round92 관련 자료(비공개 보존).

- 공통48dp 헤더의 탭은14sp, 현재 목적지는 일관된2dp 밑줄. 연결 사실과 운용 앱에 섞일 수 있는
  시험 입력의 출처는 보존한다(독립 시안은 위 검토 경계 적용).
- 현장 목록232dp / 나머지 폭의 계획 본문. 현장 행은 최소96dp, 상태/이름/선택 표시를 분리하고
  주야간 역할색의1dp 윤곽·선택 면·focus/ripple을 사용한다. 현장 이름은 큰 글꼴에서3줄까지 읽는다.
- 계획 비교에는 좁은 지도 열을 끼우지 않는다. 계획 ID·구역/규칙/개정·경로 자료 부재·지난 결과를
  넓은 행에서 읽고, 선택 요약과48dp `관측 준비` 행동을 한 하단 영역에 모은다.
- 명시적 준비 진입 뒤 현장명·저장 좌표의 한계·구역 개요를 왼쪽에,320dp 요청 패널을 오른쪽에 둔다.
  번호1/2/3은 실제 준비→적용→KSI 기록 순서이며, 값은 기존 응답 모델을 그대로 사용한다.
  `상세`/뒤로/비행 복귀는 명시적 조작이다. 장문 사실은 내부 스크롤, 주 행동·거부·복귀는 고정한다.
- 재시작 전 미확인 준비 시도의 요약을 패널에 드러낸다. 내구 전송 큐나 자동 재전송을
  새로 만들지 않으며, 준비/적용 응답으로 기체 비행·촬영·관측·원본 보존을 만들지 않는다.
- 타이포 역할은 `TextAppearance.Ksi.{Title,Section,Body,Label}`의20/16/14/12sp와 기존13sp 버튼.
  배치는4dp 단위, 조작은48dp 이상이다. 색 값과 기존 영상 HUD/기록/설정 본문은 바꾸지 않았다.
- 마무리 4건(현행 코드 기준, 최신 캡처 재판정은 별도): 복구 요약은 `MM-dd HH:mm UTC` 분 단위로
  읽히게 표시하고 원시 전체 시각은 기술 근거(`tvMissionApplyState`의 restoredLine)에 보존한다.
  대기 중에는 주 행동 대신 비활성 `전송 대기 중`/`장치 응답 대기 중` 표시(unsent-응답 구분,
  요청 추가 전송 없음)다. `준비·적용 상세`는 조종자용 문제/복구 우선 + 같은 대화상자의
  `기술 근거 보기` 펼침에 원시/unknown 코드를 유지한다. 단일 구역 명칭은 예약 하단 caption
  rail에 표시하고 좌표·형상은 변경하지 않는다. Flight compact 지도 동작은 변경 없다.

확인 장비는 **Mac arm64 Android14 에뮬레이터**,1920×1200 @400dpi 및1920×1160 override다.
이는 RC Plus2의 논리 크기/밀도에 맞춘 개발 렌더이지, RC OS·실영상·장갑/햇빛·TalkBack·현장 인수가 아니다.

## 2026-10-26 · round91 / a19 임무 선택·준비 개발 후보

기존 역할색·시스템 글꼴·4목적지를 유지한 native 업무 흐름 확장이다. round90 웹 전체를 이식하거나
전문 시각 디자인으로 인수한 것은 아니다. 운용 앱은 a18, a19는 격리 bench 렌더만 확인했다.

- 현장 목록과 계획 본문을 분리한다. 왼쪽 256dp 목록, 오른쪽 저장 계획 스크롤/선택 구역 개요,
  고정 선택 요약과 `관측 준비` 행동. 같은 현장을 다시 선택해도 계획을 지우지 않는다.
- 준비 화면의 단계는 **전송 대기 / 응답 대기 / 응답 확인 / 미확인**을 실제 출처로 구분한다.
  연결 단절은 마지막 응답 표시와 별도다. 준비 대기열의 프로세스 재시작 소실 한계를 표시한다.
- 준비·적용·기록은 읽기 전용 40dp 행, 실제 버튼은 48dp 이상이다. 비행 복귀는 단계 행동과
  다른 보조 버튼으로 항상 제공한다. 긴 설명·계획은 내부 스크롤하고 요청 결과로 실행을 만들지 않는다.
- 좁은 non-video 저장 구역 개요의 제목은 두 줄로 표시한다. 타일·경로·현재 위치가 아니라는 의미는 유지한다.
- 주간/야간·font1.3·768×464dp의 실제 native 모의 입력 확인은
  round91 관련 자료(비공개 보존)을 따른다. 운용 배포·실제 장치 응답 인수와 별개다.

## 2026-10-26 · round86 / a16 비행 HUD 스타일 설정

a14의 전방주시 원칙은 유지하고, 하단 계기만 **표시 설정**으로 바꾼다. 설정 **> 화면 > 비행 HUD**에서
H1/H2/H3를 고르며, `flight_hud_style`로 저장되고 즉시 적용된다. 기본은 **H2**.

- **H1 텍스트**: 작은 방위 눈금과 네 값.
- **H2 계기선**: 1px 방위 계기선·눈금·다섯 라벨과 네 값. 기본.
- **H3 링**: 하단 중앙 컴퍼스 링, 링 안 방위/속도, 좌우 고도/거리.
- 세 안 모두 **채움 bar/pod/카드 없음**. 하단 gradient와 그림자만 쓴다. 기존 28dp 하단 계기 행은
  레이아웃에서 숨겨 영상이 높이를 되찾고, 값은 영상 위 `FlightHudView`가 그린다.
- 값은 read-only telemetry 문자열 그대로다. `·지연N초`는 경고 배지로 분리하고, 정확한 방위는
  값 행에, 계기 테이프는 5° 격자로 표시한다. 미수신/미지원은 그대로이며 0°·0.0으로 채우지 않는다.
  H3 방위 마커는 실제 방위가 있을 때만 그린다.
- 표시 전용이다. 카메라/비행 명령·녹화·세션 상태를 바꾸지 않는다. 지도/더보기는 기존 기능
  컨트롤(카드형)을 유지하며, cardless 지도/더보기는 이번 단위가 아니다.
- 1.3배 글꼴에서는 HUD 높이 170dp 고정, 내부 확대 1.15배 제한, 중앙 empty state를 HUD 위로 올린다.
- 실제 a16/code13 캡처는 round86 관련 자료(비공개 보존)에 있다.
  실제 영상은 미연결이라 HUD는 회색 오프라인 화면 위에서 확인했고, 밝은 장면은 bench
  `[SIMULATED]` 참고 장면으로만 검토했다. H3 링은 밝은 건설 현장 위에서 대비가 가장 약했다.
  실제 움직이는 영상 위 가독성은 아직 검증 전이며, 그래서 H2가 기본이다.

## 2026-10-26 · round88 / a18 비행 상세 전체 화면

- 비행 중 영상 폭을 줄이던 **상세 side dock을 제거**했다. 평상시 영상은 전체 폭이고,
  HUD·가장자리 AI/KSI/카메라 텍스트·cardless 지도/더보기만 남긴다.
- `...` → **비행 상세**는 영상 대신 여는 **전체 화면 모드**다. 닫기 X/뒤로 가면 영상/HUD로
  복귀하며, side dock처럼 영상 폭을 예약하지 않는다. 상세는 `상태 | 카메라 | 감지` 3탭.
- AI/KSI pill과 카메라 가장자리 텍스트는 **표시 전용**이다. 탭으로 상세를 열지 않아
  비행 중 우발적으로 영상을 떠나지 않는다.
- 상세는 480dp 읽기 열로 넓은 화면에서 문장이 과하게 늘어나지 않는다. 카메라 false/unknown,
  read-only 감지 feed, 영상 박스 보류 원칙은 a17 그대로다.
- 실제 a18/code15 캡처와 한계는 round88 관련 자료(비공개 보존)에 있다.
  실제 영상은 미연결이며, 상세를 기록/설정으로 완전히 옮길지는 아직 결정하지 않았다.

## 2026-10-26 · round87 / a17 카메라 상세·감지·cardless 지도

- 카메라 상세는 REC `false`를 **`REC 아님`**, 보고 없음은 **`미수신`**, 사진 unknown은
  **`미수신`**, 명시적 shooting=false만 `없음`으로 구분한다. 저장 성공을 뜻하는 문구는 없다.
- `비행 상세`에 **감지** 탭을 추가했다. 기존 `AiObservationPanel`의 read-only 후보/관측 공백/
  근거와 `영상 오버레이 보류` 문구를 그대로 보여준다. 영상 위 박스는 프레임 대응 검증 전까지 없다.
  후보 대응은 기존 `관측 후보` 영역에 남긴다.
- 저장 구역이 없는 비행면 지도는 **cardless**(아이콘/`지도`/`타일 미연결`+shadow)이고,
  더보기는 점 3개만 남긴다. 저장 좌표가 실제로 있으면 기존 compact 지도 preview를 유지한다.
- 실제 a17/code14 화면과 bench `[SIMULATED]` 카메라/감지 캡처는
  round87 관련 자료(비공개 보존)에 있다. 실제 영상은 미연결이며,
  활성 Edge 관측 feed·물리 카메라·지도 타일은 아직 검증 전이다.

## 2026-10-26 · round80 / a14 전방주시 HUD

영상 중앙에는 상시 카드·모달·조작 패널을 두지 않는다. 카메라/AI 상태는 가장자리 텍스트, 상세는
의도적으로 연 하나의 bounded sheet다.4탭·지도·28dp 계기·48dp 조작·상태/게이트/보존 주체는 유지한다.

- `렌즈·줌 미수신` 168×48 카드와 REC/촬영 피드백 카드를 없앴다. 미수신은 우상단의 작은 `카메라 ?`,
  연결 시에는 `REC 00:53 · WIDE 1.0×`/`촬영 실패`/`지연`을 배경 없는 한 줄 텍스트(shadow)로 표시한다.
- 232dp zoom popup·inline 결과·± 패널을 비행면에서 제거했다. 물리 줌 다이얼이 기본이며,
  `btnReqZoom` 게이트/포트는 코드에 남아 있지만 기본 화면의 진입점은 없다.
- `...`의 `관측 상태`/`카메라 상태`는 하나의 **272×264dp dark bounded sheet `비행 상세`**를 연다.
  `상태 | 카메라` 두 면; 카메라 면은 렌즈·줌/REC/사진/보고 없음 여부를 읽기 전용으로 보여준다.
  흰 AlertDialog와 중앙 모달을 없앴다. 시트는 영상 오른쪽을 덮지만 중앙·지도·도구를 남긴다.
- `AI 미확인` 상시 카드를 없앴다. 기록이 없고 시작/복구 행동도 없으면 AI 줄은 숨긴다.
  기록/실행 상태가 있을 때만 `관측 시작 전`/`관측 중`/`관측 보고됨`/`관측 저하`/`관측 중지`/
  `관측 상태 미수신`을 작은 텍스트로 보여주고, KSI 기록/원본 보존은 별도 한 줄로 유지한다.
- bench 정지 참고 장면과 REC/사진/지연 timeline은 `[SIMULATED]` 표시 입력이다.
  실제 카메라 보고/영상 디코드/촬영 저장/물리 다이얼의 증거가 아니다.
  device는 read-only MSDK 보고가 있으면 실제 렌즈/배율을, 없으면 `카메라 ?`/미수신을 표시한다
  (a15에서 실제 `WIDE 1.0×`→`10.6×` 관찰; REC/사진은 DEVICE GATE).
- AI Detection 감지 feed와 on-video overlay는 다음 단위다. 프레임 매핑 검증 전에는 영상 위 박스를 만들지 않는다.

## 2026-10-26 · round79 / a13 영상 우선 HUD

영상이 작업면이고 계기는 가장자리에 붙은 하나의 HUD다. 큰 상태 카드·동등한 코너 블록·일반 설정 폼이
영상 중앙을 지배하지 않는다.4탭·28dp 읽기 띠·48dp 조작·상태/게이트/보존 주체는 유지한다.

- 중앙은 영상 장면이 차지한다. 영상이 없을 때만 작은 아이콘·사실 문구·`연결 확인` 48dp 버튼의
  조용한 빈 상태를 중앙에 둔다. 상시 상태/버튼 바를 중앙에 띄우지 않는다.
- AI 관측은 내용 폭 chip(최대220dp). 기본 `AI 미확인`, 기록 중에는
  `KSI 기록 중 · 원본 보존 미확인` 한 줄. 기록 없음 행은 만들지 않는다.
- 카메라 REC/시간·촬영 피드백과 렌즈/배율은 우상단 한 행의 cluster. REC/실패는 problem-on-video,
  지연은 attention-on-video, 확인/촬영 중은 영상 foreground. 요청 접수로 배율/REC/저장 성공을 만들지 않는다.
- 지도는132×92 near-opaque dark well, 확대288×192. 가짜 타일·기체 위치·경로를 만들지 않고
  실제 저장 좌표만 그린다. 확대해도 영상/카메라/계기 맥락을 없애지 않는다.
- 계기는 왼쪽 compact cluster.28dp 높이와 label/value/tabular 숫자를 유지하고 전체 폭5등분의
  헐거운 분산을 피한다.
- 줌은232dp dark HUD chrome. 원래 gate-first ±와 공용 결과를 재사용하고, 결과는2줄 +
  같은 popup 안의 inline 상세로 연다. 밝은 시스템 AlertDialog를 상세 표면으로 쓰지 않는다.
- 감독은 카메라 클러스터 아래의272×264dp dark bounded sheet. 사실5행·근거 상세·KSI 종료를
  유지하고, 지도·도구·영상 중앙을 남겼다. 당시 장면 출처 라벨은 하단 중앙에 뒀다.
- 당시 bench에만 ESO/E. Graf CC BY 4.0 정지 참고 장면을 `참고 장면 · 실제 영상 아님`으로 표시하고,
  REC 시간 증가/촬영/실패/지연 timeline을 검토한다. **실제 카메라 보고/영상 디코드/촬영 저장의 증거가 아니다.**
  device flavor는 scene=null, timeline=false, camera=unknown이다.

실제 기체 카메라 보고 수신 어댑터는 **미연결**이다. 운용 화면은 `렌즈·줌 미수신`/unknown을 표시한다.
이 라운드는 사용자 인수/전체 카메라 경험 완료가 아니다.

## Overview

**Creative North Star: "A clear video-first observation surface that works with the physical controller."**

이 문서는 현행 Android의 시각 어휘와 적용 범위다. **현재 구현을 전문가 수준으로 인수했다는 문서가 아니다.**
제품 의미/사용자 결정은 `AGENTS.md`의 정본, 제품 맥락은 `PRODUCT.md`, 전체 화면 검토는
`docs/verification/rc-design-review.md`를 따른다. 구체 표면 전략은 도구로 연결된 현행 브리프에 둔다.

실제 구성은 `RcWorkspaceLayout.kt`, 상태·행동 연결은 `MainActivity.kt`, 역할색·기본 조작은
`android-rc/rc-app/src/main/res/{values,values-night}/`에 있다. `field-shell.html`은 별도 웹 시뮬레이션이다.
기존 웹 토큰·반려된 구성·라운드별 변경 기록은
`design/rc-pilot-shell/history/2026-10-25-project-guidance-before-reconciliation/`에 원문 보존했다.
현재 구현과 검증 한계는 `docs/product-state.md`와 실제 코드를 따른다. 일부 렌더 근거는 별도 보존한다.

**Key Characteristics:**
- 영상과 실제 과업이 먼저 읽히고, 상태·조작·근거 상세의 무게가 구별된다.
- 물리 RC와 앱은 역할을 나눈다. 모든 물리 조작을 상시 버튼 목록으로 복제하지 않는다.
- 정상/미확인/지연과 AI·카메라·KSI·보존을 혼동하지 않는다.
- 현행 수치·배치는 검증된 범위의 기준선이지, 사용자 피드백보다 우선하는 영구 규칙이 아니다.

## Colors

frontmatter는 **native 주간 XML에서 추출한 값**이다. 야간 값은 `values-night/colors.xml`,
매핑 메타데이터는 `.impeccable/design.json`을 따른다. 옛 웹 OKLCH/rem 체계로 native 값을 덮지 않는다.
토큰 이름은 코드의 `ksi_*` 역할에 대응한다. 빠진 역할은 원본 XML을 확인하며 값을 추정하지 않는다.
사이드카는 실제 역할색/주야간 메타데이터만 제공한다. native dp/sp·ripple·팝업을 일반 HTML/CSS
컴포넌트로 가장하지 않으므로 웹 컴포넌트 예제·합성 색상 램프·웹 breakpoint/모션 표는 비워 뒀다.

- 일반 작업면: `ksi-bg`/`ksi-surface`/`ksi-fg`와 muted·border·subtle 역할을 사용한다.
- 행동·선택: primary/on-primary 및 accent를 실제 컴포넌트 역할에 맞춰 사용한다.
  녹색 선택/행동 표시가 비행 안전이나 관측 정상의 판정은 아니다.
- 영상 위: video-bg/fg/grid와 별도 on-video 경고색을 사용한다.
  일반 밝은 면의 오류색을 영상 위 텍스트에 그대로 쓰지 않는다.
- 야간: 역할은 같지만 값은 native night 자원으로 바뀐다. 밝기 반전이나 임의의 새 테마를 만들지 않는다.

## Typography

native 시스템 sans를 사용한다. `text()`의 강조는 `sans-serif-medium`, 버튼 기본은
`Widget.Ksi.Button`의13sp/sans-serif-medium이다. 선택/제목별 실제 스타일은 해당 view 코드를 확인한다.
표의 수치에는 `tnum`이 적용돼 있다. 예전 HUD-monospace·CSS clamp·rem 단계를 native 규칙으로 복원하지 않는다.

크기는 **sp**, 배치/터치는 **dp**다. CSS px/rem을 sp/dp와 같다고 가정하지 않으므로 machine frontmatter에
가짜 CSS 글자 크기·버튼 치수를 넣지 않는다. 현재 화면별 예: 탭14sp, 연결 사실11sp,
기본 계기 값12sp와 작은 label span. 이 값들이 전역 타이포 스케일을 완성했다는 뜻은 아니다.
폰트 배율 변경에서 글자 기준선·행 높이·전체 문구를 다시 확인한다.

## Layout

RC Plus2의 물리1920×1200/density2.5, 논리768×480dp와 시스템 inset을 뺀768×464dp를 구별한다.
웹 축소 fit은 Android 창/inset/터치의 증거가 아니다.

사용자 방향은 비행·임무·기록·설정4탭과 영상 중심 비행 골격이다. 현재 a14는 상단48dp,
읽기 계기 기본28dp(왼쪽 cluster), 배경 없는 AI/카메라 텍스트, 좌하단132×92 dark 지도
(확대288×192), 우하단 더보기, 영상이 없을 때만 중앙 empty state, 272×264 dark bounded `비행 상세`
(`상태 | 카메라`)를 사용한다. 카메라 카드·232dp 줌 popup·흰 모달은 비행면에서 제거했다.
카메라/지도/상세의 치수·대비·배치는 현장 피드백 전까지 재평가 가능한 설계 가설이다.
기존 치수에 맞았다는 이유로 전체 화면의 정보 위계가 좋다고 주장하지 않는다.

48dp는 조작 영역 기준이지 읽기 전용 띠·라벨의 최소 높이가 아니다. 가시적 바탕과 실제 hit 영역을 구별한다.
상세/지도/보조 조작을 열어도 비행 사실·복구와 돌아올 맥락을 잃지 않는다.
B-1의 사실·심각도·행동 보존은 모든 항목을 같은 크기의 상시 카드로 나열하라는 뜻이 아니다.

## Elevation & Depth

영상 chrome과 일반 면의 역할 차이로 읽기/조작/상세를 구별한다. `videoChrome()`은 near-opaque
배경·ripple·focus 윤곽을 사용하고, `ghostOnDark()`는 투명 바탕의 상태별 표시를 사용한다.
현재 줌 `PopupWindow`에는6dp elevation이 있다. 예전 웹 resting-card/phone-sheet/shadow CSS는 native 기준이 아니다.
새 모션/그림자나 blur를 이번 지침 정합화에서 추가하지 않았다.

## Shapes

영상 chrome/ghost의 현행 모서리는4dp이며, 일반 버튼은 `button_primary/secondary/ghost.xml`의
실제 자원을 따른다. 이 값은 배치마다 새 캡슐/카드 스타일을 만들라는 지시가 아니다.
자체 `WorkspaceIcons`의 선 아이콘을 사용한다. 글리프·이모지로 아이콘 체계를 대체하지 않는다.

## Components

- 내비게이션: 원래4개 목적지 ID와 상태를 유지한다. 비행에서는 현재 탭 밑줄, 다른 면에서는 역할색 선택 표시다.
- 비행 계기: 실제 presenter 값·단위·미수신/지연을 보존하는 읽기 면이다. 조작과 같은 높이/블록으로 취급하지 않는다.
- AI/관측 상태: 짧은 내용 폭 요약과 필요시 상세. KSI 기록·원본 보존은 다른 사실이며 없을 때 불필요한 행을 만들지 않는다.
- 렌즈/줌: 상태 HUD와 원래 gate-first 보조 버튼/결과를 연결한다. 요청 ACK로 보고 배율·REC·사진 저장 성공을 만들지 않는다.
  현재 popup의 밝은 패널/장문 결과는 구현 현황이지 채택된 운용 HUD 원칙이 아니다.
- 지도: 실제 저장 좌표 개요와 타일·기체 위치·경로를 구별한다. 부재/확대/복귀의 표현은 전체 화면에서 재평가한다.
- 관측 상세: 기본5행과 근거 상세를 구별한다. 종료는 KSI 범위를 명확히 하고 기체 비행·녹화 종료와 혼동하지 않는다.
- 설정/요청: 기존 역할색·focus/disabled/focused 자원을 재사용하고 Back/뒤로 후 과업 맥락을 보존한다.

## Do's and Don'ts

### Do:
- Do judge the whole screen: hierarchy, grouping, density, alignment, action recognition, and the scene left visible.
- Do separate native render evidence, simulated state transitions, adapter wiring, physical operation, and user acceptance.
- Do preserve source-labelled unknown/stale/error states and existing command, data, and permission boundaries.
- Do use actual native resources and the current matched brief; compare them with fresh evidence before extending the UI.
- Do record design-only input and verification provenance in reports or metadata; meet asset licences and distinguish simulated from real data in connected apps.

### Don't:
- Don't treat a reference element's presence, a passed layout test, or a Critic with no findings as professional design acceptance.
- Don't restore historical web recipes, rejected layouts, or fixed round-specific dimensions as permanent design authority.
- Don't invent live video, maps, telemetry, REC, photo success, or preservation receipts to make a screen look complete.
- Don't let a disconnected camera adapter become a claimed complete camera experience through static bench labels.
- Don't add prototype badges, repeated disclaimers, or synonymous replacements to an isolated design-only screen.
