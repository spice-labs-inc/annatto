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

import io.spicelabs.annatto.AnnattoException;
import io.spicelabs.annatto.LanguagePackage;
import io.spicelabs.annatto.PackageEntry;
import io.spicelabs.annatto.PackageEntryStream;
import io.spicelabs.annatto.ecosystem.npm.NpmPackage;
import io.spicelabs.annatto.internal.AggregateSpoolBudget;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.internal.Spool;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import io.spicelabs.annatto.testutil.CountingInputStream;
import io.spicelabs.annatto.testutil.TempDirAsserter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * npm memory-safety tests (Phase 7, plan-phase-7.md, Bug 2).
 *
 * <p>Theory (WHAT): an npm package must never be read whole into memory. The compressed
 * archive stream is spooled to a bounded temp file; metadata extraction is a single
 * streaming pass with a decompressed-scan bound; each {@code streamEntries()} pass carries
 * a decompressed budget; entry content is per-entry bounded; {@code close()} releases owned
 * resources and closes the package (S-5).
 *
 * <p>Why (requirement): {@code repo_ea.tgz} (4.9 GiB) was buffered into a Java {@code byte[]}
 * and died at the 2 GiB array limit (incident). These tests pin down the bounded/streaming
 * contract with SMALL injected limits so fixtures stay tiny and pre-fix behavior is a clean
 * exception-free success (the RED half), never an OOM.
 *
 * <p>RED/GREEN: most tests are RED now (the whole-file-buffering implementation succeeds
 * where a SecurityException/closed-state is required). Those marked "(guard)" are expected
 * green before and after.
 */
class NpmMemorySafetyTest {

    private static final String PKG_JSON = "{\"name\": \"npm-fixture\", \"version\": \"1.0.0\"}";

