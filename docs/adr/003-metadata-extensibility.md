# ADR-003: Metadata Extensibility

## Status
Proposed (Phase 0)

## Context
Different ecosystems have unique metadata fields. A fixed schema would require API changes for each new ecosystem. We need a balance between standardization (common fields) and flexibility (ecosystem-specific data).

## Decision
Use a hybrid approach: standard fields for common data, extensible raw map for ecosystem-specific fields.

### Standard Fields (all ecosystems)
- `name`: String (always present, may be empty)
- `version`: String (always present, may be empty)
- `description`: Optional<String>
- `license`: Optional<String>
- `publisher`: Optional<String>
- `publishedAt`: Optional<Instant>
- `dependencies`: List<Dependency>

### Raw Map (ecosystem-specific)
- Access via `PackageMetadata.raw()`
- Immutable Map<String, Object>
- Contains fields not in standard set

### Example
```java
PackageMetadata meta = pkg.metadata();
String name = meta.name(); // Standard field
Optional<String> license = meta.license(); // Standard field

// Ecosystem-specific
Map<String, Object> raw = meta.raw();
String crateType = (String) raw.get("crate_type"); // Rust-specific
```

### Consequences
- **Positive**: Stable API for common use cases, extensible for special cases
- **Negative**: Raw map is untyped - requires casting
- **Mitigation**: Document common raw keys per ecosystem

## Claims
- Standard fields never null (test: LanguagePackageContractTest.nameNeverNull)
- Raw map is immutable (test: LanguagePackageContractTest.metadataRawMapImmutable)
- Dependencies list is immutable (test: LanguagePackageContractTest.metadataDependenciesImmutable)

## LLM Context
For LLM code generation: Prefer standard fields when available. Only use raw() for ecosystem-specific data. Document raw keys in ecosystem's package-info.java.
