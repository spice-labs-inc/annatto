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
package io.spicelabs.annatto.rubygems;

import io.spicelabs.annatto.AnnattoException.MalformedPackageException;
import io.spicelabs.annatto.AnnattoException.MetadataExtractionException;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Extracts metadata from the {@code metadata.gz} entry inside a {@code .gem} tar archive.
 *
 * <p>This class is stateless; all methods are pure functions. Uses a two-step pattern:
 * <ol>
 *   <li>{@link #extractMetadataGzFromGem} — extracts raw YAML text from the archive</li>
 *   <li>{@link #buildMetadataResult} — parses YAML and maps to normalized {@link MetadataResult}</li>
 * </ol>
 *
 * <p>Covers {@link RubygemsQuirks} Q1 (Ruby YAML tags), Q2 (description fallback),
 * Q3 (runtime vs dev deps), Q4 (version constraints), Q5 (platform field),
 * Q6 (license join).</p>
 */
public final class RubygemsMetadataExtractor {

    private static final int MAX_METADATA_SIZE = 10 * 1024 * 1024; // 10 MB

    private RubygemsMetadataExtractor() {
        // utility class
    }

    /**
     * Extracts raw YAML text from a .gem (plain tar) archive's {@code metadata.gz} entry.
     *
     * @param stream   the plain tar input stream (.gem is NOT gzip-wrapped)
     * @param filename the artifact filename for error reporting
     * @return the extracted YAML data
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no metadata.gz found
     */
    static @NotNull GemMetadataData extractMetadataGzFromGem(@NotNull InputStream stream,
            @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(stream, StandardCharsets.UTF_8.name())) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (isMetadataGz(entryName)) {
                    if (entry.getSize() > MAX_METADATA_SIZE) {
                        throw new MetadataExtractionException(
                                "metadata.gz exceeds size limit (" + entry.getSize() + " bytes): " + filename);
                    }
                    String rawYaml = readGzippedStreamToString(tarIn);
                    return new GemMetadataData(rawYaml);
                }
            }
            throw new MalformedPackageException("No metadata.gz found in gem archive: " + filename);
        } catch (MalformedPackageException | MetadataExtractionException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read gem archive: " + filename, e);
        }
    }

    /**
     * Builds a {@link MetadataResult} from extracted gem metadata YAML.
     * Strips Ruby-specific YAML tags before parsing with SnakeYAML SafeConstructor.
     *
     * @param data the raw YAML data extracted from the archive
     * @return the normalized metadata result
     * @throws MetadataExtractionException if YAML parsing fails
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull GemMetadataData data)
            throws MetadataExtractionException {
        String strippedYaml = stripRubyYamlTags(data.rawYaml());
        Map<String, Object> parsed;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            parsed = yaml.load(strippedYaml);
        } catch (Exception e) {
            throw new MetadataExtractionException("Failed to parse gem metadata YAML", e);
        }
        if (parsed == null) {
            throw new MetadataExtractionException("Gem metadata YAML parsed to null");
        }

        String name = (String) parsed.get("name");
        String version = extractVersion(parsed.get("version"));
        String description = extractDescription(parsed);
        String license = joinLicenses(castStringList(parsed.get("licenses")));
        String publisher = extractPublisher(parsed);
        List<ParsedDependency> dependencies = parseDependencies(castList(parsed.get("dependencies")));

        return new MetadataResult(
                EcosystemId.RUBYGEMS,
                Optional.ofNullable(name).filter(s -> !s.isEmpty()),
                Optional.ofNullable(name).filter(s -> !s.isEmpty()), // simpleName == name for gems
                Optional.ofNullable(version).filter(s -> !s.isEmpty()),
                Optional.ofNullable(description).filter(s -> !s.isEmpty()),
                Optional.ofNullable(license).filter(s -> !s.isEmpty()),
                Optional.ofNullable(publisher).filter(s -> !s.isEmpty()),
                Optional.empty(),
                dependencies
        );
    }

    /**
     * Checks if a tar entry name is the root-level {@code metadata.gz}.
     * Rejects path traversal (entries containing {@code ..}).
     *
     * @param entryName the tar entry name
     * @return true if this is the gem's metadata.gz
     */
    static boolean isMetadataGz(@NotNull String entryName) {
        if (entryName.contains("..")) {
            return false;
        }
        return entryName.equals("metadata.gz");
    }

    /**
     * Strips Ruby-specific YAML tags (e.g., {@code !ruby/object:Gem::Version})
     * from raw YAML text so SnakeYAML SafeConstructor can parse it.
     * (Q1: Ruby YAML tags)
     *
     * @param rawYaml the raw YAML string from metadata.gz
     * @return the YAML with Ruby tags removed
     */
    static @NotNull String stripRubyYamlTags(@NotNull String rawYaml) {
        return rawYaml.replaceAll("!ruby/\\S+", "");
    }

    /**
     * Extracts the version string from the parsed YAML version field.
     * Handles both direct String and Gem::Version wrapper map {@code {version: "1.0"}}.
     *
     * @param versionField the parsed version object
     * @return the version string, or null
     */
    @SuppressWarnings("unchecked")
    static @Nullable String extractVersion(@Nullable Object versionField) {
        if (versionField instanceof String s) {
            return s;
        }
        if (versionField instanceof Map<?, ?> m) {
            Object inner = m.get("version");
            return inner instanceof String s ? s : null;
        }
        return null;
    }

    /**
     * Extracts description from the parsed YAML map.
     * Prefers {@code summary} (short), falls back to {@code description} if summary is nil/empty.
     * (Q2: Description fallback)
     *
     * @param data the parsed YAML map
     * @return the description string, or null
     */
    static @Nullable String extractDescription(@NotNull Map<String, Object> data) {
        Object summaryObj = data.get("summary");
        if (summaryObj instanceof String s && !s.trim().isEmpty()) {
            return s;
        }
        Object descObj = data.get("description");
        if (descObj instanceof String s && !s.trim().isEmpty()) {
            return s;
        }
        return null;
    }

    /**
     * Joins a list of license strings with {@code " OR "} to form an SPDX-like expression.
     * Returns null if the list is null or empty. (Q6: License join)
     *
     * @param licenses the list of license identifiers
     * @return the joined license string, or null
     */
    static @Nullable String joinLicenses(@Nullable List<String> licenses) {
        if (licenses == null || licenses.isEmpty()) {
            return null;
        }
        return String.join(" OR ", licenses);
    }

    /**
     * Reconstructs a version constraint string from the YAML requirement structure.
     * The requirement map has a {@code requirements} key containing a list of
     * {@code [operator, {version: "x.y"}]} pairs.
     * Returns null if the requirement is the default {@code ">= 0"}.
     * (Q4: Version constraints)
     *
     * @param requirement the parsed requirement map
     * @return the constraint string, or null for default requirements
     */
    @SuppressWarnings("unchecked")
    static @Nullable String reconstructVersionConstraint(@Nullable Map<String, Object> requirement) {
        if (requirement == null) {
            return null;
        }
        Object reqList = requirement.get("requirements");
        if (!(reqList instanceof List<?> requirements)) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Object item : requirements) {
            if (!(item instanceof List<?> pair) || pair.size() < 2) {
                continue;
            }
            String op = String.valueOf(pair.get(0));
            String ver = extractVersion(pair.get(1));
            if (ver == null) {
                continue;
            }
            parts.add(op + " " + ver);
        }
        if (parts.isEmpty()) {
            return null;
        }
        String result = String.join(", ", parts);
        // Default requirement ">= 0" maps to null (Q4)
        if (result.equals(">= 0")) {
            return null;
        }
        return result;
    }

    /**
     * Maps Ruby dependency type field to Annatto scope string.
     * {@code :runtime} → "runtime", {@code :development} → "dev".
     * (Q3: Runtime vs dev deps)
     *
     * @param typeField the type value from the parsed YAML
     * @return "runtime" or "dev"
     */
    static @NotNull String mapDependencyType(@Nullable Object typeField) {
        if (typeField == null) {
            return "runtime";
        }
        String type = String.valueOf(typeField);
        if (type.equals(":development") || type.equals("development")) {
            return "dev";
        }
        return "runtime";
    }

    /**
     * Extracts the publisher from the first element of the authors list.
     *
     * @param data the parsed YAML map
     * @return the publisher name, or null
     */
    @SuppressWarnings("unchecked")
    private static @Nullable String extractPublisher(@NotNull Map<String, Object> data) {
        Object authorsObj = data.get("authors");
        if (authorsObj instanceof List<?> authors && !authors.isEmpty()) {
            Object first = authors.get(0);
            if (first instanceof String s && !s.trim().isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /**
     * Parses the dependencies list from the YAML map.
     */
    @SuppressWarnings("unchecked")
    private static @NotNull List<ParsedDependency> parseDependencies(@Nullable List<?> deps) {
        if (deps == null) {
            return List.of();
        }
        List<ParsedDependency> result = new ArrayList<>();
        for (Object item : deps) {
            if (!(item instanceof Map<?, ?> depMap)) {
                continue;
            }
            String name = (String) depMap.get("name");
            if (name == null || name.isEmpty()) {
                continue;
            }
            Map<String, Object> requirement = (Map<String, Object>) depMap.get("requirement");
            String versionConstraint = reconstructVersionConstraint(requirement);
            String scope = mapDependencyType(depMap.get("type"));

            result.add(new ParsedDependency(
                    name,
                    Optional.ofNullable(versionConstraint),
                    Optional.of(scope)
            ));
        }
        return List.copyOf(result);
    }

    private static @NotNull String readGzippedStreamToString(@NotNull InputStream gzStream) throws IOException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(gzStream)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = gzipIn.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > MAX_METADATA_SIZE) {
                    throw new IOException("metadata.gz content exceeds size limit");
                }
                baos.write(buffer, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<String> castStringList(@Nullable Object obj) {
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s) {
                    result.add(s);
                }
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable List<?> castList(@Nullable Object obj) {
        return obj instanceof List<?> list ? list : null;
    }

    /**
     * Holds the raw YAML text extracted from a .gem archive's metadata.gz before parsing.
     */
    record GemMetadataData(@NotNull String rawYaml) {
    }
}
