# Phase 7 Plan: Streaming Hardening & Format-Misclassification Fixes

## Status
v4.2 — Phase 7b (GREEN) COMPLETE. Full suite: 7930 run, 0 failures, 0 errors.
Next: 7c documentation/claims ratification is folded into the 7b exit (see below).

## Phase 7b Execution Status (record)

- **(RED→GREEN)** all 51 Phase-7a reds now pass: routing/misclassification (13), npm
  memory-safety (8), bomb-cascade (10), hardening (5), marker property (1), reader stream
  (2), filter (1), S-5 contract (11).
- **(+2 new)** deferred 7b tests landed and pass: `NpmMemorySafetyTest.constructorFailureResetsStreamFlag`
  (source-backed reader construction; P0-E), `NpmMemorySafetyTest.aggregateBudgetAdjoint`
  (admission-time reserve).
- **Full suite `mvn test`: 7930 run, Failures: 0, Errors: 0** (previous 7928 + 2; zero stray).
- Shared infra shipped: `PackageSource`, `Spool` (private 0700 dir, 0600 files, Cleaner,
  admission-time aggregate budget, startup stale-spool sweep), `BoundedInflateStream`,
  `Archives`, `Limits`, `markers.{NpmEntryMarker,CargoTomlMarker,PkgInfoMarker,MetaMarker}`.
- Reader: spool-once-and-handoff; `read(Path,...)` bounded path scan (no temp copy).
- Routing: `.tgz`/`.crate` ambiguous; bounded scan (compressed 256MiB / inflated 16MiB /
  1000 entries); fail-closed; shared top-level markers with directory-skip; `MAX_DETECTION_SIZE`
  removed; filter `.tgz` dropped.
- Five packages fully streaming (no whole-file `byte[]`): npm, PyPI (ZipFile wheels + sdist
  scan), Crates, CPAN, Conda v1/v2; per-pass budgets + fail-fast `hasNext`; per-entry 10MiB
  buffered content; metadata scan entry-count caps; per-entry metadata caps (1MiB PyPI/Crates/CPAN).
- 1 MiB metadata cap now matches the plan limit table (was 10 MiB); **Crates/Cpad `e.getMessage()`
  splices removed; budget/scan SecurityExceptions use basename display names (no temp paths)**.
- S-5 `close()` closed-flag in all 11 packages; CAS-restore on stream-construction failure in
  all 11; distinct "closed"/"already open" messages; hard-link targets surfaced in tar entry streams.
- PathValidator: backslash normalization, CR/LF rejection, symlink-target validity at
  `openStream()` (npm + tar streams); unsupported damage to unrelated suites = none.

## Phase 7b Adversarial-Review Remediations (invariant 11)

Hostile review found and fixed in 7b:
1. **Temp-path leak in budget messages** - `Archives` now takes the SANITIZED basename display
   name; scan-cap SecurityExceptions no longer render the spool/private temp path.
2. **Stale-spool accumulation across JVM runs** (no GC/Cleaner coverage on SIGKILL/OOM) -
   implemented the startup sweep in `Spool.privateDir()`; residue assertions now look INSIDE the
   private dirs (`TempDirAsserter`).
3. **Admission-time aggregate reserve** - `Spool.create` reserves the spool cap BEFORE copying
   (fails closed prior to disk writes); unused reserve released on success; deleted bytes remain
   charged until delete.
4. **Crates `parseMetadata` e.getMessage splice + 10 MiB metadata caps** - splices removed;
   PyPI/Crates/CPAN metadata now capped at 1 MiB (plan table).
5. **CAS-restore absent in the 6 out-of-scope packages** - added restore for all 11.
6. **Hard-link targets not surfaced** - now reported in tar entry streams (`nextEntry`).
7. **Aggregate test redesigned** for admission-reserve semantics (direct `Spool.create`).

## Accepted/flagged deviations (reviewer-confirmed, low impact)
- Routing scan has NO per-entry size cap (fix 9 partial): routing matches NAMES only; the
  compressed + inflated + entry-count caps bound all routing work; reading every entry payload
  during classification would be strictly worse.
- No `BoundedZipInflater` class / no `*OuterZipLayerBounded` tests: the ZipFile-based design does
  NOT inflate skipped entries (verified empirically), so the zip-skip decompression vector is
  eliminated structurally; the ZIP per-pass inflated budget is enforced inline in the wheel/conda-v2
  entry streams and covered by `streamPassBudgetTripsOnZipEntryStreams`.
- `pkg.close()` while a wheel/conda-v2 entry stream is still open leaks the ZipFile fd: documented
  caller discipline (close streams before `close()`), matching the pre-existing single-stream
  contract.

