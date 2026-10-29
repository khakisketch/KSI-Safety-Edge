# 감독 화면 개발용 자료 v1 — 내부 통합 입력

상태: **호스트 재생/웹 렌더 통합용. 공개 MOP/API 계약이나 실제 장치 상태가 아니다.**
작성: 2026-10-23. `contracts/**`의 기존 바이트·해석·권한을 변경하지 않는다.

`scripts/build_supervision_fixture.py`가 실제 호스트 실행의 보고·봉인 원본·보관 영수증을 검증해
`design/rc-pilot-shell/fixtures/autonomous-host-replay.json`을 만든다. UI 진입은 명시적
`?supervisionFixture=fixtures/autonomous-host-replay.json`이다. 해당 query 없는 운용 경로는 기존 데이터 소스를 쓴다.
새 자료를 실제 MOP 수신 사실로 간주하거나 이 입력으로 장치/서버 명령을 보내지 않는다.

## 입력 모양

```json
{
  "schema_version": 1,
  "fixture_kind": "KSI_SUPERVISION_DEVELOPMENT",
  "provenance": {
    "environment": "HOST_REPLAY",
    "simulated_inputs": true,
    "device_verified": false,
    "source_files": []
  },
  "observation": {
    "runtime_state": "STOPPED",
    "processed_frame_count": null,
    "input_state": {"video": "absent", "telemetry": "absent", "engine": "absent"}
  },
  "camera": {
    "reported_owner": "UNKNOWN",
    "automatic_permission": "UNAVAILABLE",
    "adjustment_state": "NOT_CONFIGURED"
  },
  "assessment": {
    "mode": "SHADOW",
    "count": null,
    "latest_disposition": "UNKNOWN",
    "reason_codes": []
  },
  "evidence": {
    "local_state": "UNKNOWN",
    "context_state": "UNKNOWN",
    "source_frame_count": null,
    "assessment_artifact_count": null,
    "rc_receipt_state": "UNVERIFIED",
    "core_custody_state": "UNVERIFIED",
    "collection_id": null
  },
  "intervention": {"required": null, "reason_codes": []}
}
```

- 위 예시는 형태만 정의한다. 기본값을 정상 상태로 쓰지 않는다. 부재·null은 미확인이다.
- runtime_state/input_state는 기존 런타임 어휘를 사용한다. STOPPED는 재생 마감 상태이지 현재 분석 중이 아니다.
  UI는 해당 실행의 마감 자료임을 밝힌다. 완료된 재생 자료를 실시간 관측으로 표시하지 않는다.
- `source_files` 원소: `{"name":"역할/상대 이름","sha256":"64자리 hex"}`. 토큰·기기 키·민감한 절대 경로는 넣지 않는다.
- `camera`: reported_owner=`UNKNOWN|OPERATOR|DJI_MISSION|KSI`, automatic_permission=
  `UNKNOWN|UNAVAILABLE|RELEASED|ALLOWED`, adjustment_state=`UNKNOWN|NOT_CONFIGURED|IDLE|OBSERVING|INTERRUPTED|FAILED`.
  호스트 A에서는 카메라를 실행하지 않았으므로 위 UNKNOWN/UNAVAILABLE/NOT_CONFIGURED를 유지한다.
  별도 B의 모의 카메라 입력은 분석 상태와 독립이며, 입력 출처는 보고서/메타데이터에 기록한다.
  연결된 앱에서 실제 보고와 섞일 수 있으면 화면에서도 출처를 구별한다.
- assessment latest_disposition=`UNKNOWN|UNDETERMINED|ASSESSED|CONDITION_MET|CONDITION_NOT_MET|NOT_EVALUATED`.
  SHADOW에서 CONDITION_MET도 확정 위험 Incident나 경보를 뜻하지 않는다.
  `ASSESSED`는 공유 Edge 형식(`ksi.assessment/v1`)의 평가 완료 표기이며 Incident 승격이 아니다.
- evidence local_state=`UNKNOWN|COLLECTING|SEALED|INCOMPLETE|FAILED`, context_state=`UNKNOWN|COMPLETE|INCOMPLETE`.
  바이트 봉인 성공과 전후 맥락 완전성을 별도로 표시한다. SEALED+INCOMPLETE가 가능하다.
- core_custody_state=`UNVERIFIED|COMMITTED`. COMMITTED는 검증한 영수증과 봉인 세트가 대응할 때만 쓴다.
  자동 관측 마감·증거 완전성·위험 판정·Alert 전달의 성공을 대신하지 않는다.
- 이 호스트 경로는 Android RC가 받았다는 근거가 아니므로 rc_receipt_state=`UNVERIFIED`다.
- intervention.required는 해당 자료의 중요 입력/저장 장애에서만 계산한다. 일반 SHADOW 후보·판정불가만으로
  자동 true가 되지 않는다. 모르는 값은 false로 바꾸지 않는다.

## 소비 경계

웹 로더는 명시적 same-origin `fixtures/*.json` 입력만 읽고 파일 크기/타입/어휘를 제한한다.
실패 시 영상과 기존 운용 상태는 유지하고 자료 로딩 실패를 밝힌다. SHADOW 의미와 종료된 재생/현재
관측의 구분은 유지한다. 실제 기기·운용 서버에 연결하지 않는 독립 시안의 재생 출처와 합성 여부는 화면 계약
D9에 따라 보고서/메타데이터에 기록하고, 연결된 앱에서는 시험 입력을 실제 보고와 구별한다.
Android 호스트 모델은 동일 의미를 시험할 수 있으나, 실제 화면은 현재 받은 런타임·레코더 사실만 사용한다.
새 증거/평가 정보가 기존 MOP에 없다면 미확인으로 남긴다. 이 JSON을 실제 수신 구현으로 주장하지 않는다.
