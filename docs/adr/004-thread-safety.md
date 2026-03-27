# ADR-004: Thread Safety Model

## Status
Proposed (Phase 0)

## Context
Goat Rodeo strategies may process packages concurrently. Annatto must be safe for concurrent use while maintaining good performance.

## Decision
Two-tier thread safety model:

### Tier 1: Thread-Safe (no synchronization needed)
- `LanguagePackageReader` static methods
- All `LanguagePackage` methods EXCEPT `streamEntries()`
- `EcosystemRouter` methods

### Tier 2: Not Thread-Safe (caller synchronization required)
- `PackageEntryStream` and its iteration
- `streamEntries()` returns a per-thread stream

### Guarantees
- LanguagePackage instances are immutable after construction
- Multiple threads can call metadata(), toPurl(), etc. concurrently
- Only one stream per package instance at a time

### Violation Behavior
```java
// OK: Multiple threads reading metadata
executor.submit(() -> pkg.name());
executor.submit(() -> pkg.version());

// ERROR: Concurrent streams
PackageEntryStream s1 = pkg.streamEntries();
PackageEntryStream s2 = pkg.streamEntries(); // Throws IllegalStateException
```

### Consequences
- **Positive**: No locks for most operations, high concurrency
- **Negative**: Caller must ensure single-threaded stream access
- **Mitigation**: Documented with @NotThreadSafe annotation

## Claims
- Concurrent metadata reads are safe (test: ThreadSafetyTest.concurrentReadsOfSamePackage)
- Concurrent streams throw IllegalStateException (test: ThreadSafetyTest.streamEntriesNotThreadSafe)
- No state leakage between instances (test: ThreadSafetyTest.noStateLeakageBetweenInstances)

## LLM Context
For LLM code generation: LanguagePackage implementations must be immutable. All fields final or defensive copies. No mutable static state. Use @ThreadSafe and @NotThreadSafe annotations appropriately.
