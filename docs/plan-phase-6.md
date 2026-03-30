# Phase 6 Plan: Contract Tests, Integration Tests, and Source-of-Truth Validation

## Overview

Phase 6 implements comprehensive testing for the LanguagePackage API layer to ensure behavioral contract compliance, integration correctness, and validation against native tool output. This complements (but does not duplicate) the existing Handler-based tests by focusing on LanguagePackage-specific invariants.

**Key Distinction from Handler Tests:**
- **Handler tests** verify Rodeo integration (mementos, metadata mapping, Rodeo-specific formatting)
- **Contract tests** verify LanguagePackage API invariants (immutability, non-null, stream lifecycle)
- **Source-of-truth tests** verify extraction accuracy against native tools

## Goals

1. **Contract Tests (Option A)**: Ensure LanguagePackage implementations satisfy the behavioral contract
2. **Integration Tests (Option B)**: Verify `LanguagePackageReader` routing and error handling
3. **Source-of-Truth Tests (Option C)**: Validate extraction matches native tool output

## Test Philosophy

Per project invariants:
- Tests designed BEFORE implementation (red-to-green)
- Each test documents WHAT, WHY, and LLM-friendly context
- Boundary conditions and guard rails explicitly tested
- Property-based tests validate invariants

---

## Review Feedback Incorporated

### From Staff QA Engineer
- Added concurrency stress tests requirement
- Added resource exhaustion tests (zip bombs, large files)
- Added explicit comparison rules for source-of-truth
- Reduced package count from 550 to 110 (10 per ecosystem) to reduce flakiness
- Removed tautological tests (testing test fixtures)

### From Principal Engineer
- Explicitly documented distinction from Handler tests
- Added pilot approach: validate with 3 ecosystems before full rollout
- Phase 6c expanded with realistic estimates for Docker scripts
- Added recommendation to generate expected JSON on-demand vs committing 110 files
- Fixed: Added `streamEntries_secondCallThrows()` to base contract class

### From Red Team Security Engineer
- Added Docker security hardening requirements (non-root user, read-only filesystem)
- Added package checksum verification requirement
- Added path traversal tests for all 11 ecosystems
- Added container escape prevention requirements
- Added security-focused tests to contract tests

---

## Part A: Contract Tests for All 11 Ecosystems

### A.1 Structure

```
src/test/java/io/spicelabs/annatto/ecosystem/
├── npm/NpmPackageContractTest.java
├── pypi/PyPIPackageContractTest.java
├── crates/CratesPackageContractTest.java
├── go/GoPackageContractTest.java
├── rubygems/RubygemsPackageContractTest.java
├── packagist/PackagistPackageContractTest.java
├── conda/CondaPackageContractTest.java
├── cocoapods/CocoapodsPackageContractTest.java
├── cpan/CpanPackageContractTest.java
├── hex/HexPackageContractTest.java
└── luarocks/LuarocksPackageContractTest.java
```

### A.2 Base Contract Test Fixes Required

Before creating ecosystem implementations, fix `LanguagePackageContractTest`:

**Add Missing Tests:**
- `streamEntries_secondCallThrows()` - calling streamEntries() twice throws IllegalStateException
- `streamEntries_afterCloseAllowsNewStream()` - after close(), new stream can be opened
- `concurrentStreamAccessThrows()` - two threads opening streams concurrently throws

**Fix Vague Assertions:**
- Change `toPurlReturnsValidPurl()` to verify exact PURL string against known-good value
- Add PURL regex validation per purl-spec

### A.3 Required Test Artifacts

Each contract test requires:
1. **Valid package** - real package file from test-corpus that parses successfully
2. **Corrupted package** - real package with truncated/removed metadata file (produces incomplete metadata)
3. **Expected ecosystem** - the Ecosystem enum value

**Test Data Sources:**
- Valid packages: From test-corpus/ (assumption-based skipping if not present)
- Corrupted packages: Created by truncating real packages (e.g., remove package.json from npm tarball)

### A.4 Contract Test Requirements

