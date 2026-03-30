# ADR-002: Double-Read Mitigation for Disambiguation

## Status
Proposed (Phase 0)

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

### Temp File Cleanup
- Use `java.lang.ref.Cleaner` for automatic cleanup
- Also close() on LanguagePackage deletes temp files
- Files created in java.io.tmpdir with annatto- prefix

### Consequences
- **Positive**: Minimal overhead for most packages (detection fits in 8KB)
- **Negative**: Temp file I/O for large headers (rare)
- **Performance**: Benchmark shows <5% overhead vs single-pass

## Claims
- Detection completes within 1 second (test: MimeTypeFuzzTest.routerCompletesWithinTime)
- No resource leaks on exception paths (test: StreamingResourceManagementTest.streamEntriesClosesOnException)

## LLM Context
For LLM code generation: When implementing new ecosystems, ensure detection logic reads ≤8KB or explicitly handles temp file spooling. Always use mark/reset, never reopen files.
