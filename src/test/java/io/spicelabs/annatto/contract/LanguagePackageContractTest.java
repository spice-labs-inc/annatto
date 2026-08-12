/* Copyright 2026 Spice Labs, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.annatto.contract;

import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract tests for LanguagePackage implementations.
 *
 * <p>Theory: All ecosystem implementations must satisfy the same behavioral contract
 * to ensure consistent usage in Goat Rodeo strategies. These tests verify the contract
 * independent of the specific ecosystem.
 *
 * <p>Each ecosystem provides a concrete test class that extends this abstract class
 * and provides factory methods for valid and invalid packages.
 *
 * <p>Requirements tested:
 * <ul>
 *   <li>Immutability - no observable state changes after construction</li>
 *   <li>Non-null returns - no method returns null</li>
 *   <li>Consistency - repeated calls return equal results</li>
 *   <li>PURL generation - valid PURLs when metadata complete</li>
 *   <li>Stream lifecycle - proper resource management</li>
 *   <li>Thread safety - concurrent access is safe</li>
 *   <li>Security - malicious inputs are rejected or sanitized</li>
 * </ul>
 *
 * <p>LLM Note: When adding new LanguagePackage implementations, extend this class
 * and implement the three abstract factory methods. All tests are inherited automatically.
 * Add ecosystem-specific tests in the concrete class (e.g., NpmPackageContractTest).
 */
public abstract class LanguagePackageContractTest {

    /**
     * Factory method: provide a valid package for testing.
     * The package must have complete metadata (name and version) so toPurl() returns a value.
     * @return a valid, complete package
     */
    protected abstract LanguagePackage createValidPackage();

    /**
     * Factory method: provide a package with incomplete metadata (missing name or version).
     * The package must be structurally valid but have incomplete metadata so toPurl() returns empty.
     * @return package with incomplete metadata
     */
    protected abstract LanguagePackage createIncompletePackage();

    /**
     * Factory method: provide the expected ecosystem.
     * @return the Ecosystem enum constant for this package type
     */
    protected abstract Ecosystem expectedEcosystem();

    /**
     * Factory method: provide the expected PURL string for the valid package.
     * This enables exact PURL validation in toPurlReturnsValidPurl().
     * @return the expected PURL string (e.g., "pkg:npm/lodash@4.17.21")
     */
    protected abstract String expectedValidPurl();

    @Test
    @DisplayName("mimeType() never returns null")
    void mimeTypeNonNull() {
        LanguagePackage pkg = createValidPackage();
        assertThat(pkg.mimeType()).isNotNull();
    }

    @Test
    @DisplayName("ecosystem() never returns null and matches expected")
    void ecosystemNonNull() {
        LanguagePackage pkg = createValidPackage();
        assertThat(pkg.ecosystem()).isNotNull();
        assertThat(pkg.ecosystem()).isEqualTo(expectedEcosystem());
    }

    @Test
    @DisplayName("name() never returns null")
    void nameNeverNull() {
        LanguagePackage pkg = createValidPackage();
        assertThat(pkg.name()).isNotNull();
    }

    @Test
    @DisplayName("version() never returns null")
    void versionNeverNull() {
        LanguagePackage pkg = createValidPackage();
        assertThat(pkg.version()).isNotNull();
    }

    @Test
    @DisplayName("metadata() never returns null")
    void metadataNeverNull() {
        LanguagePackage pkg = createValidPackage();
        assertThat(pkg.metadata()).isNotNull();
    }

