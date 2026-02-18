# Annatto Architecture

## Plugin Lifecycle

Annatto integrates with Goat Rodeo via the `rodeo-components` plugin system. The lifecycle is:

1. **Discovery**: `AnnattoComponent` is found via `ServiceLoader` from `META-INF/services/io.spicelabs.rodeocomponents.RodeoComponent`
2. **initialize()**: Validates runtime environment
3. **exportAPIFactories()**: Annatto does not export APIs
4. **importAPIFactories()**: Obtains `ArtifactHandlerRegistrar` and registers `AnnattoProcessFilter`
5. **onLoadingComplete()**: Final validation
6. **shutDown()**: Releases acquired APIs

## Metadata Extraction Pipeline

```
Artifact File
    -> AnnattoProcessFilter.filterByName()   (detect ecosystem by extension/MIME)
    -> EcosystemHandler.begin()              (parse package, create memento)
    -> EcosystemHandler.getMetadata()        (return normalized MetadataResult)
    -> EcosystemHandler.getPurls()           (construct Package URLs)
    -> EcosystemHandler.augment()            (add edges/associations)
    -> EcosystemHandler.postChildProcessing() (capture child results)
    -> EcosystemHandler.end()                (cleanup)
```

## Thread Safety Model

- All records are immutable (`List.copyOf()`, `Map.copyOf()`)
- No shared mutable state in handlers - each `begin()` creates a fresh memento
- `AnnattoProcessFilter` is stateless (only inspects filenames)
- `AnnattoComponent` uses `AtomicReference` for fields set during lifecycle
- Ecosystem extractors are stateless pure functions: input -> output
- Concurrency through immutability and the memento pattern

## Per-Ecosystem Structure

Each ecosystem package contains:

| File | Purpose |
|------|---------|
| `<E>Handler.java` | `ArtifactHandler` implementation |
| `<E>Memento.java` | Processing state memento |
| `<E>MetadataExtractor.java` | Metadata parsing logic |
| `<E>Marker.java` | `RodeoItemMarker` |
| `<E>Quirks.java` | Documented ecosystem quirks |
| `package-info.java` | Package-level JavaDoc |

## Metadata Normalization

All ecosystems map to the same `MetadataTag` enum:

| Tag | Description |
|-----|-------------|
| `NAME` | Fully qualified name (e.g., `@scope/name`, `vendor/package`) |
| `SIMPLE_NAME` | Unqualified short name |
| `VERSION` | Version as-is from ecosystem |
| `DESCRIPTION` | Package description/summary |
| `LICENSE` | SPDX identifier where possible |
| `PUBLISHER` | First author/maintainer |
| `PUBLICATION_DATE` | ISO 8601 date |
| `DEPENDENCIES` | Comma-separated `name@constraint` strings |

## Docker Source-of-Truth Workflow

During corpus preparation (not during normal test runs):

1. Download a real package from the ecosystem registry
2. Run `docker run annatto-<ecosystem>-validator <package-file>` -> outputs JSON
3. Upload both the package and the expected JSON to the test data server
4. Tests compare Annatto output against the expected JSON

## npm Ecosystem Details

**Format**: `.tgz` (gzip-compressed tar) containing `package/package.json`

**Extraction pipeline** (`NpmMetadataExtractor`):
1. Open `GZIPInputStream` -> `TarArchiveInputStream`
2. Scan tar entries for top-level `package.json` (see `isPackageJson()`)
3. Parse JSON via Gson
4. Extract name, version, description, license, author, dependencies

**Key quirks** (documented in `NpmQuirks.java`, tested in `NpmMetadataExtractorTest`):
- **Q1 Scoped packages**: `@scope/name` format; scope becomes PURL namespace
- **Q2 Author formats**: String `"Name <email> (url)"` or object `{"name": "..."}`, with fallback to `maintainers`/`contributors`
- **Q3 License formats**: Modern SPDX string, object `{"type": "MIT"}`, or legacy `licenses` array joined with " OR "
- **Q4 Dependency types**: `dependencies` (runtime), `devDependencies` (dev), `peerDependencies` (peer), `optionalDependencies` (optional)
- **Q5 Registry fields**: `_`-prefixed fields ignored
- **Q6 Archive structure**: Files under `package/` prefix; matches first-level `/package.json`
- **Q7 Non-ASCII**: UTF-8 throughout
- **Q8 Minimal packages**: Only `name` and `version` required

**PURL**: `pkg:npm/[@scope/]name@version`

**Test corpus**: 50 real npm packages downloaded from the registry, source-of-truth metadata extracted via Node.js in Docker (`docker/npm/`).

## PyPI Ecosystem Details

**Format**: `.whl` (ZIP wheel) and `.tar.gz` (gzip-compressed tar sdist)

**Extraction pipeline** (`PypiMetadataExtractor`):
1. Detect format by filename extension (.whl or .tar.gz)
2. For wheels: open `ZipInputStream`, find `*.dist-info/METADATA`
3. For sdists: open `GZIPInputStream` -> `TarArchiveInputStream`, find top-level `PKG-INFO`
4. Parse RFC 822 headers via `parseRfc822Headers()`
5. Extract name, version, description (Summary), license, publisher, dependencies

