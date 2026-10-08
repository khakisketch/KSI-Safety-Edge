#!/usr/bin/env bash
# One-shot verification run for all suites (M0/M1).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="$REPO_ROOT/.venv"

if [ ! -x "$VENV/bin/python" ]; then
  echo "venv missing; run scripts/setup_env.sh first" >&2
  exit 1
fi

cd "$REPO_ROOT"
exec "$VENV/bin/python" -m pytest \
  contracts/tests \
  libs/ksi_contracts/tests \
  core/tests \
  harness/tests \
  -q -p no:cacheprovider "$@"
