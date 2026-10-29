#!/usr/bin/env python3
"""Summarize a device frame log (diagnostic only, not evidence).

The PSDK worker writes one JSON object per processed tick to its local
diagnostic log (`--out`, default /tmp/ksi-safety-edge.ndjson) carrying
`tick_us` (wall microseconds for detection + tracking + synchronous
evidence I/O on that tick) and the cumulative bounded-queue `drops`
counter. This script reduces that log to a distribution + drop count.

It reports numbers only — no pass/fail, no targets. Whether the device
keeps up is decided by comparing these numbers against the stream period
and the drop counter on the actual run, not by this tool.

Usage:
    python3 scripts/analyze_edge_frame_log.py /tmp/ksi-safety-edge.ndjson
"""
from __future__ import annotations

import argparse
import json
import sys


def percentile(sorted_values: list[float], pct: float) -> float | None:
    if not sorted_values:
        return None
    if pct <= 0:
        return sorted_values[0]
    if pct >= 100:
        return sorted_values[-1]
    rank = (len(sorted_values) - 1) * pct / 100.0
    low = int(rank)
    frac = rank - low
    high = min(low + 1, len(sorted_values) - 1)
    return sorted_values[low] * (1.0 - frac) + sorted_values[high] * frac


def summarize(rows: list[dict]) -> dict:
    frames = [r for r in rows if r.get("type") == "frame"]
    ticks = sorted(float(r["tick_us"]) / 1000.0 for r in frames if "tick_us" in r)
    drops = [int(r["drops"]) for r in frames if "drops" in r]
    recv = [int(r["recv_mono_ns"]) for r in frames if "recv_mono_ns" in r]
    status_mix: dict[str, int] = {}
    for r in frames:
        status = r.get("detect")
        if isinstance(status, str):
            status_mix[status] = status_mix.get(status, 0) + 1
    dims = sorted({(r.get("w"), r.get("h")) for r in frames})
    summary: dict = {
        "ticks": len(frames),
        "tick_ms_p50": percentile(ticks, 50),
        "tick_ms_p95": percentile(ticks, 95),
        "tick_ms_max": percentile(ticks, 100),
        "drops_first": drops[0] if drops else None,
        "drops_last": drops[-1] if drops else None,
        "drops_delta": (drops[-1] - drops[0]) if drops else None,
        "detect_status_mix": status_mix,
        "frame_dims_w_h": [list(d) for d in dims],
    }
    if len(recv) >= 2 and recv[-1] > recv[0]:
        seconds = (recv[-1] - recv[0]) / 1e9
        summary["stream_seconds"] = seconds
        summary["effective_fps"] = (len(recv) - 1) / seconds if seconds > 0 else None
    else:
        summary["stream_seconds"] = None
        summary["effective_fps"] = None
    over = sum(1 for t in ticks if t > 1000.0 / 30.0)
    summary["ticks_over_one_30fps_period"] = over
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", help="device frame log (ndjson)")
    args = parser.parse_args()
    rows = []
    malformed = 0
    try:
        with open(args.log, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                try:
                    rows.append(json.loads(line))
                except json.JSONDecodeError:
                    malformed += 1
    except OSError as exc:
        print(f"cannot read log: {exc}", file=sys.stderr)
        return 1
    summary = summarize(rows)
    summary["malformed_lines_skipped"] = malformed
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
