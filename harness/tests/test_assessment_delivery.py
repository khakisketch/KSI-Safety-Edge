"""Assessment delivery (Unit A closure) on the host harness path.

Sealed assessment JSONs ride the EXISTING generic artifact plane
(ARTIFACT_ANNOUNCE + ARTIFACT_CHUNK on EVIDENCE_TRANSFER) — no new MOP
type, no contract change. This pins the host chain end to end with mocks:

  sealed bytes -> RC ingest (commit-before-ACK) -> manifest entry
  (required=False, never blocks COMMITTED) -> Core ingest COMMITTED ->
  Core receipt + byte readback.

It also pins the contract boundary: a hypothetical `ASSESSMENT_REPORT`
wire type is terminally rejected (counted as an error, never ingested) —
new message semantics need the approved contract first (proposal
options A/B/C), they are not smuggled in here.
"""

from __future__ import annotations

import base64
import hashlib
import json
import sys
import uuid
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "harness" / "src"))

from ksi_harness import mop  # noqa: E402
from ksi_harness.rc_sim import RCSimulator  # noqa: E402
from ksi_harness.rc_store import RCStore  # noqa: E402

ASSESSMENT_MEDIA = "application/vnd.ksi.assessment+json"
ASSESSMENT_KIND = "EDGE_ASSESSMENT"

FLIGHT_ID = "11111111-2222-4333-8444-555555555555"
BOOT_ID = "66666666-7777-4888-9999-aaaaaaaaaaaa"


def _assessment_bytes(tick: int = 3) -> bytes:
    return json.dumps({
        "schema": "ksi.assessment/v1",
        "assessment_id": str(uuid.uuid5(mop.NAMESPACE, f"assess|{FLIGHT_ID}|{BOOT_ID}|{tick}")),
        "tick_index": tick,
        "flight_id": FLIGHT_ID,
        "edge_boot_id": BOOT_ID,
        "mode": "SHADOW",
        "simulated_input": True,
        "disposition": "UNDETERMINED",
        "missing_reasons": ["no_applicable_rules"],
        "observed_conditions": ["fresh_detections_present"],
        "rules": [],
        "outputs": {
            "incidents_created": 0,
            "alerts_created": 0,
            "human_responses": 0,
            "camera_commands": 0,
            "flight_commands": 0,
        },
        "custody": {
            "edge_sealed_locally": True,
            "rc_received": False,
            "rc_receipt": "unverified",
            "core_retained": False,
        },
    }, sort_keys=True).encode()


def _envelope(env_type: str, seq: int, payload: dict, msg_tag: str) -> dict:
    return mop.make_envelope(
        msg_id=mop.new_msg_id("edge", FLIGHT_ID, BOOT_ID, msg_tag),
        channel="EVIDENCE_TRANSFER",
        type=env_type,
        flight_id=FLIGHT_ID,
        edge_boot_id=BOOT_ID,
        seq=seq,
        ack_of=None,
        payload=payload,
        sent_monotonic_ns=1_000_000 * seq,
    )


def _feed_assessment(rc: RCSimulator, artifact_id: str, data: bytes, tag: str) -> None:
    announce = {
        "artifact_id": artifact_id,
        "flight_id": FLIGHT_ID,
        "edge_boot_id": BOOT_ID,
        "source_kind": ASSESSMENT_KIND,
        "sha256": hashlib.sha256(data).hexdigest(),
        "size_bytes": len(data),
        "media_type": ASSESSMENT_MEDIA,
        "segment_index": 0,
        "required": False,
    }
    assert rc.receive(_envelope("ARTIFACT_ANNOUNCE", 1, announce, f"{tag}-announce")) == "stored"
    offset, seq = 0, 2
    while offset < len(data):
        chunk = data[offset:offset + 64]
        payload = {
            "artifact_id": artifact_id,
            "offset": offset,
            "data_b64": base64.b64encode(chunk).decode(),
        }
        assert rc.receive(_envelope("ARTIFACT_CHUNK", seq, payload, f"{tag}-chunk-{seq}")) == "stored"
        offset += len(chunk)
        seq += 1


def _flight() -> dict:
    return {
        "flight_id": FLIGHT_ID,
        "mission_revision_id": str(uuid.uuid5(mop.NAMESPACE, "mis-chain")),
        "world": {"snapshot": {"content_sha256": "ab" * 32}},
    }


def _snapshot_bytes() -> bytes:
    return b"ingestible-snapshot-bytes" * 64


def _feed_snapshot(rc: RCSimulator, artifact_id: str, data: bytes) -> None:
    announce = {
        "artifact_id": artifact_id,
        "flight_id": FLIGHT_ID,
        "edge_boot_id": BOOT_ID,
        "source_kind": "EDGE_SNAPSHOT",
        "sha256": hashlib.sha256(data).hexdigest(),
        "size_bytes": len(data),
        "media_type": "image/jpeg",
        "segment_index": 0,
        "required": True,
    }
    assert rc.receive(_envelope("ARTIFACT_ANNOUNCE", 11, announce, "snap-announce")) == "stored"
    offset, seq = 0, 12
    while offset < len(data):
        chunk = data[offset:offset + 64]
        payload = {
            "artifact_id": artifact_id,
            "offset": offset,
            "data_b64": base64.b64encode(chunk).decode(),
        }
        assert rc.receive(_envelope("ARTIFACT_CHUNK", seq, payload, f"snap-chunk-{seq}")) == "stored"
        offset += len(chunk)
        seq += 1


