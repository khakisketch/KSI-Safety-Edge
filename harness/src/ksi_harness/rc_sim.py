"""RC simulator: MOP receiving (store→ACK), apply flow, sessions, uploader."""

from __future__ import annotations

import base64
import copy

from . import mop
from .mop import _Unacked, make_envelope, new_msg_id
from .rc_upload import UploadMixin


class ApplyError(RuntimeError):
    pass


class RCSimulator(UploadMixin):
    """In-proc RC side. Persists every Edge→RC message before ACKing it.

    Upload behavior (sessions/batches/manifests/ingest/annotations) comes
    from :class:`ksi_harness.rc_upload.UploadMixin`.
    """

    def __init__(self, store, clock, device_id: str | None = None) -> None:
        import uuid

        self.store = store
        self.clock = clock
        self.device_id = device_id or str(uuid.uuid5(mop.NAMESPACE, "rc-device|default"))
        self.outbox: list = []
        self.unacked: dict[str, _Unacked] = {}
        self.seen_msg_ids: set[str] = set(store.processed_msg_ids())
        self._msg_counter = 0
        self.applying: dict = {}  # flight_id -> {bundle_id, bundle_sha256}
        self.last_prepared: dict = {}
        self.last_status: dict = {}
        self.last_rejection: dict = {}
        self.acked: list[str] = []  # msg_ids ACKed (commit-before-ACK proofs)
        self.upload_ids: dict[str, str] = {}  # manifest_sha256 -> upload_id

    # -- lifecycle ----------------------------------------------------------
    def restart(self) -> None:
        """Simulate an RC crash/restart: same DB path, dedup memory rebuilt."""
        self.store.restart()
        self.seen_msg_ids = set(self.store.processed_msg_ids())
        self.unacked = {}

    # -- sending (RC→Edge control) ---------------------------------------------
    def _send_control(self, flight_id: str, type: str, payload: dict) -> dict:
        self._msg_counter += 1
        env = make_envelope(
            msg_id=new_msg_id("rc", flight_id, str(self._msg_counter), type),
            channel="CONTROL_RELIABLE",
            type=type,
            flight_id=flight_id,
            edge_boot_id=None,
            seq=None,
            ack_of=None,
            payload=payload,
            sent_monotonic_ns=self.clock.advance(ms=1),
        )
        self.outbox.append(env)
        self.unacked[env["msg_id"]] = _Unacked(envelope=copy.deepcopy(env), sent_round=0)
        return env

    def send_prepare(self, flight_id: str, bundle: dict) -> dict:
        return self._send_control(flight_id, "MISSION_PREPARE", {"bundle": copy.deepcopy(bundle)})

    def send_apply(self, flight_id: str, bundle: dict) -> dict:
        self.applying[flight_id] = {
            "bundle_id": bundle["bundle_id"], "bundle_sha256": bundle["bundle_sha256"]}
        return self._send_control(
            flight_id, "MISSION_APPLY",
            {"bundle_id": bundle["bundle_id"], "bundle_sha256": bundle["bundle_sha256"]},
        )

    def send_status_query(self, flight_id: str) -> dict:
        return self._send_control(flight_id, "MISSION_STATUS_QUERY", {})

    def send_session_start(self, flight_id: str) -> dict:
        return self._send_control(flight_id, "SESSION_START", {})

    def send_session_stop(self, flight_id: str) -> dict:
        return self._send_control(flight_id, "SESSION_STOP", {})

    def mark_unrecoverable(self) -> None:
        """RC never declares gaps itself; it records the Edge's declarations."""

    # -- receiving (Edge→RC): persist, then ACK ----------------------------------
    def receive(self, env: dict) -> str:
        if env["msg_id"] in self.seen_msg_ids or self.store.is_processed(env["msg_id"]):
            return "dedup"
        if env["type"] == "ACK":
            self.unacked.pop(env["payload"].get("ack_of", ""), None)
            return "ack"
        # Persist the envelope row (idempotent: a previous attempt may have
        # stored it and then failed in the domain handler — reprocess below).
        self.store.store_message(env)
        handler = {
            "MISSION_PREPARED": self._on_prepared,
            "MISSION_APPLIED": self._on_applied,
            "MISSION_APPLY_REJECTED": self._on_rejected,
            "MISSION_STATUS": self._on_status,
            "OBSERVATION": self._on_observation,
            "DETECTION_EVENT": self._on_event,
            "FLIGHT_SUMMARY": self._on_summary,
            "ARTIFACT_ANNOUNCE": self._on_announce,
            "ARTIFACT_CHUNK": self._on_chunk,
            "HEARTBEAT": self._on_heartbeat,
        }.get(env["type"])
        if handler is None:
            self._ack(env)
            return "error"
        try:
            handler(env)
        except (KeyError, ValueError):
            return "error"
        self.store.mark_processed(env["msg_id"])
        self.seen_msg_ids.add(env["msg_id"])
        self._ack(env)
        return "stored"

    def _ack(self, env: dict) -> None:
        self._msg_counter += 1
        ack = make_envelope(
            msg_id=new_msg_id("rc", env["flight_id"], str(self._msg_counter), "ACK", env["msg_id"]),
            channel=env["channel"],
            type="ACK",
            flight_id=env["flight_id"],
            edge_boot_id=env.get("edge_boot_id"),
            seq=None,
            ack_of=env["msg_id"],
            payload={"ack_of": env["msg_id"]},
            sent_monotonic_ns=self.clock.advance(ms=1),
        )
        self.outbox.append(ack)
        self.acked.append(env["msg_id"])

    # -- control handlers ----------------------------------------------------------
    def _on_prepared(self, env: dict) -> None:
        self.last_prepared[env["flight_id"]] = env["payload"]

    def _on_rejected(self, env: dict) -> None:
        self.last_rejection[env["flight_id"]] = env["payload"]

    def _build_application(self, flight_id: str, boot_id: str, application_id: str,
                           effective_edge_seq: int, sent_ns: int) -> dict:
        ctx = self.applying.get(flight_id, {})
        return {
            "schema_version": 1,
            "application_id": application_id,
            "flight_id": flight_id,
            "bundle_id": ctx.get("bundle_id", ""),
            "bundle_sha256": ctx.get("bundle_sha256", ""),
            "edge_boot_id": boot_id,
            "effective_edge_seq": effective_edge_seq,
            "applied": True,
            "rejection_reasons": [],
            "excluded_items": [],
            "applied_at_edge_monotonic_ns": sent_ns,
        }

    def _on_applied(self, env: dict) -> None:
        app = self._build_application(
            env["flight_id"], env["edge_boot_id"],
            env["payload"]["application_id"], env["payload"]["effective_edge_seq"],
            env["sent_monotonic_ns"],
        )
        self.store.record_application(app)
        self.applying.pop(env["flight_id"], None)

    def _on_status(self, env: dict) -> None:
        self.last_status[env["flight_id"]] = env["payload"]
        ctx = self.applying.get(env["flight_id"])
        status = env["payload"]
        if (
            ctx is not None
            and status.get("applied_bundle_sha256") == ctx.get("bundle_sha256")
            and status.get("application_id")
        ):
            app = self._build_application(
                env["flight_id"], status["edge_boot_id"], status["application_id"],
                status.get("effective_edge_seq") or 0, env["sent_monotonic_ns"],
            )
            self.store.record_application(app)
            self.applying.pop(env["flight_id"], None)

    # -- data handlers -----------------------------------------------------------------
    def _on_observation(self, env: dict) -> None:
        self.store.store_observation(env["payload"]["observation"], env["edge_boot_id"])

    def _on_event(self, env: dict) -> None:
        self.store.store_event(env["payload"]["event"], env["edge_boot_id"])

    def _on_summary(self, env: dict) -> None:
        self.store.store_flight_summary(env["payload"]["summary"], env["edge_boot_id"])

    def _on_announce(self, env: dict) -> None:
        self.store.announce_artifact(env["payload"], env["flight_id"], env["edge_boot_id"])

    def _on_chunk(self, env: dict) -> None:
        chunk = env["payload"]
        self.store.write_chunk(
            chunk["artifact_id"], chunk["offset"], base64.b64decode(chunk["data_b64"]))

    def _on_heartbeat(self, env: dict) -> None:
        received_ev = self.store.received_seqs(env["edge_boot_id"], "EVENT_RELIABLE")
        received_ex = self.store.received_seqs(env["edge_boot_id"], "EVIDENCE_TRANSFER")
        for gap in env["payload"].get("unrecoverable_gaps", []):
            received = received_ev if gap["channel"] == "EVENT_RELIABLE" else received_ex
            for seq in gap.get("seqs", []):
                if int(seq) in received:
                    continue  # already durably stored; not a gap
                self.store.record_gap(
                    env["edge_boot_id"], gap["channel"], int(seq),
                    f"UNRECOVERABLE:{gap['channel']}", "RC_MANIFEST", env["flight_id"],
                )

    # -- apply flow -----------------------------------------------------------------------
    def applications(self, flight_id: str) -> list:
        return self.store.applications_for(flight_id)

    def prepare_and_apply(self, flight_id: str, bundle: dict, edge, transport, max_rounds: int = 5) -> dict:
        self.send_prepare(flight_id, bundle)
        transport.pump(edge, self, max_rounds)
        if flight_id in self.last_rejection and flight_id not in self.last_prepared:
            raise ApplyError(f"prepare rejected: {self.last_rejection[flight_id]}")
        prepared = self.last_prepared.get(flight_id)
        if prepared is None or prepared.get("bundle_sha256") != bundle["bundle_sha256"]:
            raise ApplyError("no MISSION_PREPARED for bundle")
        self.send_apply(flight_id, bundle)
        transport.pump(edge, self, max_rounds)
        apps = self.applications(flight_id)
        mine = [a for a in apps if a["bundle_sha256"] == bundle["bundle_sha256"]]
        if mine:
            return mine[-1]
        # APPLIED reply missing → reconcile via STATUS_QUERY (never mark READY on send).
        self.send_status_query(flight_id)
        transport.pump(edge, self, max_rounds)
        apps = self.applications(flight_id)
        mine = [a for a in apps if a["bundle_sha256"] == bundle["bundle_sha256"]]
        if mine:
            return mine[-1]
        raise ApplyError("apply unconfirmed: no MISSION_APPLIED or MISSION_STATUS")

