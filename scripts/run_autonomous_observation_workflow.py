#!/usr/bin/env python3
"""Aircraft-free integrated autonomous-observation run (explicitly SIMULATED).

Uses the shared C++ Manifold processing code, verified MOP envelopes, immutable
evidence and a NEW task-local Core. It never connects to devices, uses a shared
Core DB, deletes old runs, grants ACTIVE, or treats the virtual probe as an RC.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import uuid

from build_supervision_fixture import build_fixture
from collect_edge_evidence import build_manifest, request_json, upload
from mop_edge_probe import Probe, fixtures
from supervision_evidence import load_json, save_immutable_json, verify_custody_receipt

REPO = Path(__file__).resolve().parents[1]
TOKEN = "tok-orgA-rc"  # isolated development server only, not a device credential


def _stop(proc: subprocess.Popen) -> None:
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)


@contextmanager
def isolated_core(root: Path):
    """Pass our own bound socket to uvicorn: never accidentally target port8000."""
    root.mkdir(parents=True, exist_ok=False)
    with socket.socket() as sock, (root / "server.log").open("wb") as log:
        sock.bind(("127.0.0.1", 0))
        sock.listen(16)
        port = sock.getsockname()[1]
        env = {**os.environ, "KSI_DB_URL": f"sqlite:///{root / 'core.db'}",
               "KSI_OBJECT_ROOT": str(root / "objects"),
               "KSI_DEV_TOKENS": json.dumps({TOKEN: {"org_id": "autonomous-test", "scopes": ["rc", "read"]}})}
        proc = subprocess.Popen(
            [sys.executable, "-m", "uvicorn", "ksi_core.app:create_app", "--factory", "--fd", str(sock.fileno())],
            cwd=REPO, env=env, pass_fds=(sock.fileno(),), stdout=log, stderr=subprocess.STDOUT)
        base = f"http://127.0.0.1:{port}/api/v1/edge"
        try:
            deadline = time.monotonic() + 25
            while True:
                if proc.poll() is not None:
                    raise RuntimeError(f"task-local Core exited: {root / 'server.log'}")
                try:
                    code, _ = request_json(base + "/healthz", TOKEN)
                    if code == 200:
                        break
                except OSError:
                    pass
                if time.monotonic() > deadline:
                    raise RuntimeError("task-local Core readiness timed out")
                time.sleep(.05)
            yield base
        finally:
            _stop(proc)


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def write_corpus(root: Path, count: int = 12) -> Path:
    root.mkdir(parents=True, exist_ok=False)
    lines = ["# [SIMULATED] RGB and stub detections; no real spatial/helmet claim"]
    for index in range(count):
        name = f"frame-{index:03d}.rgb"
        raw = bytes(value for y in range(64) for x in range(64)
                    for value in ((x * 4 + index * 9) % 256, y * 4, index * 13 % 256))
        (root / name).write_bytes(raw)
        lines.append(f"frame {index + 1} {(index + 1) * 100000000} 64 64 {name}")
    manifest = root / "manifest.txt"
    manifest.write_text("\n".join(lines) + "\n")
    return manifest


def run_workflow(binary: Path, root: Path, core_url: str, edge_args: list[str]) -> Path:
    corpus = write_corpus(root / "corpus")
    evidence = root / "evidence"
    evidence.mkdir(exist_ok=False)
    autonomy_evidence = root / "autonomy-evidence"
    autonomy_evidence.mkdir(exist_ok=False)
    flight = str(uuid.uuid4())
    boot = str(uuid.uuid4())
    port = _free_port()
    command = [str(binary.resolve()), "--port", str(port), "--flight-id", flight, "--boot-id", boot,
               "--replay-manifest", str(corpus), "--evidence-dir", str(evidence),
               "--autonomy-evidence-dir", str(autonomy_evidence),
               "--stub-detector", "--max-seconds", "30", *edge_args]
    probe = None
    with (root / "edge.log").open("wb") as log:
        proc = subprocess.Popen(command, cwd=REPO, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 10
            while probe is None:
                if proc.poll() is not None:
                    raise RuntimeError(f"host Edge exited before handshake: {root / 'edge.log'}")
                try:
                    probe = Probe("127.0.0.1", port, flight)
                except (ConnectionRefusedError, TimeoutError):
                    if time.monotonic() > deadline:
                        raise RuntimeError("host Edge readiness timed out")
                    time.sleep(.03)
            world = fixtures.world("autonomous-observation")
            bundle = world["bundle"]
            probe.send("MISSION_PREPARE", {"bundle": bundle})
            probe.pump(.25)
            if not any(kind == "MISSION_PREPARED" for kind, _ in probe.log):
                raise RuntimeError("Edge did not confirm preparation")
            probe.send("MISSION_APPLY", {"bundle_id": bundle["bundle_id"], "bundle_sha256": bundle["bundle_sha256"]})
            probe.pump(.25)
            if not any(kind == "MISSION_APPLIED" for kind, _ in probe.log):
                raise RuntimeError("Edge did not confirm actual installation")
            probe.send("SESSION_START", {})
            while time.monotonic() < deadline:
                probe.pump(.15)
                probe.send("MISSION_STATUS_QUERY", {})
                probe.pump(.1)
                reports = [env["payload"] for kind, env in probe.log if kind == "MISSION_STATUS"]
                if any(r.get("processed_frame_count", 0) >= 12 for r in reports):
                    break
            else:
                raise RuntimeError("actual shared runner did not process the corpus")
            probe.send("SESSION_STOP", {})
            probe.pump(.3)
            probe.send("MISSION_STATUS_QUERY", {})
            probe.pump(.2)
            transcript = {"schema_version": 1, "fixture_kind": "KSI_MOP_PROBE_TRANSCRIPT",
                          "environment": "HOST_REPLAY", "simulated_inputs": True,
                          "device_verified": False, "flight_id": flight,
                          "messages": [env for _, env in probe.log]}
            save_immutable_json(root / "transcript.json", transcript)
            deadline = time.monotonic() + 60
            while not (list((evidence / "revisions").glob("*.json")) and list((autonomy_evidence / "revisions").glob("*.json"))):
                if time.monotonic() > deadline:
                    raise RuntimeError(f"Edge did not seal original evidence (edge exit={proc.poll()})")
                time.sleep(.05)
        finally:
            if probe is not None:
                probe.sock.close()
            _stop(proc)
    revisions = list((autonomy_evidence / "revisions").glob("*.json"))
    if len(revisions) != 1:
        raise RuntimeError("expected one explicit sealed autonomy revision")
    revision_path = revisions[0]
    manifest = build_manifest(load_json(revision_path), autonomy_evidence)
    receipt = upload(core_url, TOKEN, manifest, autonomy_evidence)
    verify_custody_receipt(receipt, manifest)
    save_immutable_json(root / "collection-manifest.json", manifest)
    save_immutable_json(root / "receipt.json", receipt)
    code, raw = request_json(f"{core_url}/collections/{receipt['collection_id']}/receipt", TOKEN)
    if code != 200 or json.loads(raw) != receipt:
        raise RuntimeError("Core receipt readback mismatch")
    result = build_fixture(autonomy_evidence, revision_path, root / "transcript.json", root / "receipt.json")
    if not result["assessment"]["count"]:
        raise RuntimeError("no actual automatic assessment artifact was sealed")
    save_immutable_json(root / "supervision.json", result)
    return root / "supervision.json"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mop-server", required=True, type=Path, help="host build of the actual shared Edge code")
    parser.add_argument("--work-dir", type=Path, help="new or empty task-local directory; nothing is deleted")
    parser.add_argument("--edge-arg", action="append", default=[], help="explicit host-only autonomous module options")
    args = parser.parse_args()
    if not args.mop_server.is_file():
        parser.error("host mop_server binary is missing; build the Edge first")
    root = args.work_dir or Path(tempfile.mkdtemp(prefix="ksi-autonomous-", dir="/tmp/opencode"))
    root.mkdir(parents=True, exist_ok=True)
    if root.is_symlink() or list(root.iterdir()):
        parser.error("work directory must be a new empty directory; existing runs are preserved")
    with isolated_core(root / "core") as core_url:
        path = run_workflow(args.mop_server, root, core_url, args.edge_arg)
    print(f"HOST_REPLAY [SIMULATED] shared Edge -> sealed assessment/context -> Core receipt -> supervision: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
