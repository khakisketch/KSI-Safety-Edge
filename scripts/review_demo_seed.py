#!/usr/bin/env python3
"""Seed a local demo dataset for the /review verification console.

Creates (resetting) a demo-only store — it never touches the default
core/var/ksi_core.db:

    core/var/review-demo.db
    core/var/review-demo-objects/

Dataset (3 flights, covering the verification paths the console walks):

- flight A (newest): normal — 2 observations · 2 events (one linked to a
  previewable PNG snapshot EDGE_SNAPSHOT; plus a video segment) · 1 summary ·
  1 edge flight-session · committed manifest · append-only annotation history
  (record + REVOKE_PREVIOUS — nothing is deleted).
- flight B: zero events — observations + summary + committed snapshot, no
  events (사건 0건 ≠ 안전 display path).
- flight C: PARTIAL — one committed manifest plus one OPEN ingest session
  whose required artifact never arrives (누락 artifact display path).

Viewing session:

    KSI_DB_URL=sqlite:///$PWD/core/var/review-demo.db \
    KSI_OBJECT_ROOT=$PWD/core/var/review-demo-objects \
    .venv/bin/uvicorn ksi_core.app:create_app --factory --port 8000

    → http://127.0.0.1:8000/review
      read token:  tok-orgA-agent
      write token: tok-orgA-rc   (needed to record a reviewer annotation)

Dev-only (static dev tokens, localhost). Production auth is a separate gate.
"""

from __future__ import annotations

import os
import shutil
import struct
import sys
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DEMO_DB = REPO / "core" / "var" / "review-demo.db"
DEMO_OBJECTS = REPO / "core" / "var" / "review-demo-objects"

os.environ["KSI_DB_URL"] = f"sqlite:///{DEMO_DB}"
os.environ["KSI_OBJECT_ROOT"] = str(DEMO_OBJECTS)

sys.path.insert(0, str(REPO / "core" / "tests"))

from core_helpers import (  # noqa: E402
    RC,
    Client,
    fresh_uuid,
    make_annotation,
    make_event,
    make_flight_summary,
    make_manifest,
    make_observation,
    make_session,
)
from ksi_core.app import create_app  # noqa: E402


def reset() -> None:
    for path in (DEMO_DB, DEMO_OBJECTS):
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()
    DEMO_DB.parent.mkdir(parents=True, exist_ok=True)


def tiny_png(width: int = 640, height: int = 360, rgb: tuple = (22, 35, 44)) -> bytes:
    """A valid solid-colour PNG with no external dependencies."""

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit RGB
    row = b"\x00" + bytes(rgb) * width
    idat = zlib.compress(row * height, 9)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", idat)
        + chunk(b"IEND", b"")
    )


def register_demo_flight(client: Client, started_at_utc: str) -> str:
    """Register a flight with a distinct start time (latest-first ordering)."""

    fid = fresh_uuid()
    body = {
        "flight_id": fid,
        "site_id": fresh_uuid(),
        "rc_device_id": fresh_uuid(),
        "mission_id": fresh_uuid(),
        "mission_revision_id": fresh_uuid(),
        "bundle_sha256": "0" * 64,
        "started_at_utc": started_at_utc,
        "ended_at_utc": None,
    }
    r = client.post("/flights", token=RC, json=body)
    assert r.status_code == 200, r.text
    return fid


def ingest_manifest(client: Client, flight_id: str, artifacts: list, *, commit: bool, upload: bool = True):
    """Register a manifest; upload artifacts (unless skipped) and optionally commit."""

    manifest, blobs = make_manifest(flight_id, artifacts)
    r = client.post("/ingest-sessions", token=RC, json=manifest)
    assert r.status_code == 200, r.text
    upload_id = r.json()["upload_id"]
    if upload:
        for artifact_id, data in blobs.items():
            r = client.put(
                f"/ingest-sessions/{upload_id}/objects/{artifact_id}",
                token=RC,
                headers={"Content-Range": f"bytes 0-{len(data) - 1}/{len(data)}"},
                content=data,
            )
            assert r.status_code == 200, r.text
    if commit:
        r = client.post(
            f"/ingest-sessions/{upload_id}/complete",
            token=RC,
            json={"manifest_sha256": manifest["manifest_sha256"]},
        )
        assert r.status_code == 200, r.text
    return manifest, upload_id


