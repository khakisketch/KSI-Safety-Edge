# KSI Safety Edge

드론 탑재 Edge(PSDK) · RC(MSDK) · 서버 Core로 구성된 **오프라인 우선 안전 관측 제품**의 개발 저장소.

**현재 단계:** M0 계약 기준선 + M1 재생 기반 전체 흐름 구현 중 (선구현·후검증).
하드웨어(Matrice 4 / Manifold 3 / RC Plus 2) 검증은 별도 단계다. ACTIVE 위험 경고는
실측 검증·규칙별 승인 전까지 구현하지 않는다(SHADOW 기본).

## 구성

| 경로 | 내용 |
|---|---|
| `KSI_Safety_Edge_v1.1_Spatial_Design/` | 원본 설계 패키지(출처 snapshot, 수정 금지) |
| `docs/superpowers/specs/2026-10-08-integrated-design.md` | 승인된 통합 설계(기준본) |
| `docs/superpowers/plans/2026-10-08-m0-m1-replay-slice.md` | M0/M1 구현 계획·진행 원장 |
| `docs/superpowers/product-state.md` | 제품 목표·마일스톤·현재 슬라이스 |
| `contracts/` | JSON Schema, OpenAPI, 계약 정책 문서 |
| `libs/ksi_contracts/` | canonical digest·도메인 검증 공용 Python 패키지 |
| `core/` | 서버(FastAPI + SQLite dev + 파일 오브젝트 저장) |
| `harness/` | Edge 재생·RC 시뮬레이터·장애 주입·E2E 시험 |

## 개발 환경

```bash
bash scripts/setup_env.sh        # .venv 생성 + 패키지 설치
source .venv/bin/activate
bash scripts/run_all_tests.sh    # 전체 시험
```

## 문서 우선순위

1. `docs/superpowers/specs/2026-10-08-integrated-design.md` (통합 설계)
2. 원본 패키지 `docs/01` + `docs/14~19` (v1.1 변경 근거)
3. `docs/superpowers/plans/` (진행 원장)
