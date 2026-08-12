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

import io.spicelabs.annatto.AnnattoException.MalformedPackageException;
import io.spicelabs.annatto.AnnattoException.UnknownFormatException;
import io.spicelabs.annatto.ecosystem.cocoapods.CocoapodsPackage;
import io.spicelabs.annatto.ecosystem.conda.CondaPackage;
import io.spicelabs.annatto.ecosystem.cpan.CpanPackage;
import io.spicelabs.annatto.ecosystem.crates.CratesPackage;
import io.spicelabs.annatto.ecosystem.go.GoPackage;
import io.spicelabs.annatto.ecosystem.hex.HexPackage;
import io.spicelabs.annatto.ecosystem.luarocks.LuarocksPackage;
import io.spicelabs.annatto.ecosystem.npm.NpmPackage;
import io.spicelabs.annatto.ecosystem.packagist.PackagistPackage;
import io.spicelabs.annatto.ecosystem.pypi.PyPIPackage;
import io.spicelabs.annatto.ecosystem.rubygems.RubygemsPackage;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.Spool;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Main entry point for reading language packages.
 *
 * <p>Phase 7 (Bug 1/Bug 2):
 * <ul>
 *   <li>{@code read(Path,...)} routes with content-required disambiguation on a BOUNDED
 *       scanning pass (no temp copy); packages read directly from the caller path.</li>
 *   <li>{@code read(InputStream,...)} SPOOLS the stream ONCE (bounded), routes from the
 *       spooled file, and hands the owned spooled file to the package factory - fixing both
 *       the double-spool and the caller-stream-drain bug where the router consumed the
 *       stream before the package factory saw it.</li>
 * </ul>
 *
 * <p>Threading model (ADR-004): Annatto executes on a SINGLE thread. {@code read(...)} is
 * invoked by one thread and the returned {@link LanguagePackage} is read by that same thread.
 * Concurrent {@code read()}/{@code streamEntries()}/{@code close()} is OUT OF SCOPE and not
 * guaranteed; the Phase 7+ streaming/budget code is not synchronized for concurrent callers.
 *
 * <p>Claims:
 * <ul>
 *   <li>Stateless - no mutable state between sequential calls (verified by
 *       LanguagePackageReaderIntegrationTest.readerMethodsAreReentrant)</li>
 *   <li>Never returns null - always Optional or throws (verified by LanguagePackageContractTest)</li>
 * </ul>
 */
public final class LanguagePackageReader {

    private static final int DETECTION_BUFFER_SIZE = 8192;

    private LanguagePackageReader() {
        // Utility class
    }

    /**
     * Read package with Tika-detected MIME type.
     * Primary entry point for Goat Rodeo integration.
     *
     * @param path the file path
     * @param tikaMimeType MIME type from Apache Tika
     * @return parsed package
     * @throws IOException if I/O error occurs
     * @throws UnknownFormatException if MIME type not supported
     * @throws MalformedPackageException if package is corrupt
     */
    public static LanguagePackage read(Path path, String tikaMimeType)
            throws IOException, UnknownFormatException, MalformedPackageException {

        if (!EcosystemRouter.isSupported(tikaMimeType)) {
            throw new UnknownFormatException("Unsupported MIME type: " + tikaMimeType);
        }

        Optional<Ecosystem> ecosystem = EcosystemRouter.routeFromFilename(
            path.getFileName().toString(), tikaMimeType);

        if (ecosystem.isEmpty()) {
            // Content-required, bounded scan on the path (no temp copy).
            ecosystem = EcosystemRouter.route(path, tikaMimeType);
        }

        if (ecosystem.isEmpty()) {
            throw new UnknownFormatException(
                "Cannot determine ecosystem for: " + path.getFileName());
        }

        return createPackage(ecosystem.get(), path);
    }

    /**
     * Read from stream with Tika-detected MIME type.
     *
     * <p>Phase 7: ambiguous streams are spooled ONCE; the owning package adopts the spooled
     * file (its {@code close()} deletes it). This prevents (a) the old whole-file buffering
     * OOM and (b) the router draining the caller's stream before the package factory reads it.
     *
     * @param stream the input stream
     * @param filename original filename for hinting
     * @param tikaMimeType MIME type from Apache Tika
     * @return parsed package
     * @throws IOException if I/O error occurs
     * @throws UnknownFormatException if MIME type not supported
     * @throws MalformedPackageException if package is corrupt
     */
    public static LanguagePackage read(InputStream stream, String filename, String tikaMimeType)
            throws IOException, UnknownFormatException, MalformedPackageException {

        if (!EcosystemRouter.isSupported(tikaMimeType)) {
            throw new UnknownFormatException("Unsupported MIME type: " + tikaMimeType);
        }

        String base = basename(filename);
        Optional<Ecosystem> ecosystem = EcosystemRouter.routeFromFilename(base, tikaMimeType);

        if (ecosystem.isEmpty()) {
            // Content inspection: spool once, route from the spooled file, hand it over.
            Spool.Spooled spooled = Spool.create(stream, base, Limits.DEFAULT.spoolBytes());
            try {
                ecosystem = EcosystemRouter.route(spooled.path(), tikaMimeType);
            } catch (IOException e) {
                Spool.delete(spooled);
                throw e;
            }
            if (ecosystem.isEmpty()) {
                Spool.delete(spooled);
                throw new UnknownFormatException("Cannot determine ecosystem for: " + base);
            }
            return createPackageFromOwnedSpool(ecosystem.get(), spooled, base);
        }

        return createPackageFromStream(ecosystem.get(), stream, base);
    }

