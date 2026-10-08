"""Deterministic fixture builders (uuid5 everywhere, digests via ksi_contracts).

``world(tag)`` → site + 2 zone revisions + mission snapshot + risk
policy + bundle. ``flight_a/b/c(tag)`` → per-boot observations, events,
artifacts, flight summaries. All ids derive from ``tag`` so E2E tests can
isolate flights on one shared server while staying deterministic.
"""

from __future__ import annotations

import hashlib
import json
import uuid

from ksi_contracts import set_digest

from .mop import NAMESPACE

OBS_BASE_NS = 12_883_001_000_000
FRAME_STEP_NS = 33_333_333
MODEL_DIGEST = "ab" * 32


def _uid(*parts: str) -> str:
    return str(uuid.uuid5(NAMESPACE, "|".join(parts)))


def _polygon(lon: float, lat: float, dx: float = 0.0008, dy: float = 0.0007) -> dict:
    ring = [
        [lon, lat],
        [lon + dx, lat],
        [lon + dx, lat + dy],
        [lon, lat],
    ]
    return {"type": "Polygon", "coordinates": [ring]}


def _zone(tag: str, name: str, rule_kind: str, lon: float, lat: float) -> dict:
    zone_id = _uid(tag, "zone", name)
    revision_id = _uid(tag, "zone-rev", name, "v1")
    geometry = _polygon(lon, lat)
    zone_sha = hashlib.sha256(json.dumps(geometry, sort_keys=True).encode()).hexdigest()
    return {
        "zone_id": zone_id,
        "revision_id": revision_id,
        "rule_kind": rule_kind,
        "geometry_wgs84": geometry,
        "zone_sha256": zone_sha,
        "enabled": True,
    }


def _snapshot(tag: str, site_id: str, zones: list, flight_id: str) -> dict:
    mission_id = _uid(tag, "mission")
    revision_id = _uid(tag, "mission-rev")
    snap = {
        "schema_version": 1,
        "mission_id": mission_id,
        "flight_id": flight_id,
        "org_id": _uid(tag, "org"),
        "site_id": site_id,
        "mission_revision_id": revision_id,
        "origin": "SERVER",
        "created_at_utc": "2026-10-08T02:00:00Z",
        "zones": zones,
        "model_profiles": [
            {
                "model_id": "ksi-person-ppe-test",
                "model_digest": MODEL_DIGEST,
                "enabled": True,
            }
        ],
        "recording_policy": {
            "rc_proxy": True,
            "target_video_bitrate_bps": 1500000,
            "target_fps": 15,
            "segment_seconds": 300,
        },
    }
    return set_digest(snap, "content_sha256")


def _policy(tag: str, site_id: str, revision_name: str = "rev1", zone_rev_ids=()) -> dict:
    policy = {
        "schema_version": 1,
        "policy_id": _uid(tag, "policy"),
        "revision_id": _uid(tag, "policy-rev", revision_name),
        "site_id": site_id,
        "rules": [
            {
                "rule_id": "excavator-proximity-shadow",
                "kind": "PERSON_EQUIPMENT_PROXIMITY",
                "mode": "SHADOW",
                "threshold_m": 4.0,
                "hysteresis_m": 1.0,
                "enter_duration_ms": 1200,
                "exit_duration_ms": 1500,
                "required_geometry_level": "METRIC_VERIFIED",
                "enabled_equipment_classes": ["excavator"],
                "zone_revision_ids": list(zone_rev_ids),
            }
        ],
        "created_offline": False,
    }
    return set_digest(policy, "content_sha256")


