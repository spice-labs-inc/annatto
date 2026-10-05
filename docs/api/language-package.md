# Language Package API Guide

## Overview

The `LanguagePackageReader` is the main entry point for extracting metadata from language packages (npm, PyPI, Cargo, etc.). It is designed for integration with Goat Rodeo strategies.

## Quick Start

```java
import io.spicelabs.annatto.*;
import io.spicelabs.coordinates.Purl;
import java.nio.file.Path;

// Simple usage with auto-detection
LanguagePackage pkg = LanguagePackageReader.read(Path.of("lodash-4.17.21.tgz"));

System.out.println(pkg.name());      // "lodash"
System.out.println(pkg.version());   // "4.17.21"
System.out.println(pkg.toPurl().map(Purl::toCanonical).orElse("<none>"));
                                     // "pkg:npm/lodash@4.17.21"
```

## Package URLs

`toPurl()` returns `Optional<Purl>` — a [purl-spec](https://github.com/package-url/purl-spec)-conforming
value from the coordinates library (`io.spicelabs:coordinates`). It **never throws**: a package
whose name/version cannot produce a conforming pURL is logged at WARN and yields an empty
Optional, so one malformed package cannot abort metadata discovery (test:
`LanguagePackageContractTest.toPurlReturnsEmptyWhenIncomplete`).

- Canonical string form: `Purl.toCanonical()` (test: `PurlBuilderTest.forNpm_scopedPackage` —
  scoped npm names keep the `@` namespace, rendered `%40scope`)
- Per-type namespace rules are enforced by the library; where a type requires a namespace the
  package file lacks (cpan, golang, composer), Annatto substitutes the `"unknown"` sentinel
  (test: `PurlBuilderTest.forCpan_missingPauseIdUsesUnknownNamespace`)

## Integration with Apache Tika

For best results, use Tika to detect MIME types:

```java
import org.apache.tika.Tika;

Tika tika = new Tika();
String mimeType = tika.detect(path);

if (LanguagePackageReader.isSupported(mimeType)) {
    LanguagePackage pkg = LanguagePackageReader.read(path, mimeType);
    // Process package...
}
```

## Streaming Contents

Access archive entries without full extraction:

```java
try (PackageEntryStream entries = pkg.streamEntries()) {
    entries.forEach(entry -> {
        System.out.println(entry.name());
        if (entry.isRegularFile()) {
            // Read content
            try (InputStream content = entries.openStream()) {
                // Process content...
            }
        }
    });
}
```

## Error Handling

| Exception | Cause | Handling |
|-----------|-------|----------|
| `UnknownFormatException` | MIME type not supported | Skip file or log |
| `MalformedPackageException` | Corrupt/invalid package | Log and skip |
| `SecurityException` | Limits violated (bomb, traversal) | Log warning, quarantine |

## Execution Model (Single-Threaded)

Annatto has NO concurrency requirement (ADR-004, de-scoped 2026-08). It executes on a single
thread:

- `LanguagePackageReader`: called from ONE thread; stateless and repeatable across sequential calls
- `LanguagePackage`: read by that same ONE thread; immutable after construction
- `PackageEntryStream`: single-stream-per-package, single-threaded use only
- Concurrent `read()` / `streamEntries()` / `close()` is OUT OF SCOPE and not guaranteed

## Supported Formats

| Ecosystem | Extensions | MIME Types |
|-----------|------------|------------|
| npm | .tgz (content-required) | application/gzip |
| PyPI | .whl, .tar.gz | application/zip, application/gzip |
| Crates | .crate (content-required) | application/gzip |
| Go | .zip | application/zip |
| RubyGems | .gem | application/x-tar |
| Packagist | .zip | application/zip |
| Conda | .conda, .tar.bz2 | application/zip, application/x-bzip2 |
| CocoaPods | .podspec.json | application/json |
| CPAN | .tar.gz | application/gzip |
| Hex | .tar | application/x-tar |
| LuaRocks | .rock, .rockspec | application/zip, text/x-lua |

## Claims Verified By Tests

- All reader methods complete within 1 second (test: MimeTypeFuzzTest)
- Stream resources properly released (test: StreamingResourceManagementTest)
- Single-threaded model honored; sequential repeatability (test: SingleThreadedModelTest)
- Malicious packages are rejected (test: SecurityLimitsTest)
- toPurl() never throws; malformed metadata yields empty Optional after a WARN
  (tests: PurlBuilderTest.forCpan_moduleNameIsRejected, PurlBuilderTest.forNpm_emptyNameIsEmpty)
