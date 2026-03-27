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

package io.spicelabs.annatto.ecosystem.packagist;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
 * A Packagist (PHP) package (.zip archive).
 *
 * <p>Implements LanguagePackage for PHP/Composer packages, providing metadata extraction
 * from composer.json and entry streaming.
 */
public final class PackagistPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/zip";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_COMPOSER_JSON_SIZE = 10 * 1024 * 1024; // 10MB

    private final String filename;
    private final PackageMetadata metadata;
    private final byte[] data;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a PackagistPackage from a file path.
     *
     * @param path the .zip file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static PackagistPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create a PackagistPackage from an input stream.
     *
     * @param stream the .zip stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static PackagistPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        String composerJson = extractComposerJson(data, filename);
        PackageMetadata metadata = parseMetadata(composerJson, filename);

        return new PackagistPackage(filename, metadata, data);
    }

    private PackagistPackage(String filename, PackageMetadata metadata, byte[] data) {
        this.filename = filename;
        this.metadata = metadata;
        this.data = data;
    }

    @Override
    public @NotNull String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public @NotNull Ecosystem ecosystem() {
        return Ecosystem.PACKAGIST;
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
            // Packagist names are vendor/package
            int slashIdx = name.indexOf('/');
            if (slashIdx > 0) {
                String namespace = name.substring(0, slashIdx);
                String pkgName = name.substring(slashIdx + 1);
                return Optional.of(new PackageURL("composer", namespace, pkgName, version, null, null));
            }
            return Optional.of(new PackageURL("composer", null, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull PackageEntryStream streamEntries() throws IOException {
        if (streamOpen.compareAndSet(false, true)) {
            return new PackagistEntryStream();
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        streamOpen.set(false);
    }

    private static String extractComposerJson(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (ZipArchiveInputStream zipIn = new ZipArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name(), true, true)) {

            ZipArchiveEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (isComposerJson(entryName)) {
                    if (entry.getSize() > MAX_COMPOSER_JSON_SIZE) {
                        throw new AnnattoException.SecurityException(
                            "composer.json exceeds size limit");
                    }
                    return readStreamToString(zipIn, entry.getSize());
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No composer.json found in package: " + filename);
        } catch (AnnattoException.MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException(
                "Failed to read package: " + e.getMessage(), e);
        }
    }

    private static boolean isComposerJson(String entryName) {
        return entryName.equals("composer.json") || entryName.endsWith("/composer.json");
    }

    private static String readStreamToString(InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
            size > 0 ? (int) Math.min(size, MAX_COMPOSER_JSON_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_COMPOSER_JSON_SIZE) {
                throw new IOException("composer.json content exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static PackageMetadata parseMetadata(String composerJson, String filename)
            throws AnnattoException.MalformedPackageException {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(composerJson);
            if (!element.isJsonObject()) {
                throw new AnnattoException.MalformedPackageException("composer.json is not a JSON object");
            }
            json = element.getAsJsonObject();
        } catch (Exception e) {
            throw new AnnattoException.MalformedPackageException("Failed to parse composer.json: " + e.getMessage(), e);
        }

        String name = getString(json, "name");
        String version = getString(json, "version");

        // If version not in composer.json, extract from filename
        // Filename format: symfony-console-v6.4.2.zip or package-name-1.0.0.zip
        if (version == null || version.isEmpty()) {
            version = extractVersionFromFilename(filename);
        }

        Optional<String> description = Optional.ofNullable(getString(json, "description"));
        Optional<String> license = extractLicense(json);

        List<Dependency> dependencies = parseDependencies(json);

        Map<String, Object> raw = new HashMap<>();
        raw.put("type", getString(json, "type"));
        raw.put("homepage", getString(json, "homepage"));

        return new PackageMetadata(
            name != null ? name : "",
            version,
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
        if (json.has("authors") && json.get("authors").isJsonArray()) {
            for (JsonElement author : json.get("authors").getAsJsonArray()) {
                if (author.isJsonObject()) {
                    String name = getString(author.getAsJsonObject(), "name");
                    if (name != null && !name.isEmpty()) {
                        return Optional.of(name);
                    }
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
            } else if (license.isJsonArray() && !license.getAsJsonArray().isEmpty()) {
                JsonElement first = license.getAsJsonArray().get(0);
                if (first.isJsonPrimitive()) {
                    return Optional.of(first.getAsString());
                }
            }
        }
        return Optional.empty();
    }

    private static List<Dependency> parseDependencies(JsonObject json) {
        List<Dependency> deps = new ArrayList<>();

        // require section (runtime dependencies)
        if (json.has("require") && json.get("require").isJsonObject()) {
            JsonObject require = json.get("require").getAsJsonObject();
            for (Entry<String, JsonElement> entry : require.entrySet()) {
                String name = entry.getKey();
                // Skip PHP version requirements
                if (name.equals("php") || name.startsWith("ext-")) {
                    continue;
                }
                String version = entry.getValue().isJsonPrimitive()
                    ? entry.getValue().getAsString()
                    : "";
                deps.add(new Dependency(name, Optional.of("runtime"), version));
            }
        }

        // require-dev section (dev dependencies)
        if (json.has("require-dev") && json.get("require-dev").isJsonObject()) {
            JsonObject requireDev = json.get("require-dev").getAsJsonObject();
            for (Entry<String, JsonElement> entry : requireDev.entrySet()) {
                String name = entry.getKey();
                String version = entry.getValue().isJsonPrimitive()
                    ? entry.getValue().getAsString()
                    : "";
                deps.add(new Dependency(name, Optional.of("dev"), version));
            }
        }

        return deps;
    }

    private static String extractVersionFromFilename(String filename) {
        // Extract version from filename like "symfony-console-v6.4.2.zip"
        // Pattern: look for -vX.Y.Z or -X.Y.Z before .zip
        if (filename == null || filename.isEmpty()) {
            return "";
        }
        // Remove .zip extension
        String nameWithoutExt = filename;
        if (filename.endsWith(".zip")) {
            nameWithoutExt = filename.substring(0, filename.length() - 4);
        }
        // Try to find version pattern: -v followed by digits, or - followed by version
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("-v?([0-9]+\\.[0-9]+.*)");
        java.util.regex.Matcher matcher = pattern.matcher(nameWithoutExt);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    /**
     * Entry stream implementation for Packagist packages.
     */
    private class PackagistEntryStream implements PackageEntryStream {
        private final ZipArchiveInputStream zipIn;
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        PackagistEntryStream() throws IOException {
            this.zipIn = new ZipArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name(), true, true);
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
                throw new AnnattoException.SecurityException(
                    "Entry exceeds size limit: " + currentEntry.getName() +
                    " (" + size + " > " + MAX_ENTRY_SIZE + ")");
            }

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
}
