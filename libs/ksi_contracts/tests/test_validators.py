"""Unit tests for domain rules: happy paths, violations, digest policy."""

import copy
import json
from pathlib import Path

import pytest

from ksi_contracts import (
    ContractError,
    set_digest,
    validate_all,
    validate_domain,
    validate_schema,
)

EXAMPLES = Path(__file__).resolve().parents[3] / "contracts" / "examples"


def load_legacy(stem: str) -> dict:
    return json.loads((EXAMPLES / stem).read_text(encoding="utf-8"))


LEGACY = {
    "flight-upload-manifest.v1": "flight-upload-manifest.example.json",
    "mission-snapshot.v1": "mission-snapshot.example.json",
    "observation.v1": "observation.example.json",
    "operator-annotation.v1": "operator-annotation.example.json",
    "review-finding.v1": "review-finding.example.json",
    "risk-policy.v1": "risk-policy.v1.example.json",
    "spatial-relation.v1": "spatial-relation.v1.example.json",
    "spatial-summary.v1": "spatial-summary.v1.example.json",
}


def test_legacy_examples_pass_lax():
    for name, stem in LEGACY.items():
        assert validate_domain(name, load_legacy(stem), check_digests=False) == []


def test_strict_rejects_placeholder_digests():
    """Legacy (a)-style placeholders fail strict mode on DIGEST_MISMATCH."""
    for name, stem in [
        ("flight-upload-manifest.v1", LEGACY["flight-upload-manifest.v1"]),
        ("mission-snapshot.v1", LEGACY["mission-snapshot.v1"]),
        ("risk-policy.v1", LEGACY["risk-policy.v1"]),
    ]:
        errors = validate_domain(name, load_legacy(stem), check_digests=True)
        assert any(e.startswith("DIGEST_MISMATCH") for e in errors), (name, errors)


def test_strict_accepts_computed_digests():
    for name, stem in [
        ("flight-upload-manifest.v1", LEGACY["flight-upload-manifest.v1"]),
        ("mission-snapshot.v1", LEGACY["mission-snapshot.v1"]),
        ("risk-policy.v1", LEGACY["risk-policy.v1"]),
    ]:
        obj = load_legacy(stem)
        field = {"flight-upload-manifest.v1": "manifest_sha256"}.get(
            name, "content_sha256"
        )
        stamped = set_digest(obj, field)
        assert validate_domain(name, stamped, check_digests=True) == []


def test_unknown_schema_raises_clear_error():
    with pytest.raises(KeyError, match="unknown schema"):
        validate_domain("nope.v9", {})
    with pytest.raises(KeyError, match="unknown schema"):
        validate_schema("nope.v9", {})


def test_schema_errors_prefixed_and_combined_in_validate_all():
    with pytest.raises(ContractError) as exc:
        validate_all("observation.v1", {"schema_version": 999})
    assert exc.value.errors
    assert all(e.startswith("SCHEMA:") for e in exc.value.errors)


def test_application_state_both_directions():
    base = {
        "applied": True,
        "rejection_reasons": [],
    }
    assert validate_domain("mission-application.v1", base) == []
    bad = dict(base, rejection_reasons=["unsupported-zone"])
    assert any(
        e.startswith("APPLICATION_STATE_INCONSISTENT")
        for e in validate_domain("mission-application.v1", bad)
    )
    bad2 = {"applied": False, "rejection_reasons": []}
    assert any(
        e.startswith("APPLICATION_STATE_INCONSISTENT")
        for e in validate_domain("mission-application.v1", bad2)
    )
    good2 = {"applied": False, "rejection_reasons": ["hash-mismatch"]}
    assert validate_domain("mission-application.v1", good2) == []


