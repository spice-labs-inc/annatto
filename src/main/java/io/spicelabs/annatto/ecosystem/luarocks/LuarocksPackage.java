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

package io.spicelabs.annatto.ecosystem.luarocks;

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
 * A LuaRocks package (.rock or .rockspec file).
 *
 * <p>Implements LanguagePackage for LuaRocks packages, supporting:
 * <ul>
 *   <li>.rockspec - Lua specification files</li>
 *   <li>.rock - ZIP archives containing .rockspec</li>
 *   <li>.src.rock - Source rock archives</li>
 * </ul>
 */
public final class LuarocksPackage implements LanguagePackage {

    private static final String MIME_TYPE_ROCKSPEC = "text/x-lua";
    private static final String MIME_TYPE_ZIP = "application/zip";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_ROCKSPEC_SIZE = 1024 * 1024; // 1MB

    private final String filename;
    private final PackageMetadata metadata;
    private final byte[] data;
    private final boolean isZipFormat;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a LuarocksPackage from a file path.
     *
     * @param path the .rock, .rockspec file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static LuarocksPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create a LuarocksPackage from an input stream.
     *
     * @param stream the input stream
     * @param filename for format detection and error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static LuarocksPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        // Note: Size check is done on the extracted rockspec, not the entire archive
        // since .src.rock files can be large (they contain source code)
        boolean isZip = filename.toLowerCase().endsWith(".rock") && !filename.toLowerCase().endsWith(".rockspec");
        String rockspec = isZip
                ? extractRockspecFromZip(data, filename)
                : new String(data, StandardCharsets.UTF_8);

