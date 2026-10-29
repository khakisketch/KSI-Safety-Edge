"""Ingest lifecycle + flight upload_state transitions."""

from __future__ import annotations

from core_helpers import AGENT, RC, fresh_uuid, make_manifest, register_flight


def _put(client, up, aid, data, start=None, end=None):
    total = len(data)
    s = 0 if start is None else start
    e = total - 1 if end is None else end
    return client.put(f"/ingest-sessions/{up}/objects/{aid}", token=RC,
                      headers={"Content-Range": f"bytes {s}-{e}/{total}"},
                      content=data[s:e + 1])


def _complete(client, up, manifest):
    return client.post(f"/ingest-sessions/{up}/complete", token=RC,
                       json={"manifest_sha256": manifest["manifest_sha256"]})


def _state(client, fid):
    return client.get(f"/flights/{fid}", token=AGENT).json()


def _aid(manifest, i=0):
    return manifest["artifacts"][i]["artifact_id"]


def test_upload_state_transitions(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    assert _state(client, fid)["upload_state"] == "LOCAL_ONLY"

    data = b"D" * 100
    manifest, _ = make_manifest(fid, [("a1", data, "video/mp4", True, "RC_PROXY_SEGMENT")])
    aid = _aid(manifest)
    r = client.post("/ingest-sessions", token=RC, json=manifest)
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "NEW"
    up = r.json()["upload_id"]
    assert _state(client, fid)["upload_state"] == "UPLOADING"

    # partial upload -> complete attempt -> PARTIAL
    r = _put(client, up, aid, data, 0, 39)
    assert r.status_code == 200
    assert r.json() == {"received_bytes": 40, "complete": False}
    r = _complete(client, up, manifest)
    assert r.status_code == 200
    assert r.json()["status"] == "PARTIAL"
    assert r.json()["durable_commit_id"] is None
    assert _state(client, fid)["upload_state"] == "PARTIAL"

    # resume out-of-order: rest of the bytes -> COMMITTED
    r = _put(client, up, aid, data, 40, 99)
    assert r.json() == {"received_bytes": 100, "complete": True}
    r = _complete(client, up, manifest)
    assert r.json()["status"] == "COMMITTED"
    durable = r.json()["durable_commit_id"]
    assert durable
    view = _state(client, fid)
    assert view["upload_state"] == "COMPLETE"
    assert view["completeness"] == {"manifests_total": 1, "manifests_committed": 1, "missing_artifact_count": 0}
    # duplicate complete idempotent (same durable_commit_id)
    r = _complete(client, up, manifest)
    assert r.json()["status"] == "COMMITTED"
    assert r.json()["durable_commit_id"] == durable


def test_corrupt_then_fix(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    good = b"G" * 64
    manifest, _ = make_manifest(fid, [("c1", good, "video/mp4", True, "RC_PROXY_SEGMENT")])
    aid = _aid(manifest)
    up = client.post("/ingest-sessions", token=RC, json=manifest).json()["upload_id"]
    bad = b"B" * 64
    r = _put(client, up, aid, bad)
    assert r.json()["complete"] is True  # full size, wrong hash
    r = _complete(client, up, manifest)
    assert r.json()["status"] == "PARTIAL"
    assert r.json()["missing_artifact_ids"] == [aid]
    # fix by whole-object re-upload (overwrite allowed)
    r = _put(client, up, aid, good)
    assert r.status_code == 200
    r = _complete(client, up, manifest)
    assert r.json()["status"] == "COMMITTED"


def test_session_frozen_and_resume(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    m1, _ = make_manifest(fid, [("x1", b"1" * 8, "video/mp4", True, "RC_PROXY_SEGMENT")])
    r = client.post("/ingest-sessions", token=RC, json=m1)
    up = r.json()["upload_id"]
    # same manifest -> same upload_id, RESUMING
    r = client.post("/ingest-sessions", token=RC, json=m1)
    assert r.json()["upload_id"] == up
    assert r.json()["status"] == "RESUMING"
    # complete with a different manifest sha -> 409
    m2, _ = make_manifest(fid, [("x1", b"2" * 8, "video/mp4", True, "RC_PROXY_SEGMENT")])
    r = client.post(f"/ingest-sessions/{up}/complete", token=RC, json={"manifest_sha256": m2["manifest_sha256"]})
    assert r.status_code == 409
    # invalid manifest digest -> 422
    import copy
    m3 = copy.deepcopy(m1)
    m3["edge_last_seq"] = 999
    r = client.post("/ingest-sessions", token=RC, json=m3)
    assert r.status_code == 422


def test_artifact_id_reuse_with_a_different_digest_is_refused(client):
    """증거의 정체성은 전역이다: 같은 artifact_id가 다른 바이트를 가리키면 안 된다.

    감시는 "비행 하나에 매니페스트 하나"였다. 그러나 artifact id는 세션에
    묶이지 않는다 — 같은 id가 다른 다이제스트로 재사용되면, 한 비행의
    커밋 영수증이 다른 바이트를 증명해 버린다. 그러므로 409로 거절한다.
    """
    flight = register_flight(client)
    fid = flight["flight_id"]
    shared_id = "11111111-2222-4333-8444-555555555555"
    first_bytes = b"D" * 100
    manifest, _ = make_manifest(fid, [(shared_id, first_bytes, "video/mp4", True, "RC_PROXY_SEGMENT")])
    r = client.post("/ingest-sessions", token=RC, json=manifest)
    assert r.status_code == 200, r.text
    up = r.json()["upload_id"]
    assert _put(client, up, shared_id, first_bytes).status_code == 200
    assert _complete(client, up, manifest).json()["status"] == "COMMITTED"

    # 같은 id, 다른 바이트 -> 거절(기존 증거는 그대로다)
    other, _ = make_manifest(fid, [(shared_id, b"E" * 100, "video/mp4", True, "RC_PROXY_SEGMENT")])
    r2 = client.post("/ingest-sessions", token=RC, json=other)
    assert r2.status_code == 409, r2.text
    assert r2.json()["code"] == "CONFLICT"
    assert r2.json()["details"]["artifact_ids"] == [shared_id]

    # 거절 뒤에도 첫 세션의 커밋 상태는 유지된다.
    assert client.get(f"/ingest-sessions/{up}", token=RC).json()["status"] == "COMMITTED"

    # 같은 id + 같은 바이트는 재전송(같은 증거)이므로 허용된다.
    again, _ = make_manifest(fid, [(shared_id, first_bytes, "video/mp4", True, "RC_PROXY_SEGMENT")])
    r3 = client.post("/ingest-sessions", token=RC, json=again)
    assert r3.status_code == 200, r3.text


def test_two_manifests_one_flight_adr0001(client):
    """Two manifest revisions for one flight, both committed -> COMPLETE."""
    flight = register_flight(client)
    fid = flight["flight_id"]
    m1, blobs1 = make_manifest(fid, [("s1-a", b"a" * 16, "video/mp4", True, "RC_PROXY_SEGMENT")])
    m2, blobs2 = make_manifest(fid, [("s2-a", b"b" * 16, "video/mp4", True, "RC_PROXY_SEGMENT"),
                                     ("s2-opt", b"", "image/jpeg", False, "EDGE_SNAPSHOT")])
    up1 = client.post("/ingest-sessions", token=RC, json=m1).json()["upload_id"]
    up2 = client.post("/ingest-sessions", token=RC, json=m2).json()["upload_id"]
    assert up1 != up2
    a1 = _aid(m1, 0)
    _put(client, up1, a1, blobs1[a1])
    assert _complete(client, up1, m1).json()["status"] == "COMMITTED"
    # one committed + one open -> PARTIAL
    assert _state(client, fid)["upload_state"] == "PARTIAL"
    # optional artifact missing does NOT block COMMITTED
    a2 = _aid(m2, 0)
    _put(client, up2, a2, blobs2[a2])
    r = _complete(client, up2, m2)
    assert r.json()["status"] == "COMMITTED", r.text
    assert r.json()["missing_artifact_ids"] == []
    view = _state(client, fid)
    assert view["upload_state"] == "COMPLETE"
    assert view["completeness"]["manifests_total"] == 2
    assert view["completeness"]["manifests_committed"] == 2
