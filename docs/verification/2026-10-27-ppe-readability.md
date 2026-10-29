# PPE 가독성 분석: 순찰 고도에서 안전모는 읽히지 않는다

Date: 2026-10-27. Method only — no camera specs invented. Parametric in
horizontal FOV; plug the datasheet numbers into the same formula.
Readability bar: helmet (~25 cm) ≥ 8 px (recognition, not mere detection).
Device-measured fact used: WIDE optical zoom reaches 10.6× (a15, real RC).

GSD = 2·h·tan(HFOV/2) / 1920. Helmet px = 0.25 / GSD.

| HFOV | 30 m | 50 m | 80 m | 100 m | 120 m |
|---|---|---|---|---|---|
| 60° | 13.9 px ✅ | 8.3 px ✅ | 5.2 px (zoom) | 4.2 px (zoom) | 3.5 px (zoom) |
| 70° | 11.4 px ✅ | 6.9 px (zoom) | 4.3 px (zoom) | 3.4 px (zoom) | 2.9 px (zoom) |
| 82° | 9.2 px ✅ | 5.5 px (zoom) | 3.5 px (zoom) | 2.8 px (zoom) | 2.3 px (zoom) |

"zoom only" = readable only at ~10× zoom, which cannot patrol (keyhole
FOV). Conclusion: **at patrol altitude, wide-camera PPE is unreadable,
regardless of model accuracy.** A 0.744 mAP PPE detector does not change
physics — it will report helmets it cannot actually resolve, i.e. the
exact fabrication our principles forbid.

## Product consequence

1. **PPE-first는 약한 이야기다.** 고정 CCTV(형제 프로젝트에 이미 있음)와
   차별이 안 되고, 순찰 고도에서는 물리적으로 안 보인다.
2. **옳은 이야기는 PPE × 재관측이다**: 읽히지 않음 → 범위 내 접근/줌 →
   읽힘 → 봉인. 불확실하면 가까이 가서 본다는 로봇 서사이며, 승인된
   2단계 아키텍처와 정확히 맞는다.
3. **PPE 정책은 고도/줌 게이트 뒤에 둔다**: readable regime(저고도·이착륙·
   줌)에서만 2단계 판정, 순찰 고도에서는 person 탐지+공간 판단만.
   게이트 수치(HFOV 실측 후 확정)는 배포 파라미터로, 하드코딩 금지.
4. **투자자 데모**: (a) 기체 연결 후 L1 라이브 루프 — 유일한 실증,
   (b) 지금 당장 방에서 되는 것: bench APK 시연 + 봉인 증거 + Core 영수증
   (전 체인이 돈다는 증명), (c) 재관측을 로봇 차별점으로.
   모델 이름이 아니라 **루프와 정직성**이 제품이다 (정본 Positioning
   원문과 일치).
