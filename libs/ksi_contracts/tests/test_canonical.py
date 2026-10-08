"""Canonical JSON (RFC 8785 JCS) golden vectors and digest-exclusion behavior."""

import math

import pytest

from ksi_contracts.canonical import (
    canonical_bytes,
    digest_excluding,
    digest_sha256,
    set_digest,
    verify_digest,
)


def test_key_ordering_and_compact_float_vector():
    assert canonical_bytes({"b": 1, "a": [2, 0.5]}) == b'{"a":[2,0.5],"b":1}'


def test_integers_unicode_null_nesting():
    assert canonical_bytes({"z": [1, None, {"k": []}], "n": None, "i": 5}) == (
        b'{"i":5,"n":null,"z":[1,null,{"k":[]}]}'
    )
    out = canonical_bytes({"u": "héllo✓한글"})
    assert out == '{"u":"héllo✓한글"}'.encode("utf-8")


def test_float_vectors_follow_rfc8785():
    assert canonical_bytes({"f": 4.2}) == b'{"f":4.2}'
    assert canonical_bytes({"e": 1e21}) == b'{"e":1e+21}'


@pytest.mark.parametrize("bad", [float("nan"), float("inf"), float("-inf")])
def test_nonfinite_floats_rejected(bad):
    with pytest.raises(ValueError):
        canonical_bytes({"x": bad})


def test_non_json_serializable_rejected():
    with pytest.raises(ValueError):
        canonical_bytes({"x": object()})
    with pytest.raises(ValueError):
        canonical_bytes({"x": {1, 2}})


def test_digest_excluding_ignores_named_field():
    obj = {"a": 1, "content_sha256": "0" * 64}
    assert digest_excluding(obj, "content_sha256") == digest_sha256({"a": 1})
    # Original is not mutated.
    assert obj == {"a": 1, "content_sha256": "0" * 64}


def test_set_and_verify_digest_roundtrip():
    obj = {"a": 1, "content_sha256": "0" * 64}
    stamped = set_digest(obj, "content_sha256")
    assert stamped["content_sha256"] == digest_sha256({"a": 1})
    assert obj["content_sha256"] == "0" * 64  # input untouched
    assert verify_digest(stamped, "content_sha256") is True
    tampered = dict(stamped, a=2)
    assert verify_digest(tampered, "content_sha256") is False


def test_verify_digest_missing_or_wrong_field():
    assert verify_digest({"a": 1}, "content_sha256") is False
    assert verify_digest({"a": 1, "content_sha256": "xyz"}, "content_sha256") is False


def test_digest_is_hex_sha256_of_canonical_bytes():
    import hashlib

    obj = {"b": 1, "a": [2, 0.5]}
    assert digest_sha256(obj) == hashlib.sha256(canonical_bytes(obj)).hexdigest()
    assert len(digest_sha256(obj)) == 64


def test_canonical_rejects_nan_nested():
    with pytest.raises(ValueError):
        canonical_bytes({"a": [1, {"b": math.nan}]})