def test_relation_interval_rules():
    base = {
        "distance_m": 4.2,
        "distance_interval_m": [3.0, 5.0],
        "metric_status": "SHADOW_ESTIMATE",
        "frame_ref": {"time_basis": "PSDK_LOCAL_RECEIVE"},
    }
    assert validate_domain("spatial-relation.v1", base) == []
    reversed_iv = dict(base, distance_interval_m=[5.0, 3.0])
    codes = validate_domain("spatial-relation.v1", reversed_iv)
    assert any(c.startswith("INTERVAL_ORDER") for c in codes)
    outside = dict(base, distance_m=9.0)
    assert any(
        c.startswith("INTERVAL_CONTAINS_DISTANCE")
        for c in validate_domain("spatial-relation.v1", outside)
    )


def test_verified_requires_capture_time():
    good = {
        "metric_status": "VERIFIED",
        "frame_ref": {"time_basis": "CAMERA_CAPTURE_VERIFIED"},
    }
    assert validate_domain("spatial-relation.v1", good) == []
    bad = {
        "metric_status": "VERIFIED",
        "frame_ref": {"time_basis": "UNVERIFIED"},
    }
    assert any(
        e.startswith("VERIFIED_REQUIRES_CAPTURE_TIME")
        for e in validate_domain("spatial-relation.v1", bad)
    )


def test_period_and_pair_rules():
    good = {
        "period_start_edge_monotonic_ns": 1,
        "period_end_edge_monotonic_ns": 2,
        "metric_pair_count": 0,
        "min_verified_person_equipment_distance_m": None,
    }
    assert validate_domain("spatial-summary.v1", good) == []
    bad_period = dict(good, period_end_edge_monotonic_ns=0)
    assert any(
        e.startswith("PERIOD_ORDER")
        for e in validate_domain("spatial-summary.v1", bad_period)
    )
    bad_pair = dict(good, min_verified_person_equipment_distance_m=1.0)
    assert any(
        e.startswith("PAIR_DISTANCE_INCONSISTENT")
        for e in validate_domain("spatial-summary.v1", bad_pair)
    )


def test_polygon_ring_and_zone_duplicates():
    def zone(ring):
        return {
            "zone_id": "z1",
            "geometry_wgs84": {"type": "Polygon", "coordinates": [ring]},
        }

    closed = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.0, 0.0]]
    assert validate_domain("mission-snapshot.v1", {"zones": [zone(closed)]}) == []
    open_ring = [[0.0, 0.0], [1.0, 0.0], [1.0, 1.0], [0.5, 0.5]]
    assert any(
        e.startswith("POLYGON_RING_OPEN")
        for e in validate_domain("mission-snapshot.v1", {"zones": [zone(open_ring)]})
    )
    assert any(
        e.startswith("POLYGON_RING_OPEN")
        for e in validate_domain(
            "mission-snapshot.v1", {"zones": [zone(closed[:3])]}
        )
    )
    dup = {"zones": [zone(closed), dict(zone(closed))]}
    assert any(
        e.startswith("DUPLICATE_ID")
        for e in validate_domain("mission-snapshot.v1", dup)
    )


def test_manifest_seq_and_artifact_uniqueness():
    base = {
        "edge_last_seq": 5,
        "rc_received_last_seq": 5,
        "artifacts": [
            {
                "artifact_id": "a1",
                "source_kind": "RC_PROXY_SEGMENT",
                "segment_index": 0,
            }
        ],
    }
    assert validate_domain("flight-upload-manifest.v1", base) == []
    assert any(
        e.startswith("SEQ_ORDER")
        for e in validate_domain(
            "flight-upload-manifest.v1", dict(base, rc_received_last_seq=6)
        )
    )
    dup_art = copy.deepcopy(base)
    dup_art["artifacts"].append(dict(dup_art["artifacts"][0]))
    codes = validate_domain("flight-upload-manifest.v1", dup_art)
    assert sum(1 for c in codes if c.startswith("DUPLICATE_ID")) >= 2  # id + segment


