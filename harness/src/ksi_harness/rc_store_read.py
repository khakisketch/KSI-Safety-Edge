"""Read a pulled RC device store (FileRcStore NDJSON layout) — READ-ONLY.

Input is what `scripts/rc_device_day.sh pull-store` collects from the RC
(`files/rc-store` as a directory, or the `rc-store.tar` archive). This module
never writes into the store and never "repairs" it: malformed lines and
missing files are counted and reported so correlation evidence shows the
store exactly as it was pulled.

Field shapes follow `android-rc/rc-core/.../FileRcStore.kt`:
- `flights.ndjson`        — flight record snapshots (latest per id wins)
- `observations/events/summaries/artifacts.ndjson` — {"boot_id", "node",
  "received_wall_utc"} wrappers
- `chunks.ndjson`          — {"artifact_id", "offset", "length"} receipts
- `synced.ndjson`          — artifact_id per line
- `annotations.ndjson`     — annotation nodes; `annotation_uploads.ndjson`
  — upload receipt ids per line
- `batch_receipts.ndjson`  — {"kind","client_batch_id","server_batch_id"}
- `messages.ndjson`        — MOP envelopes (top-level flight_id/edge_boot_id)
- `gaps.ndjson`            — gap declarations with received_wall_utc
"""

from __future__ import annotations

import json
import tarfile
from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class BootNode:
    """One received evidence record with its RC receipt stamp."""

    boot_id: str
    node: dict
    received_wall_utc: str | None


@dataclass
class DeviceStore:
    root: Path
    flights: dict[str, dict] = field(default_factory=dict)
    observations: dict[str, BootNode] = field(default_factory=dict)
    events: dict[str, BootNode] = field(default_factory=dict)
    summaries: dict[str, BootNode] = field(default_factory=dict)
    artifacts: dict[str, BootNode] = field(default_factory=dict)
    synced: set[str] = field(default_factory=set)
    chunks: list[dict] = field(default_factory=list)
    artifact_complete: dict[str, bool] = field(default_factory=dict)
    gaps: list[dict] = field(default_factory=list)
    annotations: dict[str, dict] = field(default_factory=dict)
    annotation_uploads: set[str] = field(default_factory=set)
    batch_receipts: set[tuple[str, str]] = field(default_factory=set)
    application_uploads: set[str] = field(default_factory=set)
    boots_by_flight: dict[str, set[str]] = field(default_factory=dict)
    parse_notes: list[str] = field(default_factory=list)

    # -- convenience -----------------------------------------------------------
    def observations_for(self, flight_id: str) -> dict[str, BootNode]:
        return {
            oid: entry
            for oid, entry in self.observations.items()
            if entry.node.get("flight_id") == flight_id
        }

    def events_for(self, flight_id: str) -> dict[str, BootNode]:
        return {
            eid: entry
            for eid, entry in self.events.items()
            if entry.node.get("flight_id") == flight_id
        }

    def artifacts_for(self, flight_id: str) -> dict[str, BootNode]:
        return {
            aid: entry
            for aid, entry in self.artifacts.items()
            if entry.node.get("flight_id") == flight_id
        }

    def annotations_for(self, flight_id: str) -> dict[str, dict]:
        return {
            aid: node
            for aid, node in self.annotations.items()
            if node.get("flight_id") == flight_id
        }


def extract_tar(tar_path: Path, dest: Path) -> None:
    """Extract a pulled `rc-store.tar` into `dest` (caller owns cleanup)."""
    with tarfile.open(tar_path, "r") as tar:
        tar.extractall(dest, filter="data")


