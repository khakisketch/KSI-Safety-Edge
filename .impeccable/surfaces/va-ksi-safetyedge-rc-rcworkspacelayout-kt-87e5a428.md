---
version: 1
slug: "va-ksi-safetyedge-rc-rcworkspacelayout-kt-87e5a428"
primary_target: "android-rc/rc-app/src/main/java/ksi/safetyedge/rc/RcWorkspaceLayout.kt"
related_targets: ["android-rc/rc-app/src/main/java/ksi/safetyedge/rc/MainActivity.kt","android-rc/rc-app/src/main/java/ksi/safetyedge/rc/MissionPlanPanel.kt","android-rc/rc-app/src/main/java/ksi/safetyedge/rc/MissionSiteRow.kt","android-rc/rc-app/src/main/java/ksi/safetyedge/rc/FlightCameraPresentation.kt","android-rc/rc-app/src/bench/java/ksi/safetyedge/rc/WorkspacePreviewFixture.kt","android-rc/rc-app/src/main/res/values/styles.xml","android-rc/rc-app/src/main/res/drawable/mission_row_background.xml","android-rc/rc-app/src/main/res/values/colors.xml","android-rc/rc-app/src/main/res/values-night/colors.xml"]
---

# RC native workspace — 현행 작업 맥락

## 적용 경계 — 2026-10-28

화면 계약 D9와 `AGENTS.md`의 디자인 검토 경계가 이 브리프 전체에 우선한다. 실제 기기·운용 서버에
연결하지 않는 독립 시안에서는 시안임을 알리는 배지·반복 설명이나 같은 뜻의 대체 배지를 두지
않는다. 입력·출처·검증 한계와 자산 라이선스는 보고서/메타데이터에 남긴다. 실제 데이터와
시험 입력이 섞일 수 있는 앱의 출처 식별과 미수신·요청/결과 구분은 유지한다.
아래 이전 라운드의 화면·표기·검증 조건은 당시 기록이며 새 작업의 의무가 아니다.

## 직전 native 배정 — 2026-10-27 임무 + 공통 디자인 정돈

사용자가 Mac의 RC 크기 에뮬레이터에서 본 구성을 조잡하다고 평가했고, 이번 적용 범위를
`임무 + 공통 디자인부터`로 선택했다. 기존 역할색·시스템 sans·4탭·영상 중심 비행을 유지하는
구성/타이포/선택·행동 위계 개선이다. 명령·저장·계약과 운용 앱 설치는 변경하지 않는다.

### Direction contract

THESIS: 현장 선택 → 계획 비교 → 명시적 준비의 순서가 읽히는 지상 작업면. 같은 현장명과
계획 정보를 여러 구역에 반복하거나, 목록 옆의 좁은 지도 때문에 본문을 압축하지 않는다.
OWN-WORLD: 기존 주간 라이트/야간 다크 역할색과 시스템 sans. 20/16/14/13/12sp 역할,
4dp 간격 단위, 얇은 윤곽·선택 면·48dp 조작. 상단 탭은 일관된 밑줄로 현재 위치를 표시한다.
STORY: 저장 현장을 고르면 넓은 본문에서 계획을 비교한다. 선택과 장치 요청은 분리하며,
준비에서만 저장 구역 개요와 실제 요청/응답 단계를 읽는다. 원본 보존이나 실행을 추정하지 않는다.
FIRST VIEWPORT: 48dp 공통 헤더 아래 232dp 현장 목록과 남은 폭의 계획 본문. 선택 요약과
`관측 준비`는 한 footer에 모으고, 준비 진입 뒤 구역 개요와 320dp 요청 패널로 바뀐다.
FORM: 기존 세계 안의 한정된 개선(code-led); 신규 표면/정체성 추첨 대상이 아니다.
서명 상호작용은 현장 선택 → 넓은 계획 비교 → 준비에서 구역/응답 확인의 명확한 전환이다.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

