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

package io.spicelabs.annatto.ecosystem.cocoapods;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.google.gson.*;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.internal.Archives;
import io.spicelabs.annatto.internal.EntryContentStream;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.PackageSource;
import io.spicelabs.annatto.internal.PathValidator;
import io.spicelabs.annatto.internal.Spool;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map.Entry;

/**
 * A CocoaPods package (.podspec.json file).
 *
 * <p>Implements LanguagePackage for CocoaPods podspec files, providing metadata extraction
 * and (for ZIP archives) entry streaming.
 */
public final class CocoapodsPackage implements LanguagePackage {

    private static final String MIME_TYPE_JSON = "application/json";
    private static final String MIME_TYPE_ZIP = "application/zip";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_PODSPEC_SIZE = 10 * 1024 * 1024; // 10MB

    private final String filename;
    private final PackageMetadata metadata;
    private final PackageSource source;
    private final Limits limits;
    private final boolean isZipFormat;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Create a CocoapodsPackage from a file path.
     *
     * @param path the .podspec.json file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CocoapodsPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromSource(new PackageSource.PathSource(path), basename(path), Limits.DEFAULT);
    }

    /**
     * Create a CocoapodsPackage from an input stream.
     *
     * @param stream the input stream
     * @param filename for format detection and error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CocoapodsPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromStream(stream, filename, Limits.DEFAULT);
    }

    /**
     * Create a CocoapodsPackage from an input stream with explicit resource limits.
     *
     * @param stream the package stream
     * @param filename for error reporting
     * @param limits resource limits (spool/scan/stream-pass/entry bounds)
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     * @throws AnnattoException.SecurityException if a resource limit is exceeded
     */
    public static CocoapodsPackage fromStream(InputStream stream, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Spool.Spooled spooled = Spool.create(stream, basename(filename), limits.spoolBytes());
        return adoptSpool(spooled, filename, limits);
    }

    public static CocoapodsPackage adoptSpool(Spool.Spooled spooled, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        PackageSource.SpooledSource src = new PackageSource.SpooledSource(
                spooled.path(),
                Spool.registration(spooled.path(), spooled.chargedBytes(), new AtomicBoolean(false)));
        return fromSource(src, basename(filename), limits);
    }

    private static CocoapodsPackage fromSource(PackageSource source, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        boolean isZip = filename.toLowerCase(java.util.Locale.ROOT).endsWith(".zip");
        String podspecJson;
        try {
            podspecJson = isZip
                ? extractPodspecFromZip(source.path(), filename, limits)
                : readFileBounded(source.path(), filename, Math.min(limits.entryBytes(), MAX_PODSPEC_SIZE));
        } catch (Exception e) {
            source.releaseResources();
            throw e;
        }
        PackageMetadata metadata = parseMetadata(podspecJson);
        return new CocoapodsPackage(filename, source, metadata, isZip, limits);
    }

    private CocoapodsPackage(String filename, PackageSource source, PackageMetadata metadata, boolean isZip, Limits limits) {
        this.filename = filename;
        this.metadata = metadata;
        this.source = source;
        this.isZipFormat = isZip;
        this.limits = limits;
    }

    @Override
    public @NotNull String mimeType() {
        return isZipFormat ? MIME_TYPE_ZIP : MIME_TYPE_JSON;
    }

    @Override
    public @NotNull Ecosystem ecosystem() {
        return Ecosystem.COCOAPODS;
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

        try {
            return Optional.of(new PackageURL("cocoapods", null, name, version, null, null));
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
            if (isZipFormat) {
                return new CocoapodsZipEntryStream();
            } else {
                // For JSON files, return a single-entry stream with the JSON itself
                return new SingleEntryStream();
            }
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

    private static String extractPodspecFromZip(Path file, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        try (ZipFile zf = Archives.zipFile(file)) {
            java.util.Enumeration<ZipArchiveEntry> entries = zf.getEntries();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > limits.maxEntries()) {
                    throw new AnnattoException.SecurityException(
                        "Archive exceeds metadata scan entry count limit: " + filename);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getName().endsWith(".podspec.json")) {
                    return readStreamToString(zf.getInputStream(entry), filename,
                            Math.min(limits.entryBytes(), MAX_PODSPEC_SIZE));
                }
            }
            throw new AnnattoException.MalformedPackageException("No .podspec.json found in: " + filename);
        }
    }

    private static String readStreamToString(InputStream stream, String filename, long cap) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(cap, 8192));
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

    private static String readFileBounded(Path file, String filename, long cap) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 8192)) {
            return readStreamToString(in, filename, cap);
        }
    }