def main() -> None:
    reset()
    client = Client(create_app(), base="http://test/api/v1/edge")

    # --- flight A (newest): normal, with events, evidence and history --------
    fid = register_demo_flight(client, "2026-10-10T06:15:00Z")

    obs = [make_observation(fid), make_observation(fid)]
    r = client.post(
        f"/flights/{fid}/observations:batch",
        token=RC,
        json={"client_batch_id": "demo-obs-1", "items": obs},
    )
    assert r.status_code == 200, r.text

    manifest, _ = ingest_manifest(
        client,
        fid,
        [
            ("snapshot-1", tiny_png(), "image/png", True, "EDGE_SNAPSHOT"),
            ("segment-1", b"demo-segment-bytes-0001", "video/mp4", True, "RC_PROXY_SEGMENT"),
        ],
        commit=True,
    )
    snapshot_id = manifest["artifacts"][0]["artifact_id"]

    event_a = make_event(fid, obs_id=obs[0]["observation_id"], artifacts=[snapshot_id])
    event_b = make_event(fid, obs_id=obs[1]["observation_id"])
    r = client.post(
        f"/flights/{fid}/events:batch",
        token=RC,
        json={"client_batch_id": "demo-events-1", "items": [event_a, event_b]},
    )
    assert r.status_code == 200, r.text

    summary = make_flight_summary(fid)
    r = client.post(
        f"/flights/{fid}/flight-summaries:batch",
        token=RC,
        json={"client_batch_id": "demo-summary-1", "items": [summary]},
    )
    assert r.status_code == 200, r.text

    r = client.post(f"/flights/{fid}/flight-sessions", token=RC, json=make_session(fid))
    assert r.status_code == 200, r.text

    # Append-only history: an opinion record + its REVOKE_PREVIOUS cancellation
    # (the original record stays in the list — nothing is deleted).
    annotation = make_annotation(event_a["event_id"], fid)
    r = client.post(f"/events/{event_a['event_id']}/annotations", token=RC, json=annotation)
    assert r.status_code == 200, r.text
    revoke = make_annotation(
        event_a["event_id"],
        fid,
        ann_type="REVOKE_PREVIOUS",
        supersedes=annotation["annotation_id"],
    )
    r = client.post(f"/events/{event_a['event_id']}/annotations", token=RC, json=revoke)
    assert r.status_code == 200, r.text

    # --- flight B: zero events (사건 0건 ≠ 안전 display path) -----------------
    fid_b = register_demo_flight(client, "2026-10-09T04:30:00Z")
    obs_b = make_observation(fid_b)
    r = client.post(
        f"/flights/{fid_b}/observations:batch",
        token=RC,
        json={"client_batch_id": "demo-obs-b", "items": [obs_b]},
    )
    assert r.status_code == 200, r.text
    ingest_manifest(
        client,
        fid_b,
        [("snapshot-b", tiny_png(320, 180, (30, 45, 60)), "image/png", True, "EDGE_SNAPSHOT")],
        commit=True,
    )
    summary_b = make_flight_summary(fid_b)
    r = client.post(
        f"/flights/{fid_b}/flight-summaries:batch",
        token=RC,
        json={"client_batch_id": "demo-summary-b", "items": [summary_b]},
    )
    assert r.status_code == 200, r.text

    # --- flight C: PARTIAL — one committed session + one OPEN session whose
    #     required artifact never arrives (누락 artifact path) ----------------
    fid_c = register_demo_flight(client, "2026-10-08T02:00:00Z")
    ingest_manifest(
        client,
        fid_c,
        [("snapshot-c", tiny_png(200, 120, (50, 40, 30)), "image/png", True, "EDGE_SNAPSHOT")],
        commit=True,
    )
    # Deliberately left OPEN with the required artifact never uploaded:
    # PARTIAL + missing artifacts are visible in the console.
    ingest_manifest(
        client,
        fid_c,
        [("segment-c", b"never-uploaded", "video/mp4", True, "RC_PROXY_SEGMENT")],
        commit=False,
        upload=False,
    )

    print("demo store seeded (reset):")
    print(f"  db       : {DEMO_DB}")
    print(f"  objects  : {DEMO_OBJECTS}")
    print(f"  flight A : {fid}  (normal · events {event_a['event_id']}, {event_b['event_id']})")
    print(f"            annotation record + REVOKE on {event_a['event_id']}")
    print(f"  flight B : {fid_b}  (zero events)")
    print(f"  flight C : {fid_c}  (PARTIAL · one open session / missing artifact)")
    print("")
    print("run the viewing session:")
    print("  KSI_DB_URL=sqlite:///$PWD/core/var/review-demo.db \\")
    print("  KSI_OBJECT_ROOT=$PWD/core/var/review-demo-objects \\")
    print("  .venv/bin/uvicorn ksi_core.app:create_app --factory --port 8000")
    print("  → http://127.0.0.1:8000/review  (read: tok-orgA-agent · write: tok-orgA-rc)")


if __name__ == "__main__":
    main()
