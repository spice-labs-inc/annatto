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

package io.spicelabs.annatto.ecosystem.crates;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.internal.PathValidator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * A Crates.io package (.crate archive).
 *
 * <p>Implements LanguagePackage for Crates packages, providing metadata extraction
 * and entry streaming.
 */
public final class CratesPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/gzip";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_CARGO_TOML_SIZE = 10 * 1024 * 1024; // 10 MB

    private final String filename;
    private final PackageMetadata metadata;
    private final byte[] data;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a CratesPackage from a file path.
     *
     * @param path the .crate file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CratesPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create a CratesPackage from an input stream.
     *
     * @param stream the .crate stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CratesPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        // Buffer the stream for multiple reads
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        // Extract and parse Cargo.toml
        String cargoToml = extractCargoToml(data, filename);
        PackageMetadata metadata = parseMetadata(cargoToml);

        return new CratesPackage(filename, metadata, data);
    }

    private CratesPackage(String filename, PackageMetadata metadata, byte[] data) {
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
        return Ecosystem.CRATES;
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
            return Optional.of(new PackageURL("cargo", null, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull PackageEntryStream streamEntries() throws IOException {
        if (streamOpen.compareAndSet(false, true)) {
            return new CrateEntryStream();
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        // Nothing to close - data is in memory
        streamOpen.set(false);
    }

    private static String extractCargoToml(byte[] data, String filename)
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
                if (isCargoToml(entryName)) {
                    if (entry.getSize() > MAX_CARGO_TOML_SIZE) {
                        throw new AnnattoException.SecurityException(
                            "Cargo.toml exceeds size limit");
                    }
                    return readStreamToString(tarIn, entry.getSize());
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No Cargo.toml found in crate archive: " + filename);
        } catch (AnnattoException.MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException(
                "Failed to read crate archive: " + e.getMessage(), e);
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
                "Failed to decompress crate: " + filename, e);
        }
    }

    private static boolean isCargoToml(String entryName) {
        if (entryName.contains("..")) {
            return false;
        }
        String[] parts = entryName.split("/");
        return parts.length == 2 && parts[1].equals("Cargo.toml");
    }

    private static String readStreamToString(InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
            size > 0 ? (int) Math.min(size, MAX_CARGO_TOML_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_CARGO_TOML_SIZE) {
                throw new IOException("Cargo.toml content exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static PackageMetadata parseMetadata(String cargoToml)
            throws AnnattoException.MalformedPackageException {
        TomlParseResult toml;
        try {
            toml = Toml.parse(cargoToml);
        } catch (Exception e) {
            throw new AnnattoException.MalformedPackageException(
                "Failed to parse Cargo.toml: " + e.getMessage(), e);
        }

        String name = toml.getString("package.name");
        String version = toml.getString("package.version");
        String description = toml.getString("package.description");
        String license = toml.getString("package.license");
        String publisher = extractPublisher(toml);

        List<Dependency> dependencies = parseDependencies(toml);

        Map<String, Object> raw = new HashMap<>();
        raw.put("edition", toml.getString("package.edition"));
        raw.put("repository", toml.getString("package.repository"));
        raw.put("homepage", toml.getString("package.homepage"));

        return new PackageMetadata(
            name != null ? name : "",
            version != null ? version : "",
            Optional.ofNullable(description).filter(s -> !s.isEmpty()),
            Optional.ofNullable(license).filter(s -> !s.isEmpty()),
            Optional.ofNullable(publisher).filter(s -> !s.isEmpty()),
            Optional.empty(),
            dependencies,
            raw
        );
    }

    private static @Nullable String extractPublisher(TomlParseResult toml) {
        TomlArray authors = toml.getArray("package.authors");
        if (authors == null || authors.isEmpty()) {
            return null;
        }
        String firstAuthor = authors.getString(0);
        if (firstAuthor == null || firstAuthor.isEmpty()) {
            return null;
        }
        // Strip email in angle brackets: "Name <email>" -> "Name"
        int angleIdx = firstAuthor.indexOf('<');
        if (angleIdx > 0) {
            return firstAuthor.substring(0, angleIdx).trim();
        }
        return firstAuthor.trim();
    }

    private static List<Dependency> parseDependencies(TomlParseResult toml) {
        List<Dependency> deps = new ArrayList<>();

        // Top-level dependency sections
        parseDependencySection(toml.getTable("dependencies"), "runtime", deps);
        parseDependencySection(toml.getTable("dev-dependencies"), "dev", deps);
        parseDependencySection(toml.getTable("build-dependencies"), "build", deps);

        // Target-specific dependency sections
        TomlTable targetTable = toml.getTable("target");
        if (targetTable != null) {
            for (String targetKey : targetTable.keySet()) {
                Object entry = targetTable.get(List.of(targetKey));
                if (entry instanceof TomlTable targetEntry) {
                    parseDependencySection(getSubTable(targetEntry, "dependencies"), "runtime", deps);
                    parseDependencySection(getSubTable(targetEntry, "dev-dependencies"), "dev", deps);
                    parseDependencySection(getSubTable(targetEntry, "build-dependencies"), "build", deps);
                }
            }
        }

        return deps;
    }

    private static void parseDependencySection(@Nullable TomlTable section, String scope,
                                               List<Dependency> deps) {
        if (section == null) {
            return;
        }
        for (String key : section.keySet()) {
            Object value = section.get(key);
            if (value instanceof String versionStr) {
                // Simple format: dep = "1.0"
                deps.add(new Dependency(
                    key,
                    Optional.of(scope),
                    normalizeVersionConstraint(versionStr)
                ));
            } else if (value instanceof TomlTable depTable) {
                // Table format: dep = { version = "1.0", package = "real-name", ... }
                String realName = depTable.getString("package");
                String depName = realName != null ? realName : key;

                String version = depTable.getString("version");
                String versionConstraint = version != null
                    ? normalizeVersionConstraint(version)
                    : "";

                deps.add(new Dependency(depName, Optional.of(scope), versionConstraint));
            }
        }
    }

    private static String normalizeVersionConstraint(String version) {
        if (version.isEmpty()) {
            return version;
        }
        char first = version.charAt(0);
        if (first == '^' || first == '~' || first == '>' || first == '<' || first == '=' || first == '*') {
            return version;
        }
        return "^" + version;
    }

    private static @Nullable TomlTable getSubTable(@NotNull TomlTable table, @NotNull String key) {
        Object value = table.get(List.of(key));
        return value instanceof TomlTable t ? t : null;
    }

    /**
     * Entry stream implementation for crate packages.
     */
    private class CrateEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CrateEntryStream() throws IOException {
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
