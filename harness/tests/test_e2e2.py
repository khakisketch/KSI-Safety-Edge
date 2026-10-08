"""E2E E07–E12: corrupt recovery, mid-flight reconfig, zone evolution, honesty, annotations, tenancy."""

import hashlib
import uuid

import pytest

from ksi_contracts import canonical_bytes
from ksi_harness import flight_a, flight_b, flight_c
from ksi_harness.core_client import CoreError

from harness_helpers import exchange, fresh_edge, fresh_env

# -- E07: corrupt bytes → honest PARTIAL → fix → COMMITTED ------------------------


def test_e07_corrupt_artifact_partial_then_fixed_commit(tmp_path, client):
    env = fresh_env(tmp_path)
    f = flight_a(tag="e07")
    edge = fresh_edge(env, f)
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    exchange(env, edge, f["boots"][0])
    boot = f["boots"][0]
    env.rc.register(client, f)
    env.rc.upload_applications(client, f)
    env.rc.upload_sessions(client, f)
    env.rc.upload_batches(client, f)
    manifest = env.rc.build_manifest(f, edge.edge_boot_id)
    corrupt = [boot["artifacts"][0]["artifact_id"]]
    upload_id = env.rc.begin_upload(client, manifest)
    env.rc.push_chunks(client, upload_id, manifest, boot["artifacts"], corrupt_ids=corrupt)
    result = env.rc.finish_upload(client, upload_id, manifest, f["flight_id"], edge.edge_boot_id)
    assert result["status"] == "PARTIAL"
    assert result["durable_commit_id"] is None
    assert corrupt[0] in result["missing_artifact_ids"]
    assert not env.store.is_synced(corrupt[0])
    assert client.upload_state(f["flight_id"]) == "PARTIAL"
    # Re-upload the correct bytes (forced: the bad bytes are fully resident).
    env.rc.push_chunks(client, upload_id, manifest, boot["artifacts"], force_ids=corrupt)
    fixed = env.rc.finish_upload(client, upload_id, manifest, f["flight_id"], edge.edge_boot_id)
    assert fixed["status"] == "COMMITTED" and fixed["durable_commit_id"]
    assert env.store.is_synced(corrupt[0])
    assert client.upload_state(f["flight_id"]) == "COMPLETE"


# -- E08: mid-flight bundle v2 + second boot re-apply --------------------------------


def test_e08_midflight_reconfig_and_second_boot(tmp_path, client):
    env = fresh_env(tmp_path)
    f = flight_c(tag="e08")
    edge = fresh_edge(env, f, boot_index=0)
    v1, v2 = f["bundle"], f["bundle_v2"]
    boot0, boot1 = f["boots"]
    # Phase 1 under v1: first observations + v1 events.
    env.rc.prepare_and_apply(f["flight_id"], v1, edge, env.transport, max_rounds=5)
    edge.emit_observations(boot0["observations"][:22])
    edge.emit_events(boot0["events"][:2])
    env.transport.pump(edge, env.rc, max_rounds=8)
    # Mid-flight reconfig to v2, then the rest of boot 0.
    env.rc.prepare_and_apply(f["flight_id"], v2, edge, env.transport, max_rounds=5)
    edge.emit_observations(boot0["observations"][22:])
    edge.emit_events(boot0["events"][2:])
    edge.emit_artifacts(boot0["artifacts"])
    edge.emit_summary(boot0["summary"])
    edge.emit_heartbeat()
    env.transport.pump(edge, env.rc, max_rounds=10)
    # Reboot: the old ACK is not reusable; v2 is re-applied on the new boot.
    edge.reboot(next_boot_id=boot1["boot_id"])
    env.rc.prepare_and_apply(f["flight_id"], v2, edge, env.transport, max_rounds=5)
    edge.emit_all(boot1)
    env.transport.pump(edge, env.rc, max_rounds=10)
    # Applications: v1(boot0), v2(boot0), v2(boot1).
    apps = env.rc.applications(f["flight_id"])
    assert [(a["bundle_sha256"], a["edge_boot_id"]) for a in apps] == [
        (v1["bundle_sha256"], boot0["boot_id"]),
        (v2["bundle_sha256"], boot0["boot_id"]),
        (v2["bundle_sha256"], boot1["boot_id"]),
    ]
    out = env.rc.upload_flight(client, f)
    assert out["upload_state"] == "COMPLETE"
    server_apps = client.list_applications(f["flight_id"])["items"]
    assert len(server_apps) == 3
    server_events = client.get_events(f["flight_id"])["items"]
    policy_revs = {e["policy_revision_id"] for e in server_events}
    assert len(policy_revs) >= 2
    assert len(out["boots"]) == 2
    assert all(b["result"]["status"] == "COMMITTED" for b in out["boots"].values())


# -- E09: post-flight zone v2 → old events keep v1, zones show new head --------------


def test_e09_zone_revision_after_flight_preserves_history(tmp_path, client):
    env = fresh_env(tmp_path)
    f = flight_a(tag="e09")
    edge = fresh_edge(env, f)
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    exchange(env, edge, f["boots"][0])
    env.rc.upload_flight(client, f)
    before = {e["event_id"]: e["zone_revision_id"] for e in client.get_events(f["flight_id"])["items"]}
    assert len(before) == 4
    site = client.create_site("org-a", "e09-site")
    zone = f["world"]["zones"][0]
    rev1 = client.post_zone_revision(site["site_id"], {
        "zone_id": zone["zone_id"], "base_revision_id": None,
        "geometry_wgs84": zone["geometry_wgs84"], "rule_kind": zone["rule_kind"]})
    rev2 = client.post_zone_revision(site["site_id"], {
        "zone_id": zone["zone_id"], "base_revision_id": rev1["revision_id"],
        "geometry_wgs84": zone["geometry_wgs84"], "rule_kind": zone["rule_kind"]})
    heads = client.get_site_zones(site["site_id"])["items"]
    assert rev2["revision_id"] in {h["revision_id"] for h in heads}
    after = {e["event_id"]: e["zone_revision_id"] for e in client.get_events(f["flight_id"])["items"]}
    assert after == before
    assert rev2["revision_id"] not in set(after.values())


