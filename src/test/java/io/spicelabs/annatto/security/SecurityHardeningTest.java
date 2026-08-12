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
import io.spicelabs.annatto.PackageEntry;
import io.spicelabs.annatto.PackageEntryStream;
import io.spicelabs.annatto.LanguagePackageReader;
import io.spicelabs.annatto.ecosystem.npm.NpmPackage;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import io.spicelabs.annatto.testutil.TempDirAsserter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Security-hardening tests (Phase 7, plan-phase-7.md, group E).
 *
 * <p>Theory (WHAT): Annatto processes untrusted archives; error messages must not leak host
 * paths, and archive entry-name surfaces must reject control characters, backslash-based
 * traversal attempts, and unsafe symlink targets.
 *
 * <p>Why (requirement): ADR-005 message sanitization plus the Phase 7 hardening items:
 * basename-only filenames (never the caller/temp path), {@code \r}/{@code \n} and backslash
 * normalization in {@link io.spicelabs.annatto.internal.PathValidator}, symlink-target
 * validation at {@code openStream()}, and no {@code annatto-*} temp residue.
 *
 * <p>RED/GREEN: message-leak, CR/LF, backslash-traversal, and unsafe-symlink tests are RED
 * now; the residue guard is GREEN before and after.
 */
class SecurityHardeningTest {

    // ================================================================
    // Error messages must not leak host paths (ADR-005)
    // ================================================================

    @Test
    @DisplayName("malformed/unroutable messages contain the basename, never the full path")
    void spoolScanMessagesDoNotLeakPaths(@TempDir Path tempDir) throws IOException {
        // A crate-named gzip with no Cargo.toml: routing now fails closed with
        // UnknownFormatException (Bug 1), and the message must use the basename only.
        byte[] noCargo = ArchiveBuilder.gzipTar(ArchiveBuilder.Entry.of("whatever/file.txt", "x"));
        Path parent = tempDir.resolve("deep").resolve("nested").resolve("fixture-dir");
        Files.createDirectories(parent);
        Path crate = parent.resolve("fake-1.0.0.crate");
        Files.write(crate, noCargo);
        String parentStr = parent.toString();

        assertThatThrownBy(() -> LanguagePackageReader.read(crate))
                .isInstanceOf(AnnattoException.UnknownFormatException.class)
                .satisfies(throwable -> {
                    String message = throwable.getMessage();
                    // Sanitization: only the basename may appear, never surrounding host paths.
                    assertThat(message)
                            .as("message must not embed the caller path container: %s", parentStr)
                            .doesNotContain(parentStr);
                    assertThat(message)
                            .as("message must still identify the file by basename")
                            .contains("fake-1.0.0.crate");
                    assertThat(message)
                            .as("message must not leak /tmp or home prefixes")
                            .doesNotContain("/tmp/", "/home/");
                });
    }

    // ================================================================
    // Entry-name hardening (PathValidator extensions)
    // ================================================================

    @Test
    @DisplayName("entry names with CR or LF characters are rejected")
    void entryNameCarriageReturnRejected() throws IOException {
        byte[] tgz = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", "{\"name\":\"x\",\"version\":\"1.0.0\"}"),
                ArchiveBuilder.Entry.of("package/evil\r\n.js", "malicious"));
        LanguagePackage pkg = NpmPackage.fromStream(new ByteArrayInputStream(tgz), "evil.tgz", Limits.DEFAULT);

        try (PackageEntryStream stream = pkg.streamEntries()) {
            assertThat(stream.hasNext()).isTrue();
            // The first entry (package.json) is still fine...
            PackageEntry first = stream.nextEntry();
            assertThat(first.name()).isEqualTo("package/package.json");

            // ...but the entry carrying control characters must be rejected.
            assertThat(stream.hasNext()).isTrue();
            assertThatThrownBy(stream::nextEntry)
                    .isInstanceOf(AnnattoException.SecurityException.class)
                    .as("entry names containing CR/LF must be rejected (log-injection guard)");
        } finally {
            pkg.close();
        }
    }

    @Test
    @DisplayName("backslash-based traversal attempts are rejected")
    void backslashTraversalRejected() throws IOException {
        // "a\..\..\secret": on POSIX the backslash is not a separator, so a naive
        // path-normalization check misses the ".." segments. It must still be rejected.
        byte[] tgz = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", "{\"name\":\"x\",\"version\":\"1.0.0\"}"),
                ArchiveBuilder.Entry.of("a\\..\\..\\secret", "data"));
        LanguagePackage pkg = NpmPackage.fromStream(new ByteArrayInputStream(tgz), "backslash.tgz", Limits.DEFAULT);

        try (PackageEntryStream stream = pkg.streamEntries()) {
            assertThat(stream.hasNext()).isTrue();
            stream.nextEntry(); // package.json
            assertThat(stream.hasNext()).isTrue();
            assertThatThrownBy(stream::nextEntry)
                    .isInstanceOf(AnnattoException.SecurityException.class)
                    .as("backslash-based traversal must be rejected after normalization");
        } finally {
            pkg.close();
        }
    }

    @Test
    @DisplayName("opening a symlink whose target escapes the archive is rejected")
    void unsafeSymlinkOpenStreamRejected() throws IOException {
        byte[] tgz = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", "{\"name\":\"x\",\"version\":\"1.0.0\"}"),
                ArchiveBuilder.Entry.symlink("package/evil-link", "../../etc/passwd"));
        LanguagePackage pkg = NpmPackage.fromStream(new ByteArrayInputStream(tgz), "symlink.tgz", Limits.DEFAULT);

        try (PackageEntryStream stream = pkg.streamEntries()) {
            while (stream.hasNext()) {
                PackageEntry entry = stream.nextEntry();
                if (entry.name().equals("package/evil-link")) {
                    assertThatThrownBy(() -> stream.openStream())
                            .isInstanceOf(AnnattoException.SecurityException.class)
                            .as("unsafe symlink content must be refused at openStream()");
                }
            }
        } finally {
            pkg.close();
        }
    }

    // ================================================================
    // Temp hygiene guard
    // ================================================================

    @Test
    @DisplayName("no annatto-* temp residue after a workload of successes and failures")
    void tempResidueNoneOutsidePrivateDir() throws IOException {
        // RED on the overflow branch (no SecurityException today); the residue half is a guard.
        var before = TempDirAsserter.snapshotAnnattoFiles();
        byte[] valid = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", "{\"name\":\"x\",\"version\":\"1.0.0\"}"));
        byte[] noJson = ArchiveBuilder.gzipTar(ArchiveBuilder.Entry.of("package/README.md", "# x"));
        byte[] overflow = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", "{\"name\":\"x\",\"version\":\"1.0.0\"}"),
                ArchiveBuilder.Entry.random("package/data.bin", 3L * 1024 * 1024));

        LanguagePackage pkg = NpmPackage.fromStream(new ByteArrayInputStream(valid), "ok.tgz", Limits.DEFAULT);
        pkg.close();

        assertThatThrownBy(() -> NpmPackage.fromStream(
                new ByteArrayInputStream(noJson), "nojson.tgz", Limits.DEFAULT))
                .isInstanceOf(AnnattoException.MalformedPackageException.class);
        assertThatThrownBy(() -> NpmPackage.fromStream(
                new ByteArrayInputStream(overflow), "overflow.tgz", Limits.spool(1024 * 1024L)))
                .isInstanceOf(AnnattoException.SecurityException.class);

        TempDirAsserter.assertNoNewFilesSince(before);
    }
}