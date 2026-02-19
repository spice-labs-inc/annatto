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
- **Q2 +incompatible suffix**: Modules at major version 2+ lacking go.mod may use `+incompatible` version suffix. Tests: `parseGoMod_incompatibleVersionPreserved`, parameterized source-of-truth `extractDependencies_matchSourceOfTruth` (50 packages)
- **Q3 Pseudo-versions**: Non-tagged commits use `v0.0.0-yyyymmddhhmmss-hash` format. Tests: `goModule_genproto_pseudoVersion`, `extractVersionFromEntryName_pseudoVersion`, parameterized `extractVersion_matchesSourceOfTruth` on `google.golang.org_genproto`
- **Q4 replace/exclude directives**: `go.mod` may contain `replace` and `exclude` directives; extractor ignores these and reads only `require`. Tests: `parseGoMod_ignoresReplace`, `parseGoMod_ignoresReplaceBlock`, `parseGoMod_ignoresExclude`, `parseGoMod_ignoresExcludeBlock`
- **Q5 Major version suffixes /vN**: Modules at v2+ include major version in path (e.g., `example.com/mod/v2`); simple name is `v2`. Tests: `extractSimpleName_majorVersionModule`, `goModule_chiV5_majorVersion`, `getPurls_majorVersionModule`
- **Q6 Retracted versions**: `go.mod` can declare `retract` directives; extractor ignores them. Tests: `parseGoMod_ignoresRetract`, `parseGoMod_ignoresRetractBlock`

**PURL**: `pkg:golang/namespace/name@version` (module path split at last `/`)

**Test corpus**: 50 real Go modules downloaded from proxy.golang.org, source-of-truth metadata extracted via Go and jq in Docker (`docker/go/`).

## Crates.io Ecosystem Details

**Format**: `.crate` (gzip-compressed tar) containing `<name>-<version>/Cargo.toml`

**Extraction pipeline** (`CratesMetadataExtractor`):
1. Open `GZIPInputStream` -> `TarArchiveInputStream` (commons-compress)
2. Scan tar entries for root-level `Cargo.toml` (matching `<dir>/Cargo.toml` at depth 2 via `isCargoToml()`)
3. Read entry to String (10 MB size limit for security)
4. Parse TOML via `org.tomlj.Toml.parse()`
5. Extract name, version, description, license, publisher from `[package]` section
6. Parse dependencies from `[dependencies]`, `[dev-dependencies]`, `[build-dependencies]`, and `[target.*.dependencies]` sections
7. Normalize version constraints (bare `"1.0"` -> `"^1.0"` to match cargo semantics)

**Key quirks** (documented in `CratesQuirks.java`, tested in `CratesMetadataExtractorTest` and `CratesHandlerTest`):
- **Q1 TOML format**: `Cargo.toml` uses TOML; published crates use normalized format with dotted table headers (`[dependencies.serde]`). Tests: all 50 parameterized source-of-truth tests, `parseDependencies_simpleVersionString`, `parseDependencies_dottedTableHeaders`
- **Q2 Feature flags / optional deps**: Dependencies may be gated behind features via `optional = true`; scope remains `"runtime"`. Tests: `crate_serde_optionalDependency`, `parseDependencies_optionalScopeIsRuntime`
- **Q3 Build-dependencies**: `[build-dependencies]` section → scope `"build"`. Tests: `crate_openssl_sys_buildDeps`, `parseDependencies_buildDeps`
- **Q4 Renamed dependencies**: `package = "real-name"` in dep table → name uses real package name, not alias key. Tests: `crate_reqwest_renamedDependencies`, `parseDependencies_renamedPackage`, property test `parseDependencies_renamedUsesPackageField`
- **Q5 Edition field**: Rust edition affects language semantics but not dependency resolution; ignored. Tests: implicit in all 50 source-of-truth tests
- **Q6 No Cargo.lock**: Published library crates omit `Cargo.lock`; only direct deps from Cargo.toml extracted. Tests: implicit in all 50 source-of-truth tests

**PURL**: `pkg:cargo/name@version`

**Test corpus**: 50 real crates downloaded from crates.io, source-of-truth metadata extracted via `cargo read-manifest` and jq in Docker (`docker/crates/`).

## RubyGems Ecosystem Details

**Format**: `.gem` (plain tar archive, NOT gzip-wrapped) containing `metadata.gz` (gzip-compressed YAML gemspec)

