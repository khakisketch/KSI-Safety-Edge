#!/usr/bin/env python3
"""Seed a local demo dataset for the /review console (A-2 viewing session).

Creates (resetting) a demo-only store — it never touches the default
core/var/ksi_core.db:

    core/var/review-demo.db
    core/var/review-demo-objects/

Dataset: 1 flight (RC-registered) · 2 observations (1 detection each) ·
2 events (one linked to evidence) · 1 flight summary (coverage) · 1 edge
flight-session · 1 committed manifest with a previewable PNG snapshot
(EDGE_SNAPSHOT) + a video segment (RC_PROXY_SEGMENT) · 1 RC operator
annotation (so the reviewer sees existing append-only history).

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
    make_annotation,
    make_event,
    make_flight_summary,
    make_manifest,
    make_observation,
    make_session,
    register_flight,
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


def main() -> None:
    reset()
    client = Client(create_app(), base="http://test/api/v1/edge")

    flight = register_flight(client)
    fid = flight["flight_id"]

    obs = [make_observation(fid), make_observation(fid)]
    r = client.post(
        f"/flights/{fid}/observations:batch",
        token=RC,
        json={"client_batch_id": "demo-obs-1", "items": obs},
    )
    assert r.status_code == 200, r.text

    manifest, blobs = make_manifest(
        fid,
        [
            ("snapshot-1", tiny_png(), "image/png", True, "EDGE_SNAPSHOT"),
            ("segment-1", b"demo-segment-bytes-0001", "video/mp4", True, "RC_PROXY_SEGMENT"),
        ],
    )
    r = client.post("/ingest-sessions", token=RC, json=manifest)
    assert r.status_code == 200, r.text
    upload_id = r.json()["upload_id"]
    for artifact_id, data in blobs.items():
        r = client.put(
            f"/ingest-sessions/{upload_id}/objects/{artifact_id}",
            token=RC,
            headers={"Content-Range": f"bytes 0-{len(data) - 1}/{len(data)}"},
            content=data,
        )
        assert r.status_code == 200, r.text
    r = client.post(
        f"/ingest-sessions/{upload_id}/complete",
        token=RC,
        json={"manifest_sha256": manifest["manifest_sha256"]},
    )
    assert r.status_code == 200, r.text
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

    annotation = make_annotation(event_a["event_id"], fid)
    r = client.post(f"/events/{event_a['event_id']}/annotations", token=RC, json=annotation)
    assert r.status_code == 200, r.text

    print("demo store seeded (reset):")
    print(f"  db      : {DEMO_DB}")
    print(f"  objects : {DEMO_OBJECTS}")
    print(f"  flight  : {fid}")
    print(f"  events  : {event_a['event_id']} (evidence-linked), {event_b['event_id']}")
    print(f"  snapshot: {snapshot_id}")
    print("")
    print("run the viewing session:")
    print("  KSI_DB_URL=sqlite:///$PWD/core/var/review-demo.db \\")
    print("  KSI_OBJECT_ROOT=$PWD/core/var/review-demo-objects \\")
    print("  .venv/bin/uvicorn ksi_core.app:create_app --factory --port 8000")
    print("  → http://127.0.0.1:8000/review  (read: tok-orgA-agent · write: tok-orgA-rc)")


if __name__ == "__main__":
    main()
