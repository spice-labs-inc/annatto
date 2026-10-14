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
package io.spicelabs.annatto.cpan;

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
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Extracts metadata from CPAN distribution tarballs ({@code .tar.gz}).
 *
 * <p>CPAN distributions contain {@code META.json} (preferred, CPAN::Meta::Spec v2) or
 * {@code META.yml} (v1.x). This extractor reads whichever is present, preferring JSON.</p>
 *
 * <p>This class is stateless; all methods are pure functions.</p>
 *
 * <p>Covers {@link CpanQuirks} Q1 (JSON preferred), Q2 (dist vs module name),
 * Q4 (prereqs phases), Q5 (version "0"), Q7 (license array).</p>
 */
public final class CpanMetadataExtractor {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB per file
    private static final Gson GSON = new Gson();

    /** Scope mapping from CPAN META v2 phases to normalized scopes. */
    private static final Map<String, String> PHASE_TO_SCOPE = Map.of(
            "runtime", "runtime",
            "test", "test",
            "build", "build",
            "configure", "build",
            "develop", "dev"
    );

    /** Scope mapping from CPAN META v1 flat keys to normalized scopes. */
    private static final Map<String, String> V1_KEY_TO_SCOPE = Map.of(
            "build_requires", "build",
            "configure_requires", "build",
            "requires", "runtime",
            "test_requires", "test"
    );

    /**
     * License normalization from META v1 short names to META v2 canonical identifiers.
     * CPAN::Meta::Converter applies this mapping when upgrading v1 → v2.
     */
    private static final Map<String, String> V1_LICENSE_MAP = Map.of(
            "perl", "perl_5",
            "apache", "apache_1_1",
            "artistic", "artistic_1",
            "gpl", "gpl_1",
            "lgpl", "lgpl_2_1",
            "mozilla", "mozilla_1_0"
    );

    private CpanMetadataExtractor() {
        // Static utility class
    }

    /**
     * Extracts metadata from a CPAN .tar.gz distribution.
     *
     * @param inputStream the artifact input stream
     * @param filename    the artifact filename
     * @return the extracted metadata result
     * @throws MetadataExtractionException if extraction fails
     * @throws MalformedPackageException   if no META.json/yml found
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream inputStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        CpanArchiveData data = extractFromArchive(inputStream, filename);
        return buildMetadataResult(data);
    }

    /**
     * Extracts the raw META.json or META.yml text from a .tar.gz archive.
     * Prefers META.json over META.yml (Q1).
     *
     * @param stream   the archive input stream
     * @param filename the filename for error reporting
     * @return the extracted archive data
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no META file found
     */
    static @NotNull CpanArchiveData extractFromArchive(@NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(stream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn, StandardCharsets.UTF_8.name())) {
            String metaJson = null;
            String metaYml = null;

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
                    continue;
                }

                // META.json or META.yml at top level: DistName-Version/META.json
                String baseName = entryName.contains("/")
                        ? entryName.substring(entryName.indexOf('/') + 1)
                        : entryName;

                if (baseName.equals("META.json") && metaJson == null) {
                    metaJson = readStreamToString(tarIn, entry.getSize());
                } else if (baseName.equals("META.yml") && metaYml == null) {
                    metaYml = readStreamToString(tarIn, entry.getSize());
                }