**Extraction pipeline** (`RubygemsMetadataExtractor`):
1. Open `TarArchiveInputStream` directly (no GZIPInputStream — `.gem` is plain tar)
2. Scan tar entries for root-level `metadata.gz` (exact match via `isMetadataGz()`)
3. Decompress entry through `GZIPInputStream` to get raw YAML string (10 MB size limit)
4. Strip Ruby-specific YAML tags (`!ruby/\S+`) via regex
5. Parse with SnakeYAML `SafeConstructor`
6. Navigate parsed `Map<String, Object>`: extract name, version, description, license, publisher, dependencies
7. Reconstruct version constraints from YAML requirement arrays; map `">= 0"` to null

**Key quirks** (documented in `RubygemsQuirks.java`, tested in `RubygemsMetadataExtractorTest` and `RubygemsHandlerTest`):
- **Q1 Ruby YAML tags**: `metadata.gz` contains YAML with `!ruby/object:Gem::*` tags that must be stripped before SnakeYAML parsing. Tests: `stripRubyYamlTags_removesAllGemTags`, `stripRubyYamlTags_idempotent`, `stripRubyYamlTags_handlesMultipleTagTypes`, all 50 parameterized source-of-truth tests, property tests `stripRubyYamlTags_neverLeavesPartialTags`, `stripRubyYamlTags_isIdempotent`
- **Q2 Description fallback**: Prefers `summary` (short), falls back to `description` if summary nil/empty. Tests: `extractDescription_prefersSummary`, `extractDescription_fallsBackToDescription`, `extractDescription_bothAbsent_returnsNull`, `extractDescription_emptySummary_fallsBack`, `gem_zeitwerk_summaryAsDescription`, property test `extractDescription_summaryAlwaysPreferred`
- **Q3 Runtime vs dev deps**: `:runtime` → "runtime", `:development` → "dev". Both included. Tests: `mapDependencyType_runtime`, `mapDependencyType_development`, `mapDependencyType_nullDefaultsToRuntime`, `gem_rspec_core_mixedDependencyScopes`, `gem_devise_runtimeAndDevDeps`, property test `mapDependencyType_alwaysReturnsValidScope`
- **Q4 Version constraints**: Requirements stored as `[operator, {version: "x.y"}]` pairs; reconstructed into `"~> 3.13.0"` or `">= 2.0, < 3.2"`. Default `">= 0"` maps to null. Tests: `reconstructVersionConstraint_tildeArrow`, `reconstructVersionConstraint_compound`, `reconstructVersionConstraint_defaultIsNull`, `reconstructVersionConstraint_exact`, `gem_faraday_compoundConstraints`, `gem_rails_exactVersionPinning`, property tests `reconstructVersionConstraint_neverReturnsDefault`, `reconstructVersionConstraint_wellFormedOutput`
- **Q5 Platform field**: Gems may specify `platform` (e.g., `ruby`, `java`); ignored in metadata. Tests: implicit in all 50 source-of-truth tests (platform not in schema)
- **Q6 License join**: `licenses` array joined with `" OR "` (SPDX-like); null if empty. Tests: `joinLicenses_single`, `joinLicenses_multiple`, `joinLicenses_empty_returnsNull`, `joinLicenses_null_returnsNull`, property tests `joinLicenses_singleHasNoSeparator`, `joinLicenses_multipleHasSeparator`

**PURL**: `pkg:gem/name@version`

**Test corpus**: 50 real gems downloaded from rubygems.org, source-of-truth metadata extracted via Ruby's `Gem::Package` in Docker (`docker/rubygems/`).

## Packagist Ecosystem Details

**Format**: `.zip` archives (GitHub zipballs) containing `composer.json` at root or one directory level deep

**Extraction pipeline** (`PackagistMetadataExtractor`):
1. Open `ZipInputStream` (JDK `java.util.zip`)
2. Scan zip entries for `composer.json` at root or one level deep (via `isComposerJson()`)
3. Read entry to String (10 MB size limit, path traversal rejection)
4. Parse JSON via Gson `JsonParser`
5. Extract name, simpleName, version, description, license, publisher, dependencies
6. Filter platform dependencies from require/require-dev sections

