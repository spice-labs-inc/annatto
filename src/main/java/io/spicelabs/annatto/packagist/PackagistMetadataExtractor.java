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
package io.spicelabs.annatto.packagist;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.spicelabs.annatto.AnnattoException.MalformedPackageException;
import io.spicelabs.annatto.AnnattoException.MetadataExtractionException;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts metadata from the {@code composer.json} entry inside a Packagist zip archive.
 *
 * <p>This class is stateless; all methods are pure functions. Uses a two-step pattern:
 * <ol>
 *   <li>{@link #extractComposerJsonFromZip} — extracts raw composer.json text from the archive</li>
 *   <li>{@link #buildMetadataResult} — parses JSON and maps to normalized {@link MetadataResult}</li>
 * </ol>
 *
 * <p>Covers {@link PackagistQuirks} Q1 (version absence), Q2 (vendor/package naming),
 * Q3 (require vs require-dev + platform filtering), Q4 (replace/provide ignored),
 * Q5 (metadata-only registry), Q6 (license formats).</p>
 */
public final class PackagistMetadataExtractor {

    private static final int MAX_COMPOSER_JSON_SIZE = 10 * 1024 * 1024; // 10 MB

    private PackagistMetadataExtractor() {
        // utility class
    }

    /**
     * Extracts Packagist metadata from the given input stream.
     *
     * @param inputStream the artifact input stream (zip format)
     * @param filename    the artifact filename (for error reporting)
     * @return the extracted metadata result
     * @throws MetadataExtractionException if the archive cannot be read or parsed
     * @throws MalformedPackageException   if no composer.json is found in the archive
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream inputStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        ComposerJsonData data = extractComposerJsonFromZip(inputStream, filename);
        return buildMetadataResult(data);
    }

    /**
     * Builds a {@link MetadataResult} from extracted composer.json data.
     * Parses the JSON text and maps fields to the normalized metadata model.
     *
     * @param data the raw composer.json data extracted from the archive
     * @return the normalized metadata result
     * @throws MetadataExtractionException if JSON parsing fails
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull ComposerJsonData data)
            throws MetadataExtractionException {
        JsonObject json;
        try {
            json = JsonParser.parseString(data.rawJson()).getAsJsonObject();
        } catch (Exception e) {
            throw new MetadataExtractionException("Failed to parse composer.json as JSON", e);
        }

        Optional<String> name = getOptionalString(json, "name");
        Optional<String> simpleName = name.map(PackagistMetadataExtractor::extractSimpleName);
        Optional<String> version = getOptionalString(json, "version");
        Optional<String> description = getOptionalString(json, "description");
        Optional<String> license = extractLicense(json);
        Optional<String> publisher = extractPublisher(json);

        List<ParsedDependency> dependencies = extractDependencies(json);

        return new MetadataResult(
                EcosystemId.PACKAGIST,
                name,
                simpleName,
                version,
                description,
                license,
                publisher,
                Optional.empty(),
                dependencies
        );
    }

    /**
     * Extracts raw composer.json text from a zip archive.
     * Accepts composer.json at root or one directory level deep (GitHub zipball format).
     *
     * @param stream   the zip input stream
     * @param filename the filename for error reporting
     * @return the extracted composer.json data
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no composer.json found
     */
    static @NotNull ComposerJsonData extractComposerJsonFromZip(@NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (ZipInputStream zipIn = new ZipInputStream(stream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (isComposerJson(entryName)) {
                    long size = entry.getSize();
                    if (size > MAX_COMPOSER_JSON_SIZE) {
                        throw new MetadataExtractionException(
                                "composer.json exceeds size limit (" + size + " bytes): " + filename);
                    }
                    String composerJson = readStreamToString(zipIn);
                    return new ComposerJsonData(composerJson);
                }
            }
            throw new MalformedPackageException("No composer.json found in zip archive: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (MetadataExtractionException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read zip archive: " + filename, e);
        }
    }

    /**
     * Checks if a zip entry name represents a composer.json at root or one level deep.
     * Rejects path traversal (entries with {@code ..}).
     *
     * @param entryName the zip entry name
     * @return true if this is a composer.json we should extract
     */
    static boolean isComposerJson(@NotNull String entryName) {
        if (entryName.contains("..")) {
            return false;
        }
        // Normalize backslashes to forward slashes
        String normalized = entryName.replace('\\', '/');
        String[] parts = normalized.split("/");
        if (parts.length == 1) {
            return parts[0].equals("composer.json");
        }
        if (parts.length == 2) {
            return parts[1].equals("composer.json");
        }
        return false;
    }

    /**
     * Extracts the simple name (part after {@code /}) from a full vendor/package name.
     *
     * @param fullName the full package name (e.g., {@code monolog/monolog})
     * @return the simple name (e.g., {@code monolog}), or the full name if no {@code /}
     */
    static @NotNull String extractSimpleName(@NotNull String fullName) {
        int slashIdx = fullName.lastIndexOf('/');
        if (slashIdx >= 0 && slashIdx < fullName.length() - 1) {
            return fullName.substring(slashIdx + 1);
        }
        return fullName;
    }

    /**
     * Extracts the license from composer.json. Can be a string or an array.
     * Arrays are joined with {@code " OR "}.
     *
     * @param json the parsed composer.json
     * @return the license string, or empty if absent
     */
    static @NotNull Optional<String> extractLicense(@NotNull JsonObject json) {
        if (!json.has("license") || json.get("license").isJsonNull()) {
            return Optional.empty();
        }
        JsonElement licenseElem = json.get("license");
        if (licenseElem.isJsonPrimitive()) {
            String license = licenseElem.getAsString();
            return license.isEmpty() ? Optional.empty() : Optional.of(license);
        }
        if (licenseElem.isJsonArray()) {
            JsonArray arr = licenseElem.getAsJsonArray();
            if (arr.isEmpty()) {
                return Optional.empty();
            }
            List<String> licenses = new ArrayList<>();
            for (JsonElement elem : arr) {
                licenses.add(elem.getAsString());
            }
            String joined = String.join(" OR ", licenses);
            return joined.isEmpty() ? Optional.empty() : Optional.of(joined);
        }
        return Optional.empty();
    }

    /**
     * Extracts the publisher from the first entry in the {@code authors} array.
     *
     * @param json the parsed composer.json
     * @return the first author's name, or empty if absent
     */
    static @NotNull Optional<String> extractPublisher(@NotNull JsonObject json) {
        if (!json.has("authors") || json.get("authors").isJsonNull()) {
            return Optional.empty();
        }
        JsonElement authorsElem = json.get("authors");
        if (!authorsElem.isJsonArray()) {
            return Optional.empty();
        }
        JsonArray authors = authorsElem.getAsJsonArray();
        if (authors.isEmpty()) {
            return Optional.empty();
        }
        JsonElement firstAuthor = authors.get(0);
        if (!firstAuthor.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject authorObj = firstAuthor.getAsJsonObject();
        if (!authorObj.has("name") || authorObj.get("name").isJsonNull()) {
            return Optional.empty();
        }
        String name = authorObj.get("name").getAsString();
        return name.isEmpty() ? Optional.empty() : Optional.of(name);
    }

    /**
     * Extracts dependencies from {@code require} (runtime) and {@code require-dev} (dev) sections.
     * Platform dependencies ({@code php}, {@code ext-*}, {@code lib-*}, {@code composer-plugin-api},
     * {@code composer-runtime-api}, {@code composer}) are excluded (Q3).
     *
     * @param json the parsed composer.json
     * @return the list of parsed dependencies
     */
    static @NotNull List<ParsedDependency> extractDependencies(@NotNull JsonObject json) {
        List<ParsedDependency> deps = new ArrayList<>();
        parseDependencySection(json, "require", "runtime", deps);
        parseDependencySection(json, "require-dev", "dev", deps);
        return List.copyOf(deps);
    }

    /**
     * Checks if a dependency name is a platform dependency that should be excluded.
     *
     * @param name the dependency name
     * @return true if this is a platform dependency
     */
    static boolean isPlatformDependency(@NotNull String name) {
        String lower = name.toLowerCase();
        return lower.equals("php")
                || lower.equals("php-64bit")
                || lower.equals("hhvm")
                || lower.startsWith("ext-")
                || lower.startsWith("lib-")
                || lower.equals("composer-plugin-api")
                || lower.equals("composer-runtime-api")
                || lower.equals("composer");
    }

    /**
     * Safely extracts a string field from a JSON object.
     *
     * @param json      the JSON object
     * @param fieldName the field name
     * @return the field value, or empty if absent or null
     */
    static @NotNull Optional<String> getOptionalString(@NotNull JsonObject json, @NotNull String fieldName) {
        if (json.has(fieldName) && !json.get(fieldName).isJsonNull() && json.get(fieldName).isJsonPrimitive()) {
            String value = json.get(fieldName).getAsString();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        return Optional.empty();
    }

    private static void parseDependencySection(@NotNull JsonObject json, @NotNull String section,
                                                @NotNull String scope, @NotNull List<ParsedDependency> deps) {
        if (!json.has(section) || json.get(section).isJsonNull() || !json.get(section).isJsonObject()) {
            return;
        }
        JsonObject sectionObj = json.getAsJsonObject(section);
        for (var entry : sectionObj.entrySet()) {
            String depName = entry.getKey();
            if (isPlatformDependency(depName)) {
                continue;
            }
            String constraint = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : null;
            deps.add(new ParsedDependency(
                    depName,
                    Optional.ofNullable(constraint).filter(s -> !s.isEmpty()),
                    Optional.of(scope)
            ));
        }
    }

    private static @NotNull String readStreamToString(@NotNull InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_COMPOSER_JSON_SIZE) {
                throw new IOException("composer.json content exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    /**
     * Holds the raw composer.json text extracted from a zip archive before parsing.
     */
    record ComposerJsonData(@NotNull String rawJson) {
    }
}
