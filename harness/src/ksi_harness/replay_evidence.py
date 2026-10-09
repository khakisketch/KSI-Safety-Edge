"""R5 bridge: a REAL replay_run NDJSON recording -> M1 flight message shapes.

The C++ replay driver writes one NDJSON record per tick (detections, tracks,
time-match grades, spatial observations, SHADOW rule events). This module
maps those records into the SAME message shapes the M1 harness Edge replay
emits (OBSERVATION / DETECTION_EVENT / FLIGHT_SUMMARY), so the RC emulator
can store them, upload them to Core, and the server can serve them back.

This is TEST-HARNESS code that stands in for the deferred Edge MOP emitter:
it is not product logic, and the real MSDK flight path (RC app + aircraft)
stays explicitly unverified. It never fabricates geographic positions:
ENU-space observations upload with position_wgs84 = null and a
UNVERIFIED/WITHHELD geometry_status (GEO_ESTIMATE only); the real evidence
numbers (distances, episode ids, grades) ride in the event fields via
initial_model_output.
"""

from __future__ import annotations

import json
import pathlib

from .fixtures import _session_id, _uid

# COCO ids observed in the corpus recordings (bridge-side labels only; the
# product keeps raw COCO ids with no ontology mapping).
CLASS_NAMES = {0: "person", 2: "car", 7: "truck"}

EVENT_TYPES = {
    "prox-demo": "PROXIMITY_CANDIDATE",
    "zone-demo-rule": "ZONE_CANDIDATE",
}


def class_name(class_id: int) -> str:
    return CLASS_NAMES.get(class_id, f"coco-{class_id}")


def load_replay_run(path) -> tuple[list, dict]:
    """Reads a replay_run NDJSON file -> (ticks, stat)."""
    ticks: list = []
    stat: dict | None = None
    text = pathlib.Path(path).read_text(encoding="utf-8")
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        record = json.loads(line)
        kind = record.get("type")
        if kind == "tick":
            ticks.append(record)
        elif kind == "stat":
            stat = record
    if stat is None:
        raise ValueError(f"no stat line in replay run: {path}")
    if not ticks:
        raise ValueError(f"no tick lines in replay run: {path}")
    return ticks, stat


def _sync_quality(tick: dict) -> str:
    att = tick["att"]["q"]
    pos = tick["pos"]["q"]
    if att == "SOURCE_VERIFIED" and pos == "SOURCE_VERIFIED":
        return "VALID"
    if att == "NONE" and pos == "NONE":
        return "UNKNOWN"
    return "DEGRADED"


def _geometry_status(tick: dict) -> str:
    if tick["spatial_status"] == "ESTIMATED":
        return "UNVERIFIED"  # GEO_ESTIMATE only — never server-VERIFIED
    return "WITHHELD"


def _model_digest(tick: dict) -> str:
    digest = str(tick.get("model_digest", ""))
    return digest.removeprefix("sha256:")


def _observation(tag: str, flight_id: str, boot_id: str, mission_rev: str,
                 tick: dict) -> dict:
    seq = int(tick["i"])
    ts = int(tick["recv_mono_ns"])
    quality = _sync_quality(tick)
    observed_tracks = {
        tuple(round(float(v), 6) for v in track["box"]): track["id"]
        for track in tick["tracks"]
        if track.get("prov") == "OBSERVED"
    }
    detections = []
    for index, det in enumerate(tick["det"]):
        box = [float(v) for v in det["box"]]
        track_id = observed_tracks.get(tuple(round(v, 6) for v in box))
        detections.append({
            "detection_id": _uid(tag, flight_id, "r5-det", str(seq), str(index)),
            "class_id": class_name(int(det["c"])),
            "score": float(det["s"]),
            "bbox_xyxy_px": box,
            "track_id": f"trk-{track_id}" if track_id is not None else None,
            "position_wgs84": None,
        })
    att_gap_ms = abs(int(tick["att"]["gap_ns"])) / 1e6
    pos_gap_ms = abs(int(tick["pos"]["gap_ns"])) / 1e6
    return {
        "schema_version": 1,
        "observation_id": _uid(tag, flight_id, "r5-obs", str(seq)),
        "flight_id": flight_id,
        "mission_revision_id": mission_rev,
        "edge_boot_id": boot_id,
        "source_kind": "PSDK_EDGE",
        "edge_frame_seq": seq,
        "edge_frame_id": str(tick["frame_id"]),
        "lens_id": "wide",
        "frame_received_monotonic_ns": ts,
        "model_digest": _model_digest(tick),
        "sync_quality": quality,
        "geometry_status": _geometry_status(tick),
        "telemetry_refs": [
            {
                "topic": "aircraft_attitude",
                "source_timestamp_ns": None,
                "edge_receive_monotonic_ns": ts,
                "sample_age_ms": att_gap_ms,
                "timestamp_basis": "PSDK_LOCAL_RECEIVE",
                "quality": {
                    "SOURCE_VERIFIED": "VALID",
                    "RECEIVE_NEAREST": "DEGRADED",
                    "NONE": "UNKNOWN",
                }.get(tick["att"]["q"], "UNKNOWN"),
            },
            {
                "topic": "aircraft_position",
                "source_timestamp_ns": None,
                "edge_receive_monotonic_ns": ts,
                "sample_age_ms": pos_gap_ms,
                "timestamp_basis": "PSDK_LOCAL_RECEIVE",
                "quality": {
                    "SOURCE_VERIFIED": "VALID",
                    "RECEIVE_NEAREST": "DEGRADED",
                    "NONE": "UNKNOWN",
                }.get(tick["pos"]["q"], "UNKNOWN"),
            },
        ],
        "detections": detections,
        "quality_reasons": [] if quality == "VALID" else ["SYNTHETIC_REPLAY_GRADES"],
        "zone_revision_ids": [],
    }


