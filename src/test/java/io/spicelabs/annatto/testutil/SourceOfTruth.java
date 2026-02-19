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

package io.spicelabs.annatto.testutil;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Loads source-of-truth expected values from JSON files produced by Docker native tools.
 *
 * <p>Expected JSON files live in {@code src/test/resources/<ecosystem>/} (git-tracked).
 * Package files live in {@code test-corpus/<ecosystem>/} (downloaded from
 * {@code public-test-data.spice-labs.dev}).</p>
 *
 * <p>Expected file naming convention: {@code <package-basename>-expected.json}</p>
 */
public final class SourceOfTruth {

    private static final Gson GSON = new Gson();

    private SourceOfTruth() {
    }

    /**
     * Loads expected metadata from a JSON file.
     *
     * @param expectedJsonPath path to the expected JSON file
     * @return the parsed JSON object
     * @throws IOException if the file cannot be read
     */
    public static @NotNull JsonObject loadExpected(@NotNull Path expectedJsonPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(expectedJsonPath)) {
            return GSON.fromJson(reader, JsonObject.class);
        }
    }

    /**
     * Resolves the expected JSON path for a given package file.
     * The package file is in {@code test-corpus/<ecosystem>/}, and the expected JSON
     * is in {@code src/test/resources/<ecosystem>/}.
     *
     * <p>For example, given {@code test-corpus/npm/lodash-4.17.21.tgz},
     * returns {@code src/test/resources/npm/lodash-4.17.21-expected.json}.</p>
     *
     * @param packagePath  the path to the test package file in the corpus directory
     * @param expectedDir  the directory containing expected JSON files
     * @return the path to the expected JSON file
     */
    public static @NotNull Path expectedPathFor(@NotNull Path packagePath, @NotNull Path expectedDir) {
        String baseName = stripArchiveExtension(packagePath.getFileName().toString());
        return expectedDir.resolve(baseName + "-expected.json");
    }

    /**
     * Strips known archive extensions from a filename, returning the base name.
     *
     * @param filename the archive filename
     * @return the filename without its archive extension
     */
    static @NotNull String stripArchiveExtension(@NotNull String filename) {
        if (filename.endsWith(".tar.gz")) {
            return filename.substring(0, filename.length() - ".tar.gz".length());
        } else if (filename.endsWith(".tar.bz2")) {
            return filename.substring(0, filename.length() - ".tar.bz2".length());
        } else if (filename.endsWith(".podspec.json")) {
            return filename.substring(0, filename.length() - ".podspec.json".length());
        } else if (filename.endsWith(".src.rock")) {
            return filename.substring(0, filename.length() - ".src.rock".length());
        } else if (filename.endsWith(".all.rock")) {
            return filename.substring(0, filename.length() - ".all.rock".length());
        } else {
            int lastDot = filename.lastIndexOf('.');
            return lastDot > 0 ? filename.substring(0, lastDot) : filename;
        }
    }

    /**
     * Extracts a string field from expected JSON, returning Optional.empty() if absent or null.
     *
     * @param expected  the expected JSON object
     * @param fieldName the field name to extract
     * @return the field value, or empty if absent
     */
    public static @NotNull Optional<String> getString(@NotNull JsonObject expected, @NotNull String fieldName) {
        if (expected.has(fieldName) && !expected.get(fieldName).isJsonNull()) {
            return Optional.of(expected.get(fieldName).getAsString());
        }
        return Optional.empty();
    }
}
