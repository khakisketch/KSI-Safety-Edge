"""OpenAPI freeze checks: shape, operations, and reference integrity."""

import json
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "contracts" / "openapi.yaml"

# (path, method, operationId)
REQUIRED_OPS = [
    ("/healthz", "get", "healthz"),
    ("/sites", "get", "listSites"),
    ("/sites", "post", "createSite"),
    ("/sites/{siteId}/zone-revisions", "get", "listZoneRevisions"),
    ("/sites/{siteId}/zone-revisions", "post", "createZoneRevision"),
    ("/sites/{siteId}/risk-policies", "get", "listRiskPolicies"),
    ("/sites/{siteId}/risk-policies", "post", "createRiskPolicy"),
    ("/missions", "post", "createMission"),
    ("/missions/{missionId}/snapshot", "get", "getMissionSnapshot"),
    ("/mission-bundles", "get", "listMissionBundles"),
    ("/mission-bundles", "post", "createMissionBundle"),
    ("/mission-bundles/{bundleId}", "get", "getMissionBundle"),
    ("/flights", "get", "listFlights"),
    ("/flights", "post", "registerFlight"),
    ("/flights/{flightId}", "get", "getFlight"),
    ("/flights/{flightId}/mission-applications", "get", "listMissionApplications"),
    ("/flights/{flightId}/mission-applications", "post", "recordMissionApplication"),
    ("/flights/{flightId}/flight-sessions", "get", "listFlightSessions"),
    ("/flights/{flightId}/flight-sessions", "post", "recordFlightSession"),
    ("/flights/{flightId}/observations:batch", "post", "ingestObservationBatch"),
    ("/flights/{flightId}/observations", "get", "listObservations"),
    ("/flights/{flightId}/events:batch", "post", "ingestEventBatch"),
    ("/flights/{flightId}/spatial-summaries:batch", "post", "ingestSpatialSummaryBatch"),
    ("/flights/{flightId}/spatial-summaries", "get", "listSpatialSummaries"),
    ("/flights/{flightId}/flight-summaries:batch", "post", "ingestFlightSummaryBatch"),
    ("/flights/{flightId}/coverage", "get", "getFlightCoverage"),
    ("/events", "get", "listEvents"),
    ("/events/{eventId}", "get", "getEvent"),
    ("/events/{eventId}/evidence", "get", "listEventEvidence"),
    ("/objects/{artifactId}", "get", "downloadObject"),
    ("/events/{eventId}/annotations", "get", "listAnnotations"),
    ("/events/{eventId}/annotations", "post", "createAnnotation"),
    ("/ingest-sessions", "post", "openIngestSession"),
    ("/ingest-sessions/{uploadId}", "get", "getIngestSession"),
    ("/ingest-sessions/{uploadId}/objects/{artifactId}", "put", "uploadObjectChunk"),
    ("/ingest-sessions/{uploadId}/complete", "post", "completeIngestSession"),
    ("/review-jobs", "get", "listReviewJobs"),
    ("/review-jobs", "post", "createReviewJob"),
    ("/review-jobs/{jobId}", "get", "getReviewJob"),
]

# batch endpoint path -> expected item schema file referenced by its request body
BATCH_ITEM_REFS = {
    "/flights/{flightId}/observations:batch": "observation.v1.schema.json",
    "/flights/{flightId}/events:batch": "detection-event.v1.schema.json",
    "/flights/{flightId}/spatial-summaries:batch": "spatial-summary.v1.schema.json",
    "/flights/{flightId}/flight-summaries:batch": "flight-summary.v1.schema.json",
}


def load_spec() -> dict:
    return yaml.safe_load(SPEC.read_text(encoding="utf-8"))


def resolve_pointer(doc: dict, pointer: str):
    assert pointer.startswith("#/"), pointer
    node = doc
    for part in pointer[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        assert isinstance(node, dict) and part in node, f"dangling {pointer}"
        node = node[part]
    return node


def collect_refs(node, out: list):
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str):
            out.append(ref)
        for value in node.values():
            collect_refs(value, out)
    elif isinstance(node, list):
        for value in node:
            collect_refs(value, out)
    return out


def resolve_response(spec: dict, op: dict, status: str) -> dict:
    resp = op["responses"][status]
    while isinstance(resp, dict) and isinstance(resp.get("$ref"), str):
        assert resp["$ref"].startswith("#/"), resp["$ref"]
        resp = resolve_pointer(spec, resp["$ref"])
    assert isinstance(resp, dict)
    return resp


def test_parses_and_freeze_shape():
    spec = load_spec()
    assert spec["openapi"] == "3.1.0"
    assert spec["servers"][0]["url"].endswith("/api/v1/edge")
    assert spec["security"] == [{"bearerAuth": []}]
    assert spec["components"]["securitySchemes"]["bearerAuth"]["scheme"] == "bearer"


def test_required_operations_exist_with_ids():
    spec = load_spec()
    assert len(REQUIRED_OPS) == 39
    for path, method, operation_id in REQUIRED_OPS:
        assert path in spec["paths"], path
        assert method in spec["paths"][path], (path, method)
        assert spec["paths"][path][method].get("operationId") == operation_id


def test_schema_file_refs_resolve():
    spec = load_spec()
    refs = collect_refs(spec, [])
    file_refs = [r for r in refs if r.startswith("./schemas/")]
    assert file_refs, "expected ./schemas/ refs in the spec"
    for ref in file_refs:
        target = (ROOT / "contracts" / ref[2:]).resolve()
        assert target.is_file(), f"missing schema file for {ref}"
        assert str(ROOT / "contracts" / "schemas") in str(target)


def test_component_refs_resolve():
    spec = load_spec()
    refs = collect_refs(spec, [])
    local_refs = [r for r in refs if r.startswith("#/")]
    assert local_refs
    for ref in local_refs:
        resolve_pointer(spec, ref)  # raises on dangling refs


def test_batch_endpoints_reference_typed_item_schemas():
    spec = load_spec()
    for path, expected_file in BATCH_ITEM_REFS.items():
        op = spec["paths"][path]["post"]
        body = op["requestBody"]
        while isinstance(body.get("$ref"), str):
            body = resolve_pointer(spec, body["$ref"])
        schema = body["content"]["application/json"]["schema"]
        while isinstance(schema.get("$ref"), str) and schema["$ref"].startswith("#/"):
            schema = resolve_pointer(spec, schema["$ref"])
        assert expected_file in json.dumps(schema), (path, expected_file)


def test_healthz_is_public():
    spec = load_spec()
    assert spec["paths"]["/healthz"]["get"].get("security") == []


def test_protected_sample_has_auth_errors():
    spec = load_spec()
    sample = [
        ("/flights", "post"),
        ("/flights/{flightId}/observations:batch", "post"),
        ("/ingest-sessions/{uploadId}/complete", "post"),
        ("/events/{eventId}", "get"),
        ("/objects/{artifactId}", "get"),
    ]
    for path, method in sample:
        op = spec["paths"][path][method]
        assert op.get("security", [{"bearerAuth": []}]) != []
        for status in ("401", "403"):
            resp = resolve_response(spec, op, status)
            dumped = json.dumps(resp)
            assert "Error" in dumped, (path, method, status)


def test_error_shape_and_batch_accepted_shape():
    spec = load_spec()
    error = spec["components"]["schemas"]["Error"]
    assert set(error["required"]) == {"code", "message"}
    accepted = spec["components"]["schemas"]["BatchAccepted"]
    for field in ("accepted_ids", "duplicate_ids", "durable", "server_batch_id"):
        assert field in accepted["properties"], field