## Phase 7a Execution Status (record)

- **RED verified on the full suite**: `mvn test` -> 7928 run, 50 failures + 1 error = the
  intended Red set, zero stray failures.
- New red tests: `TgzNotNpmRegressionTest` (13), `NpmMemorySafetyTest` (8),
  `EcosystemDecompressionBombTest` (10), `SecurityHardeningTest` (5),
  `MarkerAgreementPropertyTest` (deterministic, `@Seed`), `LanguagePackageReaderIntegrationTest`
  (2: stream-drain `readStream_validSdistStillParses` ERROR + `readStream_genericTgzNotNpm`).
- Approved-change reds: `AnnattoProcessFilterTest.detectEcosystem_tgz_isNotClaimedByName` (1);
  `LanguagePackageContractTest.streamEntries_afterCloseThrows` inherited by all 11 contract
  classes (1 each) — the S-5 cascade.
- Guards confirmed green under the scaffolding: npm/other corpus contract tests
  (e.g. Npm 168/169), `SecurityLimitsTest`, `ThreadSafetyTest`, `StreamingResourceManagementTest`,
  `MimeTypeFuzzTest`, `StreamedContentPropertyTest`, handlers/extractors, disambiguation
  (`routerDetectsNpmFromTgzPath` still passes via content marker; renamed in 7b).
- Scaffolding delivered: `internal/Limits` + `fromStream(..., Limits)` overloads
  (pass-through until 7b — the ONLY "unimplemented" surface, clearly marked; invariant 6),
  `testutil/{ArchiveBuilder, CountingInputStream, TempDirAsserter}` (snapshot-diff).
- Adversarial review (invariant 11) applied (see below for the resolutions it forced).

## Decisions (approved by user, 2026-08-10)

- **S-5 close() contract: BREAKING.** `close()` deletes the package's OWNED temp source and
  marks the package closed; `streamEntries()` after `close()` throws `IllegalStateException`.
  `fromPath`-backed packages never own a temp (nothing to delete) but get the same closed flag.
  `LanguagePackageContractTest.streamEntries_afterCloseAllowsNewStream`, the `LanguagePackage`
  javadoc, and ADR-001 claims are updated. **Cascade:** this abstract test is inherited by all
  11 `*PackageContractTest`s, so ALL 11 packages (not just the 5 in-scope) implement the closed
  flag on `close()` in Phase 7b (small, mechanical) — otherwise those contract tests stay red
  and the full-suite gate fails.
- **S-3 scope: INCLUDE bomb-cascade now** (PyPI wheel+sdist, Crates, CPAN, Conda v1/v2).
  Remaining ecosystems (Go, RubyGems, Packagist, CocoaPods, Hex, LuaRocks) follow in phase 8.
- **S-2 fromPath: DIRECT READ** (caller `Path`; documented lifetime contract).
- **S-1 filter `.tgz`: INCLUDE NOW.** `AnnattoProcessFilter` stops claiming `.tgz` by name;
  `AnnattoProcessFilterTest.detectEcosystem_tgz_isNpm` updated to assert not-claimed.

---

## Overview

The survey workflow ran Goatrodeo over the workspace; `repo_ea.tgz` (4.9 GiB) crashed
`NpmPackage.fromStream(NpmPackage.java:77)` with `OutOfMemoryError: Required array length
2147483639 + 9 is too large` (Java `byte[]` limit, not heap).

Defect class 1 — **misclassification**: `*.tgz` routed to NPM by filename alone
(`EcosystemRouter.routeFromFilenameOnly`, :203-205); content disambiguator accepts nested
markers (`endsWith("package/package.json")`, :314); the process filter claims `.tgz` by name.
Defect class 2 — **whole-file buffering**: every `*Package.fromStream` does
`stream.transferTo(new ByteArrayOutputStream())` retaining the archive as `byte[]`; npm died
first. Worse: PyPI-sdist/Crates/CPAN decompress the whole gzip into a SECOND unbounded `byte[]`
(`PyPIPackage.java:220`, `CratesPackage.java:188`, `CpanPackage.java:196`); Conda v2
decompresses zstd unbounded (`extractIndexFromZstdTar`, :184-202). A few KB of compressible
bytes can OOM all five.

---

## Review Feedback Incorporated (invariants 10; rounds 1 & 2)

Round-1 findings already folded in (bounded routing scan, reader spool-once-and-handoff,
per-entry-buffered `openStream`, per-pass decompressed budgets, `streamOpen` CAS restore,
observable-RED tests, private 0700 temp dir + per-file perms + aggregate budget + Cleaner,
basename-only messages, `\r`/`\n` rejection, instance-scoped limits).

Round-2 additions (all verified against code by reviewers):

