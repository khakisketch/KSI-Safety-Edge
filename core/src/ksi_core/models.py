"""SQLAlchemy models for the M1 core server.

One file (under ~400 lines): every payload is stored as JSON plus a small
set of indexed key columns used for ownership, idempotency and lookup.
"""

from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import JSON, BigInteger, Boolean, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


class Base(DeclarativeBase):
    pass


class Site(Base):
    __tablename__ = "sites"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    site_id: Mapped[str] = mapped_column(String, index=True)
    name: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    __table_args__ = (UniqueConstraint("org_id", "site_id"), UniqueConstraint("org_id", "name"))


class ZoneRevision(Base):
    __tablename__ = "zone_revisions"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    site_id: Mapped[str] = mapped_column(String, index=True)
    zone_id: Mapped[str] = mapped_column(String, index=True)
    revision_id: Mapped[str] = mapped_column(String, unique=True)
    base_revision_id: Mapped[str | None] = mapped_column(String, nullable=True)
    payload: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class RiskPolicyRevision(Base):
    __tablename__ = "risk_policy_revisions"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    site_id: Mapped[str] = mapped_column(String, index=True)
    policy_id: Mapped[str] = mapped_column(String, index=True)
    revision_id: Mapped[str] = mapped_column(String, unique=True)
    parent_revision_id: Mapped[str | None] = mapped_column(String, nullable=True)
    payload: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class Mission(Base):
    __tablename__ = "missions"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    mission_id: Mapped[str] = mapped_column(String, index=True)
    mission_revision_id: Mapped[str] = mapped_column(String, unique=True)
    site_id: Mapped[str] = mapped_column(String)
    content_sha256: Mapped[str] = mapped_column(String)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class MissionBundle(Base):
    __tablename__ = "mission_bundles"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    bundle_id: Mapped[str] = mapped_column(String, index=True)
    bundle_sha256: Mapped[str] = mapped_column(String)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    __table_args__ = (UniqueConstraint("org_id", "bundle_id"),)


class Flight(Base):
    __tablename__ = "flights"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    site_id: Mapped[str] = mapped_column(String, index=True)
    rc_device_id: Mapped[str] = mapped_column(String)
    mission_id: Mapped[str] = mapped_column(String)
    mission_revision_id: Mapped[str] = mapped_column(String)
    bundle_sha256: Mapped[str] = mapped_column(String)
    started_at_utc: Mapped[str] = mapped_column(String)
    ended_at_utc: Mapped[str | None] = mapped_column(String, nullable=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    __table_args__ = (UniqueConstraint("org_id", "flight_id"),)


class MissionApplication(Base):
    __tablename__ = "mission_applications"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    application_id: Mapped[str] = mapped_column(String, unique=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class FlightSession(Base):
    __tablename__ = "flight_sessions"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    session_id: Mapped[str] = mapped_column(String, unique=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class IngestSession(Base):
    __tablename__ = "ingest_sessions"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    upload_id: Mapped[str] = mapped_column(String, unique=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    manifest_sha256: Mapped[str] = mapped_column(String)
    manifest: Mapped[dict] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String, default="OPEN")  # OPEN|PARTIAL|COMMITTED
    durable_commit_id: Mapped[str | None] = mapped_column(String, nullable=True)
    committed_at_utc: Mapped[str | None] = mapped_column(String, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    __table_args__ = (UniqueConstraint("org_id", "manifest_sha256"),)


class IngestObject(Base):
    __tablename__ = "ingest_objects"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    upload_id: Mapped[str] = mapped_column(String, ForeignKey("ingest_sessions.upload_id"), index=True)
    artifact_id: Mapped[str] = mapped_column(String, index=True)
    size_bytes: Mapped[int] = mapped_column(BigInteger)
    sha256: Mapped[str] = mapped_column(String)
    media_type: Mapped[str] = mapped_column(String)
    required: Mapped[bool] = mapped_column(Boolean, default=True)
    rel_path: Mapped[str] = mapped_column(String)
    received_ranges: Mapped[list] = mapped_column(JSON, default=list)  # [[start,end]] inclusive
    received_bytes: Mapped[int] = mapped_column(BigInteger, default=0)
    content_sha256_hint: Mapped[str | None] = mapped_column(String, nullable=True)
    __table_args__ = (UniqueConstraint("upload_id", "artifact_id"),)


class Batch(Base):
    __tablename__ = "batches"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    kind: Mapped[str] = mapped_column(String)
    client_batch_id: Mapped[str] = mapped_column(String, index=True)
    content_hash: Mapped[str] = mapped_column(String)
    server_batch_id: Mapped[str] = mapped_column(String, unique=True)
    accepted_ids: Mapped[list] = mapped_column(JSON, default=list)
    duplicate_ids: Mapped[list] = mapped_column(JSON, default=list)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    __table_args__ = (UniqueConstraint("org_id", "flight_id", "kind", "client_batch_id"),)


class Observation(Base):
    __tablename__ = "observations"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    observation_id: Mapped[str] = mapped_column(String, unique=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class DetectionEvent(Base):
    __tablename__ = "detection_events"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    event_id: Mapped[str] = mapped_column(String, unique=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class SpatialSummary(Base):
    __tablename__ = "spatial_summaries"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    summary_id: Mapped[str] = mapped_column(String, unique=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class FlightSummary(Base):
    __tablename__ = "flight_summaries"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    summary_id: Mapped[str] = mapped_column(String, unique=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class Annotation(Base):
    __tablename__ = "annotations"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    event_id: Mapped[str] = mapped_column(String, index=True)
    annotation_id: Mapped[str] = mapped_column(String, unique=True)
    payload: Mapped[dict] = mapped_column(JSON)
    canonical_hash: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


class ReviewJob(Base):
    __tablename__ = "review_jobs"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    org_id: Mapped[str] = mapped_column(String, index=True)
    job_id: Mapped[str] = mapped_column(String, unique=True)
    flight_id: Mapped[str] = mapped_column(String, index=True)
    job_kind: Mapped[str] = mapped_column(String)
    idempotency_key: Mapped[str] = mapped_column(String, index=True)
    status: Mapped[str] = mapped_column(String, default="SUBMITTED")
    payload: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)
    __table_args__ = (UniqueConstraint("org_id", "idempotency_key"),)


class AuditLog(Base):
    __tablename__ = "audit_log"
    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    actor: Mapped[str] = mapped_column(String)
    org_id: Mapped[str] = mapped_column(String, index=True)
    action: Mapped[str] = mapped_column(String)
    resource_type: Mapped[str] = mapped_column(String)
    resource_id: Mapped[str] = mapped_column(String)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_utcnow)


ALL_TABLES = Base.metadata