검증은 Mac arm64 Android 에뮬레이터의 1920×1200 @400dpi, 기본/야간/font1.3 및
768×464dp 앱 영역에서 수행한다. 입력은 `[SIMULATED]`; 실제 RC 터치·햇빛·영상·장치 응답과
사용자 인수는 별도다. 이전 라운드의 오래된 시트/미연결 설명보다 현재 코드와 정본이 우선한다.

마무리: round92 (관련 자료 비공개 보존)에 최신 native23장·
APK/source hash·host1682/1676통과/기존6SKIP과 수정 목록 재판정 `ship`을 연결한다. 복구 시각 요약,
비활성 대기 행동, 조종자용 거부 안내/명시적 기술 근거, 구역 caption을 반영했다.
이는 Mac bench 범위의 수정 재검토이며 전체 제품·실기·사용자 인수를 만들지 않는다.

## 이전 배정 — round91 / a19 임무 준비 첫 개발 단위

사용자가 구성·업무 흐름 우선의 개발 진행을 승인했다. round90의 시각 전체 인수가 아니라
실제 저장 현장/계획 선택→준비 요청/대기/응답→적용·기록 조건→비행 복귀를 native에서 연결한다.
기존 역할색·4목적지·명령/보존 주체·미확인·오프라인 대기는 유지한다. 별도 질문에서 격리 bench
설치/화면 조작만 승인됐으며 기체·Edge·Core 실제 명령이나 운용 앱 교체는 승인 범위가 아니다.
후속 이력에 남은 round80 시트/카메라 미연결 설명은 이후 round81/88 구현을 덮지 않는다.
실제 구현·격리 검증 및 남은 범위는 `design/rc-pilot-shell/round91-mission-native/README.md`를 따른다.

Scope: rc-native-workspace. Mode: Operate.
이 브리프는 실제 Android 대상에 연결되는 현행 맥락이다. 라운드 브리프/캡처는 당시 결정의 이력이다.

## 과업과 경계

조종자는 물리 RC와 한 앱으로 준비 → 비행·촬영/자동 관측 감독 → 예외 대응 → 기록 검토를 이어간다.
제품 의미는 AGENTS의 정본, 시각 체계는 DESIGN, 전체 화면 판단은 docs/verification/rc-design-review.md를 따른다.
현재 코드/렌더는 사용자 인수 전의 기준선이며, 전문적인 전체 구성으로 승인된 결과가 아니다.
UI 렌더와 기체 영상/카메라 보고/지도/AI의 실제 연결은 별도다. 카메라 read-only 어댑터의
제한된 관찰과 아직 확인되지 않은 물리 REC/사진·영상은 제품 현황을 따른다.
이 브리프는 장비 접근·실제 명령·ACTIVE를 승인하지 않는다.

## 이력 — round80 Direction contract (현행 배정 아님)

THESIS: 전방주시. 영상 중앙에는 상시 카드·모달·조작 패널을 두지 않는다. 상태는 가장자리 텍스트, 상세는 의도적으로 연 하나의 bounded sheet다.
OWN-WORLD: 현행 native 역할색/시스템 sans/자체 선 아이콘. 영상 위 텍스트는 shadow를 쓰고, 상세 표면은 near-opaque dark chrome 한 계열이다.
STORY: 조종자는 장면을 계속 보면서 가장자리의 짧은 사실만 읽고, 필요할 때만 감독 시트에서 카메라/관측 상세를 연다.
FIRST VIEWPORT:4탭·영상·작은 지도·28dp 계기·우하단 더보기. AI와 카메라는 배경 없는 가장자리 한 줄. 영상이 없을 때만 중앙 empty state.
FORM: a13의 카드/패널을 제거하는 round80. 새 앱 골격이나 4탭 교체가 아니다.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## round80의 판단

