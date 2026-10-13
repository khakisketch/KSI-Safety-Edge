#!/usr/bin/env bash
# A9-4 실기 하루 키트 — RC Plus 2 측 실행 도구 (빌드·설치·기록·증거 수집).
#
# 목적: A9-4(MSDK 지상 세션)를 하루 안에 끝내기 위한 실행 경로.
#   - 런북: docs/verification/a9-4-device-day-runbook.md
#   - Manifold(PSDK/DPK) 측 절차: docs/verification/h1-dpk-session-checklist.md
#   - 단계 정의: docs/verification/a9-msdk-integration-plan.md §4
#
# 이 스크립트는 제품 코드를 바꾸지 않는다. 빌드 산출물·증거만 만든다.
# 비밀(DJI 키·Core 토큰)은 절대 출력하지 않는다 — 키는 존재/길이만 표시한다.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_RC="$REPO_ROOT/android-rc"
APP_ID="ksi.safetyedge.rc"
APK_DIR="$ANDROID_RC/rc-app/build/outputs/apk/device/debug"
EVIDENCE_ROOT="$REPO_ROOT/docs/verification/evidence"

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export QEMU_LD_PREFIX="${QEMU_LD_PREFIX:-$HOME/Android/sysroot/root}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

LOCAL_PROPS="$ANDROID_RC/local.properties"
MANIFOLD_HOST_ALIAS="ksi-manifold"

