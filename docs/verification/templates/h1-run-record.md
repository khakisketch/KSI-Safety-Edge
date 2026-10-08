# H1 Run Record — `<SESS-YYYYMMDDTHHMMSSZ-XXXX>`

> 템플릿. 세션마다 복사해 `docs/verification/records/<session>/run-record.md`로 저장한다.
> 시리얼·토큰·자격증명 원문 금지(마스킹본만).

## 세션 정보

- 날짜/시간(현지·UTC):
- 운영자/기록자:
- 장소·조건(실내/실외, 조도, 날씨):
- 프로펠러 분리·비행 없음 확인: [ ]

## 장비·버전

| 항목 | 값 | 확인 방법 |
|---|---|---|
| 기체 모델(4E/4T)·펌웨어 | | |
| 매니폴드 3 이미지/JetPack·커널 | | |
| PSDK 버전(lock 일치 여부) | | |
| RC Plus 2: Pilot 2 / Android | | |
| KSI 앱/MSDK 버전(해당 시) | | |
| 렌즈/소스 사용 목록 | | |

## 단계 결과

| 단계 | 결과 PASS/FAIL/NOT_RUN | 증거 파일 | 비고 |
|---|---|---|---|
| H1-1 인벤토리 | | env.md / env.json | |
| H1-2 샘플 빌드·기동 | | build.log / run.log | |
| H1-3 FC/짐벌 토픽 | | topics.json | |
| H1-4 Liveview/프레임 | | frames/… | |
| H1-5 시간 정합 | | timing.csv / summary.json | |
| H1-6 30분 소크 | | soak.csv | |
| H1-7 RC | | screenshots/… | |
| H1-8 DPK(H1b) | | | |

## 측정값 (해당 단계만)

- H1-5 skew p50/p95 (frame↔topic별):
- 신규 표본 비율 / 동일값 반복 처리:
- H1-6: fps/CPU/GPU/RAM/온도(시점·최대):

## 편차·한계·미확인

- 이번 세션에서 확인하지 못한 것:
- 설계 갱신이 필요한 발견:

## 증거 무결성

- 파일 목록 + SHA256(요약):
- 마스킹 점검 완료: [ ]

## 후속

- 다음 세션에 필요한 것:
- 차단 사유(있으면):
