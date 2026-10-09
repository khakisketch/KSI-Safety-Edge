"""R5: static source contract — Replay and PSDK share the product code.

The runtime proof of input-path independence lives in
edge/tests/test_replay.cpp section 8: identical TickRecords across the
direct, PSDK-queue (BoundedFrameQueue) and replay-source (manifest/raw
frames) input paths. This test adds the STATIC side of the same claim: both
product entry points must drive the shared SessionRunner and must not
re-implement detection/matching/session inline. The device build of the
PSDK app against the same core (TRT ON, done in the R1 verification) is
the compile-time evidence; this source check is a contract check, not a
runtime guarantee.
"""

from __future__ import annotations

import pathlib

REPO = pathlib.Path(__file__).resolve().parents[2]
RUNNER_HPP = REPO / "edge" / "include" / "ksi_safety" / "runtime" / "session_runner.hpp"
RUNNER_CPP = REPO / "edge" / "src" / "runtime" / "session_runner.cpp"
REPLAY_RUN = REPO / "edge" / "tools" / "replay_run.cpp"
PSDK_APP = REPO / "edge" / "psdk-app" / "src" / "main.cpp"


def test_r5_shared_runner_contract():
    runner_hpp = RUNNER_HPP.read_text(encoding="utf-8")
    runner_cpp = RUNNER_CPP.read_text(encoding="utf-8")
    replay_src = REPLAY_RUN.read_text(encoding="utf-8")
    app_src = PSDK_APP.read_text(encoding="utf-8")

    # The shared product runner owns detection-call discipline + matching +
    # the session step.
    assert "class SessionRunner" in runner_hpp
    assert "ProcessFrame" in runner_hpp
    assert "MatchFrame(" in runner_cpp
    assert "session_.Tick(" not in runner_cpp or "UpdateTracks" in runner_cpp

    # Replay entry point: drives the runner, no inline matching/tracker use.
    assert "SessionRunner runner(" in replay_src
    assert "runner.ProcessFrame(" in replay_src
    assert "MatchFrame(" not in replay_src
    assert "Tracker::Update" not in replay_src
    assert 'session/session.hpp' in replay_src or "SessionRunner" in replay_src

    # PSDK entry point: callbacks push telemetry into the runner; the worker
    # calls the runner; no direct matching remains in the app.
    assert "SessionRunner" in app_src
    assert "runner->ProcessFrame(" in app_src
    assert "runner->PushPose(" in app_src
    assert "runner->PushPosition(" in app_src
    assert "MatchFrame(" not in app_src
    assert "Tracker::Update" not in app_src
