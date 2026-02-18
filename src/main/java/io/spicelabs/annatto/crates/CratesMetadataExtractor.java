/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spicelabs.annatto.crates;

import io.spicelabs.annatto.AnnattoException.MalformedPackageException;
import io.spicelabs.annatto.AnnattoException.MetadataExtractionException;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Extracts metadata from Crates.io artifact contents ({@code .crate} gzip tar archives
 * containing {@code Cargo.toml}).
 *
 * <p>This class is stateless; all methods are pure functions. Uses a two-step pattern:
 * <ol>
 *   <li>{@link #extractCargoTomlFromCrate} — extracts raw Cargo.toml text from the archive</li>
 *   <li>{@link #buildMetadataResult} — parses TOML and maps to normalized {@link MetadataResult}</li>
 * </ol>
 *
 * <p>Covers {@link CratesQuirks} Q1 (TOML format), Q2 (feature flags / optional deps),
 * Q3 (build-dependencies), Q4 (renamed dependencies), Q5 (edition field), Q6 (no Cargo.lock).</p>
 */
public final class CratesMetadataExtractor {

    private static final int MAX_CARGO_TOML_SIZE = 10 * 1024 * 1024; // 10 MB

    private CratesMetadataExtractor() {
        // utility class
    }

    /**
     * Extracts metadata from the given Crates.io artifact stream.
     *
     * @param inputStream the artifact input stream (gzip tar format)
     * @param filename    the artifact filename (for error reporting)
     * @return the extracted metadata result
     * @throws MetadataExtractionException if the archive cannot be read or parsed
     * @throws MalformedPackageException   if no Cargo.toml is found in the archive
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream inputStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        CargoTomlData data = extractCargoTomlFromCrate(inputStream, filename);
        return buildMetadataResult(data);
    }

    /**
     * Builds a {@link MetadataResult} from extracted Cargo.toml data.
     * Parses the TOML text and maps fields to the normalized metadata model.
     *
     * @param data the raw Cargo.toml data extracted from the archive
     * @return the normalized metadata result
     * @throws MetadataExtractionException if TOML parsing fails
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull CargoTomlData data)
            throws MetadataExtractionException {
        TomlParseResult toml;
        try {
            toml = Toml.parse(data.rawCargoToml());
        } catch (Exception e) {
            throw new MetadataExtractionException("Failed to parse Cargo.toml as TOML", e);
        }

        String name = toml.getString("package.name");
        String version = toml.getString("package.version");
        String description = toml.getString("package.description");
        String license = toml.getString("package.license");
        String publisher = extractPublisher(toml);

        List<ParsedDependency> dependencies = parseDependencies(toml);

        return new MetadataResult(
                EcosystemId.CRATES,
                Optional.ofNullable(name).filter(s -> !s.isEmpty()),
                Optional.ofNullable(name).filter(s -> !s.isEmpty()), // simpleName == name for crates
                Optional.ofNullable(version).filter(s -> !s.isEmpty()),
                Optional.ofNullable(description).filter(s -> !s.isEmpty()),
                Optional.ofNullable(license).filter(s -> !s.isEmpty()),
                Optional.ofNullable(publisher).filter(s -> !s.isEmpty()),
                Optional.empty(),
                dependencies
        );
    }

    /**
     * Extracts raw Cargo.toml text from a .crate (gzip tar) archive.
     *
     * @param stream   the gzip tar input stream
     * @param filename the filename for error reporting
     * @return the extracted Cargo.toml data
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no Cargo.toml found
     */
    static @NotNull CargoTomlData extractCargoTomlFromCrate(@NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(stream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn, StandardCharsets.UTF_8.name())) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (isCargoToml(entryName)) {
                    if (entry.getSize() > MAX_CARGO_TOML_SIZE) {
                        throw new MetadataExtractionException(
                                "Cargo.toml exceeds size limit (" + entry.getSize() + " bytes): " + filename);
                    }
                    String cargoToml = readStreamToString(tarIn, entry.getSize());
                    return new CargoTomlData(cargoToml);
                }
            }
            throw new MalformedPackageException("No Cargo.toml found in crate archive: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (MetadataExtractionException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read crate archive: " + filename, e);
        }
    }

    /**
     * Checks if a tar entry name represents the root-level Cargo.toml.
     * Pattern: exactly {@code <dir>/Cargo.toml} at depth 2 (one directory component).
     * Rejects path traversal (entries with {@code ..}).
     *
     * @param entryName the tar entry name
     * @return true if this is the crate's root Cargo.toml
     */
    static boolean isCargoToml(@NotNull String entryName) {
        if (entryName.contains("..")) {
            return false;
        }
        String[] parts = entryName.split("/");
        return parts.length == 2 && parts[1].equals("Cargo.toml");
    }

    /**
     * Extracts the publisher from the first author in the Cargo.toml {@code package.authors} array.
     * Strips email addresses in angle brackets (e.g., {@code "Alice <alice@example.com>"} → {@code "Alice"}).
     *
     * @param toml the parsed TOML result
     * @return the publisher name, or null if no authors
     */
    static @Nullable String extractPublisher(@NotNull TomlParseResult toml) {
        TomlArray authors = toml.getArray("package.authors");
        if (authors == null || authors.isEmpty()) {
            return null;
        }
        String firstAuthor = authors.getString(0);
        if (firstAuthor == null || firstAuthor.isEmpty()) {
            return null;
        }
        // Strip email in angle brackets: "Name <email>" → "Name"
        int angleIdx = firstAuthor.indexOf('<');
        if (angleIdx > 0) {
            return firstAuthor.substring(0, angleIdx).trim();
        }
        return firstAuthor.trim();
    }

    /**
     * Parses all dependencies from the Cargo.toml across all sections:
     * {@code [dependencies]}, {@code [dev-dependencies]}, {@code [build-dependencies]},
     * and target-specific variants under {@code [target.*.dependencies]}, etc.
     *
     * @param toml the parsed TOML result
     * @return all parsed dependencies
     */
    static @NotNull List<ParsedDependency> parseDependencies(@NotNull TomlParseResult toml) {
        List<ParsedDependency> deps = new ArrayList<>();

        // Top-level dependency sections
        parseDependencySection(toml.getTable("dependencies"), "runtime", deps);
        parseDependencySection(toml.getTable("dev-dependencies"), "dev", deps);
        parseDependencySection(toml.getTable("build-dependencies"), "build", deps);

        // Target-specific dependency sections
        // Keys like cfg(not(target_arch = "wasm32")) contain parens, so use List-based
        // key access to avoid tomlj's dotted key parser
        TomlTable targetTable = toml.getTable("target");
        if (targetTable != null) {
            for (String targetKey : targetTable.keySet()) {
                Object entry = targetTable.get(List.of(targetKey));
                if (entry instanceof TomlTable targetEntry) {
                    parseDependencySection(getSubTable(targetEntry, "dependencies"), "runtime", deps);
                    parseDependencySection(getSubTable(targetEntry, "dev-dependencies"), "dev", deps);
                    parseDependencySection(getSubTable(targetEntry, "build-dependencies"), "build", deps);
                }
            }
        }

        return List.copyOf(deps);
    }

    /**
     * Parses a single dependency section (e.g., {@code [dependencies]} or
     * {@code [target.*.dev-dependencies]}).
     *
     * <p>Handles dependency formats:</p>
     * <ul>
     *   <li>Simple string: {@code dep = "1.0"}</li>
     *   <li>Table: {@code dep = { version = "1.0", optional = true }}</li>
     *   <li>Renamed: {@code alias = { version = "1.0", package = "real-name" }} → name=real-name (Q4)</li>
     *   <li>Path-only: {@code dep = { path = "../local" }} → empty versionConstraint</li>
     *   <li>Git-only: {@code dep = { git = "..." }} → empty versionConstraint</li>
     * </ul>
     *
     * @param section the TOML table for a dependency section (may be null)
     * @param scope   the dependency scope ("runtime", "dev", "build")
     * @param deps    the list to add parsed dependencies to
     */
    private static void parseDependencySection(@Nullable TomlTable section, @NotNull String scope,
                                               @NotNull List<ParsedDependency> deps) {
        if (section == null) {
            return;
        }
        for (String key : section.keySet()) {
            Object value = section.get(key);
            if (value instanceof String versionStr) {
                // Simple format: dep = "1.0"
                deps.add(new ParsedDependency(
                        key,
                        Optional.of(normalizeVersionConstraint(versionStr)).filter(s -> !s.isEmpty()),
                        Optional.of(scope)
                ));
            } else if (value instanceof TomlTable depTable) {
                // Table format: dep = { version = "1.0", package = "real-name", ... }
                // Use "package" field as real name if present (Q4: renamed deps)
                String realName = depTable.getString("package");
                String depName = realName != null ? realName : key;

                String version = depTable.getString("version");
                Optional<String> versionConstraint = Optional.ofNullable(version)
                        .map(CratesMetadataExtractor::normalizeVersionConstraint)
                        .filter(s -> !s.isEmpty());

                deps.add(new ParsedDependency(
                        depName,
                        versionConstraint,
                        Optional.of(scope)
                ));
            }
        }
    }

    /**
     * Normalizes a Cargo version constraint to match the format produced by
     * {@code cargo read-manifest}. In Cargo's dependency resolution, a bare version
     * like {@code "1.0"} implicitly means {@code "^1.0"} (compatible updates).
     * {@code cargo read-manifest} makes this explicit.
     *
     * <p>Version constraints that already have an operator prefix ({@code ^}, {@code ~},
     * {@code >=}, {@code >}, {@code <=}, {@code <}, {@code =}, {@code *}) are returned as-is.</p>
     *
     * @param version the raw version constraint from Cargo.toml
     * @return the normalized version constraint
     */
    static @NotNull String normalizeVersionConstraint(@NotNull String version) {
        if (version.isEmpty()) {
            return version;
        }
        char first = version.charAt(0);
        // Already has an operator prefix
        if (first == '^' || first == '~' || first == '>' || first == '<' || first == '=' || first == '*') {
            return version;
        }
        // Bare version: add implicit caret
        return "^" + version;
    }

    /**
     * Safely gets a subtable by a single key, avoiding tomlj's dotted key parser.
     */
    private static @Nullable TomlTable getSubTable(@NotNull TomlTable table, @NotNull String key) {
        Object value = table.get(List.of(key));
        return value instanceof TomlTable t ? t : null;
    }

    private static @NotNull String readStreamToString(@NotNull InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(size > 0 ? (int) Math.min(size, MAX_CARGO_TOML_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_CARGO_TOML_SIZE) {
                throw new IOException("Cargo.toml content exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    /**
     * Holds the raw Cargo.toml text extracted from a .crate archive before parsing.
     */
    record CargoTomlData(@NotNull String rawCargoToml) {
    }
}
