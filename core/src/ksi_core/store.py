"""Filesystem object store: chunked writes, size/sha verification, reads."""

from __future__ import annotations

import hashlib
from pathlib import Path


def object_path(root: Path, upload_id: str, artifact_id: str) -> Path:
    return root / upload_id / artifact_id


def write_chunk(root: Path, upload_id: str, artifact_id: str, start: int, data: bytes, total: int) -> None:
    """Write ``data`` at ``start``; pre-size the file to ``total`` (sparse)."""
    path = object_path(root, upload_id, artifact_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with open(path, "wb") as fh:
            fh.truncate(total)
    else:
        current = path.stat().st_size
        if current != total:
            with open(path, "r+b") as fh:
                fh.truncate(total)
    with open(path, "r+b") as fh:
        fh.seek(start)
        fh.write(data)


def stored_size(root: Path, upload_id: str, artifact_id: str) -> int | None:
    path = object_path(root, upload_id, artifact_id)
    if not path.exists():
        return None
    return path.stat().st_size


def sha256_file(root: Path, upload_id: str, artifact_id: str) -> str | None:
    path = object_path(root, upload_id, artifact_id)
    if not path.exists():
        return None
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_bytes(root: Path, upload_id: str, artifact_id: str) -> bytes | None:
    path = object_path(root, upload_id, artifact_id)
    if not path.exists():
        return None
    return path.read_bytes()


def merge_ranges(ranges: list, start: int, end: int) -> list:
    """Merge inclusive [start, end] into a sorted non-overlapping range list."""
    merged = sorted([list(r) for r in ranges] + [[start, end]])
    out: list = []
    for lo, hi in merged:
        if out and lo <= out[-1][1] + 1:
            out[-1][1] = max(out[-1][1], hi)
        else:
            out.append([lo, hi])
    return out


def covered_bytes(ranges: list) -> int:
    return sum(hi - lo + 1 for lo, hi in ranges)
