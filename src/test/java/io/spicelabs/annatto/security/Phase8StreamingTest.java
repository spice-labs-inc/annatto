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

package io.spicelabs.annatto.security;

import io.spicelabs.annatto.AnnattoException;
import io.spicelabs.annatto.ecosystem.cocoapods.CocoapodsPackage;
import io.spicelabs.annatto.ecosystem.go.GoPackage;
import io.spicelabs.annatto.ecosystem.hex.HexPackage;
import io.spicelabs.annatto.ecosystem.luarocks.LuarocksPackage;
import io.spicelabs.annatto.ecosystem.packagist.PackagistPackage;
import io.spicelabs.annatto.ecosystem.rubygems.RubygemsPackage;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 red tests: the remaining six ecosystems (Go, RubyGems, Packagist, CocoaPods, Hex,
 * LuaRocks) must stop reading packages whole into memory and bound their metadata scans.
 *
 * <p>RED now (whole-file {@code transferTo(byte[])} implementations succeed where a
 * SecurityException is required); GREEN after the Phase 8 streaming refactors reuse the
 * Phase 7 {@code PackageSource}/{@code Spool}/{@code Limits} infrastructure.
 */
class Phase8StreamingTest {

    // ================================================================
    // Fixture builders
    // ================================================================

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(data);
        }
        return baos.toByteArray();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String pad(String head, long total) {
        StringBuilder sb = new StringBuilder((int) Math.min(total, 8 * 1024 * 1024));
        sb.append(head);
        while (sb.length() < total) {
            sb.append('a');
        }
        return sb.toString();
    }

    private static byte[] goZip(long goModSize, long padSize) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("github.com/acme/widget/go.mod",
                bytes(pad("module github.com/acme/widget\n\ngo 1.21\n\n", goModSize)));
        if (padSize > 0) {
            entries.put("github.com/acme/widget/pad.bin", ArchiveBuilder.Entry.random("x", padSize).content());
        }
        return ArchiveBuilder.zip(entries);
    }

    private static byte[] packagistZip(long composerSize, long padSize) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        if (padSize > 0) {
            entries.put("vendor/pkg/pad.bin", ArchiveBuilder.Entry.random("x", padSize).content());
        }
        entries.put("vendor/pkg/composer.json",
                bytes(pad("{\"name\": \"vendor/pkg\", \"version\": \"1.0.0\", \"description\": \"", composerSize) + "\"}"));
        return ArchiveBuilder.zip(entries);
    }

    private static byte[] gem(long yamlSize, long padSize) throws IOException {
        String yaml = pad("---\nname: sample-gem\nversion: 1.0.0\ndescription: \"", yamlSize) + "\"\n";
        byte[] dataTar = padSize > 0
                ? ArchiveBuilder.Entry.random("x", padSize).content()
                : bytes("");
        return ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.of("metadata.gz", gzip(bytes(yaml))),
                ArchiveBuilder.Entry.of("data.tar.gz", dataTar));
    }

    private static byte[] hexTar(int denseEntries, long padSize) throws IOException {
        List<ArchiveBuilder.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < denseEntries; i++) {
            entries.add(ArchiveBuilder.Entry.of("lib/file" + i + ".erl", ""));
        }
        if (padSize > 0) {
            entries.add(ArchiveBuilder.Entry.random("pad.bin", padSize));
        }
        entries.add(ArchiveBuilder.Entry.of("metadata.config",
                bytes("{<<\"name\">>,<<\"sample_hex\">>}.\n{<<\"version\">>,<<\"1.0.0\">>}.\n")));
        return ArchiveBuilder.plainTar(entries);
    }

    private static byte[] podspecJson(long size) {
        return bytes(pad("{\"name\": \"SamplePod\", \"version\": \"1.0.0\", \"summary\": \"", size) + "\"}");
    }

    private static byte[] rockspec(long size) {
        return bytes(pad("package = \"sample-rock\"\nversion = \"1.0-1\"\nsummary = \"", size) + "\"\n");
    }

    // ================================================================
    // Whole-file spool bound (compressed; applies to every ecosystem)
    // ================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("oversized whole-file fails closed for the remaining six ecosystems")
    void wholeFileOversizeFailsClosedPhase8() throws IOException {
        Limits tight = Limits.spool(1024 * 1024L);

        // Go module zip
        assertThatThrownBy(() -> GoPackage.fromStream(
                new ByteArrayInputStream(goZip(200, 3L * 1024 * 1024)), "github.com/acme/widget@v1.0.0.zip", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // RubyGems gem (outer tar)
        assertThatThrownBy(() -> RubygemsPackage.fromStream(
                new ByteArrayInputStream(gem(500, 3L * 1024 * 1024)), "sample-gem-1.0.0.gem", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // Packagist zip
        assertThatThrownBy(() -> PackagistPackage.fromStream(
                new ByteArrayInputStream(packagistZip(300, 3L * 1024 * 1024)), "vendor-pkg-1.0.0.zip", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // CocoaPods podspec.json (plain)
        assertThatThrownBy(() -> CocoapodsPackage.fromStream(
                new ByteArrayInputStream(podspecJson(3L * 1024 * 1024)), "SamplePod-1.0.0.podspec.json", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // Hex plain tar
        assertThatThrownBy(() -> HexPackage.fromStream(
                new ByteArrayInputStream(hexTar(0, 3L * 1024 * 1024)), "sample_hex-1.0.0.tar", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // LuaRocks rockspec (plain)
        assertThatThrownBy(() -> LuarocksPackage.fromStream(
                new ByteArrayInputStream(rockspec(3L * 1024 * 1024)), "sample-rock-1.0-1.rockspec", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    // ================================================================
    // Nested decompression (RubyGems metadata.gz)
    // ================================================================

    @Test
    @DisplayName("RubyGems nested metadata.gz decompression is bounded")
    void nestedGzipMetadataBounded() throws IOException {
        byte[] pkg = gem(500L * 1024, 0); // metadata.gz decompresses to ~500KiB

        assertThatThrownBy(() -> RubygemsPackage.fromStream(
                new ByteArrayInputStream(pkg), "sample-gem-1.0.0.gem", Limits.scan(100L * 1024)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    // ================================================================
    // Per-entry metadata caps (zip metadata files)
    // ================================================================

    @Test
    @DisplayName("oversized go.mod is rejected per-entry")
    void goModEntryCapped() throws IOException {
        assertThatThrownBy(() -> GoPackage.fromStream(
                new ByteArrayInputStream(goZip(2L * 1024 * 1024, 0)), "github.com/acme/widget@v1.0.0.zip",
                Limits.entry(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("oversized composer.json is rejected per-entry")
    void composerJsonEntryCapped() throws IOException {
        assertThatThrownBy(() -> PackagistPackage.fromStream(
                new ByteArrayInputStream(packagistZip(2L * 1024 * 1024, 0)), "vendor-pkg-1.0.0.zip",
                Limits.entry(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    // ================================================================
    // Plain-file metadata caps (CocoaPods / LuaRocks)
    // ================================================================

    @Test
    @DisplayName("oversized podspec.json is rejected (metadata cap)")
    void podspecJsonEntryBounded() throws IOException {
        assertThatThrownBy(() -> CocoapodsPackage.fromStream(
                new ByteArrayInputStream(podspecJson(2L * 1024 * 1024)), "SamplePod-1.0.0.podspec.json",
                Limits.entry(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("oversized rockspec is rejected (metadata cap)")
    void rockspecEntryBounded() throws IOException {
        assertThatThrownBy(() -> LuarocksPackage.fromStream(
                new ByteArrayInputStream(rockspec(2L * 1024 * 1024)), "sample-rock-1.0-1.rockspec",
                Limits.entry(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    // ================================================================
    // Metadata-scan entry-count cap (Hex plain tar)
    // ================================================================

    @Test
    @DisplayName("Hex metadata scan stops at the entry-count cap")
    void hexMetadataScanEntryCountBounded() throws IOException {
        assertThatThrownBy(() -> HexPackage.fromStream(
                new ByteArrayInputStream(hexTar(200, 0)), "sample_hex-1.0.0.tar", Limits.entries(50)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("entry");
    }

    // ================================================================
    // Entry-stream limits are honored (Hex regression; exact-cap boundary)
    // ================================================================

    @Test
    @DisplayName("Hex entry-stream content honors injected entry limits")
    void hexEntryStreamRespectsInjectedEntryCap() throws IOException {
        byte[] pkg = ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.random("lib/big.bin", 2L * 1024 * 1024),
                ArchiveBuilder.Entry.of("metadata.config",
                        bytes("{<<\"name\">>,<<\"sample_hex\">>}.\n{<<\"version\">>,<<\"1.0.0\">>}.\n")));
        HexPackage hp = HexPackage.fromStream(new ByteArrayInputStream(pkg), "sample_hex-1.0.0.tar",
                Limits.entry(1024L));

        try (io.spicelabs.annatto.PackageEntryStream stream = hp.streamEntries()) {
            while (stream.hasNext()) {
                io.spicelabs.annatto.PackageEntry entry = stream.nextEntry();
                if (entry.name().equals("lib/big.bin")) {
                    assertThatThrownBy(() -> stream.openStream())
                            .isInstanceOf(AnnattoException.SecurityException.class)
                            .as("entry content must be bounded by the injected per-entry cap");
                } else {
                    try (java.io.InputStream in = stream.openStream()) {
                        in.readAllBytes();
                    }
                }
            }
        } finally {
            hp.close();
        }
    }

    @Test
    @DisplayName("a package with exactly the entry-count cap iterates to completion")
    void exactEntryCountIterationCompletes() throws IOException {
        // 5-entry tar with limits.entries(5): iteration must complete with a clean false,
        // not throw at the boundary.
        byte[] pkg = ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.of("lib/a.erl", ""),
                ArchiveBuilder.Entry.of("lib/b.erl", ""),
                ArchiveBuilder.Entry.of("lib/c.erl", ""),
                ArchiveBuilder.Entry.of("lib/d.erl", ""),
                ArchiveBuilder.Entry.of("metadata.config",
                        bytes("{<<\"name\">>,<<\"sample_hex\">>}.\n{<<\"version\">>,<<\"1.0.0\">>}.\n")));
        HexPackage hp = HexPackage.fromStream(new ByteArrayInputStream(pkg), "sample_hex-1.0.0.tar",
                Limits.entries(5));

        try (io.spicelabs.annatto.PackageEntryStream stream = hp.streamEntries()) {
            int count = 0;
            while (stream.hasNext()) {
                stream.nextEntry();
                count++;
            }
            assertThat(count).isEqualTo(5);
        } finally {
            hp.close();
        }
    }
}