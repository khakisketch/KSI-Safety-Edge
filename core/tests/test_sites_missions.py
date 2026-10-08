"""Auth matrix, sites + zone CAS, missions, bundles."""

from __future__ import annotations

import json
from copy import deepcopy

from ksi_contracts import set_digest

from core_helpers import AGENT, ORGB, RC, example, make_bundle, make_snapshot, register_flight

BASE = ""  # Client already prefixes /api/v1/edge


def test_healthz_no_auth(client):
    r = client.get("/healthz")
    assert r.status_code == 200
    assert r.json() == {"ok": True}


def test_missing_token_401(client):
    r = client.get("/sites")
    assert r.status_code == 401


def test_invalid_token_401(client):
    r = client.get("/sites", token="bogus")
    assert r.status_code == 401


def test_wrong_scope_403(client):
    # read-only agent token cannot write
    r = client.post("/sites", token=AGENT, json={"org_id": "org-a", "name": "s"})
    assert r.status_code == 403
    # but can read
    r = client.get("/sites", token=AGENT)
    assert r.status_code == 200


def test_cross_org_matrix(client):
    # org-a creates flight + event batch + ingest session with artifact
    flight = register_flight(client)
    fid = flight["flight_id"]
    from core_helpers import make_event, make_manifest

    ev = make_event(fid)
    r = client.post(f"/flights/{fid}/events:batch", token=RC,
                    json={"client_batch_id": "b1", "items": [ev]})
    assert r.status_code == 200, r.text
    data = b"x" * 64
    manifest, blobs = make_manifest(fid, [(None, data, "video/mp4", True, "RC_PROXY_SEGMENT")])
    r = client.post("/ingest-sessions", token=RC, json=manifest)
    assert r.status_code == 200, r.text
    upload_id = r.json()["upload_id"]
    aid = manifest["artifacts"][0]["artifact_id"]

    # org-b reads everything -> 404 (no existence leak)
    assert client.get(f"/flights/{fid}", token=ORGB).status_code == 404
    assert client.get("/events", token=ORGB).json()["items"] == []
    assert client.get(f"/events/{ev['event_id']}", token=ORGB).status_code == 404
    assert client.get(f"/events/{ev['event_id']}/evidence", token=ORGB).status_code == 404
    assert client.get(f"/objects/{aid}", token=ORGB).status_code == 404
    assert client.get(f"/ingest-sessions/{upload_id}", token=ORGB).status_code == 404
    assert client.get(f"/flights/{fid}/observations", token=ORGB).status_code == 404


def test_sites_zone_cas(client):
    r = client.post("/sites", token=RC, json={"org_id": "org-a", "name": "alpha"})
    assert r.status_code == 200, r.text
    site_id = r.json()["site_id"]
    # idempotent on same name+org
    r2 = client.post("/sites", token=RC, json={"org_id": "org-a", "name": "alpha"})
    assert r2.status_code == 200
    assert r2.json()["site_id"] == site_id

    geom = {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 0]]]}
    zid = "00000000-0000-4000-8000-00000000aa01"
    body = {"zone_id": zid, "base_revision_id": None, "geometry_wgs84": geom, "rule_kind": "PERSON_ENTRY"}
    r = client.post(f"/sites/{site_id}/zone-revisions", token=RC, json=body)
    assert r.status_code == 200, r.text
    rev1 = r.json()["revision_id"]
    # stale base -> 409
    body2 = dict(body, base_revision_id=None)
    assert client.post(f"/sites/{site_id}/zone-revisions", token=RC, json=body2).status_code == 409
    # correct base -> ok
    body3 = dict(body, base_revision_id=rev1)
    r = client.post(f"/sites/{site_id}/zone-revisions", token=RC, json=body3)
    assert r.status_code == 200, r.text


def test_mission_snapshot_roundtrip(client):
    snap = make_snapshot()
    r = client.post("/missions", token=RC, json=snap)
    assert r.status_code == 200, r.text
    # idempotent replay
    r = client.post("/missions", token=RC, json=snap)
    assert r.status_code == 200
    # same revision different content -> 409
    other = deepcopy(snap)
    other["zones"] = []
    other = set_digest(other, "content_sha256")
    r = client.post("/missions", token=RC, json=other)
    assert r.status_code == 409
    assert r.json()["code"] == "CONFLICT"
    # GET returns stored snapshot
    r = client.get(f"/missions/{snap['mission_id']}/snapshot", token=AGENT)
    assert r.status_code == 200
    assert r.json()["mission_revision_id"] == snap["mission_revision_id"]


def test_bundle_digest_and_idempotency(client):
    bundle = make_bundle()
    r = client.post("/mission-bundles", token=RC, json=bundle)
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "CREATED"
    # replay same -> EXISTS
    r = client.post("/mission-bundles", token=RC, json=bundle)
    assert r.status_code == 200
    assert r.json()["status"] == "EXISTS"
    # tampered bundle -> 422 DIGEST_MISMATCH
    bad = deepcopy(bundle)
    bad["created_by"] = "mallory"
    r = client.post("/mission-bundles", token=RC, json=bad)
    assert r.status_code == 422, r.text
    assert r.json()["code"] == "CONTRACT_INVALID"
    assert any("DIGEST_MISMATCH" in d for d in r.json()["details"])
    # same id different content (valid digest) -> 409
    other = deepcopy(bundle)
    other["created_by"] = "someone-else"
    other = set_digest(other, "bundle_sha256")
    # fix site consistency still holds (created_by is free-form)
    r = client.post("/mission-bundles", token=RC, json=other)
    assert r.status_code == 409
    assert r.json()["code"] == "CONFLICT"
    # GET round trip
    r = client.get(f"/mission-bundles/{bundle['bundle_id']}", token=AGENT)
    assert r.status_code == 200
    assert r.json()["bundle_id"] == bundle["bundle_id"]


def test_flights_register_idempotent_409(client):
    flight = register_flight(client)
    r = client.post("/flights", token=RC, json=flight)
    assert r.status_code == 200  # same content -> idempotent
    other = dict(flight, rc_device_id="different-device")
    r = client.post("/flights", token=RC, json=other)
    assert r.status_code == 409
