"""Correlate a pulled RC device store with Core READ responses.

Purpose (L1): after a real RC session, prove what the RC actually recorded is
actually reachable from the Core read API. This module compares exactly two
things — (a) records present in the pulled RC store and (b) rows the Core
read API returned. It never infers upload success, never invents rows for
objects the store does not contain, and reports mismatches as-is.

Statuses per check:
- OK         — record found and consistent
- MISSING    — RC recorded it, Core does not return it
- MISMATCH   — both sides present but they contradict (e.g. RC synced vs
               Core not committed, digest differs, evidence subset broken)
- PENDING    — RC has it but has not uploaded it yet (expected state)
- UNKNOWN    — the comparison cannot be made from available data
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from .rc_store_read import DeviceStore
from .rc_upload import session_id_for

SAMPLE_CAP = 10


@dataclass(frozen=True)
class Check:
    name: str
    status: str  # OK | MISSING | MISMATCH | PENDING | UNKNOWN
    detail: str = ""


@dataclass
class FlightReport:
    flight_id: str
    checks: list[Check] = field(default_factory=list)
    upload_state: str | None = None
    completeness: str | None = None

    @property
    def verdict(self) -> str:
        if any(c.status in ("MISSING", "MISMATCH") for c in self.checks):
            return "DISCREPANCY"
        return "OK"


@dataclass
class Report:
    store_root: str
    flights: list[FlightReport] = field(default_factory=list)
    parse_notes: list[str] = field(default_factory=list)

    @property
    def verdict(self) -> str:
        return (
            "DISCREPANCY"
            if any(f.verdict == "DISCREPANCY" for f in self.flights)
            else "OK"
        )


class CoreReads(Protocol):
    """Read-only Core access needed for correlation (404 -> None)."""

    def flight(self, flight_id: str) -> dict | None: ...
    def artifacts(self, flight_id: str) -> list[dict]: ...
    def observations(self, flight_id: str) -> list[dict]: ...
    def events(self, flight_id: str) -> list[dict]: ...
    def evidence_ids(self, event_id: str, listed_event: dict) -> list[str] | None: ...
    def annotation_ids(self, event_id: str) -> list[str] | None: ...
    def sessions(self, flight_id: str) -> list[dict] | None: ...


def correlate(
    store: DeviceStore,
    core: CoreReads,
    flight_ids: list[str] | None = None,
) -> Report:
    report = Report(store_root=str(store.root), parse_notes=list(store.parse_notes))
    selected = flight_ids or list(store.flights.keys())
    for flight_id in selected:
        report.flights.append(_correlate_flight(store, core, flight_id))
    return report


def _correlate_flight(store: DeviceStore, core: CoreReads, flight_id: str) -> FlightReport:
    report = FlightReport(flight_id=flight_id)
    core_flight = core.flight(flight_id)
    if core_flight is None:
        report.checks.append(Check("flight", "MISSING", "Core /flights/{id} 404"))
        return report
    report.upload_state = core_flight.get("upload_state")
    report.completeness = core_flight.get("completeness")
    report.checks.append(
        Check("flight", "OK", f"upload_state={report.upload_state}")
    )

    _check_artifacts(store, core, flight_id, report)
    _check_observations(store, core, flight_id, report)
    _check_events(store, core, flight_id, report)
    _check_annotations(store, core, flight_id, report)
    _check_sessions(store, core, flight_id, report)
    return report


def _check_artifacts(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    local = store.artifacts_for(flight_id)
    core_items = {a.get("artifact_id"): a for a in core.artifacts(flight_id)}
    missing, mismatched = [], []
    for aid, entry in sorted(local.items()):
        complete = store.artifact_complete.get(aid, False)
        synced = aid in store.synced
        item = core_items.get(aid)
        if item is None:
            if complete:
                missing.append(aid)
            continue
        if synced and not item.get("committed"):
            mismatched.append(f"{aid} (RC synced, Core committed=false)")
        declared = entry.node.get("sha256")
        if declared and item.get("sha256") and declared != item["sha256"]:
            mismatched.append(f"{aid} (sha256 differs)")
        if item.get("committed") and not complete:
            mismatched.append(f"{aid} (Core committed, store bytes incomplete)")
    status = "OK"
    detail = f"store {len(local)} · core {len(core_items)}"
    if missing:
        status = "MISSING"
        detail += f" · 미도달 {len(missing)}: {', '.join(missing[:SAMPLE_CAP])}"
    if mismatched:
        status = "MISMATCH"
        detail += f" · 모순 {len(mismatched)}: {'; '.join(mismatched[:SAMPLE_CAP])}"
    report.checks.append(Check("artifacts", status, detail))


def _check_observations(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    local = set(store.observations_for(flight_id).keys())
    core_ids = {
        o.get("observation_id") for o in core.observations(flight_id)
        if isinstance(o.get("observation_id"), str)
    }
    missing = sorted(local - core_ids)
    extra = sorted(core_ids - local)
    status = "OK"
    detail = f"store {len(local)} · core {len(core_ids)}"
    if missing:
        status = "MISSING"
        detail += f" · 미도달 {len(missing)}: {', '.join(missing[:SAMPLE_CAP])}"
    if extra:
        detail += f" · Core에만 있음 {len(extra)} (스토어 pull 시점 이후 업로드 가능)"
    report.checks.append(Check("observations", status, detail))


def _check_events(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    local = store.events_for(flight_id)
    core_items = {
        e.get("event_id"): e for e in core.events(flight_id)
        if isinstance(e.get("event_id"), str)
    }
    missing = sorted(set(local) - set(core_items))
    mismatch: list[str] = []
    unverified = 0
    for eid, entry in sorted(local.items()):
        item = core_items.get(eid)
        if item is None:
            continue
        declared = entry.node.get("evidence_artifact_ids") or []
        if declared:
            core_evidence = item.get("evidence_artifact_ids")
            if core_evidence is None:
                core_evidence = core.evidence_ids(eid, item)
            if core_evidence is None:
                unverified += 1
                continue
            missing_evidence = sorted(set(declared) - set(core_evidence))
            if missing_evidence:
                mismatch.append(f"{eid} (evidence {', '.join(missing_evidence[:3])})")
    status = "OK"
    detail = f"store {len(local)} · core {len(core_items)}"
    if missing:
        status = "MISSING"
        detail += f" · 미도달 {len(missing)}: {', '.join(missing[:SAMPLE_CAP])}"
    if mismatch:
        status = "MISMATCH"
        detail += f" · 증거 연결 모순 {len(mismatch)}: {'; '.join(mismatch[:SAMPLE_CAP])}"
    elif unverified:
        status = "UNKNOWN"
        detail += f" · 증거 연결 확인 불가 {unverified}"
    report.checks.append(Check("events", status, detail))


def _check_annotations(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    local = store.annotations_for(flight_id)
    if not local:
        report.checks.append(Check("annotations", "OK", "스토어에 의견 없음"))
        return
    missing_uploaded, missing_pending = [], []
    unknown_events = 0
    cache: dict[str, list[str] | None] = {}
    for aid, node in sorted(local.items()):
        event_id = node.get("event_id")
        if not isinstance(event_id, str):
            continue
        if event_id not in cache:
            cache[event_id] = core.annotation_ids(event_id)
        core_ids = cache[event_id]
        if core_ids is None:
            unknown_events += 1
            continue
        if aid in core_ids:
            continue
        if aid in store.annotation_uploads:
            missing_uploaded.append(aid)
        else:
            missing_pending.append(aid)
    status = "OK"
    detail = f"store {len(local)}"
    if missing_uploaded:
        status = "MISMATCH"
        detail += f" · 업로드 영수증 있으나 Core에 없음 {len(missing_uploaded)}: {', '.join(missing_uploaded[:SAMPLE_CAP])}"
    if missing_pending:
        if status == "OK":
            status = "PENDING"
        detail += f" · RC 대기(pending) {len(missing_pending)}"
    if unknown_events:
        if status == "OK":
            status = "UNKNOWN"
        detail += f" · 의견 조회 실패 이벤트 {unknown_events}"
    report.checks.append(Check("annotations", status, detail))


def _check_sessions(
    store: DeviceStore, core: CoreReads, flight_id: str, report: FlightReport
) -> None:
    boots = set(store.boots_by_flight.get(flight_id, set()))
    sessions = core.sessions(flight_id)
    if sessions is None:
        report.checks.append(Check("sessions", "UNKNOWN", "Core 세션 조회 실패"))
        return
    core_ids = {
        s.get("session_id") for s in sessions if isinstance(s.get("session_id"), str)
    }
    if not boots:
        report.checks.append(
            Check("sessions", "UNKNOWN", f"core {len(core_ids)} · 스토어에 부트 기록 없음")
        )
        return
    expected = {session_id_for(flight_id, boot): boot for boot in boots}
    missing = sorted(sid for sid, _ in expected.items() if sid not in core_ids)
    status = "OK" if not missing else "MISSING"
    detail = f"store boots {len(boots)} · core sessions {len(core_ids)}"
    if missing:
        detail += f" · 미도달 {len(missing)}: {', '.join(missing[:SAMPLE_CAP])}"
    report.checks.append(Check("sessions", status, detail))


def render_text(report: Report, core_url: str) -> str:
    """Human-readable evidence text (the same facts as the dataclasses)."""
    lines = [
        "# RC → Core 대조 보고서",
        f"store: {report.store_root}",
        f"core: {core_url}",
        f"판정: {report.verdict}",
    ]
    if report.parse_notes:
        lines.append(f"스토어 파싱 주의 {len(report.parse_notes)}건:")
        lines.extend(f"- {note}" for note in report.parse_notes[:SAMPLE_CAP])
    for flight in report.flights:
        lines.append("")
        lines.append(f"## {flight.flight_id} — {flight.verdict}")
        lines.append(
            f"- upload_state={flight.upload_state} · completeness={flight.completeness}"
        )
        for check in flight.checks:
            detail = f" — {check.detail}" if check.detail else ""
            lines.append(f"- {check.name}: {check.status}{detail}")
    lines.append("")
    lines.append(
        "이 보고서는 RC 저장소 기록과 Core 응답의 일치만 판정한다. 현장 원본의 진위, "
        "운영 인증, 안전 판정은 포함하지 않는다."
    )
    return "\n".join(lines)
