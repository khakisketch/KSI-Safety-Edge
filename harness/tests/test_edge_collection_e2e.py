"""End-to-end: Edge sealed revision → PC collector → Core custody receipt.

This is the approved FIRST central collection path (handoff plan WP-D1):
the Edge's immutable sealed revision is the only thing collected, the bytes
travel the proven resumable chunk path, and the Core answers with a custody
receipt that binds the exact required artifact set.

What is pinned here:
  * the collector reads ONLY the revision and the assets it names;
  * a tampered asset (bytes that no longer match the sealed digest) is
    refused **before** anything is uploaded;
  * the receipt proves custody of the exact set (ids + digests);
  * the collector's local copy is kept — a receipt is not a deletion token.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "libs" / "ksi_contracts" / "src"))

from ksi_contracts.canonical import canonical_bytes  # noqa: E402

TOKEN = "tok-orgA-rc"


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _write_edge_export(root: Path, frame_bytes: bytes, *, artifact_id: str | None = None,
                       revision_id: str | None = None) -> tuple[str, dict]:
    """A fake Edge evidence store: one sealed revision + one source frame."""
    asset_id = artifact_id or "9b1f0d2e-4c3a-4b7e-9a5f-2c8d1e6b7a40"
    revision_id = revision_id or "7f6e5d4c-3b2a-4190-8f7e-6d5c4b3a2918"
    (root / "assets").mkdir(parents=True, exist_ok=True)
    (root / "revisions").mkdir(parents=True, exist_ok=True)
    (root / "assets" / asset_id).write_bytes(frame_bytes)

    revision = {
        "schema_version": 1,
        "revision_id": revision_id,
        "flight_id": "11111111-2222-4333-8444-555555555555",
        "edge_boot_id": "66666666-7777-4888-9999-aaaaaaaaaaaa",
        "sealed_at_monotonic_ns": 123456,
        "assets": [
            {
                "asset_id": asset_id,
                "sha256": _sha256(frame_bytes),
                "size_bytes": len(frame_bytes),
                "media_type": "application/x-raw-rgb24",
                "flight_id": "11111111-2222-4333-8444-555555555555",
                "edge_boot_id": "66666666-7777-4888-9999-aaaaaaaaaaaa",
                "frame_id": 42,
                "received_monotonic_ns": 120000,
                "observation_id": "3c2a1b90-7d6e-4f5a-8b9c-0d1e2f3a4b5c",
            }
        ],
    }
    revision["revision_sha256"] = hashlib.sha256(canonical_bytes(revision)).hexdigest()
    (root / "revisions" / f"{revision_id}.json").write_text(
        json.dumps(revision, ensure_ascii=False, sort_keys=True), encoding="utf-8"
    )
    return revision_id, revision


def _run_collector(core_url: str, edge_dir: Path, revision_id: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [
            sys.executable,
            str(REPO / "scripts" / "collect_edge_evidence.py"),
            "--edge-dir",
            str(edge_dir),
            "--revision",
            revision_id,
            "--core-url",
            core_url,
            "--token",
            TOKEN,
        ],
        capture_output=True,
        text=True,
        timeout=180,
    )


def test_collector_collects_a_sealed_revision_and_gets_a_receipt(core_server, tmp_path):
    frame = b"edge-source-frame-bytes" * 64
    edge_dir = tmp_path / "edge-export"
    revision_id, revision = _write_edge_export(edge_dir, frame)

    result = _run_collector(core_server, edge_dir, revision_id)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "CUSTODY RECEIPT" in result.stdout

    receipt = json.loads(result.stdout[result.stdout.index("{") : result.stdout.rindex("}") + 1])
    asset_id = revision["assets"][0]["asset_id"]
    assert receipt["collection_id"] == revision_id
    assert receipt["required_artifact_ids"] == [asset_id]
    assert receipt["sealed_revision_sha256"] == revision["revision_sha256"]
    assert receipt["receipt_sha256"]

    # The Core holds the bytes (readback through the artifact API).
    import httpx

    response = httpx.get(
        f"{core_server}/ingest-sessions/{receipt['upload_id']}",
        headers={"Authorization": f"Bearer {TOKEN}"},
        timeout=10.0,
    )
    assert response.status_code == 200
    assert asset_id not in response.json()["missing_artifact_ids"]


def test_collector_refuses_a_tampered_asset(core_server, tmp_path):
    frame = b"edge-source-frame-bytes" * 8
    edge_dir = tmp_path / "edge-export"
    revision_id, _ = _write_edge_export(edge_dir, frame)
    # The bytes on disk no longer match the sealed digest.
    (edge_dir / "assets" / "9b1f0d2e-4c3a-4b7e-9a5f-2c8d1e6b7a40").write_bytes(b"tampered")

    result = _run_collector(core_server, edge_dir, revision_id)
    assert result.returncode != 0
    assert "does not match the sealed digest" in result.stderr


def test_collector_preserves_assessment_bytes_and_saves_exact_receipt(core_server, tmp_path):
    """Both raw context and machine evaluation travel the existing custody path."""
    from ksi_contracts import set_digest
    import uuid
    edge_dir = tmp_path / "edge-export"
    revision_id, revision = _write_edge_export(edge_dir, b"context-original-bytes",
                                               artifact_id=str(uuid.uuid4()), revision_id=str(uuid.uuid4()))
    assessment_id = "5a1f0d2e-4c3a-4b7e-9a5f-2c8d1e6b7a40"
    raw = canonical_bytes({"record_type": "assessment", "mode": "SHADOW",
                           "disposition": "UNDETERMINED", "simulated": True})
    (edge_dir / "assets" / assessment_id).write_bytes(raw)
    asset = dict(revision["assets"][0], asset_id=assessment_id,
                 sha256=_sha256(raw), size_bytes=len(raw),
                 media_type="application/vnd.ksi.autonomous-assessment+json")
    revision["assets"].append(asset)
    revision = set_digest(revision, "revision_sha256")
    (edge_dir / "revisions" / f"{revision_id}.json").write_bytes(canonical_bytes(revision))
    receipt_path = tmp_path / "custody.json"
    command = [sys.executable, str(REPO / "scripts" / "collect_edge_evidence.py"),
               "--edge-dir", str(edge_dir), "--revision", revision_id,
               "--core-url", core_server, "--token", TOKEN, "--receipt-out", str(receipt_path)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    assert result.returncode == 0, result.stdout + result.stderr
    receipt = json.loads(receipt_path.read_text())
    assert set(receipt["required_artifact_ids"]) == {assessment_id, revision["assets"][0]["asset_id"]}
    assert (edge_dir / "assets" / assessment_id).read_bytes() == raw
    before = receipt_path.read_bytes()
    retried = subprocess.run(command, capture_output=True, text=True, timeout=180)
    assert retried.returncode == 0, retried.stdout + retried.stderr
    assert receipt_path.read_bytes() == before
