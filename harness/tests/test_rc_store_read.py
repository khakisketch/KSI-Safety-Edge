"""Host tests for the pulled device-store reader (read-only, no repair)."""

from __future__ import annotations

import json
from pathlib import Path

from ksi_harness.rc_store_read import load_device_store


def _write_ndjson(path: Path, nodes: list) -> None:
    path.write_text(
        "\n".join(json.dumps(n) for n in nodes) + "\n", encoding="utf-8"
    )


def _boot_node(node: dict, boot_id: str = "boot-1") -> dict:
    return {"boot_id": boot_id, "node": node, "received_wall_utc": "2026-10-13T00:00:00Z"}


def test_latest_flight_snapshot_wins_and_wrappers_parse(tmp_path: Path) -> None:
    _write_ndjson(
        tmp_path / "flights.ndjson",
        [
            {"flight_id": "f-1", "state": "STARTED"},
            {"flight_id": "f-1", "state": "ENDED", "ended_at_utc": "2026-10-13T01:00:00Z"},
        ],
    )
    _write_ndjson(
        tmp_path / "observations.ndjson",
        [_boot_node({"observation_id": "o-1", "flight_id": "f-1"})],
    )
    _write_ndjson(
        tmp_path / "events.ndjson",
        [_boot_node({"event_id": "e-1", "flight_id": "f-1"})],
    )

    store = load_device_store(tmp_path)
    assert store.flights["f-1"]["state"] == "ENDED"
    assert store.observations["o-1"].boot_id == "boot-1"
    assert store.observations_for("f-1")["o-1"].node["observation_id"] == "o-1"
    assert store.events_for("f-1")["e-1"].node["flight_id"] == "f-1"
    assert store.parse_notes == []


def test_coverage_is_complete_only_for_the_exact_full_span(tmp_path: Path) -> None:
    _write_ndjson(
        tmp_path / "artifacts.ndjson",
        [
            _boot_node({"artifact_id": "a-full", "flight_id": "f-1", "size_bytes": 8}),
            _boot_node({"artifact_id": "a-hole", "flight_id": "f-1", "size_bytes": 8}),
            _boot_node({"artifact_id": "a-none", "flight_id": "f-1", "size_bytes": 8}),
        ],
    )
    _write_ndjson(
        tmp_path / "chunks.ndjson",
        [
            {"artifact_id": "a-full", "offset": 4, "length": 4},
            {"artifact_id": "a-full", "offset": 0, "length": 4},
            {"artifact_id": "a-hole", "offset": 0, "length": 4},
            {"artifact_id": "a-hole", "offset": 6, "length": 2},
        ],
    )

    store = load_device_store(tmp_path)
    assert store.artifact_complete == {"a-full": True, "a-hole": False, "a-none": False}


def test_malformed_lines_are_reported_not_repaired(tmp_path: Path) -> None:
    (tmp_path / "observations.ndjson").write_text(
        '{"boot_id": "b", "node": {"observation_id": "o-1", "flight_id": "f-1"}}\n'
        "not-json\n"
        '{"boot_id": "b", "node": {"flight_id": "f-1"}}\n',
        encoding="utf-8",
    )
    store = load_device_store(tmp_path)
    assert len(store.observations) == 1
    assert any("line 2 malformed" in note for note in store.parse_notes)
    assert any("without observation_id" in note for note in store.parse_notes)


def test_boots_and_receipts_parse(tmp_path: Path) -> None:
    _write_ndjson(
        tmp_path / "messages.ndjson",
        [
            {"type": "HEARTBEAT", "flight_id": "f-1", "edge_boot_id": "b-1"},
            {"type": "OBSERVATION", "flight_id": "f-1", "edge_boot_id": "b-1"},
            {"type": "OBSERVATION", "flight_id": "f-1", "edge_boot_id": "b-2"},
        ],
    )
    (tmp_path / "synced.ndjson").write_text("a-1\na-2\n", encoding="utf-8")
    (tmp_path / "annotation_uploads.ndjson").write_text("ann-1\n", encoding="utf-8")
    _write_ndjson(
        tmp_path / "batch_receipts.ndjson",
        [{"kind": "observations", "client_batch_id": "b-1", "server_batch_id": "s-1"}],
    )
    for name, node in (
        ("annotations.ndjson", {"annotation_id": "ann-1", "event_id": "e-1", "flight_id": "f-1"}),
        ("gaps.ndjson", {"flight_id": "f-1", "edge_boot_id": "b-1", "channel": "EVENT_RELIABLE",
                         "seq": 9, "reason": "UNRECOVERABLE"}),
    ):
        _write_ndjson(tmp_path / name, [node])

    store = load_device_store(tmp_path)
    assert store.boots_by_flight == {"f-1": {"b-1", "b-2"}}
    assert store.synced == {"a-1", "a-2"}
    assert store.annotation_uploads == {"ann-1"}
    assert ("observations", "b-1") in store.batch_receipts
    assert store.annotations["ann-1"]["event_id"] == "e-1"
    assert len(store.gaps) == 1
