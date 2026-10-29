"""Host tests for the RC-store ↔ Core operator-responses check (pure, no HTTP).

Covers the read-only extension only: real `operator_actions.ndjson` /
`response_batches.ndjson` parse shapes (incl. tar), success, wrong-content,
claimed-but-missing receipts, pending-not-sent, old-Core-404, and the
unused-route OK. Uses the production payload builder and digest helpers —
no invented ids, no uploads.
"""

from __future__ import annotations

import json
import tarfile
from pathlib import Path

from ksi_contracts import verify_digest

from ksi_harness.rc_core_correlate import (
    Check,
    FlightReport,
    Report,
    RESPONSE_BATCH_KIND,
    correlate,
    effective_delivery_of,
    operator_response_payload,
    render_text,
)
from ksi_harness.rc_store_read import extract_tar, load_device_store
from ksi_harness.rc_upload import session_id_for

FLIGHT = "00000000-0000-4000-8000-000000000001"
BOOT = "boot-1"
SESSION = session_id_for(FLIGHT, BOOT)
SITE = "site-1"
CANDIDATE = "cand-1"
SOURCE = "src-1"
RID_OK = "11111111-1111-4111-8111-000000000001"
RID_PENDING = "11111111-1111-4111-8111-000000000002"
RID_WRONG = "11111111-1111-4111-8111-000000000003"
RID_GHOST = "11111111-1111-4111-8111-000000000004"


def test_unknown_query_is_incomplete_and_real_mismatch_takes_precedence():
    unknown = FlightReport(FLIGHT, checks=[Check("operator_responses", "UNKNOWN", "query unavailable")])
    pending = FlightReport("another-flight", checks=[Check("operator_responses", "PENDING", "not uploaded")])
    report = Report("readonly", flights=[unknown, pending])
    assert unknown.verdict == "INCOMPLETE"
    assert pending.verdict == "OK"
    assert report.verdict == "INCOMPLETE"
    assert "판정: INCOMPLETE" in render_text(report, "http://core")
    unknown.checks.append(Check("artifacts", "MISMATCH", "digest mismatch"))
    assert report.verdict == "DISCREPANCY"


class FakeCore:
    """Dict-driven stand-in for the Core reads protocol (incl. responses)."""

    def __init__(self) -> None:
        self.flight_view: dict | None = {"upload_state": "COMPLETE", "completeness": "COMPLETE"}
        self.response_rows: list[dict] | None = []

    def flight(self, flight_id: str) -> dict | None:
        return self.flight_view

    def artifacts(self, flight_id: str) -> list[dict]:
        return []

    def observations(self, flight_id: str) -> list[dict]:
        return []

    def events(self, flight_id: str) -> list[dict]:
        return []

    def evidence_ids(self, event_id: str, listed_event: dict) -> list[str] | None:
        return []

    def annotation_ids(self, event_id: str) -> list[str] | None:
        return []

    def sessions(self, flight_id: str) -> list[dict] | None:
        return [{"session_id": SESSION}]

    def operator_responses(self, flight_id: str) -> list[dict] | None:
        return self.response_rows


def _action(rid: str, **over: object) -> dict:
    node = {
        "schema_version": 1,
        "response_id": rid,
        "candidate_id": CANDIDATE,
        "site_id": SITE,
        "session_id": SESSION,
        "action": "ESCALATE_REQUEST",
        "note": None,
        "evidence_source_id": SOURCE,
        "evidence_received_at_utc": "2026-10-20T02:10:00Z",
        "policy_id": "pol-1",
        "policy_version": "v1",
        "acted_at_utc": "2026-10-20T02:11:00Z",
        "delivery": "REQUESTED",
        "transmission_proof": None,
        "receipt_proof": None,
        "completion_proof": None,
    }
    node.update(over)
    return node


def _receipt(flight_id: str, batch: str, ids: list[str]) -> dict:
    return {
        "schema_version": 1,
        "kind": RESPONSE_BATCH_KIND,
        "flight_id": flight_id,
        "client_batch_id": batch,
        "content_hash": "ab" * 32,
        "server_batch_id": "srv-1",
        "accepted_ids": ids,
        "duplicate_ids": [],
    }


