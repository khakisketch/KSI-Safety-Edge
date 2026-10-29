#!/usr/bin/env bash
# Release scrub check — G7 (deployment artifacts) hygiene for the RC field app.
#
# Usage:
#   bash scripts/release_scrub_check.sh            # static checks + build benchRelease APK + APK checks
#   SCRUB_FLAVOR=device bash scripts/release_scrub_check.sh  # actual MSDK deviceRelease artifact
#   SKIP_APK_BUILD=1 bash scripts/release_scrub_check.sh   # static checks only (APK checks SKIP)
#
# What it checks:
#   S1  versionCode/versionName are explicit in rc-app/build.gradle.kts (G7 version
#       policy: monotonic versionCode, semver-ish versionName) and are recorded below.
#   S2  release network_security_config (src/main) sets cleartextTrafficPermitted="false"
#       with system anchors only (G1: release blocks cleartext).
#   S3  the debug cleartext override lives ONLY under src/debug and can never leak
#       into a release variant (G1 partial: debug-only cleartext).
#   S4  no manifest source sets android:debuggable (debuggability must come only
#       from the build type), and the manifest references the network security config.
#   S5  no development endpoint defaults (127.0.0.1 / 10.0.2.2 / localhost / dev
#       token constants) in the release-compiled source sets (main/bench/device/
#       release). Test source sets are scanned separately and reported as INFO —
#       they are never packaged into the APK.
#   S6  the Core URL/token have no compiled-in defaults (empty until the operator
#       configures them on-device).
#   A1  benchRelease APK builds (unsigned local build; signing key is a G7 open item).
#   A2  APK badging versionCode/versionName match the Gradle values (recorded below).
#   A3  merged release manifest sets no android:debuggable.
#   A4  merged release network_security_config in the APK blocks cleartext (typed
#       dump, not just the source file) with no user-trust anchor.
#   A5  no dev endpoint literals in the release dex or packaged resources.
#
# Exit codes: 0 = all runnable checks PASS (warnings allowed); 1 = a check FAILED;
# 2 = static checks passed but artifact checks were explicitly skipped or an
# inspection tool was unavailable — INCOMPLETE, never PASS. A Gradle build
# failure is FAIL (exit 1), including compiler errors; never disguise it as SKIP.
#
# On failure: fix the flagged source (keep the fix in the listed source set), then
# re-run this script. Do NOT silence the check by deleting the assertion.
# Host note (aarch64): aapt2 is an x86_64 binary run via qemu-binfmt; this script
# applies the same QEMU_LD_PREFIX default as scripts/android_host_tests.sh.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
APP="$REPO/android-rc/rc-app"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export QEMU_LD_PREFIX="${QEMU_LD_PREFIX:-$HOME/Android/sysroot/root}"
SCRUB_FLAVOR="${SCRUB_FLAVOR:-bench}"
case "$SCRUB_FLAVOR" in
  bench) RELEASE_TASK="assembleBenchRelease" ;;
  device) RELEASE_TASK="assembleDeviceRelease" ;;
  *) printf 'Unsupported SCRUB_FLAVOR: %s (bench|device)\n' "$SCRUB_FLAVOR" >&2; exit 1 ;;
esac
pass=0; fail=0; skip=0; warn=0
FAILED=()
report() { # $1=PASS|FAIL|SKIP|WARN $2=id $3=message
  printf '[%s] %s: %s\n' "$1" "$2" "$3"
  case "$1" in
    PASS) pass=$((pass+1)) ;;
    FAIL) fail=$((fail+1)); FAILED+=("$2") ;;
    SKIP) skip=$((skip+1)) ;;
    WARN) warn=$((warn+1)) ;;
  esac
}

