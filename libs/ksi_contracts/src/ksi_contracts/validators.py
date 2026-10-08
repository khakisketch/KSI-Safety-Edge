"""Schema + domain validation for KSI Safety Edge contracts.

Every error string is prefixed with a stable rule code (``CODE: detail``)
so tests and callers can assert on specific codes.
"""

from __future__ import annotations

from typing import Any

from . import schema_store
from .canonical import verify_digest

__all__ = ["ContractError", "validate_schema", "validate_domain", "validate_all"]

_DIGEST_FIELDS = {
    "mission-bundle.v1": ("bundle_sha256",),
    "flight-upload-manifest.v1": ("manifest_sha256",),
    "mission-snapshot.v1": ("content_sha256",),
    "risk-policy.v1": ("content_sha256",),
}


class ContractError(Exception):
    """Raised when contract validation fails; carries all error strings."""

    def __init__(self, errors: list[str]):
        self.errors = list(errors)
        super().__init__("; ".join(self.errors) if self.errors else "contract error")


def _check_name(name: str) -> None:
    if name not in schema_store.SCHEMA_NAMES:
        raise KeyError(
            f"unknown schema {name!r}; known: {', '.join(schema_store.SCHEMA_NAMES)}"
        )


def validate_schema(name: str, obj: Any) -> None:
    """Validate ``obj`` against the JSON Schema ``name``.

    Raises:
        KeyError: for unknown schema names.
        ContractError: with all schema violations (``SCHEMA:``-prefixed).
    """
    _check_name(name)
    validator = schema_store.validator_for(name)
    errors = []
    for error in sorted(validator.iter_errors(obj), key=lambda e: list(e.absolute_path)):
        pointer = "/" + "/".join(str(p) for p in error.absolute_path)
        errors.append(f"SCHEMA: {pointer or '/'}: {error.message}")
    if errors:
        raise ContractError(errors)


def validate_domain(name: str, obj: Any, check_digests: bool = True) -> list[str]:
    """Return domain-rule violations (``CODE: detail``) for a loaded object.

    Never raises on malformed input — missing keys are skipped here
    (schema validation owns shape). Unknown schema names raise KeyError.
    """
    _check_name(name)
    if not isinstance(obj, dict):
        return [f"DOMAIN_TYPE: expected object for {name}"]
    errors: list[str] = []
    if check_digests:
        for field in _DIGEST_FIELDS.get(name, ()):
            claimed = obj.get(field)
            if isinstance(claimed, str) and not verify_digest(obj, field):
                errors.append(
                    f"DIGEST_MISMATCH: {field} does not match recomputed digest"
                )
    dispatch = {
        "mission-bundle.v1": _domain_bundle,
        "mission-application.v1": _domain_application,
        "flight-session.v1": _domain_session,
        "detection-event.v1": _domain_event,
        "flight-summary.v1": _domain_flight_summary,
        "spatial-relation.v1": _domain_relation,
        "spatial-summary.v1": _domain_spatial_summary,
        "risk-policy.v1": _domain_policy,
        "flight-upload-manifest.v1": _domain_manifest,
        "mission-snapshot.v1": _domain_snapshot,
        "observation.v1": _domain_observation,
        "operator-annotation.v1": _domain_annotation,
    }
    fn = dispatch.get(name)
    if fn is not None:
        errors.extend(fn(obj))
    return errors


def validate_all(name: str, obj: Any, check_digests: bool = True) -> None:
    """Schema + domain validation; raises ContractError with ALL errors."""
    _check_name(name)
    errors: list[str] = []
    try:
        validate_schema(name, obj)
    except ContractError as exc:
        errors.extend(exc.errors)
    errors.extend(validate_domain(name, obj, check_digests=check_digests))
    if errors:
        raise ContractError(errors)


# --- per-contract domain rules ---------------------------------------------


def _domain_bundle(obj: dict) -> list[str]:
    errors: list[str] = []
    snap = obj.get("mission_snapshot")
    policy = obj.get("risk_policy")
    site_ids = {obj.get("site_id")}
    if isinstance(snap, dict):
        site_ids.add(snap.get("site_id"))
    if isinstance(policy, dict):
        site_ids.add(policy.get("site_id"))
    site_ids.discard(None)
    if len(site_ids) > 1:
        errors.append(
            "BUNDLE_SITE_MISMATCH: bundle/site/snapshot/policy site_id differ: "
            + ",".join(sorted(str(s) for s in site_ids))
        )
    return errors


