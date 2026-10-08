"""Shared fixtures: isolated app per test + valid-payload builders."""

from __future__ import annotations

import asyncio
import hashlib
import json
import os
import uuid
from copy import deepcopy
from pathlib import Path

import httpx
import pytest
from ksi_contracts import set_digest

from ksi_core.app import create_app

CONTRACTS = Path(__file__).resolve().parents[2] / "contracts" / "examples"

TOKENS = {
    "tok-orgA-rc": {"org_id": "org-a", "scopes": ["rc", "read"]},
    "tok-orgA-agent": {"org_id": "org-a", "scopes": ["read"]},
    "tok-orgB-read": {"org_id": "org-b", "scopes": ["read"]},
}

RC = "tok-orgA-rc"
AGENT = "tok-orgA-agent"
ORGB = "tok-orgB-read"


def example(name: str) -> dict:
    return json.loads((CONTRACTS / name).read_text(encoding="utf-8"))


def fresh_uuid() -> str:
    return str(uuid.uuid4())


class Client:
    """Sync wrapper over httpx AsyncClient + ASGITransport."""

    def __init__(self, app, base="http://test/api/v1/edge"):
        self.app = app
        self.base = base

    def _run(self, method: str, path: str, token=None, **kwargs):
        async def go():
            transport = httpx.ASGITransport(app=self.app)
            headers = dict(kwargs.pop("headers", {}) or {})
            if token is not None:
                headers["Authorization"] = f"Bearer {token}"
            async with httpx.AsyncClient(transport=transport, base_url=self.base) as client:
                return await client.request(method, path, headers=headers, **kwargs)

        return asyncio.run(go())

    def get(self, path, token=None, **kwargs):
        return self._run("GET", path, token, **kwargs)

    def post(self, path, token=None, **kwargs):
        return self._run("POST", path, token, **kwargs)

    def put(self, path, token=None, **kwargs):
        return self._run("PUT", path, token, **kwargs)


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("KSI_DB_URL", f"sqlite:///{tmp_path}/test.db")
    monkeypatch.setenv("KSI_OBJECT_ROOT", str(tmp_path / "objects"))
    monkeypatch.setenv("KSI_DEV_TOKENS", json.dumps(TOKENS))
    app = create_app()
    return Client(app)


# --- payload builders (all digests recomputed; fresh ids) --------------------


def make_snapshot(flight_id=None, site_id=None, mission_id=None, revision_id=None, zone_id=None, rev=None):
    p = example("mission-snapshot.example.json")
    p["mission_id"] = mission_id or fresh_uuid()
    p["flight_id"] = flight_id or fresh_uuid()
    p["site_id"] = site_id or fresh_uuid()
    p["mission_revision_id"] = revision_id or fresh_uuid()
    zid = zone_id or fresh_uuid()
    rid = rev or fresh_uuid()
    p["zones"][0]["zone_id"] = zid
    p["zones"][0]["revision_id"] = rid
    return set_digest(p, "content_sha256")


def make_policy(site_id=None, policy_id=None, revision_id=None):
    p = example("risk-policy.v1.example.json")
    p["policy_id"] = policy_id or fresh_uuid()
    p["revision_id"] = revision_id or fresh_uuid()
    p["site_id"] = site_id or fresh_uuid()
    return set_digest(p, "content_sha256")


def make_bundle(site_id=None, snapshot=None, policy=None, bundle_id=None):
    p = example("mission-bundle.v1.example.json")
    snap = snapshot or make_snapshot(site_id=site_id or fresh_uuid())
    pol = policy or make_policy(site_id=snap["site_id"])
    # keep site consistency: bundle/site/snapshot/policy agree
    pol = dict(pol)
    pol["site_id"] = snap["site_id"]
    pol = set_digest(pol, "content_sha256")
    p["mission_snapshot"] = snap
    p["risk_policy"] = pol
    p["site_id"] = snap["site_id"]
    p["bundle_id"] = bundle_id or fresh_uuid()
    return set_digest(p, "bundle_sha256")