        PackageMetadata metadata = parseMetadata(rockspec, filename);
        return new LuarocksPackage(filename, metadata, data, isZip);
    }

    private LuarocksPackage(String filename, PackageMetadata metadata, byte[] data, boolean isZip) {
        this.filename = filename;
        this.metadata = metadata;
        this.data = data;
        this.isZipFormat = isZip;
    }

    @Override
    public @NotNull String mimeType() {
        return isZipFormat ? MIME_TYPE_ZIP : MIME_TYPE_ROCKSPEC;
    }

    @Override
    public @NotNull Ecosystem ecosystem() {
        return Ecosystem.LUAROCKS;
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
            return Optional.of(new PackageURL("luarocks", null, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull PackageEntryStream streamEntries() throws IOException {
        if (streamOpen.compareAndSet(false, true)) {
            if (isZipFormat) {
                return new LuarocksZipEntryStream();
            } else {
                return new SingleEntryStream();
            }
        }
        throw new IllegalStateException("A stream is already open on this package");
    }

    @Override
    public void close() {
        streamOpen.set(false);
    }

    private static String extractRockspecFromZip(byte[] data, String filename)
            throws AnnattoException.MalformedPackageException {
        try (ZipArchiveInputStream zipIn = new ZipArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name(), true, true)) {

            ZipArchiveEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.endsWith(".rockspec")) {
                    if (entry.getSize() > MAX_ROCKSPEC_SIZE) {
                        throw new AnnattoException.SecurityException("Rockspec exceeds size limit");
                    }
                    return readStreamToString(zipIn, entry.getSize());
                }
            }
            throw new AnnattoException.MalformedPackageException("No .rockspec found in: " + filename);
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException("Failed to read rock: " + e.getMessage(), e);
        }
    }

    private static String readStreamToString(InputStream stream, long size) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                size > 0 ? (int) Math.min(size, MAX_ROCKSPEC_SIZE) : 8192);
        byte[] buffer = new byte[8192];
        int read;
        long totalRead = 0;
        while ((read = stream.read(buffer)) != -1) {
            totalRead += read;
            if (totalRead > MAX_ROCKSPEC_SIZE) {
                throw new IOException("Rockspec exceeds size limit");
            }
            baos.write(buffer, 0, read);
        }
        return baos.toString(StandardCharsets.UTF_8);
    }

    private static PackageMetadata parseMetadata(String rockspec, String filename)
            throws AnnattoException.MalformedPackageException {
        // Parse Lua rockspec
        String packageName = extractLuaString(rockspec, "package");
        String version = extractLuaString(rockspec, "version");

        if (packageName == null || packageName.isEmpty()) {
            // Try to extract from filename
            packageName = extractNameFromFilename(filename);
        }

        if (version == null || version.isEmpty()) {
            version = extractVersionFromFilename(filename);
        }

        Optional<String> description = Optional.ofNullable(extractLuaString(rockspec, "summary"));
        Optional<String> license = Optional.ofNullable(extractLuaString(rockspec, "license"));

        List<Dependency> dependencies = parseDependencies(rockspec);

        Map<String, Object> raw = new HashMap<>();
        String homepage = extractLuaString(rockspec, "homepage");
        if (homepage != null) {
            raw.put("homepage", homepage);
        }

        return new PackageMetadata(
                packageName != null ? packageName : "",
                version != null ? version : "",
                description,
                license,
                Optional.empty(),
                Optional.empty(),
                dependencies,
                raw
        );
    }

    private static String extractLuaString(String rockspec, String key) {
        // Match patterns like: package = "name" or package = 'name'
        Pattern pattern = Pattern.compile("\\b" + key + "\\s*=\\s*([\"'])([^\"']+)\\1", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(rockspec);
        if (matcher.find()) {
            return matcher.group(2);
        }
        return null;
    }

    private static String extractNameFromFilename(String filename) {
        // Format: name-version.rockspec or name-version.src.rock
        // LuaRocks version format is: MAJOR.MINOR.PATCH-REVISION (e.g., 4.7.1-1)
        // The last numeric segment after a dash is the rockspec revision
        String base = filename.substring(filename.lastIndexOf('/') + 1);

        // Remove .rock or .rockspec extension
        String withoutExt = base;
        if (base.endsWith(".src.rock")) {
            withoutExt = base.substring(0, base.length() - 9); // Remove .src.rock
        } else if (base.endsWith(".rockspec")) {
            withoutExt = base.substring(0, base.length() - 9); // Remove .rockspec
        } else if (base.endsWith(".rock")) {
            withoutExt = base.substring(0, base.length() - 5); // Remove .rock
        }

        // Find the dash that separates name from version
        // Version ends with -N (rockspec revision number)
        // Pattern: name-X.Y.Z-N, we need to find the dash before X
        int lastDash = withoutExt.lastIndexOf('-');
        if (lastDash > 0) {
            // Check if after last dash is a number (rockspec revision)
            String afterLastDash = withoutExt.substring(lastDash + 1);
            if (afterLastDash.matches("\\d+")) {
                // This is the revision number, find the version part
                int versionDash = withoutExt.lastIndexOf('-', lastDash - 1);
                if (versionDash > 0) {
                    return withoutExt.substring(0, versionDash);
                }
            }
            // No revision number found, single dash separates name and version
            return withoutExt.substring(0, lastDash);
        }
        return "";
    }

    private static String extractVersionFromFilename(String filename) {
        // Format: name-version.rockspec or name-version.src.rock
        // LuaRocks version format is: MAJOR.MINOR.PATCH-REVISION
        String base = filename.substring(filename.lastIndexOf('/') + 1);

        // Remove .rock or .rockspec extension
        String withoutExt = base;
        if (base.endsWith(".src.rock")) {
            withoutExt = base.substring(0, base.length() - 9);
        } else if (base.endsWith(".rockspec")) {
            withoutExt = base.substring(0, base.length() - 9);
        } else if (base.endsWith(".rock")) {
            withoutExt = base.substring(0, base.length() - 5);
        }

        // Find the dash that separates name from version
        int lastDash = withoutExt.lastIndexOf('-');
        if (lastDash > 0) {
            String afterLastDash = withoutExt.substring(lastDash + 1);
            if (afterLastDash.matches("\\d+")) {
                // This is the rockspec revision, include it in version
                // Version is everything from the dash before the version number to end
                int versionDash = withoutExt.lastIndexOf('-', lastDash - 1);
                if (versionDash > 0) {
                    return withoutExt.substring(versionDash + 1);
                }
            }
            // No revision number, return everything after the dash
            return withoutExt.substring(lastDash + 1);
        }
        return "";
    }

    private static List<Dependency> parseDependencies(String rockspec) {
        List<Dependency> deps = new ArrayList<>();

        // dependencies table
        Pattern depTablePattern = Pattern.compile(
            "dependencies\\s*=\\s*\\{([^}]+)\\}", Pattern.DOTALL);
        Matcher tableMatcher = depTablePattern.matcher(rockspec);
        if (tableMatcher.find()) {
            String depsContent = tableMatcher.group(1);
            // Parse individual deps
            Pattern depPattern = Pattern.compile("([\"'])([^\"']+)\\1");
            Matcher depMatcher = depPattern.matcher(depsContent);
            while (depMatcher.find()) {
                String dep = depMatcher.group(2);
                // Parse version constraint if present (e.g., "name >= 1.0")
                int spaceIdx = dep.indexOf(' ');
                String name;
                String version;
                if (spaceIdx > 0) {
                    name = dep.substring(0, spaceIdx);
                    version = dep.substring(spaceIdx + 1).trim();
                } else {
                    name = dep;
                    version = "";
                }
                // Filter out platform dependencies (lua runtime)
                if (!name.equals("lua")) {
                    deps.add(new Dependency(name, Optional.of("runtime"), version));
                }
            }
        }

        return deps;
    }

    /**
     * Single entry stream for rockspec files.
     */
    private class SingleEntryStream implements PackageEntryStream {
        private boolean returned = false;
        private boolean closed = false;

        @Override
        public boolean hasNext() {
            checkClosed();
            return !returned;
        }

        @Override
        public @NotNull PackageEntry nextEntry() {
            checkClosed();
            if (returned) {
                throw new IllegalStateException("No current entry - call hasNext() first");
            }
            returned = true;
            return new PackageEntry(filename, data.length, false, false, Optional.empty());
        }

        @Override
        public @NotNull InputStream openStream() {
            checkClosed();
            if (!returned) {
                throw new IllegalStateException("No current entry");
            }
            return new ByteArrayInputStream(data);
        }

        @Override
        public void close() {
            closed = true;
            streamOpen.set(false);
        }

        private void checkClosed() {
            if (closed) {
                throw new IllegalStateException("Stream is closed");
            }
        }
    }

    /**
     * Entry stream for ZIP format rocks.
     */
    private class LuarocksZipEntryStream implements PackageEntryStream {
        private final ZipArchiveInputStream zipIn;
        private ZipArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        LuarocksZipEntryStream() throws IOException {
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
                    false,
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
