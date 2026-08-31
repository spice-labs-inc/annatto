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
package io.spicelabs.annatto.ecosystem;

import io.spicelabs.annatto.AnnattoException;
import io.spicelabs.annatto.PackageEntry;
import io.spicelabs.annatto.PackageEntryStream;
import io.spicelabs.annatto.ecosystem.go.GoPackage;
import io.spicelabs.annatto.ecosystem.hex.HexPackage;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end pins for the lazy bounded {@code openStream()} rewrite (Fresh Scent Phase 2).
 *
 * <p>These tests pin laziness the way the old eager code CANNOT pass: the old
 * {@code ByteArrayOutputStream} materialization consumed the entry (and charged the pass
 * budget) inside {@code openStream()} itself, so a test that observes the budget NOT being
 * consumed until the first read only passes on the lazy implementation.
 */
class FreshScentEntryStreamTest {

    private static final String META_CONFIG =
            "{<<\"name\">>,<<\"hex\">>}.\n{<<\"version\">>,<<\"1.0.0\">>}.\n";

    private static PackageEntry seek(PackageEntryStream entries, String name) throws IOException {
        while (entries.hasNext()) {
            PackageEntry entry = entries.nextEntry();
            if (entry.name().equals(name)) {
                return entry;
            }
        }
        throw new AssertionError("entry not found: " + name);
    }

    // Requirement: catalog §2 / finding A2 / plan Phase 2.1 (zip chain, case c)
    // Theory: openStream() must not consume entry content (lazy). With a per-pass budget
    //         smaller than the entry, the OLD eager code trips the budget inside
    //         openStream(); the lazy code returns the view and trips on the first read.
    // Boundaries: pass cap < entry size.
    // Revert-check: restoring the eager BAOS loop makes openStream() throw here (red).
    @Test
    void zipOpenStreamIsLazyAndChargesOnRead() throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("a.txt", "A".repeat(100).getBytes());
        entries.put("example.com/mod/go.mod", "module example.com/mod\n".getBytes());
        byte[] zip = ArchiveBuilder.zip(entries);
        GoPackage pkg = GoPackage.fromStream(new ByteArrayInputStream(zip), "mod.zip",
                Limits.zipPass(50));
        try {
            try (PackageEntryStream es = pkg.streamEntries()) {
                seek(es, "a.txt");
                InputStream in = es.openStream(); // must NOT throw (lazy)
                assertThatThrownBy(in::readAllBytes)
                        .isInstanceOf(AnnattoException.SecurityException.class);
            }
        } finally {
            pkg.close();
        }
    }

    // Requirement: catalog §2 / finding A2 / plan Phase 2.1 (raw-tar chain, case b)
    // Theory: Hex previously had NO per-pass budget at all — hostile gems streamed
    //         unbounded. The added budget must trip on read.
    // Revert-check: removing the case-b budget (withPassBudget) makes readAllBytes succeed.
    @Test
    void rawTarPassBudgetChargedOnRead() throws IOException {
        byte[] tar = ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.of("metadata.config", META_CONFIG.getBytes()),
                ArchiveBuilder.Entry.of("b.txt", "B".repeat(100)));
        HexPackage pkg = HexPackage.fromStream(new ByteArrayInputStream(tar), "pkg.tar",
                Limits.streamPass(50));
        try {
            try (PackageEntryStream es = pkg.streamEntries()) {
                seek(es, "b.txt");
                InputStream in = es.openStream();
                assertThatThrownBy(in::readAllBytes)
                        .isInstanceOf(AnnattoException.SecurityException.class);
            }
        } finally {
            pkg.close();
        }
    }

    // Requirement: catalog §6 / finding A2 (RT#6) / plan Phase 2.3
    // Theory: a raw tar truncated INSIDE entry content must be reported as an error, not
    //         returned as silently partial data (the old BAOS loop returned the partial
    //         bytes with no error signal).
    // Boundaries: truncate at header+half-content; exact-size positive control below.
    // Revert-check: restoring the eager loop makes readAllBytes return partial bytes (red).
    @Test
    void truncatedRawTarEntryThrows() throws IOException {
        byte[] full = ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.of("metadata.config", META_CONFIG.getBytes()),
                ArchiveBuilder.Entry.of("c.txt", "C".repeat(100)));
        // Cut inside c.txt's data block (header1 512 + data1 512 + header2 512 + 50 data bytes).
        byte[] truncated = Arrays.copyOf(full, 512 + 512 + 512 + 50);
        HexPackage pkg = HexPackage.fromStream(new ByteArrayInputStream(truncated), "pkg.tar");
        try {
            try (PackageEntryStream es = pkg.streamEntries()) {
                seek(es, "c.txt");
                InputStream in = es.openStream();
                assertThatThrownBy(in::readAllBytes).isInstanceOf(IOException.class);
            }
        } finally {
            pkg.close();
        }
    }

    // Requirement: positive control (exact EOF) / plan Phase 2
    // Theory: the same fixture NOT truncated must read cleanly — the strictness fix must
    //         not false-positive on exact-size content.
    @Test
    void exactSizeRawTarEntryReadsCleanly() throws IOException {
        byte[] full = ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.of("metadata.config", META_CONFIG.getBytes()),
                ArchiveBuilder.Entry.of("c.txt", "C".repeat(100)));
        HexPackage pkg = HexPackage.fromStream(new ByteArrayInputStream(full), "pkg.tar");
        try {
            try (PackageEntryStream es = pkg.streamEntries()) {
                seek(es, "c.txt");
                InputStream in = es.openStream();
                assertThat(new String(in.readAllBytes())).isEqualTo("C".repeat(100));
            }
        } finally {
            pkg.close();
        }
    }

    // Requirement: plan Phase 2.2
    // Theory: closing the returned entry-content view must NOT break the shared archive —
    //         the next entry must still be reachable (the old BoundedInputStream.close()
    //         closed the delegate; the new view deliberately does not).
    @Test
    void closingEntryViewKeepsArchiveUsable() throws IOException {
        byte[] tar = ArchiveBuilder.plainTar(
                ArchiveBuilder.Entry.of("metadata.config", META_CONFIG.getBytes()),
                ArchiveBuilder.Entry.of("a.txt", "aaa"),
                ArchiveBuilder.Entry.of("b.txt", "bbb"));
        HexPackage pkg = HexPackage.fromStream(new ByteArrayInputStream(tar), "pkg.tar");
        try {
            try (PackageEntryStream es = pkg.streamEntries()) {
                seek(es, "a.txt");
                InputStream in = es.openStream();
                in.close();
                assertThat(es.hasNext()).isTrue();
                assertThat(es.nextEntry().name()).isEqualTo("b.txt");
            }
        } finally {
            pkg.close();
        }
    }
}
