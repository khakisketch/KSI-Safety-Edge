"""Core DB 스키마:additive 마이그레이션(컬럼 추가).

`create_all`은 이미 있는 테이블을 고치지 않는다. 그래서 컬럼이 추가된 뒤에도
기존 DB 파일(개발용·운영용 모두)에는 그 컬럼이 없어서 질의가
"no such column"으로 실패한다. 이 테스트는 그 상태를 재현하고
`add_missing_columns`가 실제로 컬럼을 추가해 복구하는지 확인한다.
"""

from __future__ import annotations

import sqlite3

from ksi_core.db import add_missing_columns, create_all, make_engine


def _legacy_db(path) -> None:
    """컬럼이 추가되기 전에 만들어진 DB를 흉내낸다(테이블만 있고 컬럼은 없음)."""
    conn = sqlite3.connect(path)
    conn.execute(
        """
        CREATE TABLE sites (
            id INTEGER NOT NULL PRIMARY KEY,
            org_id VARCHAR NOT NULL,
            site_id VARCHAR NOT NULL,
            name VARCHAR NOT NULL,
            created_at DATETIME NOT NULL
        )
        """
    )
    conn.execute(
        "INSERT INTO sites (id, org_id, site_id, name, created_at)"
        " VALUES (1, 'org-a', 'site-legacy', '옛 현장', '2026-10-01T00:00:00')"
    )
    conn.commit()
    conn.close()


def test_add_missing_columns_adds_the_site_location_columns(tmp_path):
    db_path = tmp_path / "legacy.db"
    _legacy_db(db_path)

    engine = make_engine(f"sqlite:///{db_path}")
    add_missing_columns(engine)

    conn = sqlite3.connect(db_path)
    columns = {row[1] for row in conn.execute("PRAGMA table_info(sites)")}
    conn.close()

    assert {"center_lat", "center_lon", "radius_m"} <= columns, columns
    # 기존 행은 보존된다(추가만 한다).
    conn = sqlite3.connect(db_path)
    row = conn.execute("SELECT site_id, name FROM sites WHERE id = 1").fetchone()
    conn.close()
    assert row == ("site-legacy", "옛 현장")


def test_add_missing_columns_is_idempotent_and_keeps_values(tmp_path):
    db_path = tmp_path / "legacy2.db"
    _legacy_db(db_path)

    engine = make_engine(f"sqlite:///{db_path}")
    add_missing_columns(engine)
    add_missing_columns(engine)  # 두 번 실행해도 안전해야 한다

    conn = sqlite3.connect(db_path)
    conn.execute(
        "UPDATE sites SET center_lat = 37.5, center_lon = 127.0, radius_m = 300.0"
        " WHERE site_id = 'site-legacy'"
    )
    conn.commit()
    conn.close()

    add_missing_columns(engine)

    conn = sqlite3.connect(db_path)
    row = conn.execute(
        "SELECT center_lat, center_lon, radius_m FROM sites WHERE site_id = 'site-legacy'"
    ).fetchone()
    conn.close()
    assert row == (37.5, 127.0, 300.0)


def test_create_all_on_a_fresh_database_already_has_the_columns(tmp_path):
    db_path = tmp_path / "fresh.db"
    engine = make_engine(f"sqlite:///{db_path}")
    create_all(engine)

    conn = sqlite3.connect(db_path)
    columns = {row[1] for row in conn.execute("PRAGMA table_info(sites)")}
    conn.close()
    assert {"center_lat", "center_lon", "radius_m"} <= columns
