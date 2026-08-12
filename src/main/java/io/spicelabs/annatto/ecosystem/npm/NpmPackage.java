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
import io.spicelabs.annatto.internal.Archives;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.PackageSource;
import io.spicelabs.annatto.internal.Spool;
import io.spicelabs.annatto.markers.NpmEntryMarker;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An npm package (.tgz archive).
 *
 * <p>Phase 7 (Bug 2): the package is NEVER read whole into memory. {@link #fromPath} reads
 * streamingly from the caller's path; {@link #fromStream} spools the bounded source to a
 * temp file; metadata extraction is a single streaming scan with a decompressed budget; each
 * {@code streamEntries()} pass opens a FRESH decompression chain with its own pass budget;
 * {@code openStream()} content is per-entry bounded. {@code close()} releases owned spools
 * and closes the package (S-5).
 *
 * <p>Security (ADR-005): spool cap (compressed), metadata scan cap and per-pass stream cap
 * applied at the gzip layer, per-entry size cap, entry-count cap. A generic tar.gz must never
 * be treated as npm (routing guarantees this, Bug 1).
 */
public final class NpmPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/gzip";

    private final String filename;
    private final PackageMetadata metadata;
    private final com.google.gson.JsonObject packageJson;
    private final PackageSource source;
    private final Limits limits;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Create an NpmPackage from a file path (direct read; the file must outlive the package).
     *
     * @param path the .tgz file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static NpmPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromSource(new PackageSource.PathSource(path), basename(path), Limits.DEFAULT);
    }

    /**
     * Create an NpmPackage from an input stream (bounded spool to a private temp file).
     *
     * @param stream the .tgz stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static NpmPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromStream(stream, filename, Limits.DEFAULT);
    }

    /**
     * Create an NpmPackage from an input stream with explicit resource limits.
     *
     * @param stream the .tgz stream
     * @param filename for error reporting
     * @param limits resource limits (spool/scan/stream-pass/entry bounds)
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     * @throws AnnattoException.SecurityException if a resource limit is exceeded
     */
    public static NpmPackage fromStream(InputStream stream, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Spool.Spooled spooled = Spool.create(stream, basename(filename), limits.spoolBytes());
        return adoptSpool(spooled, filename, limits);
    }

    /**
     * Adopt an owned spooled file (reader stream handoff). The package's {@code close()}
     * deletes the spool (S-5).
     *
     * @param spooled the bounded spool (path + budget charge)
     * @param filename for error reporting
     * @param limits resource limits
     */
    public static NpmPackage adoptSpool(Spool.Spooled spooled, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        PackageSource.SpooledSource src = new PackageSource.SpooledSource(
                spooled.path(),
                Spool.registration(spooled.path(), spooled.chargedBytes(), new AtomicBoolean(false)));
        return fromSource(src, basename(filename), limits);
    }

    private static NpmPackage fromSource(PackageSource source, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        com.google.gson.JsonObject packageJson;
        try {
            packageJson = extractPackageJson(source.path(), filename, limits);
        } catch (Exception e) {
            source.releaseResources();
            throw e;
        }
        PackageMetadata metadata = parseMetadata(packageJson);
        return new NpmPackage(filename, source, metadata, packageJson, limits);
    }

    private NpmPackage(String filename, PackageSource source, PackageMetadata metadata,
                       com.google.gson.JsonObject packageJson, Limits limits) {
        this.filename = filename;
        this.source = source;
        this.metadata = metadata;
        this.packageJson = packageJson;
        this.limits = limits;
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
        if (closed) {
            throw new IllegalStateException("Package is closed");
        }
        if (!streamOpen.compareAndSet(false, true)) {
            throw new IllegalStateException("A stream is already open on this package");
        }
        try {
            return new NpmEntryStream();
        } catch (IOException | RuntimeException e) {
            // Restore the flag so a failed reader construction never locks the package (P0-E).
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
     * Metadata extraction: a SINGLE streaming pass over the source with a decompressed-scan
     * budget and entry-count cap (no whole-tar array; Bug 2 fix).
     */
    private static com.google.gson.JsonObject extractPackageJson(Path file, String filename, Limits limits)
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
                // npm packs files under package/; markers agree with routing (NpmEntryMarker).
                if (NpmEntryMarker.isPackageJson(entry.getName())) {
                    return parseJson(tarIn, filename, limits.entryBytes());
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No package.json found in npm archive: " + filename);
        }
    }

    private static com.google.gson.JsonObject parseJson(InputStream stream, String filename, long entryCap)
            throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > entryCap) {
                throw new AnnattoException.SecurityException(
                    "Metadata file exceeds size limit: " + filename);
            }
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
     * Entry stream for npm packages. Each pass opens a FRESH decompression chain with its own
     * per-pass decompressed budget (tar-skip = decompression → bounded); a spent budget
     * latches the stream into a fail-fast state.
     */
    private class NpmEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        NpmEntryStream() throws IOException {
            Runnable onExceed = () -> budgetExceeded.set(true);
            this.tarIn = Archives.gzipTar(source.path(), filename, limits.streamPassBytes(), onExceed);
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

            // Phase 7: refuse to surface content for a symlink whose target escapes the archive.
            if (currentEntry.isSymbolicLink()
                    && !io.spicelabs.annatto.internal.PathValidator.isSafeSymlinkTarget(currentEntry.getLinkName())) {
                throw new AnnattoException.SecurityException(
                    "Symlink target escapes the archive: " + currentEntry.getName());
            }

            // Per-entry bounded buffered content (10 MiB default); the read also flows
            // through the per-pass decompressed budget wrapped below the tar reader.
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
}