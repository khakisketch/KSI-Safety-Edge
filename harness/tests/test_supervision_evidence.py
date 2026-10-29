"""Byte/custody checks for the real collector and read-only UI bridge."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import sys

import pytest

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
from supervision_evidence import (  # noqa: E402
    digest_sha256, load_json, save_immutable_json, source_kind,
    verify_custody_receipt, verify_revision,
)
from ksi_contracts import set_digest  # noqa: E402


def manifest_and_receipt():
    manifest = {
        "schema_version": 1,
        "collection_id": "11111111-2222-4333-8444-555555555555",
        "flight_id": "22222222-2222-4333-8444-555555555555",
        "edge_boot_id": "33333333-2222-4333-8444-555555555555",
        "producer": {"type": "PC_COLLECTOR", "id": "test-only"},
        "sealed_revision_sha256": "a" * 64,
        "artifacts": [{
            "artifact_id": "44444444-2222-4333-8444-555555555555",
            "sha256": "b" * 64, "size_bytes": 12, "required": True,
            "media_type": "application/vnd.ksi.autonomous-assessment+json",
            "source_kind": "EDGE_ASSESSMENT",
        }],
    }
    artifact = manifest["artifacts"][0]
    receipt = {
        "schema_version": 1, "collection_id": manifest["collection_id"],
        "flight_id": manifest["flight_id"], "edge_boot_id": manifest["edge_boot_id"],
        "producer": manifest["producer"], "sealed_revision_sha256": "a" * 64,
        "upload_id": "55555555-2222-4333-8444-555555555555",
        "manifest_sha256": digest_sha256(manifest),
        "required_artifact_ids": [artifact["artifact_id"]],
        "artifact_set_sha256": digest_sha256({"required": [{
            "artifact_id": artifact["artifact_id"], "sha256": artifact["sha256"]}]}),
        "issued_at_utc": "2026-10-23T00:00:00Z",
    }
    return manifest, set_digest(receipt, "receipt_sha256")


def test_custody_binds_the_exact_manifest_and_artifacts():
    manifest, receipt = manifest_and_receipt()
    verify_custody_receipt(receipt, manifest)
    changed = copy.deepcopy(manifest)
    changed["artifacts"][0]["sha256"] = "c" * 64
    with pytest.raises(ValueError, match="manifest mismatch"):
        verify_custody_receipt(receipt, changed)


@pytest.mark.parametrize("key,value", [
    ("flight_id", "66666666-2222-4333-8444-555555555555"),
    ("required_artifact_ids", []), ("artifact_set_sha256", "c" * 64),
])
def test_self_consistent_receipt_cannot_be_reused_for_another_set(key, value):
    manifest, receipt = manifest_and_receipt()
    receipt[key] = value
    receipt = set_digest(receipt, "receipt_sha256")
    with pytest.raises(ValueError):
        verify_custody_receipt(receipt, manifest)


def test_receipt_missing_its_own_digest_is_not_a_custody_proof():
    manifest, receipt = manifest_and_receipt()
    receipt.pop("receipt_sha256")
    with pytest.raises(ValueError, match="digest mismatch"):
        verify_custody_receipt(receipt, manifest)


def test_saved_receipt_is_immutable_and_idempotent(tmp_path):
    _, receipt = manifest_and_receipt()
    path = tmp_path / "receipt.json"
    save_immutable_json(path, receipt)
    before = path.read_bytes()
    save_immutable_json(path, receipt)
    assert path.read_bytes() == before
    with pytest.raises(ValueError, match="refusing to replace"):
        save_immutable_json(path, {"other": "proof"})
    assert path.read_bytes() == before
    assert list(tmp_path.glob(".proof-*")) == []


@pytest.mark.parametrize("text", ['{"a":1,"a":2}', '{"a":NaN}', '[]'])
def test_bridge_json_is_not_ambiguous(tmp_path, text):
    p = tmp_path / "bad.json"
    p.write_text(text)
    with pytest.raises(ValueError):
        load_json(p)


def test_machine_assessment_is_not_labelled_a_source_frame():
    assert source_kind("application/x-raw-rgb24") == "EDGE_SOURCE_FRAME"
    assert source_kind("application/vnd.ksi.autonomous-assessment+json") == "EDGE_ASSESSMENT"
    assert source_kind("application/octet-stream") == "EDGE_EVIDENCE_ARTIFACT"


def test_revision_metadata_must_match_the_actual_bytes(tmp_path):
    import hashlib
    raw = b"source before and after a simulated observation"
    (tmp_path / "assets").mkdir()
    (tmp_path / "assets" / "frame-1").write_bytes(raw)
    asset = {"asset_id": "frame-1", "size_bytes": len(raw),
             "sha256": hashlib.sha256(raw).hexdigest(), "flight_id": "flight", "edge_boot_id": "boot"}
    revision = set_digest({"schema_version": 1, "flight_id": "flight", "edge_boot_id": "boot",
                           "assets": [asset]}, "revision_sha256")
    verify_revision(revision, tmp_path)
    changed = copy.deepcopy(revision)
    changed["assets"][0]["size_bytes"] += 1
    with pytest.raises(ValueError, match="digest/version"):
        verify_revision(changed, tmp_path)
    changed = set_digest(changed, "revision_sha256")
    with pytest.raises(ValueError, match="size differs"):
        verify_revision(changed, tmp_path)
    changed = copy.deepcopy(revision)
    changed["assets"].append(asset)
    with pytest.raises(ValueError, match="duplicate"):
        verify_revision(set_digest(changed, "revision_sha256"), tmp_path)
