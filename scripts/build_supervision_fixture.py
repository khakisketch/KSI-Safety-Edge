#!/usr/bin/env python3
"""Build a read-only, explicitly simulated UI input from a host Edge run.

The input is NOT an aircraft/MOP schema and never drives commands. A sealed
revision and optional receipt are verified against exact bytes before the UI
may display local/central custody. RC receipt is deliberately unverified.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys

from supervision_evidence import (
    ASSESSMENT_MEDIA_TYPES, load_json, save_immutable_json,
    verify_custody_receipt, verify_revision,
)
from collect_edge_evidence import build_manifest

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "harness" / "src"))
from ksi_harness.mop import verify_envelope  # noqa: E402

RUNTIME_STATES = {"REQUEST_ACCEPTED", "CONFIG_APPLIED", "STARTING", "RUNNING", "DEGRADED", "STOPPED"}
INPUT_STATES = {"ok", "stale", "lost", "failed", "absent"}
DISPOSITIONS = {"UNDETERMINED", "CONDITION_MET", "CONDITION_NOT_MET", "NOT_EVALUATED"}


def _file_source(name: str, path: Path) -> dict:
    return {"name": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def _runtime(transcript: dict, revision: dict) -> dict:
    if (transcript.get("schema_version") != 1
            or transcript.get("fixture_kind") != "KSI_MOP_PROBE_TRANSCRIPT"
            or transcript.get("environment") != "HOST_REPLAY"
            or transcript.get("simulated_inputs") is not True
            or transcript.get("device_verified") is not False
            or transcript.get("flight_id") != revision["flight_id"]):
        raise ValueError("not a matching, explicit host replay transcript")
    messages = transcript.get("messages")
    if not isinstance(messages, list):
        raise ValueError("transcript messages missing")
    reports = []
    for raw in messages:
        env = verify_envelope(raw)
        if env["flight_id"] != revision["flight_id"]:
            raise ValueError("transcript includes a different flight")
        if env["type"] == "MISSION_STATUS" and env.get("edge_boot_id") == revision["edge_boot_id"]:
            reports.append(env["payload"])
    if not reports:
        raise ValueError("no verified runtime report for the sealed Edge boot")
    report = reports[-1]
    state = report.get("runtime_state")
    if state is not None and state not in RUNTIME_STATES:
        raise ValueError("unknown runtime state")
    count = report.get("processed_frame_count")
    if count is not None and (type(count) is not int or count < 0):
        raise ValueError("invalid processed frame count")
    inputs = report.get("input_state") or {}
    if not isinstance(inputs, dict):
        raise ValueError("invalid input state")
    for value in inputs.values():
        if value is not None and value not in INPUT_STATES:
            raise ValueError("unknown input grade")
    return {"runtime_state": state, "processed_frame_count": count,
            "input_state": {key: inputs.get(key) for key in ("video", "telemetry", "engine")},
            "degraded_reasons": report.get("degraded_reasons", [])}


def _assessments(root: Path, assets: list[dict]) -> tuple[list[dict], str]:
    """Explicit internal assessment artifacts; never inspect a frame as JSON.

    Accepts the shared Edge format (schema ksi.assessment/v1, disposition
    ASSESSED/UNDETERMINED) and the earlier synthetic development envelope.
    Both stay SHADOW-only and never imply an Incident or Alert.
    """
    records = []
    context = []
    for asset in assets:
        if asset.get("media_type") not in ASSESSMENT_MEDIA_TYPES:
            continue
        record = load_json(root / "assets" / asset["asset_id"], max_bytes=1 << 20)
        if record.get("schema") == "ksi.assessment/v1":
            if record.get("mode") != "SHADOW" or record.get("disposition") not in ("ASSESSED", "UNDETERMINED"):
                raise ValueError("assessment is not a supported SHADOW evaluation")
            normalized = {"disposition": record["disposition"],
                          "reason_codes": list(record.get("missing_reasons", [])),
                          "flight_id": record.get("flight_id"),
                          "edge_boot_id": record.get("edge_boot_id")}
        else:
            if record.get("record_type") != "assessment" or record.get("schema_version") != 1:
                raise ValueError("unknown internal assessment artifact format")
            if record.get("mode") != "SHADOW" or record.get("disposition") not in DISPOSITIONS:
                raise ValueError("assessment is not a supported SHADOW evaluation")
            normalized = record
        if normalized.get("flight_id") != asset["flight_id"] or normalized.get("edge_boot_id") != asset["edge_boot_id"]:
            raise ValueError("assessment provenance does not match its sealed identity")
        records.append(normalized)
        complete = record.get("evidence_context_complete")
        if complete is not None and type(complete) is not bool:
            raise ValueError("invalid context completeness")
        context.append(complete)
    grade = "UNKNOWN" if not context or any(x is None for x in context) else (
        "COMPLETE" if all(context) else "INCOMPLETE")
    return records, grade


def build_fixture(root: Path, revision_path: Path, transcript_path: Path,
                  receipt_path: Path | None = None) -> dict:
    revision = load_json(revision_path)
    assets = verify_revision(revision, root)
    runtime = _runtime(load_json(transcript_path), revision)
    records, context = _assessments(root, assets)
    latest = records[-1] if records else None
    proof = [_file_source("sealed_revision", revision_path), _file_source("runtime_transcript", transcript_path)]
    custody = "UNVERIFIED"
    collection_id = None
    if receipt_path is not None:
        receipt = load_json(receipt_path)
        verify_custody_receipt(receipt, build_manifest(revision, root))
        custody, collection_id = "COMMITTED", receipt["collection_id"]
        proof.append(_file_source("core_custody_receipt", receipt_path))
    critical = [reason for reason in runtime.pop("degraded_reasons") if reason in {
        "video_input_lost", "video_input_stale", "engine_failed", "session_capacity_exceeded"}]
    return {
        "schema_version": 1, "fixture_kind": "KSI_SUPERVISION_DEVELOPMENT",
        "provenance": {"environment": "HOST_REPLAY", "simulated_inputs": True,
                       "device_verified": False, "source_files": proof},
        "observation": runtime,
        "camera": {"reported_owner": "UNKNOWN", "automatic_permission": "UNAVAILABLE",
                   "adjustment_state": "NOT_CONFIGURED"},
        "assessment": {"mode": "SHADOW", "count": len(records) if records else None,
                       "latest_disposition": latest["disposition"] if latest else "UNKNOWN",
                       "reason_codes": latest.get("reason_codes", []) if latest else []},
        "evidence": {"local_state": "SEALED", "context_state": context,
                     "source_frame_count": sum(a.get("media_type") == "application/x-raw-rgb24" for a in assets),
                     "assessment_artifact_count": len(records) if records else None,
                     "rc_receipt_state": "UNVERIFIED", "core_custody_state": custody,
                     "collection_id": collection_id},
        "intervention": {"required": bool(critical) if runtime["runtime_state"] else None,
                         "reason_codes": critical},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--edge-dir", required=True, type=Path)
    parser.add_argument("--revision", required=True, type=Path)
    parser.add_argument("--transcript", required=True, type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    fixture = build_fixture(args.edge_dir, args.revision, args.transcript, args.receipt)
    save_immutable_json(args.output, fixture)
    print(f"HOST_REPLAY [SIMULATED] supervision fixture saved: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
