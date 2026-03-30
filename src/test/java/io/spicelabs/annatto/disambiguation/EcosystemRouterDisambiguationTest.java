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

package io.spicelabs.annatto.disambiguation;

import io.spicelabs.annatto.AnnattoException;
import io.spicelabs.annatto.Ecosystem;
import io.spicelabs.annatto.EcosystemRouter;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for EcosystemRouter disambiguation logic.
 *
 * <p>Theory: Many archive formats (gzip tar, ZIP) are used by multiple ecosystems.
 * The router must inspect content to determine the correct ecosystem. These tests
 * verify the disambiguation logic using synthetic packages with known structure.
 *
 * <p>Requirements tested:
 * <ul>
 *   <li>GZIP tar files are disambiguated by marker files (package.json, Cargo.toml, etc.)</li>
 *   <li>ZIP files are disambiguated by structure (.dist-info, @v, etc.)</li>
 *   <li>Plain tar files are disambiguated (RubyGems vs Hex)</li>
 *   <li>Ambiguous files fail closed (empty optional, not wrong guess)</li>
 *   <li>Invalid/corrupt files throw MalformedPackageException</li>
 * </ul>
 */
public class EcosystemRouterDisambiguationTest {

    // ========================================
    // GZIP tar disambiguation tests
    // ========================================

    // ========================================
    // Test helpers for creating synthetic packages
    // ========================================

