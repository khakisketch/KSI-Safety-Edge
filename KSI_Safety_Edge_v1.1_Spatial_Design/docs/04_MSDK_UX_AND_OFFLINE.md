# 04. RC Plus 2 — MSDK 앱 화면·오프라인 명세

상태: 제품/UX target. MSDK 앱은 DJI Pilot 2 앱과 별개의 자체 Android 앱이며, Pilot 2 WebView에 임의로 기능을 끼워 넣는 구조가 아니다.

## 1. 설계 원칙

- 조종 중에는 비행·DJI 영상·기체 경고를 가장 크게, AI는 소음 적은 경고+증거 스냅샷.
- **오프라인 기본 상태가 정상**이다. '인터넷 없음'은 빨간 오류가 아니며, `서버 대기/로컬 보호 중`으로 표시.
- 비행 중 큰 대용량 업로드를 자동 시작하지 않음; 임무 종료/안정적 Wi-Fi/사용자 정책 때만.
- 조종사는 모든 사건을 승인하지 않음. 필요하면 `오탐 의심`, `확인 필요`를 비파괴적으로 누름.
- 증거 목록은 로컬 우선/서버 fallback으로 단일 목록 사용. 단, 출처·업로드·분석 상태는 명확히 표시.

## 2. 정보 구조 / 화면

| 화면(ID) | 주 목적 | 핵심 기능 | 가드 |
|---|---|---|---|
| `R01 Home` | 현장·임무 찾기 | 최근 현장, 로컬 캐시, 현재 네트워크/저장 공간 | 서버 접속 불가라도 현장 사용 가능 |
| `R02 Site/Map` | 구역 관리 | 현장 지도, Polygon/속성/위험종류, source revision | 오프라인 map tile 여부 표시 |
| `R03 Mission Plan` | 어떤 모델/구역 사용할지 결정 | 모델 선택, 증거 정책, 기록 프로파일, 준비검사 | model 미지원/권한 부족 설명 |
| `R04 Edge Pair/Ready` | PSDK 설정 적용 검증 | 기체·Edge ID, 연결, checksum, model, storage, telemetry | `APPLIED` 없으면 공간 판단 미활성 |
| `R05 Live Flight` | 조종·AI 경고 | DJI 원본 영상, ROI 또는 별도 사건 카드, AI health, 위험구역 지도 | 비행 조종 UI 우선, 프레임 불확실 overlay 금지 |
| `R06 Event Detail` | 증거와 간단 판단 | Edge snapshot, RC proxy clip, 당시 zone, 오탐/확인 필요 | 삭제 없음, 최초 검출 수정 금지 |
| `R07 Flight Summary` | 비행 종료 증거 확인 | 전체 기록, AI 가동/공백, 미관측, 사건, 누락 | manifest incomplete 명시 |
| `R08 Sync Center` | 온라인 업로드 | 대기/업로드중/실패/완료, 네트워크 정책, 재시도 | durably committed ACK 전 삭제 금지 |
| `R09 Archive` | 이전 비행/증거 조회 | 로컬캐시와 서버 통합, 필터/다운로드 | 서버 전용 파일은 온라인 필요 |
| `R10 Settings` | 모델/앱 정책 | 저장 한도, 네트워크, 계정/권한, 앱/SDK 버전 | 개인정보·토큰 안전 보관 |

## 3. UX states

- 연결: `DJI_LINKED`, `EDGE_LINKED`, `SERVER_OFFLINE/ONLINE`은 별개의 상태. 인터넷이 없다고 DJI LINK OFF로 표시하지 않음.
- 임무: `LOCAL_DRAFT → PREPARED → EDGE_APPLIED → RUNNING → STOPPED → FINALIZED`.
- 분석: `AI_READY / AI_DEGRADED / AI_PAUSED / AI_UNAVAILABLE`, 위치판정 `GEO_READY / GEO_UNCERTAIN / GEO_DISABLED`.
- 사건: `DETECTED`, `GEO_CANDIDATE`, `RISK_EVENT`, `REVIEW_REQUIRED`; 인간 평가 annotation 별도.
- 영상: `LIVE_AVAILABLE`, `RECORDING_ACTIVE`, `RECORDING_GAP`, `STORAGE_LOW`.
- 인터넷: 오프라인을 기본 동작 경로로 하고, 명시적으로 오래된 현장 revision 정보를 보여줌.

## 4. 위험구역 양방향 동기화

- 온라인 RC는 서버의 Site/Zone revisions를 pull. 다운받은 불변 revision을 로컬 DB에 저장.
- 오프라인 편집은 `draft_id + base_zone_revision + author + device_id + local_created_at`로 기록. 즉시 Edge에 적용할 수 있지만 해당 변경은 `unsynced_local_revision`로 구분.
- 온라인 복귀 시 서버와 base revision을 확인, parent가 같으면 새 canonical revision 부여.
- 서로 다른 RC/웹이 같은 base를 변경했다면 자동 last-writer-wins 금지. UI에서 변경 범위 비교/새 버전 선택 후 명시 승인. 당시 비행 snapshot은 어떠한 경우에도 수정하지 않는다.
- 지도 타일은 지역/해상도/라이선스를 고려하여 출발 전 caching. 무지도 환경에서는 위험구역 목록/좌표로 임무를 확인하되 지도 검증 없으면 구역 편집 정확성 제한.

