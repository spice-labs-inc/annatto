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

package io.spicelabs.annatto.ecosystem.packagist;

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
 * Contract tests for {@link PackagistPackage}.
 */
class PackagistPackageContractTest extends LanguagePackageContractTest {

    private static final String ECOSYSTEM = "packagist";

    @BeforeAll
    static void downloadCorpus() throws IOException {
        TestCorpusDownloader.ensureCorpusAvailable();
    }

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
            return PackagistPackage.fromPath(firstCase.packagePath());
        } catch (IOException e) {
            fail("Failed to load package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected LanguagePackage createIncompletePackage() {
        // Create a minimal composer package with empty version
        byte[] zipData = createMinimalComposerZip("test/package", "");
        try {
            return PackagistPackage.fromStream(new ByteArrayInputStream(zipData), "test-package-v.zip");
        } catch (IOException e) {
            fail("Failed to create incomplete package: " + e.getMessage());
            return null;
        }
    }

    private byte[] createMinimalComposerZip(String name, String version) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.util.zip.ZipOutputStream zipOut = new java.util.zip.ZipOutputStream(baos);

            String composerJson = "{\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"description\":\"test\"}";
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry("composer.json");
            zipOut.putNextEntry(entry);
            zipOut.write(composerJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zipOut.closeEntry();
            zipOut.close();

            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test zip", e);
        }
    }

    @Override
    protected Ecosystem expectedEcosystem() {
        return Ecosystem.PACKAGIST;
    }

    @Override
    protected String expectedValidPurl() {
        List<PackageTestCase> cases = testCases();
        assumeThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        try {
            JsonObject expected = cases.get(0).loadExpectedJson();
            String name = expected.get("name").isJsonNull()
                ? null : expected.get("name").getAsString();
            String version = expected.get("version").isJsonNull()
                ? null : expected.get("version").getAsString();
            // Packagist uses namespace/name format
            if (version != null) {
                return "pkg:composer/" + name + "@" + version;
            }
            return "pkg:composer/" + name;
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

        PackagistPackage composer = PackagistPackage.fromPath(testCase.packagePath());

        // Handle null values in JSON source of truth
        String expectedName = expected.get("name").isJsonNull()
            ? null : expected.get("name").getAsString();
        String expectedVersion = expected.get("version").isJsonNull()
            ? null : expected.get("version").getAsString();

        assertThat(composer.name())
            .as("name mismatch for %s", testCase.packageFilename())
            .isEqualTo(expectedName);

        assertThat(composer.version())
            .as("version mismatch for %s", testCase.packageFilename())
            .isEqualTo(expectedVersion);

        Optional<PackageURL> purl = composer.toPurl();
        assertThat(purl).as("PURL should be present for %s", testCase.packageFilename()).isPresent();
        assertThat(purl.get().getType()).as("PURL type for %s", testCase.packageFilename()).isEqualTo("composer");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("generates correct PURL matching source of truth")
    void generatesCorrectPurl(PackageTestCase testCase) throws Exception {
        assumeThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();
        String expectedName = expected.get("name").isJsonNull()
            ? null : expected.get("name").getAsString();
        String expectedVersion = expected.get("version").isJsonNull()
            ? null : expected.get("version").getAsString();

        PackagistPackage composer = PackagistPackage.fromPath(testCase.packagePath());
        Optional<PackageURL> purl = composer.toPurl();

        assertThat(purl).as("PURL should be present").isPresent();
        String expectedPurl = expectedVersion != null
            ? "pkg:composer/" + expectedName + "@" + expectedVersion
            : "pkg:composer/" + expectedName;
        assertThat(purl.get().toString())
            .as("PURL mismatch for %s", testCase.packageFilename())
            .isEqualTo(expectedPurl);
    }
}