1. **ZIP-layer decompression is NOT covered by a gzip/zstd/bzip2-layer budget.** PyPI wheel
   metadata pass and Conda v2 outer ZIP skip-decompress each entry; `ZipInputStream` skipping
   only decompresses the entries it iterates, but iterating a wheel to reach `.dist-info/
   METADATA` (near the end) decompresses the padded entries before it; the conda v2 outer pass
   has the same hole. Add a ZIP-layer inflated-byte budget for these two metadata passes and a
   per-`streamEntries()` pass budget for zip entry streams (10 MiB x 10k entries = ~100 GiB
   otherwise). Add a "ZIP layer" row to the Limits table and section-D tests for the wheel and
   conda-v2-outer-zip bombs.
2. **Shared marker predicates for ALL FIVE**, not just npm: router uses loose `endsWith`/
   `contains` for PyPI/Crates/CPAN markers (`EcosystemRouter.java:314-327,391`) while the
   extractors require strict 2-segment predicates. Introduce `NpmEntryMarker`,
   `CargoTomlMarker`, `PkgInfoMarker`, `MetaMarker` in phase 7 so routing and extraction agree.
3. **S-5 cascade** (above): all 11 packages get closed semantics in 7b.
4. **Test scaffolding must exist in Phase 7a** (red): package-private `Limits` factory
   overloads + `CountingInputStream` test-util + shared archive-builder in `testutil`, so RED
   fixtures are tiny (e.g., 2 MiB bound vs 64 MiB zeros) and never OOM while RED (size must be
   between the test bound and ~2 GiB). `NpmPackage`/`PyPIPackage`/etc. already cap decompressed
   metadata at 500 MiB (npm `decompressGzip`, :171) — so tests that RED on the SCAN bound MUST
   use a smaller injected bound. Fixture note: the oversized entry must PRECEDE the metadata
   marker inside the tar (or extraction returns before touching it and the test is
   unimplementable).
5. **Wheels use `ZipFile`** (central directory: near-constant-time `.dist-info/METADATA`
   lookup, real `entry.size()`; `ZipInputStream` yields `size()==-1` under data descriptors and
   forces a full sequential read for near-end METADATA). Conda v2 outer uses `ZipFile` too.
6. **`openStream()` eager size reject**: skip when `size()<0` (data descriptors); rely on the
   bounded read loop.
7. **Budget accounting**: tar trailer/padding (2 KB zero blocks) counts against
   pass/scan budgets -> tests assert `<= cap`, not exact totals.
8. **Mid-entry budget trip**: define and test `hasNext()` semantics after `openStream()`
   throws mid-entry.
9. **Multi-member gzip**: JDK `GZIPInputStream` processes the FIRST member only; document and
   add a routing/extractor agreement test; do not parse the tail.
10. **Symlink/hard-link correctness**: `PathValidator.isSymlinkSafe` is currently dead code and
    `PackageEntry` claims targets are "validated within archive" (false). Validate symlink
    targets in `nextEntry()` for tar sources; report hard-link targets; or correct the javadoc.
