#!/usr/bin/env bash
# 가상 Manifold(호스트 Edge 대역) ↔ 가상 RC MOP 왕복 확인.
#
# 실제 Manifold 3가 연결되지 않은 동안에도 MOP 프로토콜·계약 계층을
# 실행 가능한 상태로 확인한다. Edge 쪽은 **실제 C++ 코드**(edge/tools/mop_server)이고,
# RC 쪽은 harness의 MOP 구현을 쓴다(스크립트 scripts/mop_edge_probe.py).
#
# 통과 조건(모두 만족해야 PASS):
#   1) MISSION_PREPARE  → MISSION_PREPARED
#   2) MISSION_APPLY    → MISSION_APPLIED
#   3) SESSION_START    → ACK
#   4) MISSION_STATUS_QUERY → MISSION_STATUS + runtime_state + config_applied + input_state
#   5) SESSION_STOP     → ACK
#   6) 재전송은 **같은 msg_id**로 온다(수신측이 msg_id로 중복 제거해야 한다)
#      — 프로브가 첫 PREPARED의 ACK를 일부러 보류해 실제 재전송을 관측한다.
#   7) SIGTERM은 accept() 대기 중에도 프로세스를 끝낸다(고아 서버 금지)
#
# 사용: bash scripts/mop_loopback_check.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="${MOP_PORT:-5099}"
PY="${MOP_PYTHON:-${ROOT}/.venv/bin/python}"
SERVER="${MOP_SERVER:-${ROOT}/edge/build/mop_server}"
if [[ -z "${TMPDIR:-}" && -d /tmp/opencode && -w /tmp/opencode ]]; then
  export TMPDIR=/tmp/opencode
fi
LOG="$(mktemp -t mop_loopback.XXXXXX.log)" || exit 1

# Only the child started by this shell is eligible for cleanup.
SRV=""
stop_server() {
  [[ -n "$SRV" ]] || return 0
  # jobs -pr lists only running children still owned by this shell.
  if jobs -pr | grep -qxF "$SRV"; then
    kill "$SRV" 2>/dev/null || true
    for ((i=0; i<8; i++)); do
      if ! jobs -pr | grep -qxF "$SRV"; then
        wait "$SRV" 2>/dev/null || true
        SRV=""
        return 0
      fi
      sleep 0.25
    done
    if jobs -pr | grep -qxF "$SRV"; then
      kill -KILL "$SRV" 2>/dev/null || true
      wait "$SRV" 2>/dev/null || true
      SRV=""
      return 1
    fi
  fi
  wait "$SRV" 2>/dev/null || true
  SRV=""
}
cleanup() { stop_server || true; rm -f -- "$LOG"; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
fail() { echo "FAIL: $1"; exit 1; }

if [[ ! -x "$SERVER" ]]; then
  echo "FAIL: $SERVER 없음 — MOP_SERVER 또는 edge 호스트 빌드 확인"
  exit 1
fi
if [[ ! -x "$PY" ]]; then
  echo "FAIL: Python 실행기 없음: $PY (MOP_PYTHON 또는 scripts/setup_env.sh 확인)"
  exit 1
fi

"$SERVER" --port "$PORT" --bind-address 127.0.0.1 --max-seconds 60 >"$LOG" 2>&1 &
SRV=$!
sleep 2
# 서버가 실제로 리슨했는지 확인한다. bind 실패(포트 점유)는 즉시 죽음으로
# 보이며, 그대로 두면 프로브가 다른 프로세스와 대화하게 된다.
if ! kill -0 "$SRV" 2>/dev/null; then
  echo "FAIL: mop_server가 시작 직후 죽었다(포트 충돌 가능):"
  cat "$LOG"
  rm -f "$LOG"
  exit 1
fi
if ! grep -q "listening on" "$LOG" 2>/dev/null; then
  echo "FAIL: mop_server가 2초 안에 리슨하지 않았다:"
  cat "$LOG"
  stop_server
  rm -f "$LOG"
  exit 1
fi

OUT="$("$PY" "${ROOT}/scripts/mop_edge_probe.py" --port "$PORT" --observe-retransmission 2>&1)" || { echo "$OUT"; fail "probe failed"; }
echo "$OUT"

has() { grep -qF "$1" <<<"$OUT"; }

has "EDGE-> MISSION_PREPARED" || fail "MISSION_PREPARED 없음"
has "EDGE-> MISSION_APPLIED"   || fail "MISSION_APPLIED 없음"
has "EDGE-> MISSION_STATUS"    || fail "MISSION_STATUS 없음"
has "runtime_state="           || fail "runtime_state 없음"
has "config_applied = True"    || fail "config_applied 없음"
has "input_state ="            || fail "input_state 없음"
has "processed_frame_count = 0" || fail "processed_frame_count 없음(호스트 대역은 0이 정직)"

# 재전송은 같은 msg_id여야 한다(중복 제거 규칙).
DUP_IDS="$(grep -oE 'msg_id=[0-9a-f]{8}' <<<"$OUT" | sort | uniq -d | wc -l)"
if [[ "$DUP_IDS" -eq 0 ]]; then
  fail "재전송이 같은 msg_id로 오지 않음(계약 위반)"
fi

if ! stop_server; then
  echo "FAIL: mop_server가 SIGTERM에도 안 죽는다(accept() 고립 — 고아 위험)"
  rm -f "$LOG"
  exit 1
fi
rm -f "$LOG"
echo "PASS: 가상 Manifold MOP 왕복 (PREPARE→PREPARED, APPLY→APPLIED, START, STATUS, STOP)"
exit 0
