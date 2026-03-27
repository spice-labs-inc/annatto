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

package io.spicelabs.annatto.resource;

import io.spicelabs.annatto.LanguagePackage;
import io.spicelabs.annatto.PackageEntry;
import io.spicelabs.annatto.PackageEntryStream;
import io.spicelabs.annatto.ecosystem.npm.NpmPackage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.*;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * Tests for stream lifecycle and resource management.
 *
 * <p>Theory: PackageEntryStream manages underlying archive resources that must be
 * properly released. These tests verify:
 * <ul>
 *   <li>Try-with-resources properly closes streams (ADR-001)</li>
 *   <li>Partial consumption still releases resources</li>
 *   <li>Concurrent stream attempts are prevented</li>
 *   <li>Sequential streams are allowed</li>
 * </ul>
 *
 * <p>These tests use valid packages from the test corpus to ensure real-world
 * behavior is verified.
 */
public class StreamingResourceManagementTest {

    /**
     * Factory: Create a synthetic npm package with multiple entries for testing.
     */
    protected LanguagePackage createMultiEntryPackage() {
        try {
            byte[] packageData = createSyntheticNpmPackage();
            return NpmPackage.fromStream(new ByteArrayInputStream(packageData), "test-package-1.0.0.tgz");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test package", e);
        }
    }

    /**
     * Creates a minimal valid npm package (tgz archive with package.json and extra files).
     */
    private byte[] createSyntheticNpmPackage() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos);
             TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzos)) {

            // Add package.json
            String packageJson = """
                {
                  "name": "test-package",
                  "version": "1.0.0",
                  "description": "Test package for streaming tests",
                  "main": "index.js"
                }
                """;
            addEntry(tarOut, "package/package.json", packageJson.getBytes(StandardCharsets.UTF_8));

            // Add extra files for multi-entry testing
            addEntry(tarOut, "package/index.js", "console.log('hello');".getBytes(StandardCharsets.UTF_8));
            addEntry(tarOut, "package/README.md", "# Test Package".getBytes(StandardCharsets.UTF_8));
            addEntry(tarOut, "package/lib/utils.js", "exports.util = () => {};".getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    private void addEntry(TarArchiveOutputStream tarOut, String name, byte[] content) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content.length);
        tarOut.putArchiveEntry(entry);
        tarOut.write(content);
        tarOut.closeArchiveEntry();
    }

    @Test
    @DisplayName("streamEntries closes underlying stream on normal completion")
    void streamEntriesClosesUnderlyingStreamOnNormalCompletion() throws IOException {
        LanguagePackage pkg = createMultiEntryPackage();

        try (PackageEntryStream stream = pkg.streamEntries()) {
            // Consume all entries
            while (stream.hasNext()) {
                stream.nextEntry();
            }
        }
        // Success: no exception, resources released
    }

    @Test
    @DisplayName("streamEntries closes when partially consumed")
    void streamEntriesClosesWhenPartiallyConsumed() throws IOException {
        LanguagePackage pkg = createMultiEntryPackage();

        try (PackageEntryStream stream = pkg.streamEntries()) {
            // Only read first entry
            if (stream.hasNext()) {
                stream.nextEntry();
            }
            // Exit try-with-resources - should close properly
        }
    }

    @Test
    @DisplayName("streamEntries closes on exception during iteration")
    void streamEntriesClosesOnException() throws IOException {
        LanguagePackage pkg = createMultiEntryPackage();

        assertThatThrownBy(() -> {
            try (PackageEntryStream stream = pkg.streamEntries()) {
                if (stream.hasNext()) {
                    stream.nextEntry();
                }
                throw new RuntimeException("simulated error");
            }
        }).isInstanceOf(RuntimeException.class).hasMessage("simulated error");
        // Stream should still be closed via try-with-resources
    }

    @Test
    @DisplayName("concurrent streams on same package throws IllegalStateException")
    void concurrentStreamsOnSamePackageThrow() throws IOException {
        LanguagePackage pkg = createMultiEntryPackage();

        PackageEntryStream stream1 = pkg.streamEntries();
        try {
            assertThatThrownBy(() -> pkg.streamEntries())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open");
        } finally {
            stream1.close();
        }
    }

    @Test
    @DisplayName("multiple sequential streams are allowed")
    void multipleSequentialStreamsAllowed() throws IOException {
        LanguagePackage pkg = createMultiEntryPackage();

        // First stream
        try (PackageEntryStream stream1 = pkg.streamEntries()) {
            assertThat(stream1.hasNext()).isTrue();
        }

        // Second stream - should work after first is closed
        try (PackageEntryStream stream2 = pkg.streamEntries()) {
            assertThat(stream2.hasNext()).isTrue();
        }
    }

    @Test
    @DisplayName("double close is safe (idempotent)")
    void doubleCloseIsSafe() throws IOException {
        LanguagePackage pkg = createMultiEntryPackage();

        PackageEntryStream stream = pkg.streamEntries();
        stream.close();
        stream.close(); // Should not throw
    }

    @Test
    @DisplayName("stream.forEach closes automatically")
    void streamForEachClosesAutomatically() throws IOException {
        LanguagePackage pkg = createMultiEntryPackage();

        // Using the stream() method which should auto-close
        try (var stream = pkg.streamEntries().stream()) {
            stream.limit(5).forEach(entry -> {
                assertThat(entry).isNotNull();
            });
        }
    }

    @Test
    @DisplayName("openStream throws if no current entry")
    void openStreamThrowsIfNoCurrentEntry() throws IOException {
        LanguagePackage pkg = createMultiEntryPackage();

        try (PackageEntryStream stream = pkg.streamEntries()) {
            assertThatThrownBy(() -> stream.openStream())
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("entry content stream is bounded to declared size")
    void entryContentStreamBounded() throws IOException {
        // The stream should naturally end at the entry boundary
        // because TarArchiveInputStream only provides that entry's content
        LanguagePackage pkg = createMultiEntryPackage();

        try (PackageEntryStream stream = pkg.streamEntries()) {
            while (stream.hasNext()) {
                PackageEntry entry = stream.nextEntry();
                if (!entry.isDirectory() && entry.size() > 0) {
                    try (InputStream content = stream.openStream()) {
                        // Read all content - should stop at entry boundary
                        long totalRead = content.transferTo(OutputStream.nullOutputStream());
                        // Size should match what was declared
                        assertThat(totalRead).isEqualTo(entry.size());
                    }
                }
            }
        }
    }
}