# -- S1: explicit version -------------------------------------------------------
GRADLE_FILE="$APP/build.gradle.kts"
VCODE="$(grep -E '^[[:space:]]*versionCode[[:space:]]*=' "$GRADLE_FILE" | head -1 | grep -oE '[0-9]+' || true)"
VNAME="$(grep -E '^[[:space:]]*versionName[[:space:]]*=' "$GRADLE_FILE" | head -1 | grep -oE '"[^"]+"' | tr -d '"' || true)"
if [ -n "${VCODE:-}" ] && [ -n "${VNAME:-}" ]; then
  report PASS S1 "versionCode=$VCODE versionName=$VNAME (recorded; policy: monotonic code, semver-ish name)"
else
  report FAIL S1 "versionCode/versionName not explicit in rc-app/build.gradle.kts"
fi

# -- S2: release network security config source ---------------------------------
MAIN_NSC="$APP/src/main/res/xml/network_security_config.xml"
if [ -f "$MAIN_NSC" ]; then
  if grep -q 'cleartextTrafficPermitted="false"' "$MAIN_NSC" \
    && ! grep -q 'cleartextTrafficPermitted="true"' "$MAIN_NSC"; then
    report PASS S2 "release NSC blocks cleartext (base-config false, no true override)"
  else
    report FAIL S2 "release NSC $MAIN_NSC must set cleartextTrafficPermitted=\"false\" only"
  fi
  if grep -q 'src="user"' "$MAIN_NSC"; then
    report FAIL S2 "release NSC trusts user certificates (debug-proxy anchor must stay debug-only)"
  else
    report PASS S2 "release NSC trusts system anchors only"
  fi
else
  report FAIL S2 "release NSC missing: $MAIN_NSC"
fi

# -- S3: debug override containment ----------------------------------------------
DEBUG_NSC="$APP/src/debug/res/xml/network_security_config.xml"
if [ -f "$DEBUG_NSC" ] && grep -q 'cleartextTrafficPermitted="true"' "$DEBUG_NSC"; then
  report PASS S3 "debug cleartext override present exactly where expected (src/debug only, G1 dev/field-laptop path)"
else
  report FAIL S3 "debug NSC override missing or changed — release/debug split must stay explicit"
fi
LEAK="$(find "$APP/src" -path '*res/xml/network_security_config.xml' ! -path "$APP/src/main/*" ! -path "$APP/src/debug/*" | head -5 || true)"
if [ -z "$LEAK" ]; then
  report PASS S3 "no network_security_config in any other source set (no override can leak into release)"
else
  if echo "$LEAK" | grep -q .; then
    for f in $LEAK; do
      if grep -q 'cleartextTrafficPermitted="true"' "$f"; then
        report FAIL S3 "cleartext-true NSC outside src/debug: $f"
      else
        report PASS S3 "extra NSC is cleartext-safe: $f"
      fi
    done
  fi
fi

# -- S4: manifests ----------------------------------------------------------------
if grep -rn 'android:debuggable' "$APP/src" 2>/dev/null | grep -q .; then
  report FAIL S4 "android:debuggable set in a manifest source (must come only from build type): $(grep -rln 'android:debuggable' "$APP/src" | tr '\n' ' ')"
else
  report PASS S4 "no manifest source sets android:debuggable"
fi
if grep -q 'android:networkSecurityConfig="@xml/network_security_config"' "$APP/src/main/AndroidManifest.xml"; then
  report PASS S4 "manifest references @xml/network_security_config"
else
  report FAIL S4 "manifest does not reference @xml/network_security_config"
fi

# -- S5: dev endpoint literals in release-compiled sources ------------------------
# Release-compiled sets: main + flavor sets (bench/device) + release (if present).
# Test-only sets (test, testBench, androidTest) are never packaged: INFO only.
DENY='127\.0\.0\.1|10\.0\.2\.2|localhost|KSI_DEV_TOKEN|dev-token|dev_token\s*=\s*"'
REL_SETS="main bench device release"
rel_hit=""
for s in $REL_SETS; do
  d="$APP/src/$s"
  [ -d "$d" ] || continue
  hit="$(grep -rniE "$DENY" "$d" 2>/dev/null || true)"
  [ -n "$hit" ] && rel_hit="${rel_hit}${s}: ${hit}"$'\n'
done
if [ -z "$rel_hit" ]; then
  report PASS S5 "no dev endpoint/token literals in release-compiled source sets (main/bench/device)"
else
  report FAIL S5 "dev literals in release-compiled sources: $rel_hit"
fi
test_hit="$( { for s in test testBench androidTest; do [ -d "$APP/src/$s" ] && grep -rniE "$DENY" "$APP/src/$s" 2>/dev/null; done; } || true)"
if [ -n "$test_hit" ]; then
  printf '[INFO] S5-test: dev literals confined to test-only sources (not packaged): %s\n' "$(echo "$test_hit" | head -3 | tr '\n' ';')"
else
  printf '[INFO] S5-test: no dev literals even in test sources\n'
fi

# -- S6: no compiled-in Core defaults ----------------------------------------------
if grep -q 'getString("core_url", "")' "$APP/src/main/java/ksi/safetyedge/rc/AppConfig.kt"; then
  report PASS S6 "Core URL default is empty (operator configures on-device; upload gated on coreConfigured)"
else
  report FAIL S6 "AppConfig core_url default is not empty — a default endpoint would ship in release"
fi
maybe="$(grep -rniE 'Bearer [A-Za-z0-9._-]+|token\s*=\s*"[A-Za-z0-9._-]{8,}"' "$APP/src/main" 2>/dev/null || true)"
if [ -n "$maybe" ]; then
  report FAIL S6 "possible hardcoded token in main: $maybe"
else
  report PASS S6 "no hardcoded token value in main source set"
fi

# -- A1: build the release APK ------------------------------------------------------
APK=""
if [ "${SKIP_APK_BUILD:-0}" = "1" ]; then
  report SKIP A1 "SKIP_APK_BUILD=1 — APK checks below are SKIPPED, not passed"
else
  mkdir -p /tmp/opencode
  BUILD_LOG="$(mktemp "/tmp/opencode/ksi-release-${SCRUB_FLAVOR}-build.XXXXXXXX.log")"
  if (cd "$REPO/android-rc" && ./gradlew ":rc-app:$RELEASE_TASK" --console=plain -q >"$BUILD_LOG" 2>&1); then
    APK="$(ls "$APP"/build/outputs/apk/"$SCRUB_FLAVOR"/release/*.apk 2>/dev/null | head -1 || true)"
    if [ -n "$APK" ]; then
      report PASS A1 "${SCRUB_FLAVOR}Release APK built: $(basename "$APK") ($(du -h "$APK" | cut -f1))"
    else
      report FAIL A1 "gradle succeeded but expected ${SCRUB_FLAVOR}Release APK was not produced"
    fi
  else
    report FAIL A1 "release build failed (see $BUILD_LOG); compiler/environment errors require diagnosis, not a skipped pass"
  fi
fi

# -- APK checks (need aapt2; x86_64 binary via qemu on aarch64) --------------------
if [ -n "$APK" ]; then
  AAPT="$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort | tail -1 || true)"
  if [ -z "$AAPT" ] || [ ! -x "$AAPT" ]; then
    report SKIP A2 "aapt2 not found under \$ANDROID_HOME/build-tools — APK checks SKIPPED, not passed"
  else
    # A2: badging versions match Gradle + are recorded
    BADGE="$("$AAPT" dump badging "$APK" 2>/dev/null | head -1 || true)"
    if echo "$BADGE" | grep -q "versionCode='$VCODE'" && echo "$BADGE" | grep -q "versionName='$VNAME'"; then
      report PASS A2 "APK versions match Gradle: $BADGE"
    else
      report FAIL A2 "APK version mismatch (gradle $VCODE/$VNAME): $BADGE"
    fi
    # A3: merged manifest — no debuggable; record cleartext flag
    MANIFEST_DUMP="$("$AAPT" dump xmltree "$APK" --file AndroidManifest.xml 2>/dev/null || true)"
    if [ -z "$MANIFEST_DUMP" ]; then
      report FAIL A3 "could not decode packaged manifest (not a pass)"
    elif echo "$MANIFEST_DUMP" | grep -qi 'debuggable'; then
      report FAIL A3 "merged release manifest sets android:debuggable"
    else
      report PASS A3 "merged release manifest sets no android:debuggable"
    fi
    if [ -z "$MANIFEST_DUMP" ]; then
      report FAIL A3 "cleartext manifest check unavailable (not a pass)"
    elif echo "$MANIFEST_DUMP" | grep -q 'usesCleartextTraffic.*=true'; then
      report FAIL A3 "merged release manifest allows cleartext; debug override leaked"
    else
      report PASS A3 "merged manifest does not default to cleartext"
    fi
    # A4: merged NSC content (typed dump of the single packaged NSC file)
    WORK="$(mktemp -d "/tmp/opencode/ksi-release-${SCRUB_FLAVOR}.XXXXXX")"
    trap 'rm -rf "$WORK"' EXIT
    (cd "$WORK" && unzip -o -q "$APK" 'res/*.xml' 'resources.arsc' 2>/dev/null || true)
    if ! ls "$WORK"/res/*.xml >/dev/null 2>&1 || [ ! -f "$WORK/resources.arsc" ]; then
      report FAIL A4 "could not unpack APK resources for NSC verification (not a pass)"
    else
    NSC_RES="$(grep -a -l 'network-security-config' "$WORK"/res/*.xml 2>/dev/null | head -1 || true)"
    if [ -n "$NSC_RES" ]; then
      NSC_DUMP="$("$AAPT" dump xmltree "$APK" --file "res/$(basename "$NSC_RES")" 2>/dev/null || true)"
      if echo "$NSC_DUMP" | grep -q 'cleartextTrafficPermitted=false'; then
        report PASS A4 "packaged NSC blocks cleartext: $(echo "$NSC_DUMP" | grep -E 'cleartextTrafficPermitted|src=' | tr '\n' ' ')"
      else
        report FAIL A4 "packaged NSC does not block cleartext: $NSC_DUMP"
      fi
      echo "$NSC_DUMP" | grep -q 'src="user"' \
        && report FAIL A4 "packaged NSC trusts user certificates" \
        || report PASS A4 "packaged NSC has no user trust anchor"
    else
      report FAIL A4 "no network-security-config found among packaged resources"
    fi
    fi
    # A5: dev literals in dex + packaged resources
    (cd "$WORK" && unzip -o -q "$APK" 'classes*.dex' 2>/dev/null || true)
    if ! ls "$WORK"/classes*.dex >/dev/null 2>&1; then
      report FAIL A5 "could not unpack dex for literal scan (not a pass)"
    else
    dex_hit="$(grep -a -o -E '127\.0\.0\.1|10\.0\.2\.2|localhost|KSI_DEV_TOKEN|dev-token' "$WORK"/classes*.dex 2>/dev/null | sort | uniq -c || true)"
    arsc_hit="$(grep -a -o -E '127\.0\.0\.1|10\.0\.2\.2|localhost|KSI_DEV_TOKEN|dev-token' "$WORK"/resources.arsc 2>/dev/null | sort | uniq -c || true)"
    if [ -z "$dex_hit" ]; then
      report PASS A5 "no dev endpoint/token literals in release dex"
    else
      report FAIL A5 "dev literals in release dex: $dex_hit"
    fi
    if [ -z "$arsc_hit" ]; then
      report PASS A5 "no dev endpoint/token literals in packaged resources"
    else
      report FAIL A5 "dev literals in packaged resources: $arsc_hit"
    fi
    fi
  fi
else
  for id in A2 A3 A4 A5; do report SKIP "$id" "no release APK — SKIPPED, not passed"; done
fi

# -- summary -------------------------------------------------------------------------
printf '\nrelease scrub: %d PASS, %d WARN, %d FAIL, %d SKIP\n' "$pass" "$warn" "$fail" "$skip"
if [ "$fail" -gt 0 ]; then
  printf 'RESULT: FAIL (%s)\n' "$(IFS=,; echo "${FAILED[*]}")"
  exit 1
fi
if [ "$skip" -gt 0 ]; then
  printf 'RESULT: INCOMPLETE — artifact checks SKIPPED/unavailable; static checks passed. Not a PASS.\n'
  exit 2
fi
printf 'RESULT: PASS\n'