def _write(path: Path, nodes: list[dict]) -> None:
    path.write_text("\n".join(json.dumps(n) for n in nodes) + "\n", encoding="utf-8")


def _store(tmp_path: Path, actions: list[dict] | None = None,
           receipts: list[dict] | None = None) -> None:
    _write(tmp_path / "flights.ndjson", [{"flight_id": FLIGHT, "state": "ENDED"}])
    _write(tmp_path / "messages.ndjson",
           [{"type": "HEARTBEAT", "flight_id": FLIGHT, "edge_boot_id": BOOT}])
    if actions is not None:
        _write(tmp_path / "operator_actions.ndjson", actions)
    if receipts is not None:
        _write(tmp_path / "response_batches.ndjson", receipts)


def _check(report, name: str = "operator_responses"):
    return next(c for c in report.flights[0].checks if c.name == name)


def test_store_reads_real_formats_and_tar_layout(tmp_path: Path) -> None:
    _store(tmp_path, actions=[_action(RID_OK), _action(RID_OK, note="dup")],
           receipts=[_receipt(FLIGHT, "b-1", [RID_OK])])
    # Receipt without server_batch_id is skipped exactly like FileRcStore.
    with (tmp_path / "response_batches.ndjson").open("a", encoding="utf-8") as fh:
        fh.write(json.dumps({"kind": RESPONSE_BATCH_KIND, "flight_id": FLIGHT,
                             "client_batch_id": "b-2"}) + "\n")
    store = load_device_store(tmp_path)
    assert store.operator_actions[RID_OK]["note"] is None  # first write wins
    assert [r["client_batch_id"] for r in store.response_receipts_for(FLIGHT)] == ["b-1"]
    assert any("response_batches" in n for n in store.parse_notes)

    tar_path = tmp_path / "rc-store.tar"
    with tarfile.open(tar_path, "w") as tar:
        for name in ("flights.ndjson", "messages.ndjson",
                     "operator_actions.ndjson", "response_batches.ndjson"):
            tar.add(tmp_path / name, arcname=name)
    dest = tmp_path / "extracted"
    dest.mkdir()
    extract_tar(tar_path, dest)
    via_tar = load_device_store(dest)
    assert set(via_tar.operator_actions) == {RID_OK}
    assert len(via_tar.response_receipts_for(FLIGHT)) == 1


def test_no_responses_and_none_claimed_is_ok(tmp_path: Path) -> None:
    _store(tmp_path)
    report = correlate(load_device_store(tmp_path), FakeCore())
    check = _check(report)
    assert check.status == "OK" and report.verdict == "OK"


def test_linked_response_matching_core_is_ok(tmp_path: Path) -> None:
    action = _action(RID_OK)
    _store(tmp_path, actions=[action], receipts=[_receipt(FLIGHT, "b-1", [RID_OK])])
    core = FakeCore()
    core.response_rows = [operator_response_payload(action, FLIGHT)]
    report = correlate(load_device_store(tmp_path), core)
    check = _check(report)
    assert check.status == "OK" and report.verdict == "OK"
    assert "store 1 · core 1" in check.detail
    text = render_text(report, "http://core")
    assert "operator_responses: OK" in text


def test_id_present_with_wrong_content_is_mismatch(tmp_path: Path) -> None:
    action = _action(RID_WRONG)
    _store(tmp_path, actions=[action], receipts=[_receipt(FLIGHT, "b-1", [RID_WRONG])])
    tampered = operator_response_payload(action, FLIGHT)
    tampered["site_id"] = "other-site"
    tampered["delivery_state"] = "SENT_CONFIRMED"
    tampered["transmission_proof"] = "forged"
    from ksi_contracts import set_digest
    tampered = set_digest(tampered, "content_sha256")  # self-consistent, wrong content
    core = FakeCore()
    core.response_rows = [tampered]
    report = correlate(load_device_store(tmp_path), core)
    check = _check(report)
    assert check.status == "MISMATCH" and report.verdict == "DISCREPANCY"
    assert "site_id" in check.detail and "delivery_state" in check.detail


