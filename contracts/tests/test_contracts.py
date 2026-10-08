"""Contract runner: schema x example positives, 8-probe negative matrix, byte identity."""

import copy
import hashlib
import json
from pathlib import Path

import pytest

from ksi_contracts import ContractError, validate_all, validate_domain

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "contracts" / "schemas"
EXAMPLES = ROOT / "contracts" / "examples"
ORIG_EXAMPLES = ROOT / "KSI_Safety_Edge_v1.1_Spatial_Design" / "examples"
ORIG_SCHEMAS = ROOT / "KSI_Safety_Edge_v1.1_Spatial_Design" / "contracts" / "schemas"

# (schema name, example file, strict digests?)
MATRIX = [
    ("flight-upload-manifest.v1", "flight-upload-manifest.example.json", False),
    ("mission-snapshot.v1", "mission-snapshot.example.json", False),
    ("observation.v1", "observation.example.json", False),
    ("operator-annotation.v1", "operator-annotation.example.json", False),
    ("review-finding.v1", "review-finding.example.json", False),
    ("risk-policy.v1", "risk-policy.v1.example.json", False),
    ("spatial-relation.v1", "spatial-relation.v1.example.json", False),
    ("spatial-summary.v1", "spatial-summary.v1.example.json", False),
    ("mission-bundle.v1", "mission-bundle.v1.example.json", True),
    ("mission-application.v1", "mission-application.v1.example.json", True),
    ("flight-session.v1", "flight-session.v1.example.json", True),
    ("detection-event.v1", "detection-event.v1.example.json", True),
    ("flight-summary.v1", "flight-summary.v1.example.json", True),
]


def load_example(stem: str) -> dict:
    return json.loads((EXAMPLES / stem).read_text(encoding="utf-8"))


def load_original_example(stem: str) -> dict:
    return json.loads((ORIG_EXAMPLES / stem).read_text(encoding="utf-8"))


@pytest.mark.parametrize("name,stem,strict", MATRIX)
def test_positive_schema_and_domain(name, stem, strict):
    obj = load_example(stem)
    validate_all(name, obj, check_digests=strict)  # raises on failure


def _assert_rejected(name: str, obj: dict, code: str):
    errors = validate_domain(name, obj, check_digests=False)
    assert any(e.startswith(code) for e in errors), (name, code, errors)
    with pytest.raises(ContractError) as exc:
        validate_all(name, obj, check_digests=False)
    assert any(e.startswith(code) for e in exc.value.errors)


def test_probe_reversed_interval():
    obj = load_original_example("spatial-relation.v1.example.json")
    obj["distance_m"] = 4.2
    obj["distance_interval_m"] = [5.0, 3.0]
    _assert_rejected("spatial-relation.v1", obj, "INTERVAL_ORDER")


def test_probe_verified_with_unverified_time():
    obj = load_original_example("spatial-relation.v1.example.json")
    obj.update(
        {
            "metric_status": "VERIFIED",
            "geometry_level": "METRIC_VERIFIED",
            "distance_m": 4.2,
            "distance_interval_m": [3.9, 4.5],
            "distance_basis": "GROUND_FOOTPRINT",
            "calibration_revision_id": "cal-probe",
            "ground_model_revision_id": "ground-probe",
        }
    )
    obj["frame_ref"] = dict(obj["frame_ref"], time_basis="UNVERIFIED")
    _assert_rejected("spatial-relation.v1", obj, "VERIFIED_REQUIRES_CAPTURE_TIME")


def test_probe_reversed_period():
    obj = load_original_example("spatial-summary.v1.example.json")
    obj["period_end_edge_monotonic_ns"] = obj["period_start_edge_monotonic_ns"] - 1
    _assert_rejected("spatial-summary.v1", obj, "PERIOD_ORDER")


def test_probe_pair_count_zero_with_distance():
    obj = load_original_example("spatial-summary.v1.example.json")
    obj["metric_pair_count"] = 0
    obj["min_verified_person_equipment_distance_m"] = 1.0
    _assert_rejected("spatial-summary.v1", obj, "PAIR_DISTANCE_INCONSISTENT")


def test_probe_unclosed_polygon():
    obj = load_original_example("mission-snapshot.example.json")
    ring = obj["zones"][0]["geometry_wgs84"]["coordinates"][0]
    ring[-1] = [127.4911, 36.6309]  # no longer equals ring[0]
    _assert_rejected("mission-snapshot.v1", obj, "POLYGON_RING_OPEN")


def test_probe_revoke_without_target():
    obj = load_original_example("operator-annotation.example.json")
    obj["annotation_type"] = "REVOKE_PREVIOUS"
    obj["supersedes_annotation_id"] = None
    _assert_rejected("operator-annotation.v1", obj, "REVOKE_TARGET_MISSING")


def test_probe_duplicate_rule_id():
    obj = load_original_example("risk-policy.v1.example.json")
    obj["rules"].append(copy.deepcopy(obj["rules"][0]))
    _assert_rejected("risk-policy.v1", obj, "DUPLICATE_ID")


def test_probe_rc_seq_ahead_of_edge():
    obj = load_original_example("flight-upload-manifest.example.json")
    obj["rc_received_last_seq"] = obj["edge_last_seq"] + 1
    _assert_rejected("flight-upload-manifest.v1", obj, "SEQ_ORDER")


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_schemas_byte_identical_to_source_package():
    for path in sorted(SCHEMAS.glob("*.schema.json")):
        src = ORIG_SCHEMAS / path.name
        if not src.is_file():
            continue  # new W1a schemas have no source counterpart
        assert _sha256(path) == _sha256(src), f"byte mismatch: {path.name}"


def test_legacy_examples_byte_identical_to_source_package():
    legacy_stems = [stem for _, stem, strict in MATRIX if not strict]
    assert len(legacy_stems) == 8
    for stem in legacy_stems:
        assert _sha256(EXAMPLES / stem) == _sha256(ORIG_EXAMPLES / stem), (
            f"byte mismatch: {stem}"
        )
