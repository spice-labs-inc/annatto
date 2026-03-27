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
import io.spicelabs.annatto.internal.PathValidator;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
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
    private final byte[] data;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a GoPackage from a file path.
     *
     * @param path the .zip file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static GoPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        String goMod = extractGoMod(data, filename);
        PackageMetadata metadata = parseMetadata(goMod, filename);

        return new GoPackage(filename, metadata, data);
    }

    private GoPackage(String filename, PackageMetadata metadata, byte[] data) {
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
        if (streamOpen.compareAndSet(false, true)) {
            return new GoEntryStream();
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        streamOpen.set(false);
    }

    private static String extractGoMod(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (ZipArchiveInputStream zipIn = new ZipArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name(), true, true)) {

            ZipArchiveEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (isGoMod(entryName)) {
                    if (entry.getSize() > MAX_GO_MOD_SIZE) {
                        throw new AnnattoException.SecurityException(
                            "go.mod exceeds size limit");
                    }
                    return readStreamToString(zipIn, entry.getSize());
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No go.mod found in Go module: " + filename);
        } catch (AnnattoException.MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException(
                "Failed to read Go module: " + e.getMessage(), e);
        }
    }

    private static boolean isGoMod(String entryName) {
        return entryName.endsWith("/go.mod") || entryName.equals("go.mod");
    }

    private static String readStreamToString(InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
            size > 0 ? (int) Math.min(size, MAX_GO_MOD_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_GO_MOD_SIZE) {
                throw new IOException("go.mod content exceeds size limit");
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

    /**
     * Entry stream implementation for Go packages.
     */
    private class GoEntryStream implements PackageEntryStream {
        private final ZipArchiveInputStream zipIn;
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        GoEntryStream() throws IOException {
            this.zipIn = new ZipArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name(), true, true);
        }

        @Override
        public boolean hasNext() throws IOException {
            checkClosed();
            if (entryCount >= MAX_ENTRIES) {
                throw new AnnattoException.SecurityException(
                    "Package exceeds maximum entry count: " + MAX_ENTRIES);
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
                false, // ZIP doesn't support symlinks directly
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
                throw new AnnattoException.SecurityException(
                    "Entry exceeds size limit: " + currentEntry.getName() +
                    " (" + size + " > " + MAX_ENTRY_SIZE + ")");
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = zipIn.read(buffer)) != -1) {
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
}
