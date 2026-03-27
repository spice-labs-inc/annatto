# Annatto Architecture (LLM-Friendly)

## Quick Facts

| Attribute | Value |
|-----------|-------|
| **Purpose** | Goat Rodeo plugin for extracting package metadata from 11 ecosystems |
| **Language** | Java 21 |
| **Plugin System** | Java ServiceLoader (rodeo-components) |
| **Test Count** | 490+ (291 existing + ~200 Phase 6) |
| **Ecosystems** | npm, PyPI, Go, Crates, RubyGems, Packagist, Conda, CocoaPods, CPAN, Hex, LuaRocks |

## Two APIs

```
┌─────────────────────────────────────────────────────────────┐
│                    Goat Rodeo Plugin                        │
│              (RodeoComponent interface)                     │
│                    AnnattoComponent                         │
│                         │                                   │
│    ┌────────────────────┼────────────────────┐             │
│    │                    │                    │             │
│    ▼                    ▼                    ▼             │
│ Handler Layer    LanguagePackage API    Extractor Layer    │
│ (Rodeo-specific)  (Direct access)       (Static utilities) │
│                         │                                   │
│                         ▼                                   │
│              11 Ecosystem Implementations                   │
│         (NpmPackage, PyPIPackage, etc.)                     │
└─────────────────────────────────────────────────────────────┘
```

## LanguagePackage API (New in Phase 6)

**Key Classes:**
- `LanguagePackage` - Core interface implemented by all 11 ecosystems
- `PackageMetadata` - Immutable metadata record
- `PackageEntryStream` - Memory-efficient archive streaming
- `LanguagePackageReader` - Auto-detection and routing

**Critical Invariants:**
1. Immutability - All records use `List.copyOf()`, `Map.copyOf()`
2. Non-null - `name()` and `version()` never return null (empty string if unknown)
3. Optional - `description()`, `license()`, `publisher()` return `Optional`
4. Single stream - Only one `PackageEntryStream` open at a time per package
5. Thread-safe - Concurrent metadata reads OK; stream access serialized

**Security Limits (all implementations):**
- MAX_ENTRIES = 10,000
- MAX_ENTRY_SIZE = 10 MB
- Path traversal rejected (entries with `..` or `/` prefix)

## Contract Test Pattern

```java
// Base class defines contract tests
abstract class LanguagePackageContractTest {
    protected abstract LanguagePackage createValidPackage();
    protected abstract Ecosystem expectedEcosystem();
    protected abstract String expectedValidPurl();

    @Test void nameNeverNull() { ... }
    @Test void toPurlReturnsValidPurl() { ... }
    @Test void streamEntries_secondCallThrows() { ... }
    // ... 15+ contract tests
}

// Each ecosystem extends and provides test data
class NpmPackageContractTest extends LanguagePackageContractTest {
    protected LanguagePackage createValidPackage() {
        return NpmPackage.fromPath(TEST_CORPUS.resolve("lodash-4.17.21.tgz"));
    }
    // ... plus ecosystem-specific tests
}
```

**11 Ecosystem Contract Tests:**
All extend `LanguagePackageContractTest`:
- `NpmPackageContractTest`
- `PyPIPackageContractTest`
- `CratesPackageContractTest`
- `GoPackageContractTest`
- `RubygemsPackageContractTest`
- `PackagistPackageContractTest`
- `CondaPackageContractTest`
- `CocoapodsPackageContractTest`
- `CpanPackageContractTest`
- `HexPackageContractTest`
- `LuarocksPackageContractTest`

## Testing Hierarchy

```
Tests/
├── Contract Tests (11 files)
│   ├── Inherited: immutability, non-null, PURL, stream lifecycle
│   └── Ecosystem-specific: format parsing, edge cases
├── Integration Tests (1 file)
│   ├── LanguagePackageReaderIntegrationTest
│   └── Routing, disambiguation, error handling, thread safety
└── Source-of-Truth Tests (1 file)
    ├── SourceOfTruthIntegrationTest
    └── Compares against Docker-native extraction
```

