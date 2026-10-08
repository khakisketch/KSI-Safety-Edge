"""Flight assembly: per-boot observations/events/artifacts/summaries.

Element builders (zones, snapshots, policies, bundles, observations,
events, artifacts, summaries) live in :mod:`ksi_harness.fixtures`.
"""

from __future__ import annotations

from .fixtures import (
    _artifacts,
    _boot_id,
    _bundle,
    _event,
    _observation,
    _policy,
    _session_id,
    _summary,
    _uid,
    world,
)

def _boot_data(
    tag: str,
    w: dict,
    flight_id: str,
    index: int,
    n_obs: int,
    event_plan: list,
    ai_ratio: float,
    gap_frac: float,
    policy_rev: str | None = None,
    snapshot_hash: str | None = None,
    with_artifacts: bool = True,
) -> dict:
    boot_id = _boot_id(tag, flight_id, index)
    session_id = _session_id(tag, flight_id, boot_id)
    mission_rev = w["snapshot"]["mission_revision_id"]
    zone_revs = [z["revision_id"] for z in w["zones"]]
    quals = ["VALID", "DEGRADED", "VALID", "DEGRADED", "VALID", "UNKNOWN"]
    geoms = ["VERIFIED", "VERIFIED", "WITHHELD", "UNVERIFIED", "VERIFIED", "VERIFIED"]
    observations = [
        _observation(
            tag, flight_id, boot_id, mission_rev, i, zone_revs,
            quals[i % len(quals)], geoms[i % len(geoms)], i,
        )
        for i in range(n_obs)
    ]
    snap_hash = snapshot_hash or w["snapshot"]["content_sha256"]
    pol_rev = policy_rev or w["policy"]["revision_id"]
    artifacts, _blobs = _artifacts(tag, flight_id, f"boot{index}") if with_artifacts else ([], {})
    art_ids = [a["artifact_id"] for a in artifacts[:2]]
    events = []
    for k, (obs_idx, etype) in enumerate(event_plan):
        obs = observations[obs_idx]
        zone_rev = None if etype == "DETECTED_ONLY" else zone_revs[k % len(zone_revs)]
        events.append(
            _event(tag, flight_id, obs, index * 100 + k, etype, snap_hash, pol_rev, zone_rev, art_ids[:1])
        )
    summary = _summary(tag, flight_id, boot_id, session_id, f"boot{index}", ai_ratio, gap_frac, n_obs)
    return {
        "boot_index": index,
        "boot_id": boot_id,
        "session_id": session_id,
        "observations": observations,
        "events": events,
        "artifacts": artifacts,
        "summary": summary,
    }


def _flight_envelope(tag: str, w: dict, flight_name: str, boots: list, extra: dict | None = None) -> dict:
    flight_id = _uid(tag, "flight", flight_name)
    out = {
        "tag": tag,
        "flight_id": flight_id,
        "site_id": w["site"]["site_id"],
        "mission_id": w["snapshot"]["mission_id"],
        "mission_revision_id": w["snapshot"]["mission_revision_id"],
        "bundle": w["bundle"],
        "world": w,
        "boots": boots,
    }
    if extra:
        out.update(extra)
    return out


def flight_a(tag: str = "default") -> dict:
    """60 obs, 4 events (3 SPATIAL_CANDIDATE + 1 DETECTED_ONLY), 3 artifacts."""
    w = world(tag)
    flight_id = _uid(tag, "flight", "a")
    plan = [(5, "SPATIAL_CANDIDATE"), (20, "SPATIAL_CANDIDATE"), (41, "SPATIAL_CANDIDATE"), (55, "DETECTED_ONLY")]
    boots = [_boot_data(tag, w, flight_id, 0, 60, plan, 0.96, 0.04)]
    return _flight_envelope(tag, w, "a", boots)


def flight_b(tag: str = "default") -> dict:
    """0 events, ai_uptime 0.6 + 40% AI_GAP, 1 artifact."""
    w = world(tag)
    flight_id = _uid(tag, "flight", "b")
    boot = _boot_data(tag, w, flight_id, 0, 20, [], 0.6, 0.4)
    boot["artifacts"] = boot["artifacts"][:1]
    return _flight_envelope(tag, w, "b", [boot])


def flight_c(tag: str = "default") -> dict:
    """Mid-flight bundle v2 + second boot re-applying v2.

    bundle v2 = clone of v1 with a new policy revision_id + recomputed
    digests. Boot 0: 35 obs; first 2 events carry v1 refs (frame seq <30),
    later events carry v2 refs. Boot 1: 15 obs, v2 refs throughout.
    """
    w = world(tag)
    flight_id = _uid(tag, "flight", "c")
    site_id = w["site"]["site_id"]
    policy_v2 = _policy(tag, site_id, "rev2", [z["revision_id"] for z in w["zones"]])
    bundle_v2 = _bundle(tag, site_id, w["snapshot"], policy_v2, "b2")
    v1_rev = w["policy"]["revision_id"]
    v2_rev = policy_v2["revision_id"]
    snap_hash = w["snapshot"]["content_sha256"]
    mission_rev = w["snapshot"]["mission_revision_id"]
    zone_revs = [z["revision_id"] for z in w["zones"]]

    boot0_id = _boot_id(tag, flight_id, 0)
    sess0 = _session_id(tag, flight_id, boot0_id)
    quals = ["VALID", "DEGRADED", "VALID", "DEGRADED"]
    observations0 = [
        _observation(tag, flight_id, boot0_id, mission_rev, i, zone_revs, quals[i % 4], "VERIFIED", i)
        for i in range(35)
    ]
    events0 = [
        _event(tag, flight_id, observations0[4], 0, "SPATIAL_CANDIDATE", snap_hash, v1_rev, zone_revs[0], []),
        _event(tag, flight_id, observations0[18], 1, "SPATIAL_CANDIDATE", snap_hash, v1_rev, zone_revs[1], []),
        _event(tag, flight_id, observations0[31], 2, "SPATIAL_CANDIDATE", snap_hash, v2_rev, zone_revs[0], []),
        _event(tag, flight_id, observations0[33], 3, "DETECTED_ONLY", snap_hash, v2_rev, None, []),
    ]
    arts0, _b0 = _artifacts(tag, flight_id, "boot0")
    arts0 = arts0[:1]
    summary0 = _summary(tag, flight_id, boot0_id, sess0, "boot0", 0.9, 0.1, 35)
    boot0 = {
        "boot_index": 0, "boot_id": boot0_id, "session_id": sess0,
        "observations": observations0, "events": events0,
        "artifacts": arts0, "summary": summary0,
        "switch_at": 2,  # events[0:2] effective under v1, rest under v2
    }
    boot1 = _boot_data(tag, w, flight_id, 1, 15, [(3, "SPATIAL_CANDIDATE"), (11, "DETECTED_ONLY")],
                       0.95, 0.05, policy_rev=v2_rev)
    boot1["artifacts"] = boot1["artifacts"][:1]
    return _flight_envelope(tag, w, "c", [boot0, boot1],
                            {"bundle_v2": bundle_v2, "policy_v2": policy_v2})


def clone_bundle_v2(w: dict, tag: str) -> dict:
    """Standalone v2 clone helper (same derivation as flight_c)."""
    site_id = w["site"]["site_id"]
    policy_v2 = _policy(tag, site_id, "rev2", [z["revision_id"] for z in w["zones"]])
    return _bundle(tag, site_id, w["snapshot"], policy_v2, "b2")
