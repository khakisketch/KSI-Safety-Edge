"""Fixtures for core API tests. Shared helpers live in ``core_helpers.py``."""

from __future__ import annotations

import json

import pytest

from core_helpers import TOKENS, Client
from ksi_core.app import create_app


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("KSI_DB_URL", f"sqlite:///{tmp_path}/test.db")
    monkeypatch.setenv("KSI_OBJECT_ROOT", str(tmp_path / "objects"))
    monkeypatch.setenv("KSI_DEV_TOKENS", json.dumps(TOKENS))
    app = create_app()
    return Client(app)
