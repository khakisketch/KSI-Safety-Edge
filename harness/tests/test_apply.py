"""Mission prepare/apply/status/reboot mechanics (no server)."""

import copy

import pytest

from ksi_harness import EdgeReplay, FaultTransport, RCSimulator, RCStore, VirtualClock, flight_a
from ksi_harness.rc_sim import ApplyError


def make_pair(tmp_path, tag="apply"):
    clock = VirtualClock()
    store = RCStore(tmp_path / "rc.db")
    rc = RCSimulator(store, clock)
    f = flight_a(tag=tag)
    edge = EdgeReplay(f["flight_id"], clock, boot_id=f["boots"][0]["boot_id"])
    return clock, store, rc, edge, f


def test_prepare_apply_status_query(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    app = rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, FaultTransport(), max_rounds=5)
    assert app["bundle_sha256"] == f["bundle"]["bundle_sha256"]
    assert app["applied"] is True and app["edge_boot_id"] == edge.edge_boot_id
    # STATUS_QUERY reflects the same applied state.
    rc.send_status_query(f["flight_id"])
    FaultTransport().pump(edge, rc, max_rounds=5)
    status = rc.last_status[f["flight_id"]]
    assert status["applied_bundle_sha256"] == f["bundle"]["bundle_sha256"]
    assert status["application_id"] == app["application_id"]
    assert status["running"] is True


def test_prepare_rejects_tampered_bundle(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    bad = copy.deepcopy(f["bundle"])
    bad["risk_policy"]["rules"][0]["threshold_m"] = 9.9  # digest now stale
    with pytest.raises(ApplyError):
        rc.prepare_and_apply(f["flight_id"], bad, edge, FaultTransport(), max_rounds=5)
    assert rc.applications(f["flight_id"]) == []
    assert edge.applied_bundle_sha256 is None


def test_apply_is_idempotent_by_msg_id(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    rc.send_prepare(f["flight_id"], f["bundle"])
    transport = FaultTransport()
    transport.pump(edge, rc, max_rounds=5)
    apply_env = rc.send_apply(f["flight_id"], f["bundle"])
    transport.pump(edge, rc, max_rounds=5)
    first = rc.applications(f["flight_id"])[-1]
    assert len(rc.applications(f["flight_id"])) == 1
    # Simulate the RC retrying the identical APPLY envelope (reply "lost"):
    # the Edge dedups and resends the SAME application, RC stores it once.
    assert edge.receive(copy.deepcopy(apply_env)) == "dedup"
    transport.pump(edge, rc, max_rounds=5)
    apps = rc.applications(f["flight_id"])
    assert len(apps) == 1
    assert apps[0]["application_id"] == first["application_id"]
    assert rc.last_rejection.get(f["flight_id"]) is None


def test_reboot_requires_reapply_with_new_application(tmp_path):
    clock, store, rc, edge, f = make_pair(tmp_path)
    first = rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, FaultTransport(), max_rounds=5)
    new_boot = "11111111-1111-4111-8111-00000000aa01"
    edge.reboot(next_boot_id=new_boot)
    assert edge.applied_bundle_sha256 is None
    second = rc.prepare_and_apply(f["flight_id"], f["bundle"], edge, FaultTransport(), max_rounds=5)
    assert second["application_id"] != first["application_id"]
    assert second["edge_boot_id"] == new_boot
    apps = rc.applications(f["flight_id"])
    assert [a["bundle_sha256"] for a in apps] == [f["bundle"]["bundle_sha256"]] * 2
