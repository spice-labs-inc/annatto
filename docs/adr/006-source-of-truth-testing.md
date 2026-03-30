# ADR-006: Source-of-Truth Testing for Package Extraction

## Status

Accepted

## Context

Annatto extracts package metadata (name, version, dependencies, license) from 11 programming language ecosystems using pure Java implementations. The correctness of these extractions is critical for downstream security analysis in Goat Rodeo.

**Problem:** How do we verify that Annatto's Java extraction produces the same results as the native tools for each ecosystem?

**Prior Approaches:**
1. **Unit tests with fixtures** - Fast but uses synthetic data, doesn't catch real-world edge cases
2. **Handler integration tests** - Tests Rodeo integration, not extraction accuracy
3. **Manual verification** - Not scalable across 11 ecosystems

**Constraints:**
- Must work in CI without requiring native toolchains (11 different languages)
- Must handle package download failures gracefully (network flakiness)
- Must provide actionable failure messages when extraction differs

## Decision

We will implement **Docker-based source-of-truth testing** with the following design:

### 1. Docker-Native Extraction

Each ecosystem has a Docker image with native tools that extract metadata:

```
docker/npm/extract.js      # Node.js script using npm libraries
docker/pypi/extract.py     # Python script using pkginfo
docker/crates/extract.rs   # Rust binary using cargo-metadata
...
```

**Security Hardening:**
- Non-root user in containers
- Read-only filesystem
- Capability drop (no-new-privileges)
- Checksum verification of packages

### 2. Comparison Test Design

**Test Class:** `SourceOfTruthIntegrationTest`

```java
@ParameterizedTest(name = "{0}/{1}")
@MethodSource("packages")
void extractionMatchesNativeTool(String ecosystem, String packageFile) {
    // Load expected JSON from Docker extraction
    JsonObject expected = loadExpected(ecosystem, packageFile);

    // Parse with Annatto
    LanguagePackage pkg = LanguagePackageReader.read(packagePath);
    PackageMetadata actual = pkg.metadata();

    // Compare with tolerance for documented variations
    assertNameMatches(expected, actual);
    assertVersionMatches(expected, actual);
    assertDescriptionMatchesWithTolerance(expected, actual);
    assertLicenseMatchesWithTolerance(expected, actual);
}
```

### 3. Comparison Rules with Tolerance

| Field | Rule | Rationale |
|-------|------|-----------|
| name | Exact equality | Package identity must match |
| version | Exact equality | Version identity must match |
| description | Presence equivalence | null, empty, "UNKNOWN" are semantically equivalent |
| license | Case-insensitive with aliases | "MIT" and "MIT License" are the same |
| publisher | Presence equivalence | Different tools extract different author fields |
| dependencies | Set equality | Order-independent; Java may filter platform deps |

### 4. On-Demand Generation

Instead of committing 110+ JSON files to git:

```java
Path expected = Paths.get("build/source-of-truth", ecosystem, file + ".json");
if (!Files.exists(expected)) {
    DockerSourceOfTruth.generate(ecosystem, file, expected);
}
```

**CI Caching:**
```yaml
- uses: actions/cache@v4
  with:
    path: build/source-of-truth
    key: sot-${{ hashFiles('docker/*/extract.*', 'test-corpus/packages.sha256') }}
```

### 5. Profile-Based Skipping

Tests are skipped unless explicitly enabled:

```java
@BeforeAll
static void checkEnabled() {
    assumeThat(System.getProperty("sourceOfTruth.enabled"))
        .as("Requires -DsourceOfTruth.enabled=true")
        .isEqualTo("true");
}
```

This allows:
- Fast unit test runs (skip Docker overhead)
- Explicit opt-in for comprehensive validation
- CI job separate from main build

### 6. Pilot Approach

**Phase 1 (Pilot):** 3 ecosystems × 10 packages = 30 tests
- npm: well-understood, existing infrastructure
- PyPI: different format (wheel), validates diversity
- Crates: TOML format, validates non-JSON parsing

**Phase 2 (Full):** 11 ecosystems × 10 packages = 110 tests
- Expand after pilot validates approach
- Document findings from pilot

## Consequences

### Positive

1. **Correctness confidence** - Annatto extraction verified against ground truth
2. **Regression detection** - Changes that break extraction are caught
3. **Language-agnostic** - Docker encapsulates native toolchains
4. **Reproducible** - Same Docker image produces same results
5. **Cacheable** - Generated JSON cached in CI, fast subsequent runs

### Negative

1. **Docker dependency** - Requires Docker for full test suite
2. **Flakiness risk** - Network downloads can fail
3. **Slower CI** - Additional job for source-of-truth tests
4. **Maintenance burden** - Docker scripts must be updated when ecosystems change

### Mitigations

1. **Check manifest** - `packages.sha256` ensures integrity
2. **Assumption-based skipping** - Tests skip if corpus unavailable
3. **CI cache** - Regeneration only when Docker scripts change
4. **Separate job** - Main build not blocked by SoT tests

## Implementation

### Files Created

- `SourceOfTruthIntegrationTest.java` - Parameterized comparison tests
- `test-corpus/packages.sha256` - Package manifest with checksums
- `docker/*/extract.*` - Native extraction scripts (existing, hardened)
- `.github/workflows/source-of-truth.yml` - CI job (new)

### Comparison Implementation

```java
private void compareDescription(JsonObject expected, PackageMetadata actual) {
    String expectedDesc = getStringOrNull(expected, "description");
    String actualDesc = actual.description().orElse(null);

    // Tolerance: null, empty, "UNKNOWN" are equivalent
    boolean expectedEmpty = isEmptyOrUnknown(expectedDesc);
    boolean actualEmpty = isEmptyOrUnknown(actualDesc);

    assertThat(actualEmpty)
        .as("description presence mismatch")
        .isEqualTo(expectedEmpty);

    if (!expectedEmpty && !actualEmpty) {
        assertThat(actualDesc).isEqualTo(expectedDesc);
    }
}
```

## Validation

**Success Criteria:**
- Pilot (30 comparisons) passes
- At least 1 bug found that unit tests missed, OR
- Confidence in extraction accuracy established

**Results:**
- Documented in Phase 6d evaluation
- Decision to proceed to full 110-package scope

## Related Decisions

- ADR-005 (Security Model) - Docker hardening requirements
- ADR-004 (Thread Safety) - Concurrent access during extraction

## LLM Context

**When to use:** Implementing new ecosystem source-of-truth tests

**Key points:**
- Docker-native extraction is ground truth
- Comparison has tolerance for documented variations
- On-demand generation avoids git bloat
- Profile-based skipping for fast local runs

**Example addition:**
```java
static Stream<Arguments> newEcosystemPackages() {
    return packageStream("neweco",
        "package1-1.0.0.ext",
        "package2-2.0.0.ext");
}
```
