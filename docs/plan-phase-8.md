# Phase 8 Plan: Streaming Rollout to Go, RubyGems, Packagist, CocoaPods, Hex, LuaRocks

## Status
COMPLETE (7a RED → 7b GREEN). Full suite: **7939 run, 0 failures, 0 errors**.

## Execution record
- Whole-file `byte[]` removed from ALL SIX remaining ecosystems; every `*Package` now flows
  through `PackageSource` (path direct or owned bounded spool), `adoptSpool`/`fromSource`
  (spool released on construction failure and on `close()`).
- Reader: `createPackageFromOwnedSpool` now routes **all 11** ecosystems via `adoptSpool`
  (the spool re-read fallback branch was deleted); spool construction-failure cleanup is
  once-per-path idempotent (`Spool.RELEASED`).
- Streaming metadata extraction landed per plan (ZipFile scans for Go/Packagist/cocoapods-zip/
  luarocks-zip; plain-tar scan for Hex; plain-tar + NESTED-GZIP-bounded for RubyGems; bounded
  single-file reads for CocoaPods/LuaRocks JSON/Lua).
- Phase 8 adversarial review drove fixes: (1) Hex entry-stream now honors injected
  `Limits.entryBytes()`/`maxEntries()` (was hardcoded); (2) entry-count boundary off-by-one
  fixed in all 11 entry streams (post-advance `>`); (3) aggregate budget release is
  once-per-path idempotent (reader + package no longer double-release); (4) stale
  `// TODO(phase 8)` scaffolding comments removed.
- New tests: `Phase8StreamingTest` (9) incl. per-ecosystem spool/scan/entry caps, nested
  gzip bound, and the Hex entry-stream + exact-cap regression guards. Full suite 7930 → 7939.

## Scope
Phase 7 fixed npm/PyPI/Crates/CPAN/Conda. This phase applies the SAME streaming/budget model to
the remaining six `*Package.fromStream` implementations, which still do the whole-file
`stream.transferTo(new ByteArrayOutputStream())` pattern (Go, RubyGems, Packagist, CocoaPods,
Hex, LuaRocks). The phase-7 shared infrastructure is reused unchanged
(`internal.{PackageSource, Spool, Archives, BoundedInflateStream, Limits}`, `PackageSource`
Closed/S-5 flags already applied in 7b).

Per-ecosystem format:
| Package | Format | Metadata entry | Decompression |
|---|---|---|---|
| Go | zip | `**/go.mod` | none (read-only scan) |
| RubyGems | outer tar | `metadata.gz` (NESTED gzip) | YES — nested member; must be bounded |
| Packagist | zip | `composer.json` | none |
| CocoaPods | `.podspec.json` (plain) or zip | the single JSON / `*.podspec.json` inside zip | none |
| Hex | plain tar | `metadata.config` | none |
| LuaRocks | `.rockspec` (plain) or `.rock` (zip) | the rockspec / `*.rockspec` inside zip | none |

## Tests (red-to-green)
New `security/Phase8StreamingTest` + additions, using the phase-7 `Limits`/spool scaffolding:
- `wholeFileOversizeFailsClosedPhase8` — parameterized over all 6: `fromStream(...,
  Limits.spool(1 MiB))` on an incompressible-oversize package -> `SecurityException` (RED now:
  loads whole file and SUCCEEDS).
- `nestedGzipMetadataBounded` (RubyGems) — metadata.gz whose decompressed content exceeds
  `Limits.scan(...)` -> `SecurityException` (RED now: unbounded nested gzip).
- `zipMetadataScanBounded` (Go/Packagist) — metadata entry preceded by oversized padding entry ->
  `SecurityException` (RED now).
- `plainTarEntryCountBounded` (Hex) — dense header tar -> entry-count `SecurityException` (RED now).
- `jsonMetadataBounded` (CocoaPods/LuaRocks) — oversized `.podspec.json`/`.rockspec` ->
  `SecurityException` (RED now).
- Guards: corpus `*PackageContractTest` suites and `StreamingResourceManagementTest`/corpus
  integration stay green.

## Fixes
Per package (uniform pattern from 7b):
1. `fromPath` -> `PathSource` direct read (basename filename); `fromStream` -> bounded `Spool`;
   public `fromStream(..., Limits)` + `adoptSpool`; `fromSource` wraps metadata extraction and
   releases spool on construction failure.
2. Streaming metadata extraction:
   - Go/Packagist: `ZipFile` random access (`Archives.zipFile`), scan for the marker, bounded
     metadata read (1 MiB cap), entry-count cap.
   - RubyGems: fresh plain-tar chain; `metadata.gz` entry content read through
     `GZIPInputStream` + `BoundedInflateStream(scanBytes)`; bounded YAML read.
   - Hex: fresh plain-tar chain; `metadata.config` bounded; entry-count cap.
   - CocoaPods/LuaRocks: JSON/Lua plain single file read bounded (metadata cap); zip variant via
     `ZipFile` scan for `*.podspec.json`/`*.rockspec`.
3. Entry streams: fresh per pass (ZipFile for go/packagist/pods-zip/rocks-zip; fresh
   `FileInputStream`-backed plain tar for gems/hex) with per-entry caps, ZIP per-pass inflated
   accumulators for zip sources, entry-count caps, fail-fast budget latches.
4. Reader: add `adoptSpool` to all six and move them into `createPackageFromOwnedSpool`
   (handoff now works for every ecosystem; delete the spool-re-read `default` branch).
5. Markers where a marker predicate is worth sharing (composer.json, metadata.config, go.mod) —
   keep small; reuse `markers` package where cheap.
6. Docs/claims update (invariant 12) + full suite + adversarial review.

## Deviation notes
- Hex (plain tar) and zip metadata scans have NO decompression amplification; budgets focus on
  entry-count + per-entry/metadata caps (documented in ADR-005).
- `SingleEntryStream` (CocoaPods/LuaRocks JSON/Lua) returns the package file itself bounded by
  the metadata cap, read from the source path.

## Exit (HS-2)
Full `mvn test` green (expect prior 7930 + ~7 new), zero regression; adversarial review;
claims/docs refresh.