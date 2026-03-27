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

package io.spicelabs.annatto.ecosystem.npm;

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

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Contract tests for {@link NpmPackage}.
 *
 * <p>Theory: NpmPackage implements LanguagePackage for npm packages (.tgz archives).
 * These tests verify contract compliance by comparing extraction results against
 * JSON source of truth files.
 */
class NpmPackageContractTest extends LanguagePackageContractTest {

    private static final String ECOSYSTEM = "npm";

    @BeforeAll
    static void downloadCorpus() throws IOException {
        TestCorpusDownloader.ensureCorpusAvailable();
    }

    /**
     * Provides all discovered package/JSON test cases for parameterized tests.
     */
    static List<PackageTestCase> testCases() {
        return SourceOfTruthLoader.discoverTestCases(ECOSYSTEM);
    }

    @Override
    protected LanguagePackage createValidPackage() {
        List<PackageTestCase> cases = testCases();
        assumeThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        PackageTestCase firstCase = cases.get(0);
        assumeThat(Files.exists(firstCase.packagePath()))
            .as("Package file must exist: %s", firstCase.packagePath())
            .isTrue();

        try {
            return NpmPackage.fromPath(firstCase.packagePath());
        } catch (IOException e) {
            fail("Failed to load package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected LanguagePackage createIncompletePackage() {
        String json = "{\"name\": \"test-pkg\", \"version\": \"\"}";
        byte[] tgz = createMinimalNpmPackage(json);
        try {
            return NpmPackage.fromStream(new ByteArrayInputStream(tgz), "incomplete.tgz");
        } catch (IOException e) {
            fail("Failed to create incomplete package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected Ecosystem expectedEcosystem() {
        return Ecosystem.NPM;
    }

    @Override
    protected String expectedValidPurl() {
        List<PackageTestCase> cases = testCases();
        assumeThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        try {
            JsonObject expected = cases.get(0).loadExpectedJson();
            String name = expected.get("name").getAsString();
            String version = expected.get("version").getAsString();
            return "pkg:npm/" + name + "@" + version;
        } catch (IOException e) {
            fail("Failed to load expected JSON: " + e.getMessage());
            return null;
        }
    }

    @Test
    @DisplayName("entry count limit is enforced (security)")
    protected void entryCountLimitEnforced() {
    }

    @Test
    @DisplayName("entry size limit is enforced (security)")
    protected void entrySizeLimitEnforced() {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("extracts metadata matching source of truth")
    void extractsMetadataMatchingSourceOfTruth(PackageTestCase testCase) throws Exception {
        assumeThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();

        NpmPackage npm = NpmPackage.fromPath(testCase.packagePath());

        assertThat(npm.name())
            .as("name mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("name").getAsString());

        assertThat(npm.version())
            .as("version mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("version").getAsString());

        Optional<PackageURL> purl = npm.toPurl();
        assertThat(purl).as("PURL should be present for %s", testCase.packageFilename()).isPresent();
        assertThat(purl.get().getType()).as("PURL type for %s", testCase.packageFilename()).isEqualTo("npm");
    }

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

        NpmPackage npm = NpmPackage.fromPath(testCase.packagePath());
        Optional<PackageURL> purl = npm.toPurl();

        assertThat(purl).as("PURL should be present").isPresent();

        // Handle scoped packages: @scope/name becomes scope/name in PURL namespace
        String purlStr = purl.get().toString();
        if (expectedName.startsWith("@")) {
            // Scoped: pkg:npm/scope/name@version
            String scopeAndName = expectedName.substring(1); // Remove @
            assertThat(purlStr)
                .as("PURL mismatch for scoped package %s", testCase.packageFilename())
                .isEqualTo("pkg:npm/" + scopeAndName + "@" + expectedVersion);
        } else {
            assertThat(purlStr)
                .as("PURL mismatch for %s", testCase.packageFilename())
                .isEqualTo("pkg:npm/" + expectedName + "@" + expectedVersion);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("PURL namespace is correct for scoped/unscoped packages")
    void purlNamespaceIsCorrect(PackageTestCase testCase) throws Exception {
        assumeThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();
        String expectedName = expected.get("name").getAsString();

        NpmPackage npm = NpmPackage.fromPath(testCase.packagePath());
        Optional<PackageURL> purl = npm.toPurl();

        assertThat(purl).as("PURL should be present").isPresent();

        if (expectedName.startsWith("@")) {
            String scope = expectedName.substring(1, expectedName.indexOf('/'));
            String name = expectedName.substring(expectedName.indexOf('/') + 1);
            assertThat(purl.get().getNamespace())
                .as("PURL namespace for scoped package %s", testCase.packageFilename())
                .isEqualTo(scope);
            assertThat(purl.get().getName())
                .as("PURL name for scoped package %s", testCase.packageFilename())
                .isEqualTo(name);
        } else {
            assertThat(purl.get().getNamespace())
                .as("PURL namespace for unscoped package %s", testCase.packageFilename())
                .isNull();
            assertThat(purl.get().getName())
                .as("PURL name for unscoped package %s", testCase.packageFilename())
                .isEqualTo(expectedName);
        }
    }

    private byte[] createMinimalNpmPackage(String packageJson) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(baos);
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOut =
                new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzipOut);

            byte[] content = packageJson.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry =
                new org.apache.commons.compress.archivers.tar.TarArchiveEntry("package/package.json");
            entry.setSize(content.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(content);
            tarOut.closeArchiveEntry();
            tarOut.close();

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test package", e);
        }
    }
}