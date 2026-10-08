"""Unit tests for the MOP envelope + reliable delivery mechanics (no server)."""

import copy

from ksi_harness import EdgeReplay, FaultTransport, RCSimulator, RCStore, VirtualClock, flight_a
from ksi_harness.mop import verify_envelope


def make_pair(tmp_path, tag="mop"):
    clock = VirtualClock()
    store = RCStore(tmp_path / "rc.db")
    rc = RCSimulator(store, clock)
    f = flight_a(tag=tag)
    edge = EdgeReplay(f["flight_id"], clock, boot_id=f["boots"][0]["boot_id"])
    return clock, store, rc, edge, f


def test_envelope_round_trip_and_digest_reject(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    edge.emit_observations(f["boots"][0]["observations"][:1])
    env = edge.outbox.pop(0)  # hold the original back; only the tamper flies
    edge.unacked.clear()  # it never hit the wire, so no retry is owed
    assert verify_envelope(env)["type"] == "OBSERVATION"
    # Tampered payload must be rejected by the receiver (error, no ACK, no row).
    bad = copy.deepcopy(env)
    bad["payload"]["observation"]["detections"][0]["score"] = 0.01
    transport = FaultTransport()
    transport.pending["rc"].append((0, bad))
    stats = transport.pump(edge, rc, max_rounds=3)
    assert stats["rc"]["errors"] >= 1
    assert store.count_messages(f["flight_id"]) == 0
    assert rc.acked == []


def test_duplicate_delivery_dedups_without_double_store(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    transport = FaultTransport(duplicate_n=3)
    edge.emit_observations(f["boots"][0]["observations"][:2])
    stats = transport.pump(edge, rc, max_rounds=5)
    # Each observation arrived twice (original + injected duplicate).
    assert stats["rc"]["dedup"] == 2
    assert store.count_messages(f["flight_id"]) == 2
    assert len(store.observations_for(f["flight_id"])) == 2
    assert not edge.unacked and not rc.unacked


def test_retry_resolves_with_same_msg_id(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    transport = FaultTransport(drop_first_n={"OBSERVATION": 1})
    edge.emit_observations(f["boots"][0]["observations"][:1])
    first_id = edge.outbox[0]["msg_id"]
    stats = transport.pump(edge, rc, max_rounds=5)
    assert stats["edge"]["retried"] >= 1
    assert store.count_messages(f["flight_id"]) == 1
    # The retry reused the original msg_id; nothing was duplicated.
    assert stats["rc"]["dedup"] == 0
    assert len(store.observations_for(f["flight_id"])) == 1
    assert edge.outbox == [] or all(e["msg_id"] == first_id for e in edge.outbox)


def test_unrecoverable_loss_surfaces_in_heartbeat_and_rc_gaps(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    boot = f["boots"][0]
    edge.emit_observations(boot["observations"][:8])
    # Steal two EVENT_RELIABLE seqs (3 and 4) persistently, exhaust retries.
    transport = FaultTransport()
    transport.report_lost(edge.edge_boot_id, "EVENT_RELIABLE", [3, 4])
    stats = transport.pump(edge, rc, max_rounds=4)
    assert stats["edge"]["retried"] >= 1
    assert store.count_messages(f["flight_id"]) == 6
    # Next heartbeat must carry the given-up seqs; RC stores them as gaps.
    edge.emit_heartbeat()
    transport.pump(edge, rc, max_rounds=5)
    gaps = store.gaps_for_boot(edge.edge_boot_id)
    assert {(g["channel"], g["seq"]) for g in gaps} == {("EVENT_RELIABLE", 3), ("EVENT_RELIABLE", 4)}
    assert all(g["destination"] == "RC_MANIFEST" for g in gaps)


def test_rc_synthesizes_consecutive_missing_gaps_at_manifest(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    edge.emit_all(f["boots"][0])
    transport = FaultTransport()
    # Lose one mid-range EVENT seq; retries exhaust and the next heartbeat
    # carries it as unrecoverable; RC must persist a gap row for it.
    transport.report_lost(edge.edge_boot_id, "EVENT_RELIABLE", [10])
    transport.pump(edge, rc, max_rounds=3)
    edge.emit_heartbeat()
    transport.pump(edge, rc, max_rounds=5)
    manifest = rc.build_manifest(f, edge.edge_boot_id)
    gap_seqs = {g["seq"] for g in store.gaps_for_boot(edge.edge_boot_id)}
    assert 10 in gap_seqs
    assert any(g["kind"] == "MOP_EVENT_GAP" for g in manifest["gaps"])
    assert manifest["rc_received_last_seq"] == manifest["edge_last_seq"]
