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

package io.spicelabs.annatto;

import io.spicelabs.annatto.ecosystem.cocoapods.CocoapodsPackage;
import io.spicelabs.annatto.ecosystem.cpan.CpanPackage;
import io.spicelabs.annatto.ecosystem.go.GoPackage;
import io.spicelabs.annatto.ecosystem.hex.HexPackage;
import io.spicelabs.annatto.ecosystem.luarocks.LuarocksPackage;
import io.spicelabs.annatto.testutil.SourceOfTruthLoader;
import io.spicelabs.annatto.testutil.SourceOfTruthLoader.PackageTestCase;
import io.spicelabs.annatto.testutil.TestCorpusDownloader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Integration tests for {@link LanguagePackageReader}.
 *
 * <p>Theory: LanguagePackageReader is the central router for LanguagePackage creation.
 * It must correctly route files to the appropriate ecosystem implementation based on
 * file extension, MIME type, and content inspection.
 *
 * <p>Requirements Tested:
 * <ul>
 *   <li>Path-based routing for all 11 ecosystems</li>
 *   <li>MIME type-based routing with disambiguation</li>
 *   <li>Stream-based routing with filename hints</li>
 *   <li>Error handling (UnknownFormatException, MalformedPackageException)</li>
 *   <li>MIME type queries (isSupported, supportedMimeTypes)</li>
 *   <li>Ecosystem detection API (detect)</li>
 *   <li>Thread safety for concurrent read calls</li>
 * </ul>
 *
 * <p>Implementation Note: Tests use SourceOfTruthLoader to discover package files
 * from ground truth documents. No filenames are hardcoded - they are loaded from
 * the source of truth JSON files in src/test/resources/<ecosystem>/.
 */
class LanguagePackageReaderIntegrationTest {

    @BeforeAll
    static void downloadCorpus() throws IOException {
        TestCorpusDownloader.ensureCorpusAvailable();
    }

    // --- Path-based routing tests ---

