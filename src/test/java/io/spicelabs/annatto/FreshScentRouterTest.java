/* Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spicelabs.annatto;

import io.spicelabs.annatto.testutil.ArchiveBuilder;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Router boundary tests (Fresh Scent Phase 3, finding A3 + A4 contract).
 *
 * <p>Lives in package {@code io.spicelabs.annatto} so it can use the package-private
 * {@link EcosystemRouter#scanPlainTar} seam for small-bound entry-cap tests.
 */
class FreshScentRouterTest {

    // Requirement: catalog §9 / finding A3 / plan Phase 3.1
    // Theory: the OLD mark/reset disambiguation threw IOException ("Resetting to invalid
    //         mark") for any unrecognized plain tar larger than the 8 KB mark buffer. The
    //         spool-and-rescan rewrite must return Optional.empty() for such archives.
    // Revert-check: restoring mark/reset makes route() throw here.
    @Test
    void unrecognizedTarOver8kReturnsEmpty(@TempDir Path dir) throws IOException {
        List<ArchiveBuilder.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(ArchiveBuilder.Entry.of("file-" + i + ".txt", "x".repeat(600)));
        }
        byte[] tar = ArchiveBuilder.plainTar(entries);
        // ".dat" avoids filename-based routing (".tar" routes to HEX by name); content
        // detection must find the tar magic and route via the plain-tar path.
        Path file = dir.resolve("bundle.dat");
        Files.write(file, tar);

        Optional<Ecosystem> result = EcosystemRouter.route(file);
        assertThat(result).isEmpty();
    }

    // Requirement: catalog §9 / finding A3 / plan Phase 3.1
    // Theory: plain-tar disambiguation must respect the entry-count cap (fail closed) just
    //         like the gzip-tar sibling path — the package-private seam lets the test use a
    //         small bound.
    // Revert-check: removing the count check makes this return HEX/RUBYGEMS-detection or
    //               scan all entries.
    @Test
    void plainTarEntryCapFailClosed(@TempDir Path dir) throws IOException {
        // The HEX marker sits at position 4: a cap of 3 must fail closed BEFORE it is
        // seen; a cap of 5 must reach it.
        byte[] tar = ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.of("a.txt", "a"),
                ArchiveBuilder.Entry.of("b.txt", "b"),
                ArchiveBuilder.Entry.of("c.txt", "c"),
                ArchiveBuilder.Entry.of("metadata.config", "%{<<\"name\">>,<<\"hex\">>}.\n".getBytes()));
        Path file = dir.resolve("hexish.tar");
        Files.write(file, tar);

        try (TarArchiveInputStream tais = new TarArchiveInputStream(
                new java.io.ByteArrayInputStream(tar))) {
            assertThat(EcosystemRouter.scanPlainTar(tais, 3)).isEmpty();
        }
        try (TarArchiveInputStream tais = new TarArchiveInputStream(
                new java.io.ByteArrayInputStream(tar))) {
            assertThat(EcosystemRouter.scanPlainTar(tais, 5))
                    .contains(Ecosystem.HEX);
        }
    }

    // Requirement: plain-tar marker detection still works (regression guard) / plan Phase 3.1
    @Test
    void plainTarMarkersStillDetected(@TempDir Path dir) throws IOException {
        byte[] gemTar = ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.of("metadata.gz", "x"),
                ArchiveBuilder.Entry.of("data.tar.gz", "y"));
        Path gem = dir.resolve("pkg.gem");
        Files.write(gem, gemTar);
        assertThat(EcosystemRouter.route(gem)).contains(Ecosystem.RUBYGEMS);
    }

    // Requirement: catalog §7 / finding A4 / plan Phase 3.2 (documented exception contract)
    // Theory: a GZIP archive with NO entries is structurally invalid; route() documents the
    //         unchecked MalformedPackageException — the test pins the contract so javadoc
    //         and behavior cannot drift.
    // Revert-check: catching the exception at the router boundary (fail-closed empty) would
    //               silently classify hostile input — this test guards the loud contract.
    @Test
    void gzipWithNoEntriesThrowsDocumentedMalformed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("empty.tgz");
        try (java.io.OutputStream out = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(ArchiveBuilder.plainTar(List.of()));
        }
        assertThatThrownBy(() -> EcosystemRouter.route(file))
                .isInstanceOf(AnnattoException.MalformedPackageException.class);
    }
}
