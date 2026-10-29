"""Read-only custody checks shared by the collector and development UI bridge.

These checks establish byte/set consistency, not a digital signature or device
attestation. They never infer RC receipt, observation completeness or an alert
delivery from a Core custody receipt.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import sys
import tempfile

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "libs" / "ksi_contracts" / "src"))
from ksi_contracts import ContractError, canonical_bytes, digest_sha256, validate_schema, verify_digest  # noqa: E402

SAFE_ID = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
ASSESSMENT_MEDIA_TYPES = {
    "application/vnd.ksi.autonomous-assessment+json",
    "application/vnd.ksi.assessment+json",
}


def _object(pairs: list[tuple]) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_json(path: Path, max_bytes: int = 16 * 1024 * 1024) -> dict:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > max_bytes:
        raise ValueError(f"invalid or oversized JSON file: {path.name}")
    def reject_constant(value: str):
        raise ValueError(f"non-JSON constant: {value}")
    result = json.loads(path.read_text("utf-8"), object_pairs_hook=_object,
                        parse_constant=reject_constant)
    if not isinstance(result, dict):
        raise ValueError(f"JSON object required: {path.name}")
    return result


def source_kind(media_type: str) -> str:
    if media_type == "application/x-raw-rgb24":
        return "EDGE_SOURCE_FRAME"
    if media_type in ASSESSMENT_MEDIA_TYPES:
        return "EDGE_ASSESSMENT"
    return "EDGE_EVIDENCE_ARTIFACT"


def verify_revision(revision: dict, root: Path) -> list[dict]:
    if revision.get("schema_version") != 1 or not verify_digest(revision, "revision_sha256"):
        raise ValueError("sealed revision digest/version mismatch")
    assets = revision.get("assets")
    if not isinstance(assets, list) or not assets:
        raise ValueError("sealed revision must name its evidence assets")
    seen = set()
    root = root.resolve()
    for asset in assets:
        if not isinstance(asset, dict):
            raise ValueError("invalid revision asset")
        identity = asset.get("asset_id")
        if not isinstance(identity, str) or not SAFE_ID.fullmatch(identity) or identity in {".", ".."}:
            raise ValueError("unsafe revision asset id")
        if identity in seen:
            raise ValueError("duplicate revision asset id")
        seen.add(identity)
        for key in ("flight_id", "edge_boot_id"):
            if not revision.get(key) or asset.get(key) != revision[key]:
                raise ValueError(f"asset {key} does not match sealed revision")
        blob = root / "assets" / identity
        if blob.is_symlink() or not blob.is_file() or not blob.resolve().is_relative_to(root):
            raise ValueError(f"missing or unsafe asset: {identity}")
        size = asset.get("size_bytes")
        if type(size) is not int or size < 0 or blob.stat().st_size != size:
            raise ValueError(f"asset does not match the sealed digest (size differs from sealed revision): {identity}")
        digest = hashlib.sha256()
        with blob.open("rb") as handle:
            for block in iter(lambda: handle.read(1 << 20), b""):
                digest.update(block)
        if digest.hexdigest() != asset.get("sha256"):
            raise ValueError(f"asset does not match the sealed digest: {identity}")
    return assets


def verify_custody_receipt(receipt: dict, manifest: dict) -> None:
    try:
        validate_schema("collection-manifest.v1", manifest)
        validate_schema("collection-receipt.v1", receipt)
    except ContractError as exc:
        raise ValueError(f"invalid custody contract: {exc}") from exc
    if not verify_digest(receipt, "receipt_sha256"):
        raise ValueError("custody receipt digest mismatch")
    for key in ("collection_id", "flight_id", "edge_boot_id", "sealed_revision_sha256", "producer"):
        if receipt.get(key) != manifest.get(key):
            raise ValueError(f"custody receipt {key} mismatch")
    if receipt.get("manifest_sha256") != digest_sha256(manifest):
        raise ValueError("custody receipt manifest mismatch")
    artifacts = manifest["artifacts"]
    ids = [a["artifact_id"] for a in artifacts]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate manifest artifact id")
    required = sorted((a["artifact_id"], a["sha256"]) for a in artifacts if a["required"])
    if receipt["required_artifact_ids"] != [a for a, _ in required]:
        raise ValueError("custody receipt required artifact set mismatch")
    expected = digest_sha256({"required": [{"artifact_id": a, "sha256": s} for a, s in required]})
    if receipt["artifact_set_sha256"] != expected:
        raise ValueError("custody receipt artifact digest set mismatch")


def save_immutable_json(path: Path, body: dict) -> None:
    """Atomic, fsynced create; an unrelated existing file is never replaced."""
    payload = canonical_bytes(body) + b"\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() or path.is_symlink():
        if path.is_symlink() or path.read_bytes() != payload:
            raise ValueError(f"refusing to replace existing proof: {path.name}")
        return
    name = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".proof-", delete=False) as handle:
            name = Path(handle.name)
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.link(name, path)
        except FileExistsError:
            if path.is_symlink() or path.read_bytes() != payload:
                raise ValueError(f"proof appeared with different bytes: {path.name}")
        fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        if name is not None:
            name.unlink(missing_ok=True)