11. **PathValidator backslash**: `validateEntryName` uses `Paths.get(...).normalize()` which on
    POSIX treats `\` as literal — backslash-normalize before traversal checks (agrees with the
    shared markers) and route metadata-scan entry names through it.
12. **Per-entry metadata caps for PyPI**: `extractMetadataFromWheel`/`fromSdist`
    `readStreamToString` have NO per-entry cap (plan's "limits retained" claim was false for
    PyPI). Add `MAX_METADATA_SIZE` (1 MiB).
13. **Dense-header CPU DoS**: metadata scans (PyPI/Crates/CPAN/Conda) need an entry-count cap
    (`MAX_ENTRIES`), not just enumeration and routing.
14. **AggregateSpoolBudget**: admission-time reservation (before copy), release exactly once on
    delete; over-budget = fail-closed SecurityException, not blocking; it is unavoidably
    process-global mutable state — amend ADR-004 to sanction this one singleton (or injectable).
15. **Temp-dir rigor**: `Files.createTempFile` mode is `0666 & ~umask` — the load-bearing
    control is a private 0700 `Files.createTempDirectory` with a random component (never a
    fixed name), per-file chmod is defense-in-depth; verify owner/mode on any reuse; startup
    stale-spool sweep (Cleaner cannot cover SIGKILL/OOM); routing's own temp files
    (`EcosystemRouter.java:300-302,377-379`) move into the private dir.
16. **Messages**: defensively `basename()` the caller `filename`; stop appending raw
    `e.getMessage()` (DOES leak paths) — use static text + exception type. Site list known.
17. **`.crate`/`.gem`/`.whl`/`.conda` filter claims**: filter still claims `.crate`/`.gem`/
    `.whl`/`.conda` by name (out of phase scope); document the reader-vs-filter inconsistency
    and the `.tgz` content-routing regression (npm still processed via content when named
    `.tgz`).

---

## 7a Adversarial-Review Resolutions (already applied to tests + this plan)

1. **Marker predicate rejects traversal segments.** `../package.json` must NEVER be an npm
   marker (router or extractor): the shared `NpmEntryMarker.isPackageJson` and the test-local
   predicates reject `..`/`.` segments. The extractor's current acceptance of `../package.json`
   is removed as part of 7b (no existing test asserts it).
2. **`.crate` becomes content-required** (fix 8, above) — resolves the plan's fix-vs-test
   contradiction; `readStream_validCrateStillParses` and `readStream_withFilenameHint` and
   `readStream_validNpmTgzStillParses` become HANDOFF-DEPENDENT guards at 7b (they only pass
   with the spool-once-and-handoff reader, since `.tgz`/`.crate` names no longer bypass content
   disambiguation).
3. **Symlink-target validation site = `openStream()`** (fix 16), so
   `SecurityLimitsTest.symlinkToParentRejected` stays green.
4. **Residue assertions are snapshot-diff** (`TempDirAsserter.assertNoNewFilesSince`), immune
   to pre-existing residue; 7b MUST adopt the close-discipline (every `fromStream` suite closes
   its packages) so Cleaner/GC never becomes a flake vector; the private spool dir is created
   under a name that residue checks ignore or excluded explicitly.
5. **Message contract pinned (7b must produce these substrings):** spool/scan/stream-pass/
   zip-pass SecurityExceptions contain `"limit"`; metadata entry-count contains `"entry"`;
   spool messages contain `"spool"`; closed-package `streamEntries()` message contains
   `"closed"` and stays DISTINCT from the in-use `"already open"` message (the S-5 closed flag
   must not collide with the CAS-restored stream-in-use path).
6. **Fail-fast pass-budget contract:** once a per-pass budget trips mid-entry, subsequent
   `hasNext()` on that stream throws (verified by
   `NpmMemorySafetyTest.openStreamMidEntryBudgetTripHasNextSemantics`).
7. **`constructorFailureResetsStreamFlag` and `aggregateBudgetAdjoint`:** DEPLOYED-AT-7B (they
   require 7b-only mechanisms — the source-backed reader constructor and the aggregate budget);
   they are added together with their implementation and listed on the 7b gate, not as 7a reds.
8. **Property determinism:** `MarkerAgreementPropertyTest` uses a probe-based arbitrary (each
   evaluation contains a guaranteed-divergent entry name) plus `@Seed` so it is deterministically
   RED in CI and deterministically GREEN after 7b.

---

## Root Cause Analysis

### Bug 1: extension-only routing

`routeFromFilenameOnly` short-circuits `.tgz`->NPM, `.crate`->CRATES, `.gem`->RUBYGEMS,
`.whl`->PYPI, `.conda`->CONDA with no content check; the filter claims `.tgz`, `.whl`, `.crate`,
`.gem`, `.conda`, `.rock`, `.rockspec`, `.podspec` by name. A misnamed giant tar storms into the
matching `fromStream` whole-file buffer. After the streaming fix (below), misclassification no
longer OOMs — it fails cleanly; but the three markers used for gzip-family content routing
(PyPI/Crates/CPAN) are still too loose and must be shared/strict, or content routing accepts
nested markers.

### Bug 2: whole-file buffering

| Class | Path | Severity |
|---|---|---|
| npm / PyPI (wheel+sdist) / Crates / CPAN / Conda | `transferTo(baos)` whole compressed `byte[]` | OOM > ~2 GiB |
| PyPI sdist / Crates / CPAN | gzip `transferTo(baos)` — unbounded decompressed `byte[]` | bomb -> OOM from KB-scale input |
| Conda v2 | whole `byte[]` + unbounded zstd decompress (`extractIndexFromZstdTar`) | bomb class |
| Wheels + conda v2 outer ZIP | skip-decompress of iterated entries during metadata pass, no inflated cap | bomb class |
| Every `streamEntries()` | fresh full re-decompress per pass, unbounded | CPU/IO amplification |

---

## Proposed Tests (red-to-green)

### Phase 7a scaffolding (needed to make RED tests safe/fast)
- Package-private `Limits` factory overloads for the 5 in-scope `fromStream` (record:
  spool, scan, stream, zip, entry, entries) + `internal` `CountingInputStream` test-util +
  shared `testutil/ArchiveBuilder` (relocating `SecurityLimitsTest.addTarEntry`/`writeTarEntry`
  patterns) + `testutil/TempDirAsserter` (no `annatto-*` residue). Declared Phase-7a — tested.
- Existing wildcard-import / unused-field drift fixed as encountered.

### A. Routing & misclassification — `disambiguation/TgzNotNpmRegressionTest.java`
| Test | What / theory |
|---|---|
| `tgzWithGenericContentIsNotRoutedToNpm` | generic `.tgz` -> `route()` empty |
| `readPathWithGenericTgzThrowsUnknownFormat` | `read(path)` / `read(path,"application/gzip")` -> `UnknownFormatException`, never `NpmPackage` |
| `repoEaLikeTgzNotNpm` | `repo_ea/{README.md,src/main.c}` + nested `repo_ea/node_modules/lodash/package/package.json` -> not NPM (incident shape; kills `endsWith` looseness) |
| `nestedPackageJsonNotTreatedAsNpmMarker` | only `x/y/package.json` (depth ≥2) -> not NPM |
| `directoryEntryNamedPackageJsonIsNotMarker` | directory entry `x/package.json` -> not NPM (router skips dirs) |
| `realNpmLayoutStillRoutesToNpm` | guard: `package/package.json` and `<name>-1.0.0/package.json` -> NPM |
| `routeFromFilenameWithTgzMimeIsAmbiguous` | `routeFromFilename("x.tgz","application/gzip")` empty |
| `markerBoundaryParameterized` | `package.json`, `<dir>/package.json`, `<dir>//package.json`, `/package.json`, `../package.json`, `a/b/package.json` — router and extractor AGREE for all five markers |
| `filterDoesNotClaimTgzByName` | `AnnattoProcessFilter.detectEcosystem("repo_ea.tgz")` empty (replaces `detectEcosystem_tgz_isNpm`) |
| `crateGenericContentNotRoutedToCrates` | `x.crate` without strict top-level Cargo.toml -> not CRATES |
| `cpanPypiNestedMarkersRejected` | nested `a/b/PKG-INFO`, `a/b/Cargo.toml`, `a/b/META.json` -> not routed (shared strict predicates) |
| `multiMemberGzipRoutingAgreesWithExtraction` | multi-member gzip: routing view (marker in member 1) agrees with extraction (member 1 only); document first-member semantics |

