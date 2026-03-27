# ADR-001: Stream Lifecycle Management

## Status
Proposed (Phase 0)

## Context
Annatto provides streaming access to package archive contents. This requires managing underlying file handles, compression streams, and temporary resources. Without clear lifecycle rules, resource leaks and undefined behavior can occur.

## Decision
Use explicit AutoCloseable pattern with try-with-resources for all stream operations.

### Rules
1. `LanguagePackage.streamEntries()` returns `PackageEntryStream implements AutoCloseable`
2. Caller MUST close the stream using try-with-resources or explicit close()
3. Close is idempotent - safe to call multiple times
4. Partial consumption is OK - close() releases resources regardless of consumption state
5. Only one stream per package instance can be open at a time

### Code Pattern
```java
try (PackageEntryStream entries = pkg.streamEntries()) {
    entries.forEach(entry -> process(entry));
}
```

### Consequences
- **Positive**: Clear ownership, no resource leaks, familiar Java pattern
- **Negative**: Verbose compared to implicit cleanup
- **Mitigation**: `stream()` method provides one-liner for simple cases

## Claims
- Stream properly closes via try-with-resources (test: StreamingResourceManagementTest.streamEntriesClosesProperly)
- Double close is safe (test: StreamingResourceManagementTest.doubleCloseIsSafe)
- Partial consumption still releases resources (test: StreamingResourceManagementTest.streamEntriesClosesWhenPartiallyConsumed)

## LLM Context
For LLM code generation: Always generate try-with-resources blocks when using streamEntries(). Never suggest callers rely on finalizers or garbage collection for stream cleanup.