def _domain_application(obj: dict) -> list[str]:
    errors: list[str] = []
    applied = obj.get("applied")
    reasons = obj.get("rejection_reasons")
    if isinstance(applied, bool) and isinstance(reasons, list):
        if applied and reasons:
            errors.append(
                "APPLICATION_STATE_INCONSISTENT: applied==True with rejection_reasons"
            )
        if not applied and not reasons:
            errors.append(
                "APPLICATION_STATE_INCONSISTENT: applied==False with empty rejection_reasons"
            )
    return errors


def _domain_session(obj: dict) -> list[str]:
    errors: list[str] = []
    started = obj.get("started_at_edge_monotonic_ns")
    ended = obj.get("ended_at_edge_monotonic_ns")
    reason = obj.get("end_reason")
    if isinstance(started, int) and isinstance(ended, int) and ended < started:
        errors.append(
            f"SESSION_TIME_ORDER: ended ({ended}) < started ({started})"
        )
    if (ended is None) != (reason is None):
        errors.append(
            "SESSION_TIME_ORDER: end_reason present iff ended_at_edge_monotonic_ns present"
        )
    return errors


def _domain_event(obj: dict) -> list[str]:
    errors: list[str] = []
    if obj.get("mode") == "ACTIVE" and obj.get("policy_revision_id") is None:
        errors.append(
            "ACTIVE_REQUIRES_POLICY: mode ACTIVE requires policy_revision_id"
        )
    return errors


def _domain_flight_summary(obj: dict) -> list[str]:
    errors: list[str] = []
    started = obj.get("started_at_edge_monotonic_ns")
    ended = obj.get("ended_at_edge_monotonic_ns")
    if isinstance(started, int) and isinstance(ended, int) and ended < started:
        errors.append(f"PERIOD_ORDER: ended ({ended}) < started ({started})")
    errors.extend(_pair_consistency(obj))
    return errors


def _domain_relation(obj: dict) -> list[str]:
    errors: list[str] = []
    interval = obj.get("distance_interval_m")
    distance = obj.get("distance_m")
    if isinstance(interval, list) and len(interval) == 2:
        lo, hi = interval
        if isinstance(lo, (int, float)) and isinstance(hi, (int, float)):
            if lo > hi:
                errors.append(
                    f"INTERVAL_ORDER: lower ({lo}) > upper ({hi})"
                )
            if isinstance(distance, (int, float)) and not (lo <= distance <= hi):
                errors.append(
                    "INTERVAL_CONTAINS_DISTANCE: "
                    f"distance ({distance}) outside [{lo},{hi}]"
                )
    if obj.get("metric_status") == "VERIFIED":
        frame_ref = obj.get("frame_ref")
        basis = frame_ref.get("time_basis") if isinstance(frame_ref, dict) else None
        if basis != "CAMERA_CAPTURE_VERIFIED":
            errors.append(
                "VERIFIED_REQUIRES_CAPTURE_TIME: metric VERIFIED requires "
                f"frame_ref.time_basis CAMERA_CAPTURE_VERIFIED, got {basis!r}"
            )
    return errors


def _domain_spatial_summary(obj: dict) -> list[str]:
    errors: list[str] = []
    started = obj.get("period_start_edge_monotonic_ns")
    ended = obj.get("period_end_edge_monotonic_ns")
    if isinstance(started, int) and isinstance(ended, int) and ended < started:
        errors.append(f"PERIOD_ORDER: ended ({ended}) < started ({started})")
    errors.extend(_pair_consistency(obj))
    return errors


def _pair_consistency(obj: dict) -> list[str]:
    count = obj.get("metric_pair_count")
    minimum = obj.get("min_verified_person_equipment_distance_m")
    if not isinstance(count, int) or "min_verified_person_equipment_distance_m" not in obj:
        return []
    if count == 0 and minimum is not None:
        return [
            "PAIR_DISTANCE_INCONSISTENT: metric_pair_count==0 with non-null min_verified distance"
        ]
    if minimum is not None and count < 1:
        return [
            "PAIR_DISTANCE_INCONSISTENT: non-null min_verified distance with count<1"
        ]
    return []