### B. Reader integration — `LanguagePackageReaderIntegrationTest` additions
`readStream_validNpmTgzStillParses`, `readStream_validCrateStillParses`,
`readStream_validSdistStillParses`, `readStream_genericTgzNotNpm` (no temp leak; spool
ownership on `UnknownFormat`). These are REGRESSION GUARDS (green pre-fix and post-fix) per
round-2 QA — listed as guards, not red tests.

### C. npm streaming — `resource/NpmMemorySafetyTest.java`
| Test | Classification |
|---|---|
| `fromStreamNeverBuffersWholePackage` | RED — counting source, drained ≤ spool bound, `SecurityException` naming spool bound, `@Timeout` |
| `spoolOverrunCleansUpTempFile` | RED — residue-free after failed spool |
| `metadataExtractionScansBounded` | RED via injected smaller scan bound (npm already caps at 500 MiB — equality is GREEN pre-fix) |
| `missingPackageJsonCleansUpTempFile` | RED — `MalformedPackageException`, no residue |
| `enumerationDecompressionBudgetEnforced` | RED — skip-only enumeration trips pass budget (proves `BoundedInflateStream` counts skipped bytes as reads; assert `<= cap`) |
| `openStreamMidEntryBudgetTripHasNextSemantics` | design+test `hasNext()` after mid-entry SecurityException |
| `streamEntriesOpenStreamBoundedPerEntry` | guard (already enforced) + `size()==-1` eager-reject-skip behavior |
| `closeMarksPackageClosedAndDeletesSpool` | S-5 — temp gone, `streamEntries()` throws `IllegalStateException`; `fromPath` variant: close does NOT delete caller path, flag applied |
| `noRegisteredStateLeakBetweenPackages` | per-instance `Limits` honored independently |
| `doubleCloseAndCloseAfterFailureAreSafe` | idempotent; close-after-failed-construction never throws |
| `constructorFailureResetsStreamFlag` | P0-E |
| `largeStreamFailsClosedNoOom` | incident proxy — `SecurityException` + `@Timeout`, never OOM |

