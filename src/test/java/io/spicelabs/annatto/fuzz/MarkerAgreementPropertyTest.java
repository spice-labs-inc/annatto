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

import io.spicelabs.annatto.Ecosystem;
import io.spicelabs.annatto.EcosystemRouter;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import net.jqwik.api.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property: router NPM classification must agree with strict top-level npm-marker semantics
 * (Phase 7, plan-phase-7.md, group F; red-to-green).
 *
 * <p>Theory (WHAT): for ANY set of archive entries, {@code EcosystemRouter.route} must
 * classify the archive as NPM exactly when at least one entry is the strict npm marker
 * ({@code package.json} at the root or exactly one directory deep, excluding traversal
 * segments). The current content disambiguator violates this by accepting nested
 * {@code …/package/package.json} paths via {@code endsWith} while rejecting root and
 * custom-prefix markers (e.g. {@code package.json}, {@code some-name-1.0.0/package.json}).
 *
 * <p>Why (requirement): routing and extraction must never disagree; a generic tar.gz must
 * never be treated as npm.
 *
 * <p>RED: every generated list includes exactly one "probe" drawn from a set of entry names
 * that are GENUINELY divergent under the current implementation (root marker, custom-prefix
 * marker, nested marker), so any single evaluation reproduces the disagreement. The seed is
 * pinned in the {@code @Property} annotation for CI determinism. Note: the probe exercises the
 * CONTENT path (the file is named {@code .tar.gz}); the {@code .tgz} filename shortcut is
 * exercised by the example tests in {@code TgzNotNpmRegressionTest}.
 */
class MarkerAgreementPropertyTest {

    private static final List<String> PROBES = List.of(
            "package.json",                              // strict marker; router today: not NPM
            "some-name-1.0.0/package.json",              // strict marker (custom prefix); router today: not NPM
            "node_modules/lodash/package/package.json"); // nested; router today: NPM (endsWith)

    private static final List<String> FILLER = List.of(
            "package/package.json",   // strict marker (agrees)
            "a/b/package.json",       // nested (agrees: not a marker)
            "README.md",
            "src/main.c",
            "package/lib/index.js");

    private static boolean strictNpmMarker(String entryName) {
        String n = entryName.replace('\\', '/');
        if (n.equals(".") || n.equals("..") || n.startsWith("./") || n.startsWith("../")
                || n.contains("/../") || n.contains("/./")) {
            return false;
        }
        if (n.equals("package.json")) {
            return true;
        }
        if (n.endsWith("/package.json")) {
            String dir = n.substring(0, n.length() - "/package.json".length());
            return !dir.contains("/");
        }
        return false;
    }

    @Provide
    Arbitrary<List<String>> markerShapedEntryLists() {
        // 1 probe (guaranteed divergent under the current implementation) + 0..3 filler.
        Arbitrary<String> probe = Arbitraries.of(PROBES);
        Arbitrary<List<String>> filler = Arbitraries.of(FILLER).list().ofMaxSize(3);
        return Combinators.combine(probe, filler).as((p, f) -> {
            List<String> combined = new ArrayList<>(f);
            combined.add(0, p);
            return combined;
        });
    }

    @Property(tries = 20, seed = "12345")
    @Label("router classifies as NPM exactly when a strict top-level npm marker is present")
    boolean routingAgreesWithStrictMarkerSemantics(
            @ForAll("markerShapedEntryLists") List<String> entryNames) throws IOException {
        ArchiveBuilder.Entry[] entries = entryNames.stream()
                .map(name -> ArchiveBuilder.Entry.of(name, "{}"))
                .toArray(ArchiveBuilder.Entry[]::new);
        byte[] tar = ArchiveBuilder.gzipTar(entries);

        Path temp = Files.createTempFile("marker-prop-", ".tar.gz");
        try {
            Files.write(temp, tar);
            Optional<Ecosystem> routed = EcosystemRouter.route(temp);
            boolean routedAsNpm = routed.isPresent() && routed.get() == Ecosystem.NPM;
            boolean hasStrictMarker = entryNames.stream().anyMatch(MarkerAgreementPropertyTest::strictNpmMarker);

            assertThat(routedAsNpm)
                    .as("router NPM classification for %s must equal strict-marker presence (strict=%s)",
                            entryNames, hasStrictMarker)
                    .isEqualTo(hasStrictMarker);
            return true;
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}