#!/usr/bin/env bash
# Local mirror of CI (.github/workflows/ci.yml): python → edge → android.
#
# Usage:  bash scripts/run_local_verification.sh              # all targets
#         bash scripts/run_local_verification.sh python edge  # subset
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"

TARGETS=("$@")
if [ ${#TARGETS[@]} -eq 0 ]; then
  TARGETS=(python edge android)
fi

for t in "${TARGETS[@]}"; do
  case "$t" in
    python)
      echo "=== python (contracts, core, harness) ==="
      bash "$REPO/scripts/run_all_tests.sh"
      ;;
    edge)
      echo "=== edge C++ (cmake + ctest) ==="
      cmake -S "$REPO/edge" -B "$REPO/edge/build" -DCMAKE_BUILD_TYPE=RelWithDebInfo >/dev/null
      cmake --build "$REPO/edge/build" -j"$(nproc)" >/dev/null
      ctest --test-dir "$REPO/edge/build" --output-on-failure
      ;;
    android)
      echo "=== android (host unit tests) ==="
      bash "$REPO/scripts/android_host_tests.sh"
      ;;
    *)
      echo "unknown target: $t (use: python | edge | android)" >&2
      exit 2
      ;;
  esac
done

echo "local verification: all requested targets passed"
