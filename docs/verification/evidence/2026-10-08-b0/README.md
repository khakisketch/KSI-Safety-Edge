# B0 Evidence — 기존 자산 격리 빌드·테스트 (2026-10-08)

- 성격: **감사·검증 증거**. 제품 기능 완료·실장비 검증·H1 결과가 아니다.
- 절차·판정 요약: `docs/superpowers/plans/2026-10-08-reuse-audit-and-porting.md` §1.
- 소스 기준: SI `cedeb27eb8aa49b3f2df3414a9a022dd544d5089` → `git archive`로 선택 경로만 `/tmp/opencode/ksi-audit-20261008/source/`에 전개(자격증명·모델·raw 로그 제외). 주요 5개 파일 SHA256이 정적 감사 보고서와 일치.
- 환경: aarch64 Ubuntu 24.04, gcc 13.3.0, cmake 3.28.3, python 3.12.13(SE `.venv`), pytest 9.1.1.

## 실행 명령 (요지)

```bash
# B0-1 psdk-tap 순수 로직
cmake -S <source>/archive/acquisition-2026/edge/psdk-tap -B <out>/tap \
  -DKSI_PSDK_TAP_LOGIC_TESTS_ONLY=ON -DKSI_BUILD_TESTS=ON
cmake --build <out>/tap -j4 && ctest --test-dir <out>/tap --output-on-failure
# → 2/2 Passed (psdk_tap_pairing, psdk_tap_ndjson_writer)

# B0-2 manifold-runtime portable
cmake -S <source>/archive/acquisition-2026/edge/manifold-runtime -B <out>/runtime \
  -DKSI_BUILD_TESTS=ON -DKSI_ENABLE_TENSORRT=OFF
cmake --build <out>/runtime -j4 && ctest --test-dir <out>/runtime --output-on-failure
# → 10/10 Passed; edge_runtime_tests 246 checks passed, 9 groups skipped(SQLite 부재)

# B0-3 fail-closed 확인(의도된 실패)
cmake -S .../manifold-runtime -B <out>/runtime-wal -DKSI_REQUIRE_SQLITE=ON
# → configure 실패: "SQLite3 개발 헤더를 찾지 못했다" (환경 한계, 코드 결함 아님)

# B0-4 ASan/UBSan portable
cmake -S .../manifold-runtime -B <out>/runtime-asan -DCMAKE_BUILD_TYPE=Debug \
  -DKSI_BUILD_TESTS=ON -DKSI_ENABLE_SANITIZERS=ON
cmake --build <out>/runtime-asan -j4 && ctest --test-dir <out>/runtime-asan
# → 10/10 Passed (WAL 그룹은 동일 스킵)

# B0-5 시간 분석기(격리 사본, 캐시 쓰기 없음)
cd <source> && PYTHONDONTWRITEBYTECODE=1 <SE>/.venv/bin/python -B -m pytest \
  --noconftest -p no:cacheprovider tests/hil/test_capture_frame_telemetry_timing.py -q
# → 124 passed
```

## 한계 (성공으로 처리하지 않음)

- **WAL/store-forward 9개 계약 그룹: 미검증** — SQLite 개발 헤더 부재. sqlite3-dev 확보(CI 컨테이너/장비) 후 재실행 필요.
- **TensorRT/GPU 어댑터: 빌드 미실행** — `NvInfer.h` 부재(설치/다운로드는 승인 범위 밖).
- **PSDK 실행 경로(psdk-app/tap 실행파일·DPK): 미실행** — PSDK fetch·DJI 자격증명·장비 필요(H1 단계).
- **실장비/실비행 검증 없음.** 호스트 로직 통과는 기체 통합 완료가 아니다.

## 원본 보존 확인

- SI repo는 이 작업으로 변경되지 않았다(조회만). 동시 진행 중인 타 workstream의 `apps/dashboard` 변경은 열람 외 개입하지 않음.
- SE repo에는 이 증거 폴더 이외 변경 없음(원장·product-state 갱신과 함께 커밋).
