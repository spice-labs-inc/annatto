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

import io.spicelabs.annatto.internal.BoundedInflateStream;
import io.spicelabs.annatto.internal.BoundedInputStream;
import io.spicelabs.annatto.internal.Spool;
import io.spicelabs.annatto.markers.CargoTomlMarker;
import io.spicelabs.annatto.markers.MetaMarker;
import io.spicelabs.annatto.markers.NpmEntryMarker;
import io.spicelabs.annatto.markers.PkgInfoMarker;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
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
 * <p>Disambiguation (ADR-002 + Phase 7): some MIME types map to multiple ecosystems. Routing
 * inspects archive CONTENT to determine the correct ecosystem. Since {@code .tgz}/{@code .crate}
 * names are ambiguous (a generic tar.gz bundle is not necessarily an npm package - Goat Rodeo
 * survey incident), they are no longer classified by name alone. Content routing is BOUNDED:
 * compressed-input cap, decompressed-scan cap, and entry-count cap; a cap trip fails closed
 * ({@code Optional.empty()} -> {@link AnnattoException.UnknownFormatException}).
 *
 * <p>Marker predicates are the SHARED Phase 7 markers ({@code markers.*}) so the router and
 * the metadata extractors can never disagree.
 *
 * <p>Thread Safety: All methods are stateless and thread-safe.
 */
public final class EcosystemRouter {

    // Buffer size for mark/reset (8KB as per ADR-002)
    private static final int DETECTION_BUFFER_SIZE = 8192;

    // Phase 7 routing budgets (ADR-005): fail closed when tripped.
    private static final long MAX_ROUTER_COMPRESSED = 256L * 1024 * 1024;
    private static final long MAX_ROUTER_INFLATED = 16L * 1024 * 1024;
    private static final int MAX_ROUTER_ENTRIES = 1000;

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
     * @param path file path
     * @return detected ecosystem, or empty if cannot determine
     * @throws IOException if file cannot be read
     */
    public static @NotNull Optional<Ecosystem> route(@NotNull Path path) throws IOException {
        String filename = path.getFileName().toString();

        Optional<Ecosystem> fromFilename = routeFromFilenameOnly(filename);
        if (fromFilename.isPresent()) {
            return fromFilename;
        }

        String mimeType;
        try (InputStream is = Files.newInputStream(path)) {
            BufferedInputStream bis = new BufferedInputStream(is, DETECTION_BUFFER_SIZE);
            mimeType = detectMimeTypeFromContent(bis);
        }
        return routeFromMime(filename, mimeType, path, null);
    }

    /**
     * Route from a path using an explicitly provided MIME type (path-based, no stream copy).
     *
     * @param path file path
     * @param tikaMimeType MIME type from Apache Tika
     * @return detected ecosystem, or empty if cannot determine
     * @throws IOException if file cannot be read
     */
    public static @NotNull Optional<Ecosystem> route(
            @NotNull Path path, @NotNull String tikaMimeType) throws IOException {
        String filename = path.getFileName().toString();

        Optional<Ecosystem> fromFilename = routeFromFilenameOnly(filename);
        if (fromFilename.isPresent()) {
            return fromFilename;
        }
        return routeFromMime(filename, tikaMimeType, path, null);
    }

