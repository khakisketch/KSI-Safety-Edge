"""Edge replay endpoint: mission handling + deterministic data emission."""

from __future__ import annotations

import base64
import copy
import uuid

from . import mop
from .mop import _Unacked, make_envelope, new_msg_id

CHUNK_SIZE = 512


class EdgeReplay:
    """In-proc Edge side of the MOP exchange.

    Holds prepared bundles, per-boot applied state, and fixture data.
    PREPARE validates the bundle digest (mismatch → REJECTED). APPLY is
    idempotent by msg_id (same bundle → SAME application_id resent).
    ``reboot()`` rotates edge_boot_id, clears applied state and bumps the
    tracker epoch. ``emit_all`` sends data in deterministic order with
    per-channel seqs.
    """

    def __init__(self, flight_id: str, clock, boot_index: int = 0, boot_id: str | None = None) -> None:
        self.flight_id = flight_id
        self.clock = clock
        self.boot_index = int(boot_index)
        self.edge_boot_id = boot_id or self._boot_id()
        self.tracker_epoch = 0
        self.prepared: dict[str, dict] = {}
        self.applied_bundle_sha256: str | None = None
        self.applied_bundle_id: str | None = None
        self.applications: dict[str, dict] = {}  # msg_id -> MISSION_APPLIED payload
        self.running = False
        self.seq = {c: 0 for c in mop.CHANNELS}
        self.outbox: list = []
        self.unacked: dict[str, _Unacked] = {}
        self.seen_msg_ids: set[str] = set()
        self.unrecoverable: dict[str, set[int]] = {}
        self._msg_counter = 0
        self.last_responses: dict[str, dict] = {}

    # -- identity ---------------------------------------------------------
    def _boot_id(self) -> str:
        return str(uuid.uuid5(mop.NAMESPACE, f"boot|{self.flight_id}|{self.boot_index}"))

    def _next_msg_id(self, *parts: str) -> str:
        self._msg_counter += 1
        return new_msg_id("edge", self.flight_id, self.edge_boot_id, str(self._msg_counter), *parts)

    # -- sending ------------------------------------------------------------
    def _send(self, channel: str, type: str, payload: dict, *, reliable: bool = True) -> dict:
        seq = None
        if type != "ACK":
            self.seq[channel] += 1
            seq = self.seq[channel]
        env = make_envelope(
            msg_id=self._next_msg_id(channel, type, str(seq)),
            channel=channel,
            type=type,
            flight_id=self.flight_id,
            edge_boot_id=self.edge_boot_id,
            seq=seq,
            ack_of=payload.get("ack_of") if type == "ACK" else None,
            payload=payload,
            sent_monotonic_ns=self.clock.advance(ms=1),
        )
        self.outbox.append(env)
        if reliable and type not in ("ACK", "HEARTBEAT"):
            self.unacked[env["msg_id"]] = _Unacked(envelope=copy.deepcopy(env), sent_round=0)
        elif reliable and type == "HEARTBEAT":
            self.unacked[env["msg_id"]] = _Unacked(envelope=copy.deepcopy(env), sent_round=0)
        return env

    def _ack(self, msg_id: str, channel: str) -> None:
        self._send(channel, "ACK", {"ack_of": msg_id}, reliable=False)

    def mark_unrecoverable(self, max_attempts: int = 1) -> None:
        """Move still-unacked DATA-channel seqs into unrecoverable gaps.

        Only entries that were actually retried (attempts > 1) or that
        exhausted their attempts count — messages still awaiting their
        first retry stay queued for the next pump.
        """
        for mid in list(self.unacked):
            rec = self.unacked[mid]
            env = rec.envelope
            if env["channel"] in mop.DATA_CHANNELS and env.get("seq") is not None:
                if rec.attempts > 1 or rec.attempts >= max_attempts:
                    self.unrecoverable.setdefault(env["channel"], set()).add(int(env["seq"]))
                    del self.unacked[mid]

    # -- receiving (RC→Edge) --------------------------------------------------
    def receive(self, env: dict) -> str:
        if env["msg_id"] in self.seen_msg_ids:
            # Idempotent control: resend the stored response when the
            # original reply was lost (same application_id preserved).
            if env["type"] in ("MISSION_PREPARE", "MISSION_APPLY") and env["msg_id"] in self.last_responses:
                self.outbox.append(copy.deepcopy(self.last_responses[env["msg_id"]]))
            return "dedup"
        self.seen_msg_ids.add(env["msg_id"])
        handler = {
            "MISSION_PREPARE": self._on_prepare,
            "MISSION_APPLY": self._on_apply,
            "MISSION_STATUS_QUERY": self._on_status_query,
            "SESSION_START": self._on_session,
            "SESSION_STOP": self._on_session,
            "ACK": self._on_ack,
        }.get(env["type"])
        if handler is None:
            return "error"
        try:
            return handler(env)
        except (KeyError, ValueError, TypeError):
            return "error"

    def _on_ack(self, env: dict) -> str:
        self.unacked.pop(env["payload"].get("ack_of", ""), None)
        return "ack"

    def _check_bundle(self, bundle: dict) -> list[str]:
        from ksi_contracts import verify_digest

        reasons = []
        if not verify_digest(bundle, "bundle_sha256"):
            reasons.append("DIGEST_MISMATCH: bundle_sha256 does not match recomputed digest")
        snap = bundle.get("mission_snapshot", {})
        policy = bundle.get("risk_policy", {})
        sites = {bundle.get("site_id"), snap.get("site_id"), policy.get("site_id")} - {None}
        if len(sites) > 1:
            reasons.append("BUNDLE_SITE_MISMATCH")
        return reasons

    def _on_prepare(self, env: dict) -> str:
        bundle = env["payload"].get("bundle", {})
        reasons = self._check_bundle(bundle)
        if reasons:
            resp = self._send("CONTROL_RELIABLE", "MISSION_APPLY_REJECTED", {"reasons": reasons})
        else:
            self.prepared[bundle["bundle_id"]] = copy.deepcopy(bundle)
            resp = self._send(
                "CONTROL_RELIABLE",
                "MISSION_PREPARED",
                {"bundle_sha256": bundle["bundle_sha256"], "supported": True, "exclusions": []},
            )
        self.last_responses[env["msg_id"]] = copy.deepcopy(resp)
        self._ack(env["msg_id"], env["channel"])
        return "stored"

    def _on_apply(self, env: dict) -> str:
        bundle_id = env["payload"].get("bundle_id")
        bundle_sha = env["payload"].get("bundle_sha256")
        bundle = self.prepared.get(bundle_id or "")
        if bundle is None or bundle.get("bundle_sha256") != bundle_sha:
            resp = self._send(
                "CONTROL_RELIABLE", "MISSION_APPLY_REJECTED",
                {"reasons": ["UNKNOWN_BUNDLE: bundle not prepared or digest differs"]},
            )
            self.last_responses[env["msg_id"]] = copy.deepcopy(resp)
            self._ack(env["msg_id"], env["channel"])
            return "stored"
        application_id = str(uuid.uuid5(
            mop.NAMESPACE, f"apply|{self.flight_id}|{bundle_id}|{self.edge_boot_id}"))
        self.applied_bundle_sha256 = bundle_sha
        self.applied_bundle_id = bundle_id
        self.running = True
        payload = {"application_id": application_id, "effective_edge_seq": self.seq["EVENT_RELIABLE"]}
        self.applications[env["msg_id"]] = copy.deepcopy(payload)
        resp = self._send("CONTROL_RELIABLE", "MISSION_APPLIED", payload)
        self.last_responses[env["msg_id"]] = copy.deepcopy(resp)
        self._ack(env["msg_id"], env["channel"])
        return "stored"

    def _on_status_query(self, env: dict) -> str:
        app_id = None
        for payload in self.applications.values():
            app_id = payload["application_id"]
        self._send(
            "CONTROL_RELIABLE",
            "MISSION_STATUS",
            {
                "applied_bundle_sha256": self.applied_bundle_sha256,
                "application_id": app_id,
                "edge_boot_id": self.edge_boot_id,
                "running": self.running,
                "effective_edge_seq": self.seq["EVENT_RELIABLE"],
            },
        )
        self._ack(env["msg_id"], env["channel"])
        return "stored"

    def _on_session(self, env: dict) -> str:
        self.running = env["type"] == "SESSION_START"
        self._ack(env["msg_id"], env["channel"])
        return "stored"

    # -- lifecycle --------------------------------------------------------------
    def reboot(self, next_boot_id: str | None = None) -> str:
        """New boot: rotated id, cleared applied state, tracker_epoch++."""
        self.boot_index += 1
        self.edge_boot_id = next_boot_id or self._boot_id()
        self.tracker_epoch += 1
        self.applied_bundle_sha256 = None
        self.applied_bundle_id = None
        self.applications = {}
        self.running = False
        self.seq = {c: 0 for c in mop.CHANNELS}
        self.unrecoverable = {}
        return self.edge_boot_id

    # -- emission -----------------------------------------------------------------
    def emit_observations(self, observations: list) -> None:
        for obs in observations:
            self._send("EVENT_RELIABLE", "OBSERVATION", {"observation": copy.deepcopy(obs)})

    def emit_events(self, events: list) -> None:
        for event in events:
            self._send("EVENT_RELIABLE", "DETECTION_EVENT", {"event": copy.deepcopy(event)})

    def emit_artifacts(self, artifacts: list) -> None:
        for art in artifacts:
            self._send(
                "EVIDENCE_TRANSFER",
                "ARTIFACT_ANNOUNCE",
                {
                    "artifact_id": art["artifact_id"],
                    "flight_id": self.flight_id,
                    "source_kind": art["source_kind"],
                    "sha256": art["sha256"],
                    "size_bytes": art["size_bytes"],
                    "media_type": art["media_type"],
                    "segment_index": art["segment_index"],
                    "required": art["required"],
                    "edge_boot_id": self.edge_boot_id,
                },
            )
            data = art["bytes"]
            for off in range(0, len(data), CHUNK_SIZE):
                chunk = data[off:off + CHUNK_SIZE]
                self._send(
                    "EVIDENCE_TRANSFER",
                    "ARTIFACT_CHUNK",
                    {
                        "artifact_id": art["artifact_id"],
                        "offset": off,
                        "data_b64": base64.b64encode(chunk).decode(),
                    },
                )

    def emit_summary(self, summary: dict) -> None:
        self._send("EVENT_RELIABLE", "FLIGHT_SUMMARY", {"summary": copy.deepcopy(summary)})

    def emit_heartbeat(self, fps: float = 15.0) -> dict:
        gaps = [
            {"channel": ch, "seqs": sorted(seqs)}
            for ch, seqs in sorted(self.unrecoverable.items())
        ]
        return self._send(
            "HEARTBEAT",
            "HEARTBEAT",
            {
                "fps": fps,
                "applied_bundle_sha256": self.applied_bundle_sha256,
                "unrecoverable_gaps": gaps,
                "edge_boot_id": self.edge_boot_id,
            },
        )

    def emit_all(self, boot: dict, *, heartbeat: bool = True) -> None:
        self.emit_observations(boot.get("observations", []))
        self.emit_events(boot.get("events", []))
        self.emit_artifacts(boot.get("artifacts", []))
        if boot.get("summary") is not None:
            self.emit_summary(boot["summary"])
        if heartbeat:
            self.emit_heartbeat()
