"""Unit D: MISSION_STATUS runtime fields, start/stop transitions, DEGRADED, epoch change.

No server needed (pure MOP + virtual clock). Message-type sets and the
envelope shape are unchanged; these tests pin the §2 state machine and the
§3 optional payload carried by MISSION_STATUS.
"""

from __future__ import annotations

import copy

import pytest

from ksi_contracts import set_digest

from ksi_harness import EdgeReplay, FaultTransport, RCSimulator, RCStore, VirtualClock, flight_a
from ksi_harness.rc_sim import ApplyError


def make_pair(tmp_path, tag="rt"):
    clock = VirtualClock()
    store = RCStore(tmp_path / "rc.db")
    rc = RCSimulator(store, clock)
    f = flight_a(tag=tag)
    edge = EdgeReplay(f["flight_id"], clock, boot_id=f["boots"][0]["boot_id"])
    transport = FaultTransport()
    return clock, store, rc, edge, f, transport


def status_of(rc, edge, flight_id, transport, **pump_kw):
    rc.send_status_query(flight_id)
    transport.pump(edge, rc, max_rounds=pump_kw.get("max_rounds", 5))
    return rc.last_status[flight_id]


def test_start_stop_transition_and_frame_count(tmp_path):
    clock, store, rc, edge, f, transport = make_pair(tmp_path, "rt-start-stop")
    fid = f["flight_id"]
    rc.prepare_and_apply(fid, f["bundle"], edge, transport, max_rounds=5)
    assert edge.processed_frame_count == 0

    # No explicit start yet: frames must NOT be processed (§4.2).
    assert edge.process_frames(4) == 0
    assert edge.processed_frame_count == 0
    applied = status_of(rc, edge, fid, transport)
    assert applied["config_applied"] is True
    assert applied["effective_config_hash"] == f["bundle"]["bundle_sha256"]
    assert applied["runtime_state"] == "CONFIG_APPLIED"
    assert applied["processed_frame_count"] == 0

    # SESSION_START -> STARTING (approved, nothing processed yet).
    rc.send_session_start(fid)
    transport.pump(edge, rc, max_rounds=5)
    starting = status_of(rc, edge, fid, transport)
    assert starting["runtime_state"] == "STARTING"
    assert starting["config_applied"] is True
    assert starting["runtime_epoch"] == edge.runtime_epoch

    # Engine work -> RUNNING with a growing counter and a fresh age.
    assert edge.process_frames(5) == 5
    assert edge.process_frames(3) == 3
    running = status_of(rc, edge, fid, transport)
    assert running["runtime_state"] == "RUNNING"
    assert running["processed_frame_count"] == 8
    assert running["last_processed_frame_age_ms"] is not None
    assert running["degraded_reasons"] == []
    assert running["input_state"] == {"video": "ok", "telemetry": "ok", "engine": "ok"}

    # SESSION_STOP -> STOPPED; the counter freezes, late frames are refused.
    rc.send_session_stop(fid)
    transport.pump(edge, rc, max_rounds=5)
    stopped = status_of(rc, edge, fid, transport)
    assert stopped["runtime_state"] == "STOPPED"
    assert stopped["processed_frame_count"] == 8
    assert edge.process_frames(2) == 0
    assert edge.processed_frame_count == 8
    # Legacy running flag keeps its old meaning (start requested, not stopped).
    assert stopped["running"] is False

    # Same SESSION_STOP retried with the SAME msg_id: idempotent, state kept.
    first = rc.send_session_stop(fid)
    transport.pump(edge, rc, max_rounds=5)
    assert edge.receive(copy.deepcopy(first)) == "dedup"
    transport.pump(edge, rc, max_rounds=5)
    again = status_of(rc, edge, fid, transport)
    assert again["runtime_state"] == "STOPPED"
    assert again["processed_frame_count"] == 8


def test_degraded_status_and_recovery(tmp_path):
    clock, store, rc, edge, f, transport = make_pair(tmp_path, "rt-degraded")
    fid = f["flight_id"]
    rc.prepare_and_apply(fid, f["bundle"], edge, transport, max_rounds=5)
    rc.send_session_start(fid)
    transport.pump(edge, rc, max_rounds=5)
    edge.process_frames(4)
    assert status_of(rc, edge, fid, transport)["runtime_state"] == "RUNNING"

    # Video input lost -> DEGRADED with reasons + input_state (§3/§4.3).
    edge.configure_input(video="lost")
    degraded = status_of(rc, edge, fid, transport)
    assert degraded["runtime_state"] == "DEGRADED"
    assert "video_input_lost" in degraded["degraded_reasons"]
    assert degraded["input_state"]["video"] == "lost"
    # A zero-detection stretch stays DEGRADED, never "safe because 0 events".
    assert degraded["processed_frame_count"] == 4

    # Space shortage adds its own code without clearing the input one.
    edge.set_space_shortage(True)
    both = status_of(rc, edge, fid, transport)
    assert both["runtime_state"] == "DEGRADED"
    assert {"video_input_lost", "zone_geometry_unavailable"} <= set(both["degraded_reasons"])

    # Recovery: inputs healthy again -> back to RUNNING, reasons cleared.
    edge.set_space_shortage(False)
    edge.configure_input(video="ok")
    edge.process_frames(2)
    recovered = status_of(rc, edge, fid, transport)
    assert recovered["runtime_state"] == "RUNNING"
    assert recovered["degraded_reasons"] == []
    assert recovered["processed_frame_count"] == 6


