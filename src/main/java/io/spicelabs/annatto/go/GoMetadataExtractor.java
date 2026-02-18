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
package io.spicelabs.annatto.go;

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
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts metadata from Go module artifact contents (.zip archives containing go.mod).
 * This class is stateless; all methods are pure functions.
 *
 * <p>Go module zips from proxy.golang.org contain entries like
 * {@code github.com/user/repo@v1.2.3/go.mod}. The extractor finds the go.mod file,
 * extracts the module path and version from it, and parses require directives as
 * dependencies.</p>
 *
 * <p>Covers {@link GoQuirks} Q1 (URL-like module paths), Q2 (+incompatible),
 * Q3 (pseudo-versions), Q4 (replace/exclude ignored), Q5 (major version suffixes),
 * Q6 (retracted versions ignored).</p>
 */
public final class GoMetadataExtractor {

    private GoMetadataExtractor() {
        // utility class
    }

    /**
     * Extracts metadata from the given Go module artifact stream.
     *
     * @param inputStream the artifact input stream (zip format)
     * @param filename    the artifact filename (for error reporting)
     * @return the extracted metadata result
     * @throws MetadataExtractionException if the archive cannot be read or parsed
     * @throws MalformedPackageException   if no go.mod is found in the archive
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream inputStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        GoModData goModData = extractGoModFromZip(inputStream, filename);
        return buildMetadataResult(goModData);
    }

    /**
     * Builds a {@link MetadataResult} from extracted go.mod data.
     * Parses the go.mod text and maps fields to the normalized metadata model.
     *
     * <p>This is the second step of the two-step extraction pattern (analogous to
     * {@code PypiMetadataExtractor.parseMetadataText}). Step one is
     * {@link #extractGoModFromZip}.</p>
     *
     * @param goModData the raw data extracted from the zip archive
     * @return the normalized metadata result
     */
    static @NotNull MetadataResult buildMetadataResult(@NotNull GoModData goModData) {
        ParsedGoMod parsed = parseGoMod(goModData.goModText());

        String modulePath = parsed.modulePath() != null ? parsed.modulePath() : goModData.modulePath();
        String version = goModData.version();

        return new MetadataResult(
                EcosystemId.GO,
                Optional.ofNullable(modulePath).filter(s -> !s.isEmpty()),
                Optional.ofNullable(modulePath != null ? extractSimpleName(modulePath) : null),
                Optional.ofNullable(version).filter(s -> !s.isEmpty()),
                Optional.empty(), // go.mod has no description
                Optional.empty(), // go.mod has no license field
                Optional.empty(), // go.mod has no author field
                Optional.empty(),
                parsed.requires()
        );
    }

    /**
     * Extracts go.mod text and entry metadata from a Go module zip archive.
     *
     * @param stream   the zip input stream
     * @param filename the filename for error reporting
     * @return the extracted go.mod data
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no go.mod found
     */
    static @NotNull GoModData extractGoModFromZip(@NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (ZipInputStream zipIn = new ZipInputStream(stream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (isGoMod(entryName)) {
                    String goModText = readStreamToString(zipIn);
                    String modulePath = extractModulePathFromEntryName(entryName);
                    String version = extractVersionFromEntryName(entryName);
                    return new GoModData(modulePath, version, goModText);
                }
            }
            throw new MalformedPackageException("No go.mod found in Go module zip: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read Go module zip: " + filename, e);
        }
    }

    /**
     * Parses go.mod text into structured data.
     *
     * <p>Parsing rules:</p>
     * <ol>
     *   <li>Lines starting with {@code module } → module path (strip quotes if present)</li>
     *   <li>Lines starting with {@code go } → Go version (not used in MetadataResult)</li>
     *   <li>{@code require (} starts a require block; {@code )} ends it</li>
     *   <li>Inside require block: {@code <path> <version>} with optional {@code // indirect}</li>
     *   <li>Single {@code require <path> <version>} outside blocks</li>
     *   <li>Lines starting with {@code replace}, {@code exclude}, {@code retract} → skip (Q4, Q6)</li>
     *   <li>Comments ({@code //}) stripped from dependency lines but {@code // indirect} checked first</li>
     * </ol>
     *
     * @param text the go.mod file content
     * @return the parsed go.mod data
     */
    static @NotNull ParsedGoMod parseGoMod(@NotNull String text) {
        String modulePath = null;
        String goVersion = null;
        List<ParsedDependency> requires = new ArrayList<>();
        boolean inRequireBlock = false;
        boolean inReplaceBlock = false;
        boolean inExcludeBlock = false;
        boolean inRetractBlock = false;

        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();

            // Skip empty lines and pure comment lines
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }

            // Check block terminators
            if (line.equals(")")) {
                inRequireBlock = false;
                inReplaceBlock = false;
                inExcludeBlock = false;
                inRetractBlock = false;
                continue;
            }

            // Skip lines inside replace/exclude/retract blocks (Q4, Q6)
            if (inReplaceBlock || inExcludeBlock || inRetractBlock) {
                continue;
            }

            // Block openers for directives we ignore
            if (line.startsWith("replace ") || line.equals("replace(") || line.startsWith("replace(")) {
                if (line.contains("(")) {
                    inReplaceBlock = true;
                }
                continue;
            }
            if (line.startsWith("exclude ") || line.equals("exclude(") || line.startsWith("exclude(")) {
                if (line.contains("(")) {
                    inExcludeBlock = true;
                }
                continue;
            }
            if (line.startsWith("retract ") || line.equals("retract(") || line.startsWith("retract(")) {
                if (line.contains("(")) {
                    inRetractBlock = true;
                }
                continue;
            }

