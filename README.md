# Annatto

[![Maven Central](https://img.shields.io/maven-central/v/io.spicelabs/annatto?label=Maven%20Central)](https://central.sonatype.com/artifact/io.spicelabs/annatto)
[![GitHub Release](https://img.shields.io/github/v/release/spice-labs-inc/annatto?label=GitHub%20Release)](https://github.com/spice-labs-inc/annatto/releases)
[![GitHub Package](https://img.shields.io/badge/GitHub-Packages-blue?logo=github)](https://github.com/spice-labs-inc/annatto/packages/)
[![Build Status](https://github.com/spice-labs-inc/annatto/actions/workflows/buildAndTest.yml/badge.svg)](https://github.com/spice-labs-inc/annatto/actions)

**Annatto** is a Java plugin for [Goat Rodeo](https://github.com/spice-labs-inc/goatrodeo) that extracts and normalizes package metadata from 11 programming language ecosystems. It integrates via the [rodeo-components](https://github.com/spice-labs-inc/rodeo-components) plugin system and produces standardized metadata and [Package URLs](https://github.com/package-url/purl-spec) for use in Artifact Dependency Graphs.

## Quick Start

### Prerequisites

- **Java 21** or higher
- **Maven 3.9+** (for building)

### Installation

#### Maven

```xml
<dependency>
    <groupId>io.spicelabs</groupId>
    <artifactId>annatto</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

#### Gradle

```groovy
implementation 'io.spicelabs:annatto:0.0.1-SNAPSHOT'
```

Annatto is discovered automatically by Goat Rodeo via Java's `ServiceLoader` mechanism. Add Annatto to your classpath and the `AnnattoComponent` will be loaded during startup.

---

## Supported Ecosystems

| Ecosystem | Archive Format | PURL Type | Metadata Source |
|-----------|---------------|-----------|-----------------|
| **npm** | `.tgz` | `pkg:npm` | `package.json` |
| **PyPI** | `.tar.gz` / `.whl` | `pkg:pypi` | `METADATA` / `PKG-INFO` |
| **Go Modules** | `.zip` | `pkg:golang` | `go.mod` |
| **Crates.io** | `.crate` | `pkg:cargo` | `Cargo.toml` |
| **RubyGems** | `.gem` | `pkg:gem` | `metadata.gz` (YAML) |
| **Packagist** | `.zip` | `pkg:composer` | `composer.json` |
| **Conda** | `.tar.bz2` / `.conda` | `pkg:conda` | `index.json` / `about.json` |
| **CocoaPods** | `.podspec.json` | `pkg:cocoapods` | JSON podspec |
| **CPAN** | `.tar.gz` | `pkg:cpan` | `META.json` / `META.yml` |
| **Hex** | `.tar` | `pkg:hex` | `metadata.config` (Erlang terms) |
| **LuaRocks** | `.src.rock` / `.rockspec` | `pkg:luarocks` | Lua rockspec |

---

## Features

- **11 ecosystems**: Comprehensive coverage of major package registries
- **Unified metadata model**: All ecosystems normalize to the same `MetadataResult` structure (name, version, description, license, publisher, dependencies, publication date)
- **Package URL generation**: Standards-compliant PURLs with ecosystem-specific qualifiers
- **Auto-detection**: `AnnattoProcessFilter` routes artifacts to the correct handler by file extension
- **Custom parsers**: Purpose-built Erlang term parser and Lua subset evaluator — no native dependencies
- **Source-of-truth testing**: Every ecosystem validated against 50 real packages extracted by native tools in Docker
- **Security protections**: Path traversal rejection, file size limits, decompression bounds
- **Thread-safe**: Immutable records, stateless extractors, memento pattern for handler state

---

## Security

Annatto includes security protections for parsing untrusted package archives:

- **Path traversal rejection**: Archive entries containing `..` are rejected
- **File size limits**: 10 MB per-entry limit (1 MB for Lua rockspecs)
- **Token limits**: Lua tokenizer (50,000 tokens) and Erlang tokenizer (50,000 tokens)
- **Nesting depth limits**: Lua table depth 20, Erlang term depth 10

See [SECURITY.md](SECURITY.md) for vulnerability reporting.

---

## Maintainers

### Build Locally

Install JDK 21+ and Maven 3.9+.

Clone the repo:

```bash
git clone https://github.com/spice-labs-inc/annatto.git
cd annatto
```

Build with Maven:

```bash
mvn clean install
```

Run tests only:

```bash
mvn test
```

Check test coverage (report in `target/site/jacoco/`):

```bash
mvn test jacoco:report
```

### Test Corpus

Annatto validates each ecosystem against 50 real packages with source-of-truth metadata extracted by native tools running in Docker. The test corpus is downloaded on demand and gitignored. Expected JSON files are checked into `src/test/resources/`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on adding a new ecosystem.

---

### Releasing

1. **Create a GitHub Release**
   Use a tag like `v0.1.0`. This triggers GitHub Actions to:

   - Build the JAR
   - Publish to GitHub Packages
   - Upload artifacts to Maven Central

2. **Monitor Maven Central** (optional)
   Visit [https://central.sonatype.com](https://central.sonatype.com) — Deployments
   Propagation takes ~40 minutes.

3. **Verify the JAR**

```bash
mvn dependency:get \
  -Dartifact=io.spicelabs:annatto:0.1.0
```

---

## Repository

Maintained by [Spice Labs](https://github.com/spice-labs-inc).

- [`annatto`](https://github.com/spice-labs-inc/annatto) — this plugin (package metadata extraction)
- [`saffron`](https://github.com/spice-labs-inc/saffron) — Java library for reading VM disk images and filesystems
- [`baharat`](https://github.com/spice-labs-inc/baharat) — Java library for reading Linux and BSD package files

---

## References

### Ecosystem Specifications

- [npm package.json](https://docs.npmjs.com/cli/v10/configuring-npm/package-json)
- [PyPI Core Metadata](https://packaging.python.org/en/latest/specifications/core-metadata/)
- [Go Module Reference](https://go.dev/ref/mod)
- [Cargo Manifest Format](https://doc.rust-lang.org/cargo/reference/manifest.html)
- [RubyGems Specification](https://guides.rubygems.org/specification-reference/)
- [Composer Schema](https://getcomposer.org/doc/04-schema.md)
- [Conda Package Specification](https://docs.conda.io/projects/conda/en/latest/user-guide/concepts/packages.html)
- [CocoaPods Podspec Syntax](https://guides.cocoapods.org/syntax/podspec.html)
- [CPAN::Meta::Spec](https://metacpan.org/pod/CPAN::Meta::Spec)
- [Hex Package Format](https://hex.pm/docs/specification)
- [LuaRocks Rockspec Format](https://github.com/luarocks/luarocks/wiki/Rockspec-format)

### Standards

- [Package URL Specification](https://github.com/package-url/purl-spec)

---

## License

Licensed under the [Apache License 2.0](LICENSE).

Copyright 2026 Spice Labs, Inc.
