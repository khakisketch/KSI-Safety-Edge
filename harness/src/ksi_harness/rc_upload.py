"""RC→core upload side: sessions, batches, manifests, ingest, annotations.

Kept separate from :mod:`ksi_harness.rc_sim` (the MOP endpoint) so each
module stays cohesive. Methods live on ``UploadMixin`` and operate on the
host simulator's ``store`` / ``clock`` / ``device_id``.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta, timezone

from ksi_contracts import digest_sha256, set_digest

from . import mop

EPOCH_BASE_UTC = datetime(2026, 10, 8, 2, 5, 0, tzinfo=timezone.utc)
UPLOAD_CHUNK = 1024

# Frozen `flight-upload-manifest.v1` `source_kind` enum (contracts/, byte
# identical). Adding a kind needs a new `.vN` contract file + approval.
INGESTIBLE_SOURCE_KINDS = frozenset({
    "EDGE_SNAPSHOT",
    "EDGE_CLIP",
    "RC_PROXY_SEGMENT",
    "CAMERA_SD_ORIGINAL",
    "FLIGHT_LOG",
    "TELEMETRY_LOG",
})


def utc_of(ns: int) -> str:
    return (EPOCH_BASE_UTC + timedelta(microseconds=int(ns) // 1000)).isoformat().replace("+00:00", "Z")


def session_id_for(flight_id: str, boot_id: str) -> str:
    return str(uuid.uuid5(mop.NAMESPACE, f"session|{flight_id}|{boot_id}"))


def batch_id_for(flight_id: str, kind: str) -> str:
    return str(uuid.uuid5(mop.NAMESPACE, f"batch|{flight_id}|{kind}"))


class UploadMixin:
    """Upload half of the RC simulator (mixed into RCSimulator)."""

    def register(self, client, flight: dict) -> dict:
        first_ns = 0
        for boot in self.store.boots_for_flight(flight["flight_id"]):
            first, _last = self.store.first_last_ns(flight["flight_id"], boot)
            if first is not None and (first_ns == 0 or first < first_ns):
                first_ns = first
        return client.register_flight({
            "flight_id": flight["flight_id"],
            "site_id": flight["site_id"],
            "rc_device_id": self.device_id,
            "mission_id": flight["mission_id"],
            "mission_revision_id": flight["mission_revision_id"],
            "bundle_sha256": flight["bundle"]["bundle_sha256"],
            "started_at_utc": utc_of(first_ns),
            "ended_at_utc": None,
        })

    def upload_applications(self, client, flight: dict) -> list:
        out = []
        for app in self.applications(flight["flight_id"]):
            out.append(client.post_application(flight["flight_id"], app))
            self.store.mark_application_uploaded(app["application_id"])
        return out

    def synthesize_session(self, flight_id: str, boot_id: str, boot_position: int) -> dict:
        first, last = self.store.first_last_ns(flight_id, boot_id)
        apps = [a for a in self.applications(flight_id) if a["edge_boot_id"] == boot_id]
        lenses: dict[str, list] = {}
        for ob in self.store.observations_for(flight_id, boot_id):
            lenses.setdefault(ob["lens_id"], []).append(ob["frame_received_monotonic_ns"])
        epochs = [
            {
                "camera_stream_id": f"{lens}-0",
                "tracker_epoch": boot_position,
                "lens_id": lens,
                "started_at": min(ts),
                "ended_at": max(ts),
            }
            for lens, ts in sorted(lenses.items())
        ]
        return {
            "schema_version": 1,
            "session_id": session_id_for(flight_id, boot_id),
            "flight_id": flight_id,
            "edge_boot_id": boot_id,
            "started_at_edge_monotonic_ns": first or 0,
            "ended_at_edge_monotonic_ns": last or 0,
            "end_reason": "COMPLETED",
            "mission_application_ids": [a["application_id"] for a in apps],
            "tracker_epochs": epochs,
        }

    def upload_sessions(self, client, flight: dict) -> list:
        boots = sorted(
            self.store.boots_for_flight(flight["flight_id"]),
            key=lambda b: (self.store.first_last_ns(flight["flight_id"], b)[0] or 0),
        )
        return [
            client.post_session(flight["flight_id"], self.synthesize_session(flight["flight_id"], boot, pos))
            for pos, boot in enumerate(boots)
        ]

    def upload_batches(self, client, flight: dict) -> dict:
        fid = flight["flight_id"]
        kinds = {
            "observations": self.store.observations_for(fid),
            "events": self.store.events_for(fid),
            "spatial": self.store.spatial_summaries_for(fid),
            "flight": self.store.flight_summaries_for(fid),
        }
        out = {}
        for kind, items in kinds.items():
            if not items:
                continue
            client_batch_id = batch_id_for(fid, kind)
            resp = client.post_batch(fid, kind, client_batch_id, items)
            self.store.record_batch(kind, client_batch_id, fid, digest_sha256(items), resp["server_batch_id"])
            out[kind] = resp
        return out

    # -- manifests + ingest ------------------------------------------------------
    def _synthesize_missing_gaps(self, flight_id: str, boot_id: str) -> None:
        """Gap rows for consecutive seqs missing where the Edge gave up."""
        existing = {(g["channel"], g["seq"]) for g in self.store.gaps_for_boot(boot_id)}
        for channel in ("EVENT_RELIABLE", "EVIDENCE_TRANSFER"):
            received = self.store.received_seqs(boot_id, channel)
            if not received:
                continue
            if not any(ch == channel for ch, _seq in existing):
                continue  # only synthesize where the Edge gave up
            for seq in range(1, max(received) + 1):
                if seq not in received and (channel, seq) not in existing:
                    self.store.record_gap(
                        boot_id, channel, seq, f"SYNTHESIZED:{channel}", "RC_MANIFEST", flight_id)

    def build_manifest(self, flight: dict, boot_id: str) -> dict:
        fid = flight["flight_id"]
        self._synthesize_missing_gaps(fid, boot_id)
        top = 0
        for channel in ("EVENT_RELIABLE", "EVIDENCE_TRANSFER"):
            received = self.store.received_seqs(boot_id, channel)
            if received:
                top = max(top, max(received))
        artifacts = []
        for a in self.store.artifacts_for(fid, boot_id):
            # Frozen flight-ingest contract: `source_kind` is a closed enum.
            # Kinds outside it (e.g. sealed machine assessments) stay
            # retained locally and reach the Core through the producer-neutral
            # collection path — never relabelled to fit this manifest, never
            # allowed to 422 the whole boot.
            if a["source_kind"] not in INGESTIBLE_SOURCE_KINDS:
                continue
            artifacts.append(
            {
                "artifact_id": a["artifact_id"],
                "source_kind": a["source_kind"],
                "sha256": a["sha256"],
                "size_bytes": a["size_bytes"],
                "media_type": a["media_type"],
                "required": bool(a["required"]),
                "segment_index": a["segment_index"],
            }
            )
        gaps, run = [], []
        for g in self.store.gaps_for_boot(boot_id) + [None]:
            if g is not None and run and g["channel"] == run[0]["channel"] and g["seq"] == run[-1]["seq"] + 1:
                run.append(g)
            else:
                if run:
                    gaps.append({
                        "kind": "MOP_EVENT_GAP",
                        "start_ms": run[0]["seq"] * 33,
                        "duration_ms": len(run) * 33,
                        "reason": ";".join(sorted({r["reason"] for r in run})),
                    })
                run = [g] if g is not None else []
        first, last = self.store.first_last_ns(fid, boot_id)
        return set_digest({
            "schema_version": 1,
            "flight_id": fid,
            "mission_revision_id": flight["mission_revision_id"],
            "mission_sha256": flight["world"]["snapshot"]["content_sha256"],
            "rc_device_id": self.device_id,
            "edge_boot_id": boot_id,
            "event_count": len(self.store.events_for(fid, boot_id)),
            "edge_last_seq": top,
            "rc_received_last_seq": top,
            "flight_started_at_utc": utc_of(first or 0),
            "flight_ended_at_utc": utc_of(last or 0),
            "artifacts": artifacts,
            "gaps": gaps,
        }, "manifest_sha256")

    def begin_upload(self, client, manifest: dict) -> str:
        resp = client.open_ingest(manifest)
        self.upload_ids[manifest["manifest_sha256"]] = resp["upload_id"]
        return resp["upload_id"]

    def push_chunks(self, client, upload_id: str, manifest: dict, boot_artifacts: list,
                    abort_after_chunks: int | None = None, corrupt_ids=(),
                    skip_received: bool = True, force_ids=()) -> dict:
        status = client.get_ingest(upload_id)
        resident = set(status.get("uploaded_artifact_ids", [])) if skip_received else set()
        resident -= set(force_ids)
        blobs = {a["artifact_id"]: a["bytes"] for a in boot_artifacts}
        pushed, sent = [], 0
        for entry in manifest["artifacts"]:
            aid = entry["artifact_id"]
            if aid in resident:
                continue
            data = blobs[aid]
            if aid in set(corrupt_ids):
                data = bytes((b + 1) % 256 for b in data)
            total = entry["size_bytes"]
            assert len(data) == total, f"fixture bytes != manifest size for {aid}"
            for off in range(0, total, UPLOAD_CHUNK):
                chunk = data[off:off + UPLOAD_CHUNK]
                client.put_chunk(upload_id, aid, off, chunk, total, entry["sha256"])
                sent += 1
                if abort_after_chunks is not None and sent >= abort_after_chunks:
                    return {"pushed": pushed, "aborted": True, "chunks_sent": sent}
            pushed.append(aid)
        return {"pushed": pushed, "aborted": False, "chunks_sent": sent}

    def finish_upload(self, client, upload_id: str, manifest: dict,
                      flight_id: str, boot_id: str) -> dict:
        result = client.complete_ingest(upload_id, manifest["manifest_sha256"])
        if result.get("status") == "COMMITTED":
            for entry in manifest["artifacts"]:
                self.store.mark_synced(entry["artifact_id"])
        return result

    def upload_boot(self, client, flight: dict, boot_id: str, boot_artifacts: list,
                    abort_after_chunks: int | None = None, corrupt_ids=()) -> dict:
        manifest = self.build_manifest(flight, boot_id)
        upload_id = self.begin_upload(client, manifest)
        pushed = self.push_chunks(client, upload_id, manifest, boot_artifacts,
                                  abort_after_chunks=abort_after_chunks, corrupt_ids=corrupt_ids)
        result = None
        if not pushed["aborted"]:
            result = self.finish_upload(client, upload_id, manifest, flight["flight_id"], boot_id)
        return {"manifest": manifest, "upload_id": upload_id, "pushed": pushed, "result": result}

    def upload_flight(self, client, flight: dict, artifacts_by_boot: dict | None = None) -> dict:
        fid = flight["flight_id"]
        reg = self.register(client, flight)
        apps = self.upload_applications(client, flight)
        sessions = self.upload_sessions(client, flight)
        batches = self.upload_batches(client, flight)
        boots_out = {}
        for boot in self.store.boots_for_flight(fid):
            arts = (artifacts_by_boot or {}).get(boot)
            if arts is None:
                arts = [a for b in flight["boots"] if b["boot_id"] == boot for a in b["artifacts"]]
            boots_out[boot] = self.upload_boot(client, flight, boot, arts)
        return {
            "register": reg, "applications": apps, "sessions": sessions,
            "batches": batches, "boots": boots_out,
            "upload_state": client.upload_state(fid),
        }

    # -- annotations ---------------------------------------------------------------
    def create_annotation(self, annotation: dict) -> None:
        self.store.queue_annotation(annotation)

    def upload_annotations(self, client, flight_id: str) -> list:
        out = []
        for ann in self.store.pending_annotations(flight_id):
            out.append(client.post_annotation(ann["event_id"], ann))
            self.store.mark_annotation_uploaded(ann["annotation_id"])
        return out
