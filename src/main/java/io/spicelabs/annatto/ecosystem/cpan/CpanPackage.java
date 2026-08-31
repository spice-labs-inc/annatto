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

package io.spicelabs.annatto.ecosystem.cpan;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.internal.Archives;
import io.spicelabs.annatto.internal.EntryContentStream;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.PackageSource;
import io.spicelabs.annatto.internal.Spool;
import io.spicelabs.annatto.markers.MetaMarker;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A CPAN (Perl) package (.tar.gz archive).
 *
 * <p>Phase 7 (Bug 2): never read whole into memory. Metadata is a bounded streaming gzip
 * scan for the top-level {@code <dir>/META.json}/{@code META.yml}; entry streams open fresh
 * per pass with a per-pass budget. The unbounded decompress-to-{@code byte[]} bomb path is
 * removed and error messages no longer splice raw cause messages.
 */
public final class CpanPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/gzip";
    private static final int MAX_METADATA_SIZE = 10 * 1024 * 1024; // 10MB

    private final String filename;
    private final PackageMetadata metadata;
    private final PackageSource source;
    private final Limits limits;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Create a CpanPackage from a file path (direct read).
     *
     * @param path the .tar.gz file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CpanPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromSource(new PackageSource.PathSource(path), basename(path), Limits.DEFAULT);
    }

    /**
     * Create a CpanPackage from an input stream (bounded spool).
     *
     * @param stream the .tar.gz stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CpanPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromStream(stream, filename, Limits.DEFAULT);
    }

    /**
     * Create a CpanPackage from an input stream with explicit resource limits.
     *
     * @param stream the .tar.gz stream
     * @param filename for error reporting
     * @param limits resource limits (spool/scan/stream-pass/entry bounds)
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     * @throws AnnattoException.SecurityException if a resource limit is exceeded
     */
    public static CpanPackage fromStream(InputStream stream, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Spool.Spooled spooled = Spool.create(stream, basename(filename), limits.spoolBytes());
        return adoptSpool(spooled, filename, limits);
    }

    /**
     * Adopt an owned spooled file (reader stream handoff); {@code close()} deletes it (S-5).
     */
    public static CpanPackage adoptSpool(Spool.Spooled spooled, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        PackageSource.SpooledSource src = new PackageSource.SpooledSource(
                spooled.path(),
                Spool.registration(spooled.path(), spooled.chargedBytes(), new AtomicBoolean(false)));
        return fromSource(src, basename(filename), limits);
    }

    private static CpanPackage fromSource(PackageSource source, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Map<String, Object> meta;
        try {
            meta = extractMetadata(source.path(), filename, limits);
        } catch (Exception e) {
            source.releaseResources();
            throw e;
        }
        PackageMetadata metadata = parseMetadata(meta);
        return new CpanPackage(filename, source, metadata, limits);
    }

    private CpanPackage(String filename, PackageSource source, PackageMetadata metadata, Limits limits) {
        this.filename = filename;
        this.source = source;
        this.metadata = metadata;
        this.limits = limits;
    }

    @Override
    public @NotNull String mimeType() {
        return MIME_TYPE;
    }

    @Override
    public @NotNull Ecosystem ecosystem() {
        return Ecosystem.CPAN;
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
            // CPAN uses double-colon separators, convert to / for namespace
            String normalized = name.replace("::", "/");
            int lastSlash = normalized.lastIndexOf('/');
            if (lastSlash > 0) {
                String namespace = normalized.substring(0, lastSlash);
                String pkgName = normalized.substring(lastSlash + 1);
                return Optional.of(new PackageURL("cpan", namespace, pkgName, version, null, null));
            }
            return Optional.of(new PackageURL("cpan", null, normalized, version, null, null));
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
            return new CpanEntryStream();
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

    /**
     * Streaming single-pass scan for the top-level META.json/META.yml (no whole-tar byte[]).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractMetadata(Path file, String filename, Limits limits)
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
                String entryName = entry.getName();
                if (MetaMarker.isMetaFile(entryName)) {
                    String content = readStreamToString(tarIn, filename, Math.min(limits.entryBytes(), MAX_METADATA_SIZE));
                    if (entryName.endsWith(".json")) {
                        return parseJsonMetadata(content);
                    } else {
                        return parseYamlMetadata(content);
                    }
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No META.json or META.yml found in CPAN distribution: " + filename);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonMetadata(String json) {
        Map<String, Object> result = new HashMap<>();
        result.put("name", extractJsonString(json, "name"));
        result.put("version", extractJsonString(json, "version"));
        result.put("abstract", extractJsonString(json, "abstract"));
        result.put("license", extractJsonArray(json, "license"));
        result.put("author", extractJsonArray(json, "author"));
        return result;
    }

    @Nullable
    private static String extractJsonString(String json, String key) {
        String pattern = "^\\s{1,4}\"" + key + "\"\\s*:\\s*\"([^\"]+)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.MULTILINE);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    @Nullable
    private static List<String> extractJsonArray(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            String content = m.group(1);
            List<String> result = new ArrayList<>();
            java.util.regex.Pattern itemPattern = java.util.regex.Pattern.compile("\"([^\"]+)\"");
            java.util.regex.Matcher itemMatcher = itemPattern.matcher(content);
            while (itemMatcher.find()) {
                result.add(itemMatcher.group(1));
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }

    private static Map<String, Object> parseYamlMetadata(String yaml) {
        Map<String, Object> result = new HashMap<>();
        String[] lines = yaml.split("\n");

        for (String line : lines) {
            if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                if (line.startsWith("name: ") && !result.containsKey("name")) {
                    result.put("name", stripYamlQuotes(line.substring(6).trim()));
                } else if (line.startsWith("version: ") && !result.containsKey("version")) {
                    result.put("version", stripYamlQuotes(line.substring(9).trim()));
                } else if (line.startsWith("abstract: ") && !result.containsKey("abstract")) {
                    result.put("abstract", stripYamlQuotes(line.substring(10).trim()));
                } else if (line.startsWith("license: ") && !result.containsKey("license")) {
                    String license = stripYamlQuotes(line.substring(9).trim());
                    if (!license.isEmpty()) {
                        result.put("license", List.of(license));
                    }
                }
            }
        }
        return result;
    }

    private static String stripYamlQuotes(String value) {
        if ((value.startsWith("'") && value.endsWith("'")) ||
            (value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static PackageMetadata parseMetadata(Map<String, Object> meta) {
        String name = getString(meta, "name");
        String version = getString(meta, "version");

        if (name == null || name.isEmpty()) {
            name = "";
        }

        Optional<String> description = Optional.ofNullable(getString(meta, "abstract"));
        Optional<String> license = extractLicense(meta);

        Map<String, Object> raw = new HashMap<>();

        return new PackageMetadata(
            name,
            version != null ? version : "",
            description,
            license,
            Optional.empty(),
            Optional.empty(),
            List.of(), // CPAN dependencies not extracted here
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

    @SuppressWarnings("unchecked")
    private static Optional<String> extractLicense(Map<String, Object> meta) {
        Object license = meta.get("license");
        if (license instanceof List && !((List<?>) license).isEmpty()) {
            Object first = ((List<?>) license).get(0);
            if (first instanceof String) {
                return Optional.of((String) first);
            }
        }
        return Optional.empty();
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
     * Entry stream for CPAN packages: fresh gzip chain per pass with a per-pass budget.
     */
    private class CpanEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CpanEntryStream() throws IOException {
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

        private void checkBudget() {
            if (budgetExceeded.get()) {
                throw new AnnattoException.SecurityException(
                    "Entry-stream decompressed data exceeds per-pass limit: " + filename);
            }
        }
    }
}