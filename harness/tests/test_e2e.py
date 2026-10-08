"""E2E E01–E06: apply, retry/reboot recovery, offline, faults, gaps, resume."""

import pytest

from ksi_harness import flight_a
from ksi_harness.core_client import CoreError

from harness_helpers import exchange, fresh_edge, fresh_env

# -- E01: bundle apply → digest match → application visible on server ---------


def test_e01_apply_digest_matches_and_application_recorded(tmp_path, client):
    env = fresh_env(tmp_path)
    f = flight_a(tag="e01")
    edge = fresh_edge(env, f)
    app = env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    assert app["bundle_sha256"] == f["bundle"]["bundle_sha256"]
    out = env.rc.upload_flight(client, f)
    assert out["upload_state"] == "COMPLETE"
    items = client.list_applications(f["flight_id"])["items"]
    assert [a["application_id"] for a in items] == [app["application_id"]]
    assert items[0]["bundle_sha256"] == f["bundle"]["bundle_sha256"]


# -- E02a: dropped APPLIED → retry resolves, same id, no duplicate ------------


def test_e02a_dropped_apply_reply_retries_same_application(tmp_path):
    from ksi_harness import FaultTransport

    env = fresh_env(tmp_path)
    env.transport = FaultTransport(drop_first_n={"MISSION_APPLIED": 1})
    f = flight_a(tag="e02a")
    edge = fresh_edge(env, f)
    app = env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=6)
    apps = env.rc.applications(f["flight_id"])
    assert len(apps) == 1
    assert apps[0]["application_id"] == app["application_id"]
    assert env.transport.dropped_count >= 1


# -- E02b: persistent APPLIED drop → STATUS_QUERY reconciles ------------------


def test_e02b_persistent_apply_drop_resolves_via_status(tmp_path):
    from ksi_harness import FaultTransport

    env = fresh_env(tmp_path)
    env.transport = FaultTransport(drop_types={"MISSION_APPLIED"})
    f = flight_a(tag="e02b")
    edge = fresh_edge(env, f)
    app = env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    # The Edge applied exactly once; RC learned it via MISSION_STATUS.
    assert app["application_id"] in {p["application_id"] for p in edge.applications.values()}
    assert len(env.rc.applications(f["flight_id"])) == 1
    # No bogus gap was declared for the control channel.
    assert env.store.gaps_for_boot(edge.edge_boot_id) == []


# -- E02c: reboot → re-apply yields a NEW application id -----------------------


def test_e02c_reboot_reapply_new_application(tmp_path):
    env = fresh_env(tmp_path)
    f = flight_a(tag="e02c")
    edge = fresh_edge(env, f)
    first = env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    edge.reboot(next_boot_id=f["boots"][0]["boot_id"] + "-r2")
    second = env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    assert second["application_id"] != first["application_id"]
    assert second["edge_boot_id"] != first["edge_boot_id"]
    assert len(env.rc.applications(f["flight_id"])) == 2


# -- E03: fully offline exchange → restart → online upload ----------------------


def test_e03_offline_record_restart_then_upload(tmp_path, client):
    from ksi_harness import CoreClient

    env = fresh_env(tmp_path)
    f = flight_a(tag="e03")
    edge = fresh_edge(env, f)
    commits = []
    env.store.on_commit.append(lambda: commits.append(1))
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    stats = exchange(env, edge, f["boots"][0])
    assert stats["rc"]["errors"] == 0
    # ACK-before-commit: every ACKed envelope has a durable row.
    assert len(env.rc.acked) == env.store.count_messages(f["flight_id"])
    for mid in env.rc.acked:
        assert env.store.has_msg_id(mid)
    assert env.store.commit_count == len(commits) > 0
    # Crash + reopen with no server anywhere near: state intact, upload refused.
    env.rc.restart()
    assert len(env.store.observations_for(f["flight_id"])) == 60
    assert len(env.store.events_for(f["flight_id"])) == 4
    dead = CoreClient("http://127.0.0.1:9/api/v1/edge", "tok-orgA-rc", timeout=3.0)
    with pytest.raises(Exception):
        dead.register_flight({"flight_id": f["flight_id"]})
    dead.close()
    # Back online: the same backlog uploads to COMPLETE.
    out = env.rc.upload_flight(client, f)
    assert out["upload_state"] == "COMPLETE"


# -- E04: duplicate + reorder + delay → exactly-once store, no false gaps -------


