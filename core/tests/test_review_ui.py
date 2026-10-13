"""A-1 review console (static shell) + additive flight artifact listing."""

from __future__ import annotations

import re
from pathlib import Path

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
    # ...embeds no ids/data, and the ONLY credentials it may show are the two
    # documented dev-fixture tokens (they live in core/src/ksi_core/config.py
    # and are not secrets; production auth is the pre-deployment gate).
    assert set(re.findall(r"tok-[A-Za-z0-9-]+", body)) <= {"tok-orgA-rc", "tok-orgA-agent"}
    assert UUID_RE.search(body) is None


def test_review_console_exposes_a_separate_developer_annotation_write_path(client):
    """A-2 write testing remains available, but is not presented as review work."""
    root = Client(client.app, base="http://test")
    body = root.get("/review").text
    assert 'id="annotationPanel"' in body and 'id="loadAnnotations"' in body
    assert "data-anno-submit" in body
    assert "FALSE_POSITIVE_SUSPECTED" in body and "REVOKE_PREVIOUS" in body
    assert "A-2 개발용 write-path" in body
    assert "운영 검토 화면이 아닙니다" in body
    # No annotation-write shortcut is attached to each event in the primary flow.
    assert 'data-annotation="' not in body
    # Dev-console helpers: one-click dev tokens + list/detail summary + relative
    # time rendering (absolute values stay; nothing is hidden).
    assert 'id="fillDev"' in body
    assert "listSummary" in body and "relTime(" in body
    # The shell still embeds no ids (reviewer/console ids are client-side).
    assert UUID_RE.search(body) is None


def test_review_console_has_a_discoverable_accessible_developer_workflow(client):
    """The first screen says this is Core-side inspection, not RC upload proof."""
    root = Client(client.app, base="http://test")
    body = root.get("/review").text

    assert "Core 데이터 점검" in body
    assert "RC 업로드나 RC→Core 전달을 확인·증명하지 않습니다" in body
    assert 'id="accessPanel"' in body
    assert '<label for="token">Core 조회 토큰</label>' in body
    assert '<label for="writeToken">A-2 개발용 작성 토큰' in body
    # Connection, source provenance, selected record and next diagnosis are
    # distinct facts; the UI never infers real data from the Core origin.
    assert 'id="envOrigin"' in body
    assert 'id="sourceProfile"' in body
    assert 'id="nextActionButton"' in body
    assert 'id="selectedFlightState"' in body
    assert 'id="readListStatus"' in body
    assert 'id="readDetailStatus"' in body
    assert 'id="readEvidenceStatus"' in body
    assert "출처 미확인" in body
    assert 'id="flight-rail"' in body and 'id="detailPanel"' in body
    assert 'aria-label="비행 상세 섹션"' in body
    assert "Core 응답 상태" in body and "커버리지와 관측" in body
    assert "개발자 원문 JSON" in body


def test_review_console_only_labels_the_canonical_demo_store_and_hides_its_path(client, tmp_path):
    """Only the canonical seeded store gets a demo label; lookalike names do not."""
    root = Client(client.app, base="http://test")

    unknown = root.get("/review").text
    assert 'data-source-profile="unverified"' in unknown
    assert str(tmp_path) not in unknown

    settings = client.app.state.settings
    settings.db_url = f"sqlite:///{tmp_path / 'review-demo.db'}"
    settings.object_root = tmp_path / "review-demo-objects"
    lookalike = root.get("/review").text
    assert 'data-source-profile="unverified"' in lookalike
    assert str(tmp_path) not in lookalike

    repo_root = Path(__file__).resolve().parents[2]
    settings.db_url = f"sqlite:///{repo_root / 'core' / 'var' / 'review-demo.db'}"
    settings.object_root = repo_root / "core" / "var" / "review-demo-objects"
    response = root.get("/review")
    demo = response.text
    assert response.headers["cache-control"] == "no-store"
    assert 'data-source-profile="demo"' in demo
    assert str(repo_root) not in demo


def test_annotation_write_requires_rc_scope(client):
    """A-2: console writes go through the frozen contract endpoint — scope rules hold."""
    eid = "00000000-0000-4000-8000-000000000000"
    r = client.post(f"/events/{eid}/annotations", token=AGENT, json={})
    assert r.status_code == 403  # read scope cannot write (authorized before body)
    r = client.post(f"/events/{eid}/annotations", token=RC, json={})
    assert r.status_code == 422  # invalid body — validation precedes lookup/write


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
