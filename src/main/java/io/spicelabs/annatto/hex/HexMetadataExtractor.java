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
package io.spicelabs.annatto.hex;

import io.spicelabs.annatto.AnnattoException.MetadataExtractionException;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import io.spicelabs.annatto.hex.ErlangTermTokenizer.Token;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Extracts metadata from Hex {@code .tar} packages.
 *
 * <p>A Hex package is a plain tar archive containing {@code VERSION},
 * {@code CHECKSUM}, {@code metadata.config}, and {@code contents.tar.gz}.
 * The {@code metadata.config} is in Erlang external term format, parsed
 * by {@link ErlangTermTokenizer} and {@link ErlangTermParser}.</p>
 *
 * <p>Handles both Elixir (mix) and Erlang (rebar3) requirement formats (Q8).</p>
 *
 * <p>This class is stateless; all methods are pure functions.</p>
 */
public final class HexMetadataExtractor {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB

    private HexMetadataExtractor() {
        // Static utility class
    }

    /**
     * Extracts metadata from a Hex .tar archive stream.
     *
     * @param inputStream the input stream of the .tar archive
     * @param filename    the artifact filename for error reporting
     * @return the raw metadata.config text
     * @throws MetadataExtractionException if extraction fails
     */
    public static @NotNull String extractMetadataConfig(
            @NotNull InputStream inputStream, @NotNull String filename)
            throws MetadataExtractionException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(inputStream)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (entry.getName().contains("..")) {
                    continue; // path traversal rejection
                }
                if ("metadata.config".equals(entry.getName())) {
                    return readEntry(tar, entry);
                }
            }
            throw new MetadataExtractionException(
                    "No metadata.config found in: " + filename);
        } catch (IOException e) {
            throw new MetadataExtractionException(
                    "Failed to read Hex archive: " + filename, e);
        }
    }

    /**
     * Builds a MetadataResult from raw metadata.config text.
     *
     * @param rawConfig the raw Erlang term format content
     * @return the normalized metadata result
     * @throws MetadataExtractionException if parsing fails
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull String rawConfig)
            throws MetadataExtractionException {
        try {
            List<Token> tokens = new ErlangTermTokenizer(rawConfig).tokenize();
            Map<String, Object> meta = new ErlangTermParser(tokens).parseMetadataConfig();

            Optional<String> name = getOptionalString(meta, "name");
            Optional<String> version = getOptionalString(meta, "version");
            Optional<String> description = getOptionalString(meta, "description");
            Optional<String> license = extractLicense(meta);
            List<ParsedDependency> dependencies = extractDependencies(meta);

            return new MetadataResult(
                    EcosystemId.HEX,
                    name,
                    name, // simpleName == name for Hex (Q6)
                    version,
                    description,
                    license,
                    Optional.empty(), // publisher: always null (Q4)
                    Optional.empty(), // publishedAt: always null (Q4)
                    dependencies
            );
        } catch (ErlangTermException e) {
            throw new MetadataExtractionException("Failed to parse metadata.config", e);
        }
    }

    /**
     * Extracts the license from the licenses list (Q3).
     * Joins multiple licenses with " OR ".
     */
    @SuppressWarnings("unchecked")
    static @NotNull Optional<String> extractLicense(@NotNull Map<String, Object> meta) {
        Object licensesObj = meta.get("licenses");
        if (!(licensesObj instanceof List<?> licensesList)) {
            return Optional.empty();
        }
        List<String> licenses = new ArrayList<>();
        for (Object item : licensesList) {
            if (item instanceof String s && !s.isEmpty()) {
                licenses.add(s);
            }
        }
        if (licenses.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join(" OR ", licenses));
    }

    /**
     * Extracts dependencies from the requirements field (Q5, Q8).
     *
     * <p>Handles both formats:</p>
     * <ul>
     *   <li>Elixir (mix): list of maps with "name" and "requirement" keys</li>
     *   <li>Erlang (rebar3): map of dep_name → attrs map with "requirement" key</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static @NotNull List<ParsedDependency> extractDependencies(@NotNull Map<String, Object> meta) {
        Object reqsObj = meta.get("requirements");
        if (reqsObj == null) {
            return List.of();
        }

        List<ParsedDependency> deps = new ArrayList<>();

        if (reqsObj instanceof Map<?, ?> reqsMap) {
            // Erlang (rebar3) format: {dep_name => {requirement => "...", ...}}
            for (Map.Entry<?, ?> entry : reqsMap.entrySet()) {
                String depName = entry.getKey().toString();
                Optional<String> vc = Optional.empty();
                if (entry.getValue() instanceof Map<?, ?> attrs) {
                    vc = getOptionalString((Map<String, Object>) attrs, "requirement");
                }
                deps.add(new ParsedDependency(depName, vc, Optional.of("runtime")));
            }
        } else if (reqsObj instanceof List<?> reqsList) {
            for (Object item : reqsList) {
                if (item instanceof Map<?, ?> reqMap) {
                    // Elixir (mix) format: map with "name" key
                    Map<String, Object> req = (Map<String, Object>) reqMap;
                    Optional<String> depName = getOptionalString(req, "name");
                    Optional<String> vc = getOptionalString(req, "requirement");
                    depName.ifPresent(name ->
                            deps.add(new ParsedDependency(name, vc, Optional.of("runtime"))));
                }
            }
        }

        // Sort by name for deterministic output
        List<ParsedDependency> sorted = new ArrayList<>(deps);
        sorted.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return List.copyOf(sorted);
    }

    /**
     * Safely extracts an optional string value from a map.
     */
    static @NotNull Optional<String> getOptionalString(
            @NotNull Map<String, Object> map, @NotNull String key) {
        Object value = map.get(key);
        if (value instanceof String s && !s.isEmpty()) {
            return Optional.of(s);
        }
        return Optional.empty();
    }

    private static String readEntry(TarArchiveInputStream tar, TarArchiveEntry entry)
            throws IOException {
        long size = entry.getSize();
        if (size > MAX_FILE_SIZE) {
            throw new IOException("metadata.config exceeds size limit");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(size, 8192));
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = tar.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_FILE_SIZE) {
                throw new IOException("metadata.config exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }
}
