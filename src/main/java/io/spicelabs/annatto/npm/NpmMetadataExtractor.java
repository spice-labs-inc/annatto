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

package io.spicelabs.annatto.npm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.spicelabs.annatto.AnnattoException.MalformedPackageException;
import io.spicelabs.annatto.AnnattoException.MetadataExtractionException;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Extracts metadata from npm package.json within a .tgz archive.
 * This class is stateless; all methods are pure functions.
 *
 * <p>npm packages are gzip-compressed tar archives containing a {@code package/package.json} file.
 * The extractor handles the following quirks documented in {@link NpmQuirks}:
 * <ul>
 *   <li>Scoped packages ({@code @scope/name})</li>
 *   <li>Legacy {@code licenses} array vs modern {@code license} SPDX string</li>
 *   <li>{@code author} as string ("Name &lt;email&gt; (url)") or object ({@code {name, email, url}})</li>
 *   <li>Multiple dependency types: dependencies, devDependencies, peerDependencies, optionalDependencies</li>
 *   <li>Registry-prefixed {@code _} fields are ignored</li>
 * </ul>
 */
public final class NpmMetadataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(NpmMetadataExtractor.class);

    private NpmMetadataExtractor() {
    }

    /**
     * Extracts metadata from an npm .tgz package stream.
     *
     * @param tgzStream the .tgz archive input stream
     * @param filename  the artifact filename for error reporting
     * @return the extracted metadata
     * @throws MetadataExtractionException if the archive cannot be read or parsed
     * @throws MalformedPackageException   if no package.json is found in the archive
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream tgzStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        JsonObject packageJson = extractPackageJson(tgzStream, filename);
        return parsePackageJson(packageJson);
    }

    /**
     * Parses metadata directly from a package.json {@link JsonObject}.
     * Useful for testing without requiring a .tgz archive.
     *
     * @param packageJson the parsed package.json content
     * @return the extracted metadata
     */
    public static @NotNull MetadataResult parsePackageJson(@NotNull JsonObject packageJson) {
        Optional<String> name = getOptionalString(packageJson, "name");
        Optional<String> simpleName = name.map(NpmMetadataExtractor::extractSimpleName);
        Optional<String> version = getOptionalString(packageJson, "version");
        Optional<String> description = getOptionalString(packageJson, "description");
        Optional<String> license = extractLicense(packageJson);
        Optional<String> publisher = extractAuthor(packageJson);
        List<ParsedDependency> dependencies = extractDependencies(packageJson);

        return new MetadataResult(
                EcosystemId.NPM,
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
     * Finds and parses the package.json file from a .tgz archive stream.
     *
     * @param tgzStream the .tgz stream
     * @param filename  the artifact filename for error reporting
     * @return the parsed package.json as a JsonObject
     * @throws MetadataExtractionException if I/O or parsing fails
     * @throws MalformedPackageException   if no package.json found
     */
    static @NotNull JsonObject extractPackageJson(@NotNull InputStream tgzStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(tgzStream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn, StandardCharsets.UTF_8.name())) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                // npm packs files under package/ prefix; match package.json at the first directory level
                if (isPackageJson(entryName)) {
                    return parseJsonFromStream(tarIn);
                }
            }
            throw new MalformedPackageException("No package.json found in npm archive: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read npm archive: " + filename, e);
        }
    }

    /**
     * Checks if a tar entry name corresponds to the top-level package.json.
     * npm tarballs place files under a single directory (usually "package/").
     * We match any entry that ends with "/package.json" and has exactly one directory separator,
     * or is just "package.json" at root level.
     */
    static boolean isPackageJson(@NotNull String entryName) {
        // Normalize path separators
        String normalized = entryName.replace('\\', '/');
        // Match: package/package.json, or any-prefix/package.json (one level deep)
        if (normalized.equals("package.json")) {
            return true;
        }
        if (normalized.endsWith("/package.json")) {
            // Ensure it's only one directory deep
            String withoutFile = normalized.substring(0, normalized.length() - "/package.json".length());
            return !withoutFile.contains("/");
        }
        return false;
    }

    private static @NotNull JsonObject parseJsonFromStream(@NotNull InputStream stream)
            throws MetadataExtractionException {
        try {
            // Read the full content - do not close the underlying tar stream
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            String json = baos.toString(StandardCharsets.UTF_8);
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                throw new MetadataExtractionException("package.json is not a JSON object");
            }
            return element.getAsJsonObject();
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to parse package.json", e);
        } catch (com.google.gson.JsonSyntaxException e) {
            throw new MetadataExtractionException("Malformed JSON in package.json", e);
        }
    }

    /**
     * Extracts the unqualified name from a potentially scoped package name.
     * For {@code @scope/name}, returns {@code name}. For {@code name}, returns {@code name}.
     */
    static @NotNull String extractSimpleName(@NotNull String fullName) {
        if (fullName.startsWith("@") && fullName.contains("/")) {
            return fullName.substring(fullName.indexOf('/') + 1);
        }
        return fullName;
    }

    /**
     * Extracts the license from package.json, handling both modern and legacy formats.
     *
     * <p>Modern: {@code "license": "MIT"} (SPDX string)</p>
     * <p>Legacy: {@code "licenses": [{"type": "MIT", "url": "..."}]}</p>
     * <p>Object: {@code "license": {"type": "MIT", "url": "..."}}</p>
     */
    static @NotNull Optional<String> extractLicense(@NotNull JsonObject packageJson) {
        // Modern SPDX string
        if (packageJson.has("license")) {
            JsonElement licenseElement = packageJson.get("license");
            if (licenseElement.isJsonPrimitive() && licenseElement.getAsJsonPrimitive().isString()) {
                String value = licenseElement.getAsString().trim();
                if (!value.isEmpty()) {
                    return Optional.of(value);
                }
            }
            // License as object: {"type": "MIT", "url": "..."}
            if (licenseElement.isJsonObject()) {
                JsonObject licenseObj = licenseElement.getAsJsonObject();
                return getOptionalString(licenseObj, "type");
            }
        }

        // Legacy licenses array: [{"type": "MIT", "url": "..."}]
        if (packageJson.has("licenses")) {
            JsonElement licensesElement = packageJson.get("licenses");
            if (licensesElement.isJsonArray()) {
                JsonArray licensesArray = licensesElement.getAsJsonArray();
                if (!licensesArray.isEmpty()) {
                    List<String> types = new ArrayList<>();
                    for (JsonElement le : licensesArray) {
                        if (le.isJsonObject()) {
                            getOptionalString(le.getAsJsonObject(), "type").ifPresent(types::add);
                        } else if (le.isJsonPrimitive() && le.getAsJsonPrimitive().isString()) {
                            types.add(le.getAsString());
                        }
                    }
                    if (!types.isEmpty()) {
                        return Optional.of(String.join(" OR ", types));
                    }
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Extracts the author/publisher from package.json.
     *
     * <p>String format: {@code "Author Name <email> (url)"} - extracts just the name part.</p>
     * <p>Object format: {@code {"name": "Author Name", "email": "...", "url": "..."}}</p>
     * <p>Falls back to first entry in {@code maintainers} or {@code contributors} arrays.</p>
     */
    static @NotNull Optional<String> extractAuthor(@NotNull JsonObject packageJson) {
        if (packageJson.has("author")) {
            JsonElement authorElement = packageJson.get("author");
            Optional<String> result = extractPersonName(authorElement);
            if (result.isPresent()) {
                return result;
            }
        }

        // Fallback: first maintainer
        Optional<String> maintainer = extractFirstPersonFromArray(packageJson, "maintainers");
        if (maintainer.isPresent()) {
            return maintainer;
        }

        // Fallback: first contributor
        return extractFirstPersonFromArray(packageJson, "contributors");
    }

    /**
     * Extracts a person's name from either a string or object representation.
     *
     * @param element the "author" or person element
     * @return the extracted name
     */
    static @NotNull Optional<String> extractPersonName(@NotNull JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return parsePersonString(element.getAsString());
        }
        if (element.isJsonObject()) {
            return getOptionalString(element.getAsJsonObject(), "name");
        }
        return Optional.empty();
    }

    /**
     * Parses an npm person string of the form {@code "Name <email> (url)"}.
     * Returns just the name portion.
     */
    static @NotNull Optional<String> parsePersonString(@NotNull String personString) {
        String trimmed = personString.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        // Strip <email> portion
        int angleIdx = trimmed.indexOf('<');
        if (angleIdx > 0) {
            trimmed = trimmed.substring(0, angleIdx).trim();
        }
        // Strip (url) portion
        int parenIdx = trimmed.indexOf('(');
        if (parenIdx > 0) {
            trimmed = trimmed.substring(0, parenIdx).trim();
        }
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    private static @NotNull Optional<String> extractFirstPersonFromArray(@NotNull JsonObject json,
            @NotNull String fieldName) {
        if (!json.has(fieldName)) {
            return Optional.empty();
        }
        JsonElement element = json.get(fieldName);
        if (!element.isJsonArray()) {
            return Optional.empty();
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            return Optional.empty();
        }
        return extractPersonName(array.get(0));
    }

    /**
     * Extracts all dependencies (dependencies + devDependencies) from package.json.
     * Each dependency is stored with its version constraint and scope.
     *
     * <p>Dependency types extracted:
     * <ul>
     *   <li>{@code dependencies} - scope "runtime"</li>
     *   <li>{@code devDependencies} - scope "dev"</li>
     *   <li>{@code peerDependencies} - scope "peer"</li>
     *   <li>{@code optionalDependencies} - scope "optional"</li>
     * </ul>
     */
    static @NotNull List<ParsedDependency> extractDependencies(@NotNull JsonObject packageJson) {
        var deps = new ArrayList<ParsedDependency>();
        extractDepsFromField(packageJson, "dependencies", "runtime", deps);
        extractDepsFromField(packageJson, "devDependencies", "dev", deps);
        extractDepsFromField(packageJson, "peerDependencies", "peer", deps);
        extractDepsFromField(packageJson, "optionalDependencies", "optional", deps);
        return List.copyOf(deps);
    }

    private static void extractDepsFromField(@NotNull JsonObject packageJson, @NotNull String fieldName,
            @NotNull String scope, @NotNull List<ParsedDependency> target) {
        if (!packageJson.has(fieldName)) {
            return;
        }
        JsonElement element = packageJson.get(fieldName);
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject depsObj = element.getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : depsObj.entrySet()) {
            String name = entry.getKey();
            Optional<String> versionConstraint = Optional.empty();
            if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                String vc = entry.getValue().getAsString().trim();
                if (!vc.isEmpty()) {
                    versionConstraint = Optional.of(vc);
                }
            }
            target.add(new ParsedDependency(name, versionConstraint, Optional.of(scope)));
        }
    }

    private static @NotNull Optional<String> getOptionalString(@NotNull JsonObject json, @NotNull String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isString()) {
            String value = json.get(key).getAsString().trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        return Optional.empty();
    }
}
