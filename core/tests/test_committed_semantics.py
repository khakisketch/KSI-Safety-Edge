"""Unit D: Core COMMITTED semantics (§6) — durable commit only after the
session's required artifacts and digests are verified and durably stored.

Pins current behavior (no Core change): a missing/invalid required artifact
stays PARTIAL with no durable commit id, optional-only gaps still commit,
and Core never reports a session verdict (SUCCESS/FAILED/... is deferred
per §9-1/§9-2 — Core must not infer success from stored artifacts).
"""

from __future__ import annotations

from core_helpers import AGENT, RC, make_manifest, register_flight


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


def test_missing_required_artifact_stays_partial_without_durable_commit(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    manifest, blobs = make_manifest(fid, [
        ("req-a", b"A" * 64, "video/mp4", True, "RC_PROXY_SEGMENT"),
        ("req-b", b"B" * 64, "video/mp4", True, "RC_PROXY_SEGMENT"),
    ])
    aids = [a["artifact_id"] for a in manifest["artifacts"]]
    up = client.post("/ingest-sessions", token=RC, json=manifest).json()["upload_id"]
    _put(client, up, aids[0], blobs[aids[0]])
    r = _complete(client, up, manifest)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "PARTIAL"
    assert body["durable_commit_id"] is None
    assert body["missing_artifact_ids"] == [aids[1]]
    assert body["committed_at_utc"] is None
    view = client.get(f"/flights/{fid}", token=AGENT).json()
    assert view["upload_state"] == "PARTIAL"


def test_wrong_digest_bytes_stay_partial_without_durable_commit(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    good = b"G" * 48
    manifest, blobs = make_manifest(fid, [("req-x", good, "video/mp4", True, "RC_PROXY_SEGMENT")])
    aid = manifest["artifacts"][0]["artifact_id"]
    up = client.post("/ingest-sessions", token=RC, json=manifest).json()["upload_id"]
    r = _put(client, up, aid, b"B" * 48)  # full size, wrong content
    assert r.json()["complete"] is True
    body = _complete(client, up, manifest).json()
    assert body["status"] == "PARTIAL"
    assert body["durable_commit_id"] is None
    assert body["missing_artifact_ids"] == [aid]


def test_optional_only_gap_still_commits_with_durable_id(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    manifest, blobs = make_manifest(fid, [
        ("req-y", b"Y" * 32, "video/mp4", True, "RC_PROXY_SEGMENT"),
        ("opt-z", b"", "image/jpeg", False, "EDGE_SNAPSHOT"),
    ])
    aids = [a["artifact_id"] for a in manifest["artifacts"]]
    up = client.post("/ingest-sessions", token=RC, json=manifest).json()["upload_id"]
    _put(client, up, aids[0], blobs[aids[0]])
    body = _complete(client, up, manifest).json()
    assert body["status"] == "COMMITTED"
    assert body["durable_commit_id"]
    assert body["missing_artifact_ids"] == []
    view = client.get(f"/flights/{fid}", token=AGENT).json()
    assert view["upload_state"] == "COMPLETE"


def test_commit_response_carries_no_session_verdict(client):
    """COMMITTED is preservation only: no SUCCESS/FAILED/INTERRUPTED/UNKNOWN."""
    flight = register_flight(client)
    fid = flight["flight_id"]
    manifest, blobs = make_manifest(fid, [("req-v", b"V" * 16, "video/mp4", True, "RC_PROXY_SEGMENT")])
    aid = manifest["artifacts"][0]["artifact_id"]
    up = client.post("/ingest-sessions", token=RC, json=manifest).json()["upload_id"]
    _put(client, up, aid, blobs[aid])
    body = _complete(client, up, manifest).json()
    assert set(body) == {
        "status", "durable_commit_id", "manifest_sha256",
        "missing_artifact_ids", "committed_at_utc",
    }
    assert body["status"] == "COMMITTED"
    for forbidden in ("verdict", "success", "session_result", "result"):
        assert forbidden not in body
    view = client.get(f"/flights/{fid}", token=AGENT).json()
    assert "verdict" not in view and "session_result" not in view