**Key quirks** (documented in `PackagistQuirks.java`, tested in `PackagistMetadataExtractorTest` and `PackagistHandlerTest`):
- **Q1 Version absence**: Version almost always absent from `composer.json` (Composer derives it from git tag). No PURL generated when absent. Tests: `extractVersion_matchesSourceOfTruth` (50 packages), `getPurls_noVersion_returnsEmptyList`, `package_version_absent`, `buildMetadataResult_neverThrowsForValidJson` (property)
- **Q2 Vendor/package naming**: Names follow `vendor/package` format; `simpleName` = part after `/`. PURL namespace = vendor, name = package. Tests: `extractSimpleName_vendorSlashName`, `extractSimpleName_noSlash`, `extractSimpleName_multipleSlashes`, all 50 SoT name+simpleName tests, `extractSimpleName_alwaysNonEmpty` (property), `extractSimpleName_idempotent` (property)
- **Q3 require vs require-dev + platform filtering**: `require` → scope "runtime", `require-dev` → scope "dev". Platform deps (`php`, `php-64bit`, `hhvm`, `ext-*`, `lib-*`, `composer-plugin-api`, `composer-runtime-api`, `composer`) excluded. Tests: `extractDependencies_requireIsRuntime`, `extractDependencies_requireDevIsDev`, `isPlatformDependency_php`, `isPlatformDependency_ext`, `isPlatformDependency_lib`, `isPlatformDependency_composerPluginApi`, `package_symfony_console_platformDepsFiltered`, `package_ramsey_uuid_platformAndRealDeps`, `extractDependencies_neverIncludesPlatformDeps` (property), `extractDependencies_scopeAlwaysValid` (property)
- **Q4 replace/provide ignored**: Virtual package relationships, not actual dependencies. Tests: implicit in all 50 SoT tests (no replace/provide deps appear in expected output)
- **Q5 Metadata-only registry**: Packagist doesn't host archives; dist URLs point to VCS (typically GitHub zipballs). Tests: implicit — test corpus downloaded from actual Packagist dist URLs
- **Q6 License formats**: String (`"MIT"`) or array (`["MIT", "GPL-3.0"]`), array joined with `" OR "`. Absent or empty → null. Tests: `extractLicense_string`, `extractLicense_arraySingle`, `extractLicense_arrayMultiple`, `extractLicense_absent`, `package_league_flysystem_licenseString`, `extractLicense_neverReturnsEmptyString` (property)

**PURL**: `pkg:composer/vendor/name@version` (empty when version absent)

**Test corpus**: 50 real Composer packages downloaded from Packagist dist URLs, source-of-truth metadata extracted via PHP in Docker (`docker/packagist/`).

## Conda Ecosystem Details

**Format**: Two distinct archive formats — `.conda` (modern ZIP with zstd-compressed inner tars) and `.tar.bz2` (legacy bzip2-compressed tar). Both contain `info/index.json` (primary metadata) and optionally `info/about.json` (descriptive metadata).

**Extraction pipeline** (`CondaMetadataExtractor`):
1. Detect format by extension: `.conda` → `extractFromCondaFormat()`, `.tar.bz2` → `extractFromTarBz2Format()`
2. For `.conda`: `ZipInputStream` → find `info-*.tar.zst` entry → `ZstdCompressorInputStream` (commons-compress) → `TarArchiveInputStream` → find `info/index.json` + `info/about.json`
3. For `.tar.bz2`: `BZip2CompressorInputStream` → `TarArchiveInputStream` → find `info/index.json` + `info/about.json`
4. Early exit once both files found (skips large pkg-* entries)
5. Path traversal rejection on entries with `..`; 10 MB per-file size limit
6. Return `CondaArchiveData(indexJson, aboutJson)` → `buildMetadataResult()` via Gson

