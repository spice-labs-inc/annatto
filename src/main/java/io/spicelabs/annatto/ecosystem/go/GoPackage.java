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

package io.spicelabs.annatto.ecosystem.go;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.internal.Archives;
import io.spicelabs.annatto.internal.EntryContentStream;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.PackageSource;
import io.spicelabs.annatto.internal.PathValidator;
import io.spicelabs.annatto.internal.Spool;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Go module package (.zip archive).
 *
 * <p>Implements LanguagePackage for Go modules, providing metadata extraction
 * from go.mod files and entry streaming.
 */
public final class GoPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/zip";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_GO_MOD_SIZE = 1024 * 1024; // 1MB

    private final String filename;
    private final PackageMetadata metadata;
    private final PackageSource source;
    private final Limits limits;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);
    private volatile boolean closed = false;

    /**
     * Create a GoPackage from a file path.
     *
     * @param path the .zip file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static GoPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromSource(new PackageSource.PathSource(path), basename(path), Limits.DEFAULT);
    }

    /**
     * Create a GoPackage from an input stream.
     *
     * @param stream the .zip stream
     * @param filename for error reporting and module path detection
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static GoPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        return fromStream(stream, filename, Limits.DEFAULT);
    }

    /**
     * Create a GoPackage from an input stream with explicit resource limits.
     *
     * @param stream the package stream
     * @param filename for error reporting
     * @param limits resource limits (spool/scan/stream-pass/entry bounds)
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     * @throws AnnattoException.SecurityException if a resource limit is exceeded
     */
    public static GoPackage fromStream(InputStream stream, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        Spool.Spooled spooled = Spool.create(stream, basename(filename), limits.spoolBytes());
        return adoptSpool(spooled, filename, limits);
    }

    /**
     * Adopt an owned spooled file (reader stream handoff); {@code close()} deletes it (S-5).
     */
    public static GoPackage adoptSpool(Spool.Spooled spooled, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        PackageSource.SpooledSource src = new PackageSource.SpooledSource(
                spooled.path(),
                Spool.registration(spooled.path(), spooled.chargedBytes(), new AtomicBoolean(false)));
        return fromSource(src, basename(filename), limits);
    }

    private static GoPackage fromSource(PackageSource source, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        String goMod;
        try {
            goMod = extractGoMod(source.path(), filename, limits);
        } catch (Exception e) {
            source.releaseResources();
            throw e;
        }
        PackageMetadata metadata = parseMetadata(goMod, filename);
        return new GoPackage(filename, source, metadata, limits);
    }

    private GoPackage(String filename, PackageSource source, PackageMetadata metadata, Limits limits) {
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
        return Ecosystem.GO;
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
            // Go module paths can be complex - use as namespace/name
            // e.g., github.com/foo/bar -> namespace=github.com/foo, name=bar
            int lastSlash = name.lastIndexOf('/');
            if (lastSlash > 0) {
                String namespace = name.substring(0, lastSlash);
                String pkgName = name.substring(lastSlash + 1);
                return Optional.of(new PackageURL("golang", namespace, pkgName, version, null, null));
            }
            return Optional.of(new PackageURL("golang", null, name, version, null, null));
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
            return new GoEntryStream();
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

    private static String extractGoMod(Path file, String filename, Limits limits)
            throws IOException, AnnattoException.MalformedPackageException {
        try (ZipFile zf = Archives.zipFile(file)) {
            java.util.Enumeration<ZipArchiveEntry> entries = zf.getEntries();
            int count = 0;
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (++count > limits.maxEntries()) {
                    throw new AnnattoException.SecurityException(
                        "Archive exceeds metadata scan entry count limit: " + filename);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (isGoMod(entryName)) {
                    return readStreamToString(zf.getInputStream(entry), filename,
                            Math.min(limits.entryBytes(), MAX_GO_MOD_SIZE));
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No go.mod found in Go module: " + filename);
        }
    }

    private static boolean isGoMod(String entryName) {
        return entryName.endsWith("/go.mod") || entryName.equals("go.mod");
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
                    "go.mod metadata exceeds size limit: " + filename);
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static PackageMetadata parseMetadata(String goMod, String filename)
            throws AnnattoException.MalformedPackageException {
        // Extract module path from go.mod
        String modulePath = extractModulePath(goMod);
        if (modulePath.isEmpty()) {
            throw new AnnattoException.MalformedPackageException(
                "No module declaration found in go.mod");
        }

        // Extract version from filename (contains @v)
        String version = extractVersionFromFilename(filename);

        // Extract other metadata
        Optional<String> description = Optional.empty();
        Optional<String> license = extractLicense(goMod);

        // Parse dependencies
        List<Dependency> dependencies = parseDependencies(goMod);

        Map<String, Object> raw = new HashMap<>();
        raw.put("go_version", extractGoVersion(goMod));

        return new PackageMetadata(
            modulePath,
            version,
            description,
            license,
            Optional.empty(), // No publisher info in go.mod
            Optional.empty(),
            dependencies,
            raw
        );
    }

    private static String extractModulePath(String goMod) {
        // Handle both quoted and unquoted module paths
        // Examples: module "github.com/foo/bar" or module github.com/foo/bar
        Pattern pattern = Pattern.compile("^module\\s+(?:\"([^\"]+)\"|(\\S+))", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(goMod);
        if (matcher.find()) {
            // Return the quoted group if present, otherwise the unquoted group
            return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        }
        return "";
    }

    private static String extractVersionFromFilename(String filename) {
        // Go module zips have format: module@version.zip or contain @v in path
        // Examples: github.com/foo/bar@v1.2.3.zip or v1.2.3.zip
        int atV = filename.lastIndexOf("@v");
        if (atV >= 0) {
            String versionPart = filename.substring(atV + 1); // v1.2.3.zip
            int dotZip = versionPart.indexOf(".zip");
            if (dotZip > 0) {
                return versionPart.substring(0, dotZip);
            }
            return versionPart;
        }
        // Try to find vX.Y.Z pattern
        Pattern pattern = Pattern.compile("v(\\d+\\.\\d+.*?)\\.zip$");
        Matcher matcher = pattern.matcher(filename);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static Optional<String> extractLicense(String goMod) {
        // go.mod doesn't typically contain license info
        // Could be in a comment, but rare
        return Optional.empty();
    }

    private static String extractGoVersion(String goMod) {
        Pattern pattern = Pattern.compile("^go\\s+(\\d+\\.\\d+)", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(goMod);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static List<Dependency> parseDependencies(String goMod) {
        List<Dependency> deps = new ArrayList<>();

        // Parse require blocks and single require statements
        Pattern requirePattern = Pattern.compile(
            "require\\s*\\((.*?)\\)|require\\s+(\\S+)\\s+(\\S+)",
            Pattern.DOTALL | Pattern.MULTILINE
        );
        Matcher matcher = requirePattern.matcher(goMod);

        while (matcher.find()) {
            if (matcher.group(1) != null) {
                // Block format: require ( ... )
                String block = matcher.group(1);
                deps.addAll(parseRequireBlock(block));
            } else {
                // Single format: require path version
                String path = matcher.group(2);
                String version = matcher.group(3);
                if (path != null && version != null) {
                    deps.add(new Dependency(path, Optional.of("runtime"), version));
                }
            }
        }

        return deps;
    }

    private static List<Dependency> parseRequireBlock(String block) {
        List<Dependency> deps = new ArrayList<>();
        Pattern linePattern = Pattern.compile("(\\S+)\\s+(\\S+)");
        Matcher matcher = linePattern.matcher(block);
        while (matcher.find()) {
            String path = matcher.group(1);
            String version = matcher.group(2);
            if (!path.isEmpty() && !version.isEmpty()) {
                deps.add(new Dependency(path, Optional.of("runtime"), version));
            }
        }
        return deps;
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
     * Entry stream implementation for Go packages.
     */
    private class GoEntryStream implements PackageEntryStream {
        private final ZipFile zf;
        private final java.util.Enumeration<ZipArchiveEntry> entries;
        private final java.util.concurrent.atomic.AtomicLong passInflated = new java.util.concurrent.atomic.AtomicLong();
        private final AtomicBoolean budgetExceeded = new AtomicBoolean();
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        GoEntryStream() throws IOException {
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
            String name = PathValidator.validateEntryName(currentEntry.getName());
            long size = currentEntry.getSize();
            return new PackageEntry(
                name,
                size,
                currentEntry.isDirectory(),
                false, // ZIP does not support symlinks directly
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

            InputStream in = zf.getInputStream(currentEntry);
            return EntryContentStream.withPassBudget(in, currentEntry.getName(),
                    currentEntry.getSize(), limits.entryBytes(), passInflated,
                    limits.zipPassBytes(), () -> budgetExceeded.set(true));
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