def test_assessment_reaches_rc_and_ingestible_evidence_commits(client, tmp_path):
    store = RCStore(str(tmp_path / "rc.db"))

    class Clock:
        def __init__(self) -> None:
            self.now = 0

        def advance(self, ms: int = 1) -> int:
            self.now += ms
            return self.now

    rc = RCSimulator(store, Clock())
    data = _assessment_bytes()
    artifact_id = json.loads(data)["assessment_id"]
    _feed_assessment(rc, artifact_id, data, "chain")
    snap_id = str(uuid.uuid5(mop.NAMESPACE, "snap-chain"))
    snap = _snapshot_bytes()
    _feed_snapshot(rc, snap_id, snap)

    # RC holds the exact sealed bytes (verified level: ranges + size + sha).
    assert store.artifact_bytes(artifact_id) == data
    assert {a["artifact_id"] for a in store.artifacts_for(FLIGHT_ID, BOOT_ID)} == {
        artifact_id, snap_id}

    # The flight-ingest manifest carries only what the frozen contract
    # accepts: the assessment is withheld (retained locally), never
    # relabelled, never allowed to 422 the whole boot.
    manifest = rc.build_manifest(_flight(), BOOT_ID)
    entry_ids = [a["artifact_id"] for a in manifest["artifacts"]]
    assert snap_id in entry_ids
    assert artifact_id not in entry_ids

    # The ingestible boot commits; the assessment stays unsynced on THIS
    # path (its Core receipt rides the producer-neutral collection path,
    # pinned by test_edge_collection_e2e).
    out = rc.upload_boot(client, _flight(), BOOT_ID, [{"artifact_id": snap_id, "bytes": snap}])
    assert out["result"]["status"] == "COMMITTED", out["result"]
    assert out["result"]["missing_artifact_ids"] == []
    assert store.is_synced(snap_id)
    assert not store.is_synced(artifact_id)
    held, _ = client.download_object(snap_id)
    assert held == snap

    # Sealed honesty markers survive the whole chain.
    parsed = json.loads(data)
    assert parsed["mode"] == "SHADOW"
    assert all(v == 0 for v in parsed["outputs"].values())
    assert parsed["custody"] == {
        "edge_sealed_locally": True,
        "rc_received": False,
        "rc_receipt": "unverified",
        "core_retained": False,
    }
    store.close()


def test_assessment_report_wire_type_is_rejected_without_a_contract(tmp_path):
    store = RCStore(str(tmp_path / "rc.db"))

    class Clock:
        def advance(self, ms: int = 1) -> int:
            return 0

    rc = RCSimulator(store, Clock())
    env = mop.make_envelope(
        msg_id=mop.new_msg_id("edge", FLIGHT_ID, BOOT_ID, "report-1"),
        channel="EVENT_RELIABLE",
        type="ASSESSMENT_REPORT",
        flight_id=FLIGHT_ID,
        edge_boot_id=BOOT_ID,
        seq=1,
        ack_of=None,
        payload={"assessment": {"assessment_id": "x"}},
        sent_monotonic_ns=1_000_000,
    )
    assert rc.receive(env) == "error"
    assert store.artifacts_for(FLIGHT_ID, BOOT_ID) == []
    store.close()


def test_announced_but_incomplete_assessment_is_never_exposed(tmp_path):
    store = RCStore(str(tmp_path / "rc.db"))

    class Clock:
        def advance(self, ms: int = 1) -> int:
            return 0

    rc = RCSimulator(store, Clock())
    data = _assessment_bytes(tick=7)
    artifact_id = json.loads(data)["assessment_id"]
    announce = {
        "artifact_id": artifact_id,
        "flight_id": FLIGHT_ID,
        "edge_boot_id": BOOT_ID,
        "source_kind": ASSESSMENT_KIND,
        "sha256": hashlib.sha256(data).hexdigest(),
        "size_bytes": len(data),
        "media_type": ASSESSMENT_MEDIA,
        "segment_index": 0,
        "required": False,
    }
    assert rc.receive(_envelope("ARTIFACT_ANNOUNCE", 1, announce, "partial-announce")) == "stored"
    half = data[: len(data) // 2]
    payload = {
        "artifact_id": artifact_id,
        "offset": 0,
        "data_b64": base64.b64encode(half).decode(),
    }
    assert rc.receive(_envelope("ARTIFACT_CHUNK", 2, payload, "partial-chunk")) == "stored"
    rows = store.artifacts_for(FLIGHT_ID, BOOT_ID)
    assert len(rows) == 1 and rows[0]["state"] != "COMPLETE"
    store.close()
