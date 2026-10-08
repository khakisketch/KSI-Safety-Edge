"""RC offline store: SQLite (WAL) + artifact files. ACK only after commit."""

from __future__ import annotations

import hashlib
import json
import sqlite3
from pathlib import Path

SCHEMA = """
PRAGMA journal_mode=WAL;
CREATE TABLE IF NOT EXISTS flights (
  flight_id TEXT PRIMARY KEY,
  created_ns INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  edge_boot_id TEXT,
  channel TEXT NOT NULL,
  seq INTEGER,
  msg_id TEXT NOT NULL UNIQUE,
  type TEXT NOT NULL,
  flight_id TEXT NOT NULL,
  sent_ns INTEGER NOT NULL,
  payload TEXT NOT NULL,
  processed INTEGER NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_msg_boot_chan_seq
  ON messages(edge_boot_id, channel, seq) WHERE seq IS NOT NULL;
CREATE TABLE IF NOT EXISTS observations (
  observation_id TEXT PRIMARY KEY,
  flight_id TEXT NOT NULL,
  edge_boot_id TEXT NOT NULL,
  payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS events (
  event_id TEXT PRIMARY KEY,
  flight_id TEXT NOT NULL,
  edge_boot_id TEXT NOT NULL,
  policy_revision_id TEXT,
  payload TEXT NOT NULL,
  payload_hash TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS spatial_summaries (
  summary_id TEXT PRIMARY KEY,
  flight_id TEXT NOT NULL,
  edge_boot_id TEXT NOT NULL,
  payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS flight_summaries (
  summary_id TEXT PRIMARY KEY,
  flight_id TEXT NOT NULL,
  edge_boot_id TEXT NOT NULL,
  payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS artifacts (
  artifact_id TEXT PRIMARY KEY,
  flight_id TEXT NOT NULL,
  edge_boot_id TEXT NOT NULL,
  source_kind TEXT NOT NULL,
  sha256 TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  media_type TEXT NOT NULL,
  segment_index INTEGER,
  required INTEGER NOT NULL,
  state TEXT NOT NULL,
  path TEXT NOT NULL,
  received_bytes INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS applications (
  application_id TEXT PRIMARY KEY,
  flight_id TEXT NOT NULL,
  edge_boot_id TEXT NOT NULL,
  bundle_id TEXT NOT NULL,
  bundle_sha256 TEXT NOT NULL,
  payload TEXT NOT NULL,
  uploaded INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS gaps (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  edge_boot_id TEXT NOT NULL,
  channel TEXT NOT NULL,
  seq INTEGER NOT NULL,
  reason TEXT NOT NULL,
  destination TEXT NOT NULL,
  flight_id TEXT NOT NULL,
  UNIQUE(edge_boot_id, channel, seq)
);
CREATE TABLE IF NOT EXISTS annotations (
  annotation_id TEXT PRIMARY KEY,
  event_id TEXT NOT NULL,
  flight_id TEXT NOT NULL,
  state TEXT NOT NULL,
  payload TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS batches (
  kind TEXT NOT NULL,
  client_batch_id TEXT NOT NULL,
  flight_id TEXT NOT NULL,
  payload_hash TEXT NOT NULL,
  server_batch_id TEXT NOT NULL,
  PRIMARY KEY (kind, client_batch_id)
);
"""


