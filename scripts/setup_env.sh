#!/usr/bin/env bash
# KSI Safety Edge dev environment bootstrap (W1a).
# Idempotent: safe to re-run. Does not require activation.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VENV="$REPO_ROOT/.venv"

if [ ! -x "$VENV/bin/python" ]; then
  uv venv "$VENV" --python 3.12
fi

# Editable installs for every component that currently has a pyproject.toml.
for pkg in libs/ksi_contracts core harness; do
  if [ -f "$REPO_ROOT/$pkg/pyproject.toml" ]; then
    VIRTUAL_ENV="$VENV" uv pip install -e "$REPO_ROOT/$pkg"
  else
    echo "skip $pkg (no pyproject.toml yet)"
  fi
done

# Dev/test dependencies (httpx is barely used by W1a; W2/W3 need it).
VIRTUAL_ENV="$VENV" uv pip install pytest pyyaml jsonschema referencing rfc8785 httpx

echo "setup_env.sh OK (venv=$VENV)"