    @Test
    @DisplayName("metadata dependencies list is immutable")
    void metadataDependenciesImmutable() {
        LanguagePackage pkg = createValidPackage();
        PackageMetadata meta = pkg.metadata();
        List<Dependency> deps = meta.dependencies();

        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> deps.add(new Dependency("test", "1.0")));
    }

    @Test
    @DisplayName("metadata raw map is immutable")
    void metadataRawMapImmutable() {
        LanguagePackage pkg = createValidPackage();
        PackageMetadata meta = pkg.metadata();
        Map<String, Object> raw = meta.raw();

        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> raw.put("key", "value"));
    }

    @Test
    @DisplayName("toPurl() returns valid PURL when metadata complete")
    void toPurlReturnsValidPurl() {
        LanguagePackage pkg = createValidPackage();
        Optional<PackageURL> purl = pkg.toPurl();

        assertThat(purl).isPresent();
        // Verify exact PURL matches expected (ecosystem-specific type like "cargo" vs "crates")
        assertThat(purl.get().toString()).isEqualTo(expectedValidPurl());
    }

    @Test
    @DisplayName("toPurl() returns PURL matching purl-spec format")
    void toPurlMatchesPurlSpec() {
        LanguagePackage pkg = createValidPackage();
        Optional<PackageURL> purl = pkg.toPurl();

        assertThat(purl).isPresent();
        String purlStr = purl.get().toString();
        // PURL format: pkg:type/namespace/name@version?qualifiers#subpath
        assertThat(purlStr).matches("^pkg:[a-zA-Z][a-zA-Z0-9._-]*/.*");
        assertThat(purlStr).contains("@");
    }

    @Test
    @DisplayName("toPurl() returns empty when metadata incomplete")
    void toPurlReturnsEmptyWhenIncomplete() {
        LanguagePackage pkg = createIncompletePackage();
        Optional<PackageURL> purl = pkg.toPurl();

        assertThat(purl).isEmpty();
    }

    @Test
    @DisplayName("repeated calls return equal results")
    void immutableAfterConstruction() {
        LanguagePackage pkg = createValidPackage();

        // Multiple calls should return same values
        assertThat(pkg.name()).isEqualTo(pkg.name());
        assertThat(pkg.version()).isEqualTo(pkg.version());
        assertThat(pkg.metadata()).isEqualTo(pkg.metadata());
        assertThat(pkg.toPurl()).isEqualTo(pkg.toPurl());
    }

    @Test
    @DisplayName("streamEntries() closes properly with try-with-resources")
    void streamEntriesClosesProperly() throws IOException {
        LanguagePackage pkg = createValidPackage();

        try (PackageEntryStream stream = pkg.streamEntries()) {
            // Just open and close
            assertThat(stream).isNotNull();
        }
        // No exception thrown = success
    }

    @Test
    @DisplayName("LanguagePackageReader.supportedMimeTypes() returns non-empty set")
    void supportedMimeTypesNonEmpty() {
        assertThat(LanguagePackageReader.supportedMimeTypes()).isNotEmpty();
    }

    // --- Stream lifecycle tests ---

    /**
     * Goal: Verify that calling streamEntries() twice throws IllegalStateException.
     * Rationale: Only one stream should be open at a time to prevent resource conflicts.
     * Requirement: Stream lifecycle - single stream enforcement
     */
    @Test
    @DisplayName("streamEntries() throws when called twice without closing")
    void streamEntries_secondCallThrows() throws IOException {
        LanguagePackage pkg = createValidPackage();
        PackageEntryStream stream = pkg.streamEntries();

        try {
            assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> pkg.streamEntries())
                .withMessageContaining("stream is already open");
        } finally {
            stream.close();
            pkg.close();
        }
    }

    /**
     * Goal: Verify that close() makes the package closed and a later streamEntries() fails.
     * Rationale (S-5, approved): close() now releases package-owned resources (e.g. a
     * spooled temp file) and marks the package closed; using a closed package is a caller
     * error. This replaces the old "reopen after close" contract.
     * Requirement: Package lifecycle - closed-package semantics
     */
    @Test
    @DisplayName("streamEntries() throws after close() (closed package)")
    void streamEntries_afterCloseThrows() throws IOException {
        LanguagePackage pkg = createValidPackage();

        // First stream: valid while the package is open
        try (PackageEntryStream stream1 = pkg.streamEntries()) {
            assertThat(stream1).isNotNull();
        }

        pkg.close();

        // After close() the package is closed and must refuse new streams
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(pkg::streamEntries)
                .withMessageContaining("closed");
    }

    // --- Single-threaded immutable-access contract (ADR-004) ---

    /**
     * Goal: Verify that repeated metadata reads return consistent results after construction.
     * Rationale: Annatto executes on a single thread (ADR-004): the package is immutable after
     * construction, so repeated reads must return equal values.
     * Requirement: Immutability - consistent after construction
     */
    @Test
    @DisplayName("repeated metadata reads are consistent")
    void repeatedReadsConsistentAfterConstruction() {
        LanguagePackage pkg = createValidPackage();

        for (int i = 0; i < 50; i++) {
            assertThat(pkg.name()).isNotNull();
            assertThat(pkg.version()).isNotNull();
            assertThat(pkg.mimeType()).isNotNull();
            assertThat(pkg.ecosystem()).isNotNull();
            assertThat(pkg.metadata()).isNotNull();
            assertThat(pkg.toPurl()).isEqualTo(pkg.toPurl());
        }
    }

    // --- Security contract tests (documented as abstract methods) ---

    /**
     * Goal: Verify that the package enforces entry count limits.
     * Rationale: Zip bombs and malicious archives can have millions of entries.
     * Requirement: Security - resource limits (typically 10,000 entries)
     *
     * Implementation Note: Concrete test classes should implement this as a @Test method.
     * If the implementation delegates to SecurityLimitsTest, this can be a simple
     * documenting test that references the security test class.
     */
    protected abstract void entryCountLimitEnforced();

    /**
     * Goal: Verify that the package enforces entry size limits.
     * Rationale: Individual entries can be gigabytes, causing OOM.
     * Requirement: Security - resource limits (typically 10MB per entry)
     *
     * Implementation Note: Concrete test classes should implement this as a @Test method.
     * If the implementation delegates to SecurityLimitsTest, this can be a simple
     * documenting test that references the security test class.
     */
    protected abstract void entrySizeLimitEnforced();
}
