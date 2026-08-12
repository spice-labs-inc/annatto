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

package io.spicelabs.annatto.threading;

import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.ecosystem.npm.NpmPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Single-threaded execution-model tests (ADR-004, de-scoped 2026-08).
 *
 * <p>Annatto is invoked from a SINGLE thread: {@link LanguagePackageReader#read(...)} is
 * called by one thread and the resulting {@link LanguagePackage} is read by one thread.
 * Concurrent {@code read()}/{@code streamEntries()}/{@code close()} is OUT OF SCOPE and not
 * guaranteed.
 *
 * <p>These tests pin the sequential contract that IS promised:
 * <ul>
 *   <li>repeated metadata reads are consistent and immutable</li>
 *   <li>only one package stream may be open at a time; sequential reuse works</li>
 *   <li>distinct package instances never share state</li>
 *   <li>static reader query methods are repeatable</li>
 * </ul>
 */
class SingleThreadedModelTest {

    /**
     * Creates a synthetic npm package for testing.
     */
    private LanguagePackage createTestPackage() throws IOException {
        byte[] packageData = createSyntheticNpmPackage();
        return NpmPackage.fromStream(new ByteArrayInputStream(packageData), "test-package-1.0.0.tgz");
    }

    /**
     * Creates a minimal valid npm package (tgz archive).
     */
    private byte[] createSyntheticNpmPackage() throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos)) {

            String packageJson = """
                {
                  "name": "test-package",
                  "version": "1.0.0",
                  "description": "Test package for threading-model tests"
                }
                """;
            addEntry(tarOut, "package/package.json", packageJson.getBytes(StandardCharsets.UTF_8));
            addEntry(tarOut, "package/index.js", "console.log('hello');".getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    private void addEntry(TarArchiveOutputStream tarOut, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        tarOut.putArchiveEntry(entry);
        tarOut.write(content);
        tarOut.closeArchiveEntry();
    }

    @Test
    @DisplayName("repeated metadata reads are consistent (sequential)")
    void repeatedMetadataCallsConsistent() throws IOException {
        LanguagePackage pkg = createTestPackage();

        for (int i = 0; i < 100; i++) {
            assertThat(pkg.name()).isEqualTo("test-package");
            assertThat(pkg.version()).isEqualTo("1.0.0");
            assertThat(pkg.metadata().name()).isEqualTo("test-package");
            assertThat(pkg.metadata().version()).isEqualTo("1.0.0");

            Optional<PackageURL> purl = pkg.toPurl();
            assertThat(purl).isPresent();
            assertThat(purl.get().toString()).isEqualTo("pkg:npm/test-package@1.0.0");
        }
    }

    @Test
    @DisplayName("only one stream per package; sequential reuse works")
    void streamEntriesSingleOpenThenSequentialReuse() throws IOException {
        LanguagePackage pkg = createTestPackage();

        // One stream at a time; a second open must fail with the in-use message.
        PackageEntryStream stream1 = pkg.streamEntries();
        try {
            assertThatThrownBy(pkg::streamEntries)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open");
        } finally {
            stream1.close();
        }

        // After closing the first, a fresh stream works.
        try (PackageEntryStream stream2 = pkg.streamEntries()) {
            assertThat(stream2.hasNext()).isTrue();
        }
    }

    @Test
    @DisplayName("repeated sequential streams are allowed")
    void multipleSequentialStreamsAllowed() throws IOException {
        LanguagePackage pkg = createTestPackage();

        try (PackageEntryStream stream1 = pkg.streamEntries()) {
            assertThat(stream1.hasNext()).isTrue();
        }
        try (PackageEntryStream stream2 = pkg.streamEntries()) {
            assertThat(stream2.hasNext()).isTrue();
        }
    }

    @Test
    @DisplayName("distinct package instances never share state (sequential)")
    void noStateLeakageBetweenInstances() throws IOException {
        LanguagePackage pkg1 = NpmPackage.fromStream(
            new ByteArrayInputStream(createSyntheticNpmPackageWithName("package-one", "1.0.0")),
            "package-one-1.0.0.tgz");
        LanguagePackage pkg2 = NpmPackage.fromStream(
            new ByteArrayInputStream(createSyntheticNpmPackageWithName("package-two", "2.0.0")),
            "package-two-2.0.0.tgz");

        assertThat(pkg1.name()).isEqualTo("package-one");
        assertThat(pkg1.version()).isEqualTo("1.0.0");
        assertThat(pkg2.name()).isEqualTo("package-two");
        assertThat(pkg2.version()).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("reader static query methods are repeatable")
    void readerQueryMethodsRepeatable() {
        for (int i = 0; i < 100; i++) {
            assertThat(LanguagePackageReader.isSupported("application/gzip")).isTrue();
        }
        assertThat(LanguagePackageReader.supportedMimeTypes()).isNotEmpty();
    }

    private byte[] createSyntheticNpmPackageWithName(String name, String version) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos, StandardCharsets.UTF_8.name())) {

            String packageJson = String.format("""
                {
                  "name": "%s",
                  "version": "%s"
                }
                """, name, version);
            addEntry(tarOut, "package/package.json", packageJson.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }
}