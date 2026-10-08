"""MOP envelope + deterministic in-process fault transport.

Envelope (JSON round-trip enforced on every delivery)::

    {protocol_version:1, msg_id, channel, type, flight_id,
     edge_boot_id|null, seq|null, ack_of|null, payload,
     payload_sha256, sent_monotonic_ns}

``payload_sha256`` is the ksi_contracts digest of ``payload``; the
receiver verifies it and rejects (counts an error, sends no ACK) on
mismatch.

Reliable delivery: the sender keeps an unacked queue keyed by ``msg_id``;
the receiver dedups by ``msg_id``; an ACK is sent only after the
receiving side durably persists. Retries reuse the SAME ``msg_id``.
"""

from __future__ import annotations

import copy
import json
import uuid
from dataclasses import dataclass, field

from ksi_contracts import digest_sha256

PROTOCOL_VERSION = 1

CHANNELS = (
    "CONTROL_RELIABLE",
    "EVENT_RELIABLE",
    "EVIDENCE_TRANSFER",
    "HEARTBEAT",
)

DATA_CHANNELS = ("EVENT_RELIABLE", "EVIDENCE_TRANSFER")

RC_TO_EDGE = (
    "MISSION_PREPARE",
    "MISSION_APPLY",
    "MISSION_STATUS_QUERY",
    "SESSION_START",
    "SESSION_STOP",
)

EDGE_TO_RC = (
    "MISSION_PREPARED",
    "MISSION_APPLY_REJECTED",
    "MISSION_APPLIED",
    "MISSION_STATUS",
    "OBSERVATION",
    "DETECTION_EVENT",
    "FLIGHT_SUMMARY",
    "ARTIFACT_ANNOUNCE",
    "ARTIFACT_CHUNK",
    "HEARTBEAT",
    "ACK",
)

NAMESPACE = uuid.uuid5(uuid.NAMESPACE_DNS, "ksi-safety-edge-harness")


def new_msg_id(*parts: str) -> str:
    return str(uuid.uuid5(NAMESPACE, "|".join(parts)))


class EnvelopeError(ValueError):
    pass


def make_envelope(
    *,
    msg_id: str,
    channel: str,
    type: str,
    flight_id: str,
    edge_boot_id: str | None,
    seq: int | None,
    ack_of: str | None,
    payload: dict,
    sent_monotonic_ns: int,
) -> dict:
    if channel not in CHANNELS:
        raise EnvelopeError(f"unknown channel {channel!r}")
    env = {
        "protocol_version": PROTOCOL_VERSION,
        "msg_id": msg_id,
        "channel": channel,
        "type": type,
        "flight_id": flight_id,
        "edge_boot_id": edge_boot_id,
        "seq": seq,
        "ack_of": ack_of,
        "payload": payload,
        "payload_sha256": digest_sha256(payload),
        "sent_monotonic_ns": sent_monotonic_ns,
    }
    # Fail fast on non-JSON payloads (round-trip is enforced again on delivery).
    json.dumps(env)
    return env


def verify_envelope(env: dict) -> dict:
    """JSON round-trip + digest-verify; return the payload or raise."""
    try:
        raw = json.dumps(env)
        rt = json.loads(raw)
    except (TypeError, ValueError) as exc:
        raise EnvelopeError(f"envelope not JSON round-trippable: {exc}") from exc
    if rt.get("protocol_version") != PROTOCOL_VERSION:
        raise EnvelopeError("bad protocol_version")
    payload = rt.get("payload")
    if not isinstance(payload, dict):
        raise EnvelopeError("payload must be an object")
    if digest_sha256(payload) != rt.get("payload_sha256"):
        raise EnvelopeError("payload_sha256 mismatch")
    return rt


@dataclass
class _Unacked:
    envelope: dict
    attempts: int = 1
    sent_round: int = 0


@dataclass
class EndpointState:
    """Shared shape of EdgeReplay / RCSimulator as seen by the transport."""

    outbox: list = field(default_factory=list)
    unacked: dict = field(default_factory=dict)

    def receive(self, env: dict):  # implemented by the endpoint
        raise NotImplementedError