    private static byte[] validNpmTgz() throws IOException {
        return ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", PKG_JSON),
                ArchiveBuilder.Entry.of("package/index.js", "console.log(1);"));
    }

    private static byte[] npmTgzWithIncompressibleEntry(long entryBytes) throws IOException {
        return ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", PKG_JSON),
                ArchiveBuilder.Entry.random("package/data.bin", entryBytes));
    }

    private static byte[] npmTgzWithPaddingBeforePackageJson() throws IOException {
        // package.json must come AFTER the huge zeros entry so the (post-fix) scan bound
        // trips before the marker is reached - otherwise extraction returns first.
        return ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.zeros("package/huge.bin", 8L * 1024 * 1024),
                ArchiveBuilder.Entry.of("package/package.json", PKG_JSON));
    }

    private static byte[] npmTgzWithLargePackageJson(long jsonSize) throws IOException {
        StringBuilder sb = new StringBuilder("{\"name\": \"x\", \"version\": \"1.0.0\", \"pad\": \"");
        long pad = jsonSize - sb.length() - 4;
        for (int i = 0; i < pad; i++) {
            sb.append('a');
        }
        sb.append("\"}");
        return ArchiveBuilder.gzipTar(ArchiveBuilder.Entry.of("package/package.json", sb.toString()));
    }

    // ================================================================
    // Whole-file buffering must not substitute for streaming
    // ================================================================

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("fromStream stops at the spool bound instead of buffering the whole package")
    void fromStreamNeverBuffersWholePackage() throws IOException {
        byte[] data = npmTgzWithIncompressibleEntry(3L * 1024 * 1024); // compressed ~3 MiB
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(data));
        Limits limits = Limits.spool(1024 * 1024L); // 1 MiB compressed cap

        assertThatThrownBy(() -> NpmPackage.fromStream(counting, "overflow.tgz", limits))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
        // The parser must stop consuming the source once the bound is hit.
        assertThat(counting.readCount())
                .as("source bytes drained must be bounded by the spool cap")
                .isLessThanOrEqualTo(limits.spoolBytes() + 8192);
    }

    @Test
    @DisplayName("a spool overrun fails closed and leaves no temp residue")
    void spoolOverrunCleansUpTempFile() throws IOException {
        var before = TempDirAsserter.snapshotAnnattoFiles();
        byte[] data = npmTgzWithIncompressibleEntry(3L * 1024 * 1024);
        try {
            assertThatThrownBy(() -> NpmPackage.fromStream(
                    new ByteArrayInputStream(data), "overflow.tgz", Limits.spool(1024 * 1024L)))
                    .isInstanceOf(AnnattoException.SecurityException.class);
        } finally {
            TempDirAsserter.assertNoNewFilesSince(before);
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("oversized stream terminates with SecurityException, never OutOfMemoryError")
    void largeStreamFailsClosedNoOom() throws IOException {
        byte[] data = npmTgzWithIncompressibleEntry(3L * 1024 * 1024);
        // Incident proxy: a stream far larger than the bound must fail closed, not buffer.
        assertThatThrownBy(() -> NpmPackage.fromStream(
                new ByteArrayInputStream(data), "huge.tgz", Limits.spool(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class);
    }

    // ================================================================
    // Metadata extraction is a bounded streaming scan
    // ================================================================

    @Test
    @DisplayName("metadata extraction stops scanning at the decompressed scan bound")
    void metadataExtractionScansBounded() throws IOException {
        byte[] data = npmTgzWithPaddingBeforePackageJson();

        assertThatThrownBy(() -> NpmPackage.fromStream(
                new ByteArrayInputStream(data), "padded.tgz", Limits.scan(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("missing package.json is a MalformedPackageException with no temp residue (guard)")
    void missingPackageJsonCleansUpTempFile() throws IOException {
        var before = TempDirAsserter.snapshotAnnattoFiles();
        byte[] noJson = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/README.md", "# readme"));
        try {
            assertThatThrownBy(() -> NpmPackage.fromStream(
                    new ByteArrayInputStream(noJson), "nojson.tgz", Limits.DEFAULT))
                    .isInstanceOf(AnnattoException.MalformedPackageException.class)
                    .hasMessageContaining("package.json");
        } finally {
            TempDirAsserter.assertNoNewFilesSince(before);
        }
    }

    // ================================================================
    // streamEntries() pass budget (tar-skip = decompression)
    // ================================================================

    @Test
    @DisplayName("enumeration trips the per-pass decompressed budget")
    void enumerationDecompressionBudgetEnforced() throws IOException {
        byte[] data = npmTgzWithPaddingBeforePackageJson();
        // The pass budget is captured at construction time.
        NpmPackage pkg = NpmPackage.fromStream(
                new ByteArrayInputStream(data), "padded.tgz", Limits.streamPass(1024 * 1024L));

        try (PackageEntryStream stream = pkg.streamEntries()) {
            assertThatThrownBy(() -> {
                while (stream.hasNext()) {
                    stream.nextEntry();
                }
            }).isInstanceOf(AnnattoException.SecurityException.class)
              .hasMessageContaining("limit");
        } finally {
            pkg.close();
        }
    }

    @Test
    @DisplayName("openStream trips the pass budget on first read; the stream fails closed afterwards")
    void openStreamMidEntryBudgetTripHasNextSemantics() throws IOException {
        // Updated with user approval (2026-08-28, Fresh Scent Phase 2): openStream() is now
        // LAZY — it no longer buffers entry content, so the pass-budget trip moved from
        // openStream() itself to the first read. The load-bearing contract is unchanged:
        // a spent pass budget fails closed and consumers never observe silent truncation.
        byte[] data = npmTgzWithLargePackageJson(4L * 1024 * 1024);
        NpmPackage pkg = NpmPackage.fromStream(
                new ByteArrayInputStream(data), "bigjson.tgz", Limits.streamPass(1024 * 1024L));

        try (PackageEntryStream stream = pkg.streamEntries()) {
            assertThat(stream.hasNext()).isTrue();
            stream.nextEntry();
            // Lazy: openStream() itself must not consume the entry (no budget trip here).
            try (java.io.InputStream content = stream.openStream()) {
                // The trip happens on the first read of the oversized content.
                assertThatThrownBy(content::readAllBytes)
                        .isInstanceOf(AnnattoException.SecurityException.class)
                        .as("entry content larger than the (small) pass budget must be rejected on read");
            }
            // The pass is now spent; the contract is fail-fast on further use so consumers
            // never observe silently truncated content.
            assertThatThrownBy(stream::hasNext)
                    .isInstanceOf(AnnattoException.SecurityException.class)
                    .as("a spent pass budget must fail closed on subsequent iteration");
        } finally {
            pkg.close();
        }
    }

    @Test
    @DisplayName("openStream returns content matching declared size with clean EOF (guard)")
    void streamEntriesOpenStreamBoundedPerEntry() throws IOException {
        byte[] data = validNpmTgz();
        NpmPackage pkg = NpmPackage.fromStream(new ByteArrayInputStream(data), "guard.tgz", Limits.DEFAULT);

        try (PackageEntryStream stream = pkg.streamEntries()) {
            while (stream.hasNext()) {
                PackageEntry entry = stream.nextEntry();
                if (entry.isRegularFile() && entry.size() >= 0) {
                    try (java.io.InputStream content = stream.openStream()) {
                        long read = content.readAllBytes().length;
                        assertThat(read).isEqualTo(entry.size());
                    }
                }
            }
        } finally {
            pkg.close();
        }
    }

    // ================================================================
    // close() semantics (S-5) and limit isolation
    // ================================================================

    @Test
    @DisplayName("close() closes the package; streamEntries() afterwards throws")
    void closeMarksPackageClosedAndDeletesSpool() throws IOException {
        var before = TempDirAsserter.snapshotAnnattoFiles();
        NpmPackage pkg = NpmPackage.fromStream(new ByteArrayInputStream(validNpmTgz()), "pkg.tgz", Limits.DEFAULT);

        try (PackageEntryStream s = pkg.streamEntries()) {
            assertThat(s.hasNext()).isTrue();
        }
        pkg.close();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(pkg::streamEntries)
                .as("streamEntries() of a closed package must throw (S-5)")
                .withMessageContaining("closed");

        // close() must have released the owned spool (snapshot-diff; meaningful once spooling lands).
        TempDirAsserter.assertNoNewFilesSince(before);
    }

    @Test
    @DisplayName("limits are per-instance and do not leak between packages")
    void noRegisteredStateLeakBetweenPackages() throws IOException {
        byte[] data = npmTgzWithIncompressibleEntry(3L * 1024 * 1024);

        // Package A: tight spool bound -> the same stream must be rejected...
        assertThatThrownBy(() -> NpmPackage.fromStream(
                new ByteArrayInputStream(data), "a.tgz", Limits.spool(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class);

        // ...while package B with a generous bound accepts it.
        LanguagePackage pkgB = NpmPackage.fromStream(
                new ByteArrayInputStream(data), "b.tgz", Limits.spool(64L * 1024 * 1024));
        try {
            assertThat(pkgB.name()).isEqualTo("npm-fixture");
        } finally {
            pkgB.close();
        }
    }

    @Test
    @DisplayName("double close and close-after-failure are safe (guard)")
    void doubleCloseAndCloseAfterFailureAreSafe() throws IOException {
        NpmPackage pkg = NpmPackage.fromStream(new ByteArrayInputStream(validNpmTgz()), "guard.tgz", Limits.DEFAULT);
        pkg.close();
        assertThatCode(pkg::close).doesNotThrowAnyException();
        assertThatCode(pkg::close).doesNotThrowAnyException();
    }

    // ================================================================
    // 7b-only mechanisms (deferred from 7a: source-backed reader
    // construction + the aggregate spool budget)
    // ================================================================

    @Test
    @DisplayName("a failed entry-stream construction does not permanently lock the package")
    void constructorFailureResetsStreamFlag(@TempDir Path tempDir) throws IOException {
        // fromPath reads directly from the caller's file; delete it so the reader
        // construction fails - the streamOpen flag must NOT be left set.
        Path pkgFile = tempDir.resolve("gone.tgz");
        Files.write(pkgFile, validNpmTgz());
        NpmPackage pkg = NpmPackage.fromPath(pkgFile);

        Files.delete(pkgFile);
        assertThatThrownBy(pkg::streamEntries)
                .as("opening a stream over a deleted source must fail")
                .isInstanceOf(IOException.class);

        // The flag was restored: restoring the file lets a stream open normally (P0-E).
        Files.write(pkgFile, validNpmTgz());
        try (PackageEntryStream s = pkg.streamEntries()) {
            assertThat(s.hasNext()).isTrue();
        }
        pkg.close();
    }

    @Test
    @DisplayName("the aggregate spool budget is reserved upfront and fails closed before the copy")
    void aggregateBudgetAdjoint() throws IOException {
        // Two ~2MiB-compressed npm fixtures: with a 3MiB aggregate budget, the second spool
        // must fail at ADMISSION (reserve) time, before any bytes hit disk.
        byte[] data = npmTgzWithIncompressibleEntry(2L * 1024 * 1024);
        long cap = data.length + 1; // per-file spool cap >= actual file size
        assertThat(cap * 2L)
                .as("two ~2MiB spools must exceed the 3MiB aggregate budget for this test to be meaningful")
                .isGreaterThan(3L * 1024 * 1024);

        try (Spool.BudgetHandle handle = Spool.overrideBudgetForTesting(new AggregateSpoolBudget(3L * 1024 * 1024))) {
            // First spool: reserves cap, copies fully, net charge = actual bytes.
            Spool.Spooled first = Spool.create(new ByteArrayInputStream(data), "a.pkg", cap);
            try {
                // Second spool would push the aggregate past 3MiB -> fail closed at admission.
                assertThatThrownBy(() -> Spool.create(new ByteArrayInputStream(data), "b.pkg", cap))
                        .isInstanceOf(AnnattoException.SecurityException.class)
                        .hasMessageContaining("spool budget");
            } finally {
                Spool.delete(first);
            }
            // After releasing the first spool, the aggregate is free again.
            Spool.Spooled third = Spool.create(new ByteArrayInputStream(data), "c.pkg", cap);
            Spool.delete(third);
        }
    }
}