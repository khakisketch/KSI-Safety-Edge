"""Operator responses: batch ingest, proof gating, idempotency, isolation, order,
reference integrity, same-batch conflicts, concurrent convergence."""

from __future__ import annotations

import concurrent.futures
import json
from copy import deepcopy
from pathlib import Path

from ksi_contracts import set_digest
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session as SASession

from core_helpers import AGENT, ORGB, RC, fresh_uuid, make_event, make_session, register_flight

EXAMPLE = json.loads(
    (Path(__file__).resolve().parents[2] / "contracts" / "examples" / "operator-response.v1.example.json").read_text(
        encoding="utf-8"
    )
)


def make_response(flight_id, site_id, resp_id=None, **over):
    p = deepcopy(EXAMPLE)
    p["response_id"] = resp_id or fresh_uuid()
    p["candidate_id"] = fresh_uuid()
    p["flight_id"] = flight_id
    p["site_id"] = site_id
    p["session_id"] = fresh_uuid()
    p["evidence"] = {
        "source_id": p["candidate_id"],
        "received_at_utc": "2026-10-20T02:10:00Z",
        "policy_id": None,
        "policy_version": None,
    }
    p.update(over)
    return set_digest(p, "content_sha256")


def _post(client, items, batch_id="r1", token=RC):
    return client.post(
        "/operator-responses", token=token, json={"client_batch_id": batch_id, "items": items}
    )


def _listed(client, fid, token=AGENT):
    r = client.get(f"/flights/{fid}/operator-responses", token=token)
    assert r.status_code == 200, r.text
    return r.json()["items"]


