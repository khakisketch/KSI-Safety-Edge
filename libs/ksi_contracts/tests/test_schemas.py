"""Unit tests for schema_store: discovery, loading, and $ref resolution."""

import json

import pytest

from ksi_contracts import load_schema, schemas_dir, validator_for
from ksi_contracts.schema_store import SCHEMA_NAMES


def test_schemas_dir_defaults_to_repo_contracts():
    d = schemas_dir()
    assert d.is_dir()
    assert (d / "mission-snapshot.v1.schema.json").is_file()
    assert d.name == "schemas"


def test_schemas_dir_env_override(monkeypatch, tmp_path):
    monkeypatch.setenv("KSI_CONTRACTS_DIR", str(tmp_path))
    assert schemas_dir() == tmp_path


def test_all_known_schemas_load_and_self_check():
    for name in SCHEMA_NAMES:
        schema = load_schema(name)
        assert isinstance(schema, dict)
        assert schema["$id"].endswith(f"{name}.schema.json")


def test_unknown_schema_name_error_lists_known():
    with pytest.raises(KeyError, match="known:"):
        load_schema("ghost.v1")


def test_bundle_resolves_relative_refs_to_snapshot_and_policy():
    bundle = load_schema("mission-bundle.v1")
    refs = json.dumps(bundle)
    assert "./mission-snapshot.v1.schema.json" in refs
    assert "./risk-policy.v1.schema.json" in refs
    v = validator_for("mission-bundle.v1")
    # An empty snapshot must fail *through* the resolved $ref, not crash.
    errors = list(
        v.iter_errors(
            {
                "schema_version": 1,
                "bundle_id": "22222222-2222-4222-8222-000000000002",
                "site_id": "22222222-2222-4222-8222-000000000001",
                "created_at_utc": "2026-10-08T02:01:00Z",
                "origin": "SERVER",
                "created_by": None,
                "mission_snapshot": {"schema_version": 999},
                "risk_policy": {"schema_version": 999},
                "calibration_refs": [],
                "ground_model_refs": [],
                "bundle_sha256": "0" * 64,
            }
        )
    )
    assert errors, "expected $ref-resolved subschema to reject bad snapshot"
    assert any("mission_snapshot" in str(e.absolute_path) for e in errors)