### D. Bomb-cascade ecosystems — `security/EcosystemDecompressionBombTest.java` (parameterized)
| Test | Classification |
|---|---|
| `sdistCrateCpanDecompressionIsBounded` | RED via injected smaller scan bound; fixture: marker preceded by huge zeros entry; pre-fix SUCCEEDS (RED-safe), never OOM |
| `condaV1Bzip2DecompressionIsBounded` | RED via injected bound |
| `condaV2ZstdDecompressionIsBounded` | RED via injected bound; `info-…tar.zst` giant-before-index ordering |
| `wheelMetadataPassZipLayerBounded` | RED — `METADATA` preceded by huge padded `.py` entry expands past ZIP-layer budget |
| `condaV2OuterZipLayerBounded` | RED — early padding entry in outer `.conda` past ZIP-layer budget |
| `wholeFileOversizeFailsClosed` | RED via injected spool bound (all five) |
| `perEntryMetadataCapsEnforced` | RED — 2 GiB-claimed `METADATA`/`PKG-INFO` single entry fails per-entry cap early (PyPI had NO cap) |
| `metadataScanEntryCountBounded` | RED — dense-header tar (many small entries, no marker) trips scan entry cap (CPU-DoS guard) |
| `streamPassBudgetTripsOnZipEntryStreams` | RED — wheel/conda entry streams accumulate > pass budget across opened entries |

### E. Security hardening — `security/SecurityLimitsTest` additions
`spoolScanMessagesDoNotLeakPaths` (driven fixture: `read(Path,…)` failure carrying a full
caller path; asserts basename-only, no `annatto-*`/`/tmp/`, no raw `e.getMessage()` splice),
`entryNameCarriageReturnRejected` (`\r`/`\n` -> SecurityException), `backslashTraversalRejected`
(`a\..\..\secret` rejected after backslash normalization),
`symlinkTargetValidated` / `hardLinkTargetReported` (tar symlink/hardlink semantics),
`tempResidueNoneOutsidePrivateDir` (routing + package spools all under the private dir),
`aggregateBudgetAdjoint` (admission-time reservation; over-budget fail-closed).

### F. Property tests — `MimeTypeFuzzTest` + `fuzz/StreamedContentPropertyTest.java`
| Test | Classification |
|---|---|
| `routingAgreesWithSharedPredicates` | RED — 7a test-owned strict predicates vs current loose router (nested markers mismatch); GREEN once shared markers land |
| `streamedEntryContentMatchesDeclaredSize` | GUARD (green pre-fix; matches existing `entryContentStreamBounded`) |
| `roundTripAcrossAllInScopeEcosystems` | GUARD — random small artifacts: failures only in {SecurityException, MalformedPackageException, UnknownFormatException}; meaningful only under small injected `Limits` |

### G. Guard rails — existing tests that MUST keep passing
- `EcosystemRouterDisambiguationTest` — ENTIRE class (items 8 rewrites the gzip/zip blocks;
  `gzipWithPkgInfoIsPypi`, `gzipWithPyprojectTomlIsPypi`, `gzipWithCargoTomlIsCrates`,
  `gzipWithMetaJsonIsCpan`, `gzipWithMetaYmlIsCpan`, `ambiguousGzipDefaultsToNone`,
  `emptyGzipThrows`, `zipWithDistInfoIsPypiWheel`, `zipWithTarZstIsConda`).
- `LanguagePackageReaderIntegrationTest`, `NpmMetadataExtractorTest.isPackageJson_*`,
  `NpmPackageContractTest`, `CratesPackageContractTest`, `PyPIPackageContractTest`,
  `CpanPackageContractTest`, `CondaPackageContractTest`, `SourceOfTruthIntegrationTest`.
- `SecurityLimitsTest`, `StreamingResourceManagementTest`, `ThreadSafetyTest`, `MimeTypeFuzzTest`.
- `routerDetectsNpmFromTgzPath` — RENAMED/re-purposed: post-fix it passes only via content
  marker; rename to `routerDetectsNpmFromTgzContent` to stop asserting the (removed) name-based
  behavior.
- Approved changes: `AnnattoProcessFilterTest.detectEcosystem_tgz_isNpm`;
  `LanguagePackageContractTest.streamEntries_afterCloseAllowsNewStream` (S-5), and the closed
  flag implemented in ALL 11 packages (7b).
- `{pypi,crates,cpan,conda}/*MetadataExtractorTest`, `*PropertyTest`, `*HandlerTest` — LOW risk
  (disjoint code paths) unless their non-marker helpers are touched; keep them out of the shared
  marker change, except where their extractor's own `isXMarker` duplicates a new shared marker —
  in that case the extractor test is updated to the shared predicate and added to the approval
  list.

---

## Proposed Fixes

### Shared infrastructure
1. `internal.PackageSource` (sealed): `PathSource(callerPath)` | `OwnedSpooledPath`. Fresh
   reader (`FileInputStream`/`ZipFile`) per operation; no shared cast state.
