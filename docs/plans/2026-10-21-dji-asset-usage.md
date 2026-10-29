# DJI 자산 활용 원칙 — "무엇을 언제 쓰고, 무엇을 참조만 하는가" — 2026-10-21

상태: **정본(작업 원칙).** 앞으로 DJI 자산을 쓰게 되면 이 문서를 먼저 본다.

> **배포 가림:** 기기 식별자·사내망 주소는 `[REDACTED: ...]`로 가렸다. 의미·판정은 유지된다.

- 자산 실물(로컬 고정): `third_party/dji/README.md` · 복구 스크립트: `scripts/dji_assets.sh`
- 제품 계약(우리의 역할): `docs/plans/2026-10-21-manifold-role-contract.md`
- 화면 계약: `docs/plans/2026-10-21-screen-contract.md`

## 1. 원칙

**2026-10-23 범위 개정:** 기본 자동 관측·평가·증거화와 선택적 능동 재관측을 분리해 개발한다.
아래 'AI/Edge는 기체를 명령하지 않는다'는 실제 SDK 동작의 현행 경계이며, 비행/경로 변경은 계속 금지한다.
새 능동 카메라 코어는 모의 포트만 사용하고 실제 PSDK 자동 조작은 별도 실기 검증/허용 전 연결하지 않는다.
이는 SDK 교체·기존 버전 핀 변경·AI 위험 경보 승격 승인이 아니다.

> **DJI 자산을 상속받되, 우리 계약은 양보하지 않는다.**
> 자산은 **구현 수단을 좁히고**, **판단과 책임은 우리 것이 유지한다.**

세 가지로 나눈다: **채택**(의존) · **참조**(코드·UX 복제) · **배제**(사용하지 않음).
판단이 애매하면 **참조**가 기본이다(도입 비용이 되돌아오기 어렵기 때문).

**2026-10-22 소스 대조 정정:** KSI RC는 관측 보조가 아니라 주 운용 앱이다
(`product-state.md` 제품 정의·10-17 운용 책임표). **AI/Edge의 기체 명령 금지**를
**조종자가 RC에서 지원 비행·카메라 명령을 요청하는 기능 금지**로 확대하지 않는다.
아래 Beta 저장소의 이름5.0과 MSDK v5도 별개다. 로컬
`android-uxsdk-beta-core/build.gradle:71,82`의 실제 의존성은 **MSDK4.16.4**다.
현재 v5 참고 소스는 공식 `Mobile-SDK-Android-V5`의 `SampleCode-V5/android-sdk-v5-uxsdk`이며,
공식 README의 현재 버전은5.18.0이다. 이 정정은 새 SDK 의존성 채택이나 실기 지원 승인을 뜻하지 않는다.

## 2. 판정표 (2026-10-21 실측 기준)

| 자산 | 버전 | 판정 | 이유 |
|---|---|---|---|
| **MSDK v5** (`com.dji:dji-sdk-v5-aircraft:5.18.0`) | 5.18.0 | **채택** | RC의 기체 명령·영상·MOP 파이프라인. 이미 사용 중 |
| **Payload SDK (Manifold 3)** (`/open_app/psdk`, 로컬 고정 `third_party/dji/psdk-3.16.0`) | 3.16.0 | **채택** | Manifold에서 도는 우리 Edge 앱의 프레임워크. `dji_mop_channel.h`가 RC↔Edge MOP의 짝 |
| **UX SDK Beta 5.0** (GitHub, MIT, `third_party/dji/uxsdk-beta-android`) | ed5ca45 | **이전 패턴 참조** | 실제 의존성은 **MSDK4.16.4**. 이름5.0을 MSDK v5로 해석하지 않는다. **wholesale 도입 금지** |
| **MSDK v5 공식 UX 샘플** (`Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk`) | 공식 README5.18.0, 10-22 조회 | **현재 패턴 참조** | 영상·카메라·비행 상태·지도 전환의 배치/행동 참고. 소스 통째 편입·자동 의존성 추가 없음 |
| UX SDK 4.16.2 (Maven) | 4.16.2 | **배제** | MSDK **4.x** 전용. 우리 v5와 혼용 불가 |
| Mobile SDK v4 베타 zip (4.9.1 / 4.15) | — | **배제** | 구버전. v5와 무관 |

### UX SDK를 "참조"로 제한하는 이유 4가지

1. **책임·게이트 보존** — 비행·촬영은 주 운용 앱 범위다. 다만 SDK 샘플의 버튼 존재가
   우리 기체 조합의 지원·제어권·실행 확인을 보장하지 않는다. **AI는 기체를 명령하지 않으며**,
   RC 명령은 기존 명령별 게이트를 유지한다.
