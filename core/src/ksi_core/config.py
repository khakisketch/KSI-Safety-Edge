"""Server configuration, read from the environment at app-creation time."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path


def _repo_root() -> Path:
    # config.py lives at <repo>/core/src/ksi_core/config.py
    return Path(__file__).resolve().parents[3]


DEFAULT_DEV_TOKENS = {
    "tok-orgA-rc": {"org_id": "org-a", "scopes": ["rc", "read"]},
    "tok-orgA-agent": {"org_id": "org-a", "scopes": ["read"]},
    "tok-orgB-read": {"org_id": "org-b", "scopes": ["read"]},
}


@dataclass
class Settings:
    db_url: str
    object_root: Path
    dev_tokens: dict = field(default_factory=dict)


def load_settings() -> Settings:
    """Read env vars now (called from ``create_app`` so tests can override)."""
    root = _repo_root()
    db_url = os.environ.get("KSI_DB_URL", f"sqlite:///{root / 'core' / 'var' / 'ksi_core.db'}")
    object_root = Path(os.environ.get("KSI_OBJECT_ROOT", root / "core" / "var" / "objects"))
    raw_tokens = os.environ.get("KSI_DEV_TOKENS")
    if raw_tokens:
        dev_tokens = json.loads(raw_tokens)
    else:
        dev_tokens = {k: dict(v) for k, v in DEFAULT_DEV_TOKENS.items()}
    return Settings(db_url=db_url, object_root=object_root, dev_tokens=dev_tokens)
