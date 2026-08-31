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
import io.spicelabs.annatto.internal.Archives;
import io.spicelabs.annatto.internal.EntryContentStream;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.PackageSource;
import io.spicelabs.annatto.internal.Spool;
import io.spicelabs.annatto.markers.CargoTomlMarker;
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

/**
 * A Crates.io package (.crate archive).
 *
 * <p>Phase 7 (Bug 2): never read whole into memory. Metadata is a bounded streaming gzip
 * scan for the top-level {@code <dir>/Cargo.toml}; entry streams open fresh per pass with a
 * per-pass decompressed budget. The unbounded {@code GZIPInputStream.transferTo(byte[])}
 * bomb path is removed.
 */
public final class CratesPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/gzip";
    private static final long MAX_CARGO_TOML_SIZE = 1024 * 1024; // 1MB

    private final String filename;
    private final PackageMetadata metadata;
    private final PackageSource source;
    private final Limits limits;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Create a CratesPackage from a file path (direct read).
     *
     * @param path the .crate file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CratesPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromSource(new PackageSource.PathSource(path), basename(path), Limits.DEFAULT);
    }

    /**
     * Create a CratesPackage from an input stream (bounded spool).
     *
     * @param stream the .crate stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static CratesPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromStream(stream, filename, Limits.DEFAULT);
    }

    /**
     * Create a CratesPackage from an input stream with explicit resource limits.
     *
     * @param stream the .crate stream
     * @param filename for error reporting
     * @param limits resource limits (spool/scan/stream-pass/entry bounds)
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     * @throws AnnattoException.SecurityException if a resource limit is exceeded
     */
    public static CratesPackage fromStream(InputStream stream, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Spool.Spooled spooled = Spool.create(stream, basename(filename), limits.spoolBytes());
        return adoptSpool(spooled, filename, limits);
    }

    /**
     * Adopt an owned spooled file (reader stream handoff); {@code close()} deletes it (S-5).
     */
    public static CratesPackage adoptSpool(Spool.Spooled spooled, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        PackageSource.SpooledSource src = new PackageSource.SpooledSource(
                spooled.path(),
                Spool.registration(spooled.path(), spooled.chargedBytes(), new AtomicBoolean(false)));
        return fromSource(src, basename(filename), limits);
    }

    private static CratesPackage fromSource(PackageSource source, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        String cargoToml;
        try {
            cargoToml = extractCargoToml(source.path(), filename, limits);
        } catch (Exception e) {
            source.releaseResources();
            throw e;
        }
        PackageMetadata metadata = parseMetadata(cargoToml);
        return new CratesPackage(filename, source, metadata, limits);
    }

    private CratesPackage(String filename, PackageSource source, PackageMetadata metadata, Limits limits) {
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
        if (closed) {
            throw new IllegalStateException("Package is closed");
        }
        if (!streamOpen.compareAndSet(false, true)) {
            throw new IllegalStateException("A stream is already open on this package");
        }
        try {
            return new CrateEntryStream();
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
     * Streaming single-pass scan for the top-level Cargo.toml (no whole-tar byte[]).
     */
    private static String extractCargoToml(Path file, String filename, Limits limits)
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
                if (CargoTomlMarker.isCargoToml(entry.getName())) {
                    return readStreamToString(tarIn, filename, Math.min(limits.entryBytes(), MAX_CARGO_TOML_SIZE));
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No Cargo.toml found in crate archive: " + filename);
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
                    "Cargo.toml metadata exceeds size limit: " + filename);
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
            throw new AnnattoException.MalformedPackageException("Failed to parse Cargo.toml");
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
        int angleIdx = firstAuthor.indexOf('<');
        if (angleIdx > 0) {
            return firstAuthor.substring(0, angleIdx).trim();
        }
        return firstAuthor.trim();
    }

    private static List<Dependency> parseDependencies(TomlParseResult toml) {
        List<Dependency> deps = new ArrayList<>();

        parseDependencySection(toml.getTable("dependencies"), "runtime", deps);
        parseDependencySection(toml.getTable("dev-dependencies"), "dev", deps);
        parseDependencySection(toml.getTable("build-dependencies"), "build", deps);

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
                deps.add(new Dependency(
                    key,
                    Optional.of(scope),
                    normalizeVersionConstraint(versionStr)
                ));
            } else if (value instanceof TomlTable depTable) {
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
     * Entry stream for crate packages: fresh gzip chain per pass with a per-pass budget.
     */
    private class CrateEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        CrateEntryStream() throws IOException {
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

            // Lazy bounded view (Fresh Scent Phase 2): no whole-entry materialization. The
            // gzip→inflate chain already charges the per-pass budget on every read
            // (BoundedInflateStream), so this view only enforces the per-entry cap and
            // truncation detection.
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

        private void checkBudget() throws java.io.IOException {
            if (budgetExceeded.get()) {
                throw new AnnattoException.SecurityException(
                    "Entry-stream decompressed data exceeds per-pass limit: " + filename);
            }
        }
    }
}