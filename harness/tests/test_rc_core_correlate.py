"""Host tests for the RC-store ↔ Core correlation (pure, no HTTP)."""

from __future__ import annotations

import json
from pathlib import Path

from ksi_harness.rc_core_correlate import correlate, render_text
from ksi_harness.rc_store_read import load_device_store
from ksi_harness.rc_upload import session_id_for

FLIGHT = "00000000-0000-4000-8000-000000000001"


class FakeCore:
    """Dict-driven stand-in for the Core reads protocol."""

    def __init__(self) -> None:
        self.flight_view: dict | None = {"upload_state": "COMPLETE", "completeness": "COMPLETE"}
        self.artifact_rows: list[dict] = []
        self.observation_rows: list[dict] = []
        self.event_rows: list[dict] = []
        self.evidence: dict[str, list[str] | None] = {}
        self.annotation_rows: dict[str, list[str] | None] = {}
        self.session_rows: list[dict] | None = []

    def flight(self, flight_id: str) -> dict | None:
        return self.flight_view

    def artifacts(self, flight_id: str) -> list[dict]:
        return self.artifact_rows

    def observations(self, flight_id: str) -> list[dict]:
        return self.observation_rows

    def events(self, flight_id: str) -> list[dict]:
        return self.event_rows

    def evidence_ids(self, event_id: str, listed_event: dict) -> list[str] | None:
        return self.evidence.get(event_id, [])

    def annotation_ids(self, event_id: str) -> list[str] | None:
        return self.annotation_rows.get(event_id, [])

    def sessions(self, flight_id: str) -> list[dict] | None:
        return self.session_rows


def _write(path: Path, nodes: list[dict]) -> None:
    path.write_text("\n".join(json.dumps(n) for n in nodes) + "\n", encoding="utf-8")


def _store(tmp_path: Path, *, artifact_sha: str = "ab" * 32, synced: bool = True) -> None:
    _write(tmp_path / "flights.ndjson", [{"flight_id": FLIGHT, "state": "ENDED"}])
    _write(
        tmp_path / "observations.ndjson",
        [{"boot_id": "b-1", "node": {"observation_id": "o-1", "flight_id": FLIGHT},
          "received_wall_utc": "2026-10-13T00:00:00Z"}],
    )
    _write(
        tmp_path / "events.ndjson",
        [{"boot_id": "b-1", "node": {"event_id": "e-1", "flight_id": FLIGHT,
                                     "evidence_artifact_ids": ["a-1"]},
          "received_wall_utc": "2026-10-13T00:00:01Z"}],
    )
    _write(
        tmp_path / "artifacts.ndjson",
        [{"boot_id": "b-1", "node": {"artifact_id": "a-1", "flight_id": FLIGHT,
                                     "size_bytes": 4, "sha256": artifact_sha},
          "received_wall_utc": "2026-10-13T00:00:02Z"}],
    )
    _write(tmp_path / "chunks.ndjson", [{"artifact_id": "a-1", "offset": 0, "length": 4}])
    if synced:
        (tmp_path / "synced.ndjson").write_text("a-1\n", encoding="utf-8")
    _write(tmp_path / "messages.ndjson", [{"type": "HEARTBEAT", "flight_id": FLIGHT,
                                           "edge_boot_id": "b-1"}])


def _core_ok(artifact_sha: str = "ab" * 32) -> FakeCore:
    core = FakeCore()
    core.artifact_rows = [
        {"artifact_id": "a-1", "sha256": artifact_sha, "committed": True}
    ]
    core.observation_rows = [{"observation_id": "o-1"}]
    core.event_rows = [{"event_id": "e-1", "evidence_artifact_ids": ["a-1"]}]
    core.session_rows = [{"session_id": session_id_for(FLIGHT, "b-1")}]
    return core


def test_all_present_is_ok(tmp_path: Path) -> None:
    _store(tmp_path)
    report = correlate(load_device_store(tmp_path), _core_ok())
    assert report.verdict == "OK"
    statuses = {c.name: c.status for c in report.flights[0].checks}
    assert statuses == {
        "flight": "OK", "artifacts": "OK", "observations": "OK",
        "events": "OK", "annotations": "OK", "sessions": "OK",
    }
    text = render_text(report, "http://core")
    assert "판정: OK" in text and FLIGHT in text


