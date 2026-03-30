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
import io.spicelabs.annatto.internal.PathValidator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * A CPAN (Perl) package (.tar.gz archive).
 *
 * <p>Implements LanguagePackage for CPAN distributions, providing metadata extraction
 * from META.json or META.yml and entry streaming.
 */
public final class CpanPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/gzip";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_METADATA_SIZE = 10 * 1024 * 1024; // 10MB

    private final String filename;
    private final PackageMetadata metadata;
    private final byte[] data;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a CpanPackage from a file path.
     *
     * @param path the .tar.gz file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CpanPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create a CpanPackage from an input stream.
     *
     * @param stream the .tar.gz stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CpanPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        Map<String, Object> meta = extractMetadata(data, filename);
        PackageMetadata metadata = parseMetadata(meta);

        return new CpanPackage(filename, metadata, data);
    }

    private CpanPackage(String filename, PackageMetadata metadata, byte[] data) {
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
        if (streamOpen.compareAndSet(false, true)) {
            return new CpanEntryStream();
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        streamOpen.set(false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractMetadata(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        // Pre-decompress GZIP to avoid concurrency issues with native Inflater
        byte[] tarData = decompressGzipToBytes(data, filename);
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new ByteArrayInputStream(tarData), StandardCharsets.UTF_8.name())) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();

                // Look for META.json or META.yml in the distribution root
                if (isMetadataFile(entryName)) {
                    if (entry.getSize() > MAX_METADATA_SIZE) {
                        throw new AnnattoException.SecurityException("Metadata file exceeds size limit");
                    }
                    String content = readStreamToString(tarIn, entry.getSize());
                    if (entryName.endsWith(".json")) {
                        return parseJsonMetadata(content);
                    } else {
                        return parseYamlMetadata(content);
                    }
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No META.json or META.yml found in CPAN distribution: " + filename);
        } catch (AnnattoException.MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException(
                "Failed to read CPAN distribution: " + e.getMessage(), e);
        }
    }

    private static byte[] decompressGzipToBytes(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            gzis.transferTo(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException(
                "Failed to decompress CPAN distribution: " + filename, e);
        }
    }

    private static boolean isMetadataFile(String entryName) {
        // Format: Dist-Name-1.00/META.json or Dist-Name-1.00/META.yml
        return entryName.endsWith("/META.json") || entryName.endsWith("/META.yml");
    }

    private static String readStreamToString(InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
            size > 0 ? (int) Math.min(size, MAX_METADATA_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_METADATA_SIZE) {
                throw new IOException("Metadata file exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonMetadata(String json) {
        // Simple JSON parsing for key fields
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
        // Match top-level keys by requiring they appear after the opening brace
        // and before any nested objects (which would have more indentation)
        // Pattern: key at start of line with 1-4 spaces indentation (typical for JSON)
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
        // Simple YAML parsing for key fields
        // Only matches top-level keys (lines with no leading whitespace)
        Map<String, Object> result = new HashMap<>();
        String[] lines = yaml.split("\n");

        for (String line : lines) {
            // Check if this is a top-level line (no leading whitespace)
            if (!line.isEmpty() && !line.startsWith(" ") && !line.startsWith("\t")) {
                // Top-level key - strip surrounding quotes from values
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
        // Strip single or double quotes from YAML values
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

    /**
     * Entry stream implementation for CPAN packages.
     */
    private class CpanEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private final GZIPInputStream gzipIn;
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CpanEntryStream() throws IOException {
            this.gzipIn = new GZIPInputStream(new ByteArrayInputStream(data));
            this.tarIn = new TarArchiveInputStream(gzipIn, StandardCharsets.UTF_8.name());
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
