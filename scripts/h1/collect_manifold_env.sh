#!/usr/bin/env bash
# H1-1 환경 인벤토리 수집기 — Manifold 3(또는 후보 호스트)에서 실행. 읽기 전용.
# 사용: bash scripts/h1/collect_manifold_env.sh > env.md   (결과는 세션 기록 폴더로 이동)
# 원칙: 시리얼·토큰·자격증명 원문을 출력하지 않는다. 설치/변경을 수행하지 않는다.
set -u
have() { command -v "$1" >/dev/null 2>&1; }
show() { printf '\n### %s\n' "$1"; }

echo "# H1-1 Environment Inventory"
echo
echo "- 수집 시각(UTC): $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "- 수집 시각(local): $(date +%Y-%m-%dT%H:%M:%S%z)"
echo "- hostname: $(hostname 2>/dev/null)"

show "시스템"
uname -a 2>/dev/null
[ -r /etc/os-release ] && grep -E '^(PRETTY_NAME|VERSION)=' /etc/os-release
[ -r /etc/nv_tegra_release ] && head -1 /etc/nv_tegra_release
lsb_release -a 2>/dev/null | sed 's/^/  /'

show "툴체인"
for t in gcc g++ cmake make ninja python3 git; do
  if have "$t"; then printf '  %s: %s\n' "$t" "$("$t" --version 2>/dev/null | head -1)"; else printf '  %s: (없음)\n' "$t"; fi
done

show "CPU/메모리/열"
nproc 2>/dev/null | sed 's/^/  nproc: /'
free -m 2>/dev/null | sed 's/^/  /'
for z in /sys/class/thermal/thermal_zone*/temp; do
  [ -r "$z" ] && printf '  %s: %s\n' "$z" "$(cat "$z" 2>/dev/null)"
done

show "저장공간"
df -h / "$HOME" 2>/dev/null | sed 's/^/  /'

show "USB"
have lsusb && lsusb 2>/dev/null | sed 's/^/  /'

show "네트워크 인터페이스"
have ip && ip -brief link 2>/dev/null | sed 's/^/  /'
have ip && ip -brief addr 2>/dev/null | sed 's/^/  /'

show "DJI/PSDK 흔적 (읽기 전용)"
dpkg -l 2>/dev/null | grep -i -E 'dji|psdk|tegra' | head -20 | sed 's/^/  /'
for d in /opt/dji /usr/local/dji "$HOME/psdk" "$HOME/Payload-SDK"; do
  [ -e "$d" ] && printf '  exists: %s\n' "$d"
done
# PSDK 개발 트리 후보(프로젝트 로컬, 깊이 제한)
for base in "$HOME/Desktop/KSI-Projects"; do
  find "$base" -maxdepth 4 -type d -name "psdk" 2>/dev/null | head -5 | sed 's/^/  psdk dir: /'
done

show "수동 기록 필요(스크립트로 불가)"
cat <<'EOF'
- [ ] 기체 모델(4E/4T)·펌웨어 버전(RC/Pilot 화면)
- [ ] 매니폴드 3 이미지·JetPack 실제 값(장치 화면/설정)
- [ ] RC Plus 2: Pilot 2 버전, Android 버전, KSI 앱 설치 여부
- [ ] 사용 렌즈/소스 목록과 전환 가능성
- [ ] PSDK 실물 확보 위치·버전(LOCK 대장과 대조)
- [ ] dji_sdk_app_info 자격증명 보유 여부(원문 출력 금지, 유/무만)
EOF
