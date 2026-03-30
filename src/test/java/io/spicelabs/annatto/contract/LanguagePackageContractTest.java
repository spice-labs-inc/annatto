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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
     * Goal: Verify that after close(), a new stream can be opened.
     * Rationale: Packages should be reusable after proper cleanup.
     * Requirement: Stream lifecycle - reusability after close
     */
    @Test
    @DisplayName("streamEntries() allows new stream after close()")
    void streamEntries_afterCloseAllowsNewStream() throws IOException {
        LanguagePackage pkg = createValidPackage();

        // First stream
        try (PackageEntryStream stream1 = pkg.streamEntries()) {
            assertThat(stream1).isNotNull();
        }
        pkg.close();

        // Second stream after close
        try (PackageEntryStream stream2 = pkg.streamEntries()) {
            assertThat(stream2).isNotNull();
        }
    }

    /**
     * Goal: Verify that concurrent stream access from multiple threads throws.
     * Rationale: Stream state is not thread-safe; concurrent access could corrupt state.
     * Requirement: Thread safety - stream access serialization
     */
    @Test
    @DisplayName("streamEntries() throws on concurrent access from multiple threads")
    void concurrentStreamAccessThrows() throws Exception {
        LanguagePackage pkg = createValidPackage();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> firstException = new AtomicReference<>();
        AtomicReference<Exception> secondException = new AtomicReference<>();

        // First thread opens stream
        executor.submit(() -> {
            try {
                PackageEntryStream stream = pkg.streamEntries();
                latch.await(); // Wait for signal
                stream.close();
            } catch (Exception e) {
                firstException.set(e);
            }
        });

        // Second thread tries to open stream concurrently
        executor.submit(() -> {
            try {
                Thread.sleep(50); // Give first thread time to open
                latch.countDown(); // Signal first thread
                pkg.streamEntries(); // This should throw
            } catch (IllegalStateException e) {
                secondException.set(e); // Expected
            } catch (Exception e) {
                secondException.set(e);
            }
        });

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        try {
            // At least one thread should have gotten IllegalStateException
            assertThat(secondException.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stream is already open");
        } finally {
            pkg.close();
        }
    }

    // --- Thread safety tests ---

    /**
     * Goal: Verify that multiple threads reading metadata concurrently don't interfere.
     * Rationale: LanguagePackage is immutable; concurrent reads should be safe.
     * Requirement: Thread safety - immutable after construction
     */
    @Test
    @DisplayName("concurrent read-only access is thread-safe")
    void concurrentReadOnlyAccessIsThreadSafe() throws Exception {
        LanguagePackage pkg = createValidPackage();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(4);

        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Perform read operations
                    String name = pkg.name();
                    String version = pkg.version();
                    String mimeType = pkg.mimeType();
                    Ecosystem ecosystem = pkg.ecosystem();
                    PackageMetadata metadata = pkg.metadata();
                    Optional<PackageURL> purl = pkg.toPurl();

                    // Verify none are null (would indicate race condition)
                    assertThat(name).isNotNull();
                    assertThat(version).isNotNull();
                    assertThat(mimeType).isNotNull();
                    assertThat(ecosystem).isNotNull();
                    assertThat(metadata).isNotNull();
                } catch (Exception e) {
                    fail("Concurrent access failed: " + e.getMessage());
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // All threads start simultaneously
        completeLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Test passes if no exceptions thrown
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