    private byte[] createGzipTarWithEntry(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos)) {
            TarArchiveEntry entry = new TarArchiveEntry(entryName);
            entry.setSize(content.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(content);
            tarOut.closeArchiveEntry();
        }
        return baos.toByteArray();
    }

    private Path createTempGzipFile(String prefix, String entryName, byte[] content) throws IOException {
        Path temp = Files.createTempFile(prefix, ".tar.gz");
        byte[] data = createGzipTarWithEntry(entryName, content);
        Files.write(temp, data);
        return temp;
    }

    @Test
    @DisplayName("GZIP with package/package.json is NPM")
    void gzipWithPackageJsonIsNpm() throws IOException {
        Path temp = createTempGzipFile("npm", "package/package.json",
            "{\"name\": \"test\", \"version\": \"1.0.0\"}".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.NPM);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("GZIP with PKG-INFO is PyPI sdist")
    void gzipWithPkgInfoIsPypi() throws IOException {
        Path temp = createTempGzipFile("pypi", "my-package-1.0.0/PKG-INFO",
            "Name: my-package\nVersion: 1.0.0\n".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.PYPI);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("GZIP with pyproject.toml is PyPI sdist")
    void gzipWithPyprojectTomlIsPypi() throws IOException {
        Path temp = createTempGzipFile("pypi", "my-package-1.0.0/pyproject.toml",
            "[build-system]\nrequires = [\"setuptools\"]\n".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.PYPI);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("GZIP with Cargo.toml is Crates")
    void gzipWithCargoTomlIsCrates() throws IOException {
        Path temp = createTempGzipFile("crate", "my-crate-1.0.0/Cargo.toml",
            "[package]\nname = \"my-crate\"\nversion = \"1.0.0\"\n".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.CRATES);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("GZIP with META.json is CPAN")
    void gzipWithMetaJsonIsCpan() throws IOException {
        Path temp = createTempGzipFile("cpan", "My-Module-1.0/META.json",
            "{\"name\": \"My-Module\", \"version\": \"1.0\"}".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.CPAN);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("GZIP with META.yml is CPAN")
    void gzipWithMetaYmlIsCpan() throws IOException {
        Path temp = createTempGzipFile("cpan", "My-Module-1.0/META.yml",
            "name: My-Module\nversion: 1.0\n".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.CPAN);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("Ambiguous GZIP with no markers returns empty")
    void ambiguousGzipDefaultsToNone() throws IOException {
        // Create GZIP with random file that has no ecosystem markers
        Path temp = createTempGzipFile("ambiguous", "some/random/file.txt",
            "just some text".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isEmpty();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("Empty GZIP throws MalformedPackageException")
    void emptyGzipThrows() throws IOException {
        // Create empty gzip
        Path temp = Files.createTempFile("empty", ".tar.gz");
        try (GZIPOutputStream gzos = new GZIPOutputStream(Files.newOutputStream(temp))) {
            // Write nothing - just header
        }

        try {
            assertThatThrownBy(() -> EcosystemRouter.route(temp))
                .isInstanceOf(AnnattoException.MalformedPackageException.class);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ========================================
    // ZIP disambiguation tests
    // ========================================

    // ========================================
    // ZIP test helpers
    // ========================================

    private byte[] createZipWithEntry(String entryName, byte[] content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(entryName);
            entry.setSize(content.length);
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private Path createTempZipFile(String prefix, String entryName, byte[] content) throws IOException {
        Path temp = Files.createTempFile(prefix, ".zip");
        byte[] data = createZipWithEntry(entryName, content);
        Files.write(temp, data);
        return temp;
    }

    @Test
    @DisplayName("ZIP with .dist-info/ is PyPI wheel")
    void zipWithDistInfoIsPypiWheel() throws IOException {
        Path temp = createTempZipFile("wheel", "mypackage-1.0.0.dist-info/METADATA",
            "Name: mypackage\nVersion: 1.0.0\n".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.PYPI);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("ZIP with @v in path is Go module")
    void zipWithAtVIsGo() throws IOException {
        // Go modules have @v in the path (either filename or inside archive)
        Path temp = createTempZipFile("go", "github.com/foo/bar@v1.0.0/go.mod",
            "module github.com/foo/bar\n".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.GO);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("ZIP with composer.json is Packagist")
    void zipWithComposerJsonIsPackagist() throws IOException {
        Path temp = createTempZipFile("packagist", "vendor/package/composer.json",
            "{\"name\": \"vendor/package\", \"version\": \"1.0.0\"}".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.PACKAGIST);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("ZIP with .tar.zst is Conda v2")
    void zipWithTarZstIsConda() throws IOException {
        // Conda v2 packages contain .tar.zst archives
        Path temp = createTempZipFile("conda", "pkg-1.0.0-x86_64.tar.zst",
            "fake zst content".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.CONDA);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("ZIP with .rockspec is LuaRocks")
    void zipWithRockspecIsLuarocks() throws IOException {
        Path temp = createTempZipFile("luarocks", "my-rock-1.0.0-1.rockspec",
            "package = \"my-rock\"\nversion = \"1.0.0-1\"\n".getBytes());
        try {
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.LUAROCKS);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ========================================
    // Plain tar disambiguation tests
    // ========================================

    @Test
    @DisplayName("Plain tar with metadata.gz is RubyGems")
    void tarWithMetadataGzIsRubygems() throws IOException {
        // RubyGems have metadata.gz at the root
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(baos)) {
            // Add metadata.gz
            byte[] metaContent = "---\nname: my_gem\nversion: 1.0.0\n".getBytes();
            TarArchiveEntry entry = new TarArchiveEntry("metadata.gz");
            entry.setSize(metaContent.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(metaContent);
            tarOut.closeArchiveEntry();
        }

        Path temp = Files.createTempFile("rubygems", ".gem");
        try {
            Files.write(temp, baos.toByteArray());
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.RUBYGEMS);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Test
    @DisplayName("Plain tar with metadata.config is Hex")
    void tarWithMetadataConfigIsHex() throws IOException {
        // Hex packages have metadata.config at the root
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tarOut = new TarArchiveOutputStream(baos)) {
            // Add metadata.config
            byte[] metaContent = "{\"name\": \"my_package\", \"version\": \"1.0.0\"}".getBytes();
            TarArchiveEntry entry = new TarArchiveEntry("metadata.config");
            entry.setSize(metaContent.length);
            tarOut.putArchiveEntry(entry);
            tarOut.write(metaContent);
            tarOut.closeArchiveEntry();
        }

        Path temp = Files.createTempFile("hex", ".tar");
        try {
            Files.write(temp, baos.toByteArray());
            Optional<Ecosystem> result = EcosystemRouter.route(temp);
            assertThat(result).isPresent().hasValue(Ecosystem.HEX);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    // ========================================
    // MIME type detection tests
    // ========================================

    @Test
    @DisplayName("Router detects NPM from path with .tgz extension")
    void routerDetectsNpmFromTgzPath() throws IOException {
        // Create a minimal npm-like .tgz file
        Path temp = createTempGzipFile("npm", "package/package.json",
            "{\"name\": \"test\", \"version\": \"1.0.0\"}".getBytes());
        Path tgzPath = temp.resolveSibling(temp.getFileName().toString().replace(".tar.gz", ".tgz"));
        Files.move(temp, tgzPath);

        try {
            Optional<Ecosystem> result = EcosystemRouter.route(tgzPath);
            assertThat(result).isPresent().hasValue(Ecosystem.NPM);
        } finally {
            Files.deleteIfExists(tgzPath);
        }
    }

    @Test
    @DisplayName("Router detects Crates from path with .crate extension")
    void routerDetectsCratesFromCratePath() throws IOException {
        // Create a minimal crate-like .crate file
        Path temp = createTempGzipFile("crate", "my-crate-1.0.0/Cargo.toml",
            "[package]\nname = \"my-crate\"\nversion = \"1.0.0\"\n".getBytes());
        Path cratePath = temp.resolveSibling("my-crate-1.0.0.crate");
        Files.move(temp, cratePath);

        try {
            Optional<Ecosystem> result = EcosystemRouter.route(cratePath);
            assertThat(result).isPresent().hasValue(Ecosystem.CRATES);
        } finally {
            Files.deleteIfExists(cratePath);
        }
    }

    @Test
    @DisplayName("Router handles unknown MIME type gracefully")
    void routerHandlesUnknownMimeType() {
        assertThat(EcosystemRouter.isSupported("application/unknown")).isFalse();
        assertThat(EcosystemRouter.routeFromFilename("file.unknown", "application/unknown"))
            .isEmpty();
    }

    @Test
    @DisplayName("Router is case-insensitive for MIME types")
    void routerIsCaseInsensitive() {
        assertThat(EcosystemRouter.isSupported("APPLICATION/ZIP")).isTrue();
        assertThat(EcosystemRouter.isSupported("Application/Zip")).isTrue();
    }
}