**Key quirks** (documented in `PypiQuirks.java`, tested in `PypiMetadataExtractorTest`):
- **Q1 Name normalization**: PEP 503 — lowercase, collapse runs of `[-_.]` to single hyphen. Tests: `normalizeName_*`, `getPurls_nameNormalization_flaskSocketIO`
- **Q2 Sdist vs wheel**: Wheels have `*.dist-info/METADATA`, sdists have `*/PKG-INFO`. Tests: `isDistInfoMetadata_*`, `isPkgInfo_*`, `wheelPackage_*`, `sdistPackage_*`, `fullExtraction_*`
- **Q3 pyproject.toml**: Not in published archives; extractor reads METADATA/PKG-INFO only. Verified by all 50 parameterized source-of-truth tests
- **Q4 RFC 822 format**: Multi-line continuation, repeated headers (Requires-Dist, Classifier). Tests: `parseRfc822Headers_*`
- **Q5 License classifiers**: Priority: `License-Expression` > `License` header > `Classifier: License :: ...`. Tests: `extractLicense_*`
- **Q6 Author-email**: Combined `"Name <email>"` format as publisher fallback. Tests: `extractPublisher_*`, `extractNameFromEmailField_*`
- **Q7 Environment markers**: `Requires-Dist` entries with `;` markers stripped; all deps scoped "runtime". Tests: `parseRequiresDist_*`, `extractDependencies_*`
- **Q8 Minimal packages**: Only Metadata-Version, Name, Version required; "UNKNOWN" treated as absent. Tests: parameterized source-of-truth tests on minimal-metadata packages, `extractPublisher_noPublisherReturnsEmpty`, `extractLicense_noLicenseReturnsEmpty`

**PURL**: `pkg:pypi/normalized-name@version` (PEP 503 normalization applied)

**Test corpus**: 50 real PyPI packages (46 wheels + 4 sdists) downloaded from the registry, source-of-truth metadata extracted via Python in Docker (`docker/pypi/`).

## Go Modules Ecosystem Details

**Format**: `.zip` archives from the Go module proxy containing `module@version/go.mod` and source files

**Extraction pipeline** (`GoMetadataExtractor`):
1. Open `ZipInputStream` (JDK `java.util.zip`)
2. Scan zip entries for root-level `go.mod` (matching `*@*/go.mod` pattern via `isGoMod()`)
3. Extract version from zip entry name prefix (e.g., `module@v1.2.3/go.mod` → `v1.2.3`)
4. Parse `go.mod` text: module path, require directives, indirect markers
5. Map to normalized `MetadataResult` (description, license, publisher all empty)

**Key quirks** (documented in `GoQuirks.java`, tested in `GoMetadataExtractorTest` and `GoHandlerTest`):
- **Q1 URL-like module paths**: Module paths resemble URLs (e.g., `github.com/user/repo`); used as package name. Tests: `extractName_matchesSourceOfTruth` (50 packages), `extractSimpleName_standardModule`, `extractSimpleName_golangOrgX`, `extractSimpleName_gopkgIn`
- **Q2 +incompatible suffix**: Modules at major version 2+ lacking go.mod may use `+incompatible` version suffix. Tests: parameterized source-of-truth tests on packages with +incompatible dependency versions
- **Q3 Pseudo-versions**: Non-tagged commits use `v0.0.0-yyyymmddhhmmss-hash` format. Tests: `goModule_genproto_pseudoVersion`, `extractVersionFromEntryName_pseudoVersion`, parameterized tests on `google.golang.org_genproto`
- **Q4 replace/exclude directives**: `go.mod` may contain `replace` and `exclude` directives; extractor ignores these and reads only `require`. Tests: `parseGoMod_ignoresReplace`, `parseGoMod_ignoresReplaceBlock`, `parseGoMod_ignoresExclude`
- **Q5 Major version suffixes /vN**: Modules at v2+ include major version in path (e.g., `example.com/mod/v2`); simple name is `v2`. Tests: `extractSimpleName_majorVersionModule`, `goModule_chiV5_majorVersion`, `getPurls_majorVersionModule`
- **Q6 Retracted versions**: `go.mod` can declare `retract` directives; extractor ignores them. Tests: `parseGoMod_ignoresRetract`, `parseGoMod_ignoresRetractBlock`

**PURL**: `pkg:golang/namespace/name@version` (module path split at last `/`)

**Test corpus**: 50 real Go modules downloaded from proxy.golang.org, source-of-truth metadata extracted via Go and jq in Docker (`docker/go/`).

## Exception Hierarchy

- `AnnattoException` (base)
  - `UnsupportedEcosystemException` - ecosystem not recognized
  - `MetadataExtractionException` - parsing or I/O failure
  - `MalformedPackageException` - structurally invalid package
