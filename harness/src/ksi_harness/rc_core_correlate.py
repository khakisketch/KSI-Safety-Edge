"""Correlate a pulled RC device store with Core READ responses.

Purpose (L1): after a real RC session, prove what the RC actually recorded is
actually reachable from the Core read API. This module compares exactly two
things — (a) records present in the pulled RC store and (b) rows the Core
read API returned. It never infers upload success, never invents rows for
objects the store does not contain, and reports mismatches as-is.

Checks: artifacts, observations, events, annotations, sessions, plus
`operator_responses` — the structured in-flight operator responses
(CONFIRM | HOLD | ESCALATE_REQUEST, `operator-response.v1`):

- linkage is the stored `session_id` matched against this flight's boots
  (`uuid5("session|flight|boot")`, the same rule the RC uploader uses) —
  a response for another flight is never stamped with this one;
- the expected payload is rebuilt byte-exactly from the persisted RC action
  plus this flight id (same field/proof rules as the RC
  `OperatorResponsePayload`: non-escalation actions stay LOCAL_ONLY, a
  claim above REQUESTED without its proof degrades to UNKNOWN, proofs
  travel verbatim, `content_sha256` is recomputed) — no ids are invented;
- Core rows come from the read-only
  `GET /api/v1/edge/flights/{flightId}/operator-responses`; each linked
  local response must match its Core row canonically (content + digest),
  and every id a durable `response_batches.ndjson` receipt claims accepted
  must actually be present in Core. Wrong flight/site/source/delivery
  fields are a DISCREPANCY — counts are never compared alone;
- a local linked response no receipt covers yet is explicitly PENDING
  (same pattern as pending annotations): it is waiting, never "sent",
  and pending alone never claims delivery confirmation;
- when the Core query itself is unavailable (older Core without the route
  answers 404, or the read fails) and local responses or claimed receipts
  exist, the check is UNKNOWN (query unverified) — never a vacuous OK;
- no local responses and no claimed receipts means the optional route was
  simply unused: OK, with no fabricated zero-response row.

Statuses per check:
- OK         — record found and consistent
- MISSING    — RC recorded it, Core does not return it
- MISMATCH   — both sides present but they contradict (e.g. RC synced vs
               Core not committed, digest differs, evidence subset broken,
               response content differs, receipt claims an id Core lacks)
- PENDING    — RC has it but has not uploaded it yet (expected state)
- UNKNOWN    — the comparison cannot be made from available data
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from ksi_contracts import canonical_bytes, digest_excluding, verify_digest

from .rc_store_read import DeviceStore
from .rc_upload import session_id_for

SAMPLE_CAP = 10

RESPONSE_BATCH_KIND = "operator-responses"

RESPONSE_ACTIONS = ("CONFIRM", "HOLD", "ESCALATE_REQUEST")


@dataclass(frozen=True)
class Check:
    name: str
    status: str  # OK | MISSING | MISMATCH | PENDING | UNKNOWN
    detail: str = ""


@dataclass
class FlightReport:
    flight_id: str
    checks: list[Check] = field(default_factory=list)
    upload_state: str | None = None
    completeness: str | None = None

    @property
    def verdict(self) -> str:
        if any(c.status in ("MISSING", "MISMATCH") for c in self.checks):
            return "DISCREPANCY"
        if any(c.status == "UNKNOWN" for c in self.checks):
            return "INCOMPLETE"
        return "OK"


@dataclass
class Report:
    store_root: str
    flights: list[FlightReport] = field(default_factory=list)
    parse_notes: list[str] = field(default_factory=list)

    @property
    def verdict(self) -> str:
        if any(f.verdict == "DISCREPANCY" for f in self.flights):
            return "DISCREPANCY"
        if any(f.verdict == "INCOMPLETE" for f in self.flights):
            return "INCOMPLETE"
        return "OK"


class CoreReads(Protocol):
    """Read-only Core access needed for correlation (404 -> None)."""

    def flight(self, flight_id: str) -> dict | None: ...
    def artifacts(self, flight_id: str) -> list[dict]: ...
    def observations(self, flight_id: str) -> list[dict]: ...
    def events(self, flight_id: str) -> list[dict]: ...
    def evidence_ids(self, event_id: str, listed_event: dict) -> list[str] | None: ...
    def annotation_ids(self, event_id: str) -> list[str] | None: ...
    def sessions(self, flight_id: str) -> list[dict] | None: ...
    def operator_responses(self, flight_id: str) -> list[dict] | None:
        """Read-only GET .../operator-responses; None = query unverifiable
        (older Core 404 or read failure) — never an empty list."""


def correlate(
    store: DeviceStore,
    core: CoreReads,
    flight_ids: list[str] | None = None,
) -> Report:
    report = Report(store_root=str(store.root), parse_notes=list(store.parse_notes))
    selected = flight_ids or list(store.flights.keys())
    for flight_id in selected:
        report.flights.append(_correlate_flight(store, core, flight_id))
    return report


def _correlate_flight(store: DeviceStore, core: CoreReads, flight_id: str) -> FlightReport:
    report = FlightReport(flight_id=flight_id)
    core_flight = core.flight(flight_id)
    if core_flight is None:
        report.checks.append(Check("flight", "MISSING", "Core /flights/{id} 404"))
        return report
    report.upload_state = core_flight.get("upload_state")
    report.completeness = core_flight.get("completeness")
    report.checks.append(
        Check("flight", "OK", f"upload_state={report.upload_state}")
    )

    _check_artifacts(store, core, flight_id, report)
    _check_observations(store, core, flight_id, report)
    _check_events(store, core, flight_id, report)
    _check_annotations(store, core, flight_id, report)
    _check_sessions(store, core, flight_id, report)
    _check_operator_responses(store, core, flight_id, report)
    return report


def _check_artifacts(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    local = store.artifacts_for(flight_id)
    core_items = {a.get("artifact_id"): a for a in core.artifacts(flight_id)}
    missing, mismatched = [], []
    for aid, entry in sorted(local.items()):
        complete = store.artifact_complete.get(aid, False)
        synced = aid in store.synced
        item = core_items.get(aid)
        if item is None:
            if complete:
                missing.append(aid)
            continue
        if synced and not item.get("committed"):
            mismatched.append(f"{aid} (RC synced, Core committed=false)")
        declared = entry.node.get("sha256")
        if declared and item.get("sha256") and declared != item["sha256"]:
            mismatched.append(f"{aid} (sha256 differs)")
        if item.get("committed") and not complete:
            mismatched.append(f"{aid} (Core committed, store bytes incomplete)")
    status = "OK"
    detail = f"store {len(local)} · core {len(core_items)}"
    if missing:
        status = "MISSING"
        detail += f" · 미도달 {len(missing)}: {', '.join(missing[:SAMPLE_CAP])}"
    if mismatched:
        status = "MISMATCH"
        detail += f" · 모순 {len(mismatched)}: {'; '.join(mismatched[:SAMPLE_CAP])}"
    report.checks.append(Check("artifacts", status, detail))


def _check_observations(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    local = set(store.observations_for(flight_id).keys())
    core_ids = {
        o.get("observation_id") for o in core.observations(flight_id)
        if isinstance(o.get("observation_id"), str)
    }
    missing = sorted(local - core_ids)
    extra = sorted(core_ids - local)
    status = "OK"
    detail = f"store {len(local)} · core {len(core_ids)}"
    if missing:
        status = "MISSING"
        detail += f" · 미도달 {len(missing)}: {', '.join(missing[:SAMPLE_CAP])}"
    if extra:
        detail += f" · Core에만 있음 {len(extra)} (스토어 pull 시점 이후 업로드 가능)"
    report.checks.append(Check("observations", status, detail))


def _check_events(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    local = store.events_for(flight_id)
    core_items = {
        e.get("event_id"): e for e in core.events(flight_id)
        if isinstance(e.get("event_id"), str)
    }
    missing = sorted(set(local) - set(core_items))
    mismatch: list[str] = []
    unverified = 0
    for eid, entry in sorted(local.items()):
        item = core_items.get(eid)
        if item is None:
            continue
        declared = entry.node.get("evidence_artifact_ids") or []
        if declared:
            core_evidence = item.get("evidence_artifact_ids")
            if core_evidence is None:
                core_evidence = core.evidence_ids(eid, item)
            if core_evidence is None:
                unverified += 1
                continue
            missing_evidence = sorted(set(declared) - set(core_evidence))
            if missing_evidence:
                mismatch.append(f"{eid} (evidence {', '.join(missing_evidence[:3])})")
    status = "OK"
    detail = f"store {len(local)} · core {len(core_items)}"
    if missing:
        status = "MISSING"
        detail += f" · 미도달 {len(missing)}: {', '.join(missing[:SAMPLE_CAP])}"
    if mismatch:
        status = "MISMATCH"
        detail += f" · 증거 연결 모순 {len(mismatch)}: {'; '.join(mismatch[:SAMPLE_CAP])}"
    elif unverified:
        status = "UNKNOWN"
        detail += f" · 증거 연결 확인 불가 {unverified}"
    report.checks.append(Check("events", status, detail))


def _check_annotations(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    local = store.annotations_for(flight_id)
    if not local:
        report.checks.append(Check("annotations", "OK", "스토어에 의견 없음"))
        return
    missing_uploaded, missing_pending = [], []
    unknown_events = 0
    cache: dict[str, list[str] | None] = {}
    for aid, node in sorted(local.items()):
        event_id = node.get("event_id")
        if not isinstance(event_id, str):
            continue
        if event_id not in cache:
            cache[event_id] = core.annotation_ids(event_id)
        core_ids = cache[event_id]
        if core_ids is None:
            unknown_events += 1
            continue
        if aid in core_ids:
            continue
        if aid in store.annotation_uploads:
            missing_uploaded.append(aid)
        else:
            missing_pending.append(aid)
    status = "OK"
    detail = f"store {len(local)}"
    if missing_uploaded:
        status = "MISMATCH"
        detail += f" · 업로드 영수증 있으나 Core에 없음 {len(missing_uploaded)}: {', '.join(missing_uploaded[:SAMPLE_CAP])}"
    if missing_pending:
        if status == "OK":
            status = "PENDING"
        detail += f" · RC 대기(pending) {len(missing_pending)}"
    if unknown_events:
        if status == "OK":
            status = "UNKNOWN"
        detail += f" · 의견 조회 실패 이벤트 {unknown_events}"
    report.checks.append(Check("annotations", status, detail))


def _check_sessions(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    boots = set(store.boots_by_flight.get(flight_id, set()))
    sessions = core.sessions(flight_id)
    if sessions is None:
        report.checks.append(Check("sessions", "UNKNOWN", "Core 세션 조회 실패"))
        return
    core_ids = {
        s.get("session_id") for s in sessions if isinstance(s.get("session_id"), str)
    }
    if not boots:
        report.checks.append(
            Check("sessions", "UNKNOWN", f"core {len(core_ids)} · 스토어에 부트 기록 없음")
        )
        return
    expected = {session_id_for(flight_id, boot): boot for boot in boots}
    missing = sorted(sid for sid, _ in expected.items() if sid not in core_ids)
    status = "OK" if not missing else "MISSING"
    detail = f"store boots {len(boots)} · core sessions {len(core_ids)}"
    if missing:
        detail += f" · 미도달 {len(missing)}: {', '.join(missing[:SAMPLE_CAP])}"
    report.checks.append(Check("sessions", status, detail))


def effective_delivery_of(action: dict) -> str:
    """Proof-gated delivery claim for one persisted RC action.

    Exact mirror of the RC `OperatorActionLog.effectiveDeliveryOf` rule:
    non-escalation actions never carry a transmission state (LOCAL_ONLY);
    an escalation claim above REQUESTED without its proof degrades to
    UNKNOWN. Anything unrecognised degrades to UNKNOWN, never to "sent".
    """
    if action.get("action") != "ESCALATE_REQUEST":
        return "LOCAL_ONLY"
    delivery = action.get("delivery")
    if delivery in ("LOCAL_ONLY", "REQUESTED", "UNKNOWN"):
        return delivery
    has_tx = isinstance(action.get("transmission_proof"), str) and bool(action.get("transmission_proof"))
    has_rx = isinstance(action.get("receipt_proof"), str) and bool(action.get("receipt_proof"))
    has_done = isinstance(action.get("completion_proof"), str) and bool(action.get("completion_proof"))
    if delivery == "SENT_CONFIRMED":
        return delivery if has_tx else "UNKNOWN"
    if delivery == "RECEIVED_CONFIRMED":
        return delivery if (has_tx and has_rx) else "UNKNOWN"
    if delivery == "COMPLETED_CONFIRMED":
        return delivery if (has_tx and has_rx and has_done) else "UNKNOWN"
    return "UNKNOWN"


def _required_text(action: dict, field: str) -> str:
    value = action.get(field)
    if not isinstance(value, str) or not value:
        raise ValueError(f"response record without {field}")
    return value


def _optional_text(action: dict, field: str) -> str | None:
    value = action.get(field)
    return value if isinstance(value, str) else None


def operator_response_payload(action: dict, flight_id: str) -> dict:
    """Exact `operator-response.v1` payload for one persisted RC action.

    Same field/proof rules as the RC `OperatorResponsePayload.fromAction`:
    every id/fact comes from the stored action plus the caller's actual
    flight id — nothing is invented. Raises ValueError for an incomplete
    record (never serialised into a half-claim).
    """
    if not isinstance(flight_id, str) or not flight_id:
        raise ValueError("response record without flight_id")
    if action.get("action") not in RESPONSE_ACTIONS:
        raise ValueError(f"unknown action {action.get('action')!r}")
    note = _optional_text(action, "note")
    if note is not None and len(note) > 280:
        raise ValueError("operator note must be <= 280 chars")
    payload = {
        "schema_version": 1,
        "response_id": _required_text(action, "response_id"),
        "candidate_id": _required_text(action, "candidate_id"),
        "flight_id": flight_id,
        "site_id": _required_text(action, "site_id"),
        "session_id": _required_text(action, "session_id"),
        "action": action["action"],
        "note": note,
        "evidence": {
            "source_id": _required_text(action, "evidence_source_id"),
            "received_at_utc": _required_text(action, "evidence_received_at_utc"),
            "policy_id": _optional_text(action, "policy_id"),
            "policy_version": _optional_text(action, "policy_version"),
        },
        "acted_at_utc": _required_text(action, "acted_at_utc"),
        "delivery_state": effective_delivery_of(action),
        "transmission_proof": _optional_text(action, "transmission_proof"),
        "receipt_proof": _optional_text(action, "receipt_proof"),
        "completion_proof": _optional_text(action, "completion_proof"),
    }
    payload["content_sha256"] = digest_excluding(payload, "content_sha256")
    return payload


def _claimed_response_ids(receipts: list[dict]) -> set[str]:
    """Union of accepted + duplicate ids a flight's durable receipts claim."""
    claimed: set[str] = set()
    for receipt in receipts:
        for key in ("accepted_ids", "duplicate_ids"):
            ids = receipt.get(key)
            if isinstance(ids, list):
                claimed.update(i for i in ids if isinstance(i, str))
    return claimed


