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
import io.spicelabs.annatto.LanguagePackage;
import io.spicelabs.annatto.PackageEntryStream;
import io.spicelabs.annatto.ecosystem.conda.CondaPackage;
import io.spicelabs.annatto.ecosystem.cpan.CpanPackage;
import io.spicelabs.annatto.ecosystem.crates.CratesPackage;
import io.spicelabs.annatto.ecosystem.pypi.PyPIPackage;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decompression-bomb and whole-file-overrun tests for the five in-scope ecosystems
 * (Phase 7, plan-phase-7.md, Bug 2 "bomb cascade").
 *
 * <p>Theory (WHAT): PyPI sdist, Crates, and CPAN decompress the ENTIRE gzip to a
 * second unbounded {@code byte[]} during metadata extraction; Conda v2 decompresses zstd
 * uncompacts; wheels buffer the whole archive. A few KB of compressible input can OOM the
 * JVM. Every in-scope package must instead enforce, with small injected {@link Limits}:
 * a spooled-compressed cap, a decompressed-scan cap, a per-entry metadata cap, a metadata
 * scan entry-count cap, and per-pass ZIP inflated budgets.
 *
 * <p>Why (requirement): the npm incident was the first >2 GiB casualty; these are the
 * same bug class reachable with far smaller inputs.
 *
 * <p>RED/GREEN: RED now (the whole-file/unbounded implementation returns a package instead
 * of throwing), with fixtures sized between the injected bound and ~2 GiB so the pre-fix
 * behavior is a clean success, never an OOM.
 */
class EcosystemDecompressionBombTest {

    private static final String CARGO = "[package]\nname = \"big\"\nversion = \"1.0.0\"\n";
    private static final String META_JSON = "{\"name\": \"Big\", \"version\": \"1.0.0\"}";
    private static final String PKG_INFO = "Name: big\nVersion: 1.0.0\n";
    private static final String INDEX_JSON = "{\"name\": \"big\", \"version\": \"1.0.0\", \"subdir\": \"linux-64\"}";

    // ================================================================
    // Decompressed-scan bound on metadata extraction
    // ================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("PyPI sdist metadata decompression is bounded")
    void sdistDecompressionIsBounded() throws IOException {
        // 8 MiB zeros entry first so the (post-fix) gzip-layer scan bound trips
        // before PKG-INFO is reached. Pre-fix this SUCCEEDS (RED).
        byte[] sdist = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.zeros("big-1.0.0/huge.bin", 8L * 1024 * 1024),
                ArchiveBuilder.Entry.of("big-1.0.0/PKG-INFO", PKG_INFO));

