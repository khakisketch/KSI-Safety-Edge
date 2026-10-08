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


def make_session_factory(engine) -> sessionmaker:
    return sessionmaker(bind=engine, class_=Session, expire_on_commit=False)
