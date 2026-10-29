# 개발 환경과 다른 컴퓨터로 인계

이 안내는 Linux 호스트에서 장비 없이 개발·테스트하는 경로다. 저장소 루트에서 실행한다.
제품 정본과 보존 범위는 [AGENTS.md](../AGENTS.md), 현재 구현·검증 상태는
[product-state](product-state.md)를 따른다. 로컬 성공은 실기 검증·사용자 인수·운영 승인이 아니다.

## 이동 전: 공개 및 소스 범위 확인

- 새 컴퓨터에는 승인된 GitHub 저장소의 **검토된 커밋**을 clone한다. 현재 컴퓨터의 미커밋·untracked 파일,
  로컬 SDK·캐시·비밀은 clone에 포함되지 않는다. `git status --short`와 검토 대상 diff를 확인하고
  필요한 소스·문서·fixture를 빠짐없이 별도 검토한다. `.gitignore`는 공개 안전을 보장하지 않는다.
- 공개 전 전체 커밋 이력과 추가할 파일을 비밀, 실제 현장·장비 식별 정보, 기록·영상·증거,
  내부 주소·계정, 라이선스가 불명확한 이미지/모델/제3자 자산 관점에서 검토한다. 기존 문서와
  캡처에도 현장 정보가 있을 수 있다. 발견 시 공개하지 말고 소유자와 안전한 처리·이력 검토를 한다.
  DJI PSDK 및 기타 DJI 자산을 저장소에 넣지 않는다. 이 문서나 빌드 테스트는 공개 심사가 아니다.
- `contracts/`, 원본 snapshot, 검증 증거 및 다른 사람의 미커밋 변경은 `AGENTS.md`의 보존 규칙을 따른다.
  필요한 비공개 설정은 승인된 안전한 경로로 별도 마련한다. 키를 채팅·이슈·로그·Git에 넣지 않는다.

## 준비

CI 기준은 `.github/workflows/ci.yml`: Python 3.12, C++17 컴파일러와 CMake 3.16 이상,
JDK 17, Android SDK platform 34 / build-tools 34.0.0. 로컬 Python 설치 스크립트에는 `uv`도 필요하다.
Git, Bash와 표준 빌드 도구를 준비한다. Android는 SDK command-line tools를 설치하고 라이선스를
확인·수락한 뒤 `sdkmanager "platforms;android-34" "build-tools;34.0.0"`을 실행한다.
`ANDROID_HOME`을 SDK 경로로 지정한다. Gradle 8.10.2 wrapper는 저장소에 포함되어 있다.

Android 빌드에는 JRE만이 아닌 **완전한 JDK 17**이 필요하다. 자신의 설치 경로로 `JAVA_HOME`을
지정하고 같은 경로에 실행기와 컴파일러가 모두 있는지 확인한다. 예를 들어 다음의 경로는
실제 설치 위치로 바꾼다.

```bash
export JAVA_HOME="<자신의-JDK-17-설치-경로>"
export PATH="$JAVA_HOME/bin:$PATH"
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/javac" -version
```

두 명령이 모두 JDK 17을 가리켜야 한다. Linux 호스트에서 기본 `java`는 JRE 21이고 별도
`javac`은 JDK 17인 환경에서는 Gradle이 컴파일러 없는 21 경로를 자동 선택해 릴리스 작업이
실패한 사례가 있다. Mac 등 다른 OS의 설치 경로와 이 절차는 현재 검증하지 않았다.

최초 설치에는 Python 패키지 인덱스, Gradle 배포 서버, Google/Maven Central 및 Android SDK 접근이
필요하다. Python 설치는 lockfile 기반 고정이 아니므로 시점에 따라 결과가 달라질 수 있다.
Android 의존성에는 DJI MSDK v5 5.18.0이 포함된다. 관련 DJI 약관을 확인해야 하며 빌드된 산출물의
배포 권한까지 뜻하지 않는다. PSDK/TensorRT는 아래 **호스트 Edge 테스트**에 필요하지 않다.