2. `internal.Spool`: private 0700 random `Files.createTempDirectory` (load-bearing control) +
   per-file `rw-------` chmod (defense-in-depth) + random-name create; bounded copy
   (`MAX_SPOOLED_SIZE`, SecurityException past cap); no `REPLACE_EXISTING`; Cleaner cleanable on
   the Path (never the instance); immediate delete in construction-failure `finally`; `close()`
   per S-5.
3. `internal.AggregateSpoolBudget`: admission-time reservation BEFORE copy; release exactly
   once on delete; over-budget = fail-closed `SecurityException`; process-global injectable
   singleton; amend ADR-004 to sanction it.
4. `internal.BoundedInflateStream`: EOF-safe bounded wrapper between decompressor
   (gzip/zstd/bzip2) and archive reader; `skip()` implemented as counted read-discard; clean EOF
   at exact boundary, `SecurityException` only when EXCEEDED. Same construct for the ZIP layer
   (`BoundedZipInflater`) wrapping entry `openStream` inflated reads + metadata-pass skip counts.
5. `internal.ZipSource` helper: `ZipFile` random access on any `PackageSource`/owned path
   (wheels, conda v2); per-pass instance, closed by the entry stream.
6. Shared markers (single home each): `markers.NpmEntryMarker`, `markers.CargoTomlMarker`,
   `markers.PkgInfoMarker`, `markers.MetaMarker`; consumers = `EcosystemRouter` + each Package +
   each extractor (delete duplicated copies) so routing and extraction cannot disagree. Strict
   top-level predicates; router skips directories before matching; **all markers reject
   traversal segments (`..`/`.`) as prescribed by 7a resolution 1.**
7. Reader handoff: `read(InputStream,…)` bounded-spools ONCE, routes from the spooled file
   (bounded scan, no second copy), hands the OWNED spooled path to the package factory
   (`close()` deletes); `read(Path,…)` routes via bounded scan on a fresh stream (no spool) and
   hands the caller path to `fromPath` (never deletes it). `UnknownFormat` path deletes spools
   in `finally`.

### Routing (Bug 1)
8. `.tgz` -> ambiguous in `routeFromFilenameOnly`; `routeFromFilename` returns empty for
   ambiguous gzip/zip/tar. **`.crate` is also made content-required** (gzip-family S-3
   hardening, matching the RED test `crateGenericContentNotRoutedToCrates`); valid crates keep
   routing to CRATES via the shared strict `CargoTomlMarker` (`<dir>/Cargo.toml`).
9. Bounded routing scan for `disambiguateGzipTar`/`disambiguateZip`: `MAX_ROUTER_SPOOL`
   (256 MiB), `MAX_ROUTER_SCAN` (16 MiB decompressed, gzip layer), entries 1000, entry-size
   1 MiB; fail closed; wire/remove dead `MAX_DETECTION_SIZE`; use shared markers with
   directory-skip.
10. `AnnattoProcessFilter`: remove `.tgz` from `EXTENSION_MAP` (S-1). Keep `.crate`/`.gem`/
    `.whl`/`.conda`/`.rock`/`.rockspec` name claims (out of phase scope; documented
    inconsistency).

### npm (`NpmPackage`), PyPI (`PyPIPackage`), Crates, CPAN, Conda (v1/v2)
11. Same source model (direct read from `PathSource`, bounded spool for `OwnedSpooledPath`).
12. Metadata: single streaming pass with layer budgets — gzip: `BoundedInflateStream(MAX_SCAN_SIZE
    500 MiB)`; wheel/conda-outer: `ZipFile` + `BoundedZipInflater(ZIP-layer budget)`; conda v2
    inner: zstd-layer budget; conda v1: bzip2-layer budget. Entry-count cap (`MAX_ENTRIES`) on
    scans; per-entry metadata cap `MAX_METADATA_SIZE` (1 MiB, new for PyPI; retained for
    Crates/CPAN/Conda); no raw `(int) size()` casts; marker found via shared predicate.
13. `streamEntries()`: fresh per-pass chain with per-pass budgets (gzip/zstd/bzip2 layers;
    ZIP entry-stream pass budget for wheels/conda-v2); construct-then-CAS (`streamOpen` restore);
    `openStream()` per-entry bounded buffer (10 MiB), eager declared-size reject only when
    `size()>=0`; defined `hasNext()` semantics after a mid-entry budget trip.
14. `close()`: delete OWNED spool (never the caller path); set closed flag; `streamEntries()`
    after close -> `IllegalStateException`; idempotent. All 11 packages get the flag in 7b.
15. Remove obsolete "pre-decompress GZIP to avoid concurrency" comments — fresh per-stream
    `Inflater` isolation is the (tested) guarantee.