#### A.4.1 Immutability Contract

**Tests:**
- `immutableAfterConstruction()` - repeated calls return equal results (structural equality)
- `metadataDependenciesImmutable()` - unmodifiable view, throws on modification attempt
- `metadataRawMapImmutable()` - unmodifiable view, throws on modification attempt

**Why:** Thread safety depends on immutability.

#### A.4.2 Non-Null Returns

**Tests:**
- `mimeTypeNonNull()` - always returns MIME type string
- `ecosystemNonNull()` - always returns Ecosystem
- `nameNeverNull()` - empty string if unknown, never null
- `versionNeverNull()` - empty string if unknown, never null
- `metadataNeverNull()` - always returns PackageMetadata

**Why:** Null returns lead to NPEs; contract mandates Optional for optional values.

#### A.4.3 PURL Generation Contract

**Tests:**
- `toPurlReturnsValidPurl()` - valid package produces exact PURL matching expected string
- `toPurlReturnsEmptyWhenIncomplete()` - corrupted package returns empty Optional

**PURL Validation:**
- Use purl-spec regex: `^pkg:[a-zA-Z][a-zA-Z0-9._-]*/.*`
- Verify namespace handling for scoped packages (npm @scope/name)

#### A.4.4 Stream Lifecycle Contract

**Tests:**
- `streamEntriesClosesProperly()` - try-with-resources closes without exception
- `streamEntries_secondCallThrows()` - calling twice throws IllegalStateException
- `streamEntries_afterCloseAllowsNewStream()` - after close(), can open new stream
- `concurrentStreamAccessThrows()` - two threads calling concurrently throws

**Why:** Resource leaks and concurrent access could corrupt state.

#### A.4.5 Security Contract (New Section)

**Tests:**
- `pathTraversalRejected()` - entry names with `..` rejected or sanitized
- `absolutePathRejected()` - entry names starting with `/` rejected
- `zipBombRejected()` - compression ratio > 100:1 rejected
- `entryCountLimitEnforced()` - >10,000 entries rejected
- `entrySizeLimitEnforced()` - >10MB entries rejected

**Why:** Malicious packages must not compromise system.

### A.5 Ecosystem-Specific Edge Cases

In addition to inherited contract tests, each ecosystem tests format-specific behaviors:

| Ecosystem | Additional Tests | Test Data |
|-----------|------------------|-----------|
| npm | `parsesScopedPackage()`, `purlNamespaceContainsScope()` | @babel/core package |
| pypi | `parsesWheelFormat()`, `parsesSdistFormat()`, `normalizedNameInPurl()` | requests wheel + sdist |
| crates | `parsesCargoTomlMetadata()` | serde crate |
| go | `extractsVersionFromPath()`, `parsesGoMod()` | module@version.zip |
| rubygems | `handlesMetadataGz()`, `stripsRubyTags()` | rails gem |
| packagist | `filtersPlatformDependencies()` | package with ext-* deps |
| conda | `parsesV1Format()`, `parsesV2Format()`, `includesBuildAndSubdir()` | both formats |
| cocoapods | `parsesPodspecJson()` | Alamofire.podspec.json |
| cpan | `parsesMetaJsonV2()`, `parsesMetaYmlV1()`, `handlesDoubleColonNames()` | both formats |
| hex | `parsesErlangConfig()` | phoenix tar |
| luarocks | `parsesRockspec()`, `extractsFromRockArchive()`, `handlesVersionRevision()` | both formats |

---

## Part B: LanguagePackageReader Integration Tests

### B.1 Test Class Structure

```
src/test/java/io/spicelabs/annatto/
└── LanguagePackageReaderIntegrationTest.java
```

### B.2 Requirements

#### B.2.1 Path-Based Routing

**Theory:** `read(Path)` auto-detects ecosystem from file content.

**Tests (one per ecosystem):**
- `readPath_autoDetectsNpm()`, `readPath_autoDetectsPyPI()`, etc. for all 11

