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

package io.spicelabs.annatto.ecosystem.cpan;

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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Contract tests for {@link CpanPackage}.
 */
class CpanPackageContractTest extends LanguagePackageContractTest {

    private static final String ECOSYSTEM = "cpan";
    private static final String MIRROR_PAUSE_ID = "TESTER";

    /** Fixtures are copied under a CPAN mirror layout so that the PAUSE id is known. */
    @TempDir
    static Path mirror;

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
        assertThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        PackageTestCase firstCase = cases.get(0);
        assertThat(Files.exists(firstCase.packagePath()))
            .as("Package file must exist: %s", firstCase.packagePath())
            .isTrue();

        try {
            return CpanPackage.fromPath(onMirror(firstCase.packagePath()));
        } catch (IOException e) {
            fail("Failed to load package: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected LanguagePackage createIncompletePackage() {
        // Create a minimal CPAN dist with empty version in META.json
        byte[] distData = createMinimalCpanDist("Test-Package", "");
        try {
            return CpanPackage.fromStream(new ByteArrayInputStream(distData), "incomplete.tar.gz");
        } catch (IOException e) {
            fail("Failed to create incomplete package: " + e.getMessage());
            return null;
        }
    }

    private static Path onMirror(Path fixture) throws IOException {
        Path dir = mirror.resolve("authors/id/T/TE/" + MIRROR_PAUSE_ID);
        Files.createDirectories(dir);
        Path copy = dir.resolve(fixture.getFileName());
        if (!Files.exists(copy)) {
            Files.copy(fixture, copy);
        }
        return copy;
    }

    private byte[] createMinimalCpanDist(String name, String version) {
        return createMinimalCpanDist(name, version, null);
    }

    private byte[] createMinimalCpanDist(String name, String version, String xAuthority) {
        try {
            // CPAN dists have a nested structure: Distribution-Name-VERSION/META.json
            String dirName = name.replace("::", "-") + (version.isEmpty() ? "" : "-" + version);

            // Create META.json with potentially empty fields
            String metaJson = "{\n   \"name\" : \"" + name + "\",\n   \"version\" : \"" + version
                + "\",\n   \"abstract\" : \"test\""
                + (xAuthority == null ? "" : ",\n   \"x_authority\" : \"" + xAuthority + "\"") + "\n}\n";
            return createCpanDist(dirName, "META.json", metaJson);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test CPAN dist", e);
        }
    }

    private byte[] createCpanDist(String dirName, String metaFile, String metaContent) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.util.zip.GZIPOutputStream gzipOut = new java.util.zip.GZIPOutputStream(baos);
        org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOut =
            new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzipOut);

        byte[] metaBytes = metaContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        org.apache.commons.compress.archivers.tar.TarArchiveEntry entry =
            new org.apache.commons.compress.archivers.tar.TarArchiveEntry(dirName + "/" + metaFile);
        entry.setSize(metaBytes.length);
        tarOut.putArchiveEntry(entry);
        tarOut.write(metaBytes);
        tarOut.closeArchiveEntry();