        assertThatThrownBy(() -> PyPIPackage.fromStream(
                new ByteArrayInputStream(sdist), "big-1.0.0.tar.gz", Limits.scan(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Crates metadata decompression is bounded")
    void crateDecompressionIsBounded() throws IOException {
        byte[] crateBytes = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.zeros("big-1.0.0/huge.bin", 8L * 1024 * 1024),
                ArchiveBuilder.Entry.of("big-1.0.0/Cargo.toml", CARGO));

        assertThatThrownBy(() -> CratesPackage.fromStream(
                new ByteArrayInputStream(crateBytes), "big-1.0.0.crate", Limits.scan(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("CPAN metadata decompression is bounded")
    void cpanDecompressionIsBounded() throws IOException {
        byte[] dist = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.zeros("Big-1.0/huge.bin", 8L * 1024 * 1024),
                ArchiveBuilder.Entry.of("Big-1.0/META.json", META_JSON));

        assertThatThrownBy(() -> CpanPackage.fromStream(
                new ByteArrayInputStream(dist), "Big-1.0.tar.gz", Limits.scan(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("Conda v1 bzip2 metadata decompression is bounded")
    void condaV1Bzip2DecompressionIsBounded() throws IOException {
        byte[] pkg = ArchiveBuilder.bzip2Tar(
                ArchiveBuilder.Entry.zeros("info/huge.bin", 8L * 1024 * 1024),
                ArchiveBuilder.Entry.of("info/index.json", INDEX_JSON));

        assertThatThrownBy(() -> CondaPackage.fromStream(
                new ByteArrayInputStream(pkg), "big-1.0-0.tar.bz2", Limits.scan(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("Conda v2 zstd metadata decompression is bounded")
    void condaV2ZstdDecompressionIsBounded() throws IOException {
        byte[] innerTarZst = ArchiveBuilder.zstdTar(
                ArchiveBuilder.Entry.zeros("info/huge.bin", 8L * 1024 * 1024),
                ArchiveBuilder.Entry.of("info/index.json", INDEX_JSON));
        byte[] pkg = ArchiveBuilder.condaV2(innerTarZst, "info-big-1.0-0.tar.zst");

        assertThatThrownBy(() -> CondaPackage.fromStream(
                new ByteArrayInputStream(pkg), "big-1.0-0.conda", Limits.scan(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    // ================================================================
    // Whole-file spool bound (compressed)
    // ================================================================

    private static ArchiveBuilder.Entry randomPayload() throws IOException {
        return ArchiveBuilder.Entry.random("payload.bin", 3L * 1024 * 1024);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("oversized compressed package fails closed across all five ecosystems")
    void wholeFileOversizeFailsClosed() throws IOException {
        Limits tight = Limits.spool(1024 * 1024L);

        // npm
        assertThatThrownBy(() -> io.spicelabs.annatto.ecosystem.npm.NpmPackage.fromStream(
                new ByteArrayInputStream(ArchiveBuilder.gzipTar(
                        ArchiveBuilder.Entry.of("package/package.json", "{\"name\":\"x\",\"version\":\"1.0.0\"}"),
                        randomPayload())), "x-1.0.0.tgz", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // PyPI sdist
        assertThatThrownBy(() -> PyPIPackage.fromStream(
                new ByteArrayInputStream(ArchiveBuilder.gzipTar(randomPayload(), file("big-1.0.0/PKG-INFO", PKG_INFO))),
                "big-1.0.0.tar.gz", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // Crates
        assertThatThrownBy(() -> CratesPackage.fromStream(
                new ByteArrayInputStream(ArchiveBuilder.gzipTar(randomPayload(), file("big-1.0.0/Cargo.toml", CARGO))),
                "big-1.0.0.crate", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // CPAN
        assertThatThrownBy(() -> CpanPackage.fromStream(
                new ByteArrayInputStream(ArchiveBuilder.gzipTar(randomPayload(), file("Big-1.0/META.json", META_JSON))),
                "Big-1.0.tar.gz", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // Conda v1
        assertThatThrownBy(() -> CondaPackage.fromStream(
                new ByteArrayInputStream(ArchiveBuilder.bzip2Tar(randomPayload(), file("info/index.json", INDEX_JSON))),
                "big-1.0-0.tar.bz2", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");

        // Conda v2
        assertThatThrownBy(() -> CondaPackage.fromStream(
                new ByteArrayInputStream(ArchiveBuilder.condaV2(
                        ArchiveBuilder.zstdTar(randomPayload(), file("info/index.json", INDEX_JSON)),
                        "info-big-1.0-0.tar.zst")),
                "big-1.0-0.conda", tight))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    private static ArchiveBuilder.Entry file(String name, String content) {
        return ArchiveBuilder.Entry.of(name, content);
    }

    // ================================================================
    // Per-entry metadata caps (PyPI currently has none)
    // ================================================================

    @Test
    @DisplayName("oversized wheel METADATA is rejected per-entry")
    void wheelMetadataEntryCapped() throws IOException {
        byte[] bigMeta = ArchiveBuilder.Entry.random("x", 2L * 1024 * 1024).content();
        byte[] wheel = ArchiveBuilder.zip(Map.of("guard-1.0.0.dist-info/METADATA", bigMeta));

        assertThatThrownBy(() -> PyPIPackage.fromStream(
                new ByteArrayInputStream(wheel), "guard-1.0.0-py3-none-any.whl", Limits.entry(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("oversized sdist PKG-INFO is rejected per-entry")
    void sdistPkgInfoEntryCapped() throws IOException {
        byte[] bigMeta = new byte[2 * 1024 * 1024];
        byte[] head = PKG_INFO.getBytes();
        System.arraycopy(head, 0, bigMeta, 0, head.length);
        java.util.Arrays.fill(bigMeta, head.length, bigMeta.length, (byte) 'a');
        byte[] sdist = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("guard-1.0.0/PKG-INFO", bigMeta));

        assertThatThrownBy(() -> PyPIPackage.fromStream(
                new ByteArrayInputStream(sdist), "guard-1.0.0.tar.gz", Limits.entry(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    // ================================================================
    // Metadata-scan entry-count cap (CPU-DoS guard)
    // ================================================================

    @Test
    @DisplayName("metadata scan stops at the entry-count cap")
    void metadataScanEntryCountBounded() throws IOException {
        List<ArchiveBuilder.Entry> density = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            density.add(ArchiveBuilder.Entry.of("dense-1.0.0/file" + i + ".txt", ""));
        }
        byte[] sdist = ArchiveBuilder.gzipTar(density);

        assertThatThrownBy(() -> PyPIPackage.fromStream(
                new ByteArrayInputStream(sdist), "dense-1.0.0.tar.gz", Limits.entries(50)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("entry");
    }

    // ================================================================
    // ZIP-layer inflated budget on wheel entry streams
    // ================================================================

    @Test
    @DisplayName("wheel entry-stream reads accumulate toward the per-pass ZIP budget")
    void streamPassBudgetTripsOnZipEntryStreams() throws IOException {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("guard-1.0.0.dist-info/METADATA", PKG_INFO.getBytes());
        for (int i = 0; i < 30; i++) {
            entries.put("guard/module" + i + ".py", ArchiveBuilder.Entry.random("x", 100 * 1024L).content());
        }
        byte[] wheel = ArchiveBuilder.zip(entries);

        PyPIPackage pkg = PyPIPackage.fromStream(
                new ByteArrayInputStream(wheel), "guard-1.0.0-py3-none-any.whl", Limits.zipPass(1024 * 1024L));

        try (PackageEntryStream stream = pkg.streamEntries()) {
            assertThatThrownBy(() -> {
                while (stream.hasNext()) {
                    stream.nextEntry();
                    try (java.io.InputStream in = stream.openStream()) {
                        in.readAllBytes();
                    }
                }
            }).isInstanceOf(AnnattoException.SecurityException.class)
              .hasMessageContaining("limit");
        } finally {
            pkg.close();
        }
    }
}