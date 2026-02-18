# Contributing to Annatto

## Code Style

- 4-space indent, 120-character line limit
- Google Java Style as baseline
- `@NotNull` on all method parameters and return values that must not be null
- `Optional<T>` for all values that may be absent - never return `null`

## TDD Process

1. Write tests first comparing against source-of-truth JSON
2. Implement extraction logic to make tests pass
3. Verify all tests pass with `mvn test`
4. Check coverage with `mvn test jacoco:report`

## Adding a New Ecosystem

1. Create package under `io.spicelabs.annatto.<ecosystem>/`
2. Implement `<E>Handler.java` extending `BaseArtifactHandler`
3. Implement `<E>Memento.java` extending `BaseMemento`
4. Implement `<E>MetadataExtractor.java` as stateless extractor
5. Implement `<E>Marker.java` implementing `RodeoItemMarker`
6. Document quirks in `<E>Quirks.java`
7. Add `package-info.java`
8. Register handler in `AnnattoComponent.importAPIFactories()`
9. Add extension detection in `AnnattoProcessFilter`
10. Add PURL builder method in `PurlBuilder`
11. Create Docker container in `docker/<ecosystem>/`
12. Select 50+ test packages covering edge cases
13. Write tests comparing against source-of-truth
14. Update README.md ecosystem table
15. Update ARCHITECTURE.md

## Commit Messages

Use conventional commits:
- `feat(npm): implement metadata extraction from package.json`
- `test(pypi): add sdist/wheel comparison tests`
- `fix(crates): handle renamed dependencies in Cargo.toml`
- `docs: update ecosystem table in README`

## Null Safety Rules

- Never return `null` from any public method
- Use `@NotNull` annotations on all parameters and returns that cannot be null
- Use `Optional<T>` for values that may be absent
- Throw `MalformedPackageException` for invalid package structure
- Throw `UnsupportedOperationException("Not yet implemented: <detail>")` for stubs