## 5. 프록시 전체 비행 영상

- `ICameraStreamManager` 수신 H.264 스트림(렌즈별 가용)과 Android MediaCodec/MediaMuxer 기술 검토. 스트림 재인코딩이 필요하면 RC 성능 budget 실측.
- 초기 실험값: **720p/15fps H.264, 1~2 Mbps, 파일 분할 5분**. 30분 기준 단순 bitrate 산출 약 225~450MB. 품질/codec/RC 공간/영상원본의 연속성 실측 후 확정.
- `flight_id/segment_index/start/end/dropped_ms/hash/codec/source` manifest. 영상 손실/시각 점프는 segment gap.
- 원본 상세 증거는 가능하면 Edge inference snapshot/기체 SD 원본 사용. RC 라이브 프록시 단독으로 '정확한 AI detection frame' 주장하지 않음.
- RC 부족 공간 대응: 출발 전 예상 용량 검사, 임계치 시 안내, 오래된 `SYNCED` 파일만 자동 정리 대상으로 검토. 미업로드 증거 자동 삭제 금지.

## 6. 업로드 전략 (정책 / job 상태기계)

**기본**: 비행 종료, 안정적 Wi-Fi, 앱/OS가 작업 허용, 로그인 상태 검증시 자동 시작. 사용자는 셀룰러 허용, 충전중만, 수동 업로드 옵션 설정 가능. 비행 중 경량 사건 메타데이터 업로드는 P1 옵션, v1 기본 비활성.

```
LOCAL_FINALIZED
  → WAITING_FOR_NETWORK
  → WAITING_FOR_POLICY (Wi-Fi only / battery / login)
  → PREPARING_MANIFEST
  → UPLOADING_METADATA
  → UPLOADING_OBJECTS (chunk retries)
  → VERIFYING_SERVER
  → SYNCED
  ↘ RETRYABLE_FAILED / AUTH_REQUIRED / PARTIAL / PERMANENT_FAILED
```

- `flight_id + manifest_digest` idempotency. 서버에서 모르는 같은 ID/다른 manifest는 409 conflict.
- 저장 단계마다 transactional WAL/DB commit 후 ack; 앱 재시작 후 resume.
- Android background restrictions (Doze, manufacturer rules) 감안: 앱 백그라운드 자동 성공 보장은 아님. 사용자에게 '앱이 실행되거나 허용된 작업 환경에서 전송' 안내 및 수동 재개.
- 업로드 실패에 '데이터 지워짐'이 아니라 `local protected`, `server absent` 배지.
- RC 장기 보관/사용자 삭제 시도: 서버 commit 확인 후 GC, 만료 정책 및 사용자의 명시 확인.

## 7. 서버 증거 조회(online) UX

1. 사건을 누르면 로컬 캐시에서 snapshot/clip 우선 로드.
2. 로컬 없고 온라인이면 `GET /events/{id}/evidence`로 presigned URL과 hash, 파일 버전 조회.
3. 파일 캐시/스트리밍 후 서버와 로컬 provenance 표시.
4. 서버도 없거나 권한 없으면 `온라인 연결 필요/자료 미업로드/권한 부족`을 각각 구별.
5. VLM 제안은 원본 탐지 위에 덮지 않고 `사후 AI 검토 의견` 영역에 시간/모델/근거와 함께 표시.

## 8. 오탐 의견 UI

- 기본 UI: **아무것도 누르지 않는 것이 정상.** ‘오탐 의심’(optional reason), ‘확인 필요’, ‘표시 취소’만 제공.
- 운영자의 단순 선택은 `operator_annotation` append-only로 새 기록(이전 선택 revoke도 신규 record).
- 원본 event row / evidence blob / detector confidence는 수정·삭제 금지.
- '오탐 의심'은 조치 할 일 목록에서 기본 제외 **가능**하되 설정/권한/안전등급에 따라 주의 및 책임자 재검토. 통계에서 detection recall/precision의 정답으로 바로 사용 금지.
- UI는 안전에 직접 개입하는 조종사의 판단을 대체하거나 조종 중 입력을 강제하지 않음.

## 9. 오프라인 인증·분실 정책

- 최초 온라인 기기 등록, 조직과 현장별 권한 캐시. 오프라인 사용 기간(예: 7일)은 예시 정책으로 관리자 확정 필요.
- 현장 임무 실행에는 cached scope/expiry 체크; 만료시 긴급 오프라인 모드는 별도 승인 정책(기본 금지) 정의.
- 토큰/암호키 Android Keystore, 로컬 DB/파일 암호화. 분실 기기 원격 revoke는 기기 재연결 후 효력; 서버 측 API는 즉시 차단.
- 권한이 없어도 DJI 자체 비행 안전 기능을 물리적으로 봉쇄하지 않음; KSI 서비스 실행/기록 권한만 관리.

## 10. MSDK 수용조건

- 비행 중 폰트/알림이 DJI 비행 조작을 덮거나 실수 유발 X.
- 네트워크 0에서 영상·AI 경고·임무 설정·사건/비행 기록 사용 가능.
- 재부팅/앱 강제종료/와이파이 toggle에서 WAL/파일/queue 복구.
- 웹 vs RC 동시수정 충돌을 사용자가 이해 가능한 한 화면에서 분기 처리.
- RC 단독 재생이 서버의 사후 판단과 섞여 원본 사실처럼 보이지 않음.