def test_revoke_target_missing():
    good = {"annotation_type": "NEEDS_REVIEW", "supersedes_annotation_id": None}
    assert validate_domain("operator-annotation.v1", good) == []
    bad = {"annotation_type": "REVOKE_PREVIOUS", "supersedes_annotation_id": None}
    assert validate_domain("operator-annotation.v1", bad) == [
        "REVOKE_TARGET_MISSING: REVOKE_PREVIOUS requires supersedes_annotation_id"
    ]


def test_proximity_threshold_and_rule_duplicates():
    def rule(**kw):
        base = {
            "rule_id": "r1",
            "kind": "PERSON_EQUIPMENT_PROXIMITY",
            "mode": "SHADOW",
            "threshold_m": 4.0,
        }
        base.update(kw)
        return base

    assert validate_domain("risk-policy.v1", {"rules": [rule()]}) == []
    off = {"rules": [rule(mode="OFF", threshold_m=None)]}
    assert validate_domain("risk-policy.v1", off) == []
    missing = {"rules": [rule(threshold_m=None)]}
    assert any(
        e.startswith("PROXIMITY_THRESHOLD_REQUIRED")
        for e in validate_domain("risk-policy.v1", missing)
    )
    dup = {"rules": [rule(), rule()]}
    assert any(
        e.startswith("DUPLICATE_ID")
        for e in validate_domain("risk-policy.v1", dup)
    )


def test_active_event_requires_policy():
    good = {"mode": "SHADOW", "policy_revision_id": None}
    assert validate_domain("detection-event.v1", good) == []
    bad = {"mode": "ACTIVE", "policy_revision_id": None}
    assert any(
        e.startswith("ACTIVE_REQUIRES_POLICY")
        for e in validate_domain("detection-event.v1", bad)
    )


def test_session_time_rules():
    good = {
        "started_at_edge_monotonic_ns": 10,
        "ended_at_edge_monotonic_ns": 20,
        "end_reason": "COMPLETED",
    }
    assert validate_domain("flight-session.v1", good) == []
    running = {
        "started_at_edge_monotonic_ns": 10,
        "ended_at_edge_monotonic_ns": None,
        "end_reason": None,
    }
    assert validate_domain("flight-session.v1", running) == []
    bad = dict(good, ended_at_edge_monotonic_ns=5)
    assert any(
        e.startswith("SESSION_TIME_ORDER")
        for e in validate_domain("flight-session.v1", bad)
    )
    dangling = dict(good, end_reason=None)
    assert any(
        e.startswith("SESSION_TIME_ORDER")
        for e in validate_domain("flight-session.v1", dangling)
    )


def test_bbox_order_and_detection_duplicates():
    def det(bbox, did="d1"):
        return {"detection_id": did, "bbox_xyxy_px": bbox}

    good = {"detections": [det([1, 2, 3, 4])]}
    assert validate_domain("observation.v1", good) == []
    bad = {"detections": [det([3, 2, 1, 4])]}
    assert any(
        e.startswith("BBOX_ORDER") for e in validate_domain("observation.v1", bad)
    )
    dup = {"detections": [det([1, 2, 3, 4]), det([1, 2, 3, 4])]}
    assert any(
        e.startswith("DUPLICATE_ID")
        for e in validate_domain("observation.v1", dup)
    )


def test_bundle_site_mismatch():
    good = {
        "site_id": "s1",
        "mission_snapshot": {"site_id": "s1"},
        "risk_policy": {"site_id": "s1"},
    }
    assert validate_domain("mission-bundle.v1", good) == []
    bad = {
        "site_id": "s1",
        "mission_snapshot": {"site_id": "s2"},
        "risk_policy": {"site_id": "s1"},
    }
    assert validate_domain("mission-bundle.v1", bad) == [
        e
        for e in validate_domain("mission-bundle.v1", bad)
        if e.startswith("BUNDLE_SITE_MISMATCH")
    ]
    assert validate_domain("mission-bundle.v1", bad)


def test_digest_skipped_when_disabled():
    obj = load_legacy("mission-snapshot.example.json")
    assert validate_domain("mission-snapshot.v1", obj, check_digests=False) == []
