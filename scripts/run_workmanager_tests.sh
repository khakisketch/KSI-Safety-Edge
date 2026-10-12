#!/usr/bin/env bash
# =============================================================================
# Run the Robolectric WorkManager tests (:rc-app SyncWorkerTest) on x86_64.
#
# Why: Robolectric has NO Linux/aarch64 native runtime (its SQLite shadow
# refuses to load — checked through 4.17), so on this arm64 build host the
# tests SKIP with an explicit message. This script executes them for real
# in a linux/amd64 container (qemu), which is also the repo's established
# x86_64 build path for APK packaging.
#
# Usage:  bash scripts/run_workmanager_tests.sh
# Evidence: rc-app/build/test-results/testDebugUnitTest/*.xml
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="${KSI_UTEST_IMAGE:-ksi-utest-amd64}"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/sdk}"
CACHE_VOL="ksi-utest-gradle-cache"
UID_GID="$(id -u):$(id -g)"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "building $IMAGE (linux/amd64)…"
  docker build -t "$IMAGE" - <<'DOCKERFILE'
FROM --platform=linux/amd64 ubuntu:24.04
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends \
      openjdk-17-jdk-headless unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
DOCKERFILE
fi

# The named cache volume is created root-owned; make it usable by this user.
docker volume inspect "$CACHE_VOL" >/dev/null 2>&1 || docker volume create "$CACHE_VOL" >/dev/null
docker run --rm --platform linux/amd64 -v "$CACHE_VOL":/gradle ubuntu:24.04 \
  chown -R "$UID_GID" /gradle >/dev/null

docker run --rm --platform linux/amd64 \
  -u "$UID_GID" \
  -v "$REPO":/work \
  -v "$SDK":/sdk:ro \
  -v "$CACHE_VOL":/gradle \
  -e HOME=/tmp/home \
  -e GRADLE_USER_HOME=/gradle \
  -e ANDROID_SDK_ROOT=/sdk \
  -e ANDROID_HOME=/sdk \
  -w /work/android-rc \
  "$IMAGE" \
  bash -c 'mkdir -p /tmp/home && ./gradlew :rc-app:testBenchDebugUnitTest --tests "ksi.safetyedge.rc.SyncWorkerTest" --no-daemon'

echo
echo "VM test XMLs:"
ls -la "$REPO/android-rc/rc-app/build/test-results/testDebugUnitTest/" | grep -i SyncWorker || true
