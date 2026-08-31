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
import io.spicelabs.annatto.internal.Archives;
import io.spicelabs.annatto.internal.EntryContentStream;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.PackageSource;
import io.spicelabs.annatto.internal.Spool;
import io.spicelabs.annatto.markers.PkgInfoMarker;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A PyPI package (.whl wheel or .tar.gz sdist).
 *
 * <p>Phase 7 (Bug 2): never read whole into memory. Wheel metadata is read via random-access
 * {@link ZipFile} (central directory gives real sizes); sdist metadata is a bounded streaming
 * gzip scan; entry streams open fresh per pass with per-pass budgets.
 */
public final class PyPIPackage implements LanguagePackage {

    private static final String MIME_TYPE_WHEEL = "application/zip";
    private static final String MIME_TYPE_SDIST = "application/gzip";
    private static final long MAX_METADATA_SIZE = 1024 * 1024; // 1MB (plan limits table)

    private final String filename;
    private final PackageMetadata metadata;
    private final PackageSource source;
    private final boolean isWheel;
    private final Limits limits;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Create a PyPIPackage from a file path (direct read).
     *
     * @param path the .whl or .tar.gz file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static PyPIPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromSource(new PackageSource.PathSource(path), basename(path), Limits.DEFAULT);
    }

    /**
     * Create a PyPIPackage from an input stream (bounded spool).
     *
     * @param stream the input stream (.whl or .tar.gz)
     * @param filename for error reporting and format detection
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static PyPIPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromStream(stream, filename, Limits.DEFAULT);
    }

    /**
     * Create a PyPIPackage from an input stream with explicit resource limits.
     *
     * @param stream the input stream (.whl or .tar.gz)
     * @param filename for error reporting and format detection
     * @param limits resource limits (spool/scan/stream-pass/zip-pass/entry bounds)
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     * @throws AnnattoException.SecurityException if a resource limit is exceeded
     */
    public static PyPIPackage fromStream(InputStream stream, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Spool.Spooled spooled = Spool.create(stream, basename(filename), limits.spoolBytes());
        return adoptSpool(spooled, filename, limits);
    }

    /**
     * Adopt an owned spooled file (reader stream handoff); {@code close()} deletes it (S-5).
     */
    public static PyPIPackage adoptSpool(Spool.Spooled spooled, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        PackageSource.SpooledSource src = new PackageSource.SpooledSource(
                spooled.path(),
                Spool.registration(spooled.path(), spooled.chargedBytes(), new AtomicBoolean(false)));
        return fromSource(src, basename(filename), limits);
    }

    private static PyPIPackage fromSource(PackageSource source, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        boolean isWheel = filename.toLowerCase(Locale.ROOT).endsWith(".whl");
        PackageMetadata metadata;
        try {
            metadata = isWheel
                ? extractWheelMetadata(source.path(), filename, limits)
                : extractSdistMetadata(source.path(), filename, limits);
        } catch (Exception e) {
            source.releaseResources();
            throw e;
        }
        return new PyPIPackage(filename, source, metadata, isWheel, limits);
    }

    private PyPIPackage(String filename, PackageSource source, PackageMetadata metadata,
                        boolean isWheel, Limits limits) {
        this.filename = filename;
        this.source = source;
        this.metadata = metadata;
        this.isWheel = isWheel;
        this.limits = limits;
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
        if (closed) {
            throw new IllegalStateException("Package is closed");
        }
        if (!streamOpen.compareAndSet(false, true)) {
            throw new IllegalStateException("A stream is already open on this package");
        }
        try {
            return isWheel ? new WheelEntryStream() : new SdistEntryStream();
        } catch (IOException | RuntimeException e) {
            streamOpen.set(false);
            throw e;
        }
    }

    @Override
    public void close() {
        closed = true;
        source.releaseResources();
        streamOpen.set(false);
    }

    // ================================================================
    // Metadata extraction (streaming / random-access, no whole-file buffer)
    // ================================================================

    private static PackageMetadata extractWheelMetadata(Path file, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        try (ZipFile zf = Archives.zipFile(file)) {
            int count = 0;
            Enumeration<ZipArchiveEntry> entries = zf.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > limits.maxEntries()) {
                    throw new AnnattoException.SecurityException(
                        "Archive exceeds metadata scan entry count limit: " + filename);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (PkgInfoMarker.isDistInfoMetadata(entry.getName())) {
                    String metadataText = readBounded(zf.getInputStream(entry),
                            Math.min(limits.entryBytes(), MAX_METADATA_SIZE), filename);
                    return buildMetadata(parseRfc822Headers(metadataText));
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No .dist-info/METADATA found in wheel: " + filename);
        }
    }

    private static PackageMetadata extractSdistMetadata(Path file, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        try (TarArchiveInputStream tarIn = Archives.gzipTar(file, filename, limits.scanBytes(), () -> { })) {
            int count = 0;
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (++count > limits.maxEntries()) {
                    throw new AnnattoException.SecurityException(
                        "Archive exceeds metadata scan entry count limit: " + filename);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (PkgInfoMarker.isPkgInfo(entry.getName())) {
                    String metadataText = readBounded(tarIn, Math.min(limits.entryBytes(), MAX_METADATA_SIZE), filename);
                    return buildMetadata(parseRfc822Headers(metadataText));
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No PKG-INFO found in sdist: " + filename);
        }
    }

    private static String readBounded(InputStream stream, long cap, String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > cap) {
                throw new AnnattoException.SecurityException(
                    "Metadata file exceeds size limit: " + filename);
            }
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
        Optional<String> licenseExpr = getHeader(headers, "License-Expression");
        if (licenseExpr.isPresent()) {
            return licenseExpr;
        }
        Optional<String> license = getHeader(headers, "License");
        if (license.isPresent()) {
            return license;
        }
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
        Optional<String> author = getHeader(headers, "Author");
        if (author.isPresent()) {
            return author;
        }
        Optional<String> authorEmail = getHeader(headers, "Author-email");
        if (authorEmail.isPresent()) {
            Optional<String> name = extractNameFromEmailField(authorEmail.get());
            if (name.isPresent()) {
                return name;
            }
        }
        Optional<String> maintainer = getHeader(headers, "Maintainer");
        if (maintainer.isPresent()) {
            return maintainer;
        }
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
            if (name.startsWith("\"") && name.endsWith("\"")) {
                name = name.substring(1, name.length() - 1).trim();
            }
            return name.isEmpty() ? Optional.empty() : Optional.of(name);
        }
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
        int semicolonIdx = trimmed.indexOf(';');
        if (semicolonIdx >= 0) {
            trimmed = trimmed.substring(0, semicolonIdx).trim();
        }
        int bracketIdx = trimmed.indexOf('[');
        if (bracketIdx >= 0) {
            int closeIdx = trimmed.indexOf(']', bracketIdx);
            if (closeIdx >= 0) {
                trimmed = trimmed.substring(0, bracketIdx) + trimmed.substring(closeIdx + 1);
                trimmed = trimmed.trim();
            }
        }
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
        if (rest.startsWith("(") && rest.endsWith(")")) {
            rest = rest.substring(1, rest.length() - 1).trim();
        }
        String versionConstraint = rest.isEmpty() ? "" : rest;
        return Optional.of(new Dependency(name, Optional.of("runtime"), versionConstraint));
    }

    private static String normalizeName(String name) {
        return name.toLowerCase().replaceAll("[-_.]+", "-");
    }

    private static String basename(Path path) {
        return path.getFileName().toString();
    }

    private static String basename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }
        int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return slash < 0 ? filename : filename.substring(slash + 1);
    }

    // ================================================================
    // Entry streams (fresh per pass; per-pass inflated budgets)
    // ================================================================

    /**
     * Wheel entry stream: fresh {@link ZipFile} per pass (random access, real sizes); the
     * per-pass ZIP inflated budget accumulates across opened entries and latches on trip.
     */
    private class WheelEntryStream implements PackageEntryStream {
        private final ZipFile zf;
        private final java.util.Enumeration<ZipArchiveEntry> entries;
        private final AtomicLong passInflated = new AtomicLong();
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        WheelEntryStream() throws IOException {
            this.zf = Archives.zipFile(source.path());
            this.entries = zf.getEntries();
        }

        @Override
        public boolean hasNext() throws IOException {
            checkClosed();
            checkBudget();
            if (entryCount >= limits.maxEntries()) {
                throw new AnnattoException.SecurityException(
                    "Package exceeds maximum entry count: " + limits.maxEntries());
            }
            currentEntry = entries.hasMoreElements() ? entries.nextElement() : null;
            if (currentEntry != null) {
                entryCount++;
            }
            return currentEntry != null;
        }

        @Override
        public @NotNull PackageEntry nextEntry() throws IOException {
            checkClosed();
            checkBudget();
            if (currentEntry == null) {
                throw new IllegalStateException("No current entry - call hasNext() first");
            }
            String name = io.spicelabs.annatto.internal.PathValidator.validateEntryName(currentEntry.getName());
            long size = currentEntry.getSize();
            return new PackageEntry(
                name,
                size,
                currentEntry.isDirectory(),
                false,
                Optional.empty()
            );
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            checkClosed();
            checkBudget();
            if (currentEntry == null) {
                throw new IllegalStateException("No current entry");
            }

            long size = currentEntry.getSize();
            if (size >= 0 && size > limits.entryBytes()) {
                throw new AnnattoException.SecurityException(
                    "Entry exceeds size limit: " + currentEntry.getName() +
                    " (" + size + " > " + limits.entryBytes() + ")");
            }

            InputStream in = zf.getInputStream(currentEntry);
            return EntryContentStream.withPassBudget(in, currentEntry.getName(),
                    currentEntry.getSize(), limits.entryBytes(), passInflated,
                    limits.zipPassBytes(), () -> budgetExceeded.set(true));
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                try {
                    zf.close();
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

        private void checkBudget() throws java.io.IOException {
            if (budgetExceeded.get()) {
                throw new AnnattoException.SecurityException(
                    "ZIP entry-stream inflated data exceeds per-pass limit: " + filename);
            }
        }
    }

    /**
     * Sdist entry stream: fresh gzip chain per pass with a per-pass decompressed budget.
     */
    private class SdistEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        SdistEntryStream() throws IOException {
            this.tarIn = Archives.gzipTar(source.path(), filename, limits.streamPassBytes(),
                    () -> budgetExceeded.set(true));
        }

        @Override
        public boolean hasNext() throws IOException {
            checkClosed();
            checkBudget();
            currentEntry = tarIn.getNextEntry();
            if (currentEntry != null) {
                entryCount++;
                if (entryCount > limits.maxEntries()) {
                    throw new AnnattoException.SecurityException(
                        "Package exceeds maximum entry count: " + limits.maxEntries());
                }
            }
            return currentEntry != null;
        }

        @Override
        public @NotNull PackageEntry nextEntry() throws IOException {
            checkClosed();
            checkBudget();
            if (currentEntry == null) {
                throw new IllegalStateException("No current entry - call hasNext() first");
            }
            String name = io.spicelabs.annatto.internal.PathValidator.validateEntryName(currentEntry.getName());
            long size = currentEntry.getSize();
            boolean isSymbolic = currentEntry.isSymbolicLink();
            boolean hasLinkTarget = isSymbolic || currentEntry.isLink();
            return new PackageEntry(
                name,
                size,
                currentEntry.isDirectory(),
                isSymbolic,
                hasLinkTarget ? Optional.ofNullable(currentEntry.getLinkName()) : Optional.empty()
            );
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            checkClosed();
            checkBudget();
            if (currentEntry == null) {
                throw new IllegalStateException("No current entry");
            }
            long size = currentEntry.getSize();
            if (size >= 0 && size > limits.entryBytes()) {
                throw new AnnattoException.SecurityException(
                    "Entry exceeds size limit: " + currentEntry.getName() +
                    " (" + size + " > " + limits.entryBytes() + ")");
            }
            long declaredSize = currentEntry.isSparse() ? -1 : currentEntry.getSize();
            return EntryContentStream.bounded(tarIn, currentEntry.getName(), declaredSize,
                    limits.entryBytes());
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

        private void checkBudget() throws java.io.IOException {
            if (budgetExceeded.get()) {
                throw new AnnattoException.SecurityException(
                    "Entry-stream decompressed data exceeds per-pass limit: " + filename);
            }
        }
    }
}