**Key quirks** (documented in `CondaQuirks.java`, tested in `CondaMetadataExtractorTest`, `CondaHandlerTest`, and `CondaMetadataExtractorPropertyTest`):
- **Q1 Two archive formats**: `.conda` (ZIP → info-*.tar.zst → zstd → tar) vs `.tar.bz2` (bzip2 → tar). Tests: `detectFormat_conda`, `detectFormat_tarBz2`, `detectFormat_unknown_throws`, all 50 SoT tests, `begin_condaFormat_returnsMementoWithMetadata`, `begin_tarBz2Format_returnsMementoWithMetadata`, property `detectFormat_alwaysDeterminesFormatFromExtension`
- **Q2 Channel not in package**: Channel (e.g. `conda-forge`) is external context, not in the archive. No PURL namespace. Publisher always null (no author field). Tests: `getPurls_noNamespace`, `buildMetadataResult_publisherAlwaysNull` (unit + property), `extractPublisher_matchesSourceOfTruth` (50 packages)
- **Q3 Build string as PURL qualifier**: Build string (e.g. `py312hc5e2394_0`) disambiguates multiple builds of the same version; included as `?build=...` qualifier. Tests: `extractBuild_matchesSourceOfTruth` (50 packages), `forConda_withBuildQualifier`, `getPurls_condaFormat_includesBuildQualifier`
- **Q4 Subdir/platform targeting**: `subdir` field (e.g. `linux-64`, `noarch`) identifies target platform; included as `?subdir=...` qualifier. Tests: `extractSubdir_matchesSourceOfTruth` (50 packages), `forConda_withSubdirQualifier`, `package_noarch_python`, `package_linux64_platformSpecific`
- **Q5 Constrains vs depends**: `constrains` field specifies optional version restrictions on co-installed packages — NOT dependencies. Ignored entirely. Tests: `package_constrains_ignored`, implicit in all 50 SoT tests
- **Q6 Match spec dependency format**: Dependencies in `index.json` are match spec strings: `"name version_constraint [build_string]"`. Name is first token, remainder is versionConstraint. All scope "runtime" (no dev deps in conda). Tests: `parseMatchSpec_nameOnly`, `parseMatchSpec_nameAndVersion`, `parseMatchSpec_nameVersionBuild`, `parseMatchSpec_commaConstraint`, `parseMatchSpec_equalConstraint`, `extractDependencies_matchSourceOfTruth` (50 packages), properties `parseMatchSpec_alwaysProducesNonEmptyName`, `parseMatchSpec_nameNeverContainsSpaces`, `parseMatchSpec_scopeAlwaysRuntime`, `parseMatchSpec_roundTrip`
- **Q7 Description from about.json**: `summary` preferred, `description` fallback, null if `about.json` absent. Tests: `extractDescription_summaryPreferred`, `extractDescription_descriptionFallback`, `extractDescription_bothAbsent`, `extractDescription_summaryEmpty`, `package_noAboutJson`, `extractDescription_matchesSourceOfTruth` (50 packages), property `extractDescription_summaryAlwaysPreferred`
- **Q8 Timestamp in milliseconds**: `index.json` `timestamp` is millis since epoch; converted to ISO 8601 (`yyyy-MM-dd'T'HH:mm:ss+00:00`). Absent in some packages. Tests: `timestampConversion_millisToIso`, `timestampConversion_absent`, `timestampConversion_zero`, `timestampConversion_negativeTimestamp`, `extractPublishedAt_matchesSourceOfTruth` (50 packages), property `timestampConversion_outputIsIso8601`, `timestampConversion_neverNegativeYear`

**PURL**: `pkg:conda/name@version?build=<build>&subdir=<subdir>` (no namespace per purl-spec; build and subdir as qualifiers)

**Test corpus**: 50 real conda packages (25 `.conda` + 25 `.tar.bz2`) downloaded from conda-forge, source-of-truth metadata extracted via Python in Docker (`docker/conda/`). 711 tests total: 550 parameterized SoT, 11 named, 26+ unit, 5 PURL, 112 handler, 11 property.

## LuaRocks Ecosystem Details

**Format**: Two input types — `.src.rock` (ZIP archive containing a `.rockspec` file at the root) and standalone `.rockspec` files (plain Lua text). Rockspec files are executable Lua scripts, not static data formats.

**Lua subset parser** (no external dependency): Annatto includes a purpose-built Lua subset parser in three layers:
- `LuaTokenizer` — lexes Lua source into tokens (strings, numbers, names, symbols). Handles quoted strings with escapes, long bracket strings (`[[...]]`), line/block comments, hex numbers, and BOM stripping. Security limits: 1 MB input, 50,000 tokens.
- `LuaTableBuilder` — evaluates Lua expressions (string/number/boolean/nil literals, table constructors, string concatenation `..`, variable references, dotted access, bracket-key syntax, method calls like `:format()`). Unsupported constructs return null. Null values in tables are filtered before `Map.copyOf`/`List.copyOf`. Security limits: depth 20, string length 1 MB, 10,000 table elements.
- `LuaRockspecEvaluator` — executes a sequence of Lua statements (global/local assignments, multi-assignment `local a, b = x, y`, dotted assignment `description.summary = "..."`, semicolons). Skips unrecognized constructs (function definitions, control flow, return). Catches `RuntimeException` to skip failed statements while preserving already-captured metadata fields.

