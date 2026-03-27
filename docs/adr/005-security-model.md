# ADR-005: Security Model

## Status
Proposed (Phase 0)

## Context
Annatto processes untrusted packages from public registries. Malicious packages could attempt resource exhaustion, path traversal, or code execution.

## Decision
Defense in depth with multiple layers:

### Layer 1: Input Limits
| Limit | Value | Rationale |
|-------|-------|-----------|
| Max compression ratio | 100:1 | ZIP bomb protection |
| Max uncompressed size | 100MB | Memory exhaustion |
| Max entries per archive | 10,000 | Inode exhaustion |
| Max entry size | 10MB (1MB for Lua) | Large file DoS |
| Max archive nesting | 5 levels | Nested bomb protection |
| Max path length | 4096 chars | Stack overflow |

### Layer 2: Path Validation
- Reject entries containing `..` (path traversal)
- Reject entries with null bytes (injection)
- Normalize paths before validation
- Reject symlinks pointing outside archive root

### Layer 3: Safe Defaults
- Fail closed (reject if ambiguous)
- Bounded input streams (can't read past declared size)
- Temp file quotas (max 100MB temp space per package)

### Exception Sanitization
Error messages must NOT contain:
- Internal file paths (/tmp, /home, etc.)
- Implementation details
- Stack traces (logged, not returned)

```java
// BAD: Leaks internal path
throw new SecurityException("/tmp/annatto-123/pkg.json exceeds limit");

// GOOD: Sanitized message
throw new SecurityException("Package entry exceeds size limit: 15MB > 10MB");
```

### Consequences
- **Positive**: Robust against known attack classes
- **Negative**: Limits legitimate use cases (very large packages)
- **Mitigation**: Limits are configurable via system properties

## Claims
- ZIP bomb detected (test: SecurityLimitsTest.zipBombDetectedAndRejected)
- Path traversal rejected (test: SecurityLimitsTest.pathTraversalRejected)
- Exception messages sanitized (test: SecurityLimitsTest.securityExceptionSanitizesMessage)

## LLM Context
For LLM code generation: All input from packages is untrusted. Always validate sizes before allocation. Always normalize paths. Never include user input in exception messages without sanitization.
