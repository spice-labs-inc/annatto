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
package io.spicelabs.annatto.conda;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.spicelabs.annatto.AnnattoException.MalformedPackageException;
import io.spicelabs.annatto.AnnattoException.MetadataExtractionException;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import io.airlift.compress.zstd.ZstdInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts metadata from Conda artifacts in both legacy ({@code .tar.bz2}) and
 * modern ({@code .conda}) formats.
 *
 * <p>This class is stateless; all methods are pure functions. Uses a two-step pattern:
 * <ol>
 *   <li>{@link #extractFromArchive} — extracts raw JSON from the archive</li>
 *   <li>{@link #buildMetadataResult} — parses JSON and maps to normalized {@link MetadataResult}</li>
 * </ol>
 *
 * <p>Covers {@link CondaQuirks} Q1 (two formats), Q5 (constrains ignored),
 * Q6 (match spec deps), Q7 (description from about.json), Q8 (timestamp millis).</p>
 */
public final class CondaMetadataExtractor {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB per file
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+00:00'").withZone(ZoneOffset.UTC);

    /** Detected archive format for a Conda package. */
    enum CondaFormat {
        /** Modern .conda format (ZIP with zstd-compressed inner tars). */
        CONDA,
        /** Legacy .tar.bz2 format (bzip2-compressed tar archive). */
        TAR_BZ2
    }

    private CondaMetadataExtractor() {
        // static utility class
    }

    /**
     * Extracts metadata from the given Conda artifact stream.
     *
     * @param inputStream the artifact input stream
     * @param filename    the artifact filename (used for format detection and error reporting)
     * @return the extracted metadata result
     * @throws MetadataExtractionException if the archive cannot be read or parsed
     * @throws MalformedPackageException   if no info/index.json is found
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream inputStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        CondaArchiveData data = extractFromArchive(inputStream, filename);
        return buildMetadataResult(data);
    }

    /**
     * Detects the archive format from the filename extension.
     *
     * @param filename the artifact filename
     * @return the detected format
     * @throws MalformedPackageException if the extension is not recognized
     */
    static @NotNull CondaFormat detectFormat(@NotNull String filename) throws MalformedPackageException {
        if (filename.endsWith(".conda")) {
            return CondaFormat.CONDA;
        } else if (filename.endsWith(".tar.bz2")) {
            return CondaFormat.TAR_BZ2;
        }
        throw new MalformedPackageException("Unrecognized Conda archive extension: " + filename);
    }

    /**
     * Extracts raw JSON data from a Conda archive (either format).
     *
     * @param stream   the archive input stream
     * @param filename the filename for format detection and error reporting
     * @return the extracted archive data containing raw JSON
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no info/index.json found
     */
    static @NotNull CondaArchiveData extractFromArchive(@NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        CondaFormat format = detectFormat(filename);
        return switch (format) {
            case CONDA -> extractFromCondaFormat(stream, filename);
            case TAR_BZ2 -> extractFromTarBz2Format(stream, filename);
        };
    }

    /**
     * Extracts info/index.json and info/about.json from a .conda (ZIP) archive.
     * The .conda format is a ZIP file containing info-*.tar.zst (zstd-compressed tar
     * with the info/ directory) and pkg-*.tar.zst (package data).
     */
    private static @NotNull CondaArchiveData extractFromCondaFormat(
            @NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (ZipInputStream zipIn = new ZipInputStream(stream)) {
            ZipEntry zipEntry;
            while ((zipEntry = zipIn.getNextEntry()) != null) {
                String entryName = zipEntry.getName();
                if (entryName.contains("..")) {
                    continue; // path traversal rejection
                }
                if (entryName.startsWith("info-") && entryName.endsWith(".tar.zst")) {
                    return extractInfoFromZstdTar(zipIn, filename);
                }
            }
            throw new MalformedPackageException(
                    "No info-*.tar.zst entry found in .conda archive: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read .conda archive: " + filename, e);
        }
    }

    /**
     * Extracts info/index.json and info/about.json from an info-*.tar.zst stream.
     */
    private static @NotNull CondaArchiveData extractInfoFromZstdTar(
            @NotNull InputStream zstdStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (ZstdInputStream zstdIn = new ZstdInputStream(zstdStream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(zstdIn, StandardCharsets.UTF_8.name())) {
            String indexJson = null;
            String aboutJson = null;

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.contains("..")) {
                    continue; // path traversal rejection
                }

                if (isInfoIndexJson(entryName)) {
                    if (entry.getSize() > MAX_FILE_SIZE) {
                        throw new MetadataExtractionException(
                                "info/index.json exceeds size limit: " + filename);
                    }
                    indexJson = readStreamToString(tarIn, entry.getSize());
                } else if (isInfoAboutJson(entryName)) {
                    if (entry.getSize() > MAX_FILE_SIZE) {
                        throw new MetadataExtractionException(
                                "info/about.json exceeds size limit: " + filename);
                    }
                    aboutJson = readStreamToString(tarIn, entry.getSize());
                }

                // Early exit once we have both files
                if (indexJson != null && aboutJson != null) {
                    break;
                }
            }

            if (indexJson == null) {
                throw new MalformedPackageException(
                        "No info/index.json found in info tar.zst: " + filename);
            }
            return new CondaArchiveData(indexJson, aboutJson);
        } catch (MalformedPackageException | MetadataExtractionException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException(
                    "Failed to read info tar.zst in .conda archive: " + filename, e);
        }
    }

    /**
     * Extracts info/index.json and info/about.json from a .tar.bz2 archive.
     */
    private static @NotNull CondaArchiveData extractFromTarBz2Format(
            @NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (BZip2CompressorInputStream bz2In = new BZip2CompressorInputStream(stream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(bz2In, StandardCharsets.UTF_8.name())) {
            String indexJson = null;
            String aboutJson = null;

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.contains("..")) {
                    continue; // path traversal rejection
                }
                if (entry.getSize() > MAX_FILE_SIZE) {
                    continue; // skip oversized files
                }

                if (isInfoIndexJson(entryName)) {
                    indexJson = readStreamToString(tarIn, entry.getSize());
                } else if (isInfoAboutJson(entryName)) {
                    aboutJson = readStreamToString(tarIn, entry.getSize());
                }

                // Early exit once we have both files
                if (indexJson != null && aboutJson != null) {
                    break;
                }
            }

            if (indexJson == null) {
                throw new MalformedPackageException(
                        "No info/index.json found in .tar.bz2 archive: " + filename);
            }
            return new CondaArchiveData(indexJson, aboutJson);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException(
                    "Failed to read .tar.bz2 archive: " + filename, e);
        }
    }

    /**
     * Builds a {@link MetadataResult} from extracted archive data.
     *
     * @param data the raw JSON data from the archive
     * @return the normalized metadata result
     * @throws MetadataExtractionException if JSON parsing fails
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull CondaArchiveData data)
            throws MetadataExtractionException {
        JsonObject indexJson;
        try {
            indexJson = GSON.fromJson(data.indexJson(), JsonObject.class);
        } catch (Exception e) {
            throw new MetadataExtractionException("Failed to parse index.json", e);
        }

        JsonObject aboutJson = null;
        if (data.aboutJson() != null) {
            try {
                aboutJson = GSON.fromJson(data.aboutJson(), JsonObject.class);
            } catch (Exception e) {
                // about.json is optional; parsing failure is non-fatal
                aboutJson = null;
            }
        }

        Optional<String> name = getOptionalString(indexJson, "name");
        Optional<String> version = getOptionalString(indexJson, "version");
        Optional<String> description = extractDescription(aboutJson);
        Optional<String> license = getOptionalString(indexJson, "license");
        Optional<String> publishedAt = convertTimestamp(indexJson);

        List<ParsedDependency> dependencies = parseDependencies(indexJson);

        return new MetadataResult(
                EcosystemId.CONDA,
                name,
                name, // simpleName == name (no vendor concept in conda)
                version,
                description,
                license,
                Optional.empty(), // publisher always null (Q2)
                publishedAt,
                dependencies
        );
    }

    /**
     * Extracts the build string from parsed index.json.
     *
     * @param data the archive data
     * @return the build string, or empty if absent
     * @throws MetadataExtractionException if JSON parsing fails
     */
    static @NotNull Optional<String> extractBuild(@NotNull CondaArchiveData data)
            throws MetadataExtractionException {
        JsonObject indexJson;
        try {
            indexJson = GSON.fromJson(data.indexJson(), JsonObject.class);
        } catch (Exception e) {
            throw new MetadataExtractionException("Failed to parse index.json for build", e);
        }
        return getOptionalString(indexJson, "build");
    }

    /**
     * Extracts the subdir from parsed index.json.
     *
     * @param data the archive data
     * @return the subdir, or empty if absent
     * @throws MetadataExtractionException if JSON parsing fails
     */
    static @NotNull Optional<String> extractSubdir(@NotNull CondaArchiveData data)
            throws MetadataExtractionException {
        JsonObject indexJson;
        try {
            indexJson = GSON.fromJson(data.indexJson(), JsonObject.class);
        } catch (Exception e) {
            throw new MetadataExtractionException("Failed to parse index.json for subdir", e);
        }
        return getOptionalString(indexJson, "subdir");
    }

    /**
     * Extracts description from about.json. Prefers "summary" field, falls back to
     * "description" field. Returns empty if about.json is null or neither field exists (Q7).
     *
     * @param aboutJson the parsed about.json object (may be null)
     * @return the description, or empty
     */
    static @NotNull Optional<String> extractDescription(@Nullable JsonObject aboutJson) {
        if (aboutJson == null) {
            return Optional.empty();
        }
        Optional<String> summary = getOptionalString(aboutJson, "summary");
        if (summary.isPresent()) {
            return summary;
        }
        return getOptionalString(aboutJson, "description");
    }

    /**
     * Converts the timestamp field from index.json (milliseconds since epoch)
     * to ISO 8601 format (Q8).
     *
     * @param indexJson the parsed index.json object
     * @return the ISO 8601 timestamp, or empty if absent
     */
    static @NotNull Optional<String> convertTimestamp(@NotNull JsonObject indexJson) {
        if (!indexJson.has("timestamp") || indexJson.get("timestamp").isJsonNull()) {
            return Optional.empty();
        }
        try {
            long millis = indexJson.get("timestamp").getAsLong();
            Instant instant = Instant.ofEpochMilli(millis);
            return Optional.of(ISO_FORMATTER.format(instant));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Parses dependencies from the "depends" array in index.json.
     * Each entry is a match spec string: "name [version_constraint] [build_string]" (Q6).
     * All conda dependencies are scope "runtime" (no dev deps in conda).
     * The "constrains" field is ignored (Q5).
     *
     * @param indexJson the parsed index.json object
     * @return the list of parsed dependencies
     */
    static @NotNull List<ParsedDependency> parseDependencies(@NotNull JsonObject indexJson) {
        if (!indexJson.has("depends") || indexJson.get("depends").isJsonNull()) {
            return List.of();
        }
        JsonElement dependsElem = indexJson.get("depends");
        if (!dependsElem.isJsonArray()) {
            return List.of();
        }
        JsonArray depends = dependsElem.getAsJsonArray();
        List<ParsedDependency> deps = new ArrayList<>();
        for (JsonElement elem : depends) {
            if (elem.isJsonNull()) {
                continue;
            }
            String matchSpec = elem.getAsString();
            if (matchSpec == null || matchSpec.isEmpty()) {
                continue;
            }
            deps.add(parseMatchSpec(matchSpec));
        }
        return List.copyOf(deps);
    }

    /**
     * Parses a conda match spec string into name and version constraint.
     * Format: "name [version_constraint] [build_string]"
     * Split on first space: name is first token, version constraint is second token.
     * Third token onwards (build string) is discarded (Q6).
     *
     * @param matchSpec the match spec string
     * @return the parsed dependency
     */
    static @NotNull ParsedDependency parseMatchSpec(@NotNull String matchSpec) {
        String trimmed = matchSpec.trim();
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace < 0) {
            // Name only, no version constraint
            return new ParsedDependency(trimmed, Optional.empty(), Optional.of("runtime"));
        }
        String name = trimmed.substring(0, firstSpace);
        String rest = trimmed.substring(firstSpace + 1).trim();

        // The rest may contain version constraint and build string separated by spaces
        // We take everything up to (but not including) a build string as the version constraint
        // Build strings typically contain underscores and hashes, but the simplest approach
        // is to take the entire rest as version constraint, since that matches what
        // conda's native tools report
        if (rest.isEmpty()) {
            return new ParsedDependency(name, Optional.empty(), Optional.of("runtime"));
        }
        return new ParsedDependency(name, Optional.of(rest), Optional.of("runtime"));
    }

    /**
     * Safely extracts an optional string field from a JSON object.
     *
     * @param json      the JSON object
     * @param fieldName the field name
     * @return the field value, or empty if absent/null/empty
     */
    static @NotNull Optional<String> getOptionalString(@NotNull JsonObject json, @NotNull String fieldName) {
        if (json.has(fieldName) && !json.get(fieldName).isJsonNull()) {
            String value = json.get(fieldName).getAsString();
            if (value != null && !value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if a tar entry name is info/index.json (with or without leading ./).
     */
    static boolean isInfoIndexJson(@NotNull String entryName) {
        return entryName.equals("info/index.json") || entryName.equals("./info/index.json");
    }

    /**
     * Checks if a tar entry name is info/about.json (with or without leading ./).
     */
    static boolean isInfoAboutJson(@NotNull String entryName) {
        return entryName.equals("info/about.json") || entryName.equals("./info/about.json");
    }

    private static @NotNull String readStreamToString(@NotNull InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                size > 0 ? (int) Math.min(size, MAX_FILE_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_FILE_SIZE) {
                throw new IOException("File content exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    /**
     * Holds the raw JSON extracted from a Conda archive before parsing.
     *
     * @param indexJson the raw info/index.json content (never null)
     * @param aboutJson the raw info/about.json content (may be null if absent)
     */
    record CondaArchiveData(@NotNull String indexJson, @Nullable String aboutJson) {
    }
}