def _bundle(tag: str, site_id: str, snapshot: dict, policy: dict, name: str = "b1") -> dict:
    bundle = {
        "schema_version": 1,
        "bundle_id": _uid(tag, "bundle", name),
        "site_id": site_id,
        "created_at_utc": "2026-10-08T02:01:00Z",
        "origin": "SERVER",
        "created_by": "seed-operator",
        "mission_snapshot": snapshot,
        "risk_policy": policy,
        "calibration_refs": [
            {
                "calibration_revision_id": "cal-2026-10-wide",
                "lens_id": "wide",
                "sha256": "22" * 32,
            }
        ],
        "ground_model_refs": [
            {
                "ground_model_revision_id": "ground-plane-cheongju-01",
                "kind": "PLANE",
                "sha256": "33" * 32,
            }
        ],
    }
    return set_digest(bundle, "bundle_sha256")


def world(tag: str = "default") -> dict:
    """Site + zones + snapshot + policy + bundle (all digest-stamped)."""
    site_id = _uid(tag, "site")
    flight_id = _uid(tag, "world-flight")
    zones = [
        _zone(tag, "gate", "PERSON_ENTRY", 127.49, 36.63),
        _zone(tag, "pit", "EQUIPMENT_ENTRY", 127.4912, 36.6308),
    ]
    snapshot = _snapshot(tag, site_id, zones, flight_id)
    policy = _policy(tag, site_id, "rev1", [z["revision_id"] for z in zones])
    bundle = _bundle(tag, site_id, snapshot, policy, "b1")
    return {
        "tag": tag,
        "site": {"site_id": site_id, "name": f"cheongju-flat-{tag}"},
        "zones": zones,
        "snapshot": snapshot,
        "policy": policy,
        "bundle": bundle,
    }


# --- observations / events -------------------------------------------------


def _observation(
    tag: str,
    flight_id: str,
    boot_id: str,
    mission_rev: str,
    frame_seq: int,
    zone_rev_ids: list,
    quality: str,
    geometry: str,
    det_i: int,
) -> dict:
    ts = OBS_BASE_NS + frame_seq * FRAME_STEP_NS
    classes = ["person", "excavator"] if det_i % 3 else ["person"]
    dets = []
    for k, cls in enumerate(classes):
        x1 = 400 + ((det_i * 13 + k * 97) % 200)
        dets.append(
            {
                "detection_id": _uid(tag, flight_id, "det", str(frame_seq), str(k)),
                "class_id": cls,
                "score": round(0.99 - 0.01 * ((det_i + k) % 9), 3),
                "bbox_xyxy_px": [x1, 290 + k * 5, x1 + 120, 760],
                "track_id": f"trk-{cls}-{det_i % 5}",
                "position_wgs84": None
                if geometry == "WITHHELD"
                else {
                    "lon": 127.49 + frame_seq * 0.000001,
                    "lat": 36.63 + frame_seq * 0.000001,
                    "uncertainty_radius_m": 1.5,
                },
            }
        )
    return {
        "schema_version": 1,
        "observation_id": _uid(tag, flight_id, "obs", str(frame_seq)),
        "flight_id": flight_id,
        "mission_revision_id": mission_rev,
        "edge_boot_id": boot_id,
        "source_kind": "PSDK_EDGE",
        "edge_frame_seq": frame_seq,
        "edge_frame_id": None,
        "lens_id": "wide",
        "frame_received_monotonic_ns": ts,
        "model_digest": MODEL_DIGEST,
        "sync_quality": quality,
        "geometry_status": geometry,
        "telemetry_refs": [
            {
                "topic": "aircraft_attitude",
                "source_timestamp_ns": None,
                "edge_receive_monotonic_ns": ts - 6_000_000,
                "sample_age_ms": 60.1,
                "timestamp_basis": "PSDK_LOCAL_RECEIVE",
                "quality": quality if quality in ("VALID", "DEGRADED") else "UNKNOWN",
            }
        ],
        "detections": dets,
        "quality_reasons": [] if quality == "VALID" else ["exposure_timestamp_unknown"],
        "zone_revision_ids": list(zone_rev_ids),
    }


