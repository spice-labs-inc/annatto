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
package io.spicelabs.annatto.cocoapods;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts metadata from CocoaPods {@code .podspec.json} files.
 *
 * <p>Handles CocoaPods-specific quirks: license polymorphism (Q2),
 * author polymorphism (Q3), subspec dependency aggregation (Q4),
 * dependency version arrays (Q5).</p>
 *
 * <p>This class is stateless; all methods are pure functions.</p>
 */
public final class CocoapodsMetadataExtractor {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final Gson GSON = new Gson();

    private CocoapodsMetadataExtractor() {
        // Static utility class
    }

    /**
     * Reads and parses a .podspec.json from an input stream.
     *
     * @param inputStream the input stream containing the podspec JSON
     * @param filename    the artifact filename for error reporting
     * @return the raw JSON text
     * @throws MetadataExtractionException if reading fails
     */
    public static @NotNull String readJson(@NotNull InputStream inputStream, @NotNull String filename)
            throws MetadataExtractionException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = inputStream.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > MAX_FILE_SIZE) {
                    throw new IOException("File content exceeds size limit");
                }
                baos.write(buffer, 0, read);
            }
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read podspec.json: " + filename, e);
        }
    }

    /**
     * Builds a MetadataResult from raw podspec JSON text.
     *
     * @param rawJson the raw .podspec.json content
     * @return the normalized metadata result
     * @throws MetadataExtractionException if parsing fails
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull String rawJson)
            throws MetadataExtractionException {
        try {
            JsonObject root = GSON.fromJson(rawJson, JsonObject.class);

            Optional<String> name = getOptionalString(root, "name");
            Optional<String> version = getOptionalString(root, "version");
            Optional<String> description = extractDescription(root);
            Optional<String> license = extractLicense(root);
            Optional<String> publisher = extractPublisher(root);
            List<ParsedDependency> dependencies = extractDependencies(root);

            return new MetadataResult(
                    EcosystemId.COCOAPODS,
                    name,
                    name, // simpleName == name for CocoaPods (Q7)
                    version,
                    description,
                    license,
                    publisher,
                    Optional.empty(), // publishedAt: always null (Q6)
                    dependencies
            );
        } catch (Exception e) {
            throw new MetadataExtractionException("Failed to parse podspec.json", e);
        }
    }

    /**
     * Extracts the description: summary preferred, description fallback.
     */
    static @NotNull Optional<String> extractDescription(@NotNull JsonObject root) {
        Optional<String> summary = getOptionalString(root, "summary");
        if (summary.isPresent()) {
            return summary;
        }
        Optional<String> desc = getOptionalString(root, "description");
        return desc.map(String::strip);
    }

    /**
     * Extracts the license. Handles polymorphism (Q2):
     * - String: "MIT" → "MIT"
     * - Object: {"type": "MIT", "text": "..."} → "MIT"
     */
    static @NotNull Optional<String> extractLicense(@NotNull JsonObject root) {
        if (!root.has("license") || root.get("license").isJsonNull()) {
            return Optional.empty();
        }
        JsonElement licElem = root.get("license");
        if (licElem.isJsonObject()) {
            return getOptionalString(licElem.getAsJsonObject(), "type");
        } else if (licElem.isJsonPrimitive()) {
            String lic = licElem.getAsString();
            if (lic == null || lic.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(lic);
        }
        return Optional.empty();
    }

    /**
     * Extracts the publisher from author/authors fields. Handles polymorphism (Q3):
     * - Object map: {"Name": "email"} → join keys with ", "
     * - String: "Name" → "Name"
     * - Array: ["Name1", "Name2"] → join with ", "
     */
    static @NotNull Optional<String> extractPublisher(@NotNull JsonObject root) {
        // Try "authors" first, then "author"
        JsonElement authorsElem = root.has("authors") ? root.get("authors") : root.get("author");
        if (authorsElem == null || authorsElem.isJsonNull()) {
            return Optional.empty();
        }
        if (authorsElem.isJsonObject()) {
            JsonObject authorsObj = authorsElem.getAsJsonObject();
            List<String> names = new ArrayList<>(authorsObj.keySet());
            names.removeIf(n -> n == null || n.isEmpty());
            if (names.isEmpty()) {
                return Optional.empty();
            }
            // Preserve JSON insertion order (no sorting) to match native tools
            return Optional.of(String.join(", ", names));
        } else if (authorsElem.isJsonArray()) {
            JsonArray arr = authorsElem.getAsJsonArray();
            List<String> names = new ArrayList<>();
            for (JsonElement elem : arr) {
                if (!elem.isJsonNull()) {
                    String s = elem.getAsString();
                    if (s != null && !s.isEmpty()) {
                        names.add(s);
                    }
                }
            }
            if (names.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(String.join(", ", names));
        } else if (authorsElem.isJsonPrimitive()) {
            String author = authorsElem.getAsString();
            if (author == null || author.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(author);
        }
        return Optional.empty();
    }

    /**
     * Extracts dependencies from top-level and subspec dependencies (Q4, Q5).
     * - Top-level "dependencies" map
     * - Subspec dependencies: if default_subspecs specified, only those; else all
     * - Self-referencing dependencies (pod/subspec) are filtered
     * - All dependencies are scope "runtime"
     */
    static @NotNull List<ParsedDependency> extractDependencies(@NotNull JsonObject root) {
        String podName = root.has("name") && !root.get("name").isJsonNull()
                ? root.get("name").getAsString() : "";

        // Collect all deps from top-level and subspecs
        Map<String, JsonElement> allDeps = new LinkedHashMap<>();

        // Top-level dependencies
        if (root.has("dependencies") && root.get("dependencies").isJsonObject()) {
            JsonObject topDeps = root.getAsJsonObject("dependencies");
            for (String key : topDeps.keySet()) {
                allDeps.put(key, topDeps.get(key));
            }
        }

        // Subspec dependencies
        if (root.has("subspecs") && root.get("subspecs").isJsonArray()) {
            JsonArray subspecs = root.getAsJsonArray("subspecs");
            List<JsonObject> selected = selectSubspecs(root, subspecs);
            for (JsonObject subspec : selected) {
                if (subspec.has("dependencies") && subspec.get("dependencies").isJsonObject()) {
                    JsonObject subDeps = subspec.getAsJsonObject("dependencies");
                    for (String key : subDeps.keySet()) {
                        if (!allDeps.containsKey(key)) {
                            allDeps.put(key, subDeps.get(key));
                        }
                    }
                }
            }
        }

        // Build sorted dependency list, filtering self-references
        List<ParsedDependency> deps = new ArrayList<>();
        List<String> depNames = new ArrayList<>(allDeps.keySet());
        Collections.sort(depNames);

        for (String depName : depNames) {
            // Skip self-referencing (pod itself or pod/subspec)
            if (depName.equals(podName) || depName.startsWith(podName + "/")) {
                continue;
            }
            Optional<String> vc = extractVersionConstraint(allDeps.get(depName));
            deps.add(new ParsedDependency(depName, vc, Optional.of("runtime")));
        }

        return List.copyOf(deps);
    }

    /**
     * Selects which subspecs to include for dependency aggregation (Q4).
     * If default_subspecs is present, only those; otherwise all.
     */
    private static @NotNull List<JsonObject> selectSubspecs(
            @NotNull JsonObject root, @NotNull JsonArray subspecs) {
        List<JsonObject> result = new ArrayList<>();

        if (root.has("default_subspecs") && !root.get("default_subspecs").isJsonNull()) {
            List<String> defaults = new ArrayList<>();
            JsonElement defaultElem = root.get("default_subspecs");
            if (defaultElem.isJsonArray()) {
                for (JsonElement e : defaultElem.getAsJsonArray()) {
                    defaults.add(e.getAsString());
                }
            } else if (defaultElem.isJsonPrimitive()) {
                defaults.add(defaultElem.getAsString());
            }

            for (JsonElement specElem : subspecs) {
                if (specElem.isJsonObject()) {
                    JsonObject spec = specElem.getAsJsonObject();
                    String name = spec.has("name") ? spec.get("name").getAsString() : "";
                    if (defaults.contains(name)) {
                        result.add(spec);
                    }
                }
            }
        } else {
            // No default_subspecs → include all
            for (JsonElement specElem : subspecs) {
                if (specElem.isJsonObject()) {
                    result.add(specElem.getAsJsonObject());
                }
            }
        }

        return result;
    }

    /**
     * Extracts version constraint from a dependency value (Q5).
     * - Empty array or null → empty (any version)
     * - Array of strings → joined with ", "
     */
    private static @NotNull Optional<String> extractVersionConstraint(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return Optional.empty();
        }
        if (value.isJsonArray()) {
            JsonArray arr = value.getAsJsonArray();
            if (arr.isEmpty()) {
                return Optional.empty();
            }
            List<String> parts = new ArrayList<>();
            for (JsonElement elem : arr) {
                parts.add(elem.getAsString());
            }
            return Optional.of(String.join(", ", parts));
        }
        return Optional.empty();
    }

    /**
     * Safely extracts an optional string field.
     */
    static @NotNull Optional<String> getOptionalString(@NotNull JsonObject json, @NotNull String field) {
        if (json.has(field) && !json.get(field).isJsonNull() && json.get(field).isJsonPrimitive()) {
            String value = json.get(field).getAsString();
            if (value != null && !value.isEmpty()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
