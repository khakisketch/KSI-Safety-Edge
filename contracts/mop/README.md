# contracts/mop — MOP transport fixtures (A9-2)

Cross-language pins for the KSI MOP **transport**. These are transport
fixtures (byte-level framing + the existing envelope), not a new message
semantics contract: channels, message types, payload shapes and reliable
delivery rules stay defined by the existing contract (Python harness
`mop.py`, rc-core `Mop.kt`, design §8.2, `edge_replay.py`).

| file | produced by | pinned by |
|---|---|---|
| `rc-to-edge.mopv1.hex` | rc-core `MopEndpoint` (the RC command bytes the app sends) | rc-msdk `MopCrossLanguageTest.rc_commands_are_pinned_by_the_fixture` (byte equality) |
| `edge-to-rc.mopv1.hex` | C++ `MopEdgeEndpoint` + `MopLink` (`edge/tests/test_mop_e2e.cpp`) | edge `mop_e2e` (byte equality) and rc-msdk `cpp_edge_stream_reaches_evidence_ingest` (consumed through the REAL `MopEndpoint` + `EvidenceIngest` + `FileRcStore`) |

## Regeneration (only when the wire contract intentionally changes)

- `rc-to-edge`: delete the file and run `:rc-msdk:testDebugUnitTest` once —
  the Kotlin test prints `GENERATE rc-to-edge.mopv1.hex:` + the hex; commit it.
- `edge-to-rc`: `rm contracts/mop/edge-to-rc.mopv1.hex &&
  KSI_MOP_WRITE_FIXTURE=1 ./edge/build/mop_e2e` writes the file, then re-run
  `ctest` to verify the pinned bytes.

## Byte-identity scope

- The **frame layer** (`u32be(chunkLength) | u8(flags) | chunk`) is
  byte-identical across Python/Kotlin/C++ — pinned by golden vectors in
  both `edge/tests/test_mop_frame.cpp` and `MopFrameCodecTest.kt`.
- **Payload digests** (`payload_sha256` = JCS/RFC 8785 SHA-256 of the
  payload object) are byte-identical — pinned against Python-generated
  goldens on all sides.
- **Envelope JSON key order is NOT a byte contract**: Kotlin writes Jackson
  insertion order, C++ writes JCS-sorted; both sides parse JSON.
- The edge→rc fixture's OBSERVATION / DETECTION_EVENT / FLIGHT_SUMMARY
  payloads are produced by the REAL C++ mapping (`MopTickEvidence`, from a
  `TickRecord`), and the Kotlin suite asserts their field-level fidelity
  (class labels, track ids, grades, mission provenance) — so the fixture
  pins the mapping, not just the framing.

Hex format: lowercase hex of the concatenated framed bytes, single line.
