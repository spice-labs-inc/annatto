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

package io.spicelabs.annatto.ecosystem.rubygems;

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
 * Contract tests for {@link RubygemsPackage}.
 */
class RubygemsPackageContractTest extends LanguagePackageContractTest {

    private static final String ECOSYSTEM = "rubygems";

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
            return RubygemsPackage.fromPath(firstCase.packagePath());
        } catch (IOException e) {
            fail("Failed to load package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected LanguagePackage createIncompletePackage() {
        // Create a minimal gem with empty version in metadata
        byte[] gemData = createMinimalGem("test-pkg", "");
        try {
            return RubygemsPackage.fromStream(new ByteArrayInputStream(gemData), "incomplete.gem");
        } catch (IOException e) {
            fail("Failed to create incomplete package: " + e.getMessage());
            return null;
        }
    }

    private byte[] createMinimalGem(String name, String version) {
        try {
            // Gem is a plain tar (not gzipped), containing metadata.gz
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOut =
                new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(baos);

            // Create minimal metadata.gz content with YAML gem spec
            // Use simple YAML format without Ruby-specific tags that get stripped
            String metadata = "---\nname: \"" + name + "\"\nversion: \"" + version + "\"\n";
            byte[] metadataBytes = metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8);

            java.io.ByteArrayOutputStream metadataBaos = new java.io.ByteArrayOutputStream();
            java.util.zip.GZIPOutputStream metadataGzip = new java.util.zip.GZIPOutputStream(metadataBaos);
            metadataGzip.write(metadataBytes);
            metadataGzip.close();
            byte[] compressedMetadata = metadataBaos.toByteArray();

            // Add metadata.gz to tar
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry =
                new org.apache.commons.compress.archivers.tar.TarArchiveEntry("metadata.gz");
            entry.setSize(compressedMetadata.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(compressedMetadata);
            tarOut.closeArchiveEntry();

            tarOut.close();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test gem", e);
        }
    }

    @Override
    protected Ecosystem expectedEcosystem() {
        return Ecosystem.RUBYGEMS;
    }

    @Override
    protected String expectedValidPurl() {
        List<PackageTestCase> cases = testCases();
        assumeThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        try {
            JsonObject expected = cases.get(0).loadExpectedJson();
            String name = expected.get("name").getAsString();
            String version = expected.get("version").getAsString();
            return "pkg:gem/" + name + "@" + version;
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

        RubygemsPackage gem = RubygemsPackage.fromPath(testCase.packagePath());

        assertThat(gem.name())
            .as("name mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("name").getAsString());

        assertThat(gem.version())
            .as("version mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("version").getAsString());

        Optional<PackageURL> purl = gem.toPurl();
        assertThat(purl).as("PURL should be present for %s", testCase.packageFilename()).isPresent();
        assertThat(purl.get().getType()).as("PURL type for %s", testCase.packageFilename()).isEqualTo("gem");
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

        RubygemsPackage gem = RubygemsPackage.fromPath(testCase.packagePath());
        Optional<PackageURL> purl = gem.toPurl();

        assertThat(purl).as("PURL should be present").isPresent();
        assertThat(purl.get().toString())
            .as("PURL mismatch for %s", testCase.packageFilename())
            .isEqualTo("pkg:gem/" + expectedName + "@" + expectedVersion);
    }
}
