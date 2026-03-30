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

package io.spicelabs.annatto.ecosystem.hex;

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
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Hex.pm (Elixir/Erlang) package (.tar archive).
 *
 * <p>Implements LanguagePackage for Hex packages, providing metadata extraction
 * from metadata.config and entry streaming.
 */
public final class HexPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/x-tar";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_METADATA_SIZE = 1024 * 1024; // 1MB

    private final String filename;
    private final PackageMetadata metadata;
    private final byte[] data;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a HexPackage from a file path.
     *
     * @param path the .tar file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static HexPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create a HexPackage from an input stream.
     *
     * @param stream the .tar stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static HexPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        String metadataConfig = extractMetadata(data, filename);
        PackageMetadata metadata = parseMetadata(metadataConfig);

        return new HexPackage(filename, metadata, data);
    }

    private HexPackage(String filename, PackageMetadata metadata, byte[] data) {
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
        return Ecosystem.HEX;
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
            return Optional.of(new PackageURL("hex", null, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull PackageEntryStream streamEntries() throws IOException {
        if (streamOpen.compareAndSet(false, true)) {
            return new HexEntryStream();
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        streamOpen.set(false);
    }

    private static String extractMetadata(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name())) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.equals("metadata.config")) {
                    if (entry.getSize() > MAX_METADATA_SIZE) {
                        throw new AnnattoException.SecurityException(
                            "metadata.config exceeds size limit");
                    }
                    return readStreamToString(tarIn, entry.getSize());
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No metadata.config found in Hex package: " + filename);
        } catch (AnnattoException.MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException(
                "Failed to read Hex package: " + e.getMessage(), e);
        }
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
                throw new IOException("metadata.config exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static PackageMetadata parseMetadata(String config)
            throws AnnattoException.MalformedPackageException {
        // metadata.config is Erlang term format
        // Parse key fields using simple pattern matching
        Map<String, String> meta = parseErlangConfig(config);

        // Hex metadata.config uses "name" for the package name
        String name = meta.get("name");
        String version = meta.get("version");

        if (name == null || name.isEmpty()) {
            throw new AnnattoException.MalformedPackageException(
                "No name in metadata.config");
        }

        Optional<String> description = Optional.ofNullable(meta.get("description"));
        Optional<String> license = Optional.ofNullable(meta.get("licenses"));

        Map<String, Object> raw = new HashMap<>();
        if (meta.containsKey("links")) {
            raw.put("links", meta.get("links"));
        }

        return new PackageMetadata(
            name,
            version != null ? version : "",
            description,
            license,
            Optional.empty(),
            Optional.empty(),
            List.of(), // Hex dependencies not extracted here
            raw
        );
    }

    private static Map<String, String> parseErlangConfig(String config) {
        Map<String, String> result = new HashMap<>();

        // Simple parsing for key-value pairs
        // Format: {<<"key">>, <<"value">>}. or {<<"key">>, "value"}.
        // Only match top-level pairs (not inside lists like requirements)
        String[] lines = config.split("\n");
        int bracketDepth = 0;
        for (String line : lines) {
            line = line.trim();

            // Track depth in nested structures (lists, etc.)
            for (char c : line.toCharArray()) {
                if (c == '[') bracketDepth++;
                else if (c == ']') bracketDepth--;
            }

            // Only process top-level key-value pairs (depth = 0)
            if (bracketDepth == 0 && line.startsWith("{") && line.contains(",")) {
                int commaIdx = line.indexOf(',');
                String key = line.substring(1, commaIdx).trim();
                String value = line.substring(commaIdx + 1).trim();

                // Remove trailing }.
                if (value.endsWith("}.")) {
                    value = value.substring(0, value.length() - 2).trim();
                } else if (value.endsWith("}")) {
                    value = value.substring(0, value.length() - 1).trim();
                }

                // Extract key from <<"...">> if needed
                if (key.startsWith("<<\"") && key.endsWith("\">>")) {
                    key = key.substring(3, key.length() - 3);
                } else if (key.startsWith("\"") && key.endsWith("\"")) {
                    key = key.substring(1, key.length() - 1);
                }

                // Extract value from <<"...">> or "..."
                if (value.startsWith("<<\"") && value.endsWith("\">>")) {
                    value = value.substring(3, value.length() - 3);
                } else if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }

                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * Entry stream implementation for Hex packages.
     */
    private class HexEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        HexEntryStream() throws IOException {
            this.tarIn = new TarArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name());
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
