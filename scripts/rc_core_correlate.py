#!/usr/bin/env python3
"""Correlate a pulled RC device store with Core READ responses (read-only).

A9-4 evidence tool: after a real RC ground session, this proves that what the
RC actually recorded is actually reachable from the Core read API. It only
compares the pulled store (see `scripts/rc_device_day.sh pull-store`) with
Core GET responses; it never runs uploads and never infers success.

Usage:

    python scripts/rc_core_correlate.py \
        --store docs/verification/evidence/<date>-a9-4/rc-store.tar \
        --core-url http://127.0.0.1:8000 --token <read-token> \
        --out docs/verification/evidence/<date>-a9-4/08-rc-core-correlation.txt

Exit codes: 0 = 대조 OK · 2 = DISCREPANCY · 1 = 실행/전송 오류.
Tokens are never printed (env `KSI_CORE_TOKEN` also works).
"""

from __future__ import annotations

import argparse
import os
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "harness" / "src"))

from ksi_harness.core_client import CoreClient, CoreError  # noqa: E402
from ksi_harness.rc_core_correlate import correlate, render_text  # noqa: E402
from ksi_harness.rc_store_read import extract_tar, load_device_store  # noqa: E402


class CoreClientReads:
    """Correlation adapter: CoreError(404) -> None, read-only."""

    def __init__(self, client: CoreClient) -> None:
        self._client = client

    def flight(self, flight_id: str) -> dict | None:
        try:
            return self._client.get_flight(flight_id)
        except CoreError as exc:
            if exc.status == 404:
                return None
            raise

    def artifacts(self, flight_id: str) -> list[dict]:
        return self._client.list_artifacts(flight_id)

    def observations(self, flight_id: str) -> list[dict]:
        return self._client.list_observations(flight_id)

    def events(self, flight_id: str) -> list[dict]:
        return self._client.list_flight_events(flight_id)

    def evidence_ids(self, event_id: str, listed_event: dict) -> list[str] | None:
        try:
            view = self._client.get_event(event_id)
        except CoreError:
            return None
        ids = view.get("evidence_artifact_ids")
        return [i for i in ids if isinstance(i, str)] if isinstance(ids, list) else None

    def annotation_ids(self, event_id: str) -> list[str] | None:
        try:
            items = self._client.list_annotations(event_id).get("items", [])
        except CoreError:
            return None
        return [
            i.get("annotation_id") for i in items
            if isinstance(i, dict) and isinstance(i.get("annotation_id"), str)
        ]

    def sessions(self, flight_id: str) -> list[dict] | None:
        try:
            return self._client.list_sessions(flight_id).get("items", [])
        except CoreError:
            return None


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", required=True, help="rc-store 디렉터리 또는 rc-store.tar")
    parser.add_argument("--core-url", default=os.environ.get("KSI_CORE_URL", ""))
    parser.add_argument("--token", default=os.environ.get("KSI_CORE_TOKEN", ""))
    parser.add_argument("--out", default=None, help="보고서 저장 경로(미지정 시 stdout만)")
    parser.add_argument("--flight", action="append", default=None,
                        help="특정 비행만 대조(반복 가능, 미지정 시 스토어 전체)")
    args = parser.parse_args(argv)

    if not args.core_url or not args.token:
        print("오류: --core-url/--token(또는 KSI_CORE_URL/KSI_CORE_TOKEN)이 필요합니다.", file=sys.stderr)
        return 1

    store_path = Path(args.store)
    if not store_path.exists():
        print(f"오류: 스토어가 없습니다: {store_path}", file=sys.stderr)
        return 1

    cleanup: tempfile.TemporaryDirectory | None = None
    try:
        if store_path.is_file() and store_path.suffix == ".tar":
            cleanup = tempfile.TemporaryDirectory(prefix="rc-store-")
            extract_tar(store_path, Path(cleanup.name))
            store_dir = Path(cleanup.name)
        else:
            store_dir = store_path
        store = load_device_store(store_dir)
        if not store.flights:
            print("오류: 스토어에 flights.ndjson 기록이 없습니다(pull 확인).", file=sys.stderr)
            return 1

        client = CoreClient(args.core_url, args.token)
        try:
            report = correlate(store, CoreClientReads(client), args.flight)
        finally:
            client.close()

        text = render_text(report, args.core_url)
        print(text)
        if args.out:
            out = Path(args.out)
            out.parent.mkdir(parents=True, exist_ok=True)
            out.write_text(text + "\n", encoding="utf-8")
            print(f"\n저장: {out}")
        return 0 if report.verdict == "OK" else 2
    except CoreError as exc:
        print(f"오류: Core 요청 실패 {exc.method} {exc.path} -> {exc.status}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"오류: {exc}", file=sys.stderr)
        return 1
    finally:
        if cleanup is not None:
            cleanup.cleanup()


if __name__ == "__main__":
    sys.exit(main())
