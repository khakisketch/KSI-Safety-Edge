"""R5: RC-emulator E2E from REAL replay_run recordings + withhold honesty.

The recordings under docs/verification/evidence/2026-10-09-r5/ were produced
by the C++ replay driver on the Jetson (fp16 engine, synthetic policy
geometry). These tests map them into the M1 message shapes
(ksi_harness.replay_evidence) and run the existing apply -> store -> upload
-> query flow, so the REAL pipeline's episodes and evidence are served back
by Core.

This is the RC-EMULATOR E2E. The real MSDK flight path (RC app + aircraft)
remains explicitly unverified and is not exercised here.
"""

from __future__ import annotations

import pathlib

from ksi_harness import flight_a
from ksi_harness.replay_evidence import flight_from_replay, load_replay_run

from harness_helpers import exchange, fresh_edge, fresh_env

RECORDINGS = (
    pathlib.Path(__file__).resolve().parents[2]
    / "docs"
    / "verification"
    / "evidence"
    / "2026-10-09-r5"
)


def _prepare(tmp_path, tag, recording_name):
    flight = flight_from_replay(tag, RECORDINGS / recording_name,
                                flight_a(tag))
    env = fresh_env(tmp_path)
    edge = fresh_edge(env, flight)
    env.rc.prepare_and_apply(flight["flight_id"], flight["bundle"], edge,
                             env.transport, max_rounds=5)
    stats = exchange(env, edge, flight["boots"][0])
    assert stats["rc"]["errors"] == 0
    return env, edge, flight


def test_r5_replay_estimated_end_to_end(tmp_path, client):
    replay = RECORDINGS / "replay_estimated.ndjson"
    ticks, stat = load_replay_run(replay)
    env, _edge, flight = _prepare(tmp_path, "r5est", replay.name)
    boot = flight["boots"][0]
    assert len(boot["observations"]) == stat["frames"] == len(ticks)
    assert len(boot["events"]) == stat["episodes_created"] > 0
    out = env.rc.upload_flight(client, flight)
    assert out["upload_state"] == "COMPLETE"

    server_events = client.get_events(flight["flight_id"])["items"]
    assert len(server_events) == len(boot["events"])
    assert all(event["mode"] == "SHADOW" for event in server_events)
    assert all(event["episode_id"] is not None for event in server_events)
    distances = [
        event["initial_model_output"]["distance_m"] for event in server_events
    ]
    assert all(distance >= 0 for distance in distances)
    assert max(distances) > 0
    snapshot_hash = flight["world"]["snapshot"]["content_sha256"]
    assert all(event["mission_snapshot_hash"] == snapshot_hash
               for event in server_events)

    coverage = client.get_coverage(flight["flight_id"])
    assert coverage["data_completeness"] == "COMPLETE"
    assert coverage["summaries"][0]["ai_uptime_ratio"] == round(
        stat["ok_ticks"] / stat["frames"], 3)

    stored = env.store.observations_for(flight["flight_id"])
    assert len(stored) == stat["frames"]
    assert {observation["geometry_status"] for observation in stored} == {
        "UNVERIFIED"
    }  # GEO_ESTIMATE only — never pretend VERIFIED
    assert all(
        detection["position_wgs84"] is None
        for observation in stored
        for detection in observation["detections"]
    )


def test_r5_replay_withheld_stays_honest(tmp_path, client):
    replay = RECORDINGS / "replay_withheld.ndjson"
    ticks, stat = load_replay_run(replay)
    assert stat["episodes_created"] == 0
    env, _edge, flight = _prepare(tmp_path, "r5with", replay.name)
    boot = flight["boots"][0]
    assert len(boot["observations"]) == stat["frames"] == len(ticks)
    assert boot["events"] == []

    out = env.rc.upload_flight(client, flight)
    assert out["upload_state"] == "COMPLETE"
    assert client.get_events(flight["flight_id"])["items"] == []
    stored = env.store.observations_for(flight["flight_id"])
    assert stored
    assert all(observation["geometry_status"] == "WITHHELD"
               for observation in stored)
    coverage = client.get_coverage(flight["flight_id"])
    assert coverage["data_completeness"] == "COMPLETE"