**Verification:** Assert returned instance type (e.g., `assertThat(pkg).isInstanceOf(NpmPackage.class)`)

#### B.2.2 MIME Type-Based Routing with Disambiguation

**Theory:** `read(Path, String)` uses Tika MIME type with content disambiguation.

**Tests:**
- `readPathWithMimeType_usesProvidedMimeType()` - uses param, not detection
- `readPathWithMimeType_disambiguatesGzipToPyPI()` - lowercase .tar.gz -> PyPI
- `readPathWithMimeType_disambiguatesGzipToCPAN()` - uppercase .tar.gz -> CPAN
- `readPathWithMimeType_disambiguatesZipToConda()` - .conda -> Conda
- `readPathWithMimeType_disambiguatesZipToPackagist()` - composer.json -> Packagist

#### B.2.3 Stream-Based Routing

**Tests:**
- `readStream_withFilenameHint()` - extension guides detection
- `readStream_withoutFilenameHint()` - content inspection used
- `readStream_preservesStreamPosition()` - mark/reset, position unchanged after

**Implementation Note:** For `preservesStreamPosition`, assert `stream.available()` or mark position before/after.

#### B.2.4 Error Handling

**Tests:**
- `readPath_unsupportedMimeTypeThrowsUnknownFormatException()`
- `readPath_cannotDetermineEcosystemThrowsUnknownFormatException()`
- `readPath_malformedPackageThrowsMalformedPackageException()`
- `readPath_nonExistentFileThrowsIOException()`

#### B.2.5 Supported MIME Type Queries

**Tests:**
- `isSupported_returnsTrueForSupportedTypes()` - parameterized with all 7 supported types
- `isSupported_returnsFalseForUnsupportedTypes()` - parameterized with common types (text/plain, image/png)
- `isSupported_returnsFalseForNull()`
- `supportedMimeTypes_returnsNonEmptySet()`
- `supportedMimeTypes_returnsImmutableSet()`

#### B.2.6 Thread Safety

**New Tests (from QA review):**
- `concurrentReadCallsDoNotInterfere()` - multiple threads reading different packages
- `readerMethodsAreReentrant()` - same method called multiple times safely

**Implementation:** Use ExecutorService with CountDownLatch to trigger concurrent access.

---

## Part C: Docker-Based Source-of-Truth Testing

### C.1 Pilot Approach

**Per principal engineer review:** Start with 3 ecosystems, 10 packages each. Validate value before full rollout.

**Pilot Ecosystems:**
1. npm - well-understood, existing docker infrastructure
2. pypi - different format (wheel), validates diversity
3. crates - TOML format, validates non-JSON parsing

**Success Criteria for Pilot:**
- Find at least 1 bug that Handler tests missed, OR
- Validate extraction accuracy matches native tool within tolerance

### C.2 Revised Scope

**Full Rollout (after pilot success):**
- 11 ecosystems × 10 packages = 110 packages (not 550)
- Reassess if 110 provides sufficient coverage

### C.3 Docker Infrastructure

#### C.3.1 Security Hardening Requirements (New)

**All Dockerfiles must:**
```dockerfile
# Add non-root user
RUN adduser --disabled-password --gecos "" appuser
USER appuser
WORKDIR /work

# Use specific digest, not just tag
FROM node:21-slim@sha256:abc123...
```

**Container Runtime:**
```bash
docker run --rm \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --user "$(id -u):$(id -g)" \
  -v "$(pwd)/test-corpus:/work/out:rw" \
  annatto/npm extract.sh package.tgz
```

#### C.3.2 Package Verification

**Create `test-corpus/packages.sha256`:**
```
npm/lodash-4.17.21.tgz sha256:a1b2c3...
npm/express-4.18.2.tgz sha256:d4e5f6...
...
```

**Download scripts verify before extraction:**
```bash
# In each download.sh
echo "$expected_sha256  $file" | sha256sum -c - || exit 1
```

#### C.3.3 Missing Docker Scripts

**Priority Order (with time estimates):**

