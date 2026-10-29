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


def test_site_location_is_optional_and_roundtrips(client):
    """D2: 현장 GPS 추천(선택 기능)의 재료 — 현장 위치는 선택이다.

    - 좌표가 없는 현장은 좌표 없이도 만들어지고, 조회에도 null로 나온다
      ("좌표 미등록"과 "좌표 0"을 구분한다).
    - 좌표는 중심점 2개 + 반경이 한 덩어리다. 하나만 주면 422.
    - 위험구역 다각형(zones)과는 별개다 — 현장 경계 대신 쓰지 않는다.
    """
    r = client.post("/sites", token=RC, json={"org_id": "org-a", "name": "좌표없는현장"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["center_lat"] is None
    assert body["center_lon"] is None
    assert body["radius_m"] is None

    listed = client.get("/sites", token=RC).json()["items"]
    entry = next(s for s in listed if s["site_id"] == body["site_id"])
    assert entry["center_lat"] is None
    assert entry["radius_m"] is None

    loc = {"center_lat": 37.5, "center_lon": 127.0, "radius_m": 300.0}
    r = client.post("/sites", token=RC, json={"org_id": "org-a", "name": "서부물류센터", **loc})
    assert r.status_code == 200, r.text
    assert r.json()["center_lat"] == 37.5
    assert r.json()["radius_m"] == 300.0

    # 부분 입력(중심점만, 반경 없음)은 422 — 셋이 한 덩어리다.
    r = client.post(
        "/sites", token=RC,
        json={"org_id": "org-a", "name": "반경없는현장", "center_lat": 37.0, "center_lon": 127.0},
    )
    assert r.status_code == 422, r.text

    # 범위 밖 좌표는 422.
    r = client.post(
        "/sites", token=RC,
        json={"org_id": "org-a", "name": "잘못된현장", "center_lat": 999.0, "center_lon": 0.0, "radius_m": 10.0},
    )
    assert r.status_code == 422, r.text


def test_site_location_registration_updates_only_when_absent(client):
    """같은 이름 재등록은 같은 현장이다. 위치는 "있을 때만" 채운다."""
    r = client.post(
        "/sites", token=RC,
        json={"org_id": "org-a", "name": "갱신현장", "center_lat": 37.0, "center_lon": 127.0, "radius_m": 100.0},
    )
    assert r.status_code == 200, r.text
    first = r.json()
    assert first["center_lat"] == 37.0

    # 좌표 없이 같은 이름 → 기존 좌표가 보존된다(명시적 갱신 없음).
    r = client.post("/sites", token=RC, json={"org_id": "org-a", "name": "갱신현장"})
    assert r.status_code == 200, r.text
    assert r.json()["site_id"] == first["site_id"]
    assert r.json()["center_lat"] == 37.0


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