## Source-of-Truth Testing

**Docker Hardening:**
- Non-root user (`--user appuser`)
- Read-only filesystem (`--read-only`)
- No new privileges (`--security-opt no-new-privileges`)
- Capability drop (`--cap-drop ALL`)

**Comparison Rules (with tolerance):**
| Field | Rule | Tolerance |
|-------|------|-----------|
| name | exact | none |
| version | exact | none |
| description | presence | null == empty == "UNKNOWN" |
| license | case-insensitive | aliases normalized |
| deps | set equality | platform deps may be filtered |

## PURL Formats

| Ecosystem | PURL Pattern |
|-----------|--------------|
| npm | `pkg:npm/[@scope/]name@version` |
| PyPI | `pkg:pypi/normalized-name@version` |
| Go | `pkg:golang/namespace/name@version` |
| Crates | `pkg:cargo/name@version` |
| RubyGems | `pkg:gem/name@version` |
| Packagist | `pkg:composer/vendor/name@version` |
| Conda | `pkg:conda/name@version?build=...&subdir=...` |
| CocoaPods | `pkg:cocoapods/Name@version` |
| CPAN | `pkg:cpan/Namespace/Name@version` |
| Hex | `pkg:hex/name@version` |
| LuaRocks | `pkg:luarocks/name@version` |

## MIME Type Disambiguation

LanguagePackageReader routes by MIME type + content:

| MIME Type | Ecosystem | Disambiguation |
|-----------|-----------|----------------|
| `application/gzip` | PyPI | lowercase name |
| `application/gzip` | CPAN | uppercase in name |
| `application/zip` | Conda | `.conda` extension |
| `application/zip` | Packagist | contains `composer.json` |
| `application/x-tar` | RubyGems | `.gem` extension |
| `application/x-tar` | Hex | not `.gem`, has `metadata.config` |

## Thread Safety Model

```
LanguagePackage (immutable)
├── metadata: PackageMetadata (immutable, safe for concurrent read)
└── streamOpen: AtomicBoolean (serializes stream access)
    └── PackageEntryStream (only one active at a time)
```

## File Organization

```
src/
├── main/java/io/spicelabs/annatto/
│   ├── LanguagePackage.java              # Core interface
│   ├── PackageMetadata.java              # Metadata record
│   ├── PackageEntryStream.java           # Entry streaming
│   ├── LanguagePackageReader.java        # Auto-detection
│   └── ecosystem/
│       ├── npm/NpmPackage.java
│       ├── pypi/PyPIPackage.java
│       └── ... (11 implementations)
└── test/java/io/spicelabs/annatto/
    ├── contract/
    │   └── LanguagePackageContractTest.java    # Base class
    ├── LanguagePackageReaderIntegrationTest.java
    ├── SourceOfTruthIntegrationTest.java
    └── ecosystem/
        ├── npm/NpmPackageContractTest.java
        └── ... (11 contract test classes)
```

## Key Decisions (ADRs)

| ADR | Topic | Decision |
|-----|-------|----------|
| 001 | Stream Lifecycle | Single-stream policy with AtomicBoolean |
| 002 | Double Read | Load into memory, parse twice for metadata + entries |
| 003 | Metadata Extensibility | Raw map for ecosystem-specific fields |
| 004 | Thread Safety | Immutability + AtomicBoolean serialization |
| 005 | Security Model | Limits on entries, size, path validation |
| 006 | Source-of-Truth | Docker-native extraction with comparison rules |

## Common Tasks

**Add ecosystem contract tests:**
```java
class NewPackageContractTest extends LanguagePackageContractTest {
    protected String expectedValidPurl() {
        return "pkg:type/name@version";  // Exact expected PURL
    }
}
```

**Add source-of-truth test:**
1. Add package to `test-corpus/<ecosystem>/`
2. Run `docker/<ecosystem>/extract.sh`
3. Add to `SourceOfTruthIntegrationTest.packageStream()`

**Skip tests when corpus missing:**
```java
assumeThat(Files.exists(pkg)).isTrue();  // JUnit 5 assumption
```