    /**
     * Provides the first available package test case from each ecosystem.
     * Uses ground truth documents to discover actual filenames.
     */
    static Stream<org.junit.jupiter.params.provider.Arguments> ecosystemTestCases() {
        return Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("npm", Ecosystem.NPM, io.spicelabs.annatto.ecosystem.npm.NpmPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("pypi", Ecosystem.PYPI, io.spicelabs.annatto.ecosystem.pypi.PyPIPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("crates", Ecosystem.CRATES, io.spicelabs.annatto.ecosystem.crates.CratesPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("go", Ecosystem.GO, GoPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("rubygems", Ecosystem.RUBYGEMS, io.spicelabs.annatto.ecosystem.rubygems.RubygemsPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("packagist", Ecosystem.PACKAGIST, io.spicelabs.annatto.ecosystem.packagist.PackagistPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("conda", Ecosystem.CONDA, io.spicelabs.annatto.ecosystem.conda.CondaPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("cocoapods", Ecosystem.COCOAPODS, CocoapodsPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("cpan", Ecosystem.CPAN, CpanPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("hex", Ecosystem.HEX, HexPackage.class),
            org.junit.jupiter.params.provider.Arguments.of("luarocks", Ecosystem.LUAROCKS, LuarocksPackage.class)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ecosystemTestCases")
    @DisplayName("read(Path) auto-detects ecosystem from ground truth package")
    void readPath_autoDetectsEcosystem(String ecosystemName, Ecosystem expectedEcosystem, Class<?> expectedClass) throws Exception {
        // Discover test cases from ground truth documents
        List<PackageTestCase> cases = SourceOfTruthLoader.discoverTestCases(ecosystemName);
        assumeThat(cases)
            .as("At least one package/JSON pair must exist for %s", ecosystemName)
            .isNotEmpty();

        PackageTestCase testCase = cases.get(0);
        assumeThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        LanguagePackage result = LanguagePackageReader.read(testCase.packagePath());
        assertThat(result)
            .as("Should create %s package for %s", expectedClass.getSimpleName(), testCase.packageFilename())
            .isInstanceOf(expectedClass);
        assertThat(result.ecosystem())
            .as("Ecosystem should be %s for %s", expectedEcosystem, testCase.packageFilename())
            .isEqualTo(expectedEcosystem);
    }

    // --- MIME type-based routing tests ---

    @Test
    @DisplayName("read(Path, String) uses provided MIME type for routing")
    void readPathWithMimeType_usesProvidedMimeType() throws Exception {
        List<PackageTestCase> cases = SourceOfTruthLoader.discoverTestCases("npm");
        assumeThat(cases).isNotEmpty();

        Path pkg = cases.get(0).packagePath();
        assumeThat(Files.exists(pkg)).isTrue();

        LanguagePackage result = LanguagePackageReader.read(pkg, "application/gzip");
        assertThat(result).isNotNull();
        assertThat(result.ecosystem()).isEqualTo(Ecosystem.NPM);
    }

    @Test
    @DisplayName("unsupported MIME type throws UnknownFormatException")
    void readPath_unsupportedMimeTypeThrowsUnknownFormatException() {
        Path dummy = Path.of("dummy.txt");

        assertThatExceptionOfType(AnnattoException.UnknownFormatException.class)
            .isThrownBy(() -> LanguagePackageReader.read(dummy, "text/plain"))
            .withMessageContaining("Unsupported MIME type");
    }

    @Test
    @DisplayName("non-existent file throws IOException")
    void readPath_nonExistentFileThrowsIOException() {
        Path nonExistent = Path.of("/non/existent/package.tgz");

        assertThatExceptionOfType(IOException.class)
            .isThrownBy(() -> LanguagePackageReader.read(nonExistent));
    }

    // --- Stream-based routing tests ---

    @Test
    @DisplayName("read(InputStream, String, String) uses filename hint for detection")
    void readStream_withFilenameHint() throws Exception {
        List<PackageTestCase> cases = SourceOfTruthLoader.discoverTestCases("npm");
        assumeThat(cases).isNotEmpty();

        Path pkg = cases.get(0).packagePath();
        assumeThat(Files.exists(pkg)).isTrue();

        String filename = pkg.getFileName().toString();
        byte[] data = Files.readAllBytes(pkg);
        try (ByteArrayInputStream stream = new ByteArrayInputStream(data)) {
            LanguagePackage result = LanguagePackageReader.read(stream, filename, "application/gzip");
            assertThat(result).isNotNull();
            assertThat(result.ecosystem()).isEqualTo(Ecosystem.NPM);
        }
    }

    // --- Supported MIME type query tests ---

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "application/gzip",
        "application/x-gzip",
        "application/zip",
        "application/x-tar",
        "application/x-bzip2",
        "application/json",
        "text/x-lua"
    })
    @DisplayName("isSupported returns true for supported MIME types")
    void isSupported_returnsTrueForSupportedTypes(String mimeType) {
        assertThat(LanguagePackageReader.isSupported(mimeType)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "text/plain",
        "image/png",
        "application/pdf",
        "video/mp4"
    })
    @DisplayName("isSupported returns false for unsupported MIME types")
    void isSupported_returnsFalseForUnsupportedTypes(String mimeType) {
        assertThat(LanguagePackageReader.isSupported(mimeType)).isFalse();
    }

    @Test
    @DisplayName("isSupported returns false for null")
    void isSupported_returnsFalseForNull() {
        assertThat(LanguagePackageReader.isSupported(null)).isFalse();
    }

    @Test
    @DisplayName("supportedMimeTypes returns expected set")
    void supportedMimeTypes_returnsExpectedSet() {
        Set<String> supported = LanguagePackageReader.supportedMimeTypes();

        assertThat(supported).containsExactlyInAnyOrder(
            "application/gzip",
            "application/x-gzip",
            "application/zip",
            "application/x-tar",
            "application/x-bzip2",
            "application/json",
            "text/x-lua"
        );
    }

    @Test
    @DisplayName("supportedMimeTypes returns immutable set")
    void supportedMimeTypes_returnsImmutableSet() {
        Set<String> supported = LanguagePackageReader.supportedMimeTypes();

        assertThatExceptionOfType(UnsupportedOperationException.class)
            .isThrownBy(() -> supported.add("text/plain"));
    }

    // --- Ecosystem detection API tests ---

    @Test
    @DisplayName("detect returns ecosystem for supported package")
    void detect_returnsEcosystemForSupportedPackage() throws Exception {
        List<PackageTestCase> cases = SourceOfTruthLoader.discoverTestCases("npm");
        assumeThat(cases).isNotEmpty();

        Path pkg = cases.get(0).packagePath();
        assumeThat(Files.exists(pkg)).isTrue();

        Optional<Ecosystem> result = LanguagePackageReader.detect(pkg);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(Ecosystem.NPM);
    }

    @Test
    @DisplayName("detect returns empty for unsupported file")
    void detect_returnsEmptyForUnsupportedFile() throws Exception {
        Path tempFile = Files.createTempFile("test", ".txt");
        try {
            Optional<Ecosystem> result = LanguagePackageReader.detect(tempFile);
            assertThat(result).isEmpty();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    // --- Thread safety tests ---

    @Test
    @DisplayName("concurrent read calls do not interfere")
    void concurrentReadCallsDoNotInterfere() throws Exception {
        List<PackageTestCase> cases = SourceOfTruthLoader.discoverTestCases("npm");
        assumeThat(cases).isNotEmpty();

        Path pkg = cases.get(0).packagePath();
        assumeThat(Files.exists(pkg)).isTrue();

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(20);
        AtomicInteger successCount = new AtomicInteger(0);
        java.util.List<Exception> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < 20; i++) {
            final int threadNum = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    LanguagePackage result = LanguagePackageReader.read(pkg);
                    if (result != null && result.ecosystem() == Ecosystem.NPM) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.add(new RuntimeException("Thread " + threadNum + " failed: " + e.getMessage(), e));
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        completeLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Print all errors for debugging
        if (!errors.isEmpty()) {
            System.err.println("=== ERRORS during concurrent read ===");
            for (Exception e : errors) {
                System.err.println(e.getMessage());
                e.printStackTrace();
            }
            System.err.println("=== END ERRORS ===");
            fail("Had " + errors.size() + " errors out of 20 threads. First error: " + errors.get(0));
        }

        assertThat(successCount.get()).isEqualTo(20);
    }

    @Test
    @DisplayName("reader methods are reentrant")
    void readerMethodsAreReentrant() {
        // Multiple calls to static query methods should be safe
        for (int i = 0; i < 100; i++) {
            Set<String> supported = LanguagePackageReader.supportedMimeTypes();
            assertThat(supported).isNotEmpty();

            boolean gzipSupported = LanguagePackageReader.isSupported("application/gzip");
            assertThat(gzipSupported).isTrue();
        }
    }
}