# -- E10: zero events + AI gap → COMPLETE but honestly gapped --------------------------


def test_e10_zero_events_with_ai_gap_is_honest_complete(tmp_path, client):
    env = fresh_env(tmp_path)
    f = flight_b(tag="e10")
    edge = fresh_edge(env, f)
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    exchange(env, edge, f["boots"][0])
    out = env.rc.upload_flight(client, f)
    assert out["upload_state"] == "COMPLETE"
    assert client.get_events(f["flight_id"])["items"] == []
    coverage = client.get_coverage(f["flight_id"])
    assert coverage["data_completeness"] == "COMPLETE"
    assert len(coverage["summaries"]) == 1
    summary = coverage["summaries"][0]
    assert summary["ai_uptime_ratio"] == 0.6
    ai_gaps = [g for g in summary["gaps"] if g["kind"] == "AI_GAP"]
    assert len(ai_gaps) == 1 and ai_gaps[0]["duration_ms"] > 0


# -- E11: annotation + revocation are append-only; raw event hash stable --------------


def test_e11_annotations_append_only_event_immutable(tmp_path, client):
    env = fresh_env(tmp_path)
    f = flight_a(tag="e11")
    edge = fresh_edge(env, f)
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    exchange(env, edge, f["boots"][0])
    env.rc.upload_flight(client, f)
    event_id = client.get_events(f["flight_id"])["items"][0]["event_id"]
    sha_before = hashlib.sha256(canonical_bytes(client.get_event(event_id)["event"])).hexdigest()
    ann1_id = str(uuid.uuid5(uuid.NAMESPACE_DNS, "e11-ann1"))
    ann2_id = str(uuid.uuid5(uuid.NAMESPACE_DNS, "e11-ann2"))
    base_ann = {
        "schema_version": 1, "event_id": event_id, "flight_id": f["flight_id"],
        "actor_id": str(uuid.uuid5(uuid.NAMESPACE_DNS, "e11-actor")),
        "client_id": str(uuid.uuid5(uuid.NAMESPACE_DNS, "e11-client")),
        "evidence_ids": [], "created_at_local": "2026-10-08T12:00:00+09:00",
    }
    env.rc.create_annotation({**base_ann, "annotation_id": ann1_id,
                              "annotation_type": "FALSE_POSITIVE_SUSPECTED",
                              "reason": "possible occlusion", "supersedes_annotation_id": None})
    env.rc.create_annotation({**base_ann, "annotation_id": ann2_id,
                              "annotation_type": "REVOKE_PREVIOUS",
                              "reason": "re-reviewed: confirmed", "supersedes_annotation_id": ann1_id})
    env.rc.upload_annotations(client, f["flight_id"])
    items = client.list_annotations(event_id)["items"]
    assert [a["annotation_id"] for a in items] == [ann1_id, ann2_id]
    assert [a["annotation_type"] for a in items] == ["FALSE_POSITIVE_SUSPECTED", "REVOKE_PREVIOUS"]
    assert items[1]["supersedes_annotation_id"] == ann1_id
    sha_after = hashlib.sha256(canonical_bytes(client.get_event(event_id)["event"])).hexdigest()
    assert sha_after == sha_before


# -- E12: cross-org access is 404 everywhere ----------------------------------------------


def test_e12_cross_org_access_denied(tmp_path, client, client_b):
    env = fresh_env(tmp_path)
    f = flight_a(tag="e12")
    edge = fresh_edge(env, f)
    env.rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, env.transport, max_rounds=5)
    exchange(env, edge, f["boots"][0])
    out = env.rc.upload_flight(client, f)
    boot_id = f["boots"][0]["boot_id"]
    artifact_id = out["boots"][boot_id]["manifest"]["artifacts"][0]["artifact_id"]
    upload_id = out["boots"][boot_id]["upload_id"]
    manifest = out["boots"][boot_id]["manifest"]
    event_id = client.get_events(f["flight_id"])["items"][0]["event_id"]
    with pytest.raises(CoreError) as exc_flight:
        client_b.get_flight(f["flight_id"])
    assert exc_flight.value.status == 404
    with pytest.raises(CoreError) as exc_event:
        client_b.get_event(event_id)
    assert exc_event.value.status == 404
    with pytest.raises(CoreError) as exc_obj:
        client_b.download_object(artifact_id)
    assert exc_obj.value.status == 404
    with pytest.raises(CoreError) as exc_put:
        client_b.put_chunk(upload_id, artifact_id, 0, b"x", 1, "0" * 64)
    # Read-only org-B token is stopped at the scope gate (403) before any
    # org lookup; an rc-scoped org-B token reaches the org gate → 404.
    assert exc_put.value.status == 403
    from ksi_harness import CoreClient as _CC

    org_b_rc = _CC(client_b.base_url, "tok-orgB-rc")
    try:
        with pytest.raises(CoreError) as exc_put2:
            org_b_rc.put_chunk(upload_id, artifact_id, 0, b"x", 1, "0" * 64)
        assert exc_put2.value.status == 404
    finally:
        org_b_rc.close()
    # Same-org reads still work (control): flight + object are retrievable.
    assert client.get_flight(f["flight_id"])["upload_state"] == "COMPLETE"
    data, headers = client.download_object(artifact_id)
    assert headers.get("x-content-sha256") == manifest["artifacts"][0]["sha256"]