```bash
git clone https://github.com/khakisketch/KSI-Safety-Edge.git
cd KSI-Safety-Edge
bash scripts/setup_env.sh
bash scripts/run_all_tests.sh
```

위 스크립트는 `.venv`에 `libs/ksi_contracts`, `core`, `harness`를 editable 설치하고 계약·공용 라이브러리·
Core·하네스 테스트를 실행한다. 기존 `.venv`를 무심코 다른 컴퓨터로 복사하지 말고 새로 만든다.

## Edge와 Android 로컬 검증

```bash
cmake -S edge -B edge/build -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build edge/build -j2
ctest --test-dir edge/build --output-on-failure

# SDK 경로를 자신의 설치에 맞게 지정
export ANDROID_HOME="$HOME/Android/sdk"
bash scripts/android_host_tests.sh
(cd android-rc && ./gradlew :rc-app:assembleBenchDebug :rc-app:compileDeviceDebugKotlin)
```

Android 스크립트는 ports/core/MSDK와 bench·device 단위 테스트를 실행한다. bench는 대역을 사용하며
device 컴파일은 MSDK를 포함하지만 실기 동작을 증명하지 않는다. DJI 앱 키 없이 빌드할 수 있으며
빈 키는 SDK 초기화를 하지 않는다. 실제 기기용 키가 별도로 승인·발급된 경우에만
`android-rc/local.properties`의 `dji.apiKey`에 로컬로 설정한다. 이 파일은 ignore 대상이며,
`android-rc/local.properties.example`은 빈 값을 제공하는 공유 가능한 예시다. 여기에 키를 넣지 않는다.
키는 device APK에 빌드 시 주입되므로 키가 든 APK도 민감하게 취급한다.

Linux aarch64의 Android SDK aapt2에는 x86_64 QEMU/binfmt와 sysroot가 필요할 수 있다.
스크립트의 기본 `QEMU_LD_PREFIX`는 `~/Android/sysroot/root`; 환경에 맞춰 지정한다.
해당 호스트의 WorkManager/Robolectric 6건은 SKIP이며 통과가 아니다. x86_64 CI 실행 결과를 별도 확인한다.
전체 로컬 미러 `bash scripts/run_local_verification.sh`는 Edge 테스트 외 loopback MOP 프로세스도
실행·정리하므로 공유 환경에서는 스크립트를 먼저 검토한다. CI의 정확한 작업은 workflow를 따른다.

## 장비·서버·자산 경계

- 위 테스트는 합성/호스트 검증이다. 실제 RC·Manifold, MOP·영상 수신, 비행/카메라 명령은 별도 승인과
  [실기 런북](verification/a9-4-device-day-runbook.md)이 필요하다. 자동 연결하거나 명령을 보내지 않는다.
- README의 Core 예시는 로컬 개발 서버다. `/review`의 의견 쓰기는 연결된 DB에 실제 기록한다.
  실서버나 공개 네트워크에 연결하지 말고, 운영에는 별도 [보안 게이트](plans/2026-10-14-production-security-gate.md)가 필요하다.
- `run_aircraft_free_*_chain.sh`의 영상·탐지는 합성이지만 지정한 Core에는 실제 데이터를 쓴다.
  승인된 빈 로컬 개발 DB에서만 실행한다. 기존 `--work-dir`은 삭제하거나 덮어쓰지 않고 거부한다.
- 실제 Manifold PSDK 앱 빌드는 별도 장치 환경과 적법하게 확보한 PSDK 3.16.0이 필요하다.
  `docs/plans/2026-10-21-dji-asset-usage.md`와 로컬 `third_party/dji/README.md`의 출처·라이선스를 확인한다.
  `scripts/dji_assets.sh fetch`는 네트워크와 장치 SSH를 사용할 수 있으므로 이 온보딩에서는 실행하지 않는다.
  UX SDK는 참고 자산이지 v5 앱에 통째로 편입하는 의존성이 아니다.
