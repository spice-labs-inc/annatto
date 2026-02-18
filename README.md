# Annatto

Multi-ecosystem package metadata extraction plugin for [Goat Rodeo](https://github.com/spice-labs-inc/goatrodeo).

Annatto extracts and normalizes package metadata from 11 ecosystems, integrating via the [rodeo-components](https://github.com/spice-labs-inc/rodeo-components) plugin system.

## Supported Ecosystems

| Ecosystem | Format | PURL Type | Status |
|-----------|--------|-----------|--------|
| npm | `.tgz` (package.json) | `pkg:npm` | **Implemented** |
| PyPI | `.tar.gz` / `.whl` | `pkg:pypi` | **Implemented** |
| Go Modules | `.zip` (go.mod) | `pkg:golang` | **Implemented** |
| Crates.io | `.crate` (Cargo.toml) | `pkg:cargo` | Skeleton |
| RubyGems | `.gem` (metadata.gz YAML) | `pkg:gem` | Skeleton |
| Packagist | `.zip` (composer.json) | `pkg:composer` | Skeleton |
| Conda | `.tar.bz2` / `.conda` | `pkg:conda` | Skeleton |
| CocoaPods | `.podspec` / `.podspec.json` | `pkg:cocoapods` | Skeleton |
| CPAN | `.tar.gz` (META.json) | `pkg:cpan` | Skeleton |
| Hex | `.tar` (metadata.config) | `pkg:hex` | Skeleton |
| LuaRocks | `.rock` / `.rockspec` | `pkg:luarocks` | Skeleton |

## Prerequisites

- Java 21 (Temurin recommended)
- Maven 3.9+

## Building

```bash
mvn clean install
```

## Running Tests

```bash
mvn test
```

Coverage reports are generated at `target/site/jacoco/index.html`.

## Usage

Annatto is discovered automatically by Goat Rodeo via Java's `ServiceLoader` mechanism. Add Annatto to your classpath and the `AnnattoComponent` will be loaded during Goat Rodeo startup.

```xml
<dependency>
    <groupId>io.spicelabs</groupId>
    <artifactId>annatto</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for the plugin lifecycle, metadata extraction pipeline, thread safety model, and per-ecosystem structure.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for code style, TDD process, and the checklist for adding a new ecosystem.

## License

Apache License 2.0 - see [LICENSE](LICENSE) for details.

Copyright 2026 Spice Labs, Inc.