                // Early exit if we found META.json (preferred)
                if (metaJson != null) {
                    return new CpanArchiveData(metaJson, MetaFormat.JSON);
                }
            }

            if (metaYml != null) {
                return new CpanArchiveData(metaYml, MetaFormat.YAML);
            }

            throw new MalformedPackageException("No META.json or META.yml found in: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read .tar.gz archive: " + filename, e);
        }
    }

    /**
     * Builds a MetadataResult from extracted archive data.
     *
     * @param data the raw meta text and format
     * @return the normalized metadata result
     * @throws MetadataExtractionException if parsing fails
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull CpanArchiveData data)
            throws MetadataExtractionException {
        JsonObject root = parseToJsonObject(data);

        Optional<String> name = getOptionalString(root, "name");
        Optional<String> version = extractVersion(root);
        Optional<String> description = extractDescription(root);
        Optional<String> license = extractLicense(root);
        Optional<String> publisher = extractPublisher(root);

        List<ParsedDependency> dependencies = extractDependencies(root);

        return new MetadataResult(
                EcosystemId.CPAN,
                name,
                name, // simpleName == name for CPAN distributions (Q2)
                version,
                description,
                license,
                publisher,
                Optional.empty(), // publishedAt: always null (Q6)
                dependencies
        );
    }

    /**
     * Parses the raw meta text to a JsonObject, handling both JSON and YAML formats.
     */
    private static @NotNull JsonObject parseToJsonObject(@NotNull CpanArchiveData data)
            throws MetadataExtractionException {
        try {
            if (data.format() == MetaFormat.JSON) {
                return GSON.fromJson(data.rawText(), JsonObject.class);
            } else {
                return yamlToJsonObject(data.rawText());
            }
        } catch (Exception e) {
            throw new MetadataExtractionException("Failed to parse CPAN metadata", e);
        }
    }

    /**
     * Converts YAML text to a JsonObject via SnakeYAML.
     * Uses a custom resolver that does not auto-detect INT/FLOAT, so version
     * strings like "0.20" are preserved as-is instead of being truncated to "0.2".
     */
    private static @NotNull JsonObject yamlToJsonObject(@NotNull String yamlText) {
        LoaderOptions loaderOpts = new LoaderOptions();
        loaderOpts.setMaxAliasesForCollections(50);
        DumperOptions dumperOpts = new DumperOptions();
        Yaml yaml = new Yaml(
                new SafeConstructor(loaderOpts),
                new Representer(dumperOpts),
                dumperOpts,
                new StringPreservingResolver());
        Object loaded = yaml.load(yamlText);
        if (loaded instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) loaded;
            String json = GSON.toJson(map);
            return GSON.fromJson(json, JsonObject.class);
        }
        return new JsonObject();
    }

    /**
     * Custom SnakeYAML resolver that skips INT and FLOAT implicit resolvers.
     * This preserves version strings like "0.20" as strings instead of converting
     * them to floats (0.2). BOOL, NULL, MERGE, and TIMESTAMP are still resolved.
     */
    private static final class StringPreservingResolver extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.TIMESTAMP, TIMESTAMP, "0123456789");
            // Intentionally omit Tag.INT and Tag.FLOAT so that version
            // numbers (e.g., "0.20", "2.020") are kept as strings.
        }
    }

    /**
     * Extracts the version field, converting to string if needed.
     */
    static @NotNull Optional<String> extractVersion(@NotNull JsonObject root) {
        if (!root.has("version") || root.get("version").isJsonNull()) {
            return Optional.empty();
        }
        String version = root.get("version").getAsString();
        if (version == null || version.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(version);
    }

    /**
     * Extracts the description from the "abstract" field.
     *
     * @param root the parsed metadata
     * @return the description, or empty if absent
     */
    static @NotNull Optional<String> extractDescription(@NotNull JsonObject root) {
        Optional<String> abs = getOptionalString(root, "abstract");
        // Filter placeholder "unknown" abstract
        if (abs.isPresent() && abs.get().equalsIgnoreCase("unknown")) {
            return Optional.empty();
        }
        return abs;
    }

    /**
     * Extracts the license from the "license" field.
     * Can be a string (META v1) or array (META v2). ["unknown"] maps to null (Q7).
     *
     * @param root the parsed metadata
     * @return the license string, or empty
     */
    static @NotNull Optional<String> extractLicense(@NotNull JsonObject root) {
        if (!root.has("license") || root.get("license").isJsonNull()) {
            return Optional.empty();
        }
        JsonElement licenseElem = root.get("license");
        if (licenseElem.isJsonArray()) {
            JsonArray arr = licenseElem.getAsJsonArray();
            if (arr.isEmpty()) {
                return Optional.empty();
            }
            // ["unknown"] -> null (Q7)
            if (arr.size() == 1 && "unknown".equals(arr.get(0).getAsString())) {
                return Optional.empty();
            }
            List<String> licenses = new ArrayList<>();
            for (JsonElement elem : arr) {
                licenses.add(normalizeLicense(elem.getAsString()));
            }
            return Optional.of(String.join(" OR ", licenses));
        } else {
            String lic = licenseElem.getAsString();
            if (lic == null || lic.isEmpty() || lic.equals("unknown")) {
                return Optional.empty();
            }
            return Optional.of(normalizeLicense(lic));
        }
    }

    /**
     * Normalizes a META v1 license identifier to its v2 canonical form.
     * If the license is already a v2 identifier (or unrecognized), it is returned as-is.
     */
    static @NotNull String normalizeLicense(@NotNull String license) {
        return V1_LICENSE_MAP.getOrDefault(license, license);
    }

    /**
     * Extracts the publisher from the "author" field.
     * Can be a string or array; joined with ", ".
     *
     * @param root the parsed metadata
     * @return the publisher string, or empty
     */
    static @NotNull Optional<String> extractPublisher(@NotNull JsonObject root) {
        if (!root.has("author") || root.get("author").isJsonNull()) {
            return Optional.empty();
        }
        JsonElement authorElem = root.get("author");
        if (authorElem.isJsonArray()) {
            JsonArray arr = authorElem.getAsJsonArray();
            if (arr.isEmpty()) {
                return Optional.empty();
            }
            List<String> authors = new ArrayList<>();
            for (JsonElement elem : arr) {
                if (!elem.isJsonNull()) {
                    String a = elem.getAsString();
                    if (a != null && !a.isEmpty() && !a.equals("unknown")) {
                        authors.add(a);
                    }
                }
            }
            if (authors.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(String.join(", ", authors));
        } else {
            String author = authorElem.getAsString();
            if (author == null || author.isEmpty() || author.equals("unknown")) {
                return Optional.empty();
            }
            return Optional.of(author);
        }
    }

    /**
     * Extracts dependencies from the prereqs structure.
     * Phases: runtime, test, build, configure, develop.
     * Only "requires" relationships are extracted; recommends/suggests/conflicts are skipped (Q4).
     * Version "0" maps to null versionConstraint (Q5).
     * "perl" itself is skipped.
     *
     * @param root the parsed metadata
     * @return the list of parsed dependencies
     */
    static @NotNull List<ParsedDependency> extractDependencies(@NotNull JsonObject root) {
        // META v2 format: nested prereqs structure
        if (root.has("prereqs") && !root.get("prereqs").isJsonNull()
                && root.get("prereqs").isJsonObject()) {
            return extractV2Dependencies(root.getAsJsonObject("prereqs"));
        }
        // Fall back to META v1 format: flat requires/build_requires/etc.
        return extractV1Dependencies(root);
    }

    /**
     * Extracts dependencies from META v2 nested prereqs structure.
     * Structure: prereqs.{phase}.requires.{module} = version
     */
    private static @NotNull List<ParsedDependency> extractV2Dependencies(@NotNull JsonObject prereqs) {
        List<ParsedDependency> deps = new ArrayList<>();

        // Process phases in deterministic order
        List<String> phases = new ArrayList<>(PHASE_TO_SCOPE.keySet());
        Collections.sort(phases);

        for (String phase : phases) {
            if (!prereqs.has(phase) || !prereqs.get(phase).isJsonObject()) {
                continue;
            }
            JsonObject phaseObj = prereqs.getAsJsonObject(phase);
            String scope = PHASE_TO_SCOPE.get(phase);

            if (!phaseObj.has("requires") || !phaseObj.get("requires").isJsonObject()) {
                continue;
            }
            JsonObject requires = phaseObj.getAsJsonObject("requires");
            addDepsFromRequires(deps, requires, scope);
        }

        return List.copyOf(deps);
    }

    /**
     * Extracts dependencies from META v1 flat structure.
     * Keys: requires, build_requires, configure_requires, test_requires.
     * Maps to the same scopes as the v2 path.
     */
    private static @NotNull List<ParsedDependency> extractV1Dependencies(@NotNull JsonObject root) {
        List<ParsedDependency> deps = new ArrayList<>();

        // Process v1 keys in deterministic order (matches phase ordering: build, configure, runtime, test)
        List<String> keys = new ArrayList<>(V1_KEY_TO_SCOPE.keySet());
        Collections.sort(keys);

        for (String key : keys) {
            if (!root.has(key) || root.get(key).isJsonNull() || !root.get(key).isJsonObject()) {
                continue;
            }
            JsonObject requires = root.getAsJsonObject(key);
            String scope = V1_KEY_TO_SCOPE.get(key);
            addDepsFromRequires(deps, requires, scope);
        }

        return List.copyOf(deps);
    }

    /**
     * Adds dependencies from a requires map (shared by v1 and v2 paths).
     */
    private static void addDepsFromRequires(
            @NotNull List<ParsedDependency> deps,
            @NotNull JsonObject requires,
            @NotNull String scope) {
        List<String> depNames = new ArrayList<>(requires.keySet());
        Collections.sort(depNames);

        for (String depName : depNames) {
            if (depName.equals("perl")) {
                continue; // skip perl itself
            }
            Optional<String> versionConstraint = extractVersionConstraint(requires, depName);
            deps.add(new ParsedDependency(depName, versionConstraint, Optional.of(scope)));
        }
    }

    /**
     * Extracts the version constraint for a dependency.
     * Version "0" is treated as "any version" and maps to null (Q5).
     */
    private static @NotNull Optional<String> extractVersionConstraint(
            @NotNull JsonObject requires, @NotNull String depName) {
        JsonElement verElem = requires.get(depName);
        if (verElem == null || verElem.isJsonNull()) {
            return Optional.empty();
        }
        String ver = verElem.getAsString();
        if (ver == null || ver.isEmpty() || ver.equals("0")) {
            return Optional.empty(); // Q5: version "0" -> null
        }
        return Optional.of(ver);
    }

    /**
     * Safely extracts an optional string field from a JSON object.
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

    /** Detected meta format. */
    enum MetaFormat {
        JSON,
        YAML
    }

    /**
     * Holds the raw text extracted from a CPAN archive before parsing.
     *
     * @param rawText the raw META.json or META.yml content
     * @param format  the detected format
     */
    record CpanArchiveData(@NotNull String rawText, @NotNull MetaFormat format) {
    }
}
