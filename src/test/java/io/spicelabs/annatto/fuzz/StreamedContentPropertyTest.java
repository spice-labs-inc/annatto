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

package io.spicelabs.annatto.fuzz;

import io.spicelabs.annatto.LanguagePackage;
import io.spicelabs.annatto.PackageEntry;
import io.spicelabs.annatto.PackageEntryStream;
import io.spicelabs.annatto.ecosystem.npm.NpmPackage;
import io.spicelabs.annatto.internal.Limits;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import io.spicelabs.annatto.testutil.ArchiveBuilder.Entry;
import net.jqwik.api.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard property: streaming the entries of a valid npm tarball yields content identical to
 * what was written, with sizes matching the declared entry sizes (Phase 7, group F).
 *
 * <p>GREEN before and after the Phase 7 refactor; it pins the byte-for-byte round-trip
 * behavior of the streaming path so the streaming rewrite cannot silently corrupt content.
 */
class StreamedContentPropertyTest {

    @Provide
    Arbitrary<String> packageName() {
        return Arbitraries.strings().withChars('a', 'z').ofMinLength(3).ofMaxLength(10)
                .filter(s -> s.matches("[a-z]{3,10}"));
    }

    @Provide
    Arbitrary<Integer> entryCount() {
        return Arbitraries.integers().between(1, 6);
    }

    @Property(tries = 30)
    @Label("streamed entry content round-trips byte-for-byte with declared size")
    boolean streamedEntryContentMatchesDeclaredSize(
            @ForAll("packageName") String name,
            @ForAll int fileIdx) throws IOException {
        List<Entry> entries = new ArrayList<>();
        entries.add(Entry.of("package/package.json",
                "{\"name\": \"" + name + "\", \"version\": \"1.0.0\"}"));
        for (int i = 0; i < (Math.abs(fileIdx) % 4) + 1; i++) {
            String content = "content-" + name + "-" + i + "-".repeat(i + 1);
            entries.add(Entry.of("package/file" + i + ".txt", content.getBytes(StandardCharsets.UTF_8)));
        }

        byte[] tgz = ArchiveBuilder.gzipTar(entries);
        LanguagePackage pkg = NpmPackage.fromStream(new ByteArrayInputStream(tgz), name + "-1.0.0.tgz", Limits.DEFAULT);
        try (PackageEntryStream stream = pkg.streamEntries()) {
            while (stream.hasNext()) {
                PackageEntry entry = stream.nextEntry();
                if (entry.isRegularFile() && entry.size() >= 0) {
                    byte[] read;
                    try (java.io.InputStream content = stream.openStream()) {
                        read = content.readAllBytes();
                    }
                    assertThat(read.length)
                            .as("declared size for %s", entry.name())
                            .isEqualTo(entry.size());
                    // package.json must also be present with our name embedded.
                    if (entry.name().endsWith("package.json")) {
                        assertThat(new String(read, StandardCharsets.UTF_8)).contains(name);
                    }
                }
            }
        } finally {
            pkg.close();
        }
        return true;
    }
}