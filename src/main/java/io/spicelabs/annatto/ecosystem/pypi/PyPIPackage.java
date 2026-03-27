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

package io.spicelabs.annatto.ecosystem.pypi;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.internal.PathValidator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A PyPI package (.whl wheel or .tar.gz sdist).
 *
 * <p>Implements LanguagePackage for PyPI packages, providing metadata extraction
 * and entry streaming for both wheel and sdist formats.
 */
public final class PyPIPackage implements LanguagePackage {

    private static final String MIME_TYPE_WHEEL = "application/zip";
    private static final String MIME_TYPE_SDIST = "application/gzip";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;

    private final String filename;
    private final PackageMetadata metadata;
    private final byte[] data;
    private final boolean isWheel;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a PyPIPackage from a file path.
     *
     * @param path the .whl or .tar.gz file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static PyPIPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create a PyPIPackage from an input stream.
     *
     * @param stream the input stream (.whl or .tar.gz)
     * @param filename for error reporting and format detection
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static PyPIPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        // Buffer the stream for multiple reads
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        // Determine format and extract metadata
        boolean isWheel = filename.endsWith(".whl");

        // Extract metadata directly
        PackageMetadata metadata = extractMetadata(data, filename);

        return new PyPIPackage(filename, metadata, data, isWheel);
    }

    private PyPIPackage(String filename, PackageMetadata metadata, byte[] data, boolean isWheel) {
        this.filename = filename;
        this.metadata = metadata;
        this.data = data;
        this.isWheel = isWheel;
    }

    @Override
    public @NotNull String mimeType() {
        return isWheel ? MIME_TYPE_WHEEL : MIME_TYPE_SDIST;
    }

    @Override
    public @NotNull Ecosystem ecosystem() {
        return Ecosystem.PYPI;
    }

    @Override
    public @NotNull String name() {
        return metadata.name();
    }

    @Override
    public @NotNull String version() {
        return metadata.version();
    }

    @Override
    public @NotNull PackageMetadata metadata() {
        return metadata;
    }

    @Override
    public @NotNull Optional<PackageURL> toPurl() {
        String name = metadata.name();
        String version = metadata.version();

        if (name.isEmpty() || version.isEmpty()) {
            return Optional.empty();
        }

        // Normalize name per PEP 503
        String normalizedName = normalizeName(name);

        try {
            return Optional.of(new PackageURL("pypi", null, normalizedName, version, null, null));
        } catch (MalformedPackageURLException e) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull PackageEntryStream streamEntries() throws IOException {
        if (streamOpen.compareAndSet(false, true)) {
            return isWheel ? new WheelEntryStream() : new SdistEntryStream();
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        // Nothing to close - data is in memory
        streamOpen.set(false);
    }

    private static PackageMetadata extractMetadata(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        String metadataText;
        if (filename.endsWith(".whl")) {
            metadataText = extractMetadataFromWheel(data, filename);
        } else if (filename.endsWith(".tar.gz")) {
            metadataText = extractMetadataFromSdist(data, filename);
        } else {
            throw new AnnattoException.MalformedPackageException("Unsupported PyPI format: " + filename);
        }
        Map<String, List<String>> headers = parseRfc822Headers(metadataText);
        return buildMetadata(headers);
    }

    private static String extractMetadataFromWheel(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (isDistInfoMetadata(entry.getName())) {
                    return readStreamToString(zipIn);
                }
            }
            throw new AnnattoException.MalformedPackageException("No .dist-info/METADATA found in wheel: " + filename);
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException("Failed to read wheel archive: " + filename, e);
        }
    }

    private static String extractMetadataFromSdist(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        // Pre-decompress GZIP to avoid concurrency issues with Inflater
        byte[] tarData = decompressGzipToBytes(data, filename);
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new ByteArrayInputStream(tarData), StandardCharsets.UTF_8.name())) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if (isPkgInfo(entry.getName())) {
                    return readStreamToString(tarIn);
                }
            }
            throw new AnnattoException.MalformedPackageException("No PKG-INFO found in sdist: " + filename);
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException("Failed to read sdist archive: " + filename, e);
        }
    }

    private static byte[] decompressGzipToBytes(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            gzis.transferTo(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException("Failed to decompress sdist: " + filename, e);
        }
    }

    private static boolean isDistInfoMetadata(String entryName) {
        String normalized = entryName.replace('\\', '/');
        String[] parts = normalized.split("/");
        return parts.length == 2
                && parts[0].endsWith(".dist-info")
                && parts[1].equals("METADATA");
    }

    private static boolean isPkgInfo(String entryName) {
        String normalized = entryName.replace('\\', '/');
        String[] parts = normalized.split("/");
        return parts.length == 2
                && parts[1].equals("PKG-INFO");
    }

    private static String readStreamToString(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static Map<String, List<String>> parseRfc822Headers(String text) {
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

        String bodyText = body.toString().trim();
        if (!bodyText.isEmpty()) {
            headers.put("__body__", List.of(bodyText));
        }

        return Collections.unmodifiableMap(headers);
    }

    private static PackageMetadata buildMetadata(Map<String, List<String>> headers) {
        String name = getHeader(headers, "Name").orElse("");
        String version = getHeader(headers, "Version").orElse("");
        Optional<String> description = getHeader(headers, "Summary");
        Optional<String> license = extractLicense(headers);
        Optional<String> publisher = extractPublisher(headers);

        List<Dependency> dependencies = extractDependencies(headers);

        Map<String, Object> raw = new HashMap<>();
        getHeader(headers, "Home-page").ifPresent(v -> raw.put("home_page", v));
        getHeader(headers, "Author-email").ifPresent(v -> raw.put("author_email", v));
        getHeader(headers, "Maintainer").ifPresent(v -> raw.put("maintainer", v));

        return new PackageMetadata(
            name, version, description, license, publisher,
            Optional.empty(), dependencies, raw
        );
    }

    private static Optional<String> getHeader(Map<String, List<String>> headers, String key) {
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

    private static Optional<String> extractLicense(Map<String, List<String>> headers) {
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

    private static Optional<String> extractPublisher(Map<String, List<String>> headers) {
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
            return extractNameFromEmailField(maintainerEmail.get());
        }

        return Optional.empty();
    }

    private static Optional<String> extractNameFromEmailField(String emailField) {
        String trimmed = emailField.trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        int angleIdx = trimmed.indexOf('<');
        if (angleIdx > 0) {
            String name = trimmed.substring(0, angleIdx).trim();
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

    private static List<Dependency> extractDependencies(Map<String, List<String>> headers) {
        List<String> requiresDist = headers.getOrDefault("Requires-Dist", List.of());
        List<Dependency> deps = new ArrayList<>();
        for (String rd : requiresDist) {
            parseRequiresDist(rd).ifPresent(deps::add);
        }
        return deps;
    }

    private static Optional<Dependency> parseRequiresDist(String line) {
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

        // Extract name using simple pattern matching
        int i = 0;
        while (i < trimmed.length() && (Character.isLetterOrDigit(trimmed.charAt(i))
                || trimmed.charAt(i) == '_' || trimmed.charAt(i) == '-' || trimmed.charAt(i) == '.')) {
            i++;
        }
        if (i == 0) {
            return Optional.empty();
        }
        String name = trimmed.substring(0, i);
        String rest = trimmed.substring(i).trim();

        // Extract version constraint - strip outer parentheses if present
        if (rest.startsWith("(") && rest.endsWith(")")) {
            rest = rest.substring(1, rest.length() - 1).trim();
        }

        String versionConstraint = rest.isEmpty() ? "" : rest;

        return Optional.of(new Dependency(name, Optional.of("runtime"), versionConstraint));
    }

    private static String normalizeName(String name) {
        return name.toLowerCase().replaceAll("[-_.]+", "-");
    }

    /**
     * Entry stream implementation for wheel packages (ZIP format).
     */
    private class WheelEntryStream implements PackageEntryStream {
        private final ZipInputStream zipIn;
        private ZipEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        WheelEntryStream() throws IOException {
            this.zipIn = new ZipInputStream(new ByteArrayInputStream(data), StandardCharsets.UTF_8);
        }

        @Override
        public boolean hasNext() throws IOException {
            checkClosed();
            if (entryCount >= MAX_ENTRIES) {
                throw new AnnattoException.SecurityException(
                    "Package exceeds maximum entry count: " + MAX_ENTRIES);
            }
            currentEntry = zipIn.getNextEntry();
            if (currentEntry != null) {
                entryCount++;
            }
            return currentEntry != null;
        }

        @Override
        public @NotNull PackageEntry nextEntry() throws IOException {
            checkClosed();
            if (currentEntry == null) {
                throw new IllegalStateException("No current entry - call hasNext() first");
            }

            String name = PathValidator.validateEntryName(currentEntry.getName());
            long size = currentEntry.getSize();

            return new PackageEntry(
                name,
                size,
                currentEntry.isDirectory(),
                false, // ZIP doesn't support symlinks directly
                Optional.empty()
            );
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            checkClosed();
            if (currentEntry == null) {
                throw new IllegalStateException("No current entry");
            }

            long size = currentEntry.getSize();
            if (size > MAX_ENTRY_SIZE) {
                throw new AnnattoException.SecurityException(
                    "Entry exceeds size limit: " + currentEntry.getName() +
                    " (" + size + " > " + MAX_ENTRY_SIZE + ")");
            }

            // Read entry content into buffer
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = zipIn.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > MAX_ENTRY_SIZE) {
                    throw new AnnattoException.SecurityException(
                        "Entry exceeds size limit during read");
                }
                baos.write(buffer, 0, read);
            }

            return new ByteArrayInputStream(baos.toByteArray());
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    zipIn.close();
                } catch (IOException e) {
                    // Ignore
                }
                streamOpen.set(false);
            }
        }

        private void checkClosed() {
            if (closed) {
                throw new IllegalStateException("Stream is closed");
            }
        }
    }

    /**
     * Entry stream implementation for sdist packages (tar.gz format).
     */
    private class SdistEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        SdistEntryStream() throws IOException {
            this.tarIn = new TarArchiveInputStream(
                new GZIPInputStream(new ByteArrayInputStream(data)),
                StandardCharsets.UTF_8.name());
        }

        @Override
        public boolean hasNext() throws IOException {
            checkClosed();
            if (entryCount >= MAX_ENTRIES) {
                throw new AnnattoException.SecurityException(
                    "Package exceeds maximum entry count: " + MAX_ENTRIES);
            }
            currentEntry = tarIn.getNextEntry();
            if (currentEntry != null) {
                entryCount++;
            }
            return currentEntry != null;
        }

        @Override
        public @NotNull PackageEntry nextEntry() throws IOException {
            checkClosed();
            if (currentEntry == null) {
                throw new IllegalStateException("No current entry - call hasNext() first");
            }

            String name = PathValidator.validateEntryName(currentEntry.getName());
            long size = currentEntry.getSize();

            return new PackageEntry(
                name,
                size,
                currentEntry.isDirectory(),
                currentEntry.isSymbolicLink(),
                currentEntry.isSymbolicLink()
                    ? Optional.ofNullable(currentEntry.getLinkName())
                    : Optional.empty()
            );
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            checkClosed();
            if (currentEntry == null) {
                throw new IllegalStateException("No current entry");
            }

            long size = currentEntry.getSize();
            if (size > MAX_ENTRY_SIZE) {
                throw new AnnattoException.SecurityException(
                    "Entry exceeds size limit: " + currentEntry.getName() +
                    " (" + size + " > " + MAX_ENTRY_SIZE + ")");
            }

            // Read entry content into buffer
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = tarIn.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > MAX_ENTRY_SIZE) {
                    throw new AnnattoException.SecurityException(
                        "Entry exceeds size limit during read");
                }
                baos.write(buffer, 0, read);
            }

            return new ByteArrayInputStream(baos.toByteArray());
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    tarIn.close();
                } catch (IOException e) {
                    // Ignore
                }
                streamOpen.set(false);
            }
        }

        private void checkClosed() {
            if (closed) {
                throw new IllegalStateException("Stream is closed");
            }
        }
    }
}
