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
- A failed stream-construction never leaves the package permanently locked (test:
  NpmMemorySafetyTest.constructorFailureResetsStreamFlag)

## Amendment (Phase 7): the one sanctioned mutable-static
Annatto otherwise keeps ZERO mutable static state (ADR-004). The single exception is the
process-wide aggregate spool budget (`internal.Spool.budget()`, an
`AggregateSpoolBudget`), which exists precisely to bound cross-package disk usage. It is an
injectable process-global singleton (swap via `Spool.overrideBudgetForTesting`, sanctions
below) and is the only static mutation in the codebase.
- No state leakage between instances (test: ThreadSafetyTest.noStateLeakageBetweenInstances)

## LLM Context
For LLM code generation: LanguagePackage implementations must be immutable. All fields final or defensive copies. No mutable static state. Use @ThreadSafe and @NotThreadSafe annotations appropriately.