def _response_field_diffs(expected: dict, item: dict) -> str:
    diffs = [
        key for key in (
            "flight_id", "site_id", "session_id", "candidate_id", "action",
            "note", "acted_at_utc", "delivery_state", "transmission_proof",
            "receipt_proof", "completion_proof",
        )
        if expected.get(key) != item.get(key)
    ]
    expected_ev = expected.get("evidence") or {}
    item_ev = item.get("evidence") or {}
    diffs.extend(
        f"evidence.{key}" for key in (
            "source_id", "received_at_utc", "policy_id", "policy_version"
        )
        if expected_ev.get(key) != item_ev.get(key)
    )
    if expected.get("content_sha256") != item.get("content_sha256"):
        diffs.append("content_sha256")
    if set(item) - set(expected):
        diffs.append("여분키")
    return ", ".join(diffs[:8]) or "바이트"


def _linked_operator_actions(store: DeviceStore, flight_id: str) -> dict[str, dict]:
    """Persisted actions verifiably linked to this flight, in log order.

    Linkage is the stored session id matched against this flight's boots
    (same `uuid5("session|flight|boot")` rule the uploader uses).
    """
    sessions = {
        session_id_for(flight_id, boot)
        for boot in store.boots_by_flight.get(flight_id, set())
    }
    if not sessions:
        return {}
    return {
        rid: node
        for rid, node in store.operator_actions.items()
        if isinstance(node, dict) and node.get("session_id") in sessions
    }


