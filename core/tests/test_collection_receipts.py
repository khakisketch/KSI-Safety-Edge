"""Collection receipt contract: producer-neutral central collection.

WHAT IS PINNED HERE
    A collection is a **custody** statement, not a completeness claim:

    ① open is idempotent by collection id (same content resumes; different
       content is a conflict — an id must not point at two contents);
    ② bytes travel over the proven resumable chunk path, and the commit
       verifies coverage **and** the announced digest;
    ③ a missing or damaged required artifact yields **no receipt** and names
       what is missing;
    ④ the receipt binds the exact required artifact set (ids + digests) and
       is immutable — re-committing returns the same receipt;
    ⑤ one artifact id means one byte stream: the same id with a different
       digest is refused globally.
"""

from __future__ import annotations

import hashlib

from core_helpers import RC, fresh_uuid


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _collection_manifest(artifacts: list[dict], collection_id: str | None = None):
    return {
        "schema_version": 1,
        "collection_id": collection_id or fresh_uuid(),
        "flight_id": fresh_uuid(),
        "edge_boot_id": fresh_uuid(),
        "session_id": fresh_uuid(),
        "mission_revision_id": fresh_uuid(),
        "producer": {"type": "PC_COLLECTOR", "id": "collector-01"},
        "sealed_revision_sha256": "c" * 64,
        "artifacts": artifacts,
    }


def _artifact(data: bytes, artifact_id: str | None = None, required: bool = True):
    return {
        "artifact_id": artifact_id or fresh_uuid(),
        "sha256": _sha256(data),
        "size_bytes": len(data),
        "media_type": "application/x-raw-rgb24",
        "required": required,
        "source_kind": "EDGE_SOURCE_FRAME",
        "observation_id": fresh_uuid(),
    }


def _open(client, manifest):
    return client.post("/collections", token=RC, json=manifest)


def _put(client, upload_id: str, artifact_id: str, data: bytes):
    total = len(data)
    return client.put(
        f"/ingest-sessions/{upload_id}/objects/{artifact_id}",
        token=RC,
        headers={"Content-Range": f"bytes 0-{total - 1}/{total}"},
        content=data,
    )


def test_collection_issues_a_receipt_for_the_exact_required_set(client):
    data = b"edge-source-frame-bytes" * 10
    artifact = _artifact(data)
    manifest = _collection_manifest([artifact])

    opened = _open(client, manifest)
    assert opened.status_code == 200, opened.text
    body = opened.json()
    assert body["status"] == "NEW"
    assert body["missing_artifact_ids"] == [artifact["artifact_id"]]

    assert _put(client, body["upload_id"], artifact["artifact_id"], data).status_code == 200

    committed = client.post(
        f"/collections/{manifest['collection_id']}/commit", token=RC
    )
    assert committed.status_code == 200, committed.text
    receipt = committed.json()
    assert receipt["collection_id"] == manifest["collection_id"]
    assert receipt["required_artifact_ids"] == [artifact["artifact_id"]]
    assert receipt["artifact_set_sha256"]
    assert receipt["receipt_sha256"]
    assert receipt["sealed_revision_sha256"] == manifest["sealed_revision_sha256"]
    assert receipt["producer"] == manifest["producer"]

    fetched = client.get(
        f"/collections/{manifest['collection_id']}/receipt", token=RC
    )
    assert fetched.status_code == 200
    assert fetched.json() == receipt


def test_missing_artifact_means_no_receipt(client):
    data = b"frame"
    artifact = _artifact(data)
    # The other required artifact is declared up front and never arrives.
    other = _artifact(b"second")
    manifest = _collection_manifest([artifact, other])
    opened = _open(client, manifest)
    assert _put(client, opened.json()["upload_id"], artifact["artifact_id"], data).status_code == 200

    committed = client.post(
        f"/collections/{manifest['collection_id']}/commit", token=RC
    )
    assert committed.status_code == 409
    assert committed.json()["details"]["missing_artifact_ids"] == [other["artifact_id"]]
    missing = client.get(
        f"/collections/{manifest['collection_id']}/receipt", token=RC
    )
    assert missing.status_code == 404


def test_damaged_bytes_are_refused_at_commit(client):
    data = b"frame-bytes"
    artifact = _artifact(data)
    manifest = _collection_manifest([artifact])
    opened = _open(client, manifest)
    upload_id = opened.json()["upload_id"]
    # Same length, different content: the digest must not match.
    assert _put(client, upload_id, artifact["artifact_id"], b"XXXXX-bytes").status_code == 200

    committed = client.post(
        f"/collections/{manifest['collection_id']}/commit", token=RC
    )
    assert committed.status_code == 409
    assert committed.json()["details"]["missing_artifact_ids"] == [artifact["artifact_id"]]


