# scripts/h1 — H1 장비 검증 도우미

| 스크립트 | 실행 위치 | 용도 |
|---|---|---|
| `collect_manifold_env.sh` | Manifold 3(또는 후보 호스트) | H1-1 환경 인벤토리(읽기 전용, stdout) |

## 사용

```bash
# 장치에서
bash scripts/h1/collect_manifold_env.sh > env.md
# 결과를 세션 폴더로: docs/verification/records/<SESS-…>/env.md
```

## 원칙

- 스크립트는 **읽기 전용**이다. 설치·설정 변경을 하지 않는다.
- 시리얼·토큰·자격증명 원문을 출력하지 않는다(마스킹 규칙: `h1-capability-plan.md` §0).
- 기록 서식: `docs/verification/templates/h1-run-record.md`.
- 절차·단계 정의: `docs/verification/h1-capability-plan.md`.
