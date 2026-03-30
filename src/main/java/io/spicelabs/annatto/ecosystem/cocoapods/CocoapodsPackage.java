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
import io.spicelabs.annatto.internal.PathValidator;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
    private final byte[] data;
    private final boolean isZipFormat;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a CocoapodsPackage from a file path.
     *
     * @param path the .podspec.json file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CocoapodsPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        boolean isZip = filename.toLowerCase().endsWith(".zip");
        String podspecJson = isZip
                ? extractPodspecFromZip(data, filename)
                : new String(data, StandardCharsets.UTF_8);

        PackageMetadata metadata = parseMetadata(podspecJson);
        return new CocoapodsPackage(filename, metadata, data, isZip);
    }

    private CocoapodsPackage(String filename, PackageMetadata metadata, byte[] data, boolean isZip) {
        this.filename = filename;
        this.metadata = metadata;
        this.data = data;
        this.isZipFormat = isZip;
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
        if (streamOpen.compareAndSet(false, true)) {
            if (isZipFormat) {
                return new CocoapodsZipEntryStream();
            } else {
                // For JSON files, return a single-entry stream with the JSON itself
                return new SingleEntryStream();
            }
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        streamOpen.set(false);
    }

    private static String extractPodspecFromZip(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (ZipArchiveInputStream zipIn = new ZipArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name(), true, true)) {

            ZipArchiveEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.endsWith(".podspec.json")) {
                    if (entry.getSize() > MAX_PODSPEC_SIZE) {
                        throw new AnnattoException.SecurityException("podspec.json exceeds size limit");
                    }
                    return readStreamToString(zipIn, entry.getSize());
                }
            }
            throw new AnnattoException.MalformedPackageException("No .podspec.json found in: " + filename);
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException("Failed to read package: " + e.getMessage(), e);
        }
    }

    private static String readStreamToString(InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                size > 0 ? (int) Math.min(size, MAX_PODSPEC_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_PODSPEC_SIZE) {
                throw new IOException("Content exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static PackageMetadata parseMetadata(String podspecJson)
            throws AnnattoException.MalformedPackageException {
        JsonObject json;
        try {
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
        public @NotNull PackageEntry nextEntry() {
            checkClosed();
            if (returned) {
                throw new IllegalStateException("No current entry - call hasNext() first");
            }
            returned = true;
            return new PackageEntry(filename, data.length, false, false, Optional.empty());
        }

        @Override
        public @NotNull InputStream openStream() {
            checkClosed();
            if (!returned) {
                throw new IllegalStateException("No current entry");
            }
            return new ByteArrayInputStream(data);
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

    /**
     * Entry stream for ZIP format.
     */
    private class CocoapodsZipEntryStream implements PackageEntryStream {
        private final ZipArchiveInputStream zipIn;
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CocoapodsZipEntryStream() throws IOException {
            this.zipIn = new ZipArchiveInputStream(
                    new ByteArrayInputStream(data), StandardCharsets.UTF_8.name(), true, true);
        }

        @Override
        public boolean hasNext() throws IOException {
            checkClosed();
            if (entryCount >= MAX_ENTRIES) {
                throw new AnnattoException.SecurityException("Package exceeds maximum entry count: " + MAX_ENTRIES);
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
                    false,
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
                throw new AnnattoException.SecurityException("Entry exceeds size limit: " + currentEntry.getName() +
                        " (" + size + " > " + MAX_ENTRY_SIZE + ")");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = zipIn.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > MAX_ENTRY_SIZE) {
                    throw new AnnattoException.SecurityException("Entry exceeds size limit during read");
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
}