class RCStore:
    """Durable RC state. Every mutating method commits before returning —
    callers send MOP ACKs only after these return. ``on_commit`` hooks let
    tests count commits (commit-before-ACK proofs)."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.objects_dir = self.path.parent / "rc_objects"
        self.objects_dir.mkdir(parents=True, exist_ok=True)
        self.on_commit: list = []
        self.commit_count = 0
        self._connect()

    def _connect(self) -> None:
        self.db = sqlite3.connect(str(self.path))
        self.db.execute("PRAGMA journal_mode=WAL;")
        self.db.executescript(SCHEMA)

    def _commit(self) -> None:
        self.db.commit()
        self.commit_count += 1
        for hook in list(self.on_commit):
            hook()

    def close(self) -> None:
        self.db.close()

    def restart(self) -> None:
        """Close + reopen the same path; all state must survive."""
        self.close()
        self._connect()

    # -- messages ----------------------------------------------------------
    def has_msg_id(self, msg_id: str) -> bool:
        row = self.db.execute("SELECT 1 FROM messages WHERE msg_id=?", (msg_id,)).fetchone()
        return row is not None

    def all_msg_ids(self) -> set:
        return {r[0] for r in self.db.execute("SELECT msg_id FROM messages")}

    def processed_msg_ids(self) -> set:
        return {r[0] for r in self.db.execute("SELECT msg_id FROM messages WHERE processed=1")}

    def is_processed(self, msg_id: str) -> bool:
        row = self.db.execute(
            "SELECT 1 FROM messages WHERE msg_id=? AND processed=1", (msg_id,)).fetchone()
        return row is not None

    def mark_processed(self, msg_id: str) -> None:
        self.db.execute("UPDATE messages SET processed=1 WHERE msg_id=?", (msg_id,))
        self._commit()

    def store_message(self, env: dict) -> bool:
        """Persist an Edge→RC envelope. False when already present (dedup)."""
        try:
            self.db.execute(
                "INSERT INTO messages(edge_boot_id,channel,seq,msg_id,type,flight_id,sent_ns,payload)"
                " VALUES(?,?,?,?,?,?,?,?)",
                (
                    env.get("edge_boot_id"), env["channel"], env.get("seq"),
                    env["msg_id"], env["type"], env["flight_id"],
                    env["sent_monotonic_ns"], json.dumps(env["payload"]),
                ),
            )
            self.db.execute(
                "INSERT OR IGNORE INTO flights(flight_id,created_ns) VALUES(?,?)",
                (env["flight_id"], env["sent_monotonic_ns"]),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def message_stats(self, flight_id: str) -> dict:
        rows = self.db.execute(
            "SELECT edge_boot_id,channel,COUNT(*),MAX(seq) FROM messages WHERE flight_id=? GROUP BY 1,2",
            (flight_id,),
        ).fetchall()
        return {"groups": rows, "total": sum(r[2] for r in rows)}

    def first_last_ns(self, flight_id: str, boot_id: str) -> tuple[int | None, int | None]:
        row = self.db.execute(
            "SELECT MIN(sent_ns),MAX(sent_ns) FROM messages WHERE flight_id=? AND edge_boot_id=?",
            (flight_id, boot_id),
        ).fetchone()
        return (row[0], row[1])

    def boots_for_flight(self, flight_id: str) -> list:
        return [
            r[0]
            for r in self.db.execute(
                "SELECT DISTINCT edge_boot_id FROM messages WHERE flight_id=? ORDER BY 1", (flight_id,)
            )
        ]

    def max_seq(self, boot_id: str, channel: str) -> int | None:
        row = self.db.execute(
            "SELECT MAX(seq) FROM messages WHERE edge_boot_id=? AND channel=?", (boot_id, channel)
        ).fetchone()
        return row[0]

    def received_seqs(self, boot_id: str, channel: str) -> set:
        return {
            r[0]
            for r in self.db.execute(
                "SELECT seq FROM messages WHERE edge_boot_id=? AND channel=? AND seq IS NOT NULL",
                (boot_id, channel),
            )
        }

    def count_messages(self, flight_id: str) -> int:
        return self.db.execute(
            "SELECT COUNT(*) FROM messages WHERE flight_id=?", (flight_id,)
        ).fetchone()[0]

    # -- domain rows --------------------------------------------------------
    def store_observation(self, obs: dict, boot_id: str) -> bool:
        try:
            self.db.execute(
                "INSERT INTO observations(observation_id,flight_id,edge_boot_id,payload) VALUES(?,?,?,?)",
                (obs["observation_id"], obs["flight_id"], boot_id, json.dumps(obs)),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def store_event(self, event: dict, boot_id: str) -> bool:
        try:
            self.db.execute(
                "INSERT INTO events(event_id,flight_id,edge_boot_id,policy_revision_id,payload,payload_hash)"
                " VALUES(?,?,?,?,?,?)",
                (
                    event["event_id"], event["flight_id"], boot_id,
                    event.get("policy_revision_id"), json.dumps(event),
                    hashlib.sha256(json.dumps(event, sort_keys=True).encode()).hexdigest(),
                ),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def store_spatial_summary(self, summary: dict, boot_id: str) -> bool:
        try:
            self.db.execute(
                "INSERT INTO spatial_summaries(summary_id,flight_id,edge_boot_id,payload) VALUES(?,?,?,?)",
                (summary["summary_id"], summary["flight_id"], boot_id, json.dumps(summary)),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def store_flight_summary(self, summary: dict, boot_id: str) -> bool:
        try:
            self.db.execute(
                "INSERT INTO flight_summaries(summary_id,flight_id,edge_boot_id,payload) VALUES(?,?,?,?)",
                (summary["summary_id"], summary["flight_id"], boot_id, json.dumps(summary)),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def observations_for(self, flight_id: str, boot_id: str | None = None) -> list:
        q = "SELECT payload FROM observations WHERE flight_id=?"
        args: list = [flight_id]
        if boot_id is not None:
            q += " AND edge_boot_id=?"
            args.append(boot_id)
        return [json.loads(r[0]) for r in self.db.execute(q, args)]

    def events_for(self, flight_id: str, boot_id: str | None = None) -> list:
        q = "SELECT payload FROM events WHERE flight_id=?"
        args: list = [flight_id]
        if boot_id is not None:
            q += " AND edge_boot_id=?"
            args.append(boot_id)
        return [json.loads(r[0]) for r in self.db.execute(q, args)]

    def flight_summaries_for(self, flight_id: str) -> list:
        return [
            json.loads(r[0])
            for r in self.db.execute("SELECT payload FROM flight_summaries WHERE flight_id=?", (flight_id,))
        ]

    def spatial_summaries_for(self, flight_id: str) -> list:
        return [
            json.loads(r[0])
            for r in self.db.execute("SELECT payload FROM spatial_summaries WHERE flight_id=?", (flight_id,))
        ]

    # -- artifacts -----------------------------------------------------------
    def announce_artifact(self, meta: dict, flight_id: str, boot_id: str) -> bool:
        path = str(self.objects_dir / f"{meta['artifact_id']}.bin")
        try:
            self.db.execute(
                "INSERT INTO artifacts(artifact_id,flight_id,edge_boot_id,source_kind,sha256,size_bytes,"
                "media_type,segment_index,required,state,path,received_bytes)"
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    meta["artifact_id"], flight_id, boot_id, meta["source_kind"], meta["sha256"],
                    meta["size_bytes"], meta["media_type"], meta.get("segment_index"),
                    1 if meta["required"] else 0, "ANNOUNCED", path, 0,
                ),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def write_chunk(self, artifact_id: str, offset: int, data: bytes) -> dict:
        row = self.db.execute("SELECT * FROM artifacts WHERE artifact_id=?", (artifact_id,)).fetchone()
        if row is None:
            raise KeyError(f"unknown artifact {artifact_id}")
        cols = [d[0] for d in self.db.execute("SELECT * FROM artifacts LIMIT 0").description]
        rec = dict(zip(cols, row))
        with open(rec["path"], "r+b" if Path(rec["path"]).exists() else "w+b") as fh:
            fh.seek(offset)
            fh.write(data)
        received = max(rec["received_bytes"], offset + len(data))
        state = "RECEIVING" if received > 0 else rec["state"]
        if received >= rec["size_bytes"]:
            with open(rec["path"], "rb") as fh:
                actual = hashlib.sha256(fh.read()).hexdigest()
            state = "COMPLETE" if actual == rec["sha256"] else "RECEIVING"
        self.db.execute(
            "UPDATE artifacts SET received_bytes=?, state=? WHERE artifact_id=?",
            (received, state, artifact_id),
        )
        self._commit()
        return {"received_bytes": received, "state": state}

    def artifacts_for(self, flight_id: str, boot_id: str | None = None) -> list:
        q = "SELECT * FROM artifacts WHERE flight_id=?"
        args: list = [flight_id]
        if boot_id is not None:
            q += " AND edge_boot_id=?"
            args.append(boot_id)
        cols = [d[0] for d in self.db.execute("SELECT * FROM artifacts LIMIT 0").description]
        return [dict(zip(cols, r)) for r in self.db.execute(q, args)]

    def artifact_bytes(self, artifact_id: str) -> bytes:
        row = self.db.execute("SELECT path FROM artifacts WHERE artifact_id=?", (artifact_id,)).fetchone()
        return Path(row[0]).read_bytes()

    def mark_synced(self, artifact_id: str) -> None:
        self.db.execute("UPDATE artifacts SET state='SYNCED' WHERE artifact_id=?", (artifact_id,))
        self._commit()

    def is_synced(self, artifact_id: str) -> bool:
        row = self.db.execute("SELECT state FROM artifacts WHERE artifact_id=?", (artifact_id,)).fetchone()
        return row is not None and row[0] == "SYNCED"

    # -- applications / gaps / annotations / batches --------------------------
    def record_application(self, app: dict) -> bool:
        try:
            self.db.execute(
                "INSERT INTO applications(application_id,flight_id,edge_boot_id,bundle_id,bundle_sha256,payload,uploaded)"
                " VALUES(?,?,?,?,?,?,0)",
                (
                    app["application_id"], app["flight_id"], app["edge_boot_id"],
                    app["bundle_id"], app["bundle_sha256"], json.dumps(app),
                ),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def applications_for(self, flight_id: str) -> list:
        return [
            json.loads(r[0])
            for r in self.db.execute(
                "SELECT payload FROM applications WHERE flight_id=? ORDER BY rowid", (flight_id,)
            )
        ]

    def mark_application_uploaded(self, application_id: str) -> None:
        self.db.execute("UPDATE applications SET uploaded=1 WHERE application_id=?", (application_id,))
        self._commit()

    def record_gap(self, boot_id: str, channel: str, seq: int, reason: str, destination: str, flight_id: str) -> bool:
        try:
            self.db.execute(
                "INSERT INTO gaps(edge_boot_id,channel,seq,reason,destination,flight_id) VALUES(?,?,?,?,?,?)",
                (boot_id, channel, seq, reason, destination, flight_id),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def gaps_for_boot(self, boot_id: str) -> list:
        cols = ["edge_boot_id", "channel", "seq", "reason", "destination", "flight_id"]
        return [
            dict(zip(cols, r))
            for r in self.db.execute(
                "SELECT edge_boot_id,channel,seq,reason,destination,flight_id FROM gaps"
                " WHERE edge_boot_id=? ORDER BY channel,seq",
                (boot_id,),
            )
        ]

    def queue_annotation(self, ann: dict) -> bool:
        try:
            self.db.execute(
                "INSERT INTO annotations(annotation_id,event_id,flight_id,state,payload) VALUES(?,?,?,?,?)",
                (ann["annotation_id"], ann["event_id"], ann["flight_id"], "PENDING", json.dumps(ann)),
            )
        except sqlite3.IntegrityError:
            return False
        self._commit()
        return True

    def pending_annotations(self, flight_id: str | None = None) -> list:
        q = "SELECT payload FROM annotations WHERE state='PENDING' ORDER BY rowid"
        args: list = []
        if flight_id is not None:
            q = "SELECT payload FROM annotations WHERE state='PENDING' AND flight_id=? ORDER BY rowid"
            args = [flight_id]
        return [json.loads(r[0]) for r in self.db.execute(q, args)]

    def mark_annotation_uploaded(self, annotation_id: str) -> None:
        self.db.execute("UPDATE annotations SET state='UPLOADED' WHERE annotation_id=?", (annotation_id,))
        self._commit()

    def record_batch(self, kind: str, client_batch_id: str, flight_id: str, payload_hash: str, server_batch_id: str) -> None:
        self.db.execute(
            "INSERT OR REPLACE INTO batches(kind,client_batch_id,flight_id,payload_hash,server_batch_id)"
            " VALUES(?,?,?,?,?)",
            (kind, client_batch_id, flight_id, payload_hash, server_batch_id),
        )
        self._commit()
