# Contributing to Annatto

Thank you for your interest in contributing to Annatto! This document provides guidelines and instructions for contributing.

## How to Contribute

### Reporting Bugs

Before creating a bug report, please check existing issues to avoid duplicates. When creating a bug report, include:

- **Clear title** describing the issue
- **Steps to reproduce** the behavior
- **Expected behavior** vs actual behavior
- **Environment details** (Java version, OS, package file details if relevant)
- **Sample package file** if the issue is ecosystem-specific (ensure it doesn't contain sensitive data)

### Suggesting Features

Feature suggestions are welcome! Please include:

- **Use case** - Why is this feature needed?
- **Proposed solution** - How should it work?
- **Alternatives considered** - What other approaches did you consider?

### Pull Requests

1. **Fork the repository** and create your branch from `main`
2. **Write tests** for any new functionality
3. **Follow the code style** (see below)
4. **Update documentation** if needed
5. **Ensure tests pass** with `mvn test`
6. **Submit the pull request** with a clear description

## Development Setup

### Prerequisites

- Java 21 or higher
- Maven 3.9+
- Git

### Building

```bash
# Clone your fork
git clone https://github.com/YOUR_USERNAME/annatto.git
cd annatto

# Build and run tests
mvn clean install

# Run tests only
mvn test

# Generate coverage report
mvn test jacoco:report
# Report is in target/site/jacoco/index.html
```

### Running Specific Tests

```bash
# Run a specific test class
mvn test -Dtest=NpmMetadataExtractorTest

# Run tests matching a pattern
mvn test -Dtest="*Handler*"
```

## Code Style

### General Guidelines

- Use **4 spaces** for indentation (no tabs)
- Maximum line length: **120 characters**
- Use **meaningful names** for variables, methods, and classes
- Write **self-documenting code**; add comments only when necessary
- Follow **Java naming conventions**

### Null Safety

This project enforces a strict no-null policy:

- Use `@NotNull` annotation on all parameters that must not be null
- Use `Optional<T>` for return values that may be absent
- Never return `null` from any method
- Use `@Nullable` only for methods that interact with external APIs

```java
// Good
public @NotNull Optional<String> getName() {
    return Optional.ofNullable(metadata.getString("name"));
}

// Bad
public String getName() {
    return metadata.getString("name"); // Could return null!
}
```

### Immutability

- Prefer immutable objects (records, final fields)
- Use `List.copyOf()` and `Map.copyOf()` for defensive copies
- Don't expose mutable internal state

```java
// Good
public record ParsedDependency(@NotNull String name, @NotNull String scope) {}

// Good - defensive copy
public @NotNull List<ParsedDependency> dependencies() {
    return List.copyOf(dependencies);
}
```

### Error Handling

- Use `AnnattoException` and its subclasses for package-related errors
- Use specific exceptions when appropriate (e.g., `MalformedPackageException`, `MetadataExtractionException`)
- Include helpful error messages with context
- Don't catch generic `Exception` unless re-throwing

```java
// Good
throw new MalformedPackageException(
    "Expected Cargo.toml in crate archive, found none");

// Good - ecosystem-specific context
throw new MetadataExtractionException(
    "Failed to parse metadata.config: invalid Erlang term at position " + pos);

// Bad
throw new RuntimeException("Parse error");
```

### Testing

- Write unit tests for all new functionality
- Use descriptive test method names
- Test edge cases and error conditions
- Aim for 80%+ code coverage

```java
@Test
void extractName_matchesSourceOfTruth() {
    // Arrange
    Path packageFile = testCorpus.resolve("express-4.18.2.tgz");

    // Act
    MetadataResult result = NpmMetadataExtractor.extract(packageFile);

    // Assert
    assertThat(result.name()).isEqualTo("express");
    assertThat(result.version()).isEqualTo("4.18.2");
}

@Test
void extract_withCorruptedArchive_throwsMalformedPackageException() {
    Path corrupted = testCorpus.resolve("corrupted.tgz");

    assertThatThrownBy(() -> NpmMetadataExtractor.extract(corrupted))
        .isInstanceOf(MalformedPackageException.class)
        .hasMessageContaining("package.json");
}
```

### Documentation

- Add Javadoc to all public classes and methods
- Include `@param`, `@return`, and `@throws` tags
- Provide usage examples for complex APIs

```java
/**
 * Extracts metadata from an npm package archive.
 *
 * <p>Example usage:
 * <pre>{@code
 * MetadataResult result = NpmMetadataExtractor.extract(path);
 * System.out.println(result.name());
 * }</pre>
 *
 * @param packagePath the path to the .tgz package file
 * @return the extracted metadata
 * @throws MalformedPackageException if the archive lacks a package.json
 * @throws MetadataExtractionException if an I/O error occurs
 */
public static @NotNull MetadataResult extract(@NotNull Path packagePath)
        throws MalformedPackageException, MetadataExtractionException {
    // ...
}
```

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

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed architecture documentation.

## Commit Messages

Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation only
- `style`: Code style (formatting, no code change)
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `test`: Adding or correcting tests
- `chore`: Maintenance tasks

Examples:
```
feat(hex): add Erlang term parser for metadata.config

fix(pypi): handle missing License-Expression header gracefully

docs: update ecosystem table in README

test(conda): add tests for .conda zstd format
```

## Release Process

Releases are managed by maintainers. The process is:

1. Update version in `pom.xml`
2. Create a release branch
3. Run full test suite
4. Create GitHub release with tag
5. Deploy to Maven Central

## Getting Help

- **Questions**: Open a GitHub Discussion
- **Bugs**: Open a GitHub Issue
- **Security**: Email security@spicelabs.io (do not open public issues)

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.
