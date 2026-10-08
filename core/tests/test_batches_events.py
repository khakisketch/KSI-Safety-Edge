"""Batches, events/annotations, coverage, review, openapi parity, audit."""

from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path

import yaml

from core_helpers import AGENT, ORGB, RC, example, make_annotation, make_event, make_flight_summary, make_manifest, make_observation, make_spatial_summary, register_flight


def _batch(client, fid, path, items, batch_id="b1", token=RC):
    return client.post(f"/flights/{fid}/{path}:batch", token=token,
                       json={"client_batch_id": batch_id, "items": items})


def test_batch_idempotent_replay_and_conflicts(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    o1, o2 = make_observation(fid), make_observation(fid)
    r = _batch(client, fid, "observations", [o1, o2])
    assert r.status_code == 200, r.text
    first = r.json()
    assert first["durable"] is True
    # replay same client_batch_id + same content -> same server_batch_id
    r = _batch(client, fid, "observations", [o1, o2])
    assert r.status_code == 200
    assert r.json()["server_batch_id"] == first["server_batch_id"]
    assert r.json()["accepted_ids"] == first["accepted_ids"]
    # changed item content under same client_batch_id -> 409
    o2b = deepcopy(o2)
    o2b["lens_id"] = "tele"
    r = _batch(client, fid, "observations", [o1, o2b])
    assert r.status_code == 409
    # new batch with already-stored item (same content) -> duplicate_ids
    o3 = make_observation(fid)
    r = _batch(client, fid, "observations", [o1, o3], batch_id="b2")
    assert r.status_code == 200, r.text
    assert r.json()["duplicate_ids"] == [o1["observation_id"]]
    assert r.json()["accepted_ids"] == [o3["observation_id"]]
    # same item id, different content, fresh batch -> 409
    o3b = deepcopy(o3)
    o3b["lens_id"] = "tele"
    r = _batch(client, fid, "observations", [o3b], batch_id="b3")
    assert r.status_code == 409


def test_all_batch_kinds_roundtrip(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    ev = make_event(fid)
    r = _batch(client, fid, "events", [ev], batch_id="e1")
    assert r.status_code == 200, r.text
    fs = make_flight_summary(fid)
    r = _batch(client, fid, "flight-summaries", [fs], batch_id="f1")
    assert r.status_code == 200, r.text
    ss = make_spatial_summary(fid)
    r = _batch(client, fid, "spatial-summaries", [ss], batch_id="s1")
    assert r.status_code == 200, r.text
    ob = make_observation(fid)
    r = _batch(client, fid, "observations", [ob], batch_id="o1")
    assert r.status_code == 200, r.text
    # invalid item -> 422
    bad = deepcopy(make_observation(fid))
    del bad["observation_id"]
    r = _batch(client, fid, "observations", [bad], batch_id="bad")
    assert r.status_code == 422
    # lists readable
    assert client.get(f"/flights/{fid}/observations", token=AGENT).json()["items"][0]["observation_id"]
    assert client.get(f"/flights/{fid}/spatial-summaries", token=AGENT).json()["items"][0]["summary_id"]


def test_events_annotations_evidence(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    data = b"E" * 128
    manifest, blobs = make_manifest(fid, [("ev-art-1", data, "image/jpeg", True, "EDGE_SNAPSHOT")])
    aid = manifest["artifacts"][0]["artifact_id"]
    r = client.post("/ingest-sessions", token=RC, json=manifest)
    assert r.status_code == 200, r.text
    up = r.json()["upload_id"]
    total = len(data)
    r = client.put(f"/ingest-sessions/{up}/objects/{aid}", token=RC,
                   headers={"Content-Range": f"bytes 0-{total-1}/{total}"}, content=data)
    assert r.status_code == 200
    r = client.post(f"/ingest-sessions/{up}/complete", token=RC, json={"manifest_sha256": manifest["manifest_sha256"]})
    assert r.json()["status"] == "COMMITTED"

    ev = make_event(fid, artifacts=[aid])
    r = _batch(client, fid, "events", [ev], batch_id="e1")
    assert r.status_code == 200, r.text
    eid = ev["event_id"]
    # get event
    r = client.get(f"/events/{eid}", token=AGENT)
    assert r.status_code == 200
    assert r.json()["annotations_count"] == 0
    # evidence returns metadata + download_url
    r = client.get(f"/events/{eid}/evidence", token=AGENT)
    assert r.status_code == 200, r.text
    items = r.json()["items"]
    assert len(items) == 1
    assert items[0]["download_url"] == f"/api/v1/edge/objects/{aid}"
    # download works with headers
    r = client.get(f"/objects/{aid}", token=AGENT)
    assert r.status_code == 200
    assert r.content == data
    assert r.headers["x-content-sha256"] == manifest["artifacts"][0]["sha256"]
    assert r.headers["content-type"] == "image/jpeg"
    # annotations append + revoke (append-only: both visible)
    a1 = make_annotation(eid, fid)
    r = client.post(f"/events/{eid}/annotations", token=RC, json=a1)
    assert r.status_code == 200, r.text
    a2 = make_annotation(eid, fid, ann_type="REVOKE_PREVIOUS", supersedes=a1["annotation_id"])
    r = client.post(f"/events/{eid}/annotations", token=RC, json=a2)
    assert r.status_code == 200, r.text
    r = client.get(f"/events/{eid}/annotations", token=AGENT)
    assert r.status_code == 200
    assert len(r.json()["items"]) == 2
    assert client.get(f"/events/{eid}", token=AGENT).json()["annotations_count"] == 2
    # revoke without target -> 422
    a3 = make_annotation(eid, fid, ann_type="REVOKE_PREVIOUS", supersedes=None)
    r = client.post(f"/events/{eid}/annotations", token=RC, json=a3)
    assert r.status_code == 422


def test_coverage_states(client):
    f1 = register_flight(client)
    # no summaries -> UNKNOWN
    r = client.get(f"/flights/{f1['flight_id']}/coverage", token=AGENT)
    assert r.json()["data_completeness"] == "UNKNOWN"
    # summaries, no manifests -> PARTIAL
    fs = make_flight_summary(f1["flight_id"])
    r = _batch(client, f1["flight_id"], "flight-summaries", [fs], batch_id="c1")
    assert r.status_code == 200
    r = client.get(f"/flights/{f1['flight_id']}/coverage", token=AGENT)
    body = r.json()
    assert body["data_completeness"] == "PARTIAL"
    assert body["summaries"][0]["summary_id"] == fs["summary_id"]
    # summaries + committed manifest -> COMPLETE
    data = b"Z" * 32
    manifest, _ = make_manifest(f1["flight_id"], [("cov-art", data, "video/mp4", True, "RC_PROXY_SEGMENT")])
    caid = manifest["artifacts"][0]["artifact_id"]
    up = client.post("/ingest-sessions", token=RC, json=manifest).json()["upload_id"]
    total = len(data)
    client.put(f"/ingest-sessions/{up}/objects/{caid}", token=RC,
               headers={"Content-Range": f"bytes 0-{total-1}/{total}"}, content=data)
    client.post(f"/ingest-sessions/{up}/complete", token=RC, json={"manifest_sha256": manifest["manifest_sha256"]})
    r = client.get(f"/flights/{f1['flight_id']}/coverage", token=AGENT)
    assert r.json()["data_completeness"] == "COMPLETE"


def test_review_jobs_placeholder(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    body = {"flight_id": fid, "job_kind": "FP_REVIEW", "idempotency_key": "k1"}
    r = client.post("/review-jobs", token=RC, json=body)
    assert r.status_code == 200, r.text
    job_id = r.json()["job_id"]
    # idempotent replay
    r = client.post("/review-jobs", token=RC, json=body)
    assert r.json()["job_id"] == job_id
    # same key different content -> 409
    r = client.post("/review-jobs", token=RC, json=dict(body, job_kind="FN_AUDIT_SAMPLE"))
    assert r.status_code == 409
    assert client.get(f"/review-jobs/{job_id}", token=AGENT).status_code == 200
    assert client.get("/review-jobs", token=AGENT).json()["items"]


def test_openapi_parity(client):
    import re

    spec = yaml.safe_load((Path(__file__).resolve().parents[2] / "contracts" / "openapi.yaml").read_text())

    def norm(p):
        return re.sub(r"\{[^}]*\}", "{}", p)

    expected = set()
    for path, methods in spec["paths"].items():
        for method, op in methods.items():
            if method.upper() in ("HEAD", "OPTIONS"):
                continue
            if not isinstance(op, dict) or "operationId" not in op:
                continue
            expected.add((norm("/api/v1/edge" + path), method.upper()))
    actual = set()
    for path, methods in client.app.openapi()["paths"].items():
        for method in methods:
            if method.upper() in ("HEAD", "OPTIONS"):
                continue
            actual.add((norm(path), method.upper()))
    missing = expected - actual
    extra = actual - expected
    assert not missing, f"spec ops missing from app: {missing}"
    assert not extra, f"app routes missing from spec: {extra}"


def test_audit_log_rows(client):
    from ksi_core import models as _m  # noqa - ensures tables importable

    flight = register_flight(client)
    fid = flight["flight_id"]
    data = b"A" * 16
    manifest, _ = make_manifest(fid, [("aud-art", data, "video/mp4", True, "RC_PROXY_SEGMENT")])
    aud = manifest["artifacts"][0]["artifact_id"]
    up = client.post("/ingest-sessions", token=RC, json=manifest).json()["upload_id"]
    total = len(data)
    client.put(f"/ingest-sessions/{up}/objects/{aud}", token=RC,
               headers={"Content-Range": f"bytes 0-{total-1}/{total}"}, content=data)
    client.post(f"/ingest-sessions/{up}/complete", token=RC, json={"manifest_sha256": manifest["manifest_sha256"]})
    client.get(f"/objects/{aud}", token=AGENT)
    # inspect audit rows directly through a fresh session on the same DB
    app = client.app
    session = app.state.session_factory()
    try:
        rows = session.query(_m.AuditLog).all()
        actions = {(r.action, r.resource_type) for r in rows}
        assert ("create", "flight") in actions
        assert ("commit", "ingest_session") in actions
        assert ("download", "object") in actions
    finally:
        session.close()