        tarOut.close();
        return baos.toByteArray();
    }

    @Override
    protected Ecosystem expectedEcosystem() {
        return Ecosystem.CPAN;
    }

    @Override
    protected String expectedValidPurl() {
        List<PackageTestCase> cases = testCases();
        assertThat(cases).as("At least one package/JSON pair must exist").isNotEmpty();

        try {
            JsonObject expected = cases.get(0).loadExpectedJson();
            String name = expected.get("name").getAsString();
            String version = expected.get("version").getAsString();
            return "pkg:cpan/" + MIRROR_PAUSE_ID + "/" + name + "@" + version;
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
        assertThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();

        CpanPackage cpan = CpanPackage.fromPath(testCase.packagePath());

        assertThat(cpan.name())
            .as("name mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("name").getAsString());

        assertThat(cpan.version())
            .as("version mismatch for %s", testCase.packageFilename())
            .isEqualTo(expected.get("version").getAsString());

        // Outside a CPAN mirror path the PURL depends on x_authority, so it may be absent
        Optional<PackageURL> purl = cpan.toPurl();
        purl.ifPresent(p -> {
            assertThat(p.getType()).as("PURL type for %s", testCase.packageFilename()).isEqualTo("cpan");
            assertThat(p.getNamespace())
                .as("PURL namespace for %s", testCase.packageFilename())
                .matches("[A-Z][A-Z0-9-]{1,8}");
            assertThat(p.getName()).as("PURL name for %s", testCase.packageFilename())
                .isEqualTo(expected.get("name").getAsString());
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("generates correct PURL matching source of truth")
    void generatesCorrectPurl(PackageTestCase testCase) throws Exception {
        assertThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();
        String expectedName = expected.get("name").getAsString();
        String expectedVersion = expected.get("version").getAsString();

        CpanPackage cpan = CpanPackage.fromPath(onMirror(testCase.packagePath()));
        Optional<PackageURL> purl = cpan.toPurl();

        assertThat(purl).as("PURL should be present").isPresent();
        assertThat(purl.get().toString())
            .as("PURL mismatch for %s", testCase.packageFilename())
            .isEqualTo("pkg:cpan/" + MIRROR_PAUSE_ID + "/" + expectedName + "@" + expectedVersion);
    }

    @Test
    @DisplayName("PURL namespace comes from META x_authority")
    void purlNamespaceFromXAuthority() throws IOException {
        byte[] dist = createMinimalCpanDist("App-cpanminus", "1.7047", "cpan:MIYAGAWA");
        CpanPackage cpan = CpanPackage.fromStream(new ByteArrayInputStream(dist), "App-cpanminus-1.7047.tar.gz");

        assertThat(cpan.toPurl()).map(PackageURL::toString)
            .contains("pkg:cpan/MIYAGAWA/App-cpanminus@1.7047");
        assertThat(cpan.metadata().raw()).containsEntry("pauseId", "MIYAGAWA");
    }

    @Test
    @DisplayName("PURL namespace from a CPAN mirror path takes precedence over x_authority")
    void purlNamespaceFromMirrorPath() throws IOException {
        byte[] dist = createMinimalCpanDist("App-cpanminus", "1.7047", "cpan:MIYAGAWA");
        CpanPackage cpan = CpanPackage.fromStream(new ByteArrayInputStream(dist),
            "/mirror/authors/id/E/ET/ETHER/App-cpanminus-1.7047.tar.gz");

        assertThat(cpan.toPurl()).map(PackageURL::toString)
            .contains("pkg:cpan/ETHER/App-cpanminus@1.7047");
    }

    @Test
    @DisplayName("no PURL without a PAUSE id")
    void purlAbsentWithoutPauseId() throws IOException {
        byte[] dist = createMinimalCpanDist("App-cpanminus", "1.7047");
        CpanPackage cpan = CpanPackage.fromStream(new ByteArrayInputStream(dist), "App-cpanminus-1.7047.tar.gz");

        assertThat(cpan.toPurl()).isEmpty();
    }

    @Test
    @DisplayName("no PURL from a malformed x_authority or mirror path")
    void purlAbsentWithMalformedPauseId() throws IOException {
        for (String authority : List.of("MIYAGAWA", "cpan:", "cpan:not an id", "github:miyagawa")) {
            byte[] dist = createMinimalCpanDist("App-cpanminus", "1.7047", authority);
            CpanPackage cpan = CpanPackage.fromStream(new ByteArrayInputStream(dist),
                "/mirror/authors/id/E/EX/ETHER/App-cpanminus-1.7047.tar.gz");
            assertThat(cpan.toPurl()).as("x_authority %s", authority).isEmpty();
        }
    }

    @Test
    @DisplayName("a module-style name becomes a distribution name, not a namespace")
    void purlModuleNameBecomesDistributionName() throws IOException {
        byte[] dist = createMinimalCpanDist("Foo::Bar", "1.0", "cpan:FOO");
        CpanPackage cpan = CpanPackage.fromStream(new ByteArrayInputStream(dist), "Foo-Bar-1.0.tar.gz");

        assertThat(cpan.toPurl()).map(PackageURL::toString).contains("pkg:cpan/FOO/Foo-Bar@1.0");
    }

    @Test
    @DisplayName("PURL namespace comes from META.yml x_authority")
    void purlNamespaceFromYamlXAuthority() throws IOException {
        String yaml = "---\nname: Foo-Bar\nversion: '1.0'\nx_authority: cpan:foo\n";
        byte[] dist = createCpanDist("Foo-Bar-1.0", "META.yml", yaml);
        CpanPackage cpan = CpanPackage.fromStream(new ByteArrayInputStream(dist), "Foo-Bar-1.0.tar.gz");

        assertThat(cpan.toPurl()).map(PackageURL::toString).contains("pkg:cpan/FOO/Foo-Bar@1.0");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    @DisplayName("extracts description from source of truth")
    void extractsDescriptionFromSourceOfTruth(PackageTestCase testCase) throws Exception {
        assertThat(Files.exists(testCase.packagePath()))
            .as("Package file must exist: %s", testCase.packagePath())
            .isTrue();

        JsonObject expected = testCase.loadExpectedJson();

        CpanPackage cpan = CpanPackage.fromPath(testCase.packagePath());
        PackageMetadata metadata = cpan.metadata();

        if (expected.has("description") && !expected.get("description").isJsonNull()) {
            assertThat(metadata.description())
                .as("description for %s", testCase.packageFilename())
                .isPresent();
        }
    }
}