def _check_operator_responses(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    read = getattr(core, "operator_responses", None)
    if read is None:
        # Legacy read fixture without the route: no column, no claim.
        return
    linked = _linked_operator_actions(store, flight_id)
    receipts = store.response_receipts_for(flight_id, RESPONSE_BATCH_KIND)
    claimed = _claimed_response_ids(receipts)
    if not linked and not claimed:
        report.checks.append(Check(
            "operator_responses", "OK",
            "로컬 응답 없음 · 청구 영수증 없음 (선택 경로, 오류 아님)",
        ))
        return
    core_rows = read(flight_id)
    if core_rows is None:
        report.checks.append(Check(
            "operator_responses", "UNKNOWN",
            f"Core 응답 조회 불가(구버전 Core 가능) — 미검증 · 로컬 {len(linked)}건 · "
            f"청구 {len(claimed)}건 · 대기분은 전송 확인 아님",
        ))
        return
    core_by_id = {
        row.get("response_id"): row for row in core_rows
        if isinstance(row, dict) and isinstance(row.get("response_id"), str)
    }
    mismatched: list[str] = []
    pending: list[str] = []
    for rid, node in sorted(linked.items()):
        try:
            expected = operator_response_payload(node, flight_id)
        except ValueError as exc:
            mismatched.append(f"{rid} (로컬 기록 직렬화 불가: {exc})")
            continue
        item = core_by_id.get(rid)
        if item is None:
            if rid in claimed:
                mismatched.append(f"{rid} (영수증에는 수락됐으나 Core에 없음)")
            else:
                pending.append(rid)
            continue
        if not verify_digest(item, "content_sha256"):
            mismatched.append(f"{rid} (Core 행 digest 불일치)")
            continue
        if canonical_bytes(expected) != canonical_bytes(item):
            mismatched.append(f"{rid} (내용 불일치: {_response_field_diffs(expected, item)})")
    for cid in sorted(claimed):
        if cid not in core_by_id:
            mismatched.append(f"{cid} (영수증 청구 id가 Core에 없음)")
    extra = sorted(set(core_by_id) - set(linked))
    status = "OK"
    detail = f"store {len(linked)} · core {len(core_by_id)} · 영수증 {len(receipts)}건"
    if mismatched:
        status = "MISMATCH"
        detail += f" · 모순 {len(mismatched)}: {'; '.join(mismatched[:SAMPLE_CAP])}"
    if pending:
        if status == "OK":
            status = "PENDING"
        detail += (
            f" · RC 대기(pending) {len(pending)}: {', '.join(pending[:SAMPLE_CAP])}"
            " — 전송 확인 아님 ('sent' 아님)"
        )
    if extra:
        detail += f" · Core에만 있음 {len(extra)} (스토어 pull 시점 이후 업로드 가능)"
    report.checks.append(Check("operator_responses", status, detail))


def render_text(report: Report, core_url: str) -> str:
    """Human-readable evidence text (the same facts as the dataclasses)."""
    lines = [
        "# RC → Core 대조 보고서",
        f"store: {report.store_root}",
        f"core: {core_url}",
        f"판정: {report.verdict}",
    ]
    if report.parse_notes:
        lines.append(f"스토어 파싱 주의 {len(report.parse_notes)}건:")
        lines.extend(f"- {note}" for note in report.parse_notes[:SAMPLE_CAP])
    for flight in report.flights:
        lines.append("")
        lines.append(f"## {flight.flight_id} — {flight.verdict}")
        lines.append(
            f"- upload_state={flight.upload_state} · completeness={flight.completeness}"
        )
        for check in flight.checks:
            detail = f" — {check.detail}" if check.detail else ""
            lines.append(f"- {check.name}: {check.status}{detail}")
    lines.append("")
    lines.append(
        "이 보고서는 RC 저장소 기록과 Core 응답의 일치만 판정한다. 현장 원본의 진위, "
        "운영 인증, 안전 판정은 포함하지 않는다."
    )
    return "\n".join(lines)
