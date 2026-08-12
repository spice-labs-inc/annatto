# ADR-004: Execution Model (Single-Threaded)

## Status
Supersedes the Phase 0 "Thread Safety Model". De-scoped 2026-08: Annatto has NO concurrency
requirement.

## Context
Goat Rodeo's Annatto strategy was originally intended to be safe under concurrent use. Then
Phase 7 made `read(Path)` decompress the archive during BOTH the content-required routing scan
and package extraction; a 20-thread concurrent `read()` of the same gzip artifact intermittently
failed with `Corrupt GZIP trailer` (native `java.util.zip.Inflater` concurrency sensitivity).
Because concurrency is NOT a product requirement, the correct resolution is to retract the
concurrency contract rather than add synchronization.

## Decision
Annatto executes on a SINGLE thread:
- `LanguagePackageReader.read(...)` is called by ONE thread.
- The resulting `LanguagePackage` is read by that same ONE thread
  (`metadata()`, `streamEntries()`, `close()`, `releaseResources()`).
- Concurrent `read()` / `streamEntries()` / `close()` is OUT OF SCOPE and NOT guaranteed.
  The Phase 7/8 streaming, spooling, and decompression code is deliberately NOT synchronized
  for concurrent callers.

### What is still guaranteed (sequential contract)
- `LanguagePackage` instances are immutable after construction; repeated metadata reads are
  consistent (test: `LanguagePackageContractTest.repeatedReadsConsistentAfterConstruction`,
  `SingleThreadedModelTest.repeatedMetadataCallsConsistent`).
- Only ONE package stream may be open at a time; a second `streamEntries()` throws
  `IllegalStateException("A stream is already open on this package")`
  (test: `LanguagePackageContractTest.streamEntries_secondCallThrows`,
  `SingleThreadedModelTest.streamEntriesSingleOpenThenSequentialReuse`,
  `StreamingResourceManagementTest.concurrentStreamsOnSamePackageThrow`).
- Distinct package instances never share observable state
  (test: `SingleThreadedModelTest.noStateLeakageBetweenInstances`).
- A failed stream construction never permanently locks the package
  (test: `NpmMemorySafetyTest.constructorFailureResetsStreamFlag`).
- Static reader query methods are repeatable across sequential calls
  (test: `LanguagePackageReaderIntegrationTest.readerMethodsAreReentrant`,
  `SingleThreadedModelTest.readerQueryMethodsRepeatable`).

### Violation behavior (concurrent use = unsupported)
```java
// OUT OF SCOPE: Annatto is single-threaded. Calling read() from N threads, or reading a
// package from more than one thread, is unsupported and may fail (e.g. transient
// "Corrupt GZIP trailer" from concurrent native gzip inflation).
ExecutorService pool = ...; pool.invokeAll(reads); // NOT supported
```

### Consequences
- **Positive**: No locks, no sync primitives; decompression proceeds at full speed within the
  single worker thread that owns the read; the CI concurrency failure class is removed by
  contract rather than by hardening.
- **Negative**: Callers must not share a package (or concurrent reads) across threads.
- **Mitigation**: Documented here and on `LanguagePackage`/`LanguagePackageReader`;
  `PackageEntryStream` carries `@NotThreadSafe`; adders must not introduce synchronization.

## Amendment (Phase 7): the one sanctioned mutable-static
Annatto keeps ZERO mutable static state. The single exception is the process-wide aggregate
spool budget (`internal.Spool.budget()`, an `AggregateSpoolBudget`), which exists precisely to
bound cross-package temp-disk usage. It is an injectable process-global singleton (swap via
`Spool.overrideBudgetForTesting`) and is the only static mutation in the codebase. It does not
grant concurrency: under the single-threaded model exactly one worker drives spooling at a time.

## LLM Context
For LLM code generation: Annatto is SINGLE-THREADED. One thread calls
`LanguagePackageReader.read(...)` and reads the returned package. Do not assume thread safety;
do not add synchronization to make concurrent use "safe"; if a caller needs concurrency, they
must create one package (and one decompression chain) per worker thread.