def test_accept_valid_response_and_list(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    r1 = make_response(fid, flight["site_id"])
    r = _post(client, [r1])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["accepted_ids"] == [r1["response_id"]]
    assert body["duplicate_ids"] == []
    assert body["durable"] is True
    assert body["server_batch_id"]
    assert _listed(client, fid) == [r1]


def test_reject_unproven_delivery_state(client):
    flight = register_flight(client)
    fid = flight["flight_id"]
    bad = make_response(fid, flight["site_id"], delivery_state="SENT_CONFIRMED", transmission_proof=None)
    r = _post(client, [bad])
    assert r.status_code == 422, r.text
    # nothing stored
    assert _listed(client, fid) == []


def test_reject_wrong_enum(client):
    flight = register_flight(client)
    bad = make_response(flight["flight_id"], flight["site_id"], action="ACKNOWLEDGE")
    r = _post(client, [bad])
    assert r.status_code == 422, r.text


def test_reject_missing_evidence_field(client):
    flight = register_flight(client)
    bad = make_response(flight["flight_id"], flight["site_id"])
    del bad["evidence"]["policy_version"]
    bad = set_digest(bad, "content_sha256")
    r = _post(client, [bad])
    assert r.status_code == 422, r.text


def test_idempotent_batch_replay(client):
    flight = register_flight(client)
    fid, site = flight["flight_id"], flight["site_id"]
    r1, r2 = make_response(fid, site), make_response(fid, site)
    r = _post(client, [r1, r2], batch_id="b1")
    assert r.status_code == 200, r.text
    first = r.json()
    r = _post(client, [r1, r2], batch_id="b1")
    assert r.status_code == 200
    assert r.json()["server_batch_id"] == first["server_batch_id"]
    assert r.json()["accepted_ids"] == first["accepted_ids"]
    # same id + different content under same batch id -> 409
    r2b = deepcopy(r2)
    r2b["note"] = "changed"
    r2b = set_digest(r2b, "content_sha256")
    r = _post(client, [r1, r2b], batch_id="b1")
    assert r.status_code == 409
    # new batch with already-stored item (same content) -> duplicate_ids
    r3 = make_response(fid, site)
    r = _post(client, [r1, r3], batch_id="b2")
    assert r.status_code == 200, r.text
    assert r.json()["duplicate_ids"] == [r1["response_id"]]
    assert r.json()["accepted_ids"] == [r3["response_id"]]


def test_conflict_same_id_different_content(client):
    flight = register_flight(client)
    fid, site = flight["flight_id"], flight["site_id"]
    r1 = make_response(fid, site)
    assert _post(client, [r1], batch_id="c1").status_code == 200
    r1b = deepcopy(r1)
    r1b["note"] = "different content"
    r1b = set_digest(r1b, "content_sha256")
    r = _post(client, [r1b], batch_id="c2")
    assert r.status_code == 409


def test_same_batch_duplicate_id_same_content_ok(client):
    flight = register_flight(client)
    fid, site = flight["flight_id"], flight["site_id"]
    r1 = make_response(fid, site)
    r = _post(client, [r1, deepcopy(r1)], batch_id="d1")
    assert r.status_code == 200, r.text
    assert r.json()["accepted_ids"] == [r1["response_id"]]
    assert r.json()["duplicate_ids"] == [r1["response_id"]]
    assert [i["response_id"] for i in _listed(client, fid)] == [r1["response_id"]]


def test_same_batch_duplicate_id_different_content_rejects_atomically(client):
    flight = register_flight(client)
    fid, site = flight["flight_id"], flight["site_id"]
    r1 = make_response(fid, site)
    r1b = deepcopy(r1)
    r1b["note"] = "different content in same batch"
    r1b = set_digest(r1b, "content_sha256")
    r = _post(client, [r1, r1b], batch_id="d2")
    assert r.status_code == 409, r.text
    # atomic: nothing written, and the good item is still ingestible fresh
    assert _listed(client, fid) == []
    r = _post(client, [r1], batch_id="d3")
    assert r.status_code == 200, r.text
    assert _listed(client, fid) == [r1]


def test_reject_wrong_site(client):
    flight = register_flight(client)
    bad = make_response(flight["flight_id"], fresh_uuid())
    r = _post(client, [bad])
    assert r.status_code == 422, r.text
    assert "SITE_MISMATCH" in r.text
    assert _listed(client, flight["flight_id"]) == []


def test_reject_cross_flight_candidate_and_evidence(client):
    f1 = register_flight(client)
    f2 = register_flight(client)
    ev = make_event(f2["flight_id"])
    r = client.post(f"/flights/{f2['flight_id']}/events:batch", token=RC,
                    json={"client_batch_id": "e1", "items": [ev]})
    assert r.status_code == 200, r.text
    # candidate known in another flight -> 422
    bad = make_response(f1["flight_id"], f1["site_id"], candidate_id=ev["event_id"])
    bad["evidence"] = dict(bad["evidence"], source_id=fresh_uuid())
    bad = set_digest(bad, "content_sha256")
    r = _post(client, [bad], batch_id="x1")
    assert r.status_code == 422, r.text
    assert "CANDIDATE_CROSS_FLIGHT" in r.text
    # evidence source known in another flight -> 422
    bad2 = make_response(f1["flight_id"], f1["site_id"])
    bad2["evidence"] = dict(bad2["evidence"], source_id=ev["event_id"])
    bad2 = set_digest(bad2, "content_sha256")
    r = _post(client, [bad2], batch_id="x2")
    assert r.status_code == 422, r.text
    assert "EVIDENCE_CROSS_FLIGHT" in r.text
    assert _listed(client, f1["flight_id"]) == []
    # same-flight candidate is accepted
    ev1 = make_event(f1["flight_id"])
    r = client.post(f"/flights/{f1['flight_id']}/events:batch", token=RC,
                    json={"client_batch_id": "e2", "items": [ev1]})
    assert r.status_code == 200, r.text
    ok = make_response(f1["flight_id"], f1["site_id"], candidate_id=ev1["event_id"])
    ok["evidence"] = dict(ok["evidence"], source_id=ev1["event_id"])
    ok = set_digest(ok, "content_sha256")
    assert _post(client, [ok], batch_id="x3").status_code == 200


def test_reject_cross_flight_session(client):
    f1 = register_flight(client)
    f2 = register_flight(client)
    sess = make_session(f2["flight_id"])
    r = client.post(f"/flights/{f2['flight_id']}/flight-sessions", token=RC, json=sess)
    assert r.status_code == 200, r.text
    bad = make_response(f1["flight_id"], f1["site_id"], session_id=sess["session_id"])
    r = _post(client, [bad])
    assert r.status_code == 422, r.text
    assert "SESSION_MISMATCH" in r.text
    assert _listed(client, f1["flight_id"]) == []
    # session of the same flight is accepted
    sess1 = make_session(f1["flight_id"])
    assert client.post(f"/flights/{f1['flight_id']}/flight-sessions", token=RC, json=sess1).status_code == 200
    ok = make_response(f1["flight_id"], f1["site_id"], session_id=sess1["session_id"])
    assert _post(client, [ok], batch_id="s2").status_code == 200


def test_cross_org_reference_and_item_never_leak(client):
    from ksi_core import models as m

    flight = register_flight(client)
    fid, site = flight["flight_id"], flight["site_id"]
    other_event = fresh_uuid()
    other_session = fresh_uuid()
    other_response = fresh_uuid()
    db = client.app.state.session_factory()
    try:
        db.add(m.DetectionEvent(org_id="org-b", flight_id="b-flight", event_id=other_event,
                                payload={"event_id": other_event}, canonical_hash="x"))
        db.add(m.FlightSession(org_id="org-b", flight_id="b-flight", session_id=other_session,
                               payload={"session_id": other_session}, canonical_hash="x"))
        db.add(m.OperatorResponse(org_id="org-b", flight_id="b-flight", response_id=other_response,
                                  payload={"response_id": other_response}, canonical_hash="x"))
        db.commit()
    finally:
        db.close()
    for kwargs, batch in (
        ({"candidate_id": other_event}, "g1"),
        ({"session_id": other_session}, "g2"),
    ):
        bad = make_response(fid, site, **kwargs)
        if "candidate_id" in kwargs:
            bad["evidence"] = dict(bad["evidence"], source_id=fresh_uuid())
            bad = set_digest(bad, "content_sha256")
        r = _post(client, [bad], batch_id=batch)
        assert r.status_code == 404, (kwargs, r.status_code, r.text)
        assert other_event not in r.text and "org-b" not in r.text
    # same response_id owned by another org -> 404, never a diff/overwrite
    clash = make_response(fid, site, resp_id=other_response)
    r = _post(client, [clash], batch_id="g3")
    assert r.status_code == 404, r.text
    assert _listed(client, fid) == []


def test_cross_org_isolation(client):
    flight = register_flight(client)
    fid, site = flight["flight_id"], flight["site_id"]
    r1 = make_response(fid, site)
    assert _post(client, [r1], batch_id="x1").status_code == 200
    # other org reads the flight's responses -> 404, never leaks bytes
    r = client.get(f"/flights/{fid}/operator-responses", token=ORGB)
    assert r.status_code == 404
    assert r1["response_id"] not in r.text
    # other org (read-only token) cannot write either
    r = _post(client, [make_response(fid, site)], batch_id="x2", token=ORGB)
    assert r.status_code in (401, 403)
    # unknown flight -> 404
    r = client.get(f"/flights/{fresh_uuid()}/operator-responses", token=AGENT)
    assert r.status_code == 404


def test_get_returns_only_that_flight_in_order(client):
    f1 = register_flight(client)
    f2 = register_flight(client)
    a1, a2 = make_response(f1["flight_id"], f1["site_id"]), make_response(f1["flight_id"], f1["site_id"])
    b1 = make_response(f2["flight_id"], f2["site_id"])
    assert _post(client, [a1, a2], batch_id="o1").status_code == 200
    assert _post(client, [b1], batch_id="o2").status_code == 200
    items1 = _listed(client, f1["flight_id"])
    items2 = _listed(client, f2["flight_id"])
    assert [i["response_id"] for i in items1] == [a1["response_id"], a2["response_id"]]
    assert [i["response_id"] for i in items2] == [b1["response_id"]]


def test_commit_failure_never_500s(client, monkeypatch):
    flight = register_flight(client)
    fid, site = flight["flight_id"], flight["site_id"]
    real_commit = SASession.commit
    calls = {"n": 0}

    def flaky(self, *a, **k):
        calls["n"] += 1
        if calls["n"] == 1:
            raise IntegrityError("INSERT", {}, Exception("simulated race"))
        return real_commit(self, *a, **k)

    monkeypatch.setattr(SASession, "commit", flaky)
    r = _post(client, [make_response(fid, site)], batch_id="f1")
    assert r.status_code == 409, r.text
    assert _listed(client, fid) == []


def test_concurrent_duplicate_posts_converge(client):
    flight = register_flight(client)
    fid, site = flight["flight_id"], flight["site_id"]
    item = make_response(fid, site)
    body = {"client_batch_id": "race1", "items": [item]}

    def once(_):
        return client.post("/operator-responses", token=RC, json=body)

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        resps = list(ex.map(once, range(8)))
    assert all(r.status_code == 200 for r in resps), [(r.status_code, r.text) for r in resps]
    assert len({r.json()["server_batch_id"] for r in resps}) == 1
    assert [i["response_id"] for i in _listed(client, fid)] == [item["response_id"]]
