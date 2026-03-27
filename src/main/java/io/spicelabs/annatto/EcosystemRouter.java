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

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Routes Tika MIME types to ecosystems with content-based disambiguation.
 *
 * <p>Disambiguation (ADR-002):
 * Some MIME types map to multiple ecosystems. This class uses content inspection
 * to determine the correct ecosystem. Detection uses BufferedInputStream with
 * 8KB mark/reset to avoid double-reading.
 *
 * <p>Thread Safety:
 * All methods are stateless and thread-safe.
 *
 * <p>Test: EcosystemRouterDisambiguationTest validates all routing decisions
 */
public final class EcosystemRouter {

    // Buffer size for mark/reset (8KB as per ADR-002)
    private static final int DETECTION_BUFFER_SIZE = 8192;

    private EcosystemRouter() {
        // Utility class
    }

    /**
     * Get all MIME types supported by this router.
     *
     * @return set of supported MIME type strings
     */
    public static @NotNull Set<String> supportedMimeTypes() {
        return Collections.unmodifiableSet(Set.of(
            "application/gzip",
            "application/x-gzip",
            "application/zip",
            "application/x-tar",
            "application/x-bzip2",
            "application/json",
            "text/x-lua"
        ));
    }

    /**
     * Check if MIME type is supported (without disambiguation).
     *
     * @param tikaMimeType MIME type from Apache Tika
     * @return true if potentially supported (may still fail disambiguation)
     */
    public static boolean isSupported(@NotNull String tikaMimeType) {
        String normalized = tikaMimeType.toLowerCase(Locale.ROOT);
        return normalized.equals("application/gzip")
            || normalized.equals("application/x-gzip")
            || normalized.equals("application/zip")
            || normalized.equals("application/x-tar")
            || normalized.equals("application/x-bzip2")
            || normalized.equals("application/json")
            || normalized.equals("text/x-lua");
    }

    /**
     * Route from file path with content inspection.
     *
     * <p>Uses Tika to detect MIME type, then inspects content for disambiguation.
     *
     * @param path file path
     * @return detected ecosystem, or empty if cannot determine
     * @throws IOException if file cannot be read
     */
    public static @NotNull Optional<Ecosystem> route(@NotNull Path path) throws IOException {
        String filename = path.getFileName().toString();

        // First try filename-based detection
        Optional<Ecosystem> fromFilename = routeFromFilenameOnly(filename);
        if (fromFilename.isPresent()) {
            return fromFilename;
        }

        // Then use content inspection
        try (InputStream is = Files.newInputStream(path)) {
            BufferedInputStream bis = new BufferedInputStream(is, DETECTION_BUFFER_SIZE);
            String mimeType = detectMimeTypeFromContent(bis);
            return routeFromStream(filename, mimeType, bis);
        }
    }

    /**
     * Route from MIME type with content inspection.
     *
     * <p>For ambiguous MIME types (gzip, zip), this reads the archive to determine
     * the correct ecosystem. Uses mark/reset on BufferedInputStream to minimize
     * overhead.
     *
     * @param path file path (for debugging/context)
     * @param tikaMimeType MIME type from Apache Tika
     * @param stream input stream (must support mark/reset or be wrapped)
     * @return detected ecosystem, or empty if cannot determine
     * @throws IOException if stream cannot be read
     */
    public static @NotNull Optional<Ecosystem> route(
            @NotNull Path path,
            @NotNull String tikaMimeType,
            @NotNull InputStream stream) throws IOException {

        BufferedInputStream bis;
        if (stream instanceof BufferedInputStream) {
            bis = (BufferedInputStream) stream;
        } else {
            bis = new BufferedInputStream(stream, DETECTION_BUFFER_SIZE);
        }

        return routeFromStream(path.getFileName().toString(), tikaMimeType, bis);
    }

