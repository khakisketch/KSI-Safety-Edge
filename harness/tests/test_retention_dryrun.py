"""삭제 적격성 dry-run — 순수 판정 회귀.

고정하는 것:
 ① 하나라도 확인되지 않으면 **적격이 아니다**(fail-closed). "모른다"는
    "지워도 된다"의 근거가 되지 않는다.
 ② 영수증이 있어도 RC 측 보호 조건·보존 정책이 확인되지 않으면 보호된다.
 ③ 다른 리비전이 참조하는 공통 asset은 보호된다.
 ④ dry-run은 **어떤 파일도 지우지 않는다**(판정만).
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))

from retention_policy import (  # noqa: E402
    REASON_NOT_IN_RECEIPT,
    REASON_NO_RECEIPT,
    REASON_POLICY_UNAPPROVED,
    REASON_RC_HOLDS_UNKNOWN,
    REASON_SHARED_REVISION,
    AssetFacts,
    HoldFacts,
    ReceiptFacts,
    evaluate,
)


def _asset(asset_id: str = "a1", size: int = 100) -> AssetFacts:
    return AssetFacts(asset_id=asset_id, size_bytes=size, sha256="b" * 64)


def _receipt(required=("a1",)) -> ReceiptFacts:
    return ReceiptFacts(
        exists=True,
        sealed_revision_sha256="c" * 64,
        required_artifact_ids=tuple(required),
        artifact_set_sha256="d" * 64,
    )


def _holds(rc: bool = True, policy: bool = True) -> HoldFacts:
    return HoldFacts(rc_holds_confirmed=rc, policy_approved=policy)


def test_without_a_receipt_nothing_is_eligible():
    report = evaluate([_asset()], ReceiptFacts(), _holds())
    assert report.eligible_count == 0
    assert report.reclaimable_bytes == 0
    assert report.protected_bytes == 100
    assert REASON_NO_RECEIPT in report.assets[0].reasons


def test_an_asset_outside_the_receipts_required_set_is_protected():
    report = evaluate([_asset("other")], _receipt(), _holds())
    assert report.eligible_count == 0
    assert REASON_NOT_IN_RECEIPT in report.assets[0].reasons


def test_unknown_holds_protect_even_with_a_receipt():
    report = evaluate([_asset()], _receipt(), _holds(rc=False, policy=False))
    assert report.eligible_count == 0
    assert REASON_RC_HOLDS_UNKNOWN in report.assets[0].reasons
    assert REASON_POLICY_UNAPPROVED in report.assets[0].reasons


def test_a_shared_asset_is_protected():
    asset = AssetFacts(
        asset_id="a1",
        size_bytes=100,
        sha256="b" * 64,
        referenced_by_other_revisions=True,
    )
    report = evaluate([asset], _receipt(), _holds())
    assert report.eligible_count == 0
    assert REASON_SHARED_REVISION in report.assets[0].reasons


def test_everything_confirmed_makes_the_asset_eligible():
    report = evaluate([_asset()], _receipt(), _holds())
    assert report.eligible_count == 1
    assert report.reclaimable_bytes == 100
    assert report.protected_bytes == 0
    assert report.assets[0].reasons == ()


def test_the_report_never_claims_a_deletion():
    report = evaluate([_asset()], _receipt(), _holds())
    body = report.as_dict()
    assert body["dry_run"] is True
    assert body["deleted"] == 0
    # The policy module itself must not contain a deletion call.
    policy_source = (
        Path(__file__).resolve().parents[2] / "scripts" / "retention_policy.py"
    ).read_text(encoding="utf-8")
    assert "Path.unlink" not in policy_source
    assert "os.remove" not in policy_source
    assert "shutil" not in policy_source
    assert "os.unlink" not in policy_source
