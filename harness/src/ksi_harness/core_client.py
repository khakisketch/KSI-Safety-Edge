"""httpx sync wrapper over the frozen OpenAPI surface (base /api/v1/edge)."""

from __future__ import annotations


class CoreError(RuntimeError):
    def __init__(self, method: str, path: str, status: int, body: str) -> None:
        self.method = method
        self.path = path
        self.status = status
        self.body = body
        super().__init__(f"{method} {path} -> {status}: {body[:500]}")


_BATCH_PATHS = {
    "observations": "observations:batch",
    "events": "events:batch",
    "spatial": "spatial-summaries:batch",
    "flight": "flight-summaries:batch",
}


class CoreClient:
    """Sync client. Token configurable per instance (use one client per org)."""

    def __init__(self, base_url: str, token: str, timeout: float = 30.0) -> None:
        import httpx

        self.base_url = base_url.rstrip("/")
        self.token = token
        self._http = httpx.Client(base_url=self.base_url, timeout=timeout)

    def set_token(self, token: str) -> None:
        self.token = token

    def close(self) -> None:
        self._http.close()

    # -- low level ----------------------------------------------------------
    def _req(self, method: str, path: str, *, expected=(200,), **kwargs):
        headers = dict(kwargs.pop("headers", {}) or {})
        headers["Authorization"] = f"Bearer {self.token}"
        resp = self._http.request(method, path, headers=headers, **kwargs)
        if resp.status_code not in expected:
            raise CoreError(method, path, resp.status_code, resp.text)
        ctype = resp.headers.get("content-type", "")
        if "application/json" in ctype:
            return resp.json()
        return resp.content

    def _get(self, path: str, **kwargs):
        return self._req("GET", path, **kwargs)

    def _post(self, path: str, **kwargs):
        return self._req("POST", path, **kwargs)

    def _put(self, path: str, **kwargs):
        return self._req("PUT", path, **kwargs)

    # -- health / sites -------------------------------------------------------
    def healthz(self) -> dict:
        import httpx

        resp = httpx.get(self.base_url + "/healthz", timeout=10.0)
        if resp.status_code != 200:
            raise CoreError("GET", "/healthz", resp.status_code, resp.text)
        return resp.json()

    def create_site(self, org_id: str, name: str) -> dict:
        return self._post("/sites", json={"org_id": org_id, "name": name})

    def get_site_zones(self, site_id: str) -> dict:
        return self._get(f"/sites/{site_id}/zone-revisions")

    def post_zone_revision(self, site_id: str, body: dict) -> dict:
        return self._post(f"/sites/{site_id}/zone-revisions", json=body)

    # -- flights / applications / sessions --------------------------------------
    def register_flight(self, body: dict) -> dict:
        return self._post("/flights", json=body)

    def get_flight(self, flight_id: str) -> dict:
        return self._get(f"/flights/{flight_id}")

    def upload_state(self, flight_id: str) -> str:
        return self.get_flight(flight_id)["upload_state"]

    def post_application(self, flight_id: str, app: dict) -> dict:
        return self._post(f"/flights/{flight_id}/mission-applications", json=app)

    def list_applications(self, flight_id: str) -> dict:
        return self._get(f"/flights/{flight_id}/mission-applications")

    def post_session(self, flight_id: str, session: dict) -> dict:
        return self._post(f"/flights/{flight_id}/flight-sessions", json=session)

    def list_sessions(self, flight_id: str) -> dict:
        return self._get(f"/flights/{flight_id}/flight-sessions")

    # -- read listings for correlation (RC→Core 대조) ---------------------------
    def _list_paged(self, path: str, limit: int, params: dict | None = None) -> list[dict]:
        base = dict(params or {})
        items: list[dict] = []
        cursor: str | None = None
        while True:
            query = dict(base, limit=limit)
            if cursor:
                query["cursor"] = cursor
            page = self._get(path, params=query)
            items.extend(page.get("items", []))
            cursor = page.get("next_cursor")
            if not cursor:
                return items

    def list_observations(self, flight_id: str, limit: int = 500) -> list[dict]:
        return self._list_paged(f"/flights/{flight_id}/observations", limit)

    def list_flight_events(self, flight_id: str, limit: int = 500) -> list[dict]:
        return self._list_paged("/events", limit, params={"flight_id": flight_id})

    def list_artifacts(self, flight_id: str) -> list[dict]:
        return list(self._get(f"/flights/{flight_id}/artifacts").get("items", []))

    # -- structured operator responses (read-only; 404 -> caller decides) ---------
    def list_operator_responses(self, flight_id: str) -> list[dict]:
        """GET /flights/{flightId}/operator-responses items (insertion order)."""
        return list(self._get(f"/flights/{flight_id}/operator-responses").get("items", []))

    # -- batches ------------------------------------------------------------------
    def post_batch(self, flight_id: str, kind: str, client_batch_id: str, items: list) -> dict:
        if kind not in _BATCH_PATHS:
            raise ValueError(f"unknown batch kind {kind!r}")
        return self._post(
            f"/flights/{flight_id}/{_BATCH_PATHS[kind]}",
            json={"client_batch_id": client_batch_id, "items": items},
        )

    # -- ingest ---------------------------------------------------------------------
    def open_ingest(self, manifest: dict) -> dict:
        return self._post("/ingest-sessions", json=manifest)

    def get_ingest(self, upload_id: str) -> dict:
        return self._get(f"/ingest-sessions/{upload_id}")

    def put_chunk(
        self, upload_id: str, artifact_id: str, offset: int, data: bytes,
        total: int, sha256: str,
    ) -> dict:
        end = offset + len(data) - 1
        return self._put(
            f"/ingest-sessions/{upload_id}/objects/{artifact_id}",
            content=data,
            headers={
                "Content-Range": f"bytes {offset}-{end}/{total}",
                "X-Content-SHA256": sha256,
                "Content-Type": "application/octet-stream",
            },
        )

    def complete_ingest(self, upload_id: str, manifest_sha256: str) -> dict:
        return self._post(
            f"/ingest-sessions/{upload_id}/complete", json={"manifest_sha256": manifest_sha256}
        )

    # -- events / evidence / annotations / coverage ------------------------------------
    def get_events(self, flight_id: str | None = None) -> dict:
        path = "/events" + (f"?flight_id={flight_id}" if flight_id else "")
        return self._get(path)

    def get_event(self, event_id: str) -> dict:
        return self._get(f"/events/{event_id}")

    def get_evidence(self, event_id: str) -> dict:
        return self._get(f"/events/{event_id}/evidence")

    def download_object(self, artifact_id: str) -> tuple[bytes, dict]:
        headers = {"Authorization": f"Bearer {self.token}"}
        resp = self._http.get(f"/objects/{artifact_id}", headers=headers)
        if resp.status_code != 200:
            raise CoreError("GET", f"/objects/{artifact_id}", resp.status_code, resp.text)
        return resp.content, dict(resp.headers)

    def post_annotation(self, event_id: str, annotation: dict) -> dict:
        return self._post(f"/events/{event_id}/annotations", json=annotation)

    def list_annotations(self, event_id: str) -> dict:
        return self._get(f"/events/{event_id}/annotations")

    def get_coverage(self, flight_id: str) -> dict:
        return self._get(f"/flights/{flight_id}/coverage")