    /**
     * Read with auto-detection (uses content inspection).
     * Convenience method for standalone use.
     *
     * @param path the file path
     * @return parsed package
     * @throws IOException if I/O error occurs
     * @throws UnknownFormatException if format cannot be determined
     * @throws MalformedPackageException if package is corrupt
     */
    public static LanguagePackage read(Path path)
            throws IOException, UnknownFormatException, MalformedPackageException {

        Optional<Ecosystem> ecosystem = EcosystemRouter.route(path);

        if (ecosystem.isEmpty()) {
            throw new UnknownFormatException(
                "Cannot determine ecosystem for: " + path.getFileName());
        }

        return createPackage(ecosystem.get(), path);
    }

    /**
     * Check if MIME type is supported without parsing.
     *
     * @param tikaMimeType MIME type from Apache Tika
     * @return true if supported
     */
    public static boolean isSupported(String tikaMimeType) {
        if (tikaMimeType == null) {
            return false;
        }
        return EcosystemRouter.isSupported(tikaMimeType);
    }

    /**
     * Get all supported MIME types.
     *
     * @return immutable set of supported MIME type strings
     */
    public static Set<String> supportedMimeTypes() {
        return EcosystemRouter.supportedMimeTypes();
    }

    /**
     * Detect ecosystem from path without full parse.
     * Uses content inspection for disambiguation.
     *
     * @param path the file path
     * @return detected ecosystem, or empty if cannot determine
     * @throws IOException if I/O error occurs
     */
    public static Optional<Ecosystem> detect(Path path) throws IOException {
        return EcosystemRouter.route(path);
    }

    /**
     * Create a package from a file path (phase 7: reads directly from the caller's path).
     */
    private static LanguagePackage createPackage(Ecosystem ecosystem, Path path)
            throws IOException, AnnattoException.MalformedPackageException, AnnattoException.UnknownFormatException {
        return switch (ecosystem) {
            case NPM -> NpmPackage.fromPath(path);
            case PYPI -> PyPIPackage.fromPath(path);
            case CRATES -> CratesPackage.fromPath(path);
            case GO -> GoPackage.fromPath(path);
            case RUBYGEMS -> RubygemsPackage.fromPath(path);
            case PACKAGIST -> PackagistPackage.fromPath(path);
            case CONDA -> CondaPackage.fromPath(path);
            case COCOAPODS -> CocoapodsPackage.fromPath(path);
            case CPAN -> CpanPackage.fromPath(path);
            case HEX -> HexPackage.fromPath(path);
            case LUAROCKS -> LuarocksPackage.fromPath(path);
        };
    }

    /**
     * Create a package from a stream (immutable filename, sanity for error messages).
     */
    private static LanguagePackage createPackageFromStream(
            Ecosystem ecosystem, InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException, AnnattoException.UnknownFormatException {
        return switch (ecosystem) {
            case NPM -> NpmPackage.fromStream(stream, filename);
            case PYPI -> PyPIPackage.fromStream(stream, filename);
            case CRATES -> CratesPackage.fromStream(stream, filename);
            case GO -> GoPackage.fromStream(stream, filename);
            case RUBYGEMS -> RubygemsPackage.fromStream(stream, filename);
            case PACKAGIST -> PackagistPackage.fromStream(stream, filename);
            case CONDA -> CondaPackage.fromStream(stream, filename);
            case COCOAPODS -> CocoapodsPackage.fromStream(stream, filename);
            case CPAN -> CpanPackage.fromStream(stream, filename);
            case HEX -> HexPackage.fromStream(stream, filename);
            case LUAROCKS -> LuarocksPackage.fromStream(stream, filename);
        };
    }

    /**
     * Create a package over the reader's OWNED spool (Phase 7/8). All eleven ecosystems now
     * adopt the spool (their {@code close()} deletes it, S-5). The spool is deleted in
     * {@code finally} if package construction fails.
     */
    private static LanguagePackage createPackageFromOwnedSpool(
            Ecosystem ecosystem, Spool.Spooled spooled, String filename)
            throws IOException, AnnattoException.MalformedPackageException, AnnattoException.UnknownFormatException {
        try {
            return switch (ecosystem) {
                case NPM -> NpmPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case PYPI -> PyPIPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case CRATES -> CratesPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case GO -> GoPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case RUBYGEMS -> RubygemsPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case PACKAGIST -> PackagistPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case CONDA -> CondaPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case COCOAPODS -> CocoapodsPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case CPAN -> CpanPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case HEX -> HexPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
                case LUAROCKS -> LuarocksPackage.adoptSpool(spooled, filename, Limits.DEFAULT);
            };
        } catch (IOException | RuntimeException e) {
            Spool.delete(spooled);
            throw e;
        }
    }

    /** Strip any directory components from a caller-supplied filename (ADR-005 sanitization). */
    private static String basename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return slash < 0 ? filename : filename.substring(slash + 1);
    }
}