1. **rubygems/extract.sh** (6 hours)
   - Untar .gem (plain tar, not gzip)
   - gunzip metadata.gz
   - Strip Ruby tags (!ruby/object:Gem::*)
   - Parse YAML, output JSON

2. **cocoapods/extract.sh** (4 hours)
   - Parse JSON podspec (no extraction needed)
   - Handle sharded path calculation for downloads

3. **conda/extract.sh** (8 hours)
   - For .conda: unzip, find info-*.tar.zst, unzstd, untar
   - For .tar.bz2: bunzip2, untar
   - Read info/index.json
   - Requires zstd binary

4. **hex/extract.sh** (4 hours)
   - Wrap existing extract.exs or implement in shell
   - Parse metadata.config Erlang format

### C.4 Source-of-Truth Comparison

#### C.4.1 Comparison Rules (Explicit)

| Field | Comparison | Tolerance |
|-------|------------|-----------|
| name | Exact string equality | None |
| version | Exact string equality | None |
| description | String equality | null == empty string == "UNKNOWN" |
| license | Case-insensitive equality | "MIT" == "MIT License" (document normalization) |
| publisher | String equality | null == empty string |
| dependencies | Set equality (order independent) | Java may filter platform deps (documented per ecosystem) |

#### C.4.2 Test Design

**Class:** `SourceOfTruthIntegrationTest` (parameterized)

```java
@ParameterizedTest(name = "{0}/{1}")
@MethodSource("pilotPackages") // Start with pilot, expand after
void extractionMatchesNativeTool(String ecosystem, String packageFile) {
    // Load expected JSON from build/source-of-truth/ or on-demand generate
    // Parse actual package using LanguagePackageReader
    // Compare per field rules above
}
```

**On-Demand Generation:**
```java
Path expectedPath = Paths.get("build/source-of-truth", ecosystem, packageFile + ".json");
if (!Files.exists(expectedPath)) {
    // Run docker extract, save to expectedPath
    DockerSourceOfTruth.generate(ecosystem, packageFile, expectedPath);
}
```

**Advantage:** No 110 JSON files in git; regeneration is automatic; cache in CI.

#### C.4.3 CI Integration

**Separate GitHub Actions Job:**
```yaml
source-of-truth:
  runs-on: ubuntu-latest
  timeout-minutes: 20  # Reduced from 30 for 110 packages
  steps:
    - uses: actions/checkout@v4
    - name: Cache source-of-truth
      uses: actions/cache@v4
      with:
        path: build/source-of-truth
        key: sot-${{ hashFiles('docker/*/extract.sh', 'test-corpus/packages.sha256') }}
    - name: Run source-of-truth tests
      run: mvn test -Dtest=SourceOfTruthIntegrationTest -DsourceOfTruth.enabled=true
```

**Profile-based skipping:**
```java
@Test
void extractionMatchesNativeTool() {
    assumeThat(System.getProperty("sourceOfTruth.enabled"))
        .as("Source-of-truth tests require -DsourceOfTruth.enabled=true")
        .isEqualTo("true");
    // ... test
}
```

---

## Implementation Phases

### Phase 6a: Contract Test Foundation
**Deliverables:**
1. Fix `LanguagePackageContractTest` base class (add missing tests, fix assertions)
2. Create `NpmPackageContractTest` (reference implementation)
3. Create `PyPIPackageContractTest` and `CratesPackageContractTest`
4. Add security contract tests to base class
5. Run adversarial review and claims verification

**Tests Added:** ~40 (3 ecosystems × ~12 contract tests + security)

### Phase 6b: LanguagePackageReader Integration Tests
**Deliverables:**
1. Create `LanguagePackageReaderIntegrationTest`
2. Implement routing tests for all 11 ecosystems
3. Implement error handling tests
4. Implement MIME type query tests
5. Implement thread safety tests
6. Run adversarial review and claims verification

**Tests Added:** ~35

