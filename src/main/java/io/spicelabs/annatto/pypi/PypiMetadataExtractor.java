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
package io.spicelabs.annatto.pypi;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extracts metadata from PyPI artifact contents (.whl wheels and .tar.gz sdists).
 * This class is stateless; all methods are pure functions.
 *
 * <p>Wheels (.whl) are ZIP archives containing {@code *.dist-info/METADATA}.
 * Sdists (.tar.gz) are gzip-compressed tar archives containing top-level {@code PKG-INFO}.
 * Both metadata files use RFC 822 (email-style) header format.</p>
 *
 * <p>Covers {@link PypiQuirks} Q1 (name normalization), Q2 (sdist vs wheel),
 * Q4 (RFC 822 headers), Q5 (license classifiers), Q6 (author-email format),
 * Q7 (environment markers), Q8 (minimal packages).</p>
 */
public final class PypiMetadataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(PypiMetadataExtractor.class);

    /** PEP 508 dependency name pattern. */
    private static final Pattern DEP_NAME_PATTERN =
            Pattern.compile("^([A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?)");

    /** Pattern to extract name from "Name <email>" format. */
    private static final Pattern EMAIL_NAME_PATTERN = Pattern.compile("^([^<]+)<");

    private PypiMetadataExtractor() {
    }

    /**
     * Extracts metadata from a PyPI artifact stream.
     *
     * @param inputStream the artifact input stream
     * @param filename    the artifact filename (used to determine format)
     * @return the extracted metadata result
     * @throws MetadataExtractionException if the archive cannot be read or parsed
     * @throws MalformedPackageException   if no metadata file is found
     */
    public static @NotNull MetadataResult extract(@NotNull InputStream inputStream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        String metadataText;
        if (filename.endsWith(".whl")) {
            metadataText = extractMetadataFromWheel(inputStream, filename);
        } else if (filename.endsWith(".tar.gz")) {
            metadataText = extractMetadataFromSdist(inputStream, filename);
        } else {
            throw new MetadataExtractionException("Unsupported PyPI format: " + filename);
        }
        Map<String, List<String>> headers = parseRfc822Headers(metadataText);
        return parseMetadataText(headers);
    }

    /**
     * Parses extracted RFC 822 headers into a {@link MetadataResult}.
     *
     * @param headers the parsed headers
     * @return the metadata result
     */
    static @NotNull MetadataResult parseMetadataText(@NotNull Map<String, List<String>> headers) {
        Optional<String> name = getHeader(headers, "Name");
        Optional<String> version = getHeader(headers, "Version");
        Optional<String> description = getHeader(headers, "Summary");
        Optional<String> license = extractLicense(headers);
        Optional<String> publisher = extractPublisher(headers);
        List<ParsedDependency> dependencies = extractDependencies(headers);

        return new MetadataResult(
                EcosystemId.PYPI,
                name,
                name, // simpleName == name for PyPI (no namespace concept)
                version,
                description,
                license,
                publisher,
                Optional.empty(),
                dependencies
        );
    }

    /**
     * Extracts METADATA text from a .whl (ZIP) archive.
     *
     * @param stream   the ZIP input stream
     * @param filename the filename for error reporting
     * @return the METADATA file content as text
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no METADATA found
     */
    static @NotNull String extractMetadataFromWheel(@NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (ZipInputStream zipIn = new ZipInputStream(stream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (isDistInfoMetadata(entry.getName())) {
                    return readStreamToString(zipIn);
                }
            }
            throw new MalformedPackageException("No .dist-info/METADATA found in wheel: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read wheel archive: " + filename, e);
        }
    }

    /**
     * Extracts PKG-INFO text from a .tar.gz (sdist) archive.
     *
     * @param stream   the gzipped tar input stream
     * @param filename the filename for error reporting
     * @return the PKG-INFO file content as text
     * @throws MetadataExtractionException if I/O fails
     * @throws MalformedPackageException   if no PKG-INFO found
     */
    static @NotNull String extractMetadataFromSdist(@NotNull InputStream stream, @NotNull String filename)
            throws MetadataExtractionException, MalformedPackageException {
        try (GZIPInputStream gzipIn = new GZIPInputStream(stream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn, StandardCharsets.UTF_8.name())) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (isPkgInfo(entry.getName())) {
                    return readStreamToString(tarIn);
                }
            }
            throw new MalformedPackageException("No PKG-INFO found in sdist: " + filename);
        } catch (MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new MetadataExtractionException("Failed to read sdist archive: " + filename, e);
        }
    }

    /**
     * Parses RFC 822 header text into a map of header name to list of values.
     * Handles continuation lines (starting with space/tab) and repeated headers
     * (e.g., Requires-Dist, Classifier).
     *
     * @param text the RFC 822 formatted text
     * @return map of header names to their values (preserving insertion order)
     */
    static @NotNull Map<String, List<String>> parseRfc822Headers(@NotNull String text) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String[] lines = text.split("\n");
        String currentKey = null;
        StringBuilder currentValue = null;
        boolean inBody = false;
        StringBuilder body = new StringBuilder();

        for (String rawLine : lines) {
            // Remove trailing \r if present
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;

            if (inBody) {
                body.append(line).append('\n');
                continue;
            }

            // Blank line separates headers from body
            if (line.isEmpty()) {
                // Save current header if any
                if (currentKey != null && currentValue != null) {
                    headers.computeIfAbsent(currentKey, k -> new ArrayList<>()).add(currentValue.toString().trim());
                }
                currentKey = null;
                currentValue = null;
                inBody = true;
                continue;
            }

            // Continuation line
            if ((line.startsWith(" ") || line.startsWith("\t")) && currentKey != null) {
                currentValue.append('\n').append(line);
                continue;
            }

            // New header line
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                // Save previous header
                if (currentKey != null && currentValue != null) {
                    headers.computeIfAbsent(currentKey, k -> new ArrayList<>()).add(currentValue.toString().trim());
                }
                currentKey = line.substring(0, colonIdx).trim();
                currentValue = new StringBuilder(line.substring(colonIdx + 1));
            }
        }

        // Save last header
        if (currentKey != null && currentValue != null) {
            headers.computeIfAbsent(currentKey, k -> new ArrayList<>()).add(currentValue.toString().trim());
        }

        // Save body
        String bodyText = body.toString().trim();
        if (!bodyText.isEmpty()) {
            headers.put("__body__", List.of(bodyText));
        }

        return Collections.unmodifiableMap(headers);
    }

    /**
     * Extracts the license, with priority:
     * 1. License-Expression header (SPDX)
     * 2. License header (skip "UNKNOWN")
     * 3. Classifier: License :: OSI Approved :: ... (joined with " OR ")
     *
     * @param headers the parsed RFC 822 headers
     * @return the license string, or empty if not found
     */
    static @NotNull Optional<String> extractLicense(@NotNull Map<String, List<String>> headers) {
        // 1. License-Expression (SPDX)
        Optional<String> licenseExpr = getHeader(headers, "License-Expression");
        if (licenseExpr.isPresent()) {
            return licenseExpr;
        }

        // 2. License header (skip UNKNOWN)
        Optional<String> license = getHeader(headers, "License");
        if (license.isPresent()) {
            return license;
        }

        // 3. Classifiers
        List<String> classifiers = headers.getOrDefault("Classifier", List.of());
        List<String> licenseNames = new ArrayList<>();
        for (String c : classifiers) {
            if (c.startsWith("License :: OSI Approved :: ")) {
                String[] parts = c.split(" :: ");
                if (parts.length >= 3) {
                    licenseNames.add(parts[parts.length - 1]);
                }
            } else if (c.startsWith("License :: ") && !c.contains("OSI Approved")) {
                String[] parts = c.split(" :: ");
                if (parts.length >= 2) {
                    licenseNames.add(parts[parts.length - 1]);
                }
            }
        }
        if (!licenseNames.isEmpty()) {
            return Optional.of(String.join(" OR ", licenseNames));
        }

        return Optional.empty();
    }

    /**
     * Extracts the publisher, with priority:
     * 1. Author header (skip "UNKNOWN")
     * 2. Author-email → extract name from "Name &lt;email&gt;"
     * 3. Maintainer header
     * 4. Maintainer-email
     *
     * @param headers the parsed RFC 822 headers
     * @return the publisher name, or empty if not found
     */
    static @NotNull Optional<String> extractPublisher(@NotNull Map<String, List<String>> headers) {
        // 1. Author
        Optional<String> author = getHeader(headers, "Author");
        if (author.isPresent()) {
            return author;
        }

        // 2. Author-email -> extract name part
        Optional<String> authorEmail = getHeader(headers, "Author-email");
        if (authorEmail.isPresent()) {
            Optional<String> name = extractNameFromEmailField(authorEmail.get());
            if (name.isPresent()) {
                return name;
            }
        }

        // 3. Maintainer
        Optional<String> maintainer = getHeader(headers, "Maintainer");
        if (maintainer.isPresent()) {
            return maintainer;
        }

        // 4. Maintainer-email
        Optional<String> maintainerEmail = getHeader(headers, "Maintainer-email");
        if (maintainerEmail.isPresent()) {
            Optional<String> name = extractNameFromEmailField(maintainerEmail.get());
            if (name.isPresent()) {
                return name;
            }
        }

        return Optional.empty();
    }

    /**
     * Extracts a name from an email field of the form "Name &lt;email&gt;".
     * If the field contains just an email address (no name part), returns empty.
     *
     * @param emailField the email field value
     * @return the extracted name, or empty
     */
    static @NotNull Optional<String> extractNameFromEmailField(@NotNull String emailField) {
        String trimmed = emailField.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = EMAIL_NAME_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            String name = matcher.group(1).trim();
            // Strip surrounding quotes if present
            if (name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1).trim();
            }
            return name.isEmpty() ? Optional.empty() : Optional.of(name);
        }
        // No angle bracket - might be just an email or a bare name
        if (trimmed.contains("@")) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    /**
     * Parses a Requires-Dist line into a {@link ParsedDependency}.
     * PEP 508 format: {@code name [extras] (version-spec) ; env-markers}
     * Environment markers and extras are stripped. All dependencies are scoped as "runtime".
     *
     * @param line the Requires-Dist header value
     * @return the parsed dependency, or empty if the line is unparseable
     */
    static @NotNull Optional<ParsedDependency> parseRequiresDist(@NotNull String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        // Strip environment markers (after ;)
        int semicolonIdx = trimmed.indexOf(';');
        if (semicolonIdx >= 0) {
            trimmed = trimmed.substring(0, semicolonIdx).trim();
        }

        // Strip extras (inside [])
        int bracketIdx = trimmed.indexOf('[');
        if (bracketIdx >= 0) {
            int closeIdx = trimmed.indexOf(']', bracketIdx);
            if (closeIdx >= 0) {
                trimmed = trimmed.substring(0, bracketIdx) + trimmed.substring(closeIdx + 1);
                trimmed = trimmed.trim();
            }
        }

        // Extract name
        Matcher nameMatcher = DEP_NAME_PATTERN.matcher(trimmed);
        if (!nameMatcher.find()) {
            return Optional.empty();
        }
        String name = nameMatcher.group(1);
        String rest = trimmed.substring(nameMatcher.end()).trim();

        // Extract version constraint - strip outer parentheses if present
        if (rest.startsWith("(") && rest.endsWith(")")) {
            rest = rest.substring(1, rest.length() - 1).trim();
        }

        Optional<String> versionConstraint = rest.isEmpty() ? Optional.empty() : Optional.of(rest);

        return Optional.of(new ParsedDependency(name, versionConstraint, Optional.of("runtime")));
    }

    /**
     * Normalizes a PyPI package name per PEP 503:
     * lowercase, replace runs of [-_.] with a single hyphen.
     *
     * @param name the raw package name
     * @return the normalized name
     */
    static @NotNull String normalizeName(@NotNull String name) {
        return name.toLowerCase().replaceAll("[-_.]+", "-");
    }

    /**
     * Checks if a ZIP entry name matches {@code *.dist-info/METADATA} at exactly one level deep.
     *
     * @param entryName the ZIP entry name
     * @return true if this is the dist-info METADATA file
     */
    static boolean isDistInfoMetadata(@NotNull String entryName) {
        String normalized = entryName.replace('\\', '/');
        String[] parts = normalized.split("/");
        return parts.length == 2
                && parts[0].endsWith(".dist-info")
                && parts[1].equals("METADATA");
    }

    /**
     * Checks if a tar entry name matches a top-level PKG-INFO at exactly one level deep.
     *
     * @param entryName the tar entry name
     * @return true if this is the top-level PKG-INFO file
     */
    static boolean isPkgInfo(@NotNull String entryName) {
        String normalized = entryName.replace('\\', '/');
        String[] parts = normalized.split("/");
        return parts.length == 2
                && parts[1].equals("PKG-INFO");
    }

    /**
     * Returns the parsed headers from the given metadata text.
     * Exposed for {@link PypiMemento} to store raw headers.
     */
    static @NotNull Map<String, List<String>> extractHeaders(@NotNull InputStream inputStream,
            @NotNull String filename) throws MetadataExtractionException, MalformedPackageException {
        String metadataText;
        if (filename.endsWith(".whl")) {
            metadataText = extractMetadataFromWheel(inputStream, filename);
        } else if (filename.endsWith(".tar.gz")) {
            metadataText = extractMetadataFromSdist(inputStream, filename);
        } else {
            throw new MetadataExtractionException("Unsupported PyPI format: " + filename);
        }
        return parseRfc822Headers(metadataText);
    }

    // --- Private helpers ---

    private static @NotNull List<ParsedDependency> extractDependencies(
            @NotNull Map<String, List<String>> headers) {
        List<String> requiresDist = headers.getOrDefault("Requires-Dist", List.of());
        List<ParsedDependency> deps = new ArrayList<>();
        for (String rd : requiresDist) {
            parseRequiresDist(rd).ifPresent(deps::add);
        }
        return List.copyOf(deps);
    }

    private static @NotNull Optional<String> getHeader(@NotNull Map<String, List<String>> headers,
            @NotNull String key) {
        List<String> values = headers.get(key);
        if (values == null || values.isEmpty()) {
            return Optional.empty();
        }
        for (String v : values) {
            if (v != null && !v.isEmpty() && !"UNKNOWN".equalsIgnoreCase(v)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
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
}