사용자는 lens/zoom `미수신` 카드와 영상을 덮는 상세가 운전 중 전방주시를 방해한다고 지적했다.
카메라는 물리 다이얼/버튼이 기본이고, 화면은 현재 상태만 읽는다. 미수신은 카드가 아니라 상세의 사실이다.
줌 터치 보조는 기본 비행면에서 제거하고, 필요하면 감독 시트의 `카메라` 면에서만 다룬다.
AI `미확인`도 상시 카드로 반복하지 않는다. 기록/실행 상태가 있을 때만 짧은 한 줄, 없으면 숨긴다.
카메라 상세의 흰 AlertDialog와 232dp zoom popup을 없애고, `상태 | 카메라` 한 시트로 모은다.
상세 시트는 272×264dp dark bounded를 유지해 영상 중앙·지도·도구를 남긴다.

## 이력 — round80 반증 기준

- 비행 화면에 AI/카메라 dark 카드가 남거나, 영상 중앙을 덮는 zoom popup/모달이 남으면 실패.
- `렌즈·줌 미수신`이 48dp 카드로 상주하거나, 미수신을 숨겨 실제 연결로 오해하게 하면 실패.
- `카메라` 상세가 흰 AlertDialog/전체 화면으로 영상을 덮거나, ±가 gate-first 요청/결과를 잃으면 실패.
- KSI 기록/원본 보존, 4탭, 지도/계기, 상태·게이트·보존 주체를 훼손하면 실패.
- 카메라 보고/영상/지도/AI 실제 연결을 bench 장면/time으로 대체 주장하면 실패.

## 이력 — round80 검증

초기/미수신, bench 장면 위 연결 카메라 텍스트, 지도 펼침, 감독 시트 `상태|카메라`, 카메라 상세,
야간/1.3배, 운용 빈 상태를 묶어 본다. 카메라 bench 상태는 `[SIMULATED]` 표시 입력으로만 쓴다.
최신 관찰은 design/rc-pilot-shell/README.md와 연결된 검증 문서에서 APK/package/출처를 확인한다.

## 이력 — round80 Finish review

round80/a14에서 카메라/AI 상시 카드, 232dp zoom popup, ± 패널, 카메라 흰 모달을 비행면에서 제거했다.
카메라는 `카메라 ?`/`REC 00:53 · WIDE 1.0×` plain text, AI는 상태가 있을 때만 짧은 글,
상세는 `...`의 272×264 dark bounded `비행 상세` `상태|카메라` 하나로 모았다.
물리 줌 다이얼이 기본이며 비행면 터치 줌은 없다. 최종48/48 native, Android1582/1576 pass/6 SKIP,
운용a14 설치·3ENDED/자료 SHA 보존. **실제 카메라 보고 어댑터는 미연결**이고 운용은 미수신이다.
AI Detection 감지 feed/on-video overlay는 다음 단위다. 사용자 인수/카메라 경험 완료가 아니다.

## round81 카메라 read-only 수신

실제 MSDK 카메라 소스를 a15에 연결했다. 운용 화면에서 `WIDE 1.0×`→`WIDE 10.6×`를 실제로 관찰했고,
앱은 listen만 사용했다. 물리 REC/촬영·기록 시간 단위·multi-lens indexing은 DEVICE GATE다.
bench 장면/REC/촬영은 여전히 `[SIMULATED]` 표시 입력이다. 실제 영상/AI/map은 별도.



## round86 HUD 스타일 설정

a16에서 설정 **> 화면 > 비행 HUD**로 H1 텍스트/H2 계기선/H3 링을 선택한다. 기본 H2.
기존 28dp 하단 계기 행을 없애고 cardless `FlightHudView` overlay로 옮겼다. 지도/더보기는
기존 기능 컨트롤(카드형)을 유지한다. 실제 영상은 미연결이라 HUD는 회색 offline 위에서
확인했고, 밝은 장면은 bench `[SIMULATED]`로만 검토했다. H3 링이 밝은 장면에서 가장 약해
H2가 기본이다. 1.3배는 HUD 170dp/내부1.15. 물리 REC/사진·AI feed·실제 영상은 별도다.

## round87 카메라 상세·감지·cardless 지도

