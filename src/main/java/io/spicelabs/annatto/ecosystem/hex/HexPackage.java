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
import io.spicelabs.annatto.internal.EntryContentStream;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.PackageSource;
import io.spicelabs.annatto.internal.PathValidator;
import io.spicelabs.annatto.internal.Spool;
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
    private final PackageSource source;
    private final Limits limits;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Create a HexPackage from a file path.
     *
     * @param path the .tar file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static HexPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromSource(new PackageSource.PathSource(path), basename(path), Limits.DEFAULT);
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
        return fromStream(stream, filename, Limits.DEFAULT);
    }

    /**
     * Create a HexPackage from an input stream with explicit resource limits.
     *
     * @param stream the package stream
     * @param filename for error reporting
     * @param limits resource limits (spool/scan/stream-pass/entry bounds)
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     * @throws AnnattoException.SecurityException if a resource limit is exceeded
     */
    public static HexPackage fromStream(InputStream stream, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Spool.Spooled spooled = Spool.create(stream, basename(filename), limits.spoolBytes());
        return adoptSpool(spooled, filename, limits);
    }

    public static HexPackage adoptSpool(Spool.Spooled spooled, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        PackageSource.SpooledSource src = new PackageSource.SpooledSource(
                spooled.path(),
                Spool.registration(spooled.path(), spooled.chargedBytes(), new AtomicBoolean(false)));
        return fromSource(src, basename(filename), limits);
    }

    private static HexPackage fromSource(PackageSource source, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        String metadataConfig;
        try {
            metadataConfig = extractMetadata(source.path(), filename, limits);
        } catch (Exception e) {
            source.releaseResources();
            throw e;
        }
        PackageMetadata metadata = parseMetadata(metadataConfig);
        return new HexPackage(filename, source, metadata, limits);
    }

    private HexPackage(String filename, PackageSource source, PackageMetadata metadata, Limits limits) {
        this.filename = filename;
        this.metadata = metadata;
        this.source = source;
        this.limits = limits;
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
        if (closed) {
            throw new IllegalStateException("Package is closed");
        }
        if (!streamOpen.compareAndSet(false, true)) {
            throw new IllegalStateException("A stream is already open on this package");
        }
        try {
            return new HexEntryStream();
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

    private static String extractMetadata(Path file, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new BufferedInputStream(new FileInputStream(file.toFile()), 8192), StandardCharsets.UTF_8.name())) {

            TarArchiveEntry entry;
            int count = 0;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (++count > limits.maxEntries()) {
                    throw new AnnattoException.SecurityException(
                        "Archive exceeds metadata scan entry count limit: " + filename);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if (entry.getName().equals("metadata.config")) {
                    return readStreamToString(tarIn, filename, Math.min(limits.entryBytes(), MAX_METADATA_SIZE));
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No metadata.config found in Hex package: " + filename);
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
     * Entry stream implementation for Hex packages.
     */
    private class HexEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private final java.util.concurrent.atomic.AtomicLong passInflated =
                new java.util.concurrent.atomic.AtomicLong();
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        HexEntryStream() throws IOException {
            this.tarIn = new TarArchiveInputStream(
                new BufferedInputStream(new FileInputStream(source.path().toFile()), 8192),
                StandardCharsets.UTF_8.name());
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
            if (size >= 0 && size > limits.entryBytes()) {
                throw new AnnattoException.SecurityException(
                    "Entry exceeds size limit: " + currentEntry.getName() +
                    " (" + size + " > " + limits.entryBytes() + ")");
            }

            long declaredSize = currentEntry.isSparse() ? -1 : currentEntry.getSize();
            return EntryContentStream.withPassBudget(tarIn, currentEntry.getName(), declaredSize,
                    limits.entryBytes(), passInflated, limits.streamPassBytes(),
                    () -> budgetExceeded.set(true));
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
