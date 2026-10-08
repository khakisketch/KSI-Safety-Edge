"""Shared E2E rig: core as a uvicorn subprocess + client fixtures.

Scenario helpers live in ``harness_helpers.py`` (uniquely named module).
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import httpx
import pytest

from ksi_harness import CoreClient

REPO = str(Path(__file__).resolve().parents[2])
TOKENS = {
    "tok-orgA-rc": {"org_id": "org-a", "scopes": ["rc", "read"]},
    "tok-orgB-read": {"org_id": "org-b", "scopes": ["read"]},
    "tok-orgB-rc": {"org_id": "org-b", "scopes": ["rc", "read"]},
}
ORG_A = "tok-orgA-rc"
ORG_B = "tok-orgB-read"


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


@pytest.fixture(scope="module")
def core_server(tmp_path_factory):
    """Live core on a free port (temp DB/objects); module-scoped."""
    tmp = tmp_path_factory.mktemp("core")
    port = _free_port()
    env = {
        **os.environ,
        "KSI_DB_URL": f"sqlite:///{tmp}/core.db",
        "KSI_OBJECT_ROOT": str(tmp / "objects"),
        "KSI_DEV_TOKENS": json.dumps(TOKENS),
    }
    proc = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "ksi_core.app:create_app", "--factory",
         "--host", "127.0.0.1", "--port", str(port)],
        cwd=REPO, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
    )
    base = f"http://127.0.0.1:{port}/api/v1/edge"
    deadline = time.monotonic() + 25
    while True:
        try:
            resp = httpx.get(base + "/healthz", timeout=2.0)
            if resp.status_code == 200:
                break
        except Exception:
            pass
        if time.monotonic() > deadline:
            proc.terminate()
            raise RuntimeError("core subprocess did not become healthy")
        time.sleep(0.1)
    yield base
    proc.terminate()
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()


@pytest.fixture()
def client(core_server):
    cli = CoreClient(core_server, ORG_A)
    yield cli
    cli.close()


@pytest.fixture()
def client_b(core_server):
    cli = CoreClient(core_server, ORG_B)
    yield cli
    cli.close()
