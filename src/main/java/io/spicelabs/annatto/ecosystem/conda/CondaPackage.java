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
import io.spicelabs.annatto.internal.Archives;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.PackageSource;
import io.spicelabs.annatto.internal.Spool;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Conda package (.conda or .tar.bz2 archive).
 *
 * <p>Phase 7 (Bug 2): never read whole into memory.
 * <ul>
 *   <li>v1 (.tar.bz2): bounded bzip2 streaming scan for {@code info/index.json}.</li>
 *   <li>v2 (.conda): random-access {@link ZipFile}; the {@code info-*.tar.zst} entry is
 *       decompressed through a bounded zstd budget and scanned for {@code info/index.json}.</li>
 * </ul>
 * Entry streams open fresh per pass with per-pass budgets; error messages never splice raw
 * cause messages.
 */
public final class CondaPackage implements LanguagePackage {

    private static final int MAX_INDEX_JSON_SIZE = 1024 * 1024; // 1MB

    private final String filename;
    private final PackageMetadata metadata;
    private final PackageSource source;
    private final boolean isV2Format;
    private final Limits limits;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Create a CondaPackage from a file path (direct read).
     *
     * @param path the package file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CondaPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromSource(new PackageSource.PathSource(path), basename(path), Limits.DEFAULT);
    }

    /**
     * Create a CondaPackage from an input stream (bounded spool).
     *
     * @param stream the input stream
     * @param filename for format detection and error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CondaPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromStream(stream, filename, Limits.DEFAULT);
    }

    /**
     * Create a CondaPackage from an input stream with explicit resource limits.
     *
     * @param stream the input stream
     * @param filename for format detection and error reporting
     * @param limits resource limits (spool/scan/stream-pass/entry bounds)
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     * @throws AnnattoException.SecurityException if a resource limit is exceeded
     */
    public static CondaPackage fromStream(InputStream stream, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Spool.Spooled spooled = Spool.create(stream, basename(filename), limits.spoolBytes());
        return adoptSpool(spooled, filename, limits);
    }

    /**
     * Adopt an owned spooled file (reader stream handoff); {@code close()} deletes it (S-5).
     */
    public static CondaPackage adoptSpool(Spool.Spooled spooled, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        PackageSource.SpooledSource src = new PackageSource.SpooledSource(
                spooled.path(),
                Spool.registration(spooled.path(), spooled.chargedBytes(), new AtomicBoolean(false)));
        return fromSource(src, basename(filename), limits);
    }

    private static CondaPackage fromSource(PackageSource source, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        boolean isV2 = filename.toLowerCase(Locale.ROOT).endsWith(".conda");
        Map<String, Object> indexInfo;
        try {
            indexInfo = isV2
                ? extractIndexFromV2(source.path(), filename, limits)
                : extractIndexFromV1(source.path(), filename, limits);
        } catch (Exception e) {
            source.releaseResources();
            throw e;
        }
        PackageMetadata metadata = parseMetadata(indexInfo);
        return new CondaPackage(filename, source, metadata, isV2, limits);
    }

    private CondaPackage(String filename, PackageSource source, PackageMetadata metadata,
                         boolean isV2Format, Limits limits) {
        this.filename = filename;
        this.source = source;
        this.metadata = metadata;
        this.isV2Format = isV2Format;
        this.limits = limits;
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
        if (closed) {
            throw new IllegalStateException("Package is closed");
        }
        if (!streamOpen.compareAndSet(false, true)) {
            throw new IllegalStateException("A stream is already open on this package");
        }
        try {
            return isV2Format ? new CondaV2EntryStream() : new CondaV1EntryStream();
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
    // Metadata extraction (streaming / random-access)
    // ================================================================

    /** v2: ZIP containing {@code info-*.tar.zst}; zstd decompression is bounded. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractIndexFromV2(Path file, String filename, Limits limits)
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
                String entryName = entry.getName();
                if (entryName.startsWith("info-") && entryName.endsWith(".tar.zst")) {
                    try (InputStream zstStream = zf.getInputStream(entry);
                         TarArchiveInputStream tarIn =
                             Archives.zstdTar(zstStream, filename, limits.scanBytes(), () -> { })) {
                        TarArchiveEntry tarEntry;
                        int innerCount = 0;
                        while ((tarEntry = tarIn.getNextEntry()) != null) {
                            if (++innerCount > limits.maxEntries()) {
                                throw new AnnattoException.SecurityException(
                                    "Archive exceeds metadata scan entry count limit: " + filename);
                            }
                            if (tarEntry.getName().equals("info/index.json")) {
                                String json = readBounded(tarIn, Math.min(MAX_INDEX_JSON_SIZE, limits.entryBytes()), filename);
                                return parseJsonToMap(json);
                            }
                        }
                    }
                    throw new AnnattoException.MalformedPackageException(
                        "No info/index.json found in info-*.tar.zst for: " + filename);
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No info-*.tar.zst found in conda v2 package: " + filename);
        }
    }

    /** v1: bounded bzip2 streaming scan for {@code info/index.json}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractIndexFromV1(Path file, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        try (TarArchiveInputStream tarIn = Archives.bzip2Tar(file, filename, limits.scanBytes(), () -> { })) {
            int count = 0;
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (++count > limits.maxEntries()) {
                    throw new AnnattoException.SecurityException(
                        "Archive exceeds metadata scan entry count limit: " + filename);
                }
                if (entry.getName().equals("info/index.json")) {
                    String json = readBounded(tarIn, Math.min(MAX_INDEX_JSON_SIZE, limits.entryBytes()), filename);
                    return parseJsonToMap(json);
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No info/index.json found in conda v1 package: " + filename);
        }
    }

    private static String readBounded(InputStream stream, long cap, String filename) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) Math.min(cap, 8192));
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > cap) {
                throw new AnnattoException.SecurityException(
                    "info/index.json exceeds size limit: " + filename);
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonToMap(String json) {
        Map<String, Object> result = new HashMap<>();
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
        raw.put("subdir", getString(indexInfo, "subdir"));

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
    // Entry streams
    // ================================================================

    /** v1 entry stream: fresh bzip2 chain per pass with a per-pass budget. */
    private class CondaV1EntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CondaV1EntryStream() throws IOException {
            this.tarIn = Archives.bzip2Tar(source.path(), filename, limits.streamPassBytes(),
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

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = tarIn.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > limits.entryBytes()) {
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

        private void checkBudget() {
            if (budgetExceeded.get()) {
                throw new AnnattoException.SecurityException(
                    "Entry-stream decompressed data exceeds per-pass limit: " + filename);
            }
        }
    }

    /** v2 entry stream: fresh {@link ZipFile} per pass over the outer zip. */
    private class CondaV2EntryStream implements PackageEntryStream {
        private final ZipFile zf;
        private final Enumeration<ZipArchiveEntry> entries;
        private final AtomicLong passInflated = new AtomicLong();
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CondaV2EntryStream() throws IOException {
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