# Language Package API Guide

## Overview

The `LanguagePackageReader` is the main entry point for extracting metadata from language packages (npm, PyPI, Cargo, etc.). It is designed for integration with Goat Rodeo strategies.

## Quick Start

```java
import io.spicelabs.annatto.*;
import java.nio.file.Path;

// Simple usage with auto-detection
LanguagePackage pkg = LanguagePackageReader.read(Path.of("lodash-4.17.21.tgz"));

System.out.println(pkg.name());      // "lodash"
System.out.println(pkg.version());   // "4.17.21"
System.out.println(pkg.toPurl());    // Optional[pkg:npm/lodash@4.17.21]
```

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

## Thread Safety

- `LanguagePackageReader`: Thread-safe, stateless
- `LanguagePackage`: Thread-safe for all methods except `streamEntries()`
- `PackageEntryStream`: NOT thread-safe - use from single thread only

## Supported Formats

| Ecosystem | Extensions | MIME Types |
|-----------|------------|------------|
| npm | .tgz | application/gzip |
| PyPI | .whl, .tar.gz | application/zip, application/gzip |
| Crates | .crate | application/gzip |
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
- Concurrent access is safe (test: ThreadSafetyTest)
- Malicious packages are rejected (test: SecurityLimitsTest)