def load_device_store(root: Path) -> DeviceStore:
    """Load `root` (or `root/files/rc-store`) as a [DeviceStore]."""
    root = _resolve_root(root)
    store = DeviceStore(root=root)

    for node in _read_ndjson(root / "flights.ndjson", store, required=False):
        if isinstance(node, dict) and isinstance(node.get("flight_id"), str):
            store.flights[node["flight_id"]] = node

    for name, target in (
        ("observations.ndjson", store.observations),
        ("events.ndjson", store.events),
        ("summaries.ndjson", store.summaries),
        ("artifacts.ndjson", store.artifacts),
    ):
        id_field = {
            "observations.ndjson": "observation_id",
            "events.ndjson": "event_id",
            "summaries.ndjson": "summary_id",
            "artifacts.ndjson": "artifact_id",
        }[name]
        for line in _read_ndjson(root / name, store, required=False):
            if not isinstance(line, dict) or not isinstance(line.get("node"), dict):
                continue
            key = line["node"].get(id_field)
            if not isinstance(key, str):
                store.parse_notes.append(f"{name}: line without {id_field}")
                continue
            target.setdefault(
                key,
                BootNode(
                    boot_id=str(line.get("boot_id") or ""),
                    node=line["node"],
                    received_wall_utc=line.get("received_wall_utc"),
                ),
            )

    store.synced = _read_ids(root / "synced.ndjson", store)
    store.annotation_uploads = _read_ids(root / "annotation_uploads.ndjson", store)
    store.application_uploads = _read_ids(root / "app_uploads.ndjson", store)
    store.chunks = [
        c for c in _read_ndjson(root / "chunks.ndjson", store, required=False)
        if isinstance(c, dict)
    ]
    store.gaps = [
        g for g in _read_ndjson(root / "gaps.ndjson", store, required=False)
        if isinstance(g, dict)
    ]
    for node in _read_ndjson(root / "annotations.ndjson", store, required=False):
        if isinstance(node, dict) and isinstance(node.get("annotation_id"), str):
            store.annotations.setdefault(node["annotation_id"], node)
    for node in _read_ndjson(root / "batch_receipts.ndjson", store, required=False):
        if isinstance(node, dict) and node.get("kind") and node.get("client_batch_id"):
            store.batch_receipts.add((str(node["kind"]), str(node["client_batch_id"])))

    for env in _read_ndjson(root / "messages.ndjson", store, required=False):
        if not isinstance(env, dict):
            continue
        flight = env.get("flight_id")
        boot = env.get("edge_boot_id")
        if isinstance(flight, str) and isinstance(boot, str) and boot:
            store.boots_by_flight.setdefault(flight, set()).add(boot)

    store.artifact_complete = _compute_coverage(store)
    return store


def _resolve_root(path: Path) -> Path:
    if (path / "files" / "rc-store").is_dir():
        return path / "files" / "rc-store"
    if (path / "rc-store").is_dir():
        return path / "rc-store"
    return path


def _read_ids(path: Path, store: DeviceStore) -> set[str]:
    ids: set[str] = set()
    if not path.is_file():
        return ids
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line:
            ids.add(line)
    return ids


def _read_ndjson(path: Path, store: DeviceStore, required: bool) -> list:
    if not path.is_file():
        if required:
            store.parse_notes.append(f"{path.name}: missing (required for this check)")
        return []
    out: list = []
    for index, raw in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        line = raw.strip()
        if not line:
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            store.parse_notes.append(f"{path.name}: line {index} malformed (skipped)")
    return out


def _compute_coverage(store: DeviceStore) -> dict[str, bool]:
    """Complete = chunk receipts cover [0, size_bytes) exactly (no holes)."""
    ranges: dict[str, list[tuple[int, int]]] = {}
    for chunk in store.chunks:
        aid = chunk.get("artifact_id")
        offset = chunk.get("offset")
        length = chunk.get("length")
        if not isinstance(aid, str) or not isinstance(offset, int) or not isinstance(length, int):
            continue
        if length <= 0:
            continue
        ranges.setdefault(aid, []).append((offset, offset + length))

    complete: dict[str, bool] = {}
    for aid, entry in store.artifacts.items():
        size = entry.node.get("size_bytes")
        if not isinstance(size, int) or size <= 0:
            complete[aid] = False
            continue
        merged = _merge(ranges.get(aid, []))
        complete[aid] = merged == [(0, size)]
    return complete


def _merge(spans: list[tuple[int, int]]) -> list[tuple[int, int]]:
    if not spans:
        return []
    spans = sorted(spans)
    merged = [spans[0]]
    for start, end in spans[1:]:
        last_start, last_end = merged[-1]
        if start <= last_end:
            merged[-1] = (last_start, max(last_end, end))
        else:
            merged.append((start, end))
    return merged
