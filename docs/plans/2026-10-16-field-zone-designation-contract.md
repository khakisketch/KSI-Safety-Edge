# 현장 위험구역 지정 — 비행 전 RC 동선·표시 계약

작성: 2026-10-16 · 상태: **방향·표시 계약 기록. 디자인 시안·Android 구현 전, 실기 검증 전.**

- 근거(사용자 결정, 2026-10-16): 비행 전 위험구역 지정은 **현장 RC(MSDK) 임무준비의 지도
  흐름**으로 수행한다. 웹/Core는 이 여정의 필수 단계가 아니다(S0 `/review`는 개발자 점검용).
- 상위: `docs/product-state.md`(상태·원칙) · `docs/specs/2026-10-08-integrated-design.md`
  §4(경계)·§10(공간)·§11(화면 계약) · `docs/specs/2026-10-13-service-blueprint.md`(S1 현장
  조종) · `docs/plans/2026-10-12-b1-operator-flow-contract.md`(B-1 — 조종 화면 경계).

## 목적과 경계

- 목적: 조종사가 비행 전 임무준비 화면에서 지도 위에 위험구역(Polygon)을 만들고, 그 리비전을
  Edge에 적용해 관측 판단(SHADOW)에 사용한다.
- **KSI 관측 위험구역이다 — 비행금지구역·지오펜스·비행경로가 아니다.** 비행 조종 권한
  (DJI Pilot 2)은 이 흐름에서 바뀌지 않는다.
- 새 스키마·API를 만들지 않는다. `contracts/`는 불변 — 지도 타일 캐시를 계약에 끼워 넣지 않는다.

## 계약 매핑 (기존 계약 재사용)

| 요소 | 기존 계약 | 비고 |
|---|---|---|
| 구역 형상·종류 | `POST /sites/{siteId}/zone-revisions` — WGS84 Polygon, `rule_kind` 4종(PERSON_ENTRY·EQUIPMENT_ENTRY·PPE_REQUIRED·RESTRICTED_AREA) | base_revision 낙관적 잠금(409) |
| 비행 당시 고정 | `mission-snapshot.v1` `zones[]`(zone_id·revision_id·geometry·zone_sha256·enabled) | `origin: SERVER / RC_LOCAL` |
| 오프라인 생성 | `origin=RC_LOCAL` · risk-policy `created_offline=true` | 복귀·연결 후 서버 canonical ID 별칭 매핑 |
| 정책 연결 | RiskPolicy `ZONE_INTRUSION`.rule.`zone_revision_ids[]` | **mode=SHADOW 고정** — ACTIVE는 METRIC_VERIFIED·규칙별 승인 게이트(M4) 전 금지 |
| 적용 | MissionBundle digest → PREPARE/APPLY → MissionApplication 이력 | APPLIED 확인 전 "준비 완료" 표시 금지 |

## 동선 (임무 준비 — 비행 전)

1. 현장·임무 선택 → 준비 면이 지도 중심: 구역 목록 · 지도 캐시 상태 · 적용 상태(각각 별도 표시).
2. `구역 추가` → 지도에서 꼭짓점 순서대로 탭. 3점부터 미리보기, 완료 시 첫 점으로 자동 닫기
   (계약 최소 4좌표). 유효하지 않은 형상(교차·면적 0)은 즉시 수정 안내.
3. 편집: 꼭짓점 이동 · 마지막 점 실행취소 · 취소. 보조 입력: 중앙 십자선 + `점 추가`
   (장갑·차량 흔들림 대비).
4. 저장: 이름·`rule_kind` 선택 → **새 불변 로컬 리비전**(기존 리비전 덮어쓰기 금지).
5. 적용: 번들 갱신 → Edge PREPARE/APPLY → APPLIED 이력 확인.
6. 비행 중에는 구역 편집을 요구하지 않는다(관측 화면은 상태 확인 중심 — B-1 유지).

## 표시 계약 (분리 원칙 — 합치지 않는다)

- **지도 캐시 상태 ≠ 구역 저장 ≠ Edge 적용.** 셋을 하나의 "준비 완료"로 묶지 않는다.
- 지도 캐시: `오프라인 사용 가능 / 받는 중 / 일부 누락 / 자료 오래됨(촬영일 미확인)`.
  갱신 실패 시 기존 캐시를 유지하고, 불완전한 새 자료를 "준비 완료"로 표시하지 않는다.