private static PackageMetadata parseMetadata(String podspecJson)
            throws AnnattoException.MalformedPackageException {
        JsonObject json;
        try {
            // Depth guard (catalog §8 analog): GSON recursion is unbounded.
            io.spicelabs.annatto.internal.JsonSecurity.checkDepth(podspecJson);
            JsonElement element = JsonParser.parseString(podspecJson);
            if (!element.isJsonObject()) {
                throw new AnnattoException.MalformedPackageException("podspec.json is not a JSON object");
            }
            json = element.getAsJsonObject();
        } catch (Exception e) {
            throw new AnnattoException.MalformedPackageException("Failed to parse podspec.json: " + e.getMessage(), e);
        }

        String name = getString(json, "name");
        String version = getString(json, "version");

        if (name == null || name.isEmpty()) {
            throw new AnnattoException.MalformedPackageException("No name in podspec.json");
        }

        Optional<String> description = Optional.ofNullable(getString(json, "summary"));
        Optional<String> license = extractLicense(json);

        List<Dependency> dependencies = parseDependencies(json);

        Map<String, Object> raw = new HashMap<>();
        raw.put("homepage", getString(json, "homepage"));
        raw.put("source", json.get("source"));

        return new PackageMetadata(
                name,
                version != null ? version : "",
                description,
                license,
                extractPublisher(json),
                Optional.empty(),
                dependencies,
                raw
        );
    }

    private static String getString(JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            String value = json.get(key).getAsString().trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private static Optional<String> extractPublisher(JsonObject json) {
        if (json.has("authors") && json.get("authors").isJsonObject()) {
            JsonObject authors = json.get("authors").getAsJsonObject();
            if (!authors.entrySet().isEmpty()) {
                Entry<String, JsonElement> first = authors.entrySet().iterator().next();
                return Optional.of(first.getKey());
            }
        }
        if (json.has("authors") && json.get("authors").isJsonArray()) {
            for (JsonElement author : json.get("authors").getAsJsonArray()) {
                if (author.isJsonPrimitive()) {
                    return Optional.of(author.getAsString());
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractLicense(JsonObject json) {
        if (json.has("license")) {
            JsonElement license = json.get("license");
            if (license.isJsonPrimitive()) {
                return Optional.of(license.getAsString());
            } else if (license.isJsonObject() && license.getAsJsonObject().has("type")) {
                return Optional.of(license.getAsJsonObject().get("type").getAsString());
            }
        }
        return Optional.empty();
    }

    private static List<Dependency> parseDependencies(JsonObject json) {
        List<Dependency> deps = new ArrayList<>();

        // dependencies section
        if (json.has("dependencies") && json.get("dependencies").isJsonArray()) {
            for (JsonElement dep : json.get("dependencies").getAsJsonArray()) {
                if (dep.isJsonArray() && dep.getAsJsonArray().size() >= 1) {
                    JsonArray arr = dep.getAsJsonArray();
                    String name = arr.get(0).getAsString();
                    String version = arr.size() > 1 ? arr.get(1).getAsString() : "";
                    deps.add(new Dependency(name, Optional.of("runtime"), version));
                }
            }
        }

        return deps;
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

/**
     * Single entry stream for JSON files (returns the JSON itself as the only entry).
     */
    private class SingleEntryStream implements PackageEntryStream {
        private boolean returned = false;
        private boolean closed = false;

        @Override
        public boolean hasNext() {
            checkClosed();
            return !returned;
        }

        @Override
        public @NotNull PackageEntry nextEntry() throws IOException {
            checkClosed();
            if (returned) {
                throw new IllegalStateException("No current entry - call hasNext() first");
            }
            returned = true;
            return new PackageEntry(filename, Files.size(source.path()), false, false, Optional.empty());
        }

        @Override
        public @NotNull InputStream openStream() throws IOException {
            checkClosed();
            if (!returned) {
                throw new IllegalStateException("No current entry");
            }
            InputStream in = new BufferedInputStream(Files.newInputStream(source.path()), 8192);
            return EntryContentStream.bounded(in, filename, Files.size(source.path()),
                    limits.entryBytes());
        }

        @Override
        public void close() {
            closed = true;
            streamOpen.set(false);
        }

        private void checkClosed() {
            if (closed) {
                throw new IllegalStateException("Stream is closed");
            }
        }
    }

    private class CocoapodsZipEntryStream implements PackageEntryStream {
        private final ZipFile zf;
        private final java.util.Enumeration<ZipArchiveEntry> entries;
        private final java.util.concurrent.atomic.AtomicLong passInflated = new java.util.concurrent.atomic.AtomicLong();
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CocoapodsZipEntryStream() throws IOException {
            this.zf = Archives.zipFile(source.path());
            this.entries = zf.getEntries();
        }

        @Override
        public boolean hasNext() throws IOException {
            checkClosed();
            checkBudget();
            currentEntry = entries.hasMoreElements() ? entries.nextElement() : null;
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
            String name = PathValidator.validateEntryName(currentEntry.getName());
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
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            try (InputStream in = zf.getInputStream(currentEntry)) {
                while ((read = in.read(buffer)) != -1) {
                    totalRead += read;
                    if (totalRead > limits.entryBytes()) {
                        throw new AnnattoException.SecurityException(
                            "Entry exceeds size limit during read");
                    }
                    if (passInflated.addAndGet(read) > limits.zipPassBytes()) {
                        budgetExceeded.set(true);
                        throw new AnnattoException.SecurityException(
                            "ZIP entry-stream inflated data exceeds per-pass limit: " + filename);
                    }
                    baos.write(buffer, 0, read);
                }
            }
            return new ByteArrayInputStream(baos.toByteArray());
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

        private void checkBudget() {
            if (budgetExceeded.get()) {
                throw new AnnattoException.SecurityException(
                    "ZIP entry-stream inflated data exceeds per-pass limit: " + filename);
            }
        }
    }
}