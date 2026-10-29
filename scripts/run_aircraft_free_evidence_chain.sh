#!/usr/bin/env bash
# 기체 없이 "원본 증거 → 중앙 보관" 체인을 실제 구성요소로 돌린다.
#
# WHAT IS REAL HERE
#   - replay_run: the SAME SessionRunner the device app uses, over a replay
#     manifest (frames + telemetry), with the SAME source-evidence capture
#     code (edge/src/evidence/recorder.cpp) → a real sealed revision.
#   - the collector (scripts/collect_edge_evidence.py) reads that revision
#     read-only and uploads the exact bytes it names.
#   - the Core verifies coverage + sha256 and issues a custody receipt.
#
# WHAT IS SIMULATED (and therefore NOT a performance claim)
#   - the imagery is synthetic RGB ([SIMULATED]) and the detections come from
#     a stub detector ([SIMULATED]). Real camera imagery and the real D-FINE
#     engine are the DEVICE/FIELD axis (A9-4), not this one.
#
# WHAT THIS PROVES
#   The evidence CHAIN is complete and honest: capture → seal → collect →
#   verify → receipt, with a refused path for tampered bytes.
#   It proves NOTHING about detection accuracy, spatial truth or timing.
#
# USAGE
#   # 1) a Core to collect into (dev tokens, local only)
#   KSI_DB_URL="sqlite:////tmp/ksi-core.db" \
#   KSI_OBJECT_ROOT=/tmp/ksi-objects \
#   KSI_DEV_TOKENS='{"tok-orgA-rc": {"org_id": "org-a", "scopes": ["rc", "read"]}}' \
#     .venv/bin/python -m uvicorn ksi_core.app:create_app --factory \
#       --host 127.0.0.1 --port 8124 &
#
#   # 2) the chain
#   bash scripts/run_aircraft_free_evidence_chain.sh \
#     --core-url http://127.0.0.1:8124/api/v1/edge --token tok-orgA-rc
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="${REPO_ROOT}/.venv/bin/python"
CORE_URL=""
TOKEN=""
WORK=""

while [ $# -gt 0 ]; do
  case "$1" in
    --core-url) CORE_URL="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --work-dir) WORK="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [ -z "$CORE_URL" ] || [ -z "$TOKEN" ]; then
  echo "usage: $0 --core-url URL --token TOKEN [--work-dir DIR]" >&2
  exit 2
fi

# mkdir is atomic: refuse any existing path, including dangling symlinks.
if [ -n "$WORK" ]; then
  if ! mkdir -- "$WORK"; then
    echo "work directory already exists or cannot be created: $WORK" >&2
    exit 1
  fi
else
  if [ -z "${TMPDIR:-}" ] && [ -d /tmp/opencode ] && [ -w /tmp/opencode ]; then
    TMPDIR=/tmp/opencode
  fi
  WORK="$(mktemp -d "${TMPDIR:-/tmp}/ksi-aircraft-free.XXXXXXXX")"
fi
echo "work directory: $WORK"
CORPUS="${WORK}/corpus"
EVIDENCE="${WORK}/evidence"
mkdir -- "${CORPUS}" "${EVIDENCE}"

# --- 1) a tiny synthetic corpus ([SIMULATED] imagery) -------------------------
python3 - "$CORPUS" <<'PY'
import os, sys
base = sys.argv[1]
W = H = 64
lines = ["# [SIMULATED] synthetic frames — the chain is real, the imagery is not."]
for i in range(3):
    px = bytearray()
    for y in range(H):
        for x in range(W):
            px += bytes(((x * 4 + i * 20) % 256, (y * 4) % 256, (i * 37) % 256))
    name = f"frame-{i:03d}.rgb"
    open(os.path.join(base, name), "wb").write(bytes(px))
    lines.append(f"frame {i + 1} {1000 * (i + 1)} {W} {H} {name}")
open(os.path.join(base, "manifest.txt"), "w").write("\n".join(lines) + "\n")
print(f"corpus: {base}")
PY

# --- 2) the real Edge engine path → one sealed revision ----------------------
FLIGHT="11111111-2222-4333-8444-555555555555"
BOOT="66666666-7777-4888-9999-aaaaaaaaaaaa"
"${REPO_ROOT}/edge/build/replay_run" \
  --manifest "${CORPUS}/manifest.txt" \
  --stub-detector --evidence-dir "${EVIDENCE}" \
  --flight-id "${FLIGHT}" --boot-id "${BOOT}" \
  --out "${CORPUS}/ticks.ndjson"
REVISION="$(ls "${EVIDENCE}/revisions" | head -1 | sed 's/\.json$//')"
if [ -z "${REVISION}" ]; then
  echo "no revision was sealed — the capture path produced nothing" >&2
  exit 1
fi
echo "sealed revision: ${REVISION}"

# --- 3) collect → verify → receipt -------------------------------------------
"${PY}" "${REPO_ROOT}/scripts/collect_edge_evidence.py" \
  --edge-dir "${EVIDENCE}" --revision "${REVISION}" \
  --core-url "${CORE_URL}" --token "${TOKEN}"
