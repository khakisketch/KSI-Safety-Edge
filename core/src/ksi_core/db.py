"""Engine/session helpers (SQLAlchemy 2, SQLite dev)."""

from __future__ import annotations

from pathlib import Path

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from . import models


def make_engine(db_url: str):
    if db_url.startswith("sqlite:"):
        # File DB path: ensure the parent directory exists.
        path = db_url.split("sqlite:///")[-1]
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        engine = create_engine(db_url, connect_args={"check_same_thread": False})
    else:
        engine = create_engine(db_url)
    return engine


def create_all(engine) -> None:
    models.Base.metadata.create_all(engine)
    add_missing_columns(engine)


# Additive, column-only migrations.
#
# `create_all` never changes a table that already exists, so a dev/prod DB
# created before a column was added keeps the old shape and every query
# against the new column fails with "no such column". We add the missing
# columns instead of dropping/recreating data.
#
# Rules: columns are only ADDED (never dropped, never retyped). A column that
# did not exist reads as NULL, which is exactly the "not registered" state the
# API already distinguishes from a zero value.
_ADDED_COLUMNS: tuple[tuple[str, str, str], ...] = (
    # (table, column, SQL type)
    ("sites", "center_lat", "FLOAT"),
    ("sites", "center_lon", "FLOAT"),
    ("sites", "radius_m", "FLOAT"),
    ("ingest_sessions", "collection_id", "VARCHAR"),
)


def add_missing_columns(engine) -> None:
    """Add columns introduced after the database file was created."""
    from sqlalchemy import inspect, text

    inspector = inspect(engine)
    existing_tables = set(inspector.get_table_names())
    with engine.begin() as connection:
        for table, column, sql_type in _ADDED_COLUMNS:
            if table not in existing_tables:
                continue
            columns = {col["name"] for col in inspector.get_columns(table)}
            if column in columns:
                continue
            connection.execute(
                text(f"ALTER TABLE {table} ADD COLUMN {column} {sql_type}"),
            )


def make_session_factory(engine) -> sessionmaker:
    return sessionmaker(bind=engine, class_=Session, expire_on_commit=False)
