#!/usr/bin/env python3
"""Autonomy store capacity floor (mirrors the device preflight, exactly).

The Edge refuses to pretend: a stated capacity below one capture window
plus the seal reserve can only ever produce truncated evidence, so the
first processed tick reports `evidence_capacity_below_one_window` and the
run keeps observing with the gap explicit. This script computes that floor
with the SAME arithmetic the device enforces, so an installer can size the
store before deploying instead of discovering it mid-flight.

Formula (see AutonomousProcessor::PreflightEvidenceCapacity):
    frame_bytes = width * height * 3
    span        = pre + 1 + post
    floor       = frame_bytes * span + seal_reserve_bytes

The default 8 MiB device capacity is BELOW one P1 window at 1080p
(6,220,800 * 2 + 524,288 = 12,965,888). That is not raised here: with the
default the run observes with evidence explicitly saturated. Size the
store for the deployment instead.

Sustained operation additionally depends on the event rate, which only a
prior run can tell you: pass --events-per-hour with the measured
per-event bytes (from the coverage artifact's window_frames_written and
the store's asset sizes) for a labeled projection. The projection is an
estimate, not a guarantee.

Usage:
    python3 scripts/size_autonomy_store.py --width 1920 --height 1080
    python3 scripts/size_autonomy_store.py --width 1920 --height 1080 \\
        --capacity 134217728 --events-per-hour 60 --bytes-per-event 13000000
"""
from __future__ import annotations

import argparse
import json

DEFAULT_RESERVE = 512 * 1024  # must match AutonomousConfig::seal_reserve_bytes


def floor_bytes(width: int, height: int, pre: int, post: int, reserve: int) -> dict:
    frame = width * height * 3
    span = pre + 1 + post
    window = frame * span
    return {
        "frame_bytes": frame,
        "window_span_frames": span,
        "window_bytes": window,
        "seal_reserve_bytes": reserve,
        "floor_bytes": window + reserve,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--pre", type=int, default=0)
    parser.add_argument("--post", type=int, default=1)
    parser.add_argument("--reserve-bytes", type=int, default=DEFAULT_RESERVE)
    parser.add_argument("--capacity", type=int, default=None,
                        help="stated store capacity to check against the floor")
    parser.add_argument("--events-per-hour", type=float, default=None)
    parser.add_argument("--bytes-per-event", type=float, default=None,
                        help="measured per-event bytes from a prior run")
    args = parser.parse_args()
    for name in ("width", "height"):
        if getattr(args, name) <= 0:
            parser.error(f"--{name} must be > 0")
    for name in ("pre", "post", "reserve_bytes"):
        if getattr(args, name) < 0:
            parser.error(f"--{name} must be >= 0")
    result = floor_bytes(args.width, args.height, args.pre, args.post,
                         args.reserve_bytes)
    if args.capacity is not None:
        if args.capacity < 0:
            parser.error("--capacity must be >= 0")
        result["stated_capacity_bytes"] = args.capacity
        result["covers_one_window"] = args.capacity >= result["floor_bytes"]
        result["usable_for_windows_bytes"] = max(
            0, args.capacity - result["seal_reserve_bytes"])
    if args.events_per_hour is not None or args.bytes_per_event is not None:
        if args.events_per_hour is None or args.bytes_per_event is None:
            parser.error("--events-per-hour and --bytes-per-event go together")
        if args.events_per_hour < 0 or args.bytes_per_event < 0:
            parser.error("projection inputs must be >= 0")
        result["projection_note"] = (
            "estimate from a prior run's measured rate, not a guarantee")
        result["projected_bytes_per_hour"] = (
            args.events_per_hour * args.bytes_per_event)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
