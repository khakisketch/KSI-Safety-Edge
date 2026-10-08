# H1-1 Environment Inventory

- 수집 시각(UTC): 2026-10-02T06:17:28Z
- 수집 시각(local): 2026-10-02T15:17:28+0900
- hostname: tegra-ubuntu

### 시스템
Linux tegra-ubuntu 5.10.192-tegra #1 SMP PREEMPT Mon Mar 9 16:12:41 CST 2026 aarch64 aarch64 aarch64 GNU/Linux
VERSION="20.04.6 LTS (Focal Fossa)"
PRETTY_NAME="Ubuntu 20.04.6 LTS"
# R35 (release), REVISION: 5.0, GCID: 35550185, BOARD: t186ref, EABI: aarch64, DATE: Tue Feb 20 04:46:31 UTC 2024
  Distributor ID:	Ubuntu
  Description:	Ubuntu 20.04.6 LTS
  Release:	20.04
  Codename:	focal

### 툴체인
  gcc: gcc (Ubuntu 9.4.0-1ubuntu1~20.04.2) 9.4.0
  g++: g++ (Ubuntu 9.4.0-1ubuntu1~20.04.2) 9.4.0
  cmake: cmake version 3.16.3
  make: GNU Make 4.2.1
  ninja: (없음)
  python3: Python 3.8.10
  git: git version 2.25.1

### CPU/메모리/열
  nproc: 4
                total        used        free      shared  buff/cache   available
  Mem:          14834        1143         137         171       13552       13250
  Swap:         29668           0       29667
  /sys/class/thermal/thermal_zone0/temp: 42906
  /sys/class/thermal/thermal_zone1/temp: 41781
  /sys/class/thermal/thermal_zone2/temp: 42343
  /sys/class/thermal/thermal_zone3/temp: 42531
  /sys/class/thermal/thermal_zone4/temp: 42062
  /sys/class/thermal/thermal_zone5/temp: 44062
  /sys/class/thermal/thermal_zone6/temp: 44968
  /sys/class/thermal/thermal_zone7/temp: 42468
  /sys/class/thermal/thermal_zone8/temp: 44875

### 저장공간
  Filesystem      Size  Used Avail Use% Mounted on
  /dev/nvme0n1p2   27G   17G  8.7G  66% /
  overlay         9.8G  226M  9.1G   3% /home

### USB
  Bus 002 Device 003: ID 0bda:8153 Realtek Semiconductor Corp. RTL8153 Gigabit Ethernet Adapter
  Bus 002 Device 002: ID 05e3:0625 Genesys Logic, Inc. USB3.2 Hub
  Bus 002 Device 001: ID 1d6b:0003 Linux Foundation 3.0 root hub
  Bus 001 Device 002: ID 05e3:0610 Genesys Logic, Inc. 4-port hub
  Bus 001 Device 001: ID 1d6b:0002 Linux Foundation 2.0 root hub

### 네트워크 인터페이스
  lo               UNKNOWN        00:00:00:00:00:00 <LOOPBACK,UP,LOWER_UP> 
  dummy0           DOWN           ca:af:69:a6:e7:4b <BROADCAST,NOARP> 
  l4tbr0           DOWN           82:03:ac:7b:f2:05 <NO-CARRIER,BROADCAST,MULTICAST,UP> 
  rndis0           DOWN           82:03:ac:7b:f2:05 <NO-CARRIER,BROADCAST,MULTICAST,UP> 
  eth0             UP             c0:ea:c3:6a:22:b0 <BROADCAST,MULTICAST,UP,LOWER_UP> 
  lo               UNKNOWN        127.0.0.1/8 ::1/128 
  dummy0           DOWN           
  l4tbr0           DOWN           192.168.55.1/24 
  rndis0           DOWN           192.168.42.2/24 
  eth0             UP             172.30.1.33/24 fe80::721a:2ea:29e4:12f3/64 

### DJI/PSDK 흔적 (읽기 전용)
  ii  dbus-user-session                            1.12.16-2ubuntu2.3                    arm64        simple interprocess messaging system (systemd --user integration)
  ii  dji-system                                   00.00.00.01                           arm64        dji system deb package
  ii  libavahi-glib1:arm64                         0.7-4ubuntu7.3                        arm64        Avahi GLib integration library
  ii  libcairo-gobject-perl                        1.005-2                               arm64        integrate Cairo into the Glib type system in Perl
  ii  libdrm-tegra0:arm64                          2.4.107-8ubuntu1~20.04.2              arm64        Userspace interface to tegra-specific kernel DRM services -- runtime
  ii  libgnome-autoar-0-0:arm64                    0.2.3-2ubuntu0.4                      arm64        Archives integration support for GNOME
  ii  nvidia-l4t-display-kernel                    5.10.192-tegra-35.5.0-20240219203809  arm64        NVIDIA Display Kernel Modules Package
  ii  nvidia-l4t-kernel                            5.10.192-tegra-35.5.0-20240219203809  arm64        NVIDIA Kernel Package
  ii  nvidia-l4t-kernel-dtbs                       5.10.192-tegra-35.5.0-20240219203809  arm64        NVIDIA Kernel DTB Package
  ii  nvidia-l4t-kernel-headers                    5.10.192-tegra-35.5.0-20240219203809  arm64        NVIDIA Linux Tegra Kernel Headers Package
  ii  xdg-desktop-portal                           1.6.0-1ubuntu2                        arm64        desktop integration portal for Flatpak and Snap

### 수동 기록 필요(스크립트로 불가)
- [ ] 기체 모델(4E/4T)·펌웨어 버전(RC/Pilot 화면)
- [ ] 매니폴드 3 이미지·JetPack 실제 값(장치 화면/설정)
- [ ] RC Plus 2: Pilot 2 버전, Android 버전, KSI 앱 설치 여부
- [ ] 사용 렌즈/소스 목록과 전환 가능성
- [ ] PSDK 실물 확보 위치·버전(LOCK 대장과 대조)
- [ ] dji_sdk_app_info 자격증명 보유 여부(원문 출력 금지, 유/무만)