    /**
     * Route from MIME type and filename alone (no content inspection).
     *
     * <p>Useful when stream is not available. May return empty for ambiguous
     * types even if content would resolve it.
     *
     * @param filename original filename
     * @param tikaMimeType MIME type from Apache Tika
     * @return detected ecosystem, or empty if cannot determine
     */
    public static @NotNull Optional<Ecosystem> routeFromFilename(
            @NotNull String filename,
            @NotNull String tikaMimeType) {

        String lowerMime = tikaMimeType.toLowerCase(Locale.ROOT);

        // Unambiguous types by filename
        Optional<Ecosystem> fromFilename = routeFromFilenameOnly(filename);
        if (fromFilename.isPresent()) {
            return fromFilename;
        }

        // For ambiguous types, we need content - return empty here
        if (lowerMime.equals("application/gzip") || lowerMime.equals("application/x-gzip") ||
            lowerMime.equals("application/zip") || lowerMime.equals("application/x-tar")) {
            return Optional.empty();
        }

        // JSON and Lua can be determined by extension
        if (lowerMime.equals("application/json") && filename.endsWith(".podspec.json")) {
            return Optional.of(Ecosystem.COCOAPODS);
        }
        if (lowerMime.equals("text/x-lua") && filename.endsWith(".rockspec")) {
            return Optional.of(Ecosystem.LUAROCKS);
        }

        return Optional.empty();
    }

    /**
     * Try to determine ecosystem from filename alone.
     */
    private static Optional<Ecosystem> routeFromFilenameOnly(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);

        // Compound extensions first
        if (lower.endsWith(".podspec.json")) {
            return Optional.of(Ecosystem.COCOAPODS);
        }
        if (lower.endsWith(".tar.bz2")) {
            return Optional.of(Ecosystem.CONDA);
        }
        if (lower.endsWith(".tar.gz")) {
            // Ambiguous - PyPI, CPAN - need content
            return Optional.empty();
        }

        // Simple extensions
        if (lower.endsWith(".tgz")) {
            return Optional.of(Ecosystem.NPM);
        }
        if (lower.endsWith(".crate")) {
            return Optional.of(Ecosystem.CRATES);
        }
        if (lower.endsWith(".gem")) {
            return Optional.of(Ecosystem.RUBYGEMS);
        }
        if (lower.endsWith(".conda")) {
            return Optional.of(Ecosystem.CONDA);
        }
        if (lower.endsWith(".whl")) {
            return Optional.of(Ecosystem.PYPI);
        }
        if (lower.endsWith(".rockspec") || lower.endsWith(".rock")) {
            return Optional.of(Ecosystem.LUAROCKS);
        }
        // Plain .tar is Hex - but check it's not tar.gz or tar.bz2
        if (lower.endsWith(".tar") && !lower.endsWith(".tar.gz") && !lower.endsWith(".tar.bz2")) {
            return Optional.of(Ecosystem.HEX);
        }