def test_core_row_with_bad_digest_is_mismatch(tmp_path: Path) -> None:
    action = _action(RID_OK)
    _store(tmp_path, actions=[action], receipts=[_receipt(FLIGHT, "b-1", [RID_OK])])
    row = operator_response_payload(action, FLIGHT)
    row["content_sha256"] = "00" * 32
    core = FakeCore()
    core.response_rows = [row]
    report = correlate(load_device_store(tmp_path), core)
    assert _check(report).status == "MISMATCH"
    assert report.verdict == "DISCREPANCY"


def test_claimed_receipt_missing_in_core_is_mismatch(tmp_path: Path) -> None:
    _store(tmp_path, actions=[_action(RID_GHOST)],
           receipts=[_receipt(FLIGHT, "b-1", [RID_GHOST])])
    report = correlate(load_device_store(tmp_path), FakeCore())
    check = _check(report)
    assert check.status == "MISMATCH" and report.verdict == "DISCREPANCY"
    assert RID_GHOST in check.detail and "Core에 없음" in check.detail


def test_pending_actions_are_pending_never_sent(tmp_path: Path) -> None:
    _store(tmp_path, actions=[_action(RID_PENDING)])  # no receipt: not yet uploaded
    report = correlate(load_device_store(tmp_path), FakeCore())
    check = _check(report)
    assert check.status == "PENDING" and report.verdict == "OK"
    assert "pending" in check.detail and "전송 확인 아님" in check.detail


def test_old_core_404_is_unverified_never_vacuous_ok(tmp_path: Path) -> None:
    _store(tmp_path, actions=[_action(RID_PENDING)],
           receipts=[_receipt(FLIGHT, "b-1", [RID_PENDING])])
    core = FakeCore()
    core.response_rows = None  # 404 route: older Core
    report = correlate(load_device_store(tmp_path), core)
    check = _check(report)
    assert check.status == "UNKNOWN"  # not OK
    assert "미검증" in check.detail
    assert report.verdict == "INCOMPLETE"  # unavailable query is not verified correlation


def test_receipt_claim_present_in_core_without_local_record(tmp_path: Path) -> None:
    action = _action(RID_OK)
    _store(tmp_path, receipts=[_receipt(FLIGHT, "b-1", [RID_OK])])  # log line pruned
    core = FakeCore()
    core.response_rows = [operator_response_payload(action, FLIGHT)]
    report = correlate(load_device_store(tmp_path), core)
    check = _check(report)
    assert check.status == "OK" and report.verdict == "OK"
    assert "Core에만 있음" in check.detail


def test_effective_delivery_mirrors_rc_rule() -> None:
    base = _action(RID_OK)
    assert effective_delivery_of(base) == "REQUESTED"
    confirm_sent = _action(RID_OK, action="CONFIRM", delivery="SENT_CONFIRMED",
                           transmission_proof="ack")
    assert effective_delivery_of(confirm_sent) == "LOCAL_ONLY"
    proofless = _action(RID_OK, delivery="SENT_CONFIRMED")
    assert effective_delivery_of(proofless) == "UNKNOWN"
    proven = _action(RID_OK, delivery="RECEIVED_CONFIRMED",
                     transmission_proof="t", receipt_proof="r")
    assert effective_delivery_of(proven) == "RECEIVED_CONFIRMED"
    assert effective_delivery_of(_action(RID_OK, delivery="BOGUS")) == "UNKNOWN"


def test_payload_builder_is_contract_exact() -> None:
    payload = operator_response_payload(_action(RID_OK), FLIGHT)
    assert set(payload) == {
        "schema_version", "response_id", "candidate_id", "flight_id",
        "site_id", "session_id", "action", "note", "evidence",
        "acted_at_utc", "delivery_state", "transmission_proof",
        "receipt_proof", "completion_proof", "content_sha256",
    }
    assert set(payload["evidence"]) == {
        "source_id", "received_at_utc", "policy_id", "policy_version"}
    assert payload["flight_id"] == FLIGHT
    assert verify_digest(payload, "content_sha256")
    nulls = operator_response_payload(_action(RID_OK, note=None), FLIGHT)
    assert nulls["note"] is None and nulls["transmission_proof"] is None
