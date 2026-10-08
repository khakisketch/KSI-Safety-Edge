"""Canonical JSON bytes (RFC 8785 JCS) and SHA256 digest helpers.

Digest policy: a digest field (``content_sha256``, ``manifest_sha256``,
``bundle_sha256``) is the hex SHA256 of the canonical bytes of the object
**excluding the digest field itself**.
"""

from __future__ import annotations

import hashlib
from typing import Any

import rfc8785

__all__ = [
    "canonical_bytes",
    "digest_sha256",
    "digest_excluding",
    "verify_digest",
    "set_digest",
]


def canonical_bytes(obj: Any) -> bytes:
    """Return RFC 8785 canonical JSON bytes for ``obj``.

    Raises:
        ValueError: for NaN/Infinity floats or non-JSON-serializable values.
        TypeError: if ``obj`` is not a JSON object/dict at the top level is
            allowed too — any JSON value is accepted; only the *content*
            must be JSON-representable.
    """
    try:
        return rfc8785.dumps(obj)
    except (rfc8785.FloatDomainError, rfc8785.CanonicalizationError) as exc:
        raise ValueError(f"not canonicalizable as JSON: {exc}") from exc


def digest_sha256(obj: Any) -> str:
    """Hex SHA256 of :func:`canonical_bytes` of ``obj``."""
    return hashlib.sha256(canonical_bytes(obj)).hexdigest()


def digest_excluding(obj: dict, field: str) -> str:
    """Digest of a shallow copy of ``obj`` without ``field``."""
    if not isinstance(obj, dict):
        raise ValueError(f"digest_excluding needs a dict, got {type(obj).__name__}")
    return digest_sha256({k: v for k, v in obj.items() if k != field})


def verify_digest(obj: dict, field: str) -> bool:
    """True when ``obj[field]`` equals the digest excluding ``field``."""
    if not isinstance(obj, dict):
        return False
    claimed = obj.get(field)
    if not isinstance(claimed, str):
        return False
    try:
        return digest_excluding(obj, field) == claimed
    except ValueError:
        return False


def set_digest(obj: dict, field: str) -> dict:
    """Return a copy of ``obj`` with ``field`` set to its correct digest."""
    if not isinstance(obj, dict):
        raise ValueError(f"set_digest needs a dict, got {type(obj).__name__}")
    stamped = dict(obj)
    stamped[field] = digest_excluding(obj, field)
    return stamped