### Cross-cutting
16. `PathValidator`: backslash-normalize before traversal checks; reject `\r`/`\n`; validate
    symlink targets at **`openStream()`** (content-access time) and report hard-link targets at
    `nextEntry()` — NOT at `nextEntry()` for symlinks, so the existing
    `SecurityLimitsTest.symlinkToParentRejected` (which iterates and expects the unsafe target
    to be surfaced, not thrown) stays green. The unsafe-symlink rejection RED test is
    `SecurityHardeningTest.unsafeSymlinkOpenStreamRejected`.
17. Messages: defensively `basename()` caller `filename`; no raw `e.getMessage()` splicing
    (static text + type); no temp paths. (Site list gathered — applied across the five.)
18. Instance-scoped `Limits` records + package-private factory overloads; no other mutable
    static state (the sanctioned aggregate-budget singleton excepted).
19. Startup stale-spool sweep of `annatto-*` entries older than N hours in the private dir.
20. Docs/ADRs (invariant 12): `ARCHITECTURE.md`, `docs/api/language-package.md`, ADR-001
    (close semantics), ADR-002 (spool + Cleaner + handoff), ADR-004 (sanctioned singleton),
    ADR-005 (bounds table + system properties + multi-member gzip note + temp-dir ops notes),
    README. Every claim references its named test.

### Limits summary (defaults; overridable via `Limits` records / ADR-005 system properties)
| Bound | Default | Enforced at |
|---|---|---|
| `MAX_SPOOLED_SIZE` | 1 GiB | spool copy |
| aggregate spool budget | ~4 GiB | process (admission-time) |
| `MAX_ROUTER_SPOOL` | 256 MiB | routing copy |
| `MAX_ROUTER_SCAN` | 16 MiB | routing gzip layer |
| `MAX_SCAN_SIZE` (metadata) | 500 MiB | metadata decompressor layer |
| **ZIP inflated budget** | 1 GiB | wheel + conda-v2-outer metadata pass / zip entry streams |
| `MAX_STREAM_DECOMPRESSED` | 1 GiB | per `streamEntries()` pass |
| `MAX_METADATA_SIZE` | 1 MiB | per-entry metadata file |
| `MAX_ENTRY_SIZE` | 10 MiB | per-entry open |
| `MAX_ENTRIES` | 10 000 | enumeration AND metadata scans (and 1000 for routing) |

---

## Execution Phases

- **Phase 7a — Red**: scaffolding (Limits overloads, CountingInputStream, ArchiveBuilder,
  TempDirAsserter) + tests A–F; make the two approved test changes; add
  `todo!("to be implemented in phase 7b")` markers where fixes are required; `@Disabled` only
  where a fixture compiles against 7b code. Verify RED. Adversarial review pass.
- **Phase 7b — Green**: shared infra, routing fixes, five-ecosystem refactors, marks/filter/
  PathValidator/sanitization; closed flag across all 11; remove `todo!`/`@Disabled`. Full
  `mvn test`; expected count = previous + new; zero regression-list failures.
- **Phase 7c — Docs, ADRs & Exit**: claims updates; HS-2 exit review.

## HS-2 Exit Review (each phase)
1. Gap review (DONE/PARTIAL/MISSING); 2. Claims verification (named passing tests);
3. Hostile reviewer pass (routing spooling and all budgets included); 4. Full `mvn test`.

## Risks
| Risk | L | I | Mitigation |
|---|---|---|---|
| Temp-dir exhaustion under concurrency | Med | High | admission-time aggregate budget, `@Timeout` tests, startup sweep |
| Legit package over a cap | Low | Med | generous defaults, system properties, documented trade-offs |
| fromPath file mutation between construct & stream | Low | Med | documented lifetime contract + test; fallback = always-spool |
| zstd bomb fixture not constructible (aircompressor `ZstdOutputStream` unverified) | Med | Low | verify jar in 7a; fallback canned zstd fixture or `zstd-jni` (HS-1 approval) |
| Contract changes ripple (S-1, S-5) | — | — | approved; closed-flag across all 11 listed in 7b |
| RED fixtures accidentally OOM | Med | High | small injected Limits; fixtures between bound and ~2 GiB never; guards on sizes |
| Phase-8 backlog | Med | Low | committed phase 8 (Go/RubyGems/Packagist/CocoaPods/Hex/LuaRocks) incl. RubyGems nested-member per-entry inflate budget and plain-file clause |

## Remaining approval gates
None beyond the four decisions at the top; proceed requires explicit "implement" (7a).

*Plan Version: 4.0 (rounds 1 & 2 reviews + user decisions incorporated)*
*Date: 2026-08-10*