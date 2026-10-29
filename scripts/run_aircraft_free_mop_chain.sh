#!/usr/bin/env bash
# 기체 없이 "호스트 Edge(실제 엔진) → MOP → 가상 RC → 중앙 보관 영수증" 체인.
#
# 실제 구성요소: SessionRunner(장치와 동일) · MOP 프로토콜 · 원본 캡처 ·
# 봉인(revision) · PC 수집기 · Core 검증·영수증.
# [SIMULATED]인 것: 영상(합성 RGB)과 탐지(stub detector) — 성능 주장이 아니다.
#
#   bash scripts/run_aircraft_free_mop_chain.sh \
#     --core-url http://127.0.0.1:8124/api/v1/edge --token tok-orgA-rc
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="${REPO_ROOT}/.venv/bin/python"
CORE_URL=""
TOKEN=""
WORK=""
PORT=5062
FLIGHT="11111111-2222-4333-8444-555555555555"
# ASan/UBSan build of the same code. The device toolchain (GCC 9.4) crashed on
# a lifetime bug that the host build (GCC 13) survived by luck — so the
# sanitiser build is the regression guard, not the plain one.
USE_ASAN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --core-url) CORE_URL="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --work-dir) WORK="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --asan) USE_ASAN=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [ -z "${CORE_URL}" ] || [ -z "${TOKEN}" ]; then
  echo "usage: $0 --core-url URL --token TOKEN [--work-dir DIR] [--port N]" >&2
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

# --- 1) 합성 코퍼스([SIMULATED] 영상) ---------------------------------------
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
PY

# --- 2) 호스트 Edge(실제 엔진) + 가상 RC -------------------------------------
if [ "${USE_ASAN}" = "1" ]; then
  if [ ! -x "${REPO_ROOT}/edge/build-asan/mop_server" ]; then
    echo "asan build missing: cmake -S edge -B edge/build-asan -DCMAKE_BUILD_TYPE=Debug -DKSI_SAFETY_EDGE_ENABLE_SANITIZERS=ON && cmake --build edge/build-asan -j4" >&2
    exit 1
  fi
  MOP_SERVER="${REPO_ROOT}/edge/build-asan/mop_server"
  export ASAN_OPTIONS="detect_leaks=0"
else
  MOP_SERVER="${REPO_ROOT}/edge/build/mop_server"
fi
"${MOP_SERVER}" \
  --port "${PORT}" --bind-address 127.0.0.1 --flight-id "${FLIGHT}" \
  --replay-manifest "${CORPUS}/manifest.txt" \
  --evidence-dir "${EVIDENCE}" --stub-detector --max-seconds 30 \
  > "${WORK}/edge.log" 2>&1 &
EDGE_PID=$!
cleanup_edge() {
  if jobs -pr | grep -qxF "$EDGE_PID"; then
    kill "$EDGE_PID" 2>/dev/null || true
    wait "$EDGE_PID" 2>/dev/null || true
  fi
}
trap cleanup_edge EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
sleep 2
if ! jobs -pr | grep -qxF "$EDGE_PID" || ! grep -q 'listening on 127.0.0.1:' "${WORK}/edge.log"; then
  echo "mop_server failed to listen (port may be occupied); see ${WORK}/edge.log" >&2
  exit 1
fi
"${PY}" "${REPO_ROOT}/scripts/mop_edge_probe.py" --port "${PORT}" \
  | tail -3

# --- 3) 봉인된 revision 을 중앙으로 -----------------------------------------
for _ in $(seq 1 40); do
  if compgen -G "${EVIDENCE}/revisions/*.json" > /dev/null; then break; fi
  sleep 1
done
if grep -q "AddressSanitizer" "${WORK}/edge.log" 2>/dev/null; then
  echo "AddressSanitizer reported an error — the Edge run is NOT clean:" >&2
  grep -A 20 "ERROR: AddressSanitizer" "${WORK}/edge.log" | head -25 >&2
  exit 1
fi
REVISION="$(ls "${EVIDENCE}/revisions" 2>/dev/null | head -1 | sed 's/\.json$//')"
if [ -z "${REVISION}" ]; then
  echo "no revision was sealed — see ${WORK}/edge.log" >&2
  exit 1
fi
echo "sealed revision: ${REVISION}"
"${PY}" "${REPO_ROOT}/scripts/collect_edge_evidence.py" \
  --edge-dir "${EVIDENCE}" --revision "${REVISION}" \
  --core-url "${CORE_URL}" --token "${TOKEN}"