def _domain_policy(obj: dict) -> list[str]:
    errors: list[str] = []
    rules = obj.get("rules")
    if not isinstance(rules, list):
        return errors
    seen: set[str] = set()
    for rule in rules:
        if not isinstance(rule, dict):
            continue
        rule_id = rule.get("rule_id")
        if isinstance(rule_id, str):
            if rule_id in seen:
                errors.append(f"DUPLICATE_ID: duplicate rule_id {rule_id!r}")
            seen.add(rule_id)
        if (
            rule.get("kind") == "PERSON_EQUIPMENT_PROXIMITY"
            and rule.get("mode") != "OFF"
            and rule.get("threshold_m") is None
        ):
            errors.append(
                "PROXIMITY_THRESHOLD_REQUIRED: PERSON_EQUIPMENT_PROXIMITY "
                f"rule {rule_id!r} with mode {rule.get('mode')!r} requires threshold_m"
            )
    return errors


def _domain_manifest(obj: dict) -> list[str]:
    errors: list[str] = []
    edge_seq = obj.get("edge_last_seq")
    rc_seq = obj.get("rc_received_last_seq")
    if isinstance(edge_seq, int) and isinstance(rc_seq, int) and rc_seq > edge_seq:
        errors.append(
            f"SEQ_ORDER: rc_received_last_seq ({rc_seq}) > edge_last_seq ({edge_seq})"
        )
    artifacts = obj.get("artifacts")
    if isinstance(artifacts, list):
        seen_ids: set[str] = set()
        seen_segments: set[int] = set()
        for artifact in artifacts:
            if not isinstance(artifact, dict):
                continue
            artifact_id = artifact.get("artifact_id")
            if isinstance(artifact_id, str):
                if artifact_id in seen_ids:
                    errors.append(
                        f"DUPLICATE_ID: duplicate artifact_id {artifact_id!r}"
                    )
                seen_ids.add(artifact_id)
            if artifact.get("source_kind") == "RC_PROXY_SEGMENT":
                segment = artifact.get("segment_index")
                if isinstance(segment, int):
                    if segment in seen_segments:
                        errors.append(
                            "DUPLICATE_ID: duplicate RC_PROXY_SEGMENT "
                            f"segment_index {segment}"
                        )
                    seen_segments.add(segment)
    return errors


def _domain_snapshot(obj: dict) -> list[str]:
    errors: list[str] = []
    zones = obj.get("zones")
    if not isinstance(zones, list):
        return errors
    seen: set[str] = set()
    for zone in zones:
        if not isinstance(zone, dict):
            continue
        zone_id = zone.get("zone_id")
        if isinstance(zone_id, str):
            if zone_id in seen:
                errors.append(f"DUPLICATE_ID: duplicate zone_id {zone_id!r}")
            seen.add(zone_id)
        geometry = zone.get("geometry_wgs84")
        coordinates = geometry.get("coordinates") if isinstance(geometry, dict) else None
        if isinstance(coordinates, list):
            for ring in coordinates:
                if not isinstance(ring, list):
                    continue
                if len(ring) < 4 or ring[0] != ring[-1]:
                    errors.append(
                        "POLYGON_RING_OPEN: ring must have >=4 positions with first==last"
                    )
    return errors


def _domain_observation(obj: dict) -> list[str]:
    errors: list[str] = []
    detections = obj.get("detections")
    if not isinstance(detections, list):
        return errors
    seen: set[str] = set()
    for detection in detections:
        if not isinstance(detection, dict):
            continue
        detection_id = detection.get("detection_id")
        if isinstance(detection_id, str):
            if detection_id in seen:
                errors.append(
                    f"DUPLICATE_ID: duplicate detection_id {detection_id!r}"
                )
            seen.add(detection_id)
        bbox = detection.get("bbox_xyxy_px")
        if isinstance(bbox, list) and len(bbox) == 4 and all(
            isinstance(v, (int, float)) for v in bbox
        ):
            x1, y1, x2, y2 = bbox
            if x1 > x2 or y1 > y2:
                errors.append(
                    f"BBOX_ORDER: bbox corners out of order {list(bbox)}"
                )
    return errors


def _domain_annotation(obj: dict) -> list[str]:
    if (
        obj.get("annotation_type") == "REVOKE_PREVIOUS"
        and obj.get("supersedes_annotation_id") is None
    ):
        return [
            "REVOKE_TARGET_MISSING: REVOKE_PREVIOUS requires supersedes_annotation_id"
        ]
    return []
