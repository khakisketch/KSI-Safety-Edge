"""RC offline store: commit-before-ACK, restart survival, no double-store."""

from ksi_harness import EdgeReplay, FaultTransport, RCSimulator, RCStore, VirtualClock, flight_a


def make_pair(tmp_path, tag="store"):
    clock = VirtualClock()
    store = RCStore(tmp_path / "rc.db")
    rc = RCSimulator(store, clock)
    f = flight_a(tag=tag)
    edge = EdgeReplay(f["flight_id"], clock, boot_id=f["boots"][0]["boot_id"])
    return clock, store, rc, edge, f


def test_ack_only_after_commit(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    commits = []
    store.on_commit.append(lambda: commits.append(clock.now_ns))
    edge.emit_all(f["boots"][0])
    stats = FaultTransport().pump(edge, rc, max_rounds=10)
    assert stats["rc"]["errors"] == 0
    # Every ACKed message has a durable row; every persist produced a commit.
    assert len(rc.acked) > 0
    for mid in rc.acked:
        assert store.has_msg_id(mid)
    assert store.commit_count == len(commits) and store.commit_count > 0
    assert store.count_messages(f["flight_id"]) == len(rc.acked)


def test_restart_mid_flow_no_loss_no_duplicates(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    boot = f["boots"][0]
    transport = FaultTransport()
    edge.emit_observations(boot["observations"][:30])
    transport.pump(edge, rc, max_rounds=2)
    mid_count = store.count_messages(f["flight_id"])
    assert mid_count > 0
    # Crash between persist and (hypothetical) later traffic: reopen mid-flow.
    rc.restart()
    edge.emit_observations(boot["observations"][30:])
    edge.emit_events(boot["events"])
    edge.emit_artifacts(boot["artifacts"])
    edge.emit_summary(boot["summary"])
    edge.emit_heartbeat()
    transport.pump(edge, rc, max_rounds=10)
    assert len(store.observations_for(f["flight_id"])) == 60
    assert len(store.events_for(f["flight_id"])) == 4
    assert all(a["state"] == "COMPLETE" for a in store.artifacts_for(f["flight_id"]))
    # Replayed envelopes after restart must not double-store.
    seen_before = store.count_messages(f["flight_id"])
    rc2_seen = set(rc.seen_msg_ids)
    assert len(rc2_seen) == seen_before
    transport.pump(edge, rc, max_rounds=3)
    assert store.count_messages(f["flight_id"]) == seen_before


def test_full_offline_state_survives_reopen(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, FaultTransport(), max_rounds=5)
    edge.emit_all(f["boots"][0])
    FaultTransport().pump(edge, rc, max_rounds=10)
    snapshot = {
        "messages": store.count_messages(f["flight_id"]),
        "obs": len(store.observations_for(f["flight_id"])),
        "events": len(store.events_for(f["flight_id"])),
        "apps": len(rc.applications(f["flight_id"])),
        "gaps": len(store.gaps_for_boot(edge.edge_boot_id)),
    }
    store.restart()
    assert store.count_messages(f["flight_id"]) == snapshot["messages"]
    assert len(store.observations_for(f["flight_id"])) == snapshot["obs"]
    assert len(store.events_for(f["flight_id"])) == snapshot["events"]
    assert len(rc.applications(f["flight_id"])) == snapshot["apps"]
    assert len(store.gaps_for_boot(edge.edge_boot_id)) == snapshot["gaps"]
    assert snapshot["messages"] > 0 and snapshot["obs"] == 60