        return Optional.empty();
    }

    /**
     * Route using content inspection.
     */
    private static Optional<Ecosystem> routeFromStream(
            String filename,
            String mimeType,
            BufferedInputStream stream) throws IOException {

        String lowerMime = mimeType.toLowerCase(Locale.ROOT);

        // Check for specific filename patterns first
        String lowerFilename = filename.toLowerCase(Locale.ROOT);

        // Go modules have @v in the path
        if (filename.contains("@v")) {
            return Optional.of(Ecosystem.GO);
        }

        // Conda v2 - ZIP with .tar.zst inside
        if (lowerMime.equals("application/zip") || lowerFilename.endsWith(".conda")) {
            return disambiguateZip(filename, stream);
        }

        // GZIP tar - npm, PyPI, Crates, CPAN
        if (lowerMime.equals("application/gzip") || lowerMime.equals("application/x-gzip") ||
            lowerFilename.endsWith(".tgz") || lowerFilename.endsWith(".crate") ||
            lowerFilename.endsWith(".tar.gz")) {
            return disambiguateGzipTar(stream);
        }

        // Plain tar - RubyGems, Hex
        if (lowerMime.equals("application/x-tar") || lowerFilename.endsWith(".gem")) {
            return disambiguatePlainTar(stream);
        }

        // BZIP2 - Conda legacy
        if (lowerMime.equals("application/x-bzip2")) {
            return Optional.of(Ecosystem.CONDA);
        }

        // JSON - CocoaPods
        if (lowerMime.equals("application/json")) {
            if (filename.endsWith(".podspec.json")) {
                return Optional.of(Ecosystem.COCOAPODS);
            }
            return Optional.empty();
        }

        // Lua - LuaRocks
        if (lowerMime.equals("text/x-lua")) {
            if (filename.endsWith(".rockspec")) {
                return Optional.of(Ecosystem.LUAROCKS);
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    // Maximum size for decompressed GZIP data (1MB for detection buffer)
    private static final long MAX_DETECTION_SIZE = 1024 * 1024;

    /**
     * Disambiguate GZIP tar files by inspecting contents.
     *
     * <p>Buffers the stream to a temporary file since mark/reset cannot reliably
     * work across GZIP decompression (the read limit is unpredictable due to
     * compression). The temporary file allows full stream reset for the caller.
     */
    private static Optional<Ecosystem> disambiguateGzipTar(BufferedInputStream stream) throws IOException {
        // Buffer to temp file - GZIP decompression read amount is unpredictable
        Path tempFile = Files.createTempFile("annatto-gzip-", ".tar.gz");
        try {
            Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            try (GZIPInputStream gzis = new GZIPInputStream(Files.newInputStream(tempFile));
                 TarArchiveInputStream tais = new TarArchiveInputStream(gzis)) {

                ArchiveEntry entry;
                boolean hasEntries = false;
                while ((entry = tais.getNextEntry()) != null) {
                    hasEntries = true;
                    String name = entry.getName();

                    // NPM: package/package.json
                    if (name.endsWith("package/package.json")) {
                        return Optional.of(Ecosystem.NPM);
                    }
                    // PyPI: PKG-INFO or pyproject.toml
                    if (name.endsWith("PKG-INFO") || name.endsWith("pyproject.toml")) {
                        return Optional.of(Ecosystem.PYPI);
                    }
                    // Crates: Cargo.toml (at root of crate)
                    if (name.equals("Cargo.toml") || name.endsWith("/Cargo.toml")) {
                        return Optional.of(Ecosystem.CRATES);
                    }
                    // CPAN: META.json or META.yml (in package root, e.g., "Dist-Name-1.0/META.json")
                    if (name.endsWith("/META.json") || name.endsWith("/META.yml")) {
                        return Optional.of(Ecosystem.CPAN);
                    }
                }

                // If archive has no entries, it's malformed
                if (!hasEntries) {
                    throw new AnnattoException.MalformedPackageException("GZIP archive contains no entries");
                }

                return Optional.empty();
            } catch (java.util.zip.ZipException e) {
                // Corrupted GZIP
                throw new AnnattoException.MalformedPackageException("Invalid GZIP archive: " + e.getMessage());
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Disambiguate ZIP files by inspecting contents.
     */
    private static Optional<Ecosystem> disambiguateZip(String filename, BufferedInputStream stream) throws IOException {
        // For ZIP files, we need to buffer to a temp file since ZipFile needs random access
        // and mark/reset on BufferedInputStream won't work for full ZIP inspection

        String lower = filename.toLowerCase(Locale.ROOT);

        // Fast path: Go modules contain @v in filename
        if (filename.contains("@v")) {
            return Optional.of(Ecosystem.GO);
        }

        // Fast path: Conda v2 detection - check for .conda extension
        if (lower.endsWith(".conda")) {
            return Optional.of(Ecosystem.CONDA);
        }

        // Fast path: PyPI wheel has .whl extension
        if (lower.endsWith(".whl")) {
            return Optional.of(Ecosystem.PYPI);
        }

        // Fast path: LuaRocks has .rock extension
        if (lower.endsWith(".rock")) {
            return Optional.of(Ecosystem.LUAROCKS);
        }

        // For generic .zip files, we need to inspect contents
        // Buffer the stream to a temporary file for ZipFile inspection
        Path tempFile = Files.createTempFile("annatto-zip-", ".zip");
        try {
            Files.copy(stream, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            try (ZipFile zf = new ZipFile(tempFile.toFile())) {
                Enumeration<ZipArchiveEntry> entries = zf.getEntries();
                boolean hasEntries = false;

                while (entries.hasMoreElements()) {
                    hasEntries = true;
                    ZipArchiveEntry entry = entries.nextElement();
                    String name = entry.getName();

                    // PyPI wheel: .dist-info/METADATA
                    if (name.contains(".dist-info/")) {
                        return Optional.of(Ecosystem.PYPI);
                    }

                    // Go module: @v in path
                    if (name.contains("@v")) {
                        return Optional.of(Ecosystem.GO);
                    }

                    // Packagist: composer.json
                    if (name.equals("composer.json") || name.endsWith("/composer.json")) {
                        return Optional.of(Ecosystem.PACKAGIST);
                    }

                    // Conda v2: contains .tar.zst files
                    if (name.endsWith(".tar.zst") || name.endsWith(".tar.bz2")) {
                        return Optional.of(Ecosystem.CONDA);
                    }

                    // LuaRocks: .rockspec file
                    if (name.endsWith(".rockspec")) {
                        return Optional.of(Ecosystem.LUAROCKS);
                    }
                }

                // Empty ZIP is malformed
                if (!hasEntries) {
                    throw new AnnattoException.MalformedPackageException("ZIP archive contains no entries");
                }
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }

        return Optional.empty();
    }

    /**
     * Disambiguate plain tar files (RubyGems vs Hex).
     */
    private static Optional<Ecosystem> disambiguatePlainTar(BufferedInputStream stream) throws IOException {
        stream.mark(DETECTION_BUFFER_SIZE);

        try (TarArchiveInputStream tais = new TarArchiveInputStream(stream)) {
            ArchiveEntry entry;
            while ((entry = tais.getNextEntry()) != null) {
                String name = entry.getName();

                // RubyGems: metadata.gz
                if (name.equals("metadata.gz")) {
                    stream.reset();
                    return Optional.of(Ecosystem.RUBYGEMS);
                }
                // Hex: metadata.config
                if (name.equals("metadata.config")) {
                    stream.reset();
                    return Optional.of(Ecosystem.HEX);
                }
            }

            stream.reset();
            return Optional.empty();
        }
    }

    /**
     * Detect MIME type from content (simple detection based on magic bytes).
     */
    private static String detectMimeTypeFromContent(BufferedInputStream stream) throws IOException {
        stream.mark(8);
        byte[] magic = new byte[8];
        int read = stream.read(magic);
        stream.reset();

        if (read < 4) {
            return "application/octet-stream";
        }

        // ZIP: PK\x03\x04 or PK\x05\x06
        if (magic[0] == 0x50 && magic[1] == 0x4B) {
            return "application/zip";
        }

        // GZIP: 0x1f 0x8b
        if (magic[0] == 0x1f && magic[1] == (byte) 0x8b) {
            return "application/gzip";
        }

        // BZIP2: BZh
        if (magic[0] == 0x42 && magic[1] == 0x5A && magic[2] == 0x68) {
            return "application/x-bzip2";
        }

        // Plain tar: ustar at offset 257
        stream.mark(512);
        byte[] tarMagic = new byte[512];
        stream.read(tarMagic);
        stream.reset();
        if (tarMagic.length > 262 &&
            tarMagic[257] == 'u' && tarMagic[258] == 's' &&
            tarMagic[259] == 't' && tarMagic[260] == 'a' && tarMagic[261] == 'r') {
            return "application/x-tar";
        }

        // JSON: starts with { or [
        if (magic[0] == '{' || magic[0] == '[') {
            return "application/json";
        }

        return "application/octet-stream";
    }
}
