"""Schema loading with relative-$ref resolution across ``contracts/schemas``."""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

__all__ = ["schemas_dir", "load_schema", "validator_for", "SCHEMA_NAMES"]

SCHEMA_NAMES = (
    "flight-upload-manifest.v1",
    "mission-snapshot.v1",
    "observation.v1",
    "operator-annotation.v1",
    "review-finding.v1",
    "risk-policy.v1",
    "spatial-relation.v1",
    "spatial-summary.v1",
    "mission-bundle.v1",
    "mission-application.v1",
    "flight-session.v1",
    "detection-event.v1",
    "flight-summary.v1",
)


def schemas_dir() -> Path:
    """Directory holding ``*.schema.json`` files.

    ``KSI_CONTRACTS_DIR`` env var wins; otherwise walk up from this file to
    find a ``contracts/schemas`` directory (repo checkout layout).
    """
    import os

    env = os.environ.get("KSI_CONTRACTS_DIR")
    if env:
        return Path(env)
    here = Path(__file__).resolve()
    for parent in (here, *here.parents):
        candidate = parent / "contracts" / "schemas"
        if candidate.is_dir():
            return candidate
    raise FileNotFoundError("contracts/schemas directory not found")


def _schema_path(name: str) -> Path:
    return schemas_dir() / f"{name}.schema.json"


@lru_cache(maxsize=1)
def _registry() -> Registry:
    """A referencing registry seeded with every schema file in the dir."""
    resources: list[tuple[str, Resource[Any]]] = []
    for path in sorted(schemas_dir().glob("*.schema.json")):
        schema = json.loads(path.read_text(encoding="utf-8"))
        uri = schema.get("$id", path.resolve().as_uri())
        resources.append((uri, Resource.from_contents(schema, default_specification=DRAFT202012)))
    return Registry().with_resources(resources)


@lru_cache(maxsize=None)
def load_schema(name: str) -> dict:
    """Load the schema dict for ``name`` (e.g. ``mission-bundle.v1``)."""
    if name not in SCHEMA_NAMES:
        raise KeyError(
            f"unknown schema {name!r}; known: {', '.join(SCHEMA_NAMES)}"
        )
    path = _schema_path(name)
    if not path.is_file():
        raise FileNotFoundError(f"schema file missing: {path}")
    schema = json.loads(path.read_text(encoding="utf-8"))
    # Resolve (crawl) relative $refs so missing targets fail fast here.
    _registry().crawl()
    return schema


def validator_for(name: str) -> Draft202012Validator:
    """A Draft 2020-12 validator for ``name`` with cross-file $ref support."""
    return Draft202012Validator(
        load_schema(name),
        registry=_registry(),
        format_checker=FormatChecker(),
    )