def test_restart_rotates_epoch_and_resets_runtime(tmp_path):
    clock, store, rc, edge, f, transport = make_pair(tmp_path, "rt-epoch")
    fid = f["flight_id"]
    rc.prepare_and_apply(fid, f["bundle"], edge, transport, max_rounds=5)
    rc.send_session_start(fid)
    transport.pump(edge, rc, max_rounds=5)
    edge.process_frames(6)
    before = status_of(rc, edge, fid, transport)
    assert before["runtime_state"] == "RUNNING"
    old_epoch = before["runtime_epoch"]

    # Edge restart (§4.4): epoch rotates, RUNNING is NOT restored.
    edge.reboot(next_boot_id=f["boots"][0]["boot_id"] + "-rt")
    assert edge.runtime_epoch != old_epoch
    assert edge.processed_frame_count == 0
    assert edge.config_applied is False
    after_reboot = status_of(rc, edge, fid, transport)
    assert after_reboot["runtime_epoch"] == edge.runtime_epoch
    assert after_reboot["runtime_epoch"] != old_epoch
    assert after_reboot["config_applied"] is False
    assert after_reboot["effective_config_hash"] is None
    assert after_reboot["processed_frame_count"] == 0
    # No command seen yet on the new epoch: state is unknown (key absent),
    # and RUNNING is never restored across a restart (§4.4).
    assert "runtime_state" not in after_reboot

    # Re-apply + re-start on the new epoch starts counting from zero.
    rc.prepare_and_apply(fid, f["bundle"], edge, transport, max_rounds=5)
    rc.send_session_start(fid)
    transport.pump(edge, rc, max_rounds=5)
    edge.process_frames(2)
    revived = status_of(rc, edge, fid, transport)
    assert revived["runtime_epoch"] == edge.runtime_epoch != old_epoch
    assert revived["runtime_state"] == "RUNNING"
    assert revived["processed_frame_count"] == 2


def _active_bundle(bundle):
    """Clone a bundle whose first rule runs in ACTIVE mode (digest-stamped)."""
    bad = copy.deepcopy(bundle)
    bad["risk_policy"]["rules"][0]["mode"] = "ACTIVE"
    bad["risk_policy"] = set_digest(bad["risk_policy"], "content_sha256")
    return set_digest(bad, "bundle_sha256")


def test_active_policy_refused_and_reported_as_degraded(tmp_path):
    clock, store, rc, edge, f, transport = make_pair(tmp_path, "rt-active")
    fid = f["flight_id"]
    bad = _active_bundle(f["bundle"])
    with pytest.raises(ApplyError):
        rc.prepare_and_apply(fid, bad, edge, transport, max_rounds=5)
    assert edge.config_applied is False
    assert edge.effective_config_hash is None
    assert rc.last_rejection[fid]["reasons"] != []
    reported = status_of(rc, edge, fid, transport)
    assert reported["config_applied"] is False
    assert reported["effective_config_hash"] is None
    assert "policy_install_rejected" in reported["degraded_reasons"]


def test_apply_is_not_a_start_for_the_legacy_running_bool(tmp_path):
    """APPLY must not flip the legacy `running` bool (contract §2).

    The C++ endpoint leaves `running` false until SESSION_START: it means
    "a start was requested and not stopped". The fake Edge must agree, or an
    RC-side check would see two different truths depending on which side
    produced the status.
    """
    clock, store, rc, edge, f, transport = make_pair(tmp_path, "rt-apply-not-start")
    fid = f["flight_id"]
    rc.prepare_and_apply(fid, f["bundle"], edge, transport, max_rounds=5)

    status = status_of(rc, edge, fid, transport)
    assert status["running"] is False, "apply must not report a running session"
    assert status["runtime_state"] == "CONFIG_APPLIED"
    assert status["config_applied"] is True
    assert edge.processed_frame_count == 0, "no frame is processed before a start"

    rc.send_session_start(fid)
    transport.pump(edge, rc, max_rounds=5)
    status = status_of(rc, edge, fid, transport)
    assert status["running"] is True
    assert status["runtime_state"] in {"STARTING", "RUNNING"}