**Extraction pipeline** (`LuarocksMetadataExtractor`):
1. Detect format by filename extension: `.rockspec` → read directly, `.rock` → `ZipInputStream` → find root-level `.rockspec`
2. Path traversal rejection on entries with `..`; 1 MB file size limit
3. Evaluate rockspec Lua text via `LuaRockspecEvaluator.evaluate()` → `Map<String, Object>`
4. Extract fields: `package` (name), `version`, `description.summary`/`description.detailed`, `description.license`, `description.maintainer`
5. Parse dependencies from `dependencies` (runtime), `build_dependencies` (build), `test_dependencies` (test); `external_dependencies` filtered entirely
6. Build `MetadataResult` (simpleName == name, no vendor concept; publishedAt always null)

**Key quirks** (documented in `LuarocksQuirks.java`, tested in `LuarocksMetadataExtractorTest`, `LuarocksHandlerTest`, `LuaTokenizerTest`, `LuaTableBuilderTest`, `LuaRockspecEvaluatorTest`, and `LuarocksMetadataExtractorPropertyTest`):
- **Q1 Rockspec is Lua code (not static data)**: Rockspec files are valid Lua scripts executed to produce metadata tables. Annatto uses a limited Lua subset evaluator that handles simple assignments, table constructors, string concatenation, local variables, multi-assignment (`local a, b = x, y`), method calls (`:format()`), and comments. The evaluator catches `RuntimeException` to skip failed statements (e.g., complex build tables with function calls) while preserving already-captured metadata. Tests: `luaTokenizer_*`, `luaEvaluator_*`, all 50 SoT tests, property `luaEvaluator_neverThrowsForValidRockspec`
- **Q2 Version includes revision suffix**: LuaRocks versions follow `<upstream-version>-<revision>` (e.g., `1.8.0-1`). The revision is incremented when the rockspec changes. Tests: `extractVersion_matchesSourceOfTruth`, `forLuaRocks_versionWithRevision`, property `extractVersion_alwaysContainsHyphenRevision`
- **Q3 external_dependencies filtered**: Rockspecs can declare `external_dependencies` referencing system-level C libraries (e.g., OpenSSL). These are not LuaRocks packages and are filtered out. Tests: `extractDependencies_externalDepsFiltered`, `package_luasec_externalDeps`, property `extractDependencies_neverContainsExternalDeps`
- **Q4 Dependency string format**: Dependencies are strings like `"lua >= 5.1"` or `"luasocket"`. Split on first space: name = first token, versionConstraint = rest. Tests: `parseDependencyString_*`, `extractDependencies_matchSourceOfTruth`, properties `parseDependencyString_*`
- **Q5 Description fallback**: Description extracted from `description.summary` (preferred), falling back to `description.detailed`, then null. Tests: `extractDescription_*`, property `extractDescription_summaryAlwaysPreferred`
- **Q6 Publisher from maintainer**: Publisher extracted from `description.maintainer`, null if absent. Tests: `extractPublisher_*`, all 50 SoT publisher tests
- **Q7 No publishedAt**: Rockspec files have no timestamp field; `publishedAt` is always null. Tests: `extractPublishedAt_matchesSourceOfTruth`, property `buildMetadataResult_publishedAtAlwaysEmpty`
- **Q8 PURL name lowercasing**: Per purl-spec, LuaRocks PURL names are ASCII lowercased. The original case is preserved in `name` and `simpleName` metadata fields. Tests: `forLuaRocks_nameLowercased`, `getPurls_nameLowercased`, property `purlName_alwaysLowercase`

**PURL**: `pkg:luarocks/name@version` (name lowercased, no namespace)

**Test corpus**: 50 real LuaRocks packages downloaded from luarocks.org, source-of-truth metadata extracted via Lua in Docker (`docker/luarocks/`). 663 tests total.

## Exception Hierarchy

- `AnnattoException` (base)
  - `UnsupportedEcosystemException` - ecosystem not recognized
  - `MetadataExtractionException` - parsing or I/O failure
  - `MalformedPackageException` - structurally invalid package