    /**
     * Route from MIME type with content inspection over a caller-provided stream.
     *
     * <p>For ambiguous types the stream is drained into a bounded spool for inspection.
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

        return routeFromMime(path.getFileName().toString(), tikaMimeType, null, bis);
    }

    /**
     * Route from MIME type and filename alone (no content inspection).
     *
     * @param filename original filename
     * @param tikaMimeType MIME type from Apache Tika
     * @return detected ecosystem, or empty if cannot determine
     */
    public static @NotNull Optional<Ecosystem> routeFromFilename(
            @NotNull String filename,
            @NotNull String tikaMimeType) {

        String lowerMime = tikaMimeType.toLowerCase(Locale.ROOT);

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
     *
     * <p>Phase 7: {@code .tgz} and {@code .crate} are AMBIGUOUS (a generic tar.gz-repository
     * bundle is not necessarily an npm/cargo package) and require content inspection. This is
     * the fix for the Goat Rodeo survey incident ({@code repo_ea.tgz} routed to npm by name).
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
            // Ambiguous - need content inspection (a .tgz is not proof of npm)
            return Optional.empty();
        }
        if (lower.endsWith(".crate")) {
            // Ambiguous - need content inspection
            return Optional.empty();
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
     * Route from a MIME type using either a path (fresh streams, no copies) or a caller stream
     * (bounded spooling for ambiguous archive formats).
     */
    private static Optional<Ecosystem> routeFromMime(
            String filename,
            String mimeType,
            Path path,
            BufferedInputStream callerStream) throws IOException {

        String lowerMime = mimeType.toLowerCase(Locale.ROOT);
        String lowerFilename = filename.toLowerCase(Locale.ROOT);

        // Go modules have @v in the path
        if (filename.contains("@v")) {
            return Optional.of(Ecosystem.GO);
        }

        // Conda v2 - ZIP with .tar.zst inside; PyPI wheels; Packagist; Go modules; LuaRocks
        if (lowerMime.equals("application/zip") || lowerFilename.endsWith(".conda")) {
            if (path != null) {
                return disambiguateZip(path, filename);
            }
            return disambiguateZipStream(callerStream, filename);
        }

        // GZIP tar - npm, PyPI, Crates, CPAN
        if (lowerMime.equals("application/gzip") || lowerMime.equals("application/x-gzip") ||
            lowerFilename.endsWith(".tgz") || lowerFilename.endsWith(".crate") ||
            lowerFilename.endsWith(".tar.gz")) {
            if (path != null) {
                return scanGzipTar(path, filename);
            }
            return scanGzipTarStream(callerStream, filename);
        }

        // Plain tar - RubyGems, Hex
        if (lowerMime.equals("application/x-tar") || lowerFilename.endsWith(".gem")) {
            InputStream stream;
            if (path != null) {
                stream = new BufferedInputStream(Files.newInputStream(path), DETECTION_BUFFER_SIZE);
            } else {
                stream = callerStream;
            }
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

    /**
     * Bounded content scan of a gzip-tar file (Phase 7: no unbounded copy, no unbounded
     * decompression). Markers are the shared strict predicates; a routing budget trip or a
     * marker-less archive fails closed with {@code Optional.empty()}.
     */
    private static Optional<Ecosystem> scanGzipTar(Path file, String filename) throws IOException {
        // Compressed-input cap (BoundedInputStream) + decompressed-scan cap (BoundedInflateStream)
        // + entry-count cap. GZIP below for legacy/native compatibility.
        try (InputStream raw = new BoundedInputStream(Files.newInputStream(file), MAX_ROUTER_COMPRESSED, filename);
             GZIPInputStream gzis = new GZIPInputStream(new BufferedInputStream(raw, DETECTION_BUFFER_SIZE));
             BoundedInflateStream bounded = new BoundedInflateStream(gzis, MAX_ROUTER_INFLATED, filename);
             TarArchiveInputStream tais = new TarArchiveInputStream(bounded)) {

            ArchiveEntry entry;
            boolean hasEntries = false;
            int count = 0;
            while ((entry = tais.getNextEntry()) != null) {
                if (++count > MAX_ROUTER_ENTRIES) {
                    return Optional.empty(); // too many entries to classify - fail closed
                }
                hasEntries = true;
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (NpmEntryMarker.isPackageJson(name)) {
                    return Optional.of(Ecosystem.NPM);
                }
                if (PkgInfoMarker.isSdistMarker(name)) {
                    return Optional.of(Ecosystem.PYPI);
                }
                if (CargoTomlMarker.isCargoToml(name)) {
                    return Optional.of(Ecosystem.CRATES);
                }
                if (MetaMarker.isMetaFile(name)) {
                    return Optional.of(Ecosystem.CPAN);
                }
            }

            if (!hasEntries) {
                throw new AnnattoException.MalformedPackageException("GZIP archive contains no entries");
            }
            return Optional.empty();
        } catch (AnnattoException.SecurityException b) {
            // Routing budget tripped without a marker: cannot classify - fail closed.
            return Optional.empty();
        } catch (java.util.zip.ZipException e) {
            // Corrupted GZIP
            throw new AnnattoException.MalformedPackageException("Invalid GZIP archive: " + e.getMessage());
        }
    }

    /** Bounded-route a caller-provided gzip stream by spooling it once (drains the stream). */
    private static Optional<Ecosystem> scanGzipTarStream(InputStream caller, String filename) throws IOException {
        Spool.Spooled spooled = Spool.create(caller, filename, MAX_ROUTER_COMPRESSED);
        try {
            return scanGzipTar(spooled.path(), filename);
        } finally {
            Spool.delete(spooled);
        }
    }

    /**
     * Disambiguate a ZIP file (wheels, conda v2, Go modules, Packagist, LuaRocks).
     */
    private static Optional<Ecosystem> disambiguateZip(Path file, String filename) throws IOException {
        try (ZipFile zf = new ZipFile(file.toFile())) {
            Enumeration<ZipArchiveEntry> entries = zf.getEntries();
            boolean hasEntries = false;
            int count = 0;
            while (entries.hasMoreElements()) {
                if (++count > MAX_ROUTER_ENTRIES) {
                    return Optional.empty();
                }
                hasEntries = true;
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();

                if (PkgInfoMarker.isDistInfoMetadata(name)) {
                    return Optional.of(Ecosystem.PYPI);
                }
                if (name.contains("@v")) {
                    return Optional.of(Ecosystem.GO);
                }
                if (name.equals("composer.json") || name.endsWith("/composer.json")) {
                    return Optional.of(Ecosystem.PACKAGIST);
                }
                if (name.endsWith(".tar.zst") || name.endsWith(".tar.bz2")) {
                    return Optional.of(Ecosystem.CONDA);
                }
                if (name.endsWith(".rockspec")) {
                    return Optional.of(Ecosystem.LUAROCKS);
                }
            }

            if (!hasEntries) {
                throw new AnnattoException.MalformedPackageException("ZIP archive contains no entries");
            }
            return Optional.empty();
        }
    }

    /** Disambiguate a caller-provided ZIP stream by spooling once (ZipFile needs a seekable file). */
    private static Optional<Ecosystem> disambiguateZipStream(InputStream caller, String filename) throws IOException {
        Spool.Spooled spooled = Spool.create(caller, filename, MAX_ROUTER_COMPRESSED);
        try {
            return disambiguateZip(spooled.path(), filename);
        } finally {
            Spool.delete(spooled);
        }
    }

    /**
     * Disambiguate plain tar files (RubyGems vs Hex).
     */
    private static Optional<Ecosystem> disambiguatePlainTar(InputStream stream) throws IOException {
        BufferedInputStream bis;
        if (stream instanceof BufferedInputStream) {
            bis = (BufferedInputStream) stream;
        } else {
            bis = new BufferedInputStream(stream, DETECTION_BUFFER_SIZE);
        }
        bis.mark(DETECTION_BUFFER_SIZE);

        try (TarArchiveInputStream tais = new TarArchiveInputStream(bis)) {
            ArchiveEntry entry;
            while ((entry = tais.getNextEntry()) != null) {
                String name = entry.getName();

                // RubyGems: metadata.gz
                if (name.equals("metadata.gz")) {
                    bis.reset();
                    return Optional.of(Ecosystem.RUBYGEMS);
                }
                // Hex: metadata.config
                if (name.equals("metadata.config")) {
                    bis.reset();
                    return Optional.of(Ecosystem.HEX);
                }
            }

            bis.reset();
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