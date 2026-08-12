# ADR-002: Double-Read Mitigation for Disambiguation

## Status
Proposed (Phase 0), Phase 7 UPDATE: implemented as bounded scans + spool-once-and-handoff.

## Context
MIME type detection via Tika reads file headers. However, disambiguating ambiguous types (e.g., `application/gzip` used by npm, PyPI, Crates, CPAN) requires reading the archive content. This creates a "double-read" problem: detecting the ecosystem, then parsing the package.

## Decision
Use mark/reset on BufferedInputStream with a 8KB buffer. For detection requiring more than 8KB, spool to a temporary file.

### Implementation
```java
BufferedInputStream bis = new BufferedInputStream(stream, 8192);
bis.mark(8192);
// Detection logic reads up to 8KB
bis.reset();
// Parser reads from beginning
```

### Phase 7 Update (Bug 1/Bug 2)
- `.tgz`/`.crate` names are AMBIGUOUS and go through BOUNDED CONTENT routing (compressed cap,
  inflated scan cap, entry-count cap; fail closed => `UnknownFormatException`). `EcosystemRouter`
  scans gzip paths WITHOUT a temp copy (`scanGzipTar`) and routes from an owned spool only when
  the caller provided a stream.
- `LanguagePackageReader.read(InputStream,...)` SPOOLS ONCE (bounded), routes from the spooled
  file, and HANDS the owned spooled file to the package factory (`close()` deletes it) - so the
  router temp and the package temp are the SAME file, the caller's stream is not drained, and
  there is no double-spool. `read(Path,...)` uses the bounded path scan + direct `fromPath`.

### Temp File Cleanup
- Use `java.lang.ref.Cleaner` for automatic cleanup
- Also `close()` on LanguagePackage deletes temp files (S-5); construction failures delete in `finally`
- Startup `privateDir()` sweep removes stale spools from dead JVMs
- Files created in a private 0700 dir in `java.io.tmpdir` with `annatto-` prefix

## Claims
- Detection completes within 1 second (test: MimeTypeFuzzTest.routerCompletesWithinTime)
- No resource leaks on exception paths (test: StreamingResourceManagementTest.streamEntriesClosesOnException)
- Stream reads never drain and break the caller (test: LanguagePackageReaderIntegrationTest.readStream_validSdistStillParses,
  readStream_genericTgzNotNpm)
- Ambiguous `.tgz`/`.crate` require content markers (test: TgzNotNpmRegressionTest.*)

## LLM Context
For LLM code generation: When implementing new ecosystems, ensure detection logic reads ≤8KB or explicitly handles temp file spooling. Always use mark/reset, never reopen files.
