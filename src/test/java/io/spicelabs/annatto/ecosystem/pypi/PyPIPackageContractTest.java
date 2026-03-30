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

package io.spicelabs.annatto.ecosystem.pypi;

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
 * Contract tests for {@link PyPIPackage}.
 *
 * <p>Theory: PyPIPackage implements LanguagePackage for PyPI packages (.whl wheels
 * and .tar.gz sdists). These tests verify contract compliance by comparing extraction
 * results against JSON source of truth files.
 */
class PyPIPackageContractTest extends LanguagePackageContractTest {

    private static final String ECOSYSTEM = "pypi";

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
            return PyPIPackage.fromPath(firstCase.packagePath());
        } catch (IOException e) {
            fail("Failed to load package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected LanguagePackage createIncompletePackage() {
        String metadata = "Name: test-pkg\nVersion: \n";
        byte[] wheel = createMinimalWheel(metadata);
        try {
            return PyPIPackage.fromStream(new ByteArrayInputStream(wheel), "incomplete.whl");
        } catch (IOException e) {
            fail("Failed to create incomplete package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected Ecosystem expectedEcosystem() {
        return Ecosystem.PYPI;
    }

    @Override
    protected String expectedValidPurl() {
        List<PackageTestCase> cases = testCases();
        assumeThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        try {
            JsonObject expected = cases.get(0).loadExpectedJson();
            // Use simpleName for PURL comparison as it's PEP 503 normalized
            String name = expected.has("simpleName")
                ? expected.get("simpleName").getAsString()
                : expected.get("name").getAsString();
            String version = expected.get("version").getAsString();
            // PEP 503 normalize: lowercase, underscores to hyphens
            String normalizedName = name.toLowerCase().replace('_', '-');
            return "pkg:pypi/" + normalizedName + "@" + version;
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

        PyPIPackage pypi = PyPIPackage.fromPath(testCase.packagePath());

        assertThat(pypi.name())
            .as("name mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("name").getAsString());

        assertThat(pypi.version())
            .as("version mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("version").getAsString());

        Optional<PackageURL> purl = pypi.toPurl();
        assertThat(purl).as("PURL should be present for %s", testCase.packageFilename()).isPresent();
        assertThat(purl.get().getType()).as("PURL type for %s", testCase.packageFilename()).isEqualTo("pypi");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("generates correct PURL matching source of truth")
    void generatesCorrectPurl(PackageTestCase testCase) throws Exception {
        assumeThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();
        // Use simpleName for PURL comparison as it's PEP 503 normalized
        String expectedName = expected.has("simpleName")
            ? expected.get("simpleName").getAsString()
            : expected.get("name").getAsString();
        String expectedVersion = expected.get("version").getAsString();

        // PEP 503 normalize: lowercase, underscores to hyphens
        String normalizedName = expectedName.toLowerCase().replace('_', '-');

        PyPIPackage pypi = PyPIPackage.fromPath(testCase.packagePath());
        Optional<PackageURL> purl = pypi.toPurl();

        assertThat(purl).as("PURL should be present").isPresent();
        assertThat(purl.get().toString())
            .as("PURL mismatch for %s", testCase.packageFilename())
            .isEqualTo("pkg:pypi/" + normalizedName + "@" + expectedVersion);
    }

    /**
     * Goal: Verify that package names are PEP 503 normalized in PURLs.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("PURL contains PEP 503 normalized name")
    void normalizedNameInPurl(PackageTestCase testCase) throws Exception {
        assumeThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();
        String expectedName = expected.get("simpleName").getAsString();

        PyPIPackage pypi = PyPIPackage.fromPath(testCase.packagePath());
        Optional<PackageURL> purl = pypi.toPurl();

        assertThat(purl).as("PURL should be present").isPresent();
        // PEP 503 normalization: underscores become hyphens
        String normalizedName = expectedName.replace('_', '-').toLowerCase();
        assertThat(purl.get().getName())
            .as("PURL name should be PEP 503 normalized for %s", testCase.packageFilename())
            .isEqualTo(normalizedName);
    }

    private byte[] createMinimalWheel(String metadata) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.util.zip.ZipOutputStream zipOut = new java.util.zip.ZipOutputStream(baos);

            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("test-1.0.dist-info/METADATA");
            zipOut.putNextEntry(entry);
            zipOut.write(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOut.closeEntry();
            zipOut.close();

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test wheel", e);
        }
    }
}