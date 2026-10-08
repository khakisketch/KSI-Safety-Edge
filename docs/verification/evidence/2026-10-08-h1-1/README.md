# H1-1 Evidence — Manifold 3 접속·인벤토리·개발 루프 스모크 (2026-10-08)

- 성격: **장치 접근·환경 확인 증거**. 실기 센서/영상/시간 검증(H1-3~5)이 아니다.
- 접속: `ssh ksi-manifold` (`~/.ssh/config`: 172.30.1.33, user `dji`, key `~/.ssh/ksi_manifold_ed25519`). 사용자가 장비를 사내 LAN에 연결·전원 ON(2026-10-08 통보).
- 원칙 준수: 쓰기는 **신규 격리 디렉터리 1곳**뿐 — `/open_app/ksi-dev/safety-edge-smoke-20261008/`(rsync 복사 + 빌드 산출물). 기존 파일·설치·서비스·DPK 변경 없음.

## 1. 장치 환경 (읽기 전용 수집, `env-inventory.md` 전체)

| 항목 | 값 |
|---|---|
| 호스트 | `tegra-ubuntu` (Manifold 3, NVIDIA Orin 계열) |
| OS / L4T | Ubuntu 20.04.6 LTS (Focal) / L4T R35.5.0 (JetPack 5.1.3) |
| 툴체인 | gcc/g++ 9.4.0, cmake 3.16.3, make 4.2.1, git 2.25.1, python3 3.8.10, ninja 없음 |
| CPU/RAM | 4 core, RAM 14.8GB(가용 13.3GB), swap 29GB |
| 저장 | `/` 27G (8.7G 여유), `/open_app` 99G (94G 여유, **writable=yes**), `/home` overlay 9.1G 여유 |
| 열(대기) | thermal 41.8~45.0°C |
| 네트워크 | `eth0` 172.30.1.33/24 (실사용). `l4tbr0`/`rndis0` **NO-CARRIER**(USB gadget, 기체 미접속 상태) |
| PSDK | `/open_app/psdk` **3.16.0** 존재 (`psdk_lib/include/dji_version.h` = 3.16.0, aarch64 `libpayloadsdk.a` 포함) |
| 설치 앱 | `dji_app_ctl list`: **Smart3DExplore 00.01.00.16(DJI)만** — **KSI DPK 미설치** |
| 이전 스테이징 | `/open_app/ksi`(SI clone), `/open_app/ksi-build`(control_plane 빌드 산출물), `/open_app/ksi-dev/{current,dpk-staging,releases}`, `/open_app/ksi_hil_raw_logs` |
| tap DPK | `dpk-staging/ksi-psdk-tap_v00.01.00.{00,01,02}.dpk` 3종(2026-08-12) — 미설치 |
| 이전 배포 기록 | `current/deploy_manifest.json`: SI `2d9e7db6`, 2026-08-12, mode=raw (역사 기록) |
| 도구 | rsync/tar/scp 존재. `dji_app_ctl` 지원: install/uninstall/start/stop/restart/enable/disable/list/status |

## 2. 개발 루프 스모크 — 우리 `edge/` 코어 온보드 빌드·테스트 (신규 발견)

```
rsync -az --exclude 'build*/' edge/  →  ksi-manifold:/open_app/ksi-dev/safety-edge-smoke-20261008/edge/
ssh ksi-manifold 'cd .../edge && cmake -S . -B build && cmake --build build -j4 && cd build && ctest'
→ configure OK / build OK (경고: Clock skew detected) / **6/6 tests passed** (capture_queue, provenance, ring, matching, interpolation, perception)
```

- 의미: DGX에서 개발 → rsync → 장치에서 gcc 9.4/cmake 3.16.3으로 빌드·ctest → 로그 회수의 **개발 루프가 실제로 성립**함을 입증.
- 한계: 이것은 우리 코드의 호스트 성격 로직 검증이지 PSDK/영상/텔레메트리/실기 검증이 아니다.

## 3. 발견 사항·위험 (세션 전 정비 후보)

1. **장치 시계 이상(중요)**: device UTC = host UTC − 6일 4시간, `System clock synchronized: no`, `NTP inactive`, **RTC=1970-01-01**. 빌드 시 clock-skew 경고 발생. H1-5(시간 정합) 측정·로그 증거 신뢰성에 직접 영향 → 세션 전 NTP/시계 정비 필요(시스템 변경, 사용자 승인 필요).
2. **기체 미접속 상태**: `l4tbr0`/`rndis0` NO-CARRIER, USB에 DJI 기체 장치 미검출 → H1-3~6(텔레메트리·라이브뷰·정합·소크)은 기체/RC 연결·전원 상태에서만 가능.
3. **DJI 자격증명 불명**: `dji_sdk_app_info.h`(user_app_id 등) 보유 여부 미확인 — 확인했으나 기존 저장소 staging에서는 찾지 못함(원문 조회는 하지 않음). DPK 신규 빌드·설치의 선행 조건.
4. **KSI DPK 미설치**, tap DPK 3종만 staging. 설치/기동은 **미승인 상태이며 이번에 실행하지 않음**.

## 4. 이번에 하지 않은 것 (완료로 주장하지 않음)

- DPK 설치/기동(`dji_app_ctl install/start`), PSDK 실행파일 실행, official sample 빌드(H1-2 본단계), liveview/텔레메트리 수신(H1-3/4), 시간 정합 실측(H1-5), 소크(H1-6), RC 접근(H1-7), 시계 정비.
- 기체 모델(4E/4T)·펌웨어, RC Plus 2 버전·설치 상태(수동 확인 항목) — 미확인.