### Phase 6c: Docker Infrastructure (Pilot)
**Deliverables:**
1. Harden existing Dockerfiles (security requirements)
2. Create `docker/rubygems/extract.sh`
3. Create `docker/cocoapods/extract.sh`
4. Create `docker/conda/extract.sh`
5. Create `docker/hex/extract.sh`
6. Create `test-corpus/packages.sha256` with 30 entries (10 per pilot ecosystem)
7. Create `SourceOfTruthIntegrationTest` with pilot scope
8. Run adversarial review and claims verification

**Tests Added:** ~30 (3 ecosystems × 10 packages)

### Phase 6d: Pilot Evaluation
**Decision Gate:**
- Review: Did source-of-truth tests find bugs Handler tests missed?
- If YES: Proceed to Phase 6e
- If NO: Document decision to limit source-of-truth scope

### Phase 6e: Contract Tests - Remaining 8 Ecosystems
**Deliverables:**
1. Create contract tests for go, rubygems, packagist, conda, cocoapods, cpan, hex, luarocks
2. Run adversarial review and claims verification

**Tests Added:** ~96 (8 ecosystems × 12 tests)

### Phase 6f: Documentation and CI
**Deliverables:**
1. Update ARCHITECTURE.md with LanguagePackage testing documentation
2. Create ARCHITECTURE_llm.md with LLM-friendly summary
3. Create ADR-006 documenting source-of-truth testing approach
4. Update GitHub Actions workflow
5. Final full test run verification
6. Phase 6 exit review per HS-2

---

## New Artifacts

### Test Classes (15 files)
- 11 × PackageContractTest.java (created in 6a and 6e)
- 1 × LanguagePackageReaderIntegrationTest.java (6b)
- 1 × SourceOfTruthIntegrationTest.java (6c)
- 2 × utility classes (DockerSourceOfTruth, ChecksumVerifier)

### Docker Scripts (4 files)
- docker/rubygems/extract.sh
- docker/cocoapods/extract.sh
- docker/conda/extract.sh
- docker/hex/extract.sh

### Configuration (2 files)
- test-corpus/packages.sha256 (manifest)
- .github/workflows/source-of-truth.yml (CI job)

### Documentation (3 files)
- docs/adr/006-source-of-truth-testing.md
- ARCHITECTURE.md updates
- ARCHITECTURE_llm.md

---

## Dependencies

- Existing test-corpus structure (test-corpus/<ecosystem>/)
- Existing docker/ structure with hardening
- Fixed LanguagePackageContractTest base class
- Checksum manifest for package verification

## Risk Assessment (Revised)

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Docker image build failures | Medium | High | Pin SHA digests, retry logic |
| Package download failures | High | Medium | 10-retry logic, checksum verify, skip unavailable |
| License/description normalization | High | Low | Explicit comparison rules per field |
| Test corpus size | Low | Low | 110 packages (reduced), CI cache |
| Flaky network in CI | Medium | Medium | Cache, profile-based skipping |
| Container escape | Low | High | Non-root user, read-only fs, cap-drop |
| Malicious test package | Low | High | Checksum verification, Docker isolation |

## Success Criteria

1. **Contract Tests:** All 11 ecosystems have passing contract tests (~132 tests)
2. **Integration Tests:** LanguagePackageReader has 35+ integration tests
3. **Source-of-Truth:** 30 comparisons pass for pilot (expand to 110 if pilot succeeds)
4. **Total Test Count:** 291 existing + 200 new = 491+ tests
5. **Security:** All Docker containers run non-root, with checksum verification
6. **Documentation:** All claims reference specific test names

## Review Checklist (Per HS-2)

Before declaring each sub-phase complete:
- [ ] Gap Review: All items implemented or explicitly flagged
- [ ] Claims Verification: Every documentation claim has passing test
- [ ] Hostile Reviewer: Tests would pass skeptical review
- [ ] Security Review: Docker hardening, checksums verified
- [ ] Full Suite: `mvn test` passes with expected test count

---

*Plan Version: 2.0 (incorporating QA, Principal Engineer, and Red Team reviews)*
*Date: 2026-03-26*
