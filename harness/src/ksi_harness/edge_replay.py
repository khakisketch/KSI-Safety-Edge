"""Edge replay endpoint: mission handling + deterministic data emission."""

from __future__ import annotations

import base64
import copy
import uuid

from . import mop
from .mop import _Unacked, make_envelope, new_msg_id

CHUNK_SIZE = 512

# §2 runtime vocabulary (Edge reports, RC displays). The legacy ``running``
# bool keeps its old meaning ("start requested, not stopped"); execution
# evidence lives in these states + the counters below.
RUNTIME_STATES = (
    "REQUEST_ACCEPTED",
    "CONFIG_APPLIED",
    "STARTING",
    "RUNNING",
    "DEGRADED",
    "STOPPED",
)

# §3 degraded-reason codes.
DEGRADED_CODES = (
    "video_input_lost",
    "video_input_stale",
    "telemetry_absent",
    "telemetry_stale",
    "engine_failed",
    "zone_geometry_unavailable",
    "policy_install_rejected",
    "session_capacity_exceeded",
)

_INPUT_VALUES = ("ok", "stale", "lost", "failed", "absent")

# input_state value -> derived degraded code (None means healthy).
_INPUT_DERIVED = {
    ("video", "lost"): "video_input_lost",
    ("video", "stale"): "video_input_stale",
    ("telemetry", "absent"): "telemetry_absent",
    ("telemetry", "stale"): "telemetry_stale",
    ("engine", "failed"): "engine_failed",
}
_INPUT_DERIVED_CODES = frozenset(_INPUT_DERIVED.values())


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
        # -- §2/§3 simulated runtime (§4: explicit SESSION_START gates processing)
        self.runtime_epoch = self._runtime_epoch()
        self.runtime_state: str | None = None  # None == unknown (field omitted)
        self.config_applied = False
        self.effective_config_hash: str | None = None
        self.processed_frame_count = 0
        self._last_processed_ns: int | None = None
        self.degraded_reasons: list[str] = []
        self.input_state = {"video": "ok", "telemetry": "ok", "engine": "ok"}
        self.session_active = False
        self._command_seen = False
        self._session_ever_started = False
        self._install_rejected = False
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

    def _runtime_epoch(self) -> str:
        """Deterministic process-generation id; rotates on every reboot."""
        return str(uuid.uuid5(mop.NAMESPACE, f"runtime-epoch|{self.flight_id}|{self.boot_index}"))

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

    # -- simulated runtime (§2–§4) -------------------------------------------
    def _recompute_runtime_state(self) -> None:
        if self.session_active:
            if self.degraded_reasons:
                self.runtime_state = "DEGRADED"
            elif self.processed_frame_count > 0:
                self.runtime_state = "RUNNING"
            else:
                self.runtime_state = "STARTING"
        elif self._session_ever_started:
            self.runtime_state = "STOPPED"
        elif self.config_applied:
            self.runtime_state = "CONFIG_APPLIED"
        elif self._command_seen:
            self.runtime_state = "REQUEST_ACCEPTED"
        else:
            self.runtime_state = None

    def _sync_input_degraded(self) -> None:
        """Re-derive input-driven codes from ``input_state`` (keeps manual ones)."""
        self.degraded_reasons = [r for r in self.degraded_reasons if r not in _INPUT_DERIVED_CODES]
        for (channel, value), code in sorted(_INPUT_DERIVED.items()):
            if self.input_state.get(channel) == value and code not in self.degraded_reasons:
                self.degraded_reasons.append(code)
        self.degraded_reasons.sort()

    def configure_input(self, *, video: str | None = None,
                        telemetry: str | None = None, engine: str | None = None) -> dict:
        """Scenario knob: set input channels; derivations update deterministically."""
        for channel, value in (("video", video), ("telemetry", telemetry), ("engine", engine)):
            if value is None:
                continue
            if value not in _INPUT_VALUES:
                raise ValueError(f"bad input_state {channel}={value!r}")
            self.input_state[channel] = value
        self._sync_input_degraded()
        self._recompute_runtime_state()
        return dict(self.input_state)

    def inject_degraded(self, *reasons: str) -> list[str]:
        """Scenario knob: add degraded-reason codes (§3)."""
        for reason in reasons:
            if reason not in DEGRADED_CODES:
                raise ValueError(f"unknown degraded reason {reason!r}")
            if reason not in self.degraded_reasons:
                self.degraded_reasons.append(reason)
        self.degraded_reasons.sort()
        self._recompute_runtime_state()
        return list(self.degraded_reasons)

    def clear_degraded(self, *reasons: str) -> list[str]:
        """Scenario knob: drop codes (none given = drop all manual ones)."""
        if reasons:
            doomed = set(reasons)
            self.degraded_reasons = [r for r in self.degraded_reasons if r not in doomed]
        else:
            self.degraded_reasons = []
        self._sync_input_degraded()
        self._recompute_runtime_state()
        return list(self.degraded_reasons)

    def set_space_shortage(self, active: bool) -> list[str]:
        """Scenario knob: WGS84→field geometry unavailable (§4.6)."""
        if active:
            return self.inject_degraded("zone_geometry_unavailable")
        return self.clear_degraded("zone_geometry_unavailable")

    def process_frames(self, n: int = 1, ms_per_frame: int = 33) -> int:
        """Simulate engine frame processing (§4.2: only while session active
        with an installed config; engine failure processes nothing)."""
        if n <= 0:
            return 0
        if not (self.session_active and self.config_applied and not self._install_rejected):
            return 0
        if "engine_failed" in self.degraded_reasons:
            return 0
        for _ in range(int(n)):
            self.clock.advance(ms=ms_per_frame)
            self.processed_frame_count += 1
            self._last_processed_ns = self.clock.now_ns
        self._recompute_runtime_state()
        return int(n)

    def _status_payload(self) -> dict:
        app_id = None
        for payload in self.applications.values():
            app_id = payload["application_id"]
        if self._last_processed_ns is None:
            age_ms = None
        else:
            age_ms = max(0, (self.clock.now_ns - self._last_processed_ns) // 1_000_000)
        payload = {
            "applied_bundle_sha256": self.applied_bundle_sha256,
            "application_id": app_id,
            "edge_boot_id": self.edge_boot_id,
            "running": self.running,
            "effective_edge_seq": self.seq["EVENT_RELIABLE"],
            # §3 optional runtime fields (absent == unknown; runtime_state
            # is omitted until the first command is seen).
            "runtime_epoch": self.runtime_epoch,
            "config_applied": self.config_applied,
            "effective_config_hash": self.effective_config_hash,
            "processed_frame_count": self.processed_frame_count,
            "last_processed_frame_age_ms": age_ms,
            "degraded_reasons": list(self.degraded_reasons),
            "input_state": dict(self.input_state),
        }
        if self.runtime_state is not None:
            payload["runtime_state"] = self.runtime_state
        return payload

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
            if env["type"] in ("SESSION_START", "SESSION_STOP"):
                # Same command retried (same msg_id): no new session, just
                # re-confirm so the RC can clear its unacked entry (§5).
                self._ack(env["msg_id"], env["channel"])
                return "dedup"
            if env["type"] == "MISSION_STATUS_QUERY":
                # Queries are side-effect free: answer again from live state.
                self.seen_msg_ids.discard(env["msg_id"])
                return self._on_status_query(env)
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
        self._command_seen = True
        if reasons:
            resp = self._send("CONTROL_RELIABLE", "MISSION_APPLY_REJECTED", {"reasons": reasons})
        else:
            self.prepared[bundle["bundle_id"]] = copy.deepcopy(bundle)
            resp = self._send(
                "CONTROL_RELIABLE",
                "MISSION_PREPARED",
                {"bundle_sha256": bundle["bundle_sha256"], "supported": True, "exclusions": []},
            )
        self._recompute_runtime_state()
        self.last_responses[env["msg_id"]] = copy.deepcopy(resp)
        self._ack(env["msg_id"], env["channel"])
        return "stored"

    @staticmethod
    def _active_rules(bundle: dict) -> list:
        rules = ((bundle.get("risk_policy") or {}).get("rules") or [])
        return [r for r in rules if isinstance(r, dict) and r.get("mode") == "ACTIVE"]

    def _on_apply(self, env: dict) -> str:
        bundle_id = env["payload"].get("bundle_id")
        bundle_sha = env["payload"].get("bundle_sha256")
        bundle = self.prepared.get(bundle_id or "")
        self._command_seen = True
        if bundle is None or bundle.get("bundle_sha256") != bundle_sha:
            resp = self._send(
                "CONTROL_RELIABLE", "MISSION_APPLY_REJECTED",
                {"reasons": ["UNKNOWN_BUNDLE: bundle not prepared or digest differs"]},
            )
            self._recompute_runtime_state()
            self.last_responses[env["msg_id"]] = copy.deepcopy(resp)
            self._ack(env["msg_id"], env["channel"])
            return "stored"
        if self._active_rules(bundle):
            # §4.5: ACTIVE mode is never installed; refuse + degrade (§3 code).
            self.config_applied = False
            self.effective_config_hash = None
            self._install_rejected = True
            if "policy_install_rejected" not in self.degraded_reasons:
                self.degraded_reasons.append("policy_install_rejected")
                self.degraded_reasons.sort()
            resp = self._send(
                "CONTROL_RELIABLE", "MISSION_APPLY_REJECTED",
                {"reasons": ["policy_install_rejected: ACTIVE mode requires a separate gate"]},
            )
            self._recompute_runtime_state()
            self.last_responses[env["msg_id"]] = copy.deepcopy(resp)
            self._ack(env["msg_id"], env["channel"])
            return "stored"
        application_id = str(uuid.uuid5(
            mop.NAMESPACE, f"apply|{self.flight_id}|{bundle_id}|{self.edge_boot_id}"))
        self.applied_bundle_sha256 = bundle_sha
        self.applied_bundle_id = bundle_id
        self.config_applied = True
        self.effective_config_hash = bundle_sha
        self._install_rejected = False
        self.degraded_reasons = [r for r in self.degraded_reasons if r != "policy_install_rejected"]
        # The legacy `running` bool means "a start was requested and not
        # stopped" (contract §2). APPLY is not a start: the real Edge leaves
        # it false until SESSION_START. Keep the fake in step with the C++
        # endpoint so an RC-side check cannot see two different truths.
        self.running = self.session_active
        self._recompute_runtime_state()
        payload = {"application_id": application_id, "effective_edge_seq": self.seq["EVENT_RELIABLE"]}
        self.applications[env["msg_id"]] = copy.deepcopy(payload)
        resp = self._send("CONTROL_RELIABLE", "MISSION_APPLIED", payload)
        self.last_responses[env["msg_id"]] = copy.deepcopy(resp)
        self._ack(env["msg_id"], env["channel"])
        return "stored"

    def _on_status_query(self, env: dict) -> str:
        self.seen_msg_ids.add(env["msg_id"])
        self._send("CONTROL_RELIABLE", "MISSION_STATUS", self._status_payload())
        self._ack(env["msg_id"], env["channel"])
        return "stored"

    def _on_session(self, env: dict) -> str:
        if env["type"] == "SESSION_START":
            self._command_seen = True
            self.session_active = True
            self._session_ever_started = True
            self.running = True
        else:
            self.session_active = False
            self.running = False
        self._recompute_runtime_state()
        self._ack(env["msg_id"], env["channel"])
        return "stored"

    # -- lifecycle --------------------------------------------------------------
    def reboot(self, next_boot_id: str | None = None) -> str:
        """New boot: rotated id, cleared applied state, tracker_epoch++.

        §4.4: a restart never restores RUNNING. ``runtime_epoch`` rotates,
        install/session evidence resets, stored records are preserved.
        """
        self.boot_index += 1
        self.edge_boot_id = next_boot_id or self._boot_id()
        self.tracker_epoch += 1
        self.applied_bundle_sha256 = None
        self.applied_bundle_id = None
        self.applications = {}
        self.running = False
        self.runtime_epoch = self._runtime_epoch()
        self.runtime_state = None
        self.config_applied = False
        self.effective_config_hash = None
        self.processed_frame_count = 0
        self._last_processed_ns = None
        self.degraded_reasons = []
        self.input_state = {"video": "ok", "telemetry": "ok", "engine": "ok"}
        self.session_active = False
        self._command_seen = False
        self._session_ever_started = False
        self._install_rejected = False
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