def _event(tag: str, flight_id: str, world: dict, observation_id: str,
           output: dict, event: dict, index: int, artifact_ids: list) -> dict:
    return {
        "schema_version": 1,
        "event_id": _uid(tag, flight_id, "r5-event", str(index)),
        "flight_id": flight_id,
        "observation_id": observation_id,
        "episode_id": _uid(tag, flight_id, "r5-episode", output["rule"],
                           str(event["episode"])),
        "event_type": EVENT_TYPES.get(output["rule"], "RISK_EVENT"),
        "mode": "SHADOW",
        "detected_at_edge_monotonic_ns": int(event["recv_ns"]),
        "zone_revision_id": None,
        "policy_revision_id": world["policy"]["revision_id"],
        "evidence_artifact_ids": list(artifact_ids[:1]),
        "initial_model_output": {
            "rule_id": output["rule"],
            "episode": int(event["episode"]),
            "distance_m": float(event["lower_m"]),
            "geometry": "GEO_ESTIMATE",
            "synthetic_replay_geometry": True,
        },
        "observation_quality": "VALID",
        "mission_snapshot_hash": world["snapshot"]["content_sha256"],
    }


def _summary(tag: str, flight_id: str, boot_id: str, session_id: str,
             ticks: list, stat: dict) -> dict:
    first = int(ticks[0]["recv_mono_ns"])
    last = int(ticks[-1]["recv_mono_ns"])
    frames = int(stat["frames"])
    ok_ticks = int(stat["ok_ticks"])
    person_max = max(
        (sum(1 for det in tick["det"] if int(det["c"]) == 0) for tick in ticks),
        default=0)
    equipment_max = max(
        (sum(1 for det in tick["det"] if int(det["c"]) in (2, 7))
         for tick in ticks),
        default=0)
    return {
        "schema_version": 1,
        "summary_id": _uid(tag, flight_id, "r5-summary", "boot0"),
        "flight_id": flight_id,
        "edge_boot_id": boot_id,
        "session_id": session_id,
        "started_at_edge_monotonic_ns": first,
        "ended_at_edge_monotonic_ns": last,
        "ai_uptime_ratio": round(ok_ticks / frames, 3),
        "covered_frames": frames,
        "skipped_frames": frames - ok_ticks,
        "gaps": [],
        "min_verified_person_equipment_distance_m": None,
        "person_count_max": person_max,
        "equipment_count_max": equipment_max,
        "quality_reasons": [
            "SYNTHETIC_REPLAY_INPUT",
            "UNVERIFIED_FRAME_CAPTURE_TIME",
        ],
        "created_at_edge_monotonic_ns": last + 50_000,
    }


def flight_from_replay(tag: str, replay_path, base_flight: dict) -> dict:
    """Replaces the base flight's boot observations/events/summary with
    records derived from the real replay_run recording at `replay_path`.
    Artifacts and world/bundle scaffolding stay as the base fixture's."""
    ticks, stat = load_replay_run(replay_path)
    flight = base_flight
    boot = flight["boots"][0]
    world = flight["world"]
    mission_rev = world["snapshot"]["mission_revision_id"]
    observations = [
        _observation(tag, flight["flight_id"], boot["boot_id"], mission_rev,
                     tick)
        for tick in ticks
    ]
    artifact_ids = [artifact["artifact_id"] for artifact in boot["artifacts"]]
    events = []
    index = 0
    for tick in ticks:
        observation_id = observations[int(tick["i"])]["observation_id"]
        outputs = list(tick.get("prox", [])) + list(tick.get("zone", []))
        for output in outputs:
            for event in output.get("events", []):
                if event.get("kind") != "CREATED":
                    continue
                events.append(_event(tag, flight["flight_id"], world,
                                     observation_id, output, event, index,
                                     artifact_ids))
                index += 1
    boot["observations"] = observations
    boot["events"] = events
    boot["summary"] = _summary(
        tag, flight["flight_id"], boot["boot_id"],
        _session_id(tag, flight["flight_id"], boot["boot_id"]), ticks, stat)
    return flight