def test_e04_duplicate_reorder_delay_exactly_once(tmp_path):
    from ksi_harness import FaultTransport

    env = fresh_env(tmp_path)
    env.transport = FaultTransport(duplicate_n=25, reorder_window=4, delay=1)
    f = flight_a(tag="e04")
    edge = fresh_edge(env, f)
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=8)
    stats = exchange(env, edge, f["boots"][0], max_rounds=15)
    assert stats["rc"]["dedup"] > 0
    expected_unique = sum(edge.seq.values())
    assert env.store.count_messages(f["flight_id"]) == expected_unique
    assert len(env.store.observations_for(f["flight_id"])) == 60
    assert len(env.store.events_for(f["flight_id"])) == 4
    assert all(a["state"] == "COMPLETE" for a in env.store.artifacts_for(f["flight_id"]))
    assert env.store.gaps_for_boot(edge.edge_boot_id) == []
    assert edge.unrecoverable == {}


# -- E05: unrecoverable seq loss → heartbeat gap → manifest gap → commit --------


def test_e05_unrecoverable_gap_reaches_manifest_and_commit(tmp_path, client):
    env = fresh_env(tmp_path)
    f = flight_a(tag="e05")
    edge = fresh_edge(env, f)
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    edge.emit_all(f["boots"][0])
    # Observations at EVENT_RELIABLE seqs 10–11 (no events attached) are lost.
    env.transport.report_lost(edge.edge_boot_id, "EVENT_RELIABLE", [10, 11])
    env.transport.pump(edge, env.rc, max_rounds=3)
    assert env.store.count_messages(f["flight_id"]) == sum(edge.seq.values()) - 2
    edge.emit_heartbeat()
    env.transport.pump(edge, env.rc, max_rounds=6)
    gaps = env.store.gaps_for_boot(edge.edge_boot_id)
    assert {(g["channel"], g["seq"]) for g in gaps} == {("EVENT_RELIABLE", 10), ("EVENT_RELIABLE", 11)}
    out = env.rc.upload_flight(client, f)
    manifest = out["boots"][edge.edge_boot_id]["manifest"]
    assert any(g["kind"] == "MOP_EVENT_GAP" for g in manifest["gaps"])
    assert manifest["rc_received_last_seq"] == manifest["edge_last_seq"]
    assert out["upload_state"] == "COMPLETE"
    flight = client.get_flight(f["flight_id"])
    assert flight["upload_state"] == "COMPLETE"
    server_events = client.get_events(f["flight_id"])["items"]
    assert len(server_events) == len(env.store.events_for(f["flight_id"])) == 4


# -- E06: aborted upload → status resume → COMMITTED; idempotent re-complete ----


def test_e06_abort_resume_commit_and_idempotent_recomplete(tmp_path, client):
    env = fresh_env(tmp_path)
    f = flight_a(tag="e06")
    edge = fresh_edge(env, f)
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    exchange(env, edge, f["boots"][0])
    boot = f["boots"][0]
    env.rc.register(client, f)
    env.rc.upload_applications(client, f)
    env.rc.upload_sessions(client, f)
    env.rc.upload_batches(client, f)
    manifest = env.rc.build_manifest(f, edge.edge_boot_id)
    upload_id = env.rc.begin_upload(client, manifest)
    pushed = env.rc.push_chunks(
        client, upload_id, manifest, boot["artifacts"], abort_after_chunks=1)
    assert pushed["aborted"] is True
    status = client.get_ingest(upload_id)
    assert status["missing_artifact_ids"] != []
    assert not any(env.store.is_synced(a["artifact_id"]) for a in boot["artifacts"])
    # Resume: only missing bytes fly; complete commits; artifacts flip SYNCED.
    env.rc.push_chunks(client, upload_id, manifest, boot["artifacts"])
    result = env.rc.finish_upload(client, upload_id, manifest, f["flight_id"], edge.edge_boot_id)
    assert result["status"] == "COMMITTED" and result["durable_commit_id"]
    assert all(env.store.is_synced(a["artifact_id"]) for a in boot["artifacts"])
    # Re-running the same manifest re-complete is idempotent (same durable id).
    upload_id2 = env.rc.begin_upload(client, manifest)
    assert upload_id2 == upload_id
    again = client.complete_ingest(upload_id, manifest["manifest_sha256"])
    assert again["status"] == "COMMITTED"
    assert again["durable_commit_id"] == result["durable_commit_id"]
    assert client.upload_state(f["flight_id"]) == "COMPLETE"