2. **버전 계열** — 로컬 Beta는 이름5.0이어도 MSDK4.16.4에 의존한다. 우리 v5와 혼용하지 않는다.
   현재 v5 UX 샘플은 별도 저장소 경로를 참조한다.
3. **빌드 결정성** — 소스 통합 + `fly-safe-database` 자동 다운로드는 저장소 원칙
   (frozen 파이프라인·결정론적 빌드)과 충돌한다.
4. **리스크** — 이미 실기 검증된 D1~D5 화면·증거 경로를 다시 쌓으면 회귀 비용이 크다.

### UX SDK에서 "가져올 가치가 있는 것" (복제 우선순위)

1. **오프라인 맵 + 핀/지오펜스 UX** → 우리 `docs/plans/2026-10-16-field-zone-designation-contract.md`
   (현장 좌표 지정, 캐시/저장/적용 분리, SHADOW)의 화면 구현 참고. 현재 RC는 `tvMapStatus` placeholder.
2. **RC Plus 2 실기에서의 영상·하드웨어(버튼·OSD·PTZ·RTK·전원) 처리 방식** → 우리 `MsdkVideoSource` 보강 참고.
3. **미션/관측 UI 패턴** → 우리 관측 기록·대응 패널의 정보 구조 참고.

## 3. 자산 사용 시 지켜야 할 것 (우리 계약)

- **시간 등급을 올리지 않는다** — 수신 근접으로 촬영 시각 정합을 주장하지 않는다.
- **탐지 안 함 ≠ 탐지 0건 ≠ 안전** — 3분 유지.
- **ACTIVE 승격 없음** — SHADOW만.
- **AI/Edge는 기체를 명령하지 않는다.** 조종자의 RC 명령은 지원·연결·제어권 게이트를 따른다.
  현재 비행3명령은 G1b 제어권 확인 부재로 차단되며, 카메라는 별도 연결 조건을 따른다.
- **보존 성공 ≠ 세션 성공** — `COMMITTED`와 세션 결과를 분리한다.
- **매니폴드는 관측·분석·증거만** — UI·보고·안전판정은 매니폴드 책임 밖.

## 4. 포털에서 얻은 사실 (재발 방지, 2026-10-21)

| 사실 | 의미 |
|---|---|
| `POST /api/web/apps` → `{"code":101,"message":"parameter invalid or blank"}` | **계정 레벨 차단**. 모든 타입·모든 필드 조합(빈 키 제거 포함) 동일 → DJI 문의 대상 |
| 개발자 레코드 `real_name_status=0` · `approved_at=null` · `company_size=0` | 실명 인증/계정 승인이 미완료일 가능성 |
| MSDK v5 App Key = **패키지명**(App Identification Code) | keystore/서명 바인딩 없음 → debug/release 서명 교체로 키 무효화되지 않음 |
| `GET /api/web/payloadsdk_bind_info` → `total_count:0`, "Register"는 `account.dji.com/register` | 페이로드 바인딩은 **포털 입력으로 하는 게 아니다** → 기기가 스스로 등록 |
| 미검증 앱 배포 **20대 한정** / `Application Verification` 통과 시 production | 우리 2대(RC+Manifold)면 현재 상태로 충분 |
| UX SDK 4.16.2는 Maven 공개 아티팩트 | MSDK v4 앱 키만 나오면 UX SDK 경로가 즉시 열림 → DJI 문의에 포함 |
| Manifold 3 SN [REDACTED: device serial], eth0 정적 [REDACTED: private IPv4 /24], PSDK 3.16.0 상주 | 전원 ON + LAN 배선이면 SSH·DPK 배포 가능 |

## 5. 다음에 이 원칙을 적용할 지점

- **지도/현장 지정 구현** → UX SDK 맵 위젯 **참조** 후 우리 계약에 맞춰 자체 구현
- **영상 표시 완성도** → DJI의 RC 하드웨어/OSD 처리 **참조** 후 `MsdkVideoSource` 보강
- **비행·촬영 화면 구성** → 현재 v5 공식 UX 샘플을 참조해 기존 RC 명령 포트에 연결하되,
  기체 명령 게이트·요청/실행 분리·카메라 원본/KSI 기록 분리를 유지한다.
- **DJI 답 오면** → `scripts/dji_assets.sh fetch` 로 자산을 복구하고 MSDK 키를 주입해 실기 검증
