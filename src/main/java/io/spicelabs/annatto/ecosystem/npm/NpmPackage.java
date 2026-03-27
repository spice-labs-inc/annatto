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

package io.spicelabs.annatto.ecosystem.npm;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.internal.BoundedInputStream;
import io.spicelabs.annatto.internal.PathValidator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * An npm package (.tgz archive).
 *
 * <p>Implements LanguagePackage for npm packages, providing metadata extraction
 * and entry streaming.
 */
public final class NpmPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/gzip";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;

    private final Path sourcePath;
    private final PackageMetadata metadata;
    private final com.google.gson.JsonObject packageJson;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create an NpmPackage from a file path.
     *
     * @param path the .tgz file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static NpmPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create an NpmPackage from an input stream.
     *
     * @param stream the .tgz stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static NpmPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        // Buffer the stream for multiple reads
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        // Extract package.json
        com.google.gson.JsonObject packageJson = extractPackageJson(
            new ByteArrayInputStream(data), filename);

        PackageMetadata metadata = parseMetadata(packageJson);

        return new NpmPackage(filename, metadata, packageJson, data);
    }

    private final String filename;
    private final byte[] data;

    private NpmPackage(String filename, PackageMetadata metadata,
                       com.google.gson.JsonObject packageJson, byte[] data) {
        this.filename = filename;
        this.metadata = metadata;
        this.packageJson = packageJson;
        this.data = data;
        this.sourcePath = new File(filename).toPath();
    }

    @Override
    public @NotNull String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public @NotNull Ecosystem ecosystem() {
        return Ecosystem.NPM;
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
            // Handle scoped packages (@scope/name)
            if (name.startsWith("@")) {
                int slashIdx = name.indexOf('/');
                if (slashIdx > 0) {
                    String namespace = name.substring(1, slashIdx);
                    String pkgName = name.substring(slashIdx + 1);
                    return Optional.of(new PackageURL("npm", namespace, pkgName, version, null, null));
                }
            }
            return Optional.of(new PackageURL("npm", null, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull PackageEntryStream streamEntries() throws IOException {
        if (streamOpen.compareAndSet(false, true)) {
            return new NpmEntryStream();
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        // Nothing to close - data is in memory
        streamOpen.set(false);
    }

    /**
     * Maximum size for npm package decompression (500MB safety limit).
     * Prevents zip bomb attacks and memory exhaustion.
     * Note: This is a last-resort limit. Entry-level size limits (10MB per entry)
     * provide finer-grained protection.
     */
    private static final long MAX_DECOMPRESSED_SIZE = 500 * 1024 * 1024;

    private static com.google.gson.JsonObject extractPackageJson(
            InputStream tgzStream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        // Pre-decompress GZIP to byte array to avoid concurrency issues.
        // java.util.zip.GZIPInputStream uses native Inflater which has
        // thread-safety issues when multiple threads decompress simultaneously.
        // Requirement: ThreadSafetyTest.concurrentReadCallsDoNotInterfere
        byte[] tarData = decompressGzip(tgzStream, filename);

        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new ByteArrayInputStream(tarData), StandardCharsets.UTF_8.name())) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                // npm packs files under package/ prefix
                if (isPackageJson(entryName)) {
                    return parseJson(tarIn, (int) entry.getSize());
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No package.json found in npm archive: " + filename);
        }
    }

    /**
     * Decompresses GZIP data to a byte array with size limits.
     *
     * @param gzipStream the GZIP compressed input stream
     * @param filename for error reporting
     * @return the decompressed TAR data
     * @throws IOException if decompression fails
     * @throws AnnattoException.SecurityException if size limit exceeded
     */
    private static byte[] decompressGzip(InputStream gzipStream, String filename)
            throws IOException {
        try (GZIPInputStream gzis = new GZIPInputStream(gzipStream);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = gzis.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > MAX_DECOMPRESSED_SIZE) {
                    throw new AnnattoException.SecurityException(
                        "Decompressed size exceeds limit (" + MAX_DECOMPRESSED_SIZE +
                        " bytes) for: " + filename);
                }
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private static boolean isPackageJson(String entryName) {
        String normalized = entryName.replace('\\', '/');
        if (normalized.equals("package.json")) {
            return true;
        }
        if (normalized.endsWith("/package.json")) {
            String withoutFile = normalized.substring(0, normalized.length() - "/package.json".length());
            return !withoutFile.contains("/");
        }
        return false;
    }

    private static com.google.gson.JsonObject parseJson(InputStream stream, int size)
            throws IOException, AnnattoException.MalformedPackageException {
        byte[] buffer = new byte[size > 0 && size < 10_000_000 ? size : 8192];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int read;
        while ((read = stream.read(buffer)) != -1) {
            baos.write(buffer, 0, read);
        }
        String json = baos.toString(StandardCharsets.UTF_8);
        com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(json);
        if (!element.isJsonObject()) {
            throw new AnnattoException.MalformedPackageException("package.json is not a JSON object");
        }
        return element.getAsJsonObject();
    }

    private static PackageMetadata parseMetadata(com.google.gson.JsonObject packageJson) {
        String name = getString(packageJson, "name").orElse("");
        String version = getString(packageJson, "version").orElse("");
        Optional<String> description = getString(packageJson, "description");
        Optional<String> license = extractLicense(packageJson);
        Optional<String> publisher = extractAuthor(packageJson);

        List<Dependency> dependencies = extractDependencies(packageJson);

        Map<String, Object> raw = new HashMap<>();
        getString(packageJson, "main").ifPresent(v -> raw.put("main", v));
        getString(packageJson, "homepage").ifPresent(v -> raw.put("homepage", v));

        return new PackageMetadata(
            name, version, description, license, publisher,
            Optional.empty(), dependencies, raw
        );
    }

    private static Optional<String> getString(com.google.gson.JsonObject json, String key) {
        if (json.has(key) && json.get(key).isJsonPrimitive()) {
            String value = json.get(key).getAsString().trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }
        return Optional.empty();
    }

    private static Optional<String> extractLicense(com.google.gson.JsonObject packageJson) {
        // Modern SPDX string
        if (packageJson.has("license")) {
            com.google.gson.JsonElement element = packageJson.get("license");
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                String value = element.getAsString().trim();
                if (!value.isEmpty()) {
                    return Optional.of(value);
                }
            }
            if (element.isJsonObject()) {
                return getString(element.getAsJsonObject(), "type");
            }
        }

        // Legacy licenses array
        if (packageJson.has("licenses")) {
            com.google.gson.JsonElement element = packageJson.get("licenses");
            if (element.isJsonArray()) {
                com.google.gson.JsonArray array = element.getAsJsonArray();
                List<String> types = new ArrayList<>();
                for (com.google.gson.JsonElement le : array) {
                    if (le.isJsonObject()) {
                        getString(le.getAsJsonObject(), "type").ifPresent(types::add);
                    }
                }
                if (!types.isEmpty()) {
                    return Optional.of(String.join(" OR ", types));
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<String> extractAuthor(com.google.gson.JsonObject packageJson) {
        if (packageJson.has("author")) {
            com.google.gson.JsonElement element = packageJson.get("author");
            Optional<String> result = extractPersonName(element);
            if (result.isPresent()) {
                return result;
            }
        }

        // Fallback: first maintainer
        if (packageJson.has("maintainers")) {
            com.google.gson.JsonElement element = packageJson.get("maintainers");
            if (element.isJsonArray() && !element.getAsJsonArray().isEmpty()) {
                Optional<String> result = extractPersonName(element.getAsJsonArray().get(0));
                if (result.isPresent()) {
                    return result;
                }
            }
        }

        return Optional.empty();
    }

    private static Optional<String> extractPersonName(com.google.gson.JsonElement element) {
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String str = element.getAsString().trim();
            // Parse "Name <email> (url)" format
            int angleIdx = str.indexOf('<');
            if (angleIdx > 0) {
                str = str.substring(0, angleIdx).trim();
            }
            int parenIdx = str.indexOf('(');
            if (parenIdx > 0) {
                str = str.substring(0, parenIdx).trim();
            }
            return str.isEmpty() ? Optional.empty() : Optional.of(str);
        }
        if (element.isJsonObject()) {
            return getString(element.getAsJsonObject(), "name");
        }
        return Optional.empty();
    }

    private static List<Dependency> extractDependencies(com.google.gson.JsonObject packageJson) {
        List<Dependency> deps = new ArrayList<>();
        extractDeps(packageJson, "dependencies", "runtime", deps);
        extractDeps(packageJson, "devDependencies", "dev", deps);
        extractDeps(packageJson, "peerDependencies", "peer", deps);
        extractDeps(packageJson, "optionalDependencies", "optional", deps);
        return deps;
    }

    private static void extractDeps(com.google.gson.JsonObject json, String field,
                                     String scope, List<Dependency> target) {
        if (!json.has(field)) return;
        com.google.gson.JsonElement element = json.get(field);
        if (!element.isJsonObject()) return;
        com.google.gson.JsonObject obj = element.getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
            String name = e.getKey();
            String version = "";
            if (e.getValue().isJsonPrimitive()) {
                version = e.getValue().getAsString();
            }
            target.add(new Dependency(name, Optional.of(scope), version));
        }
    }

    /**
     * Entry stream implementation for npm packages.
     */
    private class NpmEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        NpmEntryStream() throws IOException {
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
