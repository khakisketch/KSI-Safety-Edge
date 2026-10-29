#!/usr/bin/env bash
# DJI third-party 자산 복구·검증 (로컬 고정, 커밋 금지).
#
#   status   현재 고정된 자산과 버전/해시 확인
#   fetch    UX SDK Beta(GitHub, MIT) + PSDK(Manifold SSH) 복구
#   verify   고정 해시와 일치하는지 확인
#
# DJI 자산은 커밋하지 않는다(.gitignore). 이 스크립트가 "재현 가능한 복구"를 담당한다.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DST="${ROOT}/third_party/dji"
UX_REPO="https://github.com/dji-sdk/Mobile-UXSDK-Beta-Android.git"
UX_DIR="${DST}/uxsdk-beta-android"
PSDK_DIR="${DST}/psdk-3.16.0"
PSDK_VERSION="3.16.0"
# 고정 기준: 2026-10-21에 고정한 UX SDK Beta 커밋
UX_PIN="ed5ca45d118efbada6716f372b96dc5fe11e0620"

say() { echo "$*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

cmd_status() {
  say "=== DJI 자산 상태 ($DST) ==="
  if [ -d "$PSDK_DIR" ]; then
    local v
    v="$(awk '/DJI_VERSION_MAJOR/{maj=$3} /DJI_VERSION_MINOR/{min=$3} /DJI_VERSION_MODIFY/{mod=$3} END{printf "%s.%s.%s", maj, min, mod}' "$PSDK_DIR/psdk_lib/include/dji_version.h" 2>/dev/null || echo '?')"
    say "PSDK:      있음  ($PSDK_DIR)  ${v}"
    [ -f "$PSDK_DIR/psdk_lib/lib/aarch64-linux-gnu-gcc/libpayloadsdk.a" ] &&
      say "            libpayloadsdk.a: $(sha256sum "$PSDK_DIR/psdk_lib/lib/aarch64-linux-gnu-gcc/libpayloadsdk.a" | cut -c1-16)…"
  else
    say "PSDK:      없음  (fetch 필요)"
  fi
  if [ -d "$UX_DIR/.git" ]; then
    say "UX SDK Beta: 있음  commit $(git -C "$UX_DIR" rev-parse --short HEAD 2>/dev/null)  ($(du -sh "$UX_DIR" | cut -f1))"
  else
    say "UX SDK Beta: 없음  (fetch 필요)"
  fi
  say "MSDK v5:    Gradle이 Maven Central에서 자동 획득 (com.dji:dji-sdk-v5-aircraft:5.18.0)"
}

cmd_fetch() {
  mkdir -p "$DST"
  if [ ! -d "$UX_DIR/.git" ]; then
    say "UX SDK Beta 복구 (shallow clone)…"
    git clone --depth 1 "$UX_REPO" "$UX_DIR" || die "clone 실패"
  fi
  if [ -d "$UX_DIR/.git" ]; then
    git -C "$UX_DIR" fetch --depth 1 origin "$UX_PIN" >/dev/null 2>&1 || true
    git -C "$UX_DIR" checkout -q "$UX_PIN" 2>/dev/null &&
      say "  pin 고정: $UX_PIN" || say "  경고: pin checkout 실패(네트워크/오프라인)"
  fi
  if [ ! -d "$PSDK_DIR" ]; then
    say "PSDK ${PSDK_VERSION} 복구 (Manifold 3에서 회수)…"
    ssh -o ConnectTimeout=8 ksi-manifold \
      'tar -C / -czf - open_app/psdk 2>/dev/null || tar -C /open_app/psdk -czf - .' \
      | tar -xz -C "$DST" --transform 's,^open_app/psdk,psdk-3.16.0,' 2>/dev/null ||
      die "PSDK 회수 실패 — Manifold가 꺼져 있으면 전원 ON + LAN 연결 후 재시도"
    say "  복구 완료"
  fi
  cmd_status
}

cmd_verify() {
  local ok=0
  if [ -d "$UX_DIR/.git" ]; then
    local h; h="$(git -C "$UX_DIR" rev-parse HEAD 2>/dev/null)"
    if [ "$h" = "$UX_PIN" ]; then say "UX SDK Beta: pin 일치 ($UX_PIN)"; else say "UX SDK Beta: pin 불일치 (현재 $h, 기준 $UX_PIN)"; ok=1; fi
  else
    say "UX SDK Beta: 없음"; ok=1
  fi
  if [ -f "$PSDK_DIR/psdk_lib/lib/aarch64-linux-gnu-gcc/libpayloadsdk.a" ]; then
    say "PSDK: 정적 라이브러리 존재 ($(sha256sum "$PSDK_DIR/psdk_lib/lib/aarch64-linux-gnu-gcc/libpayloadsdk.a" | cut -c1-16)…)"
  else
    say "PSDK: 정적 라이브러리 없음"; ok=1
  fi
  return $ok
}

case "${1:-status}" in
  status) cmd_status ;;
  fetch)  cmd_fetch ;;
  verify) cmd_verify ;;
  *) echo "사용법: bash scripts/dji_assets.sh [status|fetch|verify]" >&2; exit 2 ;;
esac
