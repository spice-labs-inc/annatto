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

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Main entry point for reading language packages.
 *
 * <p>Claims:
 * <ul>
 *   <li>Thread-safe for all static methods (verified by ThreadSafetyTest)</li>
 *   <li>Stateless - no mutable state between calls (verified by ThreadSafetyTest)</li>
 *   <li>Never returns null - always Optional or throws (verified by LanguagePackageContractTest)</li>
 *   <li>All methods complete within 1 second for valid input (verified by MimeTypeFuzzTest)</li>
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
     * <p>Test: EcosystemRouterDisambiguationTest validates routing for all formats</p>
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
            // Need content inspection
            try (InputStream is = Files.newInputStream(path)) {
                BufferedInputStream bis = new BufferedInputStream(is, DETECTION_BUFFER_SIZE);
                ecosystem = EcosystemRouter.route(path, tikaMimeType, bis);
            }
        }

        if (ecosystem.isEmpty()) {
            throw new UnknownFormatException(
                "Cannot determine ecosystem for: " + path.getFileName());
        }

        return createPackage(ecosystem.get(), path.toString());
    }

    /**
     * Read from stream with Tika-detected MIME type.
     *
     * <p>Test: StreamingResourceManagementTest validates stream handling</p>
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

        Optional<Ecosystem> ecosystem = EcosystemRouter.routeFromFilename(filename, tikaMimeType);

        if (ecosystem.isEmpty()) {
            // Need content inspection
            BufferedInputStream bis;
            if (stream instanceof BufferedInputStream) {
                bis = (BufferedInputStream) stream;
            } else {
                bis = new BufferedInputStream(stream, DETECTION_BUFFER_SIZE);
            }
            ecosystem = EcosystemRouter.route(Path.of(filename), tikaMimeType, bis);
        }

        if (ecosystem.isEmpty()) {
            throw new UnknownFormatException("Cannot determine ecosystem for: " + filename);
        }

        return createPackageFromStream(ecosystem.get(), stream, filename);
    }

    /**
     * Read with auto-detection (uses internal Tika if available).
     * Convenience method for standalone use.
     *
     * <p>Test: EcosystemRouterDisambiguationTest validates detection</p>
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

        return createPackage(ecosystem.get(), path.toString());
    }

    /**
     * Check if MIME type is supported without parsing.
     *
     * <p>Test: MimeTypeFuzzTest.forall(String).routerNeverThrows</p>
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
     * <p>Test: LanguagePackageContractTest.supportedMimeTypesNonEmpty</p>
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
     * <p>Test: EcosystemRouterDisambiguationTest validates all detection paths</p>
     *
     * @param path the file path
     * @return detected ecosystem, or empty if cannot determine
     * @throws IOException if I/O error occurs
     */
    public static Optional<Ecosystem> detect(Path path) throws IOException {
        return EcosystemRouter.route(path);
    }

    /**
     * Create a package from a file path.
     */
    private static LanguagePackage createPackage(Ecosystem ecosystem, String path)
            throws IOException, AnnattoException.MalformedPackageException, AnnattoException.UnknownFormatException {
        return switch (ecosystem) {
            case NPM -> NpmPackage.fromPath(Path.of(path));
            case PYPI -> PyPIPackage.fromPath(Path.of(path));
            case CRATES -> CratesPackage.fromPath(Path.of(path));
            case GO -> GoPackage.fromPath(Path.of(path));
            case RUBYGEMS -> RubygemsPackage.fromPath(Path.of(path));
            case PACKAGIST -> PackagistPackage.fromPath(Path.of(path));
            case CONDA -> CondaPackage.fromPath(Path.of(path));
            case COCOAPODS -> CocoapodsPackage.fromPath(Path.of(path));
            case CPAN -> CpanPackage.fromPath(Path.of(path));
            case HEX -> HexPackage.fromPath(Path.of(path));
            case LUAROCKS -> LuarocksPackage.fromPath(Path.of(path));
        };
    }

    /**
     * Create a package from a stream.
     */
    /**
     * Create a package from a stream.
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
}
