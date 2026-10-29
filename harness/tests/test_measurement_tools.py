"""Host tests for the device-measurement helper scripts.

Both tools report numbers only (no pass/fail, no targets); these tests pin
that contract plus the arithmetic the device enforces.
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"


def load(name: str):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f"{name}.py")
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


analyzer = load("analyze_edge_frame_log")
sizer = load("size_autonomy_store")


def _row(i: int, tick_us: int, drops: int, detect: str = "Ok") -> dict:
    return {
        "type": "frame",
        "i": i,
        "frame_id": i,
        "w": 1920,
        "h": 1080,
        "recv_mono_ns": i * 33_333_333,
        "detect": detect,
        "dets": 1,
        "tick_us": tick_us,
        "drops": drops,
    }


def test_analyzer_reports_distribution_and_drops_not_a_verdict():
    rows = [_row(i, 1000 * (i + 1), i // 3) for i in range(6)]
    summary = analyzer.summarize(rows)
    assert summary["ticks"] == 6
    assert summary["tick_ms_p50"] == 3.5
    assert summary["tick_ms_max"] == 6.0
    assert summary["drops_first"] == 0
    assert summary["drops_last"] == 1
    assert summary["drops_delta"] == 1
    assert summary["detect_status_mix"] == {"Ok": 6}
    assert summary["frame_dims_w_h"] == [[1920, 1080]]
    assert "pass" not in summary and "fail" not in summary
    # Malformed lines never poison the summary; absence is explicit None.
    assert analyzer.summarize([])["tick_ms_p50"] is None
    assert analyzer.summarize([])["effective_fps"] is None


def test_analyzer_cli_skips_malformed_lines(tmp_path):
    log = tmp_path / "frames.ndjson"
    log.write_text(
        '{"type": "frame", "tick_us": 2000, "drops": 0, "recv_mono_ns": 0}\n'
        'not json\n'
        '{"type": "frame", "tick_us": 4000, "drops": 2, "recv_mono_ns": 33333333}\n',
        encoding="utf-8",
    )
    proc = subprocess.run(
        [sys.executable, str(SCRIPTS / "analyze_edge_frame_log.py"), str(log)],
        capture_output=True, text=True, check=False,
    )
    assert proc.returncode == 0, proc.stderr
    summary = json.loads(proc.stdout)
    assert summary["ticks"] == 2
    assert summary["malformed_lines_skipped"] == 1
    assert summary["drops_delta"] == 2


def test_sizer_mirrors_device_preflight_floor():
    # P1 defaults at 1080p: 6,220,800 * 2 + 524,288 = 12,965,888.
    # The 8 MiB device default is below it (reported, not raised here).
    result = sizer.floor_bytes(1920, 1080, 0, 1, 512 * 1024)
    assert result == {
        "frame_bytes": 6220800,
        "window_span_frames": 2,
        "window_bytes": 12441600,
        "seal_reserve_bytes": 524288,
        "floor_bytes": 12965888,
    }


def test_sizer_cli_checks_stated_capacity(tmp_path):
    proc = subprocess.run(
        [sys.executable, str(SCRIPTS / "size_autonomy_store.py"),
         "--width", "1920", "--height", "1080",
         "--capacity", str(8 * 1024 * 1024)],
        capture_output=True, text=True, check=False,
    )
    assert proc.returncode == 0, proc.stderr
    result = json.loads(proc.stdout)
    assert result["covers_one_window"] is False
    assert result["usable_for_windows_bytes"] == 8 * 1024 * 1024 - 512 * 1024
    proc = subprocess.run(
        [sys.executable, str(SCRIPTS / "size_autonomy_store.py"),
         "--width", "1920", "--height", "1080",
         "--capacity", "134217728",
         "--events-per-hour", "60", "--bytes-per-event", "13000000"],
        capture_output=True, text=True, check=False,
    )
    assert proc.returncode == 0, proc.stderr
    result = json.loads(proc.stdout)
    assert result["covers_one_window"] is True
    assert result["projected_bytes_per_hour"] == 60 * 13000000
    assert "not a guarantee" in result["projection_note"]
