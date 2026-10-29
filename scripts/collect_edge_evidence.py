#!/usr/bin/env python3
"""Edge → PC collector → Core: the first central collection path.

WHY THIS SCRIPT EXISTS
    The Edge is the origin of the observation evidence. The Core is the
    custodian. Nothing in the field is allowed to reach the internet, and the
    RC's own upload path describes the RC's record (rc_device_id, RC receipt
    times) — a collector must NOT invent those. So the central collection of
    Edge origin evidence travels its own producer-neutral path
    (`collection-manifest.v1`) and comes back as a **custody receipt**.

WHAT IT DOES
    1. Reads the Edge's **sealed revision** (read-only): the immutable
       manifest plus the exact asset bytes it lists. Nothing else is read —
       the live spool is never copied and called a manifest.
    2. Builds the collection manifest from that revision (identity, digests
       and sizes come from the revision itself, never re-derived).
    3. Opens the collection on the Core, uploads the bytes over the proven
       resumable chunk path, and commits.
    4. Prints the **receipt** — the custody proof for the exact required
       artifact set. No receipt means the collection is not held centrally.

SAFETY RULES (deliberate, not incidental)
    * The local copy is **never deleted**: the receipt arrives first, and even
      then removal is a separate, approved decision (ADR-0002: a receipt is
      not a deletion token).
    * The device command is a **fixed allowlisted shape** (tar of one revision
      file plus the asset ids named by that revision). No free-form remote
      command, no shell interpolation of remote data.
    * The collector never modifies the Edge and never issues aircraft or
      session commands.

USAGE
    # From a live Edge over SSH (production shape):
    python3 scripts/collect_edge_evidence.py \
        --edge-ssh ksi-manifold \
        --edge-state-dir /open_app/ksi-dev/safety-edge-20261021/edge/../../state \
        --core-url http://127.0.0.1:8000/api/v1/edge \
        --token "$KSI_DEV_TOKEN" \
        --revision <revision_id>

    # From an already exported directory (offline copy / test):
    python3 scripts/collect_edge_evidence.py --edge-dir /tmp/edge-export ...
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

from supervision_evidence import (
    save_immutable_json,
    source_kind,
    verify_custody_receipt,
    verify_revision,
)

SCHEMA = "collection-manifest.v1"
SAFE_ID = re.compile(r"^[A-Za-z0-9._-]{1,128}$")
CHUNK_BYTES = 1 << 20


def log(message: str) -> None:
    print(f"[collect] {message}", flush=True)


def fail(message: str, code: int = 1) -> "NoReturn":  # type: ignore[name-defined]
    print(f"[collect] ERROR: {message}", file=sys.stderr, flush=True)
    raise SystemExit(code)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            block = handle.read(1 << 20)
            if not block:
                break
            digest.update(block)
    return digest.hexdigest()


# --- Edge read (read-only) ---------------------------------------------------


def read_revision_over_ssh(host: str, state_dir: str, revision_id: str, workdir: Path) -> Path:
    """Stream ONE sealed revision (+ the assets it names) from the Edge.

    The remote command is a fixed shape: the revision file and the asset ids
    come from the revision manifest we already read, and every path is
    validated against a safe-name pattern before it is used.
    """
    if not SAFE_ID.match(revision_id):
        fail(f"unsafe revision id: {revision_id!r}")
    revision_path = f"{state_dir}/evidence/revisions/{revision_id}.json"
    # 1) the manifest
    result = subprocess.run(
        ["ssh", host, "cat", revision_path],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0 or not result.stdout:
        fail(
            f"cannot read the revision manifest on {host} "
            f"({result.stderr.decode(errors='replace').strip()})"
        )
    manifest = json.loads(result.stdout.decode("utf-8"))
    (workdir / "revision.json").write_bytes(result.stdout)

    # 2) the assets the revision names (allowlisted names only)
    asset_ids = []
    for asset in manifest.get("assets", []):
        asset_id = asset.get("asset_id", "")
        if not SAFE_ID.match(asset_id):
            fail(f"unsafe asset id in the revision: {asset_id!r}")
        asset_ids.append(asset_id)
    if asset_ids:
        remote = "tar -C {dir}/evidence -cf - {files}".format(
            dir=state_dir,
            files=" ".join(f"assets/{asset_id}" for asset_id in asset_ids),
        )
        result = subprocess.run(
            ["ssh", host, remote],
            check=False,
            capture_output=True,
        )
        if result.returncode != 0:
            fail(f"cannot read the assets on {host}: {result.stderr.decode(errors='replace')}")
        with tarfile.open(fileobj=__import__("io").BytesIO(result.stdout)) as tar:
            # Python 3.14+ refuses unfiltered extraction by default. The
            # archive comes from the device we just read, but the filter is
            # the documented safe form (no absolute/escaping paths).
            try:
                tar.extractall(workdir, filter="data")
            except TypeError:  # Python < 3.12 has no filter argument
                tar.extractall(workdir)
    return workdir / "revision.json"


def read_revision_from_dir(edge_dir: Path, revision_id: str, workdir: Path) -> Path:
    if not SAFE_ID.match(revision_id):
        fail(f"unsafe revision id: {revision_id!r}")
    source = edge_dir / "revisions" / f"{revision_id}.json"
    if not source.is_file():
        fail(f"no such revision in {edge_dir}: {revision_id}")
    (workdir / "revision.json").write_bytes(source.read_bytes())
    for asset in json.loads(source.read_text("utf-8")).get("assets", []):
        asset_id = asset.get("asset_id", "")
        if not SAFE_ID.match(asset_id):
            fail(f"unsafe asset id in the revision: {asset_id!r}")
        blob = edge_dir / "assets" / asset_id
        if not blob.is_file():
            fail(f"the revision names an asset that is not there: {asset_id}")
        (workdir / "assets").mkdir(parents=True, exist_ok=True)
        (workdir / "assets" / asset_id).write_bytes(blob.read_bytes())
    return workdir / "revision.json"


# --- Core (HTTP) --------------------------------------------------------------


def request_json(
    url: str,
    token: str,
    method: str = "GET",
    body: dict | None = None,
    raw: bytes | None = None,
    headers: dict | None = None,
) -> tuple[int, bytes]:
    data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", f"Bearer {token}")
    if data is not None and "Content-Type" not in (headers or {}):
        request.add_header("Content-Type", "application/json")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()


def build_manifest(revision: dict, workdir: Path) -> dict:
    try:
        verify_revision(revision, workdir)
    except ValueError as exc:
        fail(str(exc))
    artifacts = []
    for asset in revision.get("assets", []):
        asset_id = asset["asset_id"]
        blob = workdir / "assets" / asset_id
        if not blob.is_file():
            fail(f"asset bytes are missing: {asset_id}")
        actual = sha256_file(blob)
        if actual != asset["sha256"]:
            fail(
                f"asset {asset_id} does not match the sealed digest "
                f"(revision {asset['sha256'][:12]}…, file {actual[:12]}…)"
            )
        artifacts.append(
            {
                "artifact_id": asset_id,
                "sha256": actual,
                "size_bytes": blob.stat().st_size,
                "media_type": asset.get("media_type", "application/octet-stream"),
                "required": True,
                "source_kind": source_kind(asset.get("media_type", "application/octet-stream")),
                "observation_id": asset.get("observation_id"),
            }
        )
    manifest = {
        "schema_version": 1,
        # The collection id is derived from the revision: the same revision is
        # the same collection, so a retried run resumes instead of duplicating.
        "collection_id": revision["revision_id"],
        "flight_id": revision["flight_id"],
        "edge_boot_id": revision["edge_boot_id"],
        "session_id": None,
        "mission_revision_id": None,
        "producer": {"type": "PC_COLLECTOR", "id": "collector-01"},
        "sealed_revision_sha256": revision["revision_sha256"],
        "artifacts": artifacts,
    }
    return manifest


def upload(client: str, token: str, manifest: dict, workdir: Path) -> dict:
    status, raw = request_json(
        f"{client}/collections", token, method="POST", body=manifest
    )
    if status != 200:
        fail(f"the Core refused the collection ({status}): {raw.decode(errors='replace')}")
    opened = json.loads(raw)
    upload_id = opened["upload_id"]
    log(f"collection {manifest['collection_id']} opened ({opened['status']}), upload {upload_id}")

    for artifact in manifest["artifacts"]:
        asset_id = artifact["artifact_id"]
        blob = workdir / "assets" / asset_id
        payload = blob.read_bytes()
        for offset in range(0, max(len(payload), 1), CHUNK_BYTES):
            chunk = payload[offset : offset + CHUNK_BYTES]
            end = offset + len(chunk) - 1
            status, raw = request_json(
                f"{client}/ingest-sessions/{upload_id}/objects/{asset_id}",
                token,
                method="PUT",
                raw=chunk,
                headers={
                    "Content-Range": f"bytes {offset}-{end}/{len(payload)}",
                    "Content-Type": "application/octet-stream",
                },
            )
            if status != 200:
                fail(
                    f"chunk upload failed for {asset_id} ({status}): "
                    f"{raw.decode(errors='replace')}"
                )
        log(f"  uploaded {asset_id} ({len(payload)} bytes)")

    status, raw = request_json(
        f"{client}/collections/{manifest['collection_id']}/commit", token, method="POST"
    )
    if status != 200:
        fail(
            f"the Core did not issue a receipt ({status}): "
            f"{raw.decode(errors='replace')}"
        )
    return json.loads(raw)


def retention_dry_run(
    core_url: str,
    token: str,
    manifest: dict,
    receipt: dict,
) -> dict:
    """The deletion-eligibility dry run. NOTHING is deleted.

    The facts we cannot see (the RC's unsent opinions, the retention policy)
    are reported as **unknown**, and an unknown protects the asset: the
    verdict is "eligible 0" until those are settled. That is the honest
    answer today, not a missing feature.
    """
    from retention_policy import (  # local import: the policy is a pure module
        AssetFacts,
        HoldFacts,
        ReceiptFacts,
        evaluate,
    )

    assets = [
        AssetFacts(
            asset_id=artifact["artifact_id"],
            size_bytes=artifact["size_bytes"],
            sha256=artifact["sha256"],
        )
        for artifact in manifest.get("artifacts", [])
    ]
    receipt_facts = ReceiptFacts(
        exists=True,
        sealed_revision_sha256=receipt.get("sealed_revision_sha256", ""),
        required_artifact_ids=tuple(receipt.get("required_artifact_ids", [])),
        artifact_set_sha256=receipt.get("artifact_set_sha256", ""),
    )
    holds = HoldFacts(
        rc_holds_confirmed=False,
        policy_approved=False,
        notes=(
            "RC의 미전송 의견·오프라인 검토 대기 자료는 이 수집기가 볼 수 없다",
            "보존 기간·hold·사건 보존 조건은 아직 승인되지 않았다",
        ),
    )
    report = evaluate(assets, receipt_facts, holds)
    body = report.as_dict()
    # The Core's own view of what it can prove (kept separate from what it
    # cannot see). If the endpoint is unavailable we keep the local reasons
    # rather than pretending the server said something.
    view = _core_retention_view(core_url, token, receipt.get("collection_id", ""))
    if view is not None:
        body["core_view"] = {
            "proven": view.get("proven", []),
            "unproven": view.get("unproven", []),
            "eligible": view.get("eligible", False),
        }
    body["note"] = (
        "dry-run only — 삭제 실행은 별도 승인 후 별도 경로에서 한다"
    )
    return body


def _core_retention_view(core_url: str, token: str, collection_id: str) -> dict | None:
    if not collection_id:
        return None
    try:
        status, raw = request_json(
            f"{core_url}/collections/{collection_id}/retention-view", token
        )
    except Exception:
        return None
    if status != 200:
        return None
    try:
        return json.loads(raw)
    except Exception:
        return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--edge-ssh", help="SSH alias/host of the Edge (e.g. ksi-manifold)")
    source.add_argument("--edge-dir", help="a local directory holding the Edge's evidence export")
    parser.add_argument("--edge-state-dir", default="/open_app/ksi-safety-edge/state")
    parser.add_argument("--revision", required=True, help="the sealed revision id to collect")
    parser.add_argument("--core-url", required=True, help="e.g. http://127.0.0.1:8000/api/v1/edge")
    parser.add_argument("--token", required=True, help="the Core access token")
    parser.add_argument("--receipt-out", type=Path,
                        help="atomically save the verified custody receipt; never replace other bytes")
    parser.add_argument(
        "--retention-dry-run",
        action="store_true",
        help=(
            "삭제 적격성 dry-run만 수행(영수증 확인 + 사유). 아무것도 지우지 않는다."
        ),
    )
    parser.add_argument(
        "--keep-local",
        action="store_true",
        help="keep the local copy of the collected bytes (default: kept — removal is a separate decision)",
    )
    args = parser.parse_args(argv)

    with tempfile.TemporaryDirectory(prefix="edge-collect-") as tmp:
        workdir = Path(tmp)
        if args.edge_ssh:
            log(f"reading the sealed revision {args.revision} from {args.edge_ssh}")
            read_revision_over_ssh(
                args.edge_ssh, args.edge_state_dir, args.revision, workdir
            )
        else:
            log(f"reading the sealed revision {args.revision} from {args.edge_dir}")
            read_revision_from_dir(Path(args.edge_dir), args.revision, workdir)

        revision = json.loads((workdir / "revision.json").read_text("utf-8"))
        manifest = build_manifest(revision, workdir)
        log(
            f"collection covers {len(manifest['artifacts'])} assets "
            f"(sealed revision {revision['revision_sha256'][:12]}…)"
        )
        receipt = upload(args.core_url, args.token, manifest, workdir)
        try:
            verify_custody_receipt(receipt, manifest)
            if args.receipt_out:
                save_immutable_json(args.receipt_out, receipt)
        except ValueError as exc:
            fail(f"custody proof could not be verified/saved: {exc}")
        log("CUSTODY RECEIPT")
        print(json.dumps(receipt, indent=2, ensure_ascii=False))
        log(
            "the local copy is kept: a receipt is not a deletion token "
            "(removal is a separate, approved decision)"
        )
        if args.retention_dry_run:
            report = retention_dry_run(args.core_url, args.token, manifest, receipt)
            log("RETENTION DRY-RUN (nothing is deleted)")
            print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
