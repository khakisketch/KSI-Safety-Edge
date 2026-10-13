#!/usr/bin/env bash
# Android RC host unit tests on this aarch64 machine.
#
# Why the extra env: the Android SDK's aapt2 is an x86_64 binary; this host
# runs it through qemu-binfmt with the x86_64 sysroot installed at
# ~/Android/sysroot/root (QEMU_LD_PREFIX). Without QEMU_LD_PREFIX the resource
# task fails with "AAPT2 ... Daemon startup failed".
#
# Known limit: the six WorkManager/Robolectric tests SKIP on aarch64 (no
# Robolectric native runtime). CI (.github/workflows/ci.yml, x86_64 runner)
# executes them for real. Everything else runs here.
#
# Usage:  bash scripts/android_host_tests.sh [gradle task ...]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export QEMU_LD_PREFIX="${QEMU_LD_PREFIX:-$HOME/Android/sysroot/root}"

if [ ! -e "$QEMU_LD_PREFIX/lib64/ld-linux-x86-64.so.2" ]; then
  echo "warning: qemu sysroot loader not found under $QEMU_LD_PREFIX (aapt2 may fail)" >&2
fi

TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then
  TASKS=(
    :rc-ports:test
    :rc-core:test
    :rc-msdk:testDebugUnitTest
    :rc-app:testBenchDebugUnitTest
    :rc-app:testDeviceDebugUnitTest
  )
fi

cd "$REPO/android-rc"
./gradlew --console=plain "${TASKS[@]}"
