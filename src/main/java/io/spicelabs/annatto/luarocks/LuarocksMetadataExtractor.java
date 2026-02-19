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
package io.spicelabs.annatto.luarocks;

import io.spicelabs.annatto.AnnattoException.MalformedPackageException;
import io.spicelabs.annatto.AnnattoException.MetadataExtractionException;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts metadata from LuaRocks artifacts ({@code .src.rock} archives and
 * standalone {@code .rockspec} files).
 *
 * <p>This class is stateless; all methods are pure functions. Uses a two-step pattern:
 * <ol>
 *   <li>{@link #extractRockspecText} — extracts raw Lua text from the artifact</li>
 *   <li>{@link #buildMetadataResult} — evaluates the Lua and maps to {@link MetadataResult}</li>
 * </ol>
 *
 * <p>Covers {@link LuarocksQuirks} Q1 (Lua code parsing), Q2 (version revision),
 * Q3 (external deps filtered), Q4 (dependency string format), Q5 (description fallback),
 * Q6 (publisher from maintainer), Q7 (no publishedAt), Q8 (PURL lowercasing).</p>
 */
public final class LuarocksMetadataExtractor {

    private static final int MAX_FILE_SIZE = 1024 * 1024; // 1 MB

    private LuarocksMetadataExtractor() {
        // Static utility class — do not instantiate.
    }

    /**
     * Extracts metadata from the given LuaRocks artifact stream.
     *
     * @param inputStream the artifact input stream
     * @param filename    the artifact filename
     * @return the extracted metadata result
     * @throws MetadataExtractionException if the artifact cannot be read or parsed
     * @throws MalformedPackageException   if no rockspec is found
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream inputStream,
            @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        RockspecData data = extractRockspecText(inputStream, filename);
        return buildMetadataResult(data);
    }

    /**
     * Holds the raw rockspec text extracted from an artifact.
     *
     * @param rawText  the rockspec Lua source code
     * @param filename the original artifact filename
     */
    record RockspecData(@NotNull String rawText, @NotNull String filename) {
    }

    /**
     * Extracts rockspec text from a LuaRocks artifact.
     * Detects the format from the filename extension.
     *
     * @param stream   the artifact input stream
     * @param filename the artifact filename
     * @return the extracted rockspec data
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no rockspec found in archive
     */
    static @NotNull RockspecData extractRockspecText(@NotNull InputStream stream,
            @NotNull String filename) throws MetadataExtractionException, MalformedPackageException {
        if (filename.endsWith(".rockspec")) {
            return extractFromRockspec(stream, filename);
        } else if (filename.endsWith(".rock")) {
            return extractFromRock(stream, filename);
        }
        throw new MalformedPackageException("Unrecognized LuaRocks file extension: " + filename);
    }

    /**
     * Reads a standalone .rockspec file directly from the input stream.
     */
    private static @NotNull RockspecData extractFromRockspec(@NotNull InputStream stream,
            @NotNull String filename) throws MetadataExtractionException {
        try {
            String text = readStreamToString(stream);
            return new RockspecData(text, filename);
        } catch (IOException e) {
            throw new MetadataExtractionException(
                    "Failed to read .rockspec file: " + filename, e);
        }
    }

    /**
     * Extracts the .rockspec file from a .rock (ZIP) archive.
     * Looks for a .rockspec entry at the root level of the ZIP.
     */
    static @NotNull RockspecData extractFromRock(@NotNull InputStream stream,
            @NotNull String filename) throws MetadataExtractionException, MalformedPackageException {
        try (ZipInputStream zipIn = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String entryName = entry.getName();
                if (entryName.contains("..")) {
                    continue; // path traversal rejection
                }
                if (entryName.endsWith(".rockspec") && !entryName.contains("/")) {
                    String text = readStreamToString(zipIn);
                    return new RockspecData(text, filename);
                }
            }
            throw new MalformedPackageException(
                    "No .rockspec found in .rock archive: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException(
                    "Failed to read .rock archive: " + filename, e);
        }
    }

    /**
     * Evaluates the rockspec Lua text and builds a normalized {@link MetadataResult}.
     *
     * @param data the rockspec data containing raw Lua text
     * @return the normalized metadata result
     * @throws MetadataExtractionException if Lua evaluation fails
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull RockspecData data)
            throws MetadataExtractionException {
        Map<String, Object> env;
        try {
            env = LuaRockspecEvaluator.evaluate(data.rawText());
        } catch (Exception e) {
            throw new MetadataExtractionException(
                    "Failed to evaluate rockspec: " + data.filename(), e);
        }

        Optional<String> name = getStringValue(env, "package");
        Optional<String> version = getStringValue(env, "version");

        // Description fields from the description table (Q5)
        Optional<String> description = extractDescription(env);
        Optional<String> license = extractFromDescriptionTable(env, "license");
        Optional<String> publisher = extractFromDescriptionTable(env, "maintainer"); // Q6

        // Dependencies from dependencies, build_dependencies, test_dependencies (Q4)
        // external_dependencies are filtered entirely (Q3)
        List<ParsedDependency> dependencies = new ArrayList<>();
        addDependencies(dependencies, env, "dependencies", "runtime");
        addDependencies(dependencies, env, "build_dependencies", "build");
        addDependencies(dependencies, env, "test_dependencies", "test");

        return new MetadataResult(
                EcosystemId.LUAROCKS,
                name,
                name, // simpleName == name (no vendor concept in LuaRocks)
                version,
                description,
                license,
                publisher,
                Optional.empty(), // publishedAt always null (Q7)
                List.copyOf(dependencies)
        );
    }

    /**
     * Extracts description from the description table.
     * Prefers "summary" field, falls back to "detailed" field (Q5).
     *
     * @param env the rockspec evaluation environment
     * @return the description, or empty if absent
     */
    static @NotNull Optional<String> extractDescription(@NotNull Map<String, Object> env) {
        Object descObj = env.get("description");
        if (descObj instanceof Map<?, ?> descMap) {
            Object summary = descMap.get("summary");
            if (summary instanceof String s && !s.isEmpty()) {
                return Optional.of(s);
            }
            Object detailed = descMap.get("detailed");
            if (detailed instanceof String s && !s.isEmpty()) {
                return Optional.of(s);
            }
        } else if (descObj instanceof String s && !s.isEmpty()) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    /**
     * Extracts a string field from the description table.
     *
     * @param env   the rockspec evaluation environment
     * @param field the field name within the description table
     * @return the field value, or empty if absent
     */
    static @NotNull Optional<String> extractFromDescriptionTable(@NotNull Map<String, Object> env,
            @NotNull String field) {
        Object descObj = env.get("description");
        if (descObj instanceof Map<?, ?> descMap) {
            Object value = descMap.get(field);
            if (value instanceof String s && !s.isEmpty()) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /**
     * Adds dependencies from a named field in the environment to the dependency list.
     *
     * @param deps      the dependency list to add to
     * @param env       the rockspec evaluation environment
     * @param fieldName the field name (e.g., "dependencies", "build_dependencies")
     * @param scope     the dependency scope (e.g., "runtime", "build", "test")
     */
    @SuppressWarnings("unchecked")
    private static void addDependencies(@NotNull List<ParsedDependency> deps,
            @NotNull Map<String, Object> env, @NotNull String fieldName, @NotNull String scope) {
        Object depsObj = env.get(fieldName);
        if (depsObj instanceof List<?> depsList) {
            for (Object item : depsList) {
                if (item instanceof String depStr) {
                    ParsedDependency dep = parseDependencyString(depStr, scope);
                    if (dep != null) {
                        deps.add(dep);
                    }
                }
            }
        }
    }

    /**
     * Parses a LuaRocks dependency string into a {@link ParsedDependency}.
     * Format: "name [version_constraint]"
     * Split on first space: name is first token, version constraint is rest.
     *
     * @param depStr the dependency string (e.g., "lua >= 5.1")
     * @param scope  the dependency scope
     * @return the parsed dependency, or null if the string is empty
     */
    static @Nullable ParsedDependency parseDependencyString(@NotNull String depStr,
            @NotNull String scope) {
        String trimmed = depStr.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace < 0) {
            return new ParsedDependency(trimmed, Optional.empty(), Optional.of(scope));
        }
        String name = trimmed.substring(0, firstSpace);
        String constraint = trimmed.substring(firstSpace + 1).trim();
        if (constraint.isEmpty()) {
            return new ParsedDependency(name, Optional.empty(), Optional.of(scope));
        }
        return new ParsedDependency(name, Optional.of(constraint), Optional.of(scope));
    }

    /**
     * Safely extracts a string value from the evaluation environment.
     */
    private static @NotNull Optional<String> getStringValue(@NotNull Map<String, Object> env,
            @NotNull String key) {
        Object value = env.get(key);
        if (value instanceof String s && !s.isEmpty()) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    private static @NotNull String readStreamToString(@NotNull InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_FILE_SIZE) {
                throw new IOException("File content exceeds size limit of " + MAX_FILE_SIZE);
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