def make_application(flight_id, bundle_id, bundle_sha256, app_id=None, applied=True):
    p = example("mission-application.v1.example.json")
    p["application_id"] = app_id or fresh_uuid()
    p["flight_id"] = flight_id
    p["bundle_id"] = bundle_id
    p["bundle_sha256"] = bundle_sha256
    p["applied"] = applied
    p["rejection_reasons"] = [] if applied else ["not_supported"]
    return p


def make_session(flight_id, session_id=None, boot_id=None):
    p = example("flight-session.v1.example.json")
    p["session_id"] = session_id or fresh_uuid()
    p["flight_id"] = flight_id
    p["edge_boot_id"] = boot_id or fresh_uuid()
    return p


def make_observation(flight_id, obs_id=None):
    p = example("observation.example.json")
    p["observation_id"] = obs_id or fresh_uuid()
    p["flight_id"] = flight_id
    return p


def make_event(flight_id, event_id=None, obs_id=None, artifacts=()):
    p = example("detection-event.v1.example.json")
    p["event_id"] = event_id or fresh_uuid()
    p["flight_id"] = flight_id
    p["observation_id"] = obs_id or fresh_uuid()
    p["evidence_artifact_ids"] = list(artifacts)
    return p


def make_flight_summary(flight_id, summary_id=None):
    p = example("flight-summary.v1.example.json")
    p["summary_id"] = summary_id or fresh_uuid()
    p["flight_id"] = flight_id
    return p


def make_spatial_summary(flight_id, summary_id=None):
    p = example("spatial-summary.v1.example.json")
    p["summary_id"] = summary_id or fresh_uuid()
    p["flight_id"] = flight_id
    return p


def make_annotation(event_id, flight_id, ann_id=None, ann_type="FALSE_POSITIVE_SUSPECTED", supersedes=None):
    p = example("operator-annotation.example.json")
    p["annotation_id"] = ann_id or fresh_uuid()
    p["event_id"] = event_id
    p["flight_id"] = flight_id
    p["annotation_type"] = ann_type
    p["supersedes_annotation_id"] = supersedes
    return p


def make_manifest(flight_id, artifacts: list, revision_id=None):
    """artifacts: list of (artifact_id, bytes, media_type, required, source_kind).

    Non-UUID artifact ids are replaced with fresh UUIDs (schema requires
    uuid format); the manifest's final ids are authoritative.
    """
    import re

    uuid_re = re.compile(r"^[0-9a-fA-F-]{36}$")
    p = example("flight-upload-manifest.example.json")
    p["flight_id"] = flight_id
    p["mission_revision_id"] = revision_id or fresh_uuid()
    entries = []
    blobs = {}
    for i, (aid, data, media, required, kind) in enumerate(artifacts):
        if not isinstance(aid, str) or not uuid_re.match(aid):
            aid = fresh_uuid()
        entry = {
            "artifact_id": aid,
            "source_kind": kind,
            "sha256": hashlib.sha256(data).hexdigest(),
            "size_bytes": len(data),
            "media_type": media,
            "required": required,
            "segment_index": i if kind == "RC_PROXY_SEGMENT" else None,
        }
        entries.append(entry)
        blobs[aid] = data
    p["artifacts"] = entries
    p["gaps"] = []
    return set_digest(p, "manifest_sha256"), blobs


def register_flight(client: Client, token=RC, flight_id=None, site_id=None):
    fid = flight_id or fresh_uuid()
    body = {
        "flight_id": fid,
        "site_id": site_id or fresh_uuid(),
        "rc_device_id": fresh_uuid(),
        "mission_id": fresh_uuid(),
        "mission_revision_id": fresh_uuid(),
        "bundle_sha256": "0" * 64,
        "started_at_utc": "2026-10-08T02:00:00Z",
        "ended_at_utc": None,
    }
    r = client.post("/flights", token=token, json=body)
    assert r.status_code == 200, r.text
    return body