def test_open_is_idempotent_and_conflicts_on_different_content(client):
    data = b"frame"
    artifact = _artifact(data)
    manifest = _collection_manifest([artifact])

    first = _open(client, manifest)
    assert first.status_code == 200
    again = _open(client, manifest)
    assert again.status_code == 200
    assert again.json()["status"] == "RESUMING"
    assert again.json()["upload_id"] == first.json()["upload_id"]

    other = _collection_manifest([_artifact(b"other")], collection_id=manifest["collection_id"])
    conflict = _open(client, other)
    assert conflict.status_code == 409
    assert conflict.json()["code"] == "CONFLICT"


def test_recommit_returns_the_same_receipt(client):
    data = b"frame-bytes"
    artifact = _artifact(data)
    manifest = _collection_manifest([artifact])
    opened = _open(client, manifest)
    _put(client, opened.json()["upload_id"], artifact["artifact_id"], data)

    first = client.post(f"/collections/{manifest['collection_id']}/commit", token=RC).json()
    second = client.post(f"/collections/{manifest['collection_id']}/commit", token=RC).json()
    assert first == second, "a receipt is immutable"


def test_artifact_id_reuse_with_a_different_digest_is_refused(client):
    data = b"frame-bytes"
    artifact = _artifact(data)
    first = _collection_manifest([artifact])
    opened = _open(client, first)
    _put(client, opened.json()["upload_id"], artifact["artifact_id"], data)
    assert client.post(
        f"/collections/{first['collection_id']}/commit", token=RC
    ).status_code == 200

    # A different collection presents the SAME artifact id with other bytes.
    second = _collection_manifest([_artifact(b"other bytes", artifact_id=artifact["artifact_id"])])
    conflict = _open(client, second)
    assert conflict.status_code == 409
    assert conflict.json()["details"]["reason"] == "ARTIFACT_ID_DIGEST_CONFLICT"


def test_collections_can_be_listed_for_a_flight(client):
    """RC는 "원본 근거가 중앙에 있는가"를 **영수증으로** 물어야 한다."""
    data = b"frame-bytes"
    artifact = _artifact(data)
    manifest = _collection_manifest([artifact])
    opened = _open(client, manifest)
    _put(client, opened.json()["upload_id"], artifact["artifact_id"], data)
    assert client.post(
        f"/collections/{manifest['collection_id']}/commit", token=RC
    ).status_code == 200

    listed = client.get(
        f"/collections?flight_id={manifest['flight_id']}", token=RC
    )
    assert listed.status_code == 200
    items = listed.json()["items"]
    assert len(items) == 1
    assert items[0]["collection_id"] == manifest["collection_id"]
    assert items[0]["required_artifact_count"] == 1
    assert items[0]["artifact_set_sha256"]
    assert items[0]["receipt_sha256"]

    # 다른 비행의 영수증은 보이지 않는다(사실이 분리된다).
    other = client.get("/collections?flight_id=11111111-1111-4111-8111-111111111111", token=RC)
    assert other.json()["items"] == []


def test_the_retention_view_separates_proven_from_unproven(client):
    """삭제 판정에 필요한 사실은 두 종류다 — Core가 증명하는 것과
    Core가 볼 수 없는 것. 둘을 합치면 한 줄 verdict가 되어 버린다."""
    data = b"frame-bytes"
    artifact = _artifact(data)
    manifest = _collection_manifest([artifact])
    opened = _open(client, manifest)
    _put(client, opened.json()["upload_id"], artifact["artifact_id"], data)
    client.post(f"/collections/{manifest['collection_id']}/commit", token=RC)

    view = client.get(
        f"/collections/{manifest['collection_id']}/retention-view", token=RC
    )
    assert view.status_code == 200
    body = view.json()
    assert body["eligible"] is False, "미확인 사실이 있으면 적격이 아니다"
    assert body["deleted"] == 0, "조회만 한다"
    assert body["proven"], "Core가 증명하는 것은 이름으로 남는다"
    assert body["unproven"], "Core가 볼 수 없는 것도 이름으로 남는다"
    assert body["reasons"] == body["unproven"]
    assert body["required_artifact_count"] == 1
    assert body["receipt_sha256"]

    # 영수증이 없으면 조회도 없다(존재 누설 없음).
    missing = client.get("/collections/00000000-0000-4000-8000-000000000000/retention-view", token=RC)
    assert missing.status_code == 404


def test_optional_artifact_missing_does_not_block_the_receipt(client):
    data = b"required-frame"
    required = _artifact(data)
    optional = _artifact(b"optional", required=False)
    manifest = _collection_manifest([required, optional])

    opened = _open(client, manifest)
    upload_id = opened.json()["upload_id"]
    _put(client, upload_id, required["artifact_id"], data)

    committed = client.post(f"/collections/{manifest['collection_id']}/commit", token=RC)
    assert committed.status_code == 200, committed.text
    receipt = committed.json()
    # The receipt binds the REQUIRED set only.
    assert receipt["required_artifact_ids"] == [required["artifact_id"]]