die() { echo "오류: $*" >&2; exit 1; }
note() { echo "· $*"; }
adb_ready() { command -v adb >/dev/null 2>&1; }
device_line() { adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}'; }
aapt2_bin() { ls -1 "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | tail -1; }
apk_path() { ls -1 "$APK_DIR"/*.apk 2>/dev/null | head -1; }

require_device() {
  adb_ready || die "adb가 없습니다 (ANDROID_HOME/platform-tools 확인)"
  adb start-server >/dev/null 2>&1 || true
  local n
  n="$(device_line | wc -l)"
  if [ "$n" -eq 0 ]; then
    die "연결된 장치가 없습니다. RC Plus 2 USB + 개발자 모드/adb 디버깅 허용이 필요합니다 (adb devices)."
  fi
  if [ "$n" -gt 1 ]; then
    die "장치가 2대 이상입니다. 하나만 연결하세요: $(device_line | tr '\n' ' ')"
  fi
}

latest_evidence_dir() {
  if [ -n "${A9_4_EVIDENCE:-}" ]; then
    [ -d "$A9_4_EVIDENCE" ] || die "A9_4_EVIDENCE 디렉터리가 없습니다: $A9_4_EVIDENCE"
    printf '%s\n' "$A9_4_EVIDENCE"
    return 0
  fi
  local dir
  dir="$(find "$EVIDENCE_ROOT" -maxdepth 1 -type d -name '*-a9-4*' 2>/dev/null | sort | tail -1)"
  [ -n "$dir" ] || die "증거 폴더가 없습니다. 먼저 'init'을 실행하세요."
  printf '%s\n' "$dir"
}

key_value() {
  # local.properties의 dji.apiKey — 값은 절대 출력하지 않는다(길이만 사용).
  [ -f "$LOCAL_PROPS" ] || { printf ''; return 0; }
  sed -n 's/^[[:space:]]*dji\.apiKey[[:space:]]*=[[:space:]]*//p' "$LOCAL_PROPS" | head -1 | tr -d '\r'
}

cmd_init() {
  local stamp dir
  stamp="$(date +%F)"
  dir="$EVIDENCE_ROOT/${stamp}-a9-4"
  mkdir -p "$dir"
  if [ ! -f "$dir/notes.md" ]; then
    cat > "$dir/notes.md" <<'EOF'
# A9-4 지상 세션 — 진행 노트 (세션 중 계속 추가)

- 세션 시작: / 종료:
- 참여자:
- 장치: RC Plus 2 (serial: ) · Manifold (ssh ksi-manifold):
- DJI 키 주입: (build 출력 그대로) / APK sha256:

## 단계별 기록 (a9-msdk-integration-plan §4)

| # | 단계 | 결과 | 증거 파일 | 비고(실패는 그대로) |
|---|---|---|---|---|
| 1 | 설치·기동 | | | |
| 1b | 최소 SDK 초기화 | | | |
| 2 | MSDK 등록 | | | |
| 3 | RC↔Manifold MOP | | | |
| 4 | 임무 적용 | | | |
| 5 | 로컬 저장·재시작 | | | |
| 6 | 통신 복구 | | | |
| 7 | 업로드(WorkManager) | | | |
| 8 | 시계 기록 | | | |
| 9 | 실패 주입 | | | |

## 관찰 (UI 재보정 입력)
- 콕핏 무스크롤·글자 크기·장갑 터치 (wm size/density는 device-facts.txt):
- 햇빛 가독성:

## 커밋 전 스크럽 체크
- [ ] 로그/스크린샷에 DJI 키·Core 토큰 없음
- [ ] 실패를 성공으로 바꾸지 않았음
EOF
  fi
  if [ ! -f "$dir/README.md" ]; then
    cat > "$dir/README.md" <<EOF
# A9-4 지상 세션 증거 — ${stamp} (작성 중)

정의: docs/verification/a9-msdk-integration-plan.md §4 (10단계).
실행: scripts/rc_device_day.sh · docs/verification/a9-4-device-day-runbook.md.

| 증거 파일 | 무엇 |
|---|---|
| notes.md | 세션 노트(단계 표·관찰) |
| preflight.txt | 호스트·장치·키·Manifold 점검 출력 |
| device-facts.txt | 장치/시계/화면(밀도) 사실 |
| logcat-*.txt | 앱/DJI 로그 |
| crash-buffer.txt | 크래시 버퍼(있는 경우) |
| shot-*.png | 화면 증거 |
| rc-store.tar | 앱 로컬 저장소 회수(read-only) |

세션 후: notes.md 마감 → 스크럽 체크 → (필요 시) 이 폴더 커밋.
EOF
  fi
  note "증거 폴더: ${dir#"$REPO_ROOT"/}"
  note "다음: preflight → build → install → launch → facts → shot 01-launch"
}

cmd_preflight() {
  cmd_init >/dev/null
  local dir
  dir="$(latest_evidence_dir)"
  {
    echo "# preflight — host $(date '+%F %T %Z')"
    echo "arch: $(uname -m)"
    if adb_ready; then echo "adb: $(adb version | head -1)"; else echo "adb: 없음(!)"; fi
    local a2
    a2="$(aapt2_bin)"
    echo "aapt2: ${a2:-없음(!)}"
    if [ -e "$QEMU_LD_PREFIX/lib64/ld-linux-x86-64.so.2" ]; then
      echo "qemu sysroot: OK ($QEMU_LD_PREFIX)"
    else
      echo "qemu sysroot: 없음(!) — aapt2 실패 가능"
    fi
    local key
    key="$(key_value)"
    if [ -n "$key" ]; then
      echo "DJI 키(local.properties): 있음 (길이 ${#key})"
    else
      echo "DJI 키(local.properties): 없음 — device 빌드는 KEY_MISSING으로 기동(정상 폴백)"
    fi
    local apk
    apk="$(apk_path)"
    if [ -n "$apk" ]; then
      echo "APK: ${apk#"$REPO_ROOT"/} ($(stat -c%s "$apk") bytes)"
    else
      echo "APK: 아직 없음 (build 필요)"
    fi
    echo
    echo "# preflight — devices"
    if adb_ready; then
      adb start-server >/dev/null 2>&1 || true
      local n
      n="$(device_line | wc -l)"
      if [ "$n" -eq 0 ]; then
        echo "장치: 없음 — RC USB 연결 + 개발자 모드/adb 허용 필요"
      else
        adb devices | sed -n '2,$p'
        echo "모델: $(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true) / Android: $(adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)"
      fi
    fi
    echo
    echo "# preflight — manifold (read-only)"
    local mh
    mh="$(ssh -G "$MANIFOLD_HOST_ALIAS" 2>/dev/null | awk '$1=="hostname"{print $2; exit}')"
    if [ -n "$mh" ] && timeout 3 bash -c "exec 3<>/dev/tcp/$mh/22" 2>/dev/null; then
      echo "Manifold($mh):22 — 접속 가능 (DPK 절차: h1-dpk-session-checklist.md)"
    else
      echo "Manifold(${mh:-미설정}): 접속 불가 — MOP/DPK 왕복 전 확인 필요"
    fi
  } | tee "$dir/preflight.txt"
  note "preflight 저장: ${dir#"$REPO_ROOT"/}/preflight.txt"
}

cmd_build() {
  ( cd "$ANDROID_RC" && ./gradlew --console=plain :rc-app:assembleDeviceDebug )
  local apk
  apk="$(apk_path)"
  [ -n "$apk" ] || die "APK를 찾지 못했습니다: $APK_DIR"
  echo "APK: ${apk#"$REPO_ROOT"/}"
  echo "size: $(stat -c%s "$apk") bytes"
  echo "sha256: $(sha256sum "$apk" | cut -d' ' -f1)"
  cmd_check "$apk"
}

cmd_check() {
  local apk="${1:-$(apk_path)}"
  [ -n "$apk" ] || die "APK가 없습니다. build를 먼저 실행하세요."
  local a2
  a2="$(aapt2_bin)"
  [ -n "$a2" ] || die "aapt2를 찾지 못했습니다 (ANDROID_HOME/build-tools/*/aapt2)."
  local dump val
  dump="$("$a2" dump xmltree --file AndroidManifest.xml "$apk" 2>/dev/null || true)"
  if [ -z "$dump" ]; then
    echo "DJI 키 주입: 판정 불가 (aapt2 dump 실패 — qemu sysroot 확인)"
    return 0
  fi
  # aapt2 prints attributes as  android:value(0x01010024)="VALUE"
  val="$(printf '%s\n' "$dump" | grep -A2 'com.dji.sdk.API_KEY' | grep -m1 -o 'android:value[^=]*="[^"]*"' | sed -e 's/.*="//' -e 's/"$//' || true)"
  if [ -n "$val" ]; then
    echo "DJI 키 주입: 있음 (길이 ${#val})"
  else
    echo "DJI 키 주입: 없음/빈 값 — 실기 등록을 하려면 local.properties의 dji.apiKey 설정 후 재빌드"
  fi
}

cmd_install() {
  local apk
  require_device
  apk="$(apk_path)"
  [ -n "$apk" ] || die "APK가 없습니다. build를 먼저 실행하세요."
  adb install -r "$apk"
}

cmd_launch() {
  require_device
  adb shell am start -n "$APP_ID/.MainActivity"
}

cmd_stop() {
  require_device
  adb shell am force-stop "$APP_ID"
  note "force-stop 완료"
}

cmd_uninstall() {
  require_device
  adb uninstall "$APP_ID"
}

cmd_facts() {
  require_device
  local dir out
  dir="$(latest_evidence_dir)"
  out="$dir/device-facts.txt"
  {
    echo "# device facts — $(date -u +%FT%TZ) host"
    for p in ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.product.cpu.abi ro.build.fingerprint persist.sys.timezone; do
      printf '%s=%s\n' "$p" "$(adb shell getprop "$p" | tr -d '\r')"
    done
    echo "--- date (local / utc)"
    adb shell 'date; date -u' | tr -d '\r'
    echo "--- wm size / density (UI 재보정 입력)"
    adb shell 'wm size; wm density' | tr -d '\r'
    echo "--- battery"
    adb shell dumpsys battery | sed -n '1,12p' | tr -d '\r'
    echo "--- storage"
    adb shell 'df -h /data | tail -2' | tr -d '\r'
    echo "--- package (installed?)"
    adb shell "pm list packages | grep $APP_ID || echo 'not installed'" | tr -d '\r'
    adb shell "dumpsys package $APP_ID | grep -E 'versionName|firstInstallTime|lastUpdateTime' || true" | tr -d '\r'
    echo "--- dji props"
    adb shell 'getprop | grep -i dji | head -20' | tr -d '\r'
  } > "$out" 2>&1
  note "저장: ${out#"$REPO_ROOT"/} ($(wc -l < "$out") lines)"
  sed -n '1,24p' "$out"
}

cmd_shot() {
  require_device
  local dir name out
  dir="$(latest_evidence_dir)"
  name="${1:-shot-$(date +%H%M%S)}"
  out="$dir/$name.png"
  adb exec-out screencap -p > "$out"
  [ -s "$out" ] || die "스크린샷이 비었습니다: $out"
  note "저장: ${out#"$REPO_ROOT"/} ($(stat -c%s "$out") bytes)"
}

cmd_log_start() {
  require_device
  local dir pid out
  dir="$(latest_evidence_dir)"
  pid="$(adb shell pidof -s "$APP_ID" 2>/dev/null | tr -d '\r' || true)"
  out="$dir/logcat-$(date +%H%M%S).txt"
  if [ -n "$pid" ]; then
    nohup adb logcat --pid="$pid" -v threadtime >"$out" 2>&1 &
    note "앱 PID $pid 로그 기록 시작: ${out#"$REPO_ROOT"/}"
  else
    nohup adb logcat -v threadtime >"$out" 2>&1 &
    note "앱 미실행 — 전체 로그 기록(권장: launch 후 log-start): ${out#"$REPO_ROOT"/}"
  fi
  echo $! > "$dir/.logcat.pid"
}

cmd_log_stop() {
  local dir
  dir="$(latest_evidence_dir)"
  if [ -f "$dir/.logcat.pid" ]; then
    kill "$(cat "$dir/.logcat.pid")" 2>/dev/null || true
    rm -f "$dir/.logcat.pid"
    note "로그 기록 중지"
  else
    note "기록 중인 로그가 없습니다"
  fi
}

cmd_log_crash() {
  require_device
  local dir
  dir="$(latest_evidence_dir)"
  adb logcat -d -b crash > "$dir/crash-buffer.txt" 2>&1 || true
  note "저장: ${dir#"$REPO_ROOT"/}/crash-buffer.txt"
}

cmd_pull_store() {
  require_device
  local dir out
  dir="$(latest_evidence_dir)"
  out="$dir/rc-store.tar"
  if adb exec-out run-as "$APP_ID" tar -cf - files/rc-store > "$out" 2>/dev/null && tar -tf "$out" >/dev/null 2>&1; then
    note "저장: ${out#"$REPO_ROOT"/} ($(tar -tf "$out" | wc -l) entries)"
  else
    rm -f "$out"
    adb exec-out run-as "$APP_ID" sh -c 'ls -laR files/rc-store 2>/dev/null || ls -laR files' > "$dir/rc-store-listing.txt" 2>&1 || true
    note "tar 실패 — 목록만 저장: ${dir#"$REPO_ROOT"/}/rc-store-listing.txt"
  fi
}

cmd_manifold() {
  local mh
  mh="$(ssh -G "$MANIFOLD_HOST_ALIAS" 2>/dev/null | awk '$1=="hostname"{print $2; exit}')"
  [ -n "$mh" ] || die "ssh 별칭 $MANIFOLD_HOST_ALIAS 를 찾을 수 없습니다."
  if ! timeout 3 bash -c "exec 3<>/dev/tcp/$mh/22" 2>/dev/null; then
    die "Manifold($mh) 접속 불가 — 장치 전원/네트워크 확인."
  fi
  ssh "$MANIFOLD_HOST_ALIAS" 'ip -br link | grep -E "l4tbr0|rndis0" || true; echo ---; dji_app_ctl list 2>/dev/null | head -10 || echo "dji_app_ctl 조회 실패"'
}

cmd_help() {
  cat <<'USAGE'
A9-4 실기 하루 키트 (RC 측)

  init            오늘 날짜 증거 폴더 생성 (docs/verification/evidence/<date>-a9-4/)
  preflight       호스트·장치·DJI 키·Manifold 접속 점검 → 증거 폴더에 저장
  build           device APK 빌드 + DJI 키 주입 확인
  check           (빌드된) APK의 DJI 키 주입만 확인
  install         APK 설치 (adb install -r)
  launch / stop / uninstall
  facts           장치 사실(모델·SDK·시계·wm 밀도·저장공간) → device-facts.txt
  shot [NAME]     스크린샷 → shot-*.png
  log-start/stop  앱 로그 기록 시작/중지 (앱 실행 중이면 PID 한정)
  log-crash       크래시 버퍼 덤프
  pull-store      앱 로컬 저장소(files/rc-store) 회수
  manifold        Manifold 접속 확인 + DPK 목록(읽기 전용)

환경: A9_4_EVIDENCE=<dir> 로 증거 폴더를 지정할 수 있습니다.
런북: docs/verification/a9-4-device-day-runbook.md
USAGE
}

case "${1:-help}" in
  init) cmd_init ;;
  preflight) cmd_preflight ;;
  build) cmd_build ;;
  check) cmd_check ;;
  install) cmd_install ;;
  launch) cmd_launch ;;
  stop) cmd_stop ;;
  uninstall) cmd_uninstall ;;
  facts) cmd_facts ;;
  shot) shift; cmd_shot "${1:-}" ;;
  log-start) cmd_log_start ;;
  log-stop) cmd_log_stop ;;
  log-crash) cmd_log_crash ;;
  pull-store) cmd_pull_store ;;
  manifold) cmd_manifold ;;
  help|-h|--help) cmd_help ;;
  *) cmd_help; exit 1 ;;
esac
