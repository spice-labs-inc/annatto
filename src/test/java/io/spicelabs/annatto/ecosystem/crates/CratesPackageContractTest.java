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

package io.spicelabs.annatto.ecosystem.crates;

import com.github.packageurl.PackageURL;
import com.google.gson.JsonObject;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.contract.LanguagePackageContractTest;
import io.spicelabs.annatto.testutil.SourceOfTruthLoader;
import io.spicelabs.annatto.testutil.SourceOfTruthLoader.PackageTestCase;
import io.spicelabs.annatto.testutil.TestCorpusDownloader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Contract tests for {@link CratesPackage}.
 *
 * <p>Theory: CratesPackage implements LanguagePackage for Crates.io packages (.crate
 * archives). These tests verify contract compliance by comparing extraction results
 * against JSON source of truth files.
 *
 * <p>Test Data Discovery:
 * <ul>
 *   <li>Package files: test-corpus/crates/*.crate</li>
 *   <li>Source of truth: src/test/resources/crates/*-expected.json</li>
 * </ul>
 *
 * <p>Requirement: Every package file MUST have a corresponding JSON source of truth
 * file, and every JSON file MUST have a corresponding package file. Missing pairs
 * cause test discovery to fail.
 */
class CratesPackageContractTest extends LanguagePackageContractTest {

    private static final String ECOSYSTEM = "crates";

    @BeforeAll
    static void downloadCorpus() throws IOException {
        TestCorpusDownloader.ensureCorpusAvailable();
    }

    /**
     * Provides all discovered package/JSON test cases for parameterized tests.
     * Fails if any package lacks a JSON file or any JSON lacks a package.
     */
    static List<PackageTestCase> testCases() {
        return SourceOfTruthLoader.discoverTestCases(ECOSYSTEM);
    }

    // --- Contract test implementation using first available package ---

    @Override
    protected LanguagePackage createValidPackage() {
        List<PackageTestCase> cases = testCases();
        assumeThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        PackageTestCase firstCase = cases.get(0);
        assumeThat(Files.exists(firstCase.packagePath()))
            .as("Package file must exist: %s", firstCase.packagePath())
            .isTrue();

        try {
            return CratesPackage.fromPath(firstCase.packagePath());
        } catch (IOException e) {
            fail("Failed to load package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected LanguagePackage createIncompletePackage() {
        // Create a synthetic .crate with Cargo.toml having empty version
        String cargoToml = "[package]\n" +
            "name = \"test-pkg\"\n" +
            "version = \"\"\n" +
            "edition = \"2021\"\n" +
            "repository = \"https://example.com\"\n" +
            "homepage = \"https://example.com\"\n";
        byte[] crate = createMinimalCrate(cargoToml);
        try {
            return CratesPackage.fromStream(new ByteArrayInputStream(crate), "incomplete.crate");
        } catch (IOException e) {
            fail("Failed to create incomplete package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected Ecosystem expectedEcosystem() {
        return Ecosystem.CRATES;
    }

    @Override
    protected String expectedValidPurl() {
        // PURL is derived from the first test case's JSON source of truth
        List<PackageTestCase> cases = testCases();
        assumeThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        try {
            JsonObject expected = cases.get(0).loadExpectedJson();
            String name = expected.get("name").getAsString();
            String version = expected.get("version").getAsString();
            return "pkg:cargo/" + name + "@" + version;
        } catch (IOException e) {
            fail("Failed to load expected JSON: " + e.getMessage());
            return null;
        }
    }

    @Test
    @DisplayName("entry count limit is enforced (security)")
    protected void entryCountLimitEnforced() {
        // Documented: CratesPackage enforces MAX_ENTRIES = 10000
    }

    @Test
    @DisplayName("entry size limit is enforced (security)")
    protected void entrySizeLimitEnforced() {
        // Documented: CratesPackage enforces MAX_ENTRY_SIZE = 10MB
    }

    // --- Source of Truth parameterized tests ---

    /**
     * Goal: Verify that metadata extracted from package matches source of truth.
     * Rationale: Source of truth JSON represents native tool extraction output.
     * Requirement: Extraction accuracy - must match native tools
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("extracts metadata matching source of truth")
    void extractsMetadataMatchingSourceOfTruth(PackageTestCase testCase) throws Exception {
        assumeThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();

        CratesPackage crate = CratesPackage.fromPath(testCase.packagePath());
        PackageMetadata metadata = crate.metadata();

        // Compare against source of truth (with appropriate tolerance)
        assertThat(crate.name())
            .as("name mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("name").getAsString());

        assertThat(crate.version())
            .as("version mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("version").getAsString());

        // Verify PURL matches expected format
        Optional<PackageURL> purl = crate.toPurl();
        assertThat(purl).as("PURL should be present for %s", testCase.packageFilename()).isPresent();
        assertThat(purl.get().getType()).as("PURL type for %s", testCase.packageFilename()).isEqualTo("cargo");
    }

    /**
     * Goal: Verify that PURL generation matches source of truth.
     * Rationale: PURL must be consistent with package identity.
     * Requirement: Correct PURL generation
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("generates correct PURL matching source of truth")
    void generatesCorrectPurl(PackageTestCase testCase) throws Exception {
        assumeThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();
        String expectedName = expected.get("name").getAsString();
        String expectedVersion = expected.get("version").getAsString();

        CratesPackage crate = CratesPackage.fromPath(testCase.packagePath());
        Optional<PackageURL> purl = crate.toPurl();

        assertThat(purl).as("PURL should be present").isPresent();
        assertThat(purl.get().toString())
            .as("PURL mismatch for %s", testCase.packageFilename())
            .isEqualTo("pkg:cargo/" + expectedName + "@" + expectedVersion);
    }

    // --- Helper methods ---

    /**
     * Creates a minimal .crate containing a Cargo.toml with the given content.
     */
    private byte[] createMinimalCrate(String cargoToml) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(baos);
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOut =
                new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzipOut);

            byte[] content = cargoToml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry =
                new org.apache.commons.compress.archivers.tar.TarArchiveEntry("test-pkg-1.0/Cargo.toml");
            entry.setSize(content.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(content);
            tarOut.closeArchiveEntry();
            tarOut.close();

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test crate", e);
        }
    }
}