            // Module directive
            if (line.startsWith("module ")) {
                modulePath = stripQuotes(line.substring("module ".length()).trim());
                continue;
            }

            // Go version directive
            if (line.startsWith("go ")) {
                goVersion = line.substring("go ".length()).trim();
                continue;
            }

            // Toolchain directive (go 1.21+)
            if (line.startsWith("toolchain ")) {
                continue;
            }

            // Require block opener
            if (line.equals("require (") || line.startsWith("require (")
                    || line.equals("require(") || line.startsWith("require(")) {
                inRequireBlock = true;
                // Check if there's content after "require (" on the same line
                String afterParen = line.substring(line.indexOf('(') + 1).trim();
                if (!afterParen.isEmpty() && !afterParen.equals(")")) {
                    parseRequireLine(afterParen, requires);
                }
                continue;
            }

            // Inside require block
            if (inRequireBlock) {
                parseRequireLine(line, requires);
                continue;
            }

            // Single-line require
            if (line.startsWith("require ")) {
                String rest = line.substring("require ".length()).trim();
                parseRequireLine(rest, requires);
            }
        }

        return new ParsedGoMod(modulePath, goVersion, List.copyOf(requires));
    }

    /**
     * Parses a single require line of the form {@code <path> <version> [// indirect]}.
     */
    private static void parseRequireLine(@NotNull String line, @NotNull List<ParsedDependency> requires) {
        // Check for // indirect before stripping comments
        boolean indirect = line.contains("// indirect");

        // Strip comments
        int commentIdx = line.indexOf("//");
        String clean = commentIdx >= 0 ? line.substring(0, commentIdx).trim() : line.trim();

        if (clean.isEmpty()) {
            return;
        }

        // Split into path and version
        String[] parts = clean.split("\\s+", 2);
        if (parts.length < 2) {
            return;
        }

        String path = stripQuotes(parts[0]);
        String version = parts[1];
        String scope = indirect ? "indirect" : "runtime";

        requires.add(new ParsedDependency(
                path,
                Optional.of(version),
                Optional.of(scope)
        ));
    }

    /**
     * Extracts the simple name from a Go module path.
     * The simple name is the last path segment.
     *
     * <p>For major version modules like {@code example.com/mod/v2},
     * returns {@code v2} (consistent with {@link io.spicelabs.annatto.common.PurlBuilder#forGo}).</p>
     *
     * @param modulePath the full Go module path
     * @return the simple name (last segment)
     */
    static @NotNull String extractSimpleName(@NotNull String modulePath) {
        int lastSlash = modulePath.lastIndexOf('/');
        return lastSlash >= 0 ? modulePath.substring(lastSlash + 1) : modulePath;
    }

    /**
     * Extracts the version from a Go module zip entry name.
     * Entry format: {@code github.com/user/repo@v1.2.3/go.mod}
     *
     * @param entryName the zip entry name containing go.mod
     * @return the version string (e.g., {@code v1.2.3})
     */
    static @NotNull String extractVersionFromEntryName(@NotNull String entryName) {
        // Find last @ sign, then take up to the next /
        int atIdx = entryName.lastIndexOf('@');
        if (atIdx < 0) {
            return "";
        }
        int slashIdx = entryName.indexOf('/', atIdx);
        return slashIdx > atIdx ? entryName.substring(atIdx + 1, slashIdx) : entryName.substring(atIdx + 1);
    }

    /**
     * Extracts the module path from a Go module zip entry name.
     * Entry format: {@code github.com/user/repo@v1.2.3/go.mod}
     *
     * @param entryName the zip entry name containing go.mod
     * @return the module path (e.g., {@code github.com/user/repo})
     */
    static @NotNull String extractModulePathFromEntryName(@NotNull String entryName) {
        int atIdx = entryName.lastIndexOf('@');
        return atIdx > 0 ? entryName.substring(0, atIdx) : entryName;
    }

    /**
     * Checks if a zip entry name represents a root-level go.mod file.
     * Pattern: entries ending in {@code /go.mod} where go.mod is an immediate child
     * of the module@version directory (i.e., one directory segment after @version).
     *
     * @param entryName the zip entry name
     * @return true if this is the module's root go.mod
     */
    static boolean isGoMod(@NotNull String entryName) {
        if (!entryName.endsWith("/go.mod") && !entryName.equals("go.mod")) {
            return false;
        }
        // Must contain @ for the module@version prefix
        int atIdx = entryName.lastIndexOf('@');
        if (atIdx < 0) {
            return false;
        }
        // After @version/, go.mod should be an immediate child
        // So the entry should look like: module@version/go.mod
        int slashAfterAt = entryName.indexOf('/', atIdx);
        if (slashAfterAt < 0) {
            return false;
        }
        // Everything after the first slash after @ should be "go.mod"
        String afterVersionSlash = entryName.substring(slashAfterAt + 1);
        return afterVersionSlash.equals("go.mod");
    }

    private static @NotNull String stripQuotes(@NotNull String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static @NotNull String readStreamToString(@NotNull InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    /**
     * Holds the raw data extracted from a Go module zip before parsing.
     */
    record GoModData(@NotNull String modulePath, @NotNull String version, @NotNull String goModText) {
    }

    /**
     * Holds the parsed structure of a go.mod file.
     */
    record ParsedGoMod(@Nullable String modulePath, @Nullable String goVersion,
                       @NotNull List<ParsedDependency> requires) {
    }
}