- 지도 없음: 빈 지도에 눈짐작 그리기를 강요하지 않는다. 구역 판단 불가를 명시하고 기본
  관측·기록과 구분한다.
- 다운로드 시각 ≠ 촬영 시각: 모르면 `촬영일 미확인`.
- 비행 경로·기체 커버리지 레이어: 확인된 DJI 데이터가 있을 때만 읽기 전용으로 분리 표시.
  없으면 선을 그려 추측하지 않고 "Pilot 2에서 확인 필요" 문구.
- 항공기 GPS를 지상 꼭짓점의 대체값으로 쓰지 않는다.

## 금지 (경계 — 변경 금지)

- 자동 비행·경로 생성·지오펜스 기능으로 표현·구현하지 않는다.
- ACTIVE 승격 금지(규칙별 검증·승인 게이트 전 — M4).
- Pilot 2 캐시 공유 전제 금지 — 공식 인터페이스 확인 + 실기 검증 전까지 사설 캐시 접근이나
  "Pilot 2와 지도가 공유된다"는 표현을 쓰지 않는다.
- `[SIMULATED]`·사건 0건≠안전 등 정직 규칙은 B-1과 동일하게 적용한다.

## 기술 방향 (구현 참고 — 확정 아님)

- RC Plus 2 지도: MSDK `MapWidget` + MapLibre 계열 프로바이더(`initMapLibreMap`, Mapkit/Mapbox
  토큰). Google Map은 RC에서 불가(Google Services Framework 부재 — DJI 공식 답변). 구역 그리기는
  `setOnMapClickListener` → `DJIMarker/DJIPolygon` 구성(공식 3rd-party map 문서·V5 샘플 패턴).
- 오프라인: MapLibre `OfflineManager.createOfflineRegion`(타일 피라미드) 또는 동급 SDK로
  **현장별 자동 캐시**. 수동 "지도 받기" 단계를 기본 여정으로 두지 않는다. 갱신 시각을 기록한다.
- **결정 필요 — 공급사 조사 결과(2026-10-16, 공식 약관 기준)**:
  - 오프라인 저장 제한: OSM 표준 타일 금지 · 카카오/네이버 저장 금지 · VWorld 저장 조항 없음(사실상
    불가, 군사시설 병용 제한) · MapTiler Cloud는 임시 캐시만 · Stadia는 100MB/기기·구독 유지 조건 ·
    Mapbox는 SDK 오프라인(TileStore) 지원. Mapbox·HERE·Esri 용도 조항은 미확인(법무 확인 필요).
  - 안전 용도 조항: **MapTiler는 Cloud·Server/Data 약관 모두 "사망·부상이 발생할 수 있는 제품 운용
    에 사용 금지"** — 사용하려면 Custom License 예외 협의 필요.
  - 후보: ① MapTiler On-prem(Server+Data)+용도 예외 협의 ② Stadia(저비용 파일럿 검증, 100MB 캡
    주의) ③ Mapbox(TileStore)·Esri(Enterprise, .tpkx) — 약관·비용 확인 후. 자체 타일 파이프라인은
    벡터는 가능하나 국내 고해상 항공의 합법 오프라인 확보가 과제.
  - 미확정 상태의 동작 원칙: 지도 없음/스타일라이즈드 폴백으로 동작하며 거짓 준비 완료를 만들지
    않는다(위 표시 계약 유지). 공급사 최종 선정은 현장 파일럿 조건과 함께 확정한다.
- 참고: `developer.dji.com` import-3rd-party-map · MapLibre Native Android OfflineManager 문서.

## 검증

- 디자인: `design/rc-pilot-shell/field-shell.html` 임무 준비 흐름 렌더 검증(절차·영수증은
  `docs/verification/dev-harness-runbook.md`).
- 실기(A9-4 이후): RC Plus 2 렌더·장갑 터치·햇빛, 오프라인 재시작 후 지도·구역 복원, APPLY 확인.
- 회귀 기준: 지도 없음/일부 누락/구역 없음 상태에서 **거짓 준비 완료 0**.

## 미완·후속 (정직 기록)

- 지도 타일 공급사 결정(위).
- 반복 방문 시 현장 경계 재확인 UX(자동 확정 금지) — 디자인 위임.
- 오프라인 zone_id → 서버 canonical ID 별칭 매핑 구현 — Core 후속.
- 실기 검증 전에는 이 기능을 "동작"으로 주장하지 않는다.
