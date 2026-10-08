"""Deterministic virtual clock — the only time source for harness assertions."""

from __future__ import annotations


class VirtualClock:
    """Monotonic nanosecond clock driven explicitly by tests.

    ``now_ns`` is the current time. :meth:`advance` moves it forward;
    every harness timestamp (envelope ``sent_monotonic_ns``, derived UTC
    conversions) comes from here — never the wall clock.
    """

    def __init__(self, initial_ns: int = 0) -> None:
        self._now_ns = int(initial_ns)

    @property
    def now_ns(self) -> int:
        return self._now_ns

    def advance(self, ms: int = 1) -> int:
        """Advance by ``ms`` milliseconds; return the new ``now_ns``."""
        if ms < 0:
            raise ValueError("clock cannot go backwards")
        self._now_ns += int(ms) * 1_000_000
        return self._now_ns

    def advance_ns(self, ns: int) -> int:
        if ns < 0:
            raise ValueError("clock cannot go backwards")
        self._now_ns += int(ns)
        return self._now_ns