class FaultTransport:
    """Deterministic in-proc transport with fault injection.

    Fault knobs (all deterministic, no randomness):
      drop_types: message types always dropped.
      drop_first_n: {type: count} — drop the first N of each type.
      duplicate_n: duplicate the next N accepted envelopes.
      reorder_window: when >0, each per-round delivery batch is reversed.
      delay: rounds a delivery is held before becoming due.
      disconnect(peer): drop everything destined to "edge"/"rc".
      report_lost(edge_boot_id, channel, seqs): persistently drop those
        Edge seqs, including retries (models unrecoverable loss).
    """

    def __init__(
        self,
        *,
        drop_types=(),
        drop_first_n=None,
        duplicate_n: int = 0,
        reorder_window: int = 0,
        delay: int = 0,
    ) -> None:
        self.drop_types = set(drop_types)
        self.drop_first_n = dict(drop_first_n or {})
        self.duplicate_n = int(duplicate_n)
        self.reorder_window = int(reorder_window)
        self.delay = int(delay)
        self.disconnected: set[str] = set()
        self.lost: set[tuple] = set()
        self.pending: dict[str, list] = {"edge": [], "rc": []}
        self._round = 0
        self.dropped_count = 0

    # -- configuration ----------------------------------------------------
    def disconnect(self, peer: str) -> None:
        self.disconnected.add(peer)

    def reconnect(self, peer: str) -> None:
        self.disconnected.discard(peer)

    def report_lost(self, edge_boot_id: str, channel: str, seqs) -> None:
        for seq in seqs:
            self.lost.add((edge_boot_id, channel, int(seq)))

    def clear_drops(self) -> None:
        self.drop_types.clear()
        self.drop_first_n.clear()

    # -- sending ----------------------------------------------------------
    def send(self, sender: str, envelope: dict) -> bool:
        """Queue ``envelope`` for delivery; False when dropped by a fault."""
        dest = "rc" if sender == "edge" else "edge"
        mtype = envelope.get("type")
        key = (envelope.get("edge_boot_id"), envelope.get("channel"), envelope.get("seq"))
        if dest in self.disconnected:
            self.dropped_count += 1
            return False
        if mtype in self.drop_types or key in self.lost:
            self.dropped_count += 1
            return False
        remaining = self.drop_first_n.get(mtype, 0)
        if remaining > 0:
            self.drop_first_n[mtype] = remaining - 1
            self.dropped_count += 1
            return False
        due = self._round + self.delay
        self.pending[dest].append((due, copy.deepcopy(envelope)))
        if self.duplicate_n > 0:
            self.duplicate_n -= 1
            self.pending[dest].append((due, copy.deepcopy(envelope)))
        return True

    def _drain_outboxes(self, edge, rc, stats) -> None:
        for sender, ep in (("edge", edge), ("rc", rc)):
            while ep.outbox:
                env = ep.outbox.pop(0)
                self.send(sender, env)
                stats[sender]["sent"] += 1
                rec = ep.unacked.get(env["msg_id"])
                if rec is not None and rec.sent_round == 0:
                    # Fresh send: grace round so the ACK can come back
                    # before this entry becomes retry-eligible.
                    rec.sent_round = self._round

    def pump(self, edge, rc, max_rounds: int = 5) -> dict:
        """Drain both directions until quiet or ``max_rounds`` exhausted.

        Returns per-side stats {sent, received, retried, dedup, errors}
        plus the rounds used. Retries reuse the SAME msg_id. After the
        rounds are exhausted the Edge moves still-unacked DATA-channel
        seqs into unrecoverable gaps (control flow reconciles via
        STATUS_QUERY instead).
        """
        stats = {
            "edge": {"sent": 0, "received": 0, "retried": 0, "dedup": 0, "errors": 0},
            "rc": {"sent": 0, "received": 0, "retried": 0, "dedup": 0, "errors": 0},
        }
        rounds = 0
        while rounds < max_rounds:
            rounds += 1
            self._round += 1
            self._drain_outboxes(edge, rc, stats)
            delivered = False
            for dest, ep in (("rc", rc), ("edge", edge)):
                due = [env for due_r, env in self.pending[dest] if due_r <= self._round]
                self.pending[dest] = [(d, e) for d, e in self.pending[dest] if d > self._round]
                if self.reorder_window > 0:
                    due = list(reversed(due))
                for env in due:
                    delivered = True
                    stats[dest]["received"] += 1
                    try:
                        env = verify_envelope(env)
                    except EnvelopeError:
                        stats[dest]["errors"] += 1
                        continue
                    outcome = ep.receive(env)
                    if outcome == "dedup":
                        stats[dest]["dedup"] += 1
                    elif outcome == "error":
                        stats[dest]["errors"] += 1
            # Schedule retries for overdue unacked entries only: a fresh send
            # needs a full round trip (send + ACK, each possibly delayed)
            # before it may be retried, else ACKs in flight cause duplicates.
            overdue_after = 2 * self.delay + 2
            for _sender, ep in (("edge", edge), ("rc", rc)):
                for mid, rec in list(ep.unacked.items()):
                    if rec.attempts < max_rounds and self._round - rec.sent_round >= overdue_after:
                        rec.attempts += 1
                        rec.sent_round = self._round
                        ep.outbox.append(copy.deepcopy(rec.envelope))
            self._drain_outboxes(edge, rc, stats)
            # Any unacked entry requeued this round counts as a retry.
            for side, ep in (("edge", edge), ("rc", rc)):
                for rec in ep.unacked.values():
                    if rec.sent_round == self._round and rec.attempts > 1:
                        stats[side]["retried"] += 1
            quiet = (
                not self.pending["edge"]
                and not self.pending["rc"]
                and not edge.outbox
                and not rc.outbox
                and not edge.unacked
                and not rc.unacked
            )
            if quiet and not delivered:
                break
            if quiet:
                # One more round to let trailing ACKs settle is already
                # covered: ACKs travel via outbox/pending/unacked above.
                break
            if (
                not self.pending["edge"]
                and not self.pending["rc"]
                and not edge.outbox
                and not rc.outbox
                and all(r.attempts >= max_rounds for r in list(edge.unacked.values()) + list(rc.unacked.values()))
            ):
                break
        edge.mark_unrecoverable(max_rounds)
        stats["rounds"] = rounds
        return stats