def _event(
    tag: str,
    flight_id: str,
    obs: dict,
    idx: int,
    event_type: str,
    snapshot_hash: str,
    policy_rev: str | None,
    zone_rev: str | None,
    artifact_ids: list,
) -> dict:
    return {
        "schema_version": 1,
        "event_id": _uid(tag, flight_id, "event", str(idx)),
        "flight_id": flight_id,
        "observation_id": obs["observation_id"],
        "episode_id": None,
        "event_type": event_type,
        "mode": "SHADOW",
        "detected_at_edge_monotonic_ns": obs["frame_received_monotonic_ns"] + 5_000_000,
        "zone_revision_id": zone_rev,
        "policy_revision_id": policy_rev,
        "evidence_artifact_ids": list(artifact_ids),
        "initial_model_output": {"person": 0.91, "excavator": 0.87, "distance_m": 4.2},
        "observation_quality": "DEGRADED"
        if obs["sync_quality"] != "VALID"
        else "VALID",
        "mission_snapshot_hash": snapshot_hash,
    }


def _artifact_bytes(size: int, seed: int) -> bytes:
    return bytes(((i * 31 + seed * 7 + (i >> 8)) % 256) for i in range(size))


def _artifacts(tag: str, flight_id: str, boot_label: str) -> tuple[list, dict]:
    """3 required artifacts: 2 snapshots (~2KB) + 1 proxy (~8KB)."""
    specs = [
        ("snap-1", 2048, "EDGE_SNAPSHOT", "image/jpeg", None),
        ("snap-2", 2048, "EDGE_SNAPSHOT", "image/jpeg", None),
        ("proxy-0", 8192, "RC_PROXY_SEGMENT", "video/mp4", 0),
    ]
    items, blobs = [], {}
    for name, size, kind, media, seg in specs:
        aid = _uid(tag, flight_id, boot_label, name)
        data = _artifact_bytes(size, len(blobs) + 1)
        blobs[aid] = data
        items.append(
            {
                "artifact_id": aid,
                "bytes": data,
                "source_kind": kind,
                "media_type": media,
                "required": True,
                "segment_index": seg,
                "sha256": hashlib.sha256(data).hexdigest(),
                "size_bytes": size,
            }
        )
    return items, blobs


def _summary(
    tag: str,
    flight_id: str,
    boot_id: str,
    session_id: str,
    name: str,
    ai_ratio: float,
    gap_frac: float,
    obs_count: int,
) -> dict:
    start = OBS_BASE_NS
    end = OBS_BASE_NS + obs_count * FRAME_STEP_NS
    gaps = []
    if gap_frac > 0:
        gaps.append(
            {
                "kind": "AI_GAP",
                "start_ms": 400000,
                "duration_ms": int(obs_count * FRAME_STEP_NS / 1_000_000 * gap_frac),
                "reason": "edge_model_restarting",
            }
        )
    return {
        "schema_version": 1,
        "summary_id": _uid(tag, flight_id, "summary", name),
        "flight_id": flight_id,
        "edge_boot_id": boot_id,
        "session_id": session_id,
        "started_at_edge_monotonic_ns": start,
        "ended_at_edge_monotonic_ns": end,
        "ai_uptime_ratio": ai_ratio,
        "covered_frames": obs_count * 450,
        "skipped_frames": int(obs_count * 450 * (1 - ai_ratio)),
        "gaps": gaps,
        "min_verified_person_equipment_distance_m": None,
        "person_count_max": 3,
        "equipment_count_max": 1,
        "quality_reasons": ["UNVERIFIED_FRAME_CAPTURE_TIME"],
        "created_at_edge_monotonic_ns": end + 50_000,
    }


def _boot_id(tag: str, flight_id: str, index: int) -> str:
    return _uid(tag, flight_id, "boot", str(index))


def _session_id(tag: str, flight_id: str, boot_id: str) -> str:
    # Must match RCSimulator's synthesis: uuid5("session"|flight|boot).
    return _uid("session", flight_id, boot_id)


def session_id_for(flight_id: str, boot_id: str) -> str:
    """RC-side session derivation (tag-independent given flight+boot)."""
    return _uid("session", flight_id, boot_id)


