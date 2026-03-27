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

package io.spicelabs.annatto.ecosystem.rubygems;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.*;
import io.spicelabs.annatto.internal.PathValidator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * A RubyGems package (.gem archive).
 *
 * <p>Implements LanguagePackage for RubyGems, providing metadata extraction
 * from metadata.gz and entry streaming.
 */
public final class RubygemsPackage implements LanguagePackage {

    private static final String MIME_TYPE = "application/x-tar";
    private static final long MAX_ENTRY_SIZE = 10 * 1024 * 1024; // 10MB
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_METADATA_SIZE = 10 * 1024 * 1024; // 10MB

    private final String filename;
    private final PackageMetadata metadata;
    private final byte[] data;
    private final AtomicBoolean streamOpen = new AtomicBoolean(false);

    /**
     * Create a RubygemsPackage from a file path.
     *
     * @param path the .gem file path
     * @throws IOException if the file cannot be read
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static RubygemsPackage fromPath(Path path)
            throws IOException, AnnattoException.MalformedPackageException {
        try (InputStream is = new BufferedInputStream(
                new FileInputStream(path.toFile()), 8192)) {
            return fromStream(is, path.toString());
        }
    }

    /**
     * Create a RubygemsPackage from an input stream.
     *
     * @param stream the .gem stream
     * @param filename for error reporting
     * @throws IOException if reading fails
     * @throws AnnattoException.MalformedPackageException if the package is invalid
     */
    public static RubygemsPackage fromStream(InputStream stream, String filename)
            throws IOException, AnnattoException.MalformedPackageException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        stream.transferTo(baos);
        byte[] data = baos.toByteArray();

        // Extract metadata from metadata.gz
        Map<String, Object> gemSpec = extractMetadata(data, filename);
        PackageMetadata metadata = parseMetadata(gemSpec);

        return new RubygemsPackage(filename, metadata, data);
    }

    private RubygemsPackage(String filename, PackageMetadata metadata, byte[] data) {
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
        return Ecosystem.RUBYGEMS;
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
            return Optional.of(new PackageURL("gem", null, name, version, null, null));
        } catch (MalformedPackageURLException e) {
            return Optional.empty();
        }
    }

    @Override
    public @NotNull PackageEntryStream streamEntries() throws IOException {
        if (streamOpen.compareAndSet(false, true)) {
            return new RubygemsEntryStream();
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
        try (TarArchiveInputStream tarIn = new TarArchiveInputStream(
                new ByteArrayInputStream(data), StandardCharsets.UTF_8.name())) {

            TarArchiveEntry entry;
            while ((entry = tarIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                if (entryName.equals("metadata.gz")) {
                    if (entry.getSize() > MAX_METADATA_SIZE) {
                        throw new AnnattoException.SecurityException(
                            "metadata.gz exceeds size limit");
                    }
                    return readAndParseMetadataGz(tarIn);
                }
            }
            throw new AnnattoException.MalformedPackageException(
                "No metadata.gz found in gem: " + filename);
        } catch (AnnattoException.MalformedPackageException e) {
            throw e;
        } catch (IOException e) {
            throw new AnnattoException.MalformedPackageException(
                "Failed to read gem archive: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readAndParseMetadataGz(InputStream stream) throws IOException {
        try (GZIPInputStream gzis = new GZIPInputStream(stream);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int read;
            long totalRead = 0;
            while ((read = gzis.read(buffer)) != -1) {
                totalRead += read;
                if (totalRead > MAX_METADATA_SIZE) {
                    throw new IOException("metadata.gz content exceeds size limit");
                }
                baos.write(buffer, 0, read);
            }

            String yaml = baos.toString(StandardCharsets.UTF_8);
            // Strip Ruby-specific YAML tags that SafeConstructor cannot handle
            yaml = yaml.replaceAll("!ruby/object:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/regexp:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/range:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/class:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/module:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/exception:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/struct:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/sym:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/array:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/hash:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/set:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/omap:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/pairs:[^\\s\\n]*", "");
            yaml = yaml.replaceAll("!ruby/timestamp:[^\\s\\n]*", "");
            LoaderOptions loaderOptions = new LoaderOptions();
            Yaml yamlParser = new Yaml(new SafeConstructor(loaderOptions));
            return yamlParser.load(yaml);
        }
    }

    @SuppressWarnings("unchecked")
    private static PackageMetadata parseMetadata(Map<String, Object> gemSpec) {
        String name = getString(gemSpec, "name");
        String version = "";

        Object versionObj = gemSpec.get("version");
        if (versionObj instanceof String) {
            version = (String) versionObj;
        } else if (versionObj instanceof Map) {
            version = getString((Map<String, Object>) versionObj, "version");
        }

        Optional<String> description = Optional.ofNullable(getString(gemSpec, "summary"));
        Optional<String> license = extractLicense(gemSpec);

        List<Dependency> dependencies = parseDependencies(gemSpec);

        Map<String, Object> raw = new HashMap<>();
        raw.put("homepage", gemSpec.get("homepage"));
        raw.put("authors", gemSpec.get("authors"));

        return new PackageMetadata(
            name != null ? name : "",
            version != null ? version : "",
            description,
            license,
            extractPublisher(gemSpec),
            Optional.empty(),
            dependencies,
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

    private static Optional<String> extractPublisher(Map<String, Object> gemSpec) {
        Object authors = gemSpec.get("authors");
        if (authors instanceof List && !((List<?>) authors).isEmpty()) {
            Object first = ((List<?>) authors).get(0);
            if (first instanceof String) {
                return Optional.of((String) first);
            }
        }
        Object author = gemSpec.get("author");
        if (author instanceof String) {
            return Optional.of((String) author);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Optional<String> extractLicense(Map<String, Object> gemSpec) {
        Object licenses = gemSpec.get("licenses");
        if (licenses instanceof List && !((List<?>) licenses).isEmpty()) {
            Object first = ((List<?>) licenses).get(0);
            if (first instanceof String) {
                return Optional.of((String) first);
            }
        }
        String license = getString(gemSpec, "license");
        if (license != null && !license.isEmpty()) {
            return Optional.of(license);
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static List<Dependency> parseDependencies(Map<String, Object> gemSpec) {
        List<Dependency> deps = new ArrayList<>();

        Object dependencies = gemSpec.get("dependencies");
        if (dependencies instanceof List) {
            for (Object depObj : (List<?>) dependencies) {
                if (depObj instanceof Map) {
                    Map<String, Object> dep = (Map<String, Object>) depObj;
                    String name = getString(dep, "name");
                    String versionReq = "";
                    Object requirement = dep.get("requirement");
                    if (requirement instanceof Map) {
                        versionReq = getString((Map<String, Object>) requirement, "requirements");
                        if (versionReq == null) versionReq = "";
                    }
                    String type = getString(dep, "type");
                    String scope = type != null ? type : "runtime";

                    if (name != null && !name.isEmpty()) {
                        deps.add(new Dependency(name, Optional.of(scope), versionReq));
                    }
                }
            }
        }

        return deps;
    }

    /**
     * Entry stream implementation for RubyGems packages.
     */
    private class RubygemsEntryStream implements PackageEntryStream {
        private final TarArchiveInputStream tarIn;
        private TarArchiveEntry currentEntry;
        private int entryCount = 0;
        private boolean closed = false;

        RubygemsEntryStream() throws IOException {
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
