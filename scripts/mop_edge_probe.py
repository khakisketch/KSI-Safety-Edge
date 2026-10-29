#!/usr/bin/env python3
"""가상 RC → 가상 Manifold(호스트 Edge 대역) MOP 왕복 프로브.

`edge/tools/mop_server`(실제 C++ Edge 엔진)를 TCP로 띄우고, **RC가 하던 제어
시퀀스**를 그대로 재현한다:

    MISSION_PREPARE → MISSION_PREPARED
    MISSION_APPLY   → MISSION_APPLIED (또는 REJECTED)
    SESSION_START   → ACK + 실행 상태
    MISSION_STATUS_QUERY → MISSION_STATUS (6종 어휘 + 선택 필드)
    SESSION_STOP    → ACK + 상태

목적: 실제 Manifold 3가 연결되지 않은 동안에도 **MOP 프로토콜·계약 계층**을
실행 가능한 상태로 확인한다. 메시지 형식·다이제스트는 harness의 MOP 구현을
사용한다(Edge 쪽은 C++ 실코드).

사용:
    python3 scripts/mop_edge_probe.py --port 5050
    python3 scripts/mop_edge_probe.py --port 5050 --keep-open  # 응답을 계속 받는다
    python3 scripts/mop_edge_probe.py --port 5050 --observe-retransmission
        # 첫 MISSION_PREPARED의 ACK를 일부러 보류해 Edge의 재전송(같은
        # msg_id)을 실제로 관측한다 — "재전송은 같은 msg_id로 온다" 계약의
        # 수신측 관측 증거. 기본 동작(즉시 ACK)에서는 재전송이 일어나지 않는다.
"""

from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import time
import uuid
from pathlib import Path

from supervision_evidence import save_immutable_json

REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "harness" / "src"))

from ksi_harness import fixtures  # noqa: E402
from ksi_harness.mop import (  # noqa: E402
    CHANNELS,
    EnvelopeError,
    make_envelope,
    new_msg_id,
    verify_envelope,
)

PROTOCOL_VERSION = 1
FLAG_MORE = 0x01


def encode_frame(message: str) -> bytes:
    """MOP 바이트 프레이밍: u32be(len) | u8(flags) | chunk."""
    raw = message.encode("utf-8")
    return struct.pack(">I", len(raw)) + bytes([0]) + raw


class Framer:
    """수신 프레임 → 완전 메시지 재조립(리셋 없음: 손상은 연결을 끊는다)."""

    def __init__(self) -> None:
        self._buf = bytearray()

    def feed(self, data: bytes) -> list[str]:
        self._buf.extend(data)
        out: list[str] = []
        while True:
            if len(self._buf) < 5:
                break
            length = struct.unpack(">I", self._buf[0:4])[0]
            flags = self._buf[4]
            if len(self._buf) < 5 + length:
                break
            chunk = bytes(self._buf[5 : 5 + length])
            del self._buf[: 5 + length]
            out.append(chunk.decode("utf-8"))
            if not (flags & FLAG_MORE):
                pass  # 다음 메시지는 새 프레임
        return out


