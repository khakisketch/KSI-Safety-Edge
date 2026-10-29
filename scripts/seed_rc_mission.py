"""RC가 실제로 쓸 수 있는 현장·구역·정책·번들을 Core에 심는다.

WHY: RC의 임무 화면은 (1) Core의 현장 목록, (2) 그 현장의 번들 목록으로
번들을 고른다. 그래서 **현장·구역·정책·번들이 같은 site_id를 공유해야** 화면에
번들이 보인다. Core가 site_id를 새로 만들면 그 id로 구역·정책·번들을 다시
맞춰야 한다(그리고 다이제스트를 다시 계산해야 한다).
"""

from __future__ import annotations

import json
import sys
import uuid
import urllib.error
import urllib.request

sys.path.insert(0, "harness/src")
sys.path.insert(0, "libs/ksi_contracts/src")

from ksi_contracts import set_digest  # noqa: E402
from ksi_harness import fixtures  # noqa: E402

BASE = "http://127.0.0.1:8011/api/v1/edge"
TOKEN = "tok-orgA-rc"


def post(path: str, body: dict) -> tuple[int, str]:
    request = urllib.request.Request(
        BASE + path, data=json.dumps(body).encode(), method="POST"
    )
    request.add_header("Authorization", f"Bearer {TOKEN}")
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode()


def main() -> int:
    world = fixtures.world("rc-live")
    existing_site = sys.argv[1] if len(sys.argv) > 1 else ""
    site = dict(world["site"])
    site["org_id"] = "org-a"
    site["center_lat"] = 37.5665
    site["center_lon"] = 126.9780
    site["radius_m"] = 300
    if existing_site:
        # The RC already has this site locally. The Core assigns its own id
        # for a NEW name, so we reuse the RC's site NAME: the RC's "현장
        # 동기화" then brings the Core's site (and its id) into the RC.
        name = sys.argv[2] if len(sys.argv) > 2 else None
        if name:
            site["name"] = name
        status, body = post("/sites", site)
        print("site:", status, body[:160])
        if status != 200:
            return 1
        site_id = json.loads(body)["site_id"]
    else:
        status, body = post("/sites", site)
        print("site:", status, body[:160])
        if status != 200:
            return 1
        site_id = json.loads(body)["site_id"]

    for zone in world["zones"]:
        payload = dict(zone)
        # 리비전은 불변이므로 내용이 바뀌면 새 revision id가 필요하다
        # (같은 id + 다른 내용 = 409).
        payload["revision_id"] = str(uuid.uuid4())
        # A NEW zone has no base revision (the Core expects null); an existing
        # one must build on the current revision. A fresh zone_id keeps the
        # seeding idempotent without fighting the immutable chain.
        payload["zone_id"] = str(uuid.uuid4())
        payload["base_revision_id"] = None
        payload["site_id"] = site_id
        payload["org_id"] = "org-a"
        payload = set_digest(payload, "content_sha256")
        status, body = post(f"/sites/{site_id}/zone-revisions", payload)
        if status == 409 and "expected " in body:
            # The Core's immutable chain: the new revision must build on the
            # CURRENT one. Retry once with the revision it names.
            expected = body.split("expected ")[1].split('"')[0]
            payload["base_revision_id"] = expected
            payload = set_digest(payload, "content_sha256")
            status, body = post(f"/sites/{site_id}/zone-revisions", payload)
        print("zone:", (status, body[:120]))

    policy = dict(world["policy"])
    policy["site_id"] = site_id
    # The chain field belongs to the REQUEST wrapper, not to the policy
    # document (the schema forbids it inside the policy).
    policy.pop("expected_parent_revision_id", None)
    policy = set_digest(policy, "content_sha256")
    status, body = post(
        f"/sites/{site_id}/risk-policies",
        {"expected_parent_revision_id": None, "policy": policy},
    )
    if status == 409 and "expected " in body:
        expected = body.split("expected ")[1].split('"')[0]
        status, body = post(
            f"/sites/{site_id}/risk-policies",
            {"expected_parent_revision_id": expected, "policy": policy},
        )
    print("policy:", (status, body[:200]))

    snapshot = dict(world["snapshot"])
    snapshot["site_id"] = site_id
    snapshot["zones"] = [z for z in snapshot.get("zones", [])]
    snapshot = set_digest(snapshot, "content_sha256")

    bundle = dict(world["bundle"])
    bundle["bundle_id"] = str(uuid.uuid4())
    bundle["site_id"] = site_id
    bundle["mission_snapshot"] = snapshot
    bundle["risk_policy"] = {
        k: v for k, v in policy.items() if k != "expected_parent_revision_id"
    }
    bundle = set_digest(bundle, "bundle_sha256")
    print("bundle:", post("/mission-bundles", bundle)[:2])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
