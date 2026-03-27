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

package io.spicelabs.annatto.ecosystem.conda;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.internal.PathValidator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * A Conda package (.conda or .tar.bz2 archive).
 *
 * <p>Implements LanguagePackage for Conda packages, supporting both:
 * <ul>
 *   <li>Conda v1: .tar.bz2 format</li>
 *   <li>Conda v2: .conda format (ZIP containing .tar.zst files)</li>
 * </ul>
 */
public final class CondaPackage implements LanguagePackage {

    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_INDEX_JSON_SIZE = 1024 * 1024; // 1MB

    private final String filename;
    private final PackageMetadata metadata;
    private final byte[] data;
    private final boolean isV2Format;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a CondaPackage from a file path.
     *
     * @param path the .conda or .tar.bz2 file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CondaPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create a CondaPackage from an input stream.
     *
     * @param stream the input stream
     * @param filename for format detection and error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CondaPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        boolean isV2 = filename.toLowerCase().endsWith(".conda");
        Map<String, Object> indexInfo = extractIndexInfo(data, filename, isV2);
        PackageMetadata metadata = parseMetadata(indexInfo);

        return new CondaPackage(filename, metadata, data, isV2);
    }

    private CondaPackage(String filename, PackageMetadata metadata, byte[] data, boolean isV2) {
        this.filename = filename;
        this.metadata = metadata;
        this.data = data;
        this.isV2Format = isV2;
    }

    @Override
    public @NotNull String mimeType() {
        return isV2Format ? "application/zip" : "application/x-bzip2";
    }

    @Override
    public @NotNull Ecosystem ecosystem() {
        return Ecosystem.CONDA;
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
            return Optional.of(new PackageURL("conda", null, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull PackageEntryStream streamEntries() throws IOException {
        if (streamOpen.compareAndSet(false, true)) {
            return isV2Format ? new CondaV2EntryStream() : new CondaV1EntryStream();
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        streamOpen.set(false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractIndexInfo(byte[] data, String filename, boolean isV2)
            throws AnnattoException.MalformedPackageException {
        if (isV2) {
            return extractIndexFromV2(data, filename);
        } else {
            return extractIndexFromV1(data, filename);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractIndexFromV2(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (ZipArchiveInputStream zipIn = new ZipArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name(), true, true)) {

            ZipArchiveEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                String entryName = entry.getName();
                // Conda v2 format has info-{name}-{version}-{build}.tar.zst containing info/index.json
                if (entryName.startsWith("info-") && entryName.endsWith(".tar.zst")) {
                    return extractIndexFromZstdTar(zipIn, filename);
                }
            }
            throw new AnnattoException.MalformedPackageException("No info-*.tar.zst found in conda v2 package: " + filename);
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException("Failed to read conda v2 package: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractIndexFromZstdTar(InputStream zstdStream, String filename)
            throws AnnattoException.MalformedPackageException, IOException {
        // Decompress zstd and read the tar contents
        try (ZstdDecompressorInputStream zstdIn = new ZstdDecompressorInputStream(zstdStream);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(zstdIn, StandardCharsets.UTF_8.name())) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.getName().equals("info/index.json")) {
                    if (entry.getSize() > MAX_INDEX_JSON_SIZE) {
                        throw new AnnattoException.SecurityException("info/index.json exceeds size limit");
                    }
                    String json = readStreamToString(tarIn, entry.getSize());
                    return parseJsonToMap(json);
                }
            }
            throw new AnnattoException.MalformedPackageException("No info/index.json found in info-*.tar.zst for: " + filename);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractIndexFromV1(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (BZip2InputStream bzIn = new BZip2InputStream(new ByteArrayInputStream(data));
             TarArchiveInputStream tarIn = new TarArchiveInputStream(bzIn, StandardCharsets.UTF_8.name())) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.getName().equals("info/index.json")) {
                    if (entry.getSize() > MAX_INDEX_JSON_SIZE) {
                        throw new AnnattoException.SecurityException("info/index.json exceeds size limit");
                    }
                    String json = readStreamToString(tarIn, entry.getSize());
                    return parseJsonToMap(json);
                }
            }
            throw new AnnattoException.MalformedPackageException("No info/index.json found in conda v1 package: " + filename);
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException("Failed to read conda v1 package: " + e.getMessage(), e);
        }
    }

    private static String readStreamToString(InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                size > 0 ? (int) Math.min(size, MAX_INDEX_JSON_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_INDEX_JSON_SIZE) {
                throw new IOException("Content exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonToMap(String json) {
        // Simple JSON parser for index.json
        Map<String, Object> result = new HashMap<>();
        // Extract key fields using regex for simplicity
        result.put("name", extractJsonString(json, "name"));
        result.put("version", extractJsonString(json, "version"));
        result.put("license", extractJsonString(json, "license"));
        result.put("summary", extractJsonString(json, "summary"));
        result.put("subdir", extractJsonString(json, "subdir"));
        return result;
    }

    @Nullable
    private static String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static PackageMetadata parseMetadata(Map<String, Object> indexInfo) {
        String name = getString(indexInfo, "name");
        String version = getString(indexInfo, "version");

        Optional<String> description = Optional.ofNullable(getString(indexInfo, "summary"));
        Optional<String> license = Optional.ofNullable(getString(indexInfo, "license"));

        Map<String, Object> raw = new HashMap<>();
        raw.put("subdir", indexInfo.get("subdir"));

        return new PackageMetadata(
                name != null ? name : "",
                version != null ? version : "",
                description,
                license,
                Optional.empty(),
                Optional.empty(),
                List.of(), // Conda dependencies not extracted here
                raw
        );
    }

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof String) {
            return (String) value;
        }
        return null;
    }

    /**
     * Entry stream for Conda v1 format (.tar.bz2).
     */
    private class CondaV1EntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private final BZip2InputStream bzIn;
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CondaV1EntryStream() throws IOException {
            this.bzIn = new BZip2InputStream(new ByteArrayInputStream(data));
            this.tarIn = new TarArchiveInputStream(bzIn, StandardCharsets.UTF_8.name());
        }

        @Override
        public boolean hasNext() throws IOException {
            checkClosed();
            if (entryCount >= MAX_ENTRIES) {
                throw new AnnattoException.SecurityException("Package exceeds maximum entry count: " + MAX_ENTRIES);
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
                throw new AnnattoException.SecurityException("Entry exceeds size limit: " + currentEntry.getName() +
                        " (" + size + " > " + MAX_ENTRY_SIZE + ")");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = tarIn.read(buffer)) != -1) {
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

    /**
     * Entry stream for Conda v2 format (.conda - ZIP containing tar.zst).
     * For now, returns empty as full zstd support would need additional implementation.
     */
    private class CondaV2EntryStream implements PackageEntryStream {
        private final ZipArchiveInputStream zipIn;
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CondaV2EntryStream() throws IOException {
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

    /**
     * BZip2 input stream wrapper.
     */
    private static class BZip2InputStream extends InputStream {
        private final org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream bzIn;

        BZip2InputStream(InputStream in) throws IOException {
            this.bzIn = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(in);
        }

        @Override
        public int read() throws IOException {
            return bzIn.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return bzIn.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            bzIn.close();
        }
    }

    /**
     * Zstd decompressor input stream using aircompressor (pure Java).
     */
    private static class ZstdDecompressorInputStream extends InputStream {
        private final io.airlift.compress.zstd.ZstdInputStream zstdIn;

        ZstdDecompressorInputStream(InputStream in) throws IOException {
            this.zstdIn = new io.airlift.compress.zstd.ZstdInputStream(in);
        }

        @Override
        public int read() throws IOException {
            return zstdIn.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return zstdIn.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            zstdIn.close();
        }
    }
}