class Probe:
    def __init__(self, host: str, port: int, flight_id: str,
                 observe_retransmission: bool = False) -> None:
        self.flight_id = flight_id
        self.observe_retransmission = observe_retransmission
        self.withhold_ack: str | None = None  # msg_id we deliberately do not ACK yet
        self.sock = socket.create_connection((host, port), timeout=10)
        self.framer = Framer()
        self.seq = 0
        self.edge_boot_id: str | None = None
        self.pending: dict[str, str] = {}  # msg_id -> type (ACK 대기)
        self.log: list[tuple[str, dict]] = []

    # -- 송신 -------------------------------------------------------------
    def send(self, msg_type: str, payload: dict, channel: str = "CONTROL_RELIABLE") -> None:
        self.seq += 1
        env = make_envelope(
            msg_id=new_msg_id("rc", self.flight_id, str(self.seq), msg_type),
            channel=channel,
            type=msg_type,
            flight_id=self.flight_id,
            edge_boot_id=self.edge_boot_id,
            seq=self.seq,
            ack_of=None,
            payload=payload,
            sent_monotonic_ns=time.monotonic_ns(),
        )
        self.pending[env["msg_id"]] = msg_type
        self.sock.sendall(encode_frame(json.dumps(env, ensure_ascii=False)))
        print(f"  RC  -> {msg_type}")

    def ack(self, env: dict) -> None:
        """Edge의 신뢰성 있는 메시지에 ACK. 못 보내면 Edge는 재전송한다.

        계약상 `ack_of`는 **엔벨로프 수준과 payload 양쪽**에 있다
        (harness rc_sim과 C++ endpoint가 같은 형태를 읽는다).
        """
        ack = make_envelope(
            msg_id=new_msg_id("rc", self.flight_id, "ack", env["msg_id"]),
            channel=env.get("channel", "CONTROL_RELIABLE"),
            type="ACK",
            flight_id=self.flight_id,
            edge_boot_id=env.get("edge_boot_id"),
            seq=None,
            ack_of=env["msg_id"],
            payload={"ack_of": env["msg_id"]},
            sent_monotonic_ns=time.monotonic_ns(),
        )
        self.sock.sendall(encode_frame(json.dumps(ack, ensure_ascii=False)))

    # -- 수신 -------------------------------------------------------------
    def pump(self, seconds: float) -> list[dict]:
        deadline = time.time() + seconds
        received: list[dict] = []
        while time.time() < deadline:
            self.sock.settimeout(max(0.05, deadline - time.time()))
            try:
                data = self.sock.recv(65536)
            except socket.timeout:
                break
            except OSError:
                break
            if not data:
                break
            for message in self.framer.feed(data):
                try:
                    env = verify_envelope(json.loads(message))
                except (EnvelopeError, json.JSONDecodeError) as exc:
                    print(f"  !! 잘못된 엔벨로프: {exc}")
                    continue
                received.append(env)
                self.log.append((env.get("type", "?"), env))
                if env.get("edge_boot_id"):
                    self.edge_boot_id = env["edge_boot_id"]
                print(f"  EDGE-> {env.get('type')}"
                      + (f"  {self._brief(env)}" if self._brief(env) else "")
                      + f"  msg_id={str(env.get('msg_id'))[:8]}")
                if env.get("type") != "ACK":
                    if (self.observe_retransmission and
                            env.get("type") == "MISSION_PREPARED" and
                            self.withhold_ack is None):
                        # 계약 시험(수신측 관측): 첫 PREPARED의 ACK를 보류해
                        # Edge의 재전송 타이머가 실제로 돌게 만든다. 재수신이
                        # 오면(같은 msg_id) 중복으로 관측되고 보류를 푼다 —
                        # 수신측은 msg_id로 중복을 제거해야 한다.
                        self.withhold_ack = env["msg_id"]
                        print("  (재전송 관측용 ACK 보류)")
                    else:
                        self.ack(env)
                        if self.withhold_ack == env.get("msg_id"):
                            self.withhold_ack = None  # 중복 수신됨 → ACK 재개
        return received

    @staticmethod
    def _brief(env: dict) -> str:
        payload = env.get("payload") or {}
        for key in ("runtime_state", "bundle_sha256", "result", "reason"):
            if key in payload:
                return f"{key}={payload[key]}"
        return ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=5050)
    parser.add_argument("--flight-id", help="explicit host-test flight identity (default: a fresh UUID)")
    parser.add_argument("--transcript-out", type=Path,
                        help="save verified received envelopes as a HOST_REPLAY development transcript")
    parser.add_argument("--keep-open", action="store_true",
                        help="종료까지 계속 수신한다")
    parser.add_argument("--observe-retransmission", action="store_true",
                        help="첫 MISSION_PREPARED의 ACK를 보류해 Edge 재전송(같은 msg_id)을 관측한다")
    args = parser.parse_args()

    world = fixtures.world("probe")
    bundle = world["bundle"]
    # 비행 UUID는 RC가 만든다(Edge는 와이어로 학습한다).
    flight_id = str(uuid.UUID(args.flight_id)) if args.flight_id else str(uuid.uuid4())

    print(f"가상 RC → 가상 Manifold({args.host}:{args.port}) flight={flight_id[:8]}")
    probe = Probe(args.host, args.port, flight_id,
                  observe_retransmission=args.observe_retransmission)

    print("[1] MISSION_PREPARE")
    probe.send("MISSION_PREPARE", {"bundle": bundle})
    probe.pump(1.5)

    print("[2] MISSION_APPLY")
    probe.send(
        "MISSION_APPLY",
        {
            "bundle_id": bundle["bundle_id"],
            "bundle_sha256": bundle["bundle_sha256"],
        },
    )
    probe.pump(1.5)

    print("[3] SESSION_START")
    probe.send("SESSION_START", {})
    probe.pump(1.5)

    print("[4] MISSION_STATUS_QUERY")
    probe.send("MISSION_STATUS_QUERY", {})
    statuses = probe.pump(1.5)
    for env in statuses:
        if env.get("type") == "MISSION_STATUS":
            print("      상태 필드:")
            for key, value in sorted((env.get("payload") or {}).items()):
                print(f"        {key} = {value}")

    print("[5] SESSION_STOP")
    probe.send("SESSION_STOP", {})
    probe.pump(1.5)

    if args.keep_open:
        print("[keep-open] 계속 수신한다 (Ctrl-C 종료)")
        try:
            while True:
                probe.pump(2.0)
        except KeyboardInterrupt:
            pass

    types = [t for t, _ in probe.log]
    print("\n요약:", ", ".join(f"{t}×{types.count(t)}" for t in sorted(set(types))))
    if args.transcript_out:
        save_immutable_json(args.transcript_out, {
            "schema_version": 1,
            "fixture_kind": "KSI_MOP_PROBE_TRANSCRIPT",
            "environment": "HOST_REPLAY",
            "simulated_inputs": True,
            "device_verified": False,
            "flight_id": flight_id,
            "messages": [env for _, env in probe.log],
        })
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