def test_missing_artifact_is_a_discrepancy(tmp_path: Path) -> None:
    _store(tmp_path)
    core = _core_ok()
    core.artifact_rows = []
    report = correlate(load_device_store(tmp_path), core)
    assert report.verdict == "DISCREPANCY"
    artifacts = next(c for c in report.flights[0].checks if c.name == "artifacts")
    assert artifacts.status == "MISSING" and "a-1" in artifacts.detail


def test_synced_but_not_committed_is_a_mismatch(tmp_path: Path) -> None:
    _store(tmp_path)
    core = _core_ok()
    core.artifact_rows = [
        {"artifact_id": "a-1", "sha256": "ab" * 32, "committed": False}
    ]
    report = correlate(load_device_store(tmp_path), core)
    assert report.verdict == "DISCREPANCY"
    artifacts = next(c for c in report.flights[0].checks if c.name == "artifacts")
    assert artifacts.status == "MISMATCH" and "committed=false" in artifacts.detail


def test_digest_difference_is_a_mismatch(tmp_path: Path) -> None:
    _store(tmp_path)
    core = _core_ok(artifact_sha="cd" * 32)
    report = correlate(load_device_store(tmp_path), core)
    artifacts = next(c for c in report.flights[0].checks if c.name == "artifacts")
    assert artifacts.status == "MISMATCH" and "sha256" in artifacts.detail


def test_missing_event_and_broken_evidence_link(tmp_path: Path) -> None:
    _store(tmp_path)
    core = _core_ok()
    core.event_rows = []
    report = correlate(load_device_store(tmp_path), core)
    events = next(c for c in report.flights[0].checks if c.name == "events")
    assert events.status == "MISSING"

    core = _core_ok()
    core.event_rows = [{"event_id": "e-1", "evidence_artifact_ids": []}]
    report = correlate(load_device_store(tmp_path), core)
    events = next(c for c in report.flights[0].checks if c.name == "events")
    assert events.status == "MISMATCH" and "evidence" in events.detail


def test_uploaded_annotation_missing_is_mismatch_pending_is_not(tmp_path: Path) -> None:
    _store(tmp_path)
    _write(
        tmp_path / "annotations.ndjson",
        [
            {"annotation_id": "ann-up", "event_id": "e-1", "flight_id": FLIGHT},
            {"annotation_id": "ann-pending", "event_id": "e-1", "flight_id": FLIGHT},
        ],
    )
    (tmp_path / "annotation_uploads.ndjson").write_text("ann-up\n", encoding="utf-8")

    core = _core_ok()
    core.annotation_rows = {"e-1": []}
    report = correlate(load_device_store(tmp_path), core)
    annotations = next(c for c in report.flights[0].checks if c.name == "annotations")
    assert annotations.status == "MISMATCH"
    assert "ann-up" in annotations.detail and "pending" in annotations.detail
    assert report.verdict == "DISCREPANCY"

    core = _core_ok()
    core.annotation_rows = {"e-1": ["ann-up"]}
    report = correlate(load_device_store(tmp_path), core)
    annotations = next(c for c in report.flights[0].checks if c.name == "annotations")
    assert annotations.status == "PENDING"
    assert report.verdict == "OK"


def test_missing_observation_id_and_session_boot(tmp_path: Path) -> None:
    _store(tmp_path)
    core = _core_ok()
    core.observation_rows = []
    core.session_rows = []
    report = correlate(load_device_store(tmp_path), core)
    checks = {c.name: c.status for c in report.flights[0].checks}
    assert checks["observations"] == "MISSING"
    assert checks["sessions"] == "MISSING"


def test_flight_not_in_core_is_missing(tmp_path: Path) -> None:
    _store(tmp_path)
    core = _core_ok()
    core.flight_view = None
    report = correlate(load_device_store(tmp_path), core)
    assert report.verdict == "DISCREPANCY"
    assert report.flights[0].checks[0].status == "MISSING"


def test_sessions_unknown_without_boot_records(tmp_path: Path) -> None:
    _store(tmp_path)
    core = _core_ok()
    core.session_rows = None
    report = correlate(load_device_store(tmp_path), core)
    sessions = next(c for c in report.flights[0].checks if c.name == "sessions")
    assert sessions.status == "UNKNOWN"
