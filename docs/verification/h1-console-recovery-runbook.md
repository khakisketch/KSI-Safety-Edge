# H1 복구 런북 — Manifold 콘솔 작업용 (운영자 실행)

작성: 2026-10-11 · 대상: Manifold 3 (SSH `ksi-manifold`, dji 계정은 sudo 불가)
실행: **권한 있는 운영자가 콘솔(모니터+키보드)에서 직접**. 비밀번호는 콘솔에서
직접 입력하며 **채팅·파일·에이전트에게 전달하지 않는다.**

관찰된 현상(읽기 전용 점검, 2026-10-08/11):
- root `/`가 **읽기 전용(ro)** 으로 마운트됨 (27G·66% 사용, 여유 8.7G)
- 시스템 시계가 **약 6일 지연**(현재 10-11인데 장치 10-05), RTC는 1970, NTP 비활성
- `/open_app`·`/tmp`는 rw, 우리 개발 자산: `/open_app/psdk`, `/open_app/ksi-dev`

## 0. 공통 원칙 (반드시)

1. **진단 먼저** — 원인을 확정하기 전에 고치지 않는다.
2. **백업 먼저** — 복구 전에 `/open_app`(우리 자산 포함)을 백업한다.
3. **마운트된 root에 fsck 강행 금지 / 강제 rw 전환 금지**.
4. **RTC · 시스템 시간 · 동기화(NTP)는 각각 별개로** 취급하고, 보정 전후의
   시간 정합 측정을 섞지 않는다.
5. 모든 명령의 결과에서 **비밀번호가 출력되지 않도록** 한다(예: sudo는 대화형
   프롬프트 사용, 파이프/로그에 명령 이력 남기지 않기).

## 1. 진단 (읽기 전용, 먼저 이것만)

콘솔에서:

```bash
# root 마운트 상태·옵션
findmnt /
mount | grep ' / '

# 커널이 남긴 파일시스템 오류 흔적
sudo dmesg | grep -iE "ext4|i/o error|remount|read-only|orphan" | tail -40

# 마운트 실패·복구 관련 서비스
systemctl --failed
systemctl status systemd-remount-fs 2>/dev/null | head -20

# 디스크·파티션 상태
lsblk -f
df -h /

# 쓰기 가능성 확인 (io 오류 확인용 — 파일 하나 생성/삭제)
sudo touch /rootwrite-test && echo "root writable" || echo "root NOT writable"
sudo rm -f /rootwrite-test
```

**해석 가이드**
- dmesg에 `ext4 ... errors`/`remounting filesystem read-only`가 있으면 →
  전원 차단으로 인한 파일시스템 손상 경로(2-B).
- 아무 오류 없이 처음부터 ro이고, 시스템이 정상 동작 중이면 → **설계상 ro
  루트**(오버레이형 산업용 구성)일 가능성(2-A). 이 경우 "고칠 필요 없음"이
  정답일 수 있다.

## 2. 복구 경로

### 2-A. 설계상 ro 루트인 경우 (오류 흔적 없음)
- root ro를 억지로 바꾸지 않는다. 필요한 쓰기는 이미 rw인 `/open_app`·`/tmp`로.
- 확인할 것은 **지속성**뿐: 재부팅 후 `/open_app/psdk`·`/open_app/ksi-dev`가
  그대로 남아 있고 DPK/개발 파일을 쓸 수 있는지(2-C의 검증 항목).

### 2-B. 파일시스템 손상 의심(dmesg 오류 있음)인 경우
1) **백업**: 외장 USB/네트워크로 `/open_app` 백업(용량 확인 후):
```bash
# 예: 외장 USB 마운트 후
sudo rsync -a --info=progress2 /open_app/ /mnt/usb/ksi-backup-20261011/open_app/
sha256sum /open_app/ksi-dev/dfine-export-20261008/* 2>/dev/null | head   # 백업 전 스냅샷
```
2) **복구**: 마운트된 root에 fsck 금지 → 아래 중 하나:
   - 장치가 복구 부팅(USB/복구 이미지)을 지원하면 그 환경에서 `fsck -f` 실행, 또는
   - 재부팅 1회 후에도 ro가 유지되고 dmesg 오류가 반복되면 제조사(또는 보유한
     복구 절차서)에 문의 — 이 단계는 **데이터 백업 완료 후** 진행.
3) 복구 후 **재발 방지**: 정상 종료 습관, 가능하면 UPS/안정 전원.

### 2-C. 시계 복구 (RTC/시스템/NTP 분리)

```bash
# (1) RTC 상태
sudo hwclock --show 2>&1 | head -2        # 1970이면 RTC 백업전지/드라이버 의심
# (2) 시스템 시간
date
timedatectl 2>/dev/null | head -10
# (3) 동기화 수단
systemctl status systemd-timesyncd 2>/dev/null | head -12
# 인터넷 가능하면 NTP 활성화(권장), 불가하면 수동 설정
sudo timedatectl set-ntp true
# 수동(오프라인)으로 맞출 때 — RTC에도 기록
sudo date -s "2026-10-11 12:00:00"
sudo hwclock --systohc
date && sudo hwclock --show
```
- **보정 전 시각과 보정 후 시각을 각각 기록**해 가져오세요(정합 측정 기준선).
- RTC가 계속 1970으로 리셋되면 전지 교체/제조사 문의 항목으로 기록.

## 3. 검증 (결과로 가져올 것 — 비밀번호 없는 출력만)

아래 출력을 캡처/복사해 전달해 주시면 제가 비교·기록합니다:

```bash
findmnt / ; df -h / /open_app
date ; sudo hwclock --show 2>&1 | head -1 ; timedatectl 2>/dev/null | head -6
sudo dmesg | grep -iE "ext4|i/o error|remount" | tail -10
sudo touch /open_app/write-test && echo OK && sudo rm /open_app/write-test
ls -la /open_app/psdk /open_app/ksi-dev | head -20
sudo reboot   # 재부팅이 허용되는 시점에 (백업 완료 후)
# 재부팅 후
findmnt / ; date ; ls /open_app/psdk /open_app/ksi-dev | head
```

**주의**: 비밀번호·토큰·키 값이 화면에 나오는 출력은 보내지 마세요.

## 4. 이 런북 완료 후 연결되는 것

- 장치 자산(PSDK/개발 파일) 지속성 확인 → H1 잔여 항목 정리
- 시계 기준선 확보 → **시간 정합 측정(별도 절차)** 의 전제
- 이후 RC Plus 2 연결(입력 ②)·DJI 앱키(입력 ③)로 A9 진행
