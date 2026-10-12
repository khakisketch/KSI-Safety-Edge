"""A-1 review console (static shell) + additive flight artifact listing."""

from __future__ import annotations

import re

from core_helpers import AGENT, ORGB, RC, Client, make_manifest, register_flight

UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}")


def test_review_console_shell_is_static_and_data_free(client):
    root = Client(client.app, base="http://test")
    r = root.get("/review")
    assert r.status_code == 200
    assert r.headers["content-type"].startswith("text/html")
    body = r.text
    assert 'id="review-app"' in body
    # It drives the existing read APIs...
    assert "/api/v1/edge" in body
    # ...and embeds no data, no credentials, no ids.
    assert "tok-orgA-rc" not in body and "tok-orgA-agent" not in body
    assert UUID_RE.search(body) is None


def test_flight_artifacts_listing_covers_zero_event_flights(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    manifest, blobs = make_manifest(
        fid,
        [("seg", b"segment-bytes-0001", "video/mp4", True, "RC_PROXY_SEGMENT")],
    )
    r = client.post("/ingest-sessions", token=RC, json=manifest)
    assert r.status_code == 200, r.text
    upload_id = r.json()["upload_id"]
    aid = manifest["artifacts"][0]["artifact_id"]
    data = blobs[aid]
    r = client.put(
        f"/ingest-sessions/{upload_id}/objects/{aid}",
        token=RC,
        headers={"Content-Range": f"bytes 0-{len(data) - 1}/{len(data)}"},
        content=data,
    )
    assert r.status_code == 200, r.text

    # Before commit: listed, but honestly NOT downloadable yet.
    r = client.get(f"/flights/{fid}/artifacts", token=AGENT)
    assert r.status_code == 200, r.text
    items = r.json()["items"]
    assert len(items) == 1
    item = items[0]
    assert item["artifact_id"] == aid
    assert item["source_kind"] == "RC_PROXY_SEGMENT"
    assert item["committed"] is False
    assert item["session_status"] == "OPEN"

    # Commit -> the flag flips; a zero-event flight still shows its evidence.
    r = client.post(
        f"/ingest-sessions/{upload_id}/complete",
        token=RC,
        json={"manifest_sha256": manifest["manifest_sha256"]},
    )
    assert r.status_code == 200, r.text
    item = client.get(f"/flights/{fid}/artifacts", token=AGENT).json()["items"][0]
    assert item["committed"] is True
    assert item["media_type"] == "video/mp4"
    assert item["required"] is True
    assert item["download_url"].endswith(aid)

    # Org isolation: cross-org access is invisible (404, never 403).
    assert client.get(f"/flights/{fid}/artifacts", token=ORGB).status_code == 404
