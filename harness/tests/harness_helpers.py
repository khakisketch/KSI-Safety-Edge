"""Scenario helpers for harness E2E tests (uniquely named; see core_helpers)."""

from __future__ import annotations

from types import SimpleNamespace

from ksi_harness import EdgeReplay, FaultTransport, RCSimulator, RCStore, VirtualClock


def fresh_env(tmp_path):
    """New virtual clock + RC store/simulator + clean transport."""
    clock = VirtualClock()
    store = RCStore(tmp_path / "rc.db")
    rc = RCSimulator(store, clock)
    return SimpleNamespace(clock=clock, store=store, rc=rc, transport=FaultTransport())


def fresh_edge(env, flight, boot_index=0):
    boot = flight["boots"][boot_index]
    return EdgeReplay(flight["flight_id"], env.clock, boot_id=boot["boot_id"])


def exchange(env, edge, boot, *, max_rounds=10):
    """Emit a whole boot and drain; return pump stats."""
    edge.emit_all(boot)
    return env.transport.pump(edge, env.rc, max_rounds=max_rounds)