a17에서 카메라 상세의 REC false(`REC 아님`)와 unknown(`미수신`), 사진 unknown(`미수신`)을 분리했다.
`비행 상세`에 감지 탭을 추가해 기존 AiObservationPanel의 read-only 후보/공백/근거를 보여주며,
영상 위 박스는 프레임 대응 검증 전까지 표시하지 않는다. 저장 구역이 없는 지도와 더보기는
cardless로 바꿨고, 실제 저장 좌표가 있으면 기존 compact 지도 preview를 유지한다. 실제 영상은
미연결이며, 활성 Edge 감지 feed·물리 카메라·지도 타일은 별도 검증이다.

## round88 상세 전체 화면

a18에서 비행 중 영상 폭을 줄이던 상세 side dock을 제거했다. 평상시 영상은 전체 폭이고,
AI/KSI/카메라 edge text는 표시 전용이다. `...` → 비행 상세는 영상 대신 여는 전체 화면 모드이며
상태/카메라/감지 3탭, 닫으면 영상/HUD로 복귀한다. 상세를 기록/설정으로 완전히 옮길지는
아직 결정하지 않았다. 실제 영상은 미연결이다.

## round90 제안 전용 Direction contract (native 구현 변경 아님)

사용자가 전체 비평 뒤 `상태·임무·비행 조작` 우선, `전체 화면 개선 시안`을 선택했다.
대상은 `design/rc-pilot-shell/round90-proposal/index.html`의 독립 웹 모형이며
native 승인·설치·실기 검증을 만들지 않는다. 위 native 구현 기록은 그대로 유지한다.

THESIS: 넓은 영역은 현재 선택/과업에 쓰고, 비행 중 상세·보조 조작은 영상 폭을 빼앗지 않는다. 빈 패널을 장식으로 채우지 않는다.
OWN-WORLD: DESIGN.md의 다크 비행/라이트 지상 역할색·시스템 sans·선 아이콘을 계승한다. 12/14/16/20sp에 대응하는 모형 글자 위계, 48dp 대응 조작 영역, 일관된 선택/경고/미확인 상태를 쓴다.
STORY: 조종자는 현장 선택 뒤 오른쪽에서 계획을 읽고, 준비/적용/기록의 요청과 응답을 구분한다. 기록에서는 RC 수신과 서버 영수증, Edge 원본의 독립 축을 읽는다.
FIRST VIEWPORT: 기존 4목적지/48dp 헤더, 평상시 전폭 비행, 임무는 목록과 선택 내용의 2열, 지상 기록은 행/근거 중심이다. 상세와 저빈도 보조 조작은 명시적 별도 화면과 비행 복귀를 갖는다.
FORM: 사용자 선택으로 고정된 기존 세계 확장·code-led 제안이었다. 식별 가능한 a18 값은 과거 캡처값으로만 재사용했다. 당시 화면의 `[SIMULATED]` 표시 요구는 이력이며 새 독립 시안에는 적용하지 않는다.
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

제안의 종료 기준: 주요 화면 클릭 경로·48dp 대응 hit box·768×480/464 모형 fit을 브라우저에서 확인하고,
독립 검토와 캡처 출처를 남긴다. Android dp/sp·TalkBack·실기 적합성은 이 웹 모형으로 검증하지 않는다.
현재 native DESIGN.md/앱/장비/계약은 수정하지 않는다.

### round90 제안 검토 결과

웹 모형 14경로의 주간768×480 및 야간/글꼴1.3/768×464 28건, 상호작용8건,
키보드 스모크9건을 확인했다. 긴 자료·상세 영역은 내부 스크롤한다.
독립 검토 `ses_e87b8dcafffeAS9R6XKtt2yl67`는 지연 속도 표현·큰 글꼴 fit·선택 기록 복귀·
포커스·진단 링크 이름 5건 수정에 한정해 `ship` 판정을 반환했다. 전체 native 승인 아님.
DESIGN.md/sidecar는 기존 native 정본을 유지하며, 모형의 outline·주야간 역할색은 제안에만 적용했다.
결과·출처·제한은 `design/rc-pilot-shell/round90-proposal/README.md`와 `verification.json`에 있다.
