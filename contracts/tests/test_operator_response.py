"""OperatorResponse v1: schema x example positive, negative matrix, digest rule.

Style mirrors contracts/tests/test_contracts.py without touching it:
positives via validate_all (strict digests), negatives asserting stable
CODE: prefixes from the domain layer plus schema rejection.
"""

import copy
import json
from pathlib import Path

import pytest
import yaml

from ksi_contracts import ContractError, set_digest, validate_all, validate_domain

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "contracts" / "examples"
EXTENSION = ROOT / "contracts" / "extensions" / "operator-responses.openapi.yaml"
FROZEN_SPEC = ROOT / "contracts" / "openapi.yaml"

NAME = "operator-response.v1"
STEM = "operator-response.v1.example.json"


def load_example() -> dict:
    return json.loads((EXAMPLES / STEM).read_text(encoding="utf-8"))


def test_positive_example_strict():
    validate_all(NAME, load_example(), check_digests=True)  # raises on failure


def _assert_rejected(obj: dict, code: str):
    errors = validate_domain(NAME, obj, check_digests=False)
    assert any(e.startswith(code) for e in errors), (code, errors)
    with pytest.raises(ContractError) as exc:
        validate_all(NAME, obj, check_digests=False)
    assert any(
        e.startswith(code) or e.startswith("SCHEMA:") for e in exc.value.errors
    ), exc.value.errors


def test_probe_unproven_sent_confirmed():
    obj = load_example()
    obj["delivery_state"] = "SENT_CONFIRMED"
    obj["transmission_proof"] = None
    _assert_rejected(obj, "PROOF_REQUIRED")


def test_probe_unproven_received_confirmed_missing_receipt():
    obj = load_example()
    obj["delivery_state"] = "RECEIVED_CONFIRMED"
    obj["transmission_proof"] = "ack-1"
    obj["receipt_proof"] = None
    _assert_rejected(obj, "PROOF_REQUIRED")


def test_probe_unproven_completed_confirmed_missing_completion():
    obj = load_example()
    obj["delivery_state"] = "COMPLETED_CONFIRMED"
    obj["transmission_proof"] = "ack-1"
    obj["receipt_proof"] = "rcv-1"
    obj["completion_proof"] = None
    _assert_rejected(obj, "PROOF_REQUIRED")


def test_probe_proven_states_accept():
    obj = load_example()
    obj["delivery_state"] = "SENT_CONFIRMED"
    obj["transmission_proof"] = "ack-1"
    validate_all(NAME, set_digest(obj, "content_sha256"), check_digests=True)
    obj = load_example()
    obj["delivery_state"] = "COMPLETED_CONFIRMED"
    obj["transmission_proof"] = "ack-1"
    obj["receipt_proof"] = "rcv-1"
    obj["completion_proof"] = "done-1"
    validate_all(NAME, set_digest(obj, "content_sha256"), check_digests=True)


def test_probe_wrong_action_enum_rejected_by_schema():
    obj = load_example()
    obj["action"] = "ACKNOWLEDGE"
    with pytest.raises(ContractError) as exc:
        validate_all(NAME, obj, check_digests=False)
    assert any(e.startswith("SCHEMA:") for e in exc.value.errors)


def test_probe_wrong_delivery_enum_rejected_by_schema():
    obj = load_example()
    obj["delivery_state"] = "SENT"
    with pytest.raises(ContractError) as exc:
        validate_all(NAME, obj, check_digests=False)
    assert any(e.startswith("SCHEMA:") for e in exc.value.errors)


def test_probe_missing_evidence_field_rejected_by_schema():
    obj = load_example()
    del obj["evidence"]["policy_version"]
    with pytest.raises(ContractError) as exc:
        validate_all(NAME, obj, check_digests=False)
    assert any(e.startswith("SCHEMA:") for e in exc.value.errors)


def test_probe_note_over_280_chars_rejected_by_schema():
    obj = load_example()
    obj["note"] = "x" * 281
    with pytest.raises(ContractError) as exc:
        validate_all(NAME, obj, check_digests=False)
    assert any(e.startswith("SCHEMA:") for e in exc.value.errors)


def test_probe_digest_mismatch_rejected_strict():
    obj = load_example()
    obj["note"] = "tampered after digest"
    errors = validate_domain(NAME, obj, check_digests=True)
    assert any(e.startswith("DIGEST_MISMATCH") for e in errors), errors
    with pytest.raises(ContractError):
        validate_all(NAME, obj, check_digests=True)


def test_probe_extra_field_rejected():
    obj = load_example()
    obj["verdict"] = "SAFE"
    with pytest.raises(ContractError) as exc:
        validate_all(NAME, obj, check_digests=False)
    assert any(e.startswith("SCHEMA:") for e in exc.value.errors)


def test_probe_requested_without_proofs_accepts():
    obj = load_example()
    assert obj["delivery_state"] == "REQUESTED"
    assert obj["transmission_proof"] is None
    validate_all(NAME, copy.deepcopy(obj), check_digests=True)


def load_extension() -> dict:
    return yaml.safe_load(EXTENSION.read_text(encoding="utf-8"))


def test_extension_documents_exactly_the_two_response_routes():
    ext = load_extension()
    assert ext["openapi"] == "3.1.0"
    assert set(ext["paths"]) == {"/operator-responses", "/flights/{flightId}/operator-responses"}
    assert ext["paths"]["/operator-responses"]["post"]["operationId"] == "ingestOperatorResponseBatch"
    assert ext["paths"]["/flights/{flightId}/operator-responses"]["get"]["operationId"] == "listOperatorResponses"


def test_extension_schema_refs_resolve_to_real_files():
    ext = load_extension()
    refs = []

    def collect(node):
        if isinstance(node, dict):
            ref = node.get("$ref")
            if isinstance(ref, str):
                refs.append(ref)
            for value in node.values():
                collect(value)
        elif isinstance(node, list):
            for value in node:
                collect(value)

    collect(ext)
    file_refs = [r for r in refs if r.endswith(".schema.json")]
    assert file_refs, "extension must reference the operator-response schema file"
    for ref in file_refs:
        target = (EXTENSION.parent / ref).resolve()
        assert target.is_file(), f"missing schema file for {ref}"
        assert "operator-response.v1.schema.json" in ref


def test_extension_covers_error_auth_batch_query_semantics():
    ext = load_extension()
    post = ext["paths"]["/operator-responses"]["post"]
    get = ext["paths"]["/flights/{flightId}/operator-responses"]["get"]
    for op in (post, get):
        for status in ("401", "403", "404"):
            assert status in op["responses"], (op.get("operationId"), status)
    assert "409" in post["responses"]  # batch/idempotency conflicts
    assert "422" in post["responses"]  # proof/reference validation
    dumped = json.dumps(ext)
    assert "BatchAccepted" in dumped
    assert "client_batch_id" in dumped
    assert "durable" in dumped
    assert "server_batch_id" in dumped
    # frozen baseline untouched by the extension: no baseline path redefined
    frozen = yaml.safe_load(FROZEN_SPEC.read_text(encoding="utf-8"))
    assert not (set(ext["paths"]) & set(frozen["paths"])), "extension must not redefine frozen paths"
