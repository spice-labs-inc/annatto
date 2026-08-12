# ADR-005: Security Model

## Status
Proposed (Phase 0)

## Context
Annatto processes untrusted packages from public registries. Malicious packages could attempt resource exhaustion, path traversal, or code execution.

## Decision
Defense in depth with multiple layers:

### Layer 1: Input Limits (Phase 7 defaults; see `internal/Limits`)
| Limit | Default | Enforced at |
|-------|---------|-------------|
| `FROMSTREAM spool (compressed)` | 1 GiB (per package) | `Spool.create` (admission-time reserve) |
| Aggregate in-flight spool budget | ~4 GiB (per process) | `AggregateSpoolBudget` |
| Metadata scan (decompressed) | 500 MiB | gzip/zstd/bzip2 layer via `BoundedInflateStream` |
| Routing scan compressed / inflated / entries | 256 MiB / 16 MiB / 1000 | `EcosystemRouter` (fail closed) |
| Per `streamEntries()` pass (inflated) | 1 GiB gzip-family; 1 GiB ZIP | package entry streams (fail-fast) |
| Per-entry content | 10 MiB | `openStream()` (buffered, then returned) |
| Per-entry metadata file | 1 MiB | PyPI / Crates / CPAN metadata read |
| Entries per archive (enumeration AND metadata scans) | 10 000 | `hasNext`/scan loops |

### Layer 2: Path Validation
- Reject entries containing `..` (path traversal; backslash-normalized first, Phase 7)
- Reject entries with null bytes, CR/LF/DEL control characters (log-injection guard)
- Reject absolute paths
- Reject symlink targets that escape the archive at `openStream()` time; hard-link targets surfaced
- Normalize backslashes before traversal checks

### Layer 3: Safe Defaults
- Fail closed (routing budgets and unknown markers -> `UnknownFormatException`; scan/pass budget
  trips -> `SecurityException`)
- Packages never buffer the whole archive; `fromStream` bounded spools to a private 0700 dir with
  0600 files, Cleaner-backed cleanup, a startup stale-spool sweep, and per-package + aggregate quotas
- Spool temp files NEVER appear in error messages (basename-only display names)

### Exception Sanitization
Error messages must NOT contain:
- Internal file paths (/tmp, /home, spool paths, etc.) - filename display is basename-only
- Raw `cause.getMessage()` from I/O (used as static text + exception type)
- Stack traces (logged, not returned)

```java
// BAD: Leaks internal path
throw new SecurityException("/tmp/.annatto-private-123/annatto-spool-4.pkg exceeds limit");

// GOOD: Sanitized message (basename only)
throw new SecurityException("Decompressed data exceeds size limit: my-package.tgz (max 1048576 bytes)");
```

### Consequences
- **Positive**: Robust against known attack classes; incident class (OOM on >2 GiB archives) fixed
- **Negative**: Legit-but-oversized packages fail closed (marker-after-cap / spool-cap)
- **Mitigation**: limits are instance-scoped (`Limits` records) / configurable

## Claims
- ZIP bomb detected (test: SecurityLimitsTest.zipBombDetectedAndRejected)
- Path traversal rejected (test: SecurityLimitsTest.pathTraversalRejected)
- Exception messages sanitized (test: SecurityLimitsTest.securityExceptionSanitizesMessage,
  SecurityHardeningTest.spoolScanMessagesDoNotLeakPaths)
- Decompressed budgets enforced at the gzip/zstd/bzip2 layers (test:
  NpmMemorySafetyTest.metadataExtractionScansBounded, EcosystemDecompressionBombTest.*)
- Per-pass stream budgets fail fast (test: NpmMemorySafetyTest.enumerationDecompressionBudgetEnforced,
  openStreamMidEntryBudgetTripHasNextSemantics)
- Aggregate + per-package spool quotas (test: NpmMemorySafetyTest.aggregateBudgetAdjoint,
  fromStreamNeverBuffersWholePackage)
- CR/LF and backslash-traversal entry names rejected (test:
  SecurityHardeningTest.entryNameCarriageReturnRejected, backslashTraversalRejected)

## LLM Context
For LLM code generation: All input from packages is untrusted. Always validate sizes before allocation. Always normalize paths. Never include user input in exception messages without sanitization.
