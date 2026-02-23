# Annatto Architecture

## Overview

Annatto is a plugin for [Goat Rodeo](https://github.com/spice-labs-inc/goatrodeo) that extracts and normalizes package metadata from 11 programming language ecosystems. It integrates via the [rodeo-components](https://github.com/spice-labs-inc/rodeo-components) plugin system using Java's `ServiceLoader` mechanism.

## Plugin Lifecycle

`AnnattoComponent` implements `RodeoComponent` and follows this lifecycle:

1. **Discovery** — `ServiceLoader` finds `AnnattoComponent` via `META-INF/services/io.spicelabs.rodeocomponents.RodeoComponent`
2. **initialize()** — Validates runtime environment
3. **exportAPIFactories()** — Annatto does not export APIs
4. **importAPIFactories()** — Obtains `ArtifactHandlerRegistrar` and registers `AnnattoProcessFilter` with all 11 ecosystem handlers
5. **onLoadingComplete()** — Final validation
6. **shutDown()** — Releases acquired APIs

## Metadata Extraction Pipeline

When Goat Rodeo encounters a package artifact, Annatto processes it through this pipeline:

```
Artifact File
  -> AnnattoProcessFilter.filterByName()   // detect ecosystem by file extension
  -> EcosystemHandler.begin()              // parse package, create memento
  -> EcosystemHandler.getMetadata()        // return normalized MetadataResult
  -> EcosystemHandler.getPurls()           // construct Package URLs
  -> EcosystemHandler.augment()            // add edges/associations
  -> EcosystemHandler.postChildProcessing() // capture child results
  -> EcosystemHandler.end()                // cleanup
```

### Ecosystem Detection

`AnnattoProcessFilter` routes artifacts to the correct handler based on file extension and MIME type. Notable disambiguation:

- `.tar.gz` files are split between CPAN and PyPI using a heuristic: names containing uppercase characters route to CPAN, lowercase to PyPI
- `.tar` files (after excluding `.tar.gz` and `.tar.bz2`) route to Hex

### Normalized Metadata

All ecosystems produce the same `MetadataResult` record with these fields:

| Field | Description |
|-------|-------------|
| `name` | Fully qualified name (e.g., `@scope/name`, `vendor/package`) |
| `simpleName` | Unqualified short name |
| `version` | Version as-is from the ecosystem |
| `description` | Package description or summary |
| `license` | SPDX identifier where possible |
| `publisher` | First author or maintainer |
| `publishedAt` | ISO 8601 date (when available) |
| `dependencies` | List of `ParsedDependency(name, scope, versionConstraint)` |

### Package URL Generation

Each ecosystem implements PURL construction in `PurlBuilder` following the [purl-spec](https://github.com/package-url/purl-spec). Ecosystem-specific details:

- **npm**: `pkg:npm/[@scope/]name@version`
- **PyPI**: `pkg:pypi/normalized-name@version` (PEP 503 normalization)
- **Go**: `pkg:golang/namespace/name@version`
- **Crates.io**: `pkg:cargo/name@version`
- **RubyGems**: `pkg:gem/name@version`
- **Packagist**: `pkg:composer/vendor/name@version` (empty when version absent)
- **Conda**: `pkg:conda/name@version?build=<build>&subdir=<subdir>`
- **CocoaPods**: `pkg:cocoapods/Name@version` (case-sensitive)
- **CPAN**: `pkg:cpan/Distribution-Name@version`
- **Hex**: `pkg:hex/name@version` (lowercased)
- **LuaRocks**: `pkg:luarocks/name@version` (lowercased)

## Per-Ecosystem Structure

Each ecosystem is implemented as a self-contained Java package under `io.spicelabs.annatto.<ecosystem>`:

| File | Purpose |
|------|---------|
| `<E>Handler.java` | `ArtifactHandler` implementation — lifecycle methods |
| `<E>Memento.java` | Processing state holder (created in `begin()`, consumed by other methods) |
| `<E>MetadataExtractor.java` | Stateless metadata parsing logic (private constructor, static methods) |
| `<E>Marker.java` | `RodeoItemMarker` for tagging processed items |
| `<E>Quirks.java` | Documented ecosystem-specific behaviors and edge cases |
| `package-info.java` | Package-level Javadoc |

## Thread Safety

Annatto achieves thread safety through immutability and isolation:

- **Immutable records**: `MetadataResult`, `ParsedDependency`, and all mementos use `List.copyOf()` and `Map.copyOf()`
- **No shared mutable state**: Each `begin()` creates a fresh memento; handlers hold no state between invocations
- **Stateless extractors**: All `*MetadataExtractor` classes are pure functions with private constructors and static methods
- **Stateless filter**: `AnnattoProcessFilter` only inspects filenames
- **Atomic lifecycle fields**: `AnnattoComponent` uses `AtomicReference` for fields set during the plugin lifecycle

## Custom Parsers

Two ecosystems use metadata formats that have no standard Java parser, so Annatto includes purpose-built parsers:

### Erlang Term Parser (Hex)

Hex packages store metadata in `metadata.config` using Erlang external term format. The parser has two layers:

- **`ErlangTermTokenizer`** — Lexes Erlang terms into tokens: binary strings (`<<"text">>`), atoms, integers, tuples (`{}`), lists (`[]`). Handles `<<"text"/utf8>>` encoding specifiers and `%` line comments. Limits: 1 MB input, 50,000 tokens.
- **`ErlangTermParser`** — Parses tokens into `Map<String, Object>`. Detects proplist patterns (list of 2-element tuples with string keys) and converts to Map. Limits: nesting depth 10, 10,000 elements.

### Lua Subset Evaluator (LuaRocks)

LuaRocks rockspec files are executable Lua scripts. The evaluator has three layers:

- **`LuaTokenizer`** — Lexes Lua source into tokens. Handles quoted strings with escapes, long bracket strings (`[[...]]`), comments, and hex numbers. Limits: 1 MB input, 50,000 tokens.
- **`LuaTableBuilder`** — Evaluates Lua expressions: literals, table constructors, string concatenation (`..`), variable references, dotted access. Unsupported constructs return null. Limits: depth 20, 1 MB strings, 10,000 table elements.
- **`LuaRockspecEvaluator`** — Executes assignment statements, captures metadata fields, skips unrecognized constructs (function definitions, control flow). Catches `RuntimeException` to skip failed statements while preserving already-captured fields.

## Source-of-Truth Testing

Each ecosystem is validated against real packages using native tools:

1. **Download**: `docker/<ecosystem>/download.sh` fetches 50 real packages from the ecosystem registry
2. **Extract**: `docker/<ecosystem>/extract.*` runs native tools (Node.js, Python, Ruby, Go, etc.) inside Docker to produce expected JSON
3. **Store**: Expected JSON is committed to `src/test/resources/<ecosystem>/`; package files are downloaded to `test-corpus/` (gitignored)
4. **Compare**: `*MetadataExtractorTest` parameterized tests compare Annatto's Java output against the expected JSON via `SourceOfTruth.loadExpected()`

This gives 550+ source-of-truth comparisons (50 packages x 11 ecosystems) ensuring Annatto's pure-Java extraction matches native tool output.

## Ecosystem-Specific Notes

### npm
- Archive: gzip-compressed tar containing `package/package.json`
- Scoped packages (`@scope/name`) use scope as PURL namespace
- License formats: SPDX string, `{"type": "MIT"}` object, or legacy `licenses` array

### PyPI
- Two formats: wheels (`.whl` ZIP with `*.dist-info/METADATA`) and sdists (`.tar.gz` with `PKG-INFO`)
- Package names normalized per PEP 503 (lowercase, collapse `[-_.]` to hyphen)
- Dependencies parsed from `Requires-Dist` headers with environment markers stripped

### Go Modules
- Archive from module proxy containing `module@version/go.mod`
- Version extracted from zip entry path prefix
- `replace`, `exclude`, and `retract` directives ignored; only `require` parsed

### Crates.io
- gzip tar containing `Cargo.toml` parsed as TOML
- Dependencies from `[dependencies]`, `[dev-dependencies]`, `[build-dependencies]`, and `[target.*.dependencies]`
- Renamed dependencies (`package = "real-name"`) resolved to real package name

### RubyGems
- Plain tar (NOT gzip) containing `metadata.gz` (gzip-compressed YAML)
- Ruby YAML tags (`!ruby/object:Gem::*`) stripped before parsing with SnakeYAML
- Description prefers `summary`, falls back to `description`

### Packagist
- ZIP archives (GitHub zipballs) with `composer.json`
- Version almost always absent (derived from git tag); no PURL when missing
- Platform dependencies (`php`, `ext-*`, `lib-*`, `composer-*`) filtered out

### Conda
- Two formats: `.conda` (ZIP with zstd-compressed inner tars) and `.tar.bz2` (bzip2 tar)
- Metadata in `info/index.json` + optional `info/about.json`
- Build string and subdir included as PURL qualifiers

### CocoaPods
- `.podspec.json` only (Ruby DSL `.podspec` not supported)
- Subspec dependencies aggregated, filtered by `default_subspecs`; self-referencing deps removed
- Pod names are case-sensitive in PURLs

### CPAN
- gzip tar with `META.json` (v2, preferred) or `META.yml` (v1)
- Prerequisites organized by phase (runtime, test, build, configure, develop) and relationship (only `requires` extracted)
- v1 license identifiers normalized (`perl` -> `perl_5`, `gpl` -> `gpl_1`)

### Hex
- Plain tar (NOT gzip) with `metadata.config` in Erlang term format
- Both Elixir (mix) and Erlang (rebar3) requirement formats supported transparently
- No publisher or publication date in metadata

### LuaRocks
- `.src.rock` (ZIP containing `.rockspec`) or standalone `.rockspec` files
- Versions include revision suffix (e.g., `1.8.0-1`)
- `external_dependencies` (system C libraries) filtered out

## Exception Hierarchy

```
AnnattoException
  ├── UnsupportedEcosystemException  — ecosystem not recognized
  ├── MetadataExtractionException    — parsing or I/O failure
  └── MalformedPackageException      — structurally invalid package
```

## Project Layout

```
annatto/
├── .github/
│   ├── workflows/
│   │   ├── buildAndTest.yml          # CI: build, test, coverage
│   │   └── publish.yml               # Release to GitHub Packages & Maven Central
│   ├── ISSUE_TEMPLATE/
│   └── PULL_REQUEST_TEMPLATE.md
├── docker/                            # Native extractors for source-of-truth testing
│   ├── cocoapods/
│   ├── conda/
│   ├── cpan/
│   ├── crates/
│   ├── go/
│   ├── hex/
│   ├── luarocks/
│   ├── npm/
│   ├── packagist/
│   ├── pypi/
│   └── rubygems/
├── src/
│   ├── main/java/io/spicelabs/annatto/
│   │   ├── AnnattoComponent.java      # Plugin entry point
│   │   ├── common/                    # Shared types (MetadataResult, ParsedDependency, PurlBuilder)
│   │   ├── filter/                    # AnnattoProcessFilter
│   │   ├── handler/                   # Base classes
│   │   ├── npm/                       # Per-ecosystem packages
│   │   ├── pypi/
│   │   ├── go/
│   │   ├── crates/
│   │   ├── rubygems/
│   │   ├── packagist/
│   │   ├── conda/
│   │   ├── cocoapods/
│   │   ├── cpan/
│   │   ├── hex/                       # Includes ErlangTermTokenizer/Parser
│   │   └── luarocks/                  # Includes LuaTokenizer/TableBuilder/Evaluator
│   └── test/
│       ├── java/                      # 44 test classes, 550+ SoT comparisons
│       └── resources/                 # Expected JSON per ecosystem (50 each)
├── pom.xml
├── README.md
├── CONTRIBUTING.md
├── ARCHITECTURE.md
├── SECURITY.md
└── LICENSE
```
