"""삭제 적격성(dry-run) — **실제 삭제는 하지 않는다.**

WHAT THIS IS
    "이 asset을 지워도 되는가"를 **판정만** 하는 순수 로직이다. 이 모듈은
    파일을 지우지 않는다. 결과는 목록과 사유이며, 실제 삭제는 별도 승인 +
    별도 실행이다(ADR-0002: 영수증은 삭제 토큰이 아니다).

THE RULES (fail-closed)
    하나의 조건이라도 확인되지 않으면 **적격이 아니다**. "모른다"는
    "지워도 된다"의 근거가 될 수 없다.

    1. 중앙 영수증이 있다 — Core가 그 sealed revision의 필수 집합을
       커밋했고, 그 영수증이 이 asset id를 포함한다.
    2. 다른 리비전이 이 asset을 참조하지 않는다 — 공통 asset은 마지막
       의존관계까지 확인되어야 한다.
    3. RC 측 보호 조건이 확인되었다 — 미전송 의견·오프라인 검토 대기 자료가
       이 asset을 가리키지 않는다.
    4. 보존 정책이 승인되었다 — 보존 기간·hold·사건 보존 조건이 정해졌다.
    5. 진행 중인 쓰기·수집이 없다.

WHY 3·4 ARE CURRENTLY UNKNOWN
    RC의 미전송 의견과 보존 정책은 **이 수집기가 볼 수 없는 곳**에 있다
    (RC 로컬 저장소 / 운영 결정). 그래서 지금 이 판정은 honest하게
    "적격 0건"을 낸다 — 지워도 된다고 말할 근거가 아직 없다는 뜻이다.

NOT A DELETION PATH
    이 모듈을 호출해도 어떤 파일도 지워지지 않는다. `unlink`를 호출하는
    코드는 이 저장소에 없다.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# Reasons an asset is protected. Every one of them blocks deletion.
REASON_NO_RECEIPT = "중앙 영수증 없음"
REASON_NOT_IN_RECEIPT = "영수증의 필수 집합에 없음"
REASON_SHARED_REVISION = "다른 리비전이 참조"
REASON_RC_HOLDS_UNKNOWN = "RC 측 보호 조건 미확인(미전송 의견·오프라인 검토)"
REASON_POLICY_UNAPPROVED = "보존 정책 미승인"
REASON_WRITE_IN_FLIGHT = "진행 중 쓰기·수집 있음"
REASON_REVISION_MISSING = "봉인된 리비전을 찾을 수 없음"


@dataclass(frozen=True)
class AssetFacts:
    """One asset as the retention view knows it."""

    asset_id: str
    size_bytes: int
    sha256: str
    revision_ids: tuple[str, ...] = ()
    referenced_by_other_revisions: bool = False
    write_in_flight: bool = False


@dataclass(frozen=True)
class ReceiptFacts:
    """What the Core proved (or did not)."""

    exists: bool = False
    sealed_revision_sha256: str = ""
    required_artifact_ids: tuple[str, ...] = ()
    artifact_set_sha256: str = ""


@dataclass(frozen=True)
class HoldFacts:
    """Holds the collector cannot see. Unknown == protective."""

    rc_holds_confirmed: bool = False
    policy_approved: bool = False
    notes: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class AssetVerdict:
    asset_id: str
    size_bytes: int
    eligible: bool
    reasons: tuple[str, ...]


@dataclass(frozen=True)
class DryRunReport:
    assets: tuple[AssetVerdict, ...]
    receipt: ReceiptFacts
    holds: HoldFacts

    @property
    def eligible_count(self) -> int:
        return sum(1 for a in self.assets if a.eligible)

    @property
    def protected_bytes(self) -> int:
        return sum(a.size_bytes for a in self.assets if not a.eligible)

    @property
    def reclaimable_bytes(self) -> int:
        return sum(a.size_bytes for a in self.assets if a.eligible)

    def as_dict(self) -> dict:
        return {
            "dry_run": True,
            "deleted": 0,
            "assets": [
                {
                    "asset_id": a.asset_id,
                    "size_bytes": a.size_bytes,
                    "eligible": a.eligible,
                    "reasons": list(a.reasons),
                }
                for a in self.assets
            ],
            "eligible_count": self.eligible_count,
            "protected_bytes": self.protected_bytes,
            "reclaimable_bytes": self.reclaimable_bytes,
            "receipt": {
                "exists": self.receipt.exists,
                "sealed_revision_sha256": self.receipt.sealed_revision_sha256,
                "required_artifact_count": len(self.receipt.required_artifact_ids),
                "artifact_set_sha256": self.receipt.artifact_set_sha256,
            },
            "holds": {
                "rc_holds_confirmed": self.holds.rc_holds_confirmed,
                "policy_approved": self.holds.policy_approved,
                "notes": list(self.holds.notes),
            },
        }


def evaluate_asset(
    asset: AssetFacts,
    receipt: ReceiptFacts,
    holds: HoldFacts,
) -> AssetVerdict:
    """One asset's verdict. Fail-closed: any doubt protects it."""
    reasons: list[str] = []
    if not receipt.exists:
        reasons.append(REASON_NO_RECEIPT)
    elif asset.asset_id not in receipt.required_artifact_ids:
        reasons.append(REASON_NOT_IN_RECEIPT)
    if asset.referenced_by_other_revisions:
        reasons.append(REASON_SHARED_REVISION)
    if not holds.rc_holds_confirmed:
        reasons.append(REASON_RC_HOLDS_UNKNOWN)
    if not holds.policy_approved:
        reasons.append(REASON_POLICY_UNAPPROVED)
    if asset.write_in_flight:
        reasons.append(REASON_WRITE_IN_FLIGHT)
    return AssetVerdict(
        asset_id=asset.asset_id,
        size_bytes=asset.size_bytes,
        eligible=not reasons,
        reasons=tuple(reasons),
    )


def evaluate(
    assets: list[AssetFacts],
    receipt: ReceiptFacts,
    holds: HoldFacts,
) -> DryRunReport:
    """The whole dry-run. Nothing is deleted — this only decides."""
    return DryRunReport(
        assets=tuple(evaluate_asset(a, receipt, holds) for a in assets),
        receipt=receipt,
        holds=holds,
    )
