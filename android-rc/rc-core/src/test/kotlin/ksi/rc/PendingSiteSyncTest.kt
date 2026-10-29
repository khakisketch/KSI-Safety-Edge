package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Offline-first site registration: draft durability + process-death
 * reopen, deferred upload (CREATED vs name-keyed MATCHED_EXISTING vs
 * failure-retry), forward-only alias (old rows untouched), and
 * storage-degraded paths. No id is ever fabricated: drafts carry a
 * client UUID that is never presented as a server id.
 */
class PendingSiteSyncTest {
    private fun tempRoot(): Path = Files.createTempDirectory("rc-pending-site")

    private fun journalOn(
        root: Path,
        now: String = "2026-10-24T00:00:00Z",
    ): PendingSiteJournal = PendingSiteJournal(root, { now })

    // -- validation ---------------------------------------------------------

    @Test
    fun blank_name_is_rejected_without_writing() {
        val root = tempRoot()
        val journal = journalOn(root)
        assertFailsWith<IllegalArgumentException> {
            journal.createDraft("  ", null, null, null)
        }
        assertTrue(journal.pendingDrafts().isEmpty())
        assertFalse(root.resolve(PendingSiteJournal.FILE_NAME).toFile().exists())
    }

    @Test
    fun partial_location_is_rejected_without_writing() {
        val journal = journalOn(tempRoot())
        assertFailsWith<IllegalArgumentException> {
            journal.createDraft("현장 A", 37.5, null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            journal.createDraft("현장 A", 37.5, 127.0, null)
        }
        assertTrue(journal.pendingDrafts().isEmpty())
    }

    @Test
    fun out_of_range_location_is_rejected() {
        val journal = journalOn(tempRoot())
        assertFailsWith<IllegalArgumentException> {
            journal.createDraft("현장 A", 91.0, 127.0, 500.0)
        }
        assertFailsWith<IllegalArgumentException> {
            journal.createDraft("현장 A", 37.5, 127.0, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            journal.createDraft("현장 A", 37.5, 127.0, 100_000.1)
        }
        assertTrue(journal.pendingDrafts().isEmpty())
    }

    @Test
    fun nameless_location_bounds_match_the_server_contract() {
        assertEquals(
            listOf(PendingSiteValidation.PARTIAL_LOCATION),
            PendingSiteValidation.validateLocation(37.5, null, null),
        )
        assertEquals(
            listOf(PendingSiteValidation.BAD_LAT),
            PendingSiteValidation.validateLocation(91.0, 127.0, 50.0),
        )
        assertEquals(
            emptyList(),
            PendingSiteValidation.validateLocation(null, null, null),
        )
    }

    // -- durability ----------------------------------------------------------

    @Test
    fun draft_survives_process_death_reopen() {
        val root = tempRoot()
        val created = journalOn(root).createDraft("청주 평지", 36.6, 127.4, 500.0)
        assertTrue(created.clientSiteId.isNotBlank())

        val reopened = PendingSiteJournal(root, { "2026-10-24T01:00:00Z" })
        val pending = reopened.pendingDrafts()
        assertEquals(1, pending.size)
        assertEquals(created.clientSiteId, pending.single().clientSiteId)
        assertEquals("청주 평지", pending.single().name)
        assertEquals(36.6, pending.single().centerLat)
        assertEquals(500.0, pending.single().radiusM)
    }

    @Test
    fun draft_without_location_round_trips_as_unregistered() {
        val root = tempRoot()
        journalOn(root).createDraft("이름만 현장", null, null, null)
        val pending = PendingSiteJournal(root, { "2026-10-24T01:00:00Z" }).pendingDrafts()
        assertEquals(1, pending.size)
        assertNull(pending.single().centerLat)
        assertNull(pending.single().radiusM)
    }

    // -- deferred upload ------------------------------------------------------

    private fun seededOrgStore(root: Path, org: String = "org-a"): FileRcStore {
        val store = FileRcStore(root)
        val site = Mop.json.createObjectNode()
        site.put("site_id", Fixture.uuid(900))
        site.put("org_id", org)
        site.put("name", "seed-site")
        site.putNull("center_lat")
        site.putNull("center_lon")
        site.putNull("radius_m")
        store.saveSite(site)
        return store
    }

    @Test
    fun upload_creates_and_caches_the_canonical_row() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val journal = journalOn(root)
        val draft = journal.createDraft("새 현장", 36.6, 127.4, 500.0)
        val sync = PendingSiteSync(CoreApi(FakeCore()), journal, store)

        val report = sync.syncOnce()

        assertTrue(report.attempted)
        assertEquals(1, report.results.size)
        val result = report.results.single()
        assertEquals(draft.clientSiteId, result.clientSiteId)
        assertEquals(PendingSyncOutcome.CREATED, result.outcome)
        assertFalse(result.canonicalSiteId == draft.clientSiteId)
        // Canonical row is cached additively for NEW preparations.
        assertNotNull(store.site(result.canonicalSiteId))
        // Draft resolved: no longer pending, alias recorded.
        assertTrue(journal.pendingDrafts().isEmpty())
        assertEquals(result.canonicalSiteId, journal.canonicalIdFor(draft.clientSiteId))
        assertEquals(result.canonicalSiteId, journal.resolveForCore(draft.clientSiteId))
    }

    @Test
    fun same_name_upload_matches_instead_of_forking() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val fake = FakeCore()
        val journal = journalOn(root)
        val draft = journal.createDraft("같은 이름", 36.6, 127.4, 500.0)
        // The name already exists centrally (created out-of-band).
        val preexisting = fakePreexistingSite(fake, "같은 이름")
        val sync = PendingSiteSync(CoreApi(fake), journal, store)

        val report = sync.syncOnce()

        assertTrue(report.attempted)
        val result = report.results.single()
        assertEquals(PendingSyncOutcome.MATCHED_EXISTING, result.outcome)
        assertEquals(preexisting, result.canonicalSiteId)
        assertEquals(1, fake.sites.size, "no duplicate row forked on the server double")
        assertTrue(journal.pendingDrafts().isEmpty())
        assertEquals(preexisting, journal.resolveForCore(draft.clientSiteId))
    }

    @Test
    fun match_never_rewrites_a_known_stored_row() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val fake = FakeCore()
        // The operator synced BEFORE: the known row has no location.
        val staleView = fakePreexistingSiteView(fake, "알던 현장")
        store.saveSite(staleView)
        val journal = journalOn(root)
        val draft = journal.createDraft("알던 현장", 36.6, 127.4, 500.0)
        val sync = PendingSiteSync(CoreApi(fake), journal, store)

        val report = sync.syncOnce()

        assertEquals(PendingSyncOutcome.MATCHED_EXISTING, report.results.single().outcome)
        // The server filled the location (fill-when-empty), but the locally
        // stored row is untouched — forward only, no backfill.
        val kept = store.site(staleView.get("site_id").asText())!!
        assertTrue(
            kept.get("center_lat") == null || kept.get("center_lat").isNull,
            "stored row must not be rewritten by the match path: $kept",
        )
        assertTrue(
            fake.sites.getValue(staleView.get("site_id").asText())
                .get("center_lat").isNumber,
            "server did fill the empty location (double fidelity)",
        )
        // Alias still recorded so NEW preparations use the canonical id.
        assertEquals(
            staleView.get("site_id").asText(),
            journal.resolveForCore(draft.clientSiteId),
        )
    }

    @Test
    fun match_caches_the_canonical_row_when_locally_unknown() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val fake = FakeCore()
        val canonical = fakePreexistingSite(fake, "낯선 중앙 현장")
        val journal = journalOn(root)
        journal.createDraft("낯선 중앙 현장", null, null, null)

        PendingSiteSync(CoreApi(fake), journal, store).syncOnce()

        assertNotNull(
            store.site(canonical),
            "a matched-but-unknown row is cached additively (never a rewrite)",
        )
    }

    @Test
    fun upload_failure_stays_pending_with_attempt_fact_and_retries() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val fake = FakeCore()
        fake.healthy = false
        val journal = journalOn(root)
        val draft = journal.createDraft("나중에 현장", null, null, null)
        val sync = PendingSiteSync(CoreApi(fake), journal, store)

        val unreachable = sync.syncOnce()
        assertFalse(unreachable.attempted)
        assertEquals(1, journal.pendingDrafts().size)

        // Reachable but POST throws (transport fault): stays pending.
        val failing = object : CoreTransport {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): CoreHttpResponse {
                if (method == "GET" && path == "/healthz") {
                    return CoreHttpResponse(
                        200, Mop.json.writeValueAsBytes(
                            Mop.json.createObjectNode().put("status", "ok"),
                        ),
                    )
                }
                if (method == "GET" && path == "/sites") {
                    return CoreHttpResponse(
                        200, Mop.json.writeValueAsBytes(
                            Mop.json.createObjectNode().apply {
                                set<com.fasterxml.jackson.databind.JsonNode>(
                                    "items", Mop.json.createArrayNode(),
                                )
                                putNull("next_cursor")
                            },
                        ),
                    )
                }
                throw IOException("field network down")
            }
        }
        val failed = PendingSiteSync(CoreApi(failing), journal, store).syncOnce()
        assertTrue(failed.attempted)
        assertTrue(failed.results.isEmpty())
        assertEquals(1, journal.pendingDrafts().size)
        val status = journal.statusFor(draft.clientSiteId)!!
        assertTrue(
            (status.lastAttemptError ?: "").contains("field network down"),
            "last attempt fact retained: ${status.lastAttemptError}",
        )

        // Healed: the retry succeeds (server dedups by name, no fork).
        fake.healthy = true
        val healed = sync.syncOnce()
        assertEquals(PendingSyncOutcome.CREATED, healed.results.single().outcome)
        assertTrue(journal.pendingDrafts().isEmpty())
    }

    @Test
    fun no_core_means_no_attempt_and_no_error() {
        val root = tempRoot()
        // No stored sites at all: org unknown AND nothing cached.
        val journal = journalOn(root)
        journal.createDraft("오프라인 현장", null, null, null)
        val sync = PendingSiteSync(
            CoreApi(FakeCore()),
            journal,
            FileRcStore(root),
        )
        val report = sync.syncOnce()
        assertFalse(report.attempted)
        assertNotNull(report.detail)
        assertEquals(1, journal.pendingDrafts().size)
    }

    @Test
    fun unknown_org_stays_pending_without_posting() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val site = Mop.json.createObjectNode()
        site.put("site_id", Fixture.uuid(901))
        site.put("name", "org-less")
        store.saveSite(site)
        val journal = journalOn(root)
        journal.createDraft("조직 미확인 현장", null, null, null)
        val fake = FakeCore()
        val report = PendingSiteSync(CoreApi(fake), journal, store).syncOnce()
        assertFalse(report.attempted)
        assertTrue((report.detail ?: "").contains("조직"))
        // Bootstrap path may probe liveness + the site list, but it must
        // never POST a registration it cannot attribute to an org.
        assertTrue(
            fake.requests.none { it.startsWith("POST /sites") },
            "no registration POST without an org: ${fake.requests}",
        )
        assertEquals(1, journal.pendingDrafts().size)
    }

    @Test
    fun unknown_org_bootstraps_from_the_reachable_server_list() {
        val root = tempRoot()
        // Fresh device: the local store carries no org at all.
        val store = FileRcStore(root)
        val fake = FakeCore()
        fakePreexistingSite(fake, "중앙 현장")
        val journal = journalOn(root)
        val draft = journal.createDraft("새 들판", 36.6, 127.4, 500.0)

        val report = PendingSiteSync(CoreApi(fake), journal, store).syncOnce()

        assertTrue(report.attempted)
        val result = report.results.single()
        assertEquals(draft.clientSiteId, result.clientSiteId)
        assertEquals(PendingSyncOutcome.CREATED, result.outcome)
        assertTrue(journal.pendingDrafts().isEmpty())
        assertEquals(result.canonicalSiteId, journal.resolveForCore(draft.clientSiteId))
        assertNotNull(store.site(result.canonicalSiteId))
    }

    @Test
    fun stale_local_org_never_shadows_the_token_org() {
        val root = tempRoot()
        val store = FileRcStore(root)
        // A token switch left an org-stale row behind: the live list says
        // org-live, and the POST must go there — never under the stale id.
        val stale = Mop.json.createObjectNode()
        stale.put("site_id", Fixture.uuid(902))
        stale.put("org_id", "org-stale")
        stale.put("name", "seed-site")
        store.saveSite(stale)
        val gate = OrgGateFake(tokenOrg = "org-live")
        gate.addSite(Fixture.uuid(903), "org-live", "중앙 현장", null, null, null)
        val journal = journalOn(root)
        val draft = journal.createDraft("새 들판", null, null, null)

        val report = PendingSiteSync(CoreApi(gate), journal, store).syncOnce()

        assertTrue(report.attempted)
        assertEquals(listOf("org-live"), gate.postedOrgs)
        assertEquals(PendingSyncOutcome.CREATED, report.results.single().outcome)
        assertTrue(journal.pendingDrafts().isEmpty())
    }

    @Test
    fun post_403_rederives_the_org_once_and_retries() {
        val root = tempRoot()
        // The list was empty at probe time (local fallback org-stale), then
        // the token moved: the POST 403s, one live re-list teaches org-live,
        // and the same draft retries under it instead of stranding.
        val store = FileRcStore(root)
        val stale = Mop.json.createObjectNode()
        stale.put("site_id", Fixture.uuid(904))
        stale.put("org_id", "org-stale")
        stale.put("name", "seed-site")
        store.saveSite(stale)
        val gate = OrgGateFake(tokenOrg = "org-live", sitesOnSecondList = true)
        gate.addSite(Fixture.uuid(905), "org-live", "중앙 현장", null, null, null)
        val journal = journalOn(root)
        val draft = journal.createDraft("새 들판", null, null, null)

        val report = PendingSiteSync(CoreApi(gate), journal, store).syncOnce()

        assertTrue(report.attempted)
        assertEquals(
            listOf("org-stale", "org-live"), gate.postedOrgs,
            "stale POST, then exactly one retry under the re-derived org",
        )
        assertEquals(PendingSyncOutcome.CREATED, report.results.single().outcome)
        assertEquals(
            report.results.single().canonicalSiteId,
            journal.resolveForCore(draft.clientSiteId),
        )
    }

    @Test
    fun unrecoverable_403_stays_pending_with_the_attempt_fact() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val stale = Mop.json.createObjectNode()
        stale.put("site_id", Fixture.uuid(906))
        stale.put("org_id", "org-stale")
        stale.put("name", "seed-site")
        store.saveSite(stale)
        // Empty Core and a foreign token: every list is empty, the POST
        // 403s, and there is nothing to re-derive — the draft stays
        // pending with the failure named.
        val gate = OrgGateFake(tokenOrg = "org-live")
        val journal = journalOn(root)
        val draft = journal.createDraft("새 들판", null, null, null)

        val report = PendingSiteSync(CoreApi(gate), journal, store).syncOnce()

        assertTrue(report.attempted)
        assertTrue(report.results.isEmpty())
        assertEquals(1, journal.pendingDrafts().size)
        assertTrue(
            (journal.statusFor(draft.clientSiteId)?.lastAttemptError ?: "").contains("403"),
            "the 403 is named, not swallowed",
        )
    }

    @Test
    fun multiple_server_orgs_refuse_without_guessing() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val fake = FakeCore()
        fakePreexistingSite(fake, "현장 갑")
        // A second org on the caller-scoped list (must not happen under
        // token scoping, but fail-closed if it does): no POST anywhere.
        val foreign = Mop.json.createObjectNode()
        foreign.put("site_id", Fixture.uuid(907))
        foreign.put("org_id", "org-foreign")
        foreign.put("name", "현장 을")
        foreign.putNull("center_lat")
        foreign.putNull("center_lon")
        foreign.putNull("radius_m")
        fake.sites[Fixture.uuid(907)] = foreign
        val journal = journalOn(root)
        journal.createDraft("새 들판", null, null, null)
        fake.requests.clear() // seeding POSTs above are not the run's

        val report = PendingSiteSync(CoreApi(fake), journal, store).syncOnce()

        assertFalse(report.attempted)
        assertTrue(
            (report.detail ?: "").contains("여럿"),
            "the multi-org wait names itself: ${report.detail}",
        )
        assertTrue(
            fake.requests.none { it.startsWith("POST /sites") },
            "never guess an org: ${fake.requests}",
        )
        assertEquals(1, journal.pendingDrafts().size)
    }

    @Test
    fun matched_location_mismatch_carries_the_dropped_location() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val fake = FakeCore()
        postToFake(
            fake, "/sites",
            Mop.json.createObjectNode().put("org_id", "org-a")
                .put("name", "같은 이름")
                .put("center_lat", 37.0).put("center_lon", 127.0).put("radius_m", 300.0),
        )
        val journal = journalOn(root)
        val same = journal.createDraft("같은 이름", 37.0, 127.0, 300.0)
        val moved = journal.createDraft("같은 이름", 36.6, 127.4, 500.0)

        val report = PendingSiteSync(CoreApi(fake), journal, store).syncOnce()

        assertTrue(report.attempted)
        val byClient = report.results.associateBy { it.clientSiteId }
        assertEquals(
            PendingSyncOutcome.MATCHED_EXISTING,
            byClient.getValue(same.clientSiteId).outcome,
        )
        assertNull(
            byClient.getValue(same.clientSiteId).droppedLocation,
            "identical coordinates: nothing dropped",
        )
        val dropped = byClient.getValue(moved.clientSiteId).droppedLocation
        assertEquals(PendingSyncOutcome.MATCHED_EXISTING, byClient.getValue(moved.clientSiteId).outcome)
        assertTrue(
            dropped != null && dropped.serverLat == 37.0 && dropped.draftLat == 36.6,
            "the server-kept coordinates and the dropped draft coordinates: $dropped",
        )
    }

    @Test
    fun matched_empty_server_row_fills_without_a_drop_note() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val fake = FakeCore()
        fakePreexistingSite(fake, "빈 위치 현장")
        val journal = journalOn(root)
        journal.createDraft("빈 위치 현장", 36.6, 127.4, 500.0)

        val report = PendingSiteSync(CoreApi(fake), journal, store).syncOnce()

        assertEquals(PendingSyncOutcome.MATCHED_EXISTING, report.results.single().outcome)
        assertNull(
            report.results.single().droppedLocation,
            "the server filled its empty row from the draft: nothing dropped",
        )
    }

    @Test
    fun empty_server_and_unknown_org_stays_pending() {
        val root = tempRoot()
        val store = FileRcStore(root)
        val fake = FakeCore() // reachable, but holds no sites: no org to learn.
        val journal = journalOn(root)
        journal.createDraft("첫 현장", null, null, null)

        val report = PendingSiteSync(CoreApi(fake), journal, store).syncOnce()

        assertFalse(report.attempted)
        assertTrue((report.detail ?: "").contains("조직"))
        assertTrue(
            fake.requests.none { it.startsWith("POST /sites") },
            "an empty Core teaches no org — never invent one: ${fake.requests}",
        )
        assertEquals(1, journal.pendingDrafts().size)
    }

    @Test
    fun same_name_second_draft_in_one_run_matches_instead_of_creating() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val fake = FakeCore()
        val journal = journalOn(root)
        val first = journal.createDraft("같은 이름", null, null, null)
        val second = journal.createDraft("같은 이름", null, null, null)

        val report = PendingSiteSync(CoreApi(fake), journal, store).syncOnce()

        assertTrue(report.attempted)
        assertEquals(2, report.results.size)
        val byClient = report.results.associateBy { it.clientSiteId }
        assertEquals(PendingSyncOutcome.CREATED, byClient.getValue(first.clientSiteId).outcome)
        assertEquals(
            PendingSyncOutcome.MATCHED_EXISTING,
            byClient.getValue(second.clientSiteId).outcome,
        )
        assertEquals(
            byClient.getValue(first.clientSiteId).canonicalSiteId,
            byClient.getValue(second.clientSiteId).canonicalSiteId,
            "one run, one name, one server row",
        )
        assertEquals(1, fake.sites.size, "no duplicate row forked within the run")
        assertTrue(journal.pendingDrafts().isEmpty())
    }

    @Test
    fun probe_failure_after_passing_health_returns_a_report_without_touching_drafts() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val journal = journalOn(root)
        journal.createDraft("대기 현장", null, null, null)
        val failing = object : CoreTransport {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): CoreHttpResponse {
                if (method == "GET" && path == "/healthz") {
                    return CoreHttpResponse(
                        200, Mop.json.writeValueAsBytes(
                            Mop.json.createObjectNode().put("status", "ok"),
                        ),
                    )
                }
                throw IOException("site list flaked after health passed")
            }
        }

        val report = PendingSiteSync(CoreApi(failing), journal, store).syncOnce()

        assertTrue(report.attempted)
        assertTrue(report.results.isEmpty())
        assertTrue((report.detail ?: "").contains("목록"))
        assertEquals(1, journal.pendingDrafts().size)
        assertNull(journal.statusFor(journal.pendingDrafts().single().clientSiteId)?.lastAttemptError)
    }

    @Test
    fun resolved_history_is_never_rewritten() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val journal = journalOn(root)
        val draft = journal.createDraft("한번 현장", null, null, null)
        PendingSiteSync(CoreApi(FakeCore()), journal, store).syncOnce()
        val canonical = journal.canonicalIdFor(draft.clientSiteId)!!
        // Same outcome replays as a no-op.
        journal.recordSynced(draft.clientSiteId, canonical, PendingSyncOutcome.CREATED)
        assertFailsWith<IllegalStateException> {
            journal.recordSynced(
                draft.clientSiteId, "00000000-0000-4000-8000-000000009999",
                PendingSyncOutcome.MATCHED_EXISTING,
            )
        }
        assertFailsWith<IllegalStateException> {
            journal.recordAttempt(draft.clientSiteId, "late failure")
        }
        assertEquals(canonical, journal.canonicalIdFor(draft.clientSiteId))
    }

    // -- alias forward-only ----------------------------------------------------

    @Test
    fun flight_rows_keep_their_recorded_id_after_resolution() {
        val root = tempRoot()
        val store = seededOrgStore(root)
        val journal = journalOn(root)
        val draft = journal.createDraft("비행 현장", null, null, null)
        val flight = Mop.json.createObjectNode()
        flight.put("schema_version", 1)
        flight.put("flight_id", Fixture.uuid(100))
        flight.put("site_id", draft.clientSiteId)
        flight.put("mission_id", Fixture.uuid(101))
        flight.put("mission_revision_id", Fixture.uuid(102))
        flight.put("mission_sha256", "msha")
        flight.put("bundle_id", Fixture.uuid(103))
        flight.put("bundle_sha256", "bsha")
        flight.putArray("zone_revision_ids")
        flight.put("started_at_utc", "2026-10-24T00:00:00Z")
        flight.putNull("ended_at_utc")
        flight.put("state", "STARTED")
        store.startFlight(flight)

        PendingSiteSync(CoreApi(FakeCore()), journal, store).syncOnce()

        val canonical = journal.canonicalIdFor(draft.clientSiteId)!!
        // Stored flight UNTOUCHED (no backfill of the canonical id).
        assertEquals(
            draft.clientSiteId,
            store.flight(Fixture.uuid(100))!!.get("site_id").asText(),
        )
        // Alias maps forward for Core-facing reporting only.
        assertEquals(canonical, journal.resolveForCore(draft.clientSiteId))
        assertEquals("unrelated-id", journal.resolveForCore("unrelated-id"))
    }

    // -- storage degradation ----------------------------------------------------

    @Test
    fun corrupt_journal_lines_are_skipped_visibly() {
        val root = tempRoot()
        journalOn(root).createDraft("살아남은 현장", null, null, null)
        root.resolve(PendingSiteJournal.FILE_NAME).toFile()
            .appendText("{not-json}\n{\"kind\":\"draft\"}\n")
        val reopened = PendingSiteJournal(root, { "2026-10-24T01:00:00Z" })
        assertEquals(1, reopened.pendingDrafts().size)
        assertTrue(reopened.recoveredPartialWrites >= 2)
    }

    @Test
    fun unavailable_journal_reads_empty_and_refuses_writes() {
        val reason = IOException("journal unavailable for test")
        val journal = PendingSiteJournal.unavailable(reason)
        assertTrue(journal.pendingDrafts().isEmpty())
        assertTrue(journal.statuses().isEmpty())
        assertNull(journal.canonicalIdFor("any"))
        assertEquals("any", journal.resolveForCore("any"))
        assertFailsWith<IOException> {
            journal.createDraft("현장", null, null, null)
        }
        // Unknown-id guards refuse before any write on an empty (degraded)
        // read: still a fail-closed refusal, never a silent write. The
        // captured reason surfaces on the paths without preconditions.
        assertFailsWith<IllegalStateException> {
            journal.recordAttempt("any", "err")
        }
        assertFailsWith<IllegalStateException> {
            journal.recordSynced("any", "canon", PendingSyncOutcome.CREATED)
        }
        assertFailsWith<IOException> {
            journal.repairAfterFailure()
        }
    }

    @Test
    fun poisoned_journal_blocks_later_writes_fail_closed() {
        val root = tempRoot()
        // One good draft with the real filesystem (the retained fact).
        journalOn(root).createDraft("첫 현장", null, null, null)
        // Then the disk fails: a torn write whose rollback cannot be made
        // durable poisons the journal — every later write fails closed.
        val exploding = object : JournalIo {
            override fun appendSync(file: java.io.File, bytes: ByteArray) {
                // Torn write: partial bytes land, then the fsync fails.
                java.io.FileOutputStream(file, true).use {
                    it.write(bytes.copyOf(bytes.size / 2))
                }
                throw IOException("fsync failed mid-append")
            }

            override fun truncateTo(file: java.io.File, length: Long) {
                throw IOException("truncate failed too")
            }
        }
        val journal = PendingSiteJournal(root, { "2026-10-24T00:00:00Z" }, exploding)
        val poisoned = assertFailsWith<IOException> {
            journal.createDraft("둘째 현장", null, null, null)
        }
        assertTrue(
            (poisoned.message ?: "").contains("repairAfterFailure"),
            "poison names the explicit repair: ${poisoned.message}",
        )
        // Facts retained: the first draft still reads back.
        assertEquals(1, journal.pendingDrafts().size)
        assertEquals("첫 현장", journal.pendingDrafts().single().name)
    }

    // -- helpers ---------------------------------------------------------------

    private fun fakePreexistingSite(fake: FakeCore, name: String): String {
        val created = postToFake(
            fake, "/sites",
            Mop.json.createObjectNode().put("org_id", "org-a").put("name", name),
        )
        return created.get("site_id").asText()
    }

    /**
     * Org-enforcing Core double: the POST honours ONLY the token org (403
     * otherwise — the server rule `org_id != caller org → FORBIDDEN` the
     * 403-recovery path depends on). Name dedup + fill-only-if-empty mirror
     * `sites.py`, so it stays a faithful double for the uploader paths.
     */
    private class OrgGateFake(
        val tokenOrg: String,
        /** When true, the site list appears only from the second GET on
         *  (simulates a token switch mid-run: empty at probe, live later). */
        val sitesOnSecondList: Boolean = false,
    ) : CoreTransport {
        val sites = linkedMapOf<String, ObjectNode>()
        val postedOrgs = mutableListOf<String>()
        private var listsServed = 0
        private var counter = 0

        fun addSite(
            siteId: String, orgId: String, name: String,
            lat: Double?, lon: Double?, radius: Double?,
        ) {
            val site = Mop.json.createObjectNode()
            site.put("site_id", siteId)
            site.put("org_id", orgId)
            site.put("name", name)
            if (lat != null && lon != null && radius != null) {
                site.put("center_lat", lat)
                site.put("center_lon", lon)
                site.put("radius_m", radius)
            } else {
                site.putNull("center_lat")
                site.putNull("center_lon")
                site.putNull("radius_m")
            }
            sites[siteId] = site
        }

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): CoreHttpResponse {
            fun ok(node: com.fasterxml.jackson.databind.JsonNode) =
                CoreHttpResponse(200, Mop.json.writeValueAsBytes(node))
            if (method == "GET" && path == "/healthz") {
                return ok(Mop.json.createObjectNode().put("status", "ok"))
            }
            if (method == "GET" && path == "/sites") {
                listsServed += 1
                val items = Mop.json.createArrayNode()
                if (!sitesOnSecondList || listsServed >= 2) {
                    sites.values.forEach { items.add(it) }
                }
                return ok(
                    Mop.json.createObjectNode().apply {
                        set<com.fasterxml.jackson.databind.JsonNode>("items", items)
                        putNull("next_cursor")
                    },
                )
            }
            if (method == "POST" && path == "/sites") {
                val req = Mop.json.readTree(body) as ObjectNode
                val org = req.get("org_id")?.asText() ?: ""
                postedOrgs.add(org)
                if (org != tokenOrg) {
                    return CoreHttpResponse(
                        403,
                        Mop.json.writeValueAsBytes(
                            Mop.json.createObjectNode()
                                .put("code", "FORBIDDEN")
                                .put("message", "org_id does not match caller org"),
                        ),
                    )
                }
                val name = req.get("name").asText()
                val existing = sites.values.firstOrNull { it.get("name")?.asText() == name }
                if (existing != null) {
                    val lat = req.get("center_lat")?.takeIf { it.isNumber }?.asDouble()
                    val lon = req.get("center_lon")?.takeIf { it.isNumber }?.asDouble()
                    val radius = req.get("radius_m")?.takeIf { it.isNumber }?.asDouble()
                    if (lat != null && lon != null && radius != null &&
                        existing.get("center_lat")?.isNumber != true
                    ) {
                        existing.put("center_lat", lat)
                        existing.put("center_lon", lon)
                        existing.put("radius_m", radius)
                    }
                    return ok(existing.deepCopy())
                }
                counter += 1
                val site = Mop.json.createObjectNode()
                site.put("site_id", "00000000-0000-4000-8000-00000000e%03d".format(counter))
                site.put("org_id", tokenOrg)
                site.put("name", name)
                val lat = req.get("center_lat")?.takeIf { it.isNumber }?.asDouble()
                val lon = req.get("center_lon")?.takeIf { it.isNumber }?.asDouble()
                val radius = req.get("radius_m")?.takeIf { it.isNumber }?.asDouble()
                if (lat != null && lon != null && radius != null) {
                    site.put("center_lat", lat)
                    site.put("center_lon", lon)
                    site.put("radius_m", radius)
                } else {
                    site.putNull("center_lat")
                    site.putNull("center_lon")
                    site.putNull("radius_m")
                }
                sites[site.get("site_id").asText()] = site
                return ok(site.deepCopy())
            }
            return CoreHttpResponse(404, ByteArray(0))
        }
    }

    private fun fakePreexistingSiteView(fake: FakeCore, name: String): ObjectNode =
        postToFake(
            fake, "/sites",
            Mop.json.createObjectNode().put("org_id", "org-a").put("name", name),
        )
}
