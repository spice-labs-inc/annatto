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

package io.spicelabs.annatto.disambiguation;

import io.spicelabs.annatto.AnnattoException;
import io.spicelabs.annatto.Ecosystem;
import io.spicelabs.annatto.EcosystemRouter;
import io.spicelabs.annatto.LanguagePackage;
import io.spicelabs.annatto.LanguagePackageReader;
import io.spicelabs.annatto.testutil.ArchiveBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/**
 * Regression tests for the Goat Rodeo survey incident (Phase 7, plan-phase-7.md, Bug 1).
 *
 * <p>Theory (WHAT): a generic tar.gz archive must never be treated as an npm package merely
 * because its name ends in {@code .tgz}, and the content markers used during routing must
 * match the strict semantics of the metadata extractors (top-level {@code package.json}
 * under a single directory), not a loose {@code endsWith} check that fires for nested
 * {@code …/package/package.json} paths.
 *
 * <p>Why (requirement): a 4.9 GiB generic bundle named {@code repo_ea.tgz} was routed to npm
 * by filename and OOM'd the JVM at Java's byte[] limit. These tests pin down the routing
 * contract so the same incident cannot recur.
 *
 * <p>LLM note: every fixture is synthetic and small (no corpus download). A marker test's
 * entry-order matters only for bomb fixtures (see ArchiveBuilder javadoc).
 *
 * <p>RED/GREEN status: motivated to be RED against the current implementation (the
 * extension-only routing at EcosystemRouter.routeFromFilenameOnly and the loose
 * endsWith markers in EcosystemRouter.disambiguateGzipTar). Guards (marked as such) are
 * GREEN before and after.
 */
class TgzNotNpmRegressionTest {

    private static final String PKG_JSON = "{\"name\": \"npm-fixture\", \"version\": \"1.0.0\"}";

    /**
     * Strict top-level npm marker semantics (mirrors the documented extraction rule:
     * {@code package.json} at the root or exactly one directory deep, and never a path
     * containing a traversal segment). This test-local predicate is the Phase-7a stand-in
     * for the planned shared marker, which MUST also reject traversal segments so that a
     * name like {@code ../package.json} can never act as a marker.
     */
    private static boolean strictNpmMarker(String entryName) {
        String n = entryName.replace('\\', '/');
        if (!isMarkerCandidate(n)) {
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

    private static boolean isMarkerCandidate(String n) {
        return !n.equals(".") && !n.equals("..") && !n.startsWith("./") && !n.startsWith("../")
                && !n.contains("/../") && !n.contains("/./");
    }

    // ================================================================
    // Filename-extension shortcut must not imply NPM
    // ================================================================

    @Test
    @DisplayName("generic .tgz content is not routed to NPM")
    void tgzWithGenericContentIsNotRoutedToNpm(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("repo_ea/README.md", "# Repo"),
                ArchiveBuilder.Entry.of("repo_ea/src/main.c", "int main(){return 0;}"));
        Path pkg = write(tempDir, "repo_ea.tgz", tar);

        Optional<Ecosystem> result = EcosystemRouter.route(pkg);
        assertThat(result)
                .as("generic .tgz with no npm marker must not be classified as npm")
                .isEmpty();
    }

    @Test
    @DisplayName("read(Path) refuses a generic .tgz with UnknownFormatException")
    void readPathWithGenericTgzThrowsUnknownFormat(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("repo_ea/README.md", "# Repo"),
                ArchiveBuilder.Entry.of("repo_ea/src/main.c", "int main(){return 0;}"));
        Path pkg = write(tempDir, "repo_ea.tgz", tar);

        assertThatExceptionOfType(AnnattoException.UnknownFormatException.class)
                .isThrownBy(() -> LanguagePackageReader.read(pkg))
                .as("generic .tgz must not be parsed as npm; it is an unknown format")
                .withMessageContaining("Cannot determine ecosystem");

        assertThatExceptionOfType(AnnattoException.UnknownFormatException.class)
                .isThrownBy(() -> LanguagePackageReader.read(pkg, "application/gzip"));
    }

    @Test
    @DisplayName("repo_ea-shaped tgz with nested package/package.json is not NPM")
    void repoEaLikeTgzNotNpm(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        // Incident shape: a repo bundle whose node_modules contains a nested
        // package/package.json deep inside the tree.
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("repo_ea/README.md", "# Repo"),
                ArchiveBuilder.Entry.of("repo_ea/src/main.c", "int main(){return 0;}"),
                ArchiveBuilder.Entry.of("repo_ea/node_modules/lodash/package/package.json", PKG_JSON));
        Path pkg = write(tempDir, "repo_ea.tgz", tar);

        Optional<Ecosystem> result = EcosystemRouter.route(pkg);
        assertThat(result)
                .as("a nested node_modules package/package.json is not an npm package marker")
                .isEmpty();
    }

    @Test
    @DisplayName("routeFromFilename(x.tgz, application/gzip) is ambiguous")
    void routeFromFilenameWithTgzMimeIsAmbiguous() {
        assertThat(EcosystemRouter.routeFromFilename("repo_ea.tgz", "application/gzip"))
                .as(".tgz must be ambiguous at the name layer; content inspection is required")
                .isEmpty();
    }

    // ================================================================
    // Content markers must be strict (top-level only)
    // ================================================================

    @Test
    @DisplayName("nested <dir>/<dir>/package.json is not an npm marker")
    void nestedPackageJsonNotTreatedAsNpmMarker(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        // Content-driven path: .tar.gz name means the router uses the disambiguator.
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("repo_ea/monorepo/package/package.json", PKG_JSON),
                ArchiveBuilder.Entry.of("repo_ea/README.md", "# Repo"));
        Path pkg = write(tempDir, "repo_ea.tar.gz", tar);

        Optional<Ecosystem> result = EcosystemRouter.route(pkg);
        assertThat(result)
                .as("a package.json nested deeper than one directory is not an npm marker")
                .isEmpty();
    }

    @Test
    @DisplayName("a directory entry named x/package.json is not an npm marker")
    void directoryEntryNamedPackageJsonIsNotMarker(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.directory("package/package.json"),
                ArchiveBuilder.Entry.of("README.md", "# Repo"));
        Path pkg = write(tempDir, "dir.tar.gz", tar);

        Optional<Ecosystem> result = EcosystemRouter.route(pkg);
        assertThat(result)
                .as("directory entries must not be treated as npm markers")
                .isEmpty();
    }

    @Test
    @DisplayName("nested PKG-INFO is not a PyPI marker")
    void nestedPkgInfoNotRoutedToPypi(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("repo/vendor/PKG-INFO", "Name: x\nVersion: 1.0\n"));
        Path pkg = write(tempDir, "bundle.tar.gz", tar);

        assertThat(EcosystemRouter.route(pkg))
                .as("a PKG-INFO nested beyond the distribution root is not a PyPI sdist marker")
                .isNotEqualTo(Optional.of(Ecosystem.PYPI));
    }

    @Test
    @DisplayName("nested META.yml is not a CPAN marker")
    void nestedMetaYmlNotRoutedToCpan(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("stuff/deep/META.yml", "name: X\nversion: 1.0\n"));
        Path pkg = write(tempDir, "bundle.tar.gz", tar);

        assertThat(EcosystemRouter.route(pkg))
                .as("a META.yml nested beyond the distribution root is not a CPAN marker")
                .isNotEqualTo(Optional.of(Ecosystem.CPAN));
    }

    @Test
    @DisplayName("nested Cargo.toml is not a Crates marker")
    void nestedCargoTomlNotRoutedToCrates(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("workspace/sub/Cargo.toml", "[package]\nname = \"x\"\nversion = \"1.0\"\n"));
        Path pkg = write(tempDir, "bundle.tar.gz", tar);

        assertThat(EcosystemRouter.route(pkg))
                .as("a Cargo.toml deeper than <crate-dir>/Cargo.toml is not a crates marker")
                .isNotEqualTo(Optional.of(Ecosystem.CRATES));
    }

    @Test
    @DisplayName("generic .crate content is not routed to CRATES")
    void crateGenericContentNotRoutedToCrates(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("whatever/file.txt", "hello"));
        Path pkg = write(tempDir, "bundle-1.0.0.crate", tar);

        assertThat(EcosystemRouter.route(pkg))
                .as("a .crate file without a top-level Cargo.toml is not a rust crate")
                .isEmpty();
    }

    // ================================================================
    // Router and extractor agreement on the npm marker
    // ================================================================

    static Stream<Object[]> markerBoundaryCases() {
        return Stream.of(
                new Object[]{"package/package.json", true},
                new Object[]{"some-name-1.0.0/package.json", true},
                new Object[]{"package.json", true},
                new Object[]{"a/b/package.json", false},
                new Object[]{"a/b/c/package/package.json", false},
                new Object[]{"../package.json", false},
                new Object[]{"package//package.json", false}
        );
    }

    @ParameterizedTest(name = "entry {0}")
    @MethodSource("markerBoundaryCases")
    @DisplayName("router classifies entry as NPM exactly when it is a top-level npm marker")
    void markerBoundaryParameterized(String entryName, boolean strictIsMarker,
                                     @org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        // The agreement property: the router must classify the archive as NPM iff the
        // strict marker semantics say this entry name is "the" package.json.
        byte[] tar = ArchiveBuilder.gzipTar(ArchiveBuilder.Entry.of(entryName, PKG_JSON));
        Path pkg = write(tempDir, "marker-" + Math.abs(entryName.hashCode()) + ".tar.gz", tar);

        Optional<Ecosystem> routed = EcosystemRouter.route(pkg);
        boolean routedAsNpm = routed.isPresent() && routed.get() == Ecosystem.NPM;

        assertThat(routedAsNpm)
                .as("router NPM classification for entry '%s' must equal strict marker=%s",
                        entryName, strictIsMarker)
                .isEqualTo(strictIsMarker);
    }

    // ================================================================
    // Guards: valid layouts must keep routing to their ecosystem
    // ================================================================

    @Test
    @DisplayName("real npm layout still routes to NPM (guard)")
    void realNpmLayoutStillRoutesToNpm(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        byte[] tar = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("package/package.json", PKG_JSON),
                ArchiveBuilder.Entry.of("package/index.js", "console.log(1)"));
        Path pkg = write(tempDir, "pkg-1.0.0.tgz", tar);

        assertThat(EcosystemRouter.route(pkg)).hasValue(Ecosystem.NPM);

        // Q6 custom single-directory prefix is part of the documented extraction rule.
        byte[] custom = ArchiveBuilder.gzipTar(
                ArchiveBuilder.Entry.of("my-package-1.0.0/package.json", PKG_JSON));
        Path pkg2 = write(tempDir, "other-1.0.0.tgz", custom);
        assertThat(EcosystemRouter.route(pkg2)).hasValue(Ecosystem.NPM);
    }

    @Test
    @DisplayName("multi-member gzip routing agrees with extraction (guard)")
    void multiMemberGzipRoutingAgreesWithExtraction(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        // Two concatenated gzip members; the npm marker lives in the first member.
        byte[] first = ArchiveBuilder.gzipTar(ArchiveBuilder.Entry.of("package/package.json", PKG_JSON));
        byte[] second = ArchiveBuilder.gzipTar(ArchiveBuilder.Entry.of("other/file.txt", "x"));
        byte[] concat = java.nio.ByteBuffer.allocate(first.length + second.length)
                .put(first).put(second).array();
        Path pkg = write(tempDir, "mm.tgz", concat);

        Optional<Ecosystem> routed = EcosystemRouter.route(pkg);
        assertThat(routed).hasValue(Ecosystem.NPM);

        LanguagePackage lp = LanguagePackageReader.read(pkg);
        try {
            assertThat(lp.ecosystem()).isEqualTo(Ecosystem.NPM);
        } finally {
            lp.close();
        }
    }

    @Test
    @DisplayName("valid npm package with >1000 entries routes to NPM (marker late)")
    void largeValidNpmTgzRoutesToNpm(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
        // lodash-4.17.21.tgz regression: 1054 entries with package/package.json at index 1049.
        // A valid package must ALWAYS open - the routing budget must never misroute it.
        List<ArchiveBuilder.Entry> entries = new java.util.ArrayList<>();
        for (int i = 0; i < 1100; i++) {
            entries.add(ArchiveBuilder.Entry.of("package/lib/file" + i + ".js", "//" + i));
        }
        entries.add(ArchiveBuilder.Entry.of("package/package.json", PKG_JSON));
        Path pkg = write(tempDir, "big-1.0.0.tgz", ArchiveBuilder.gzipTar(entries));

        assertThat(EcosystemRouter.route(pkg)).hasValue(Ecosystem.NPM);

        // And the full read() path must open it.
        LanguagePackage lp = LanguagePackageReader.read(pkg);
        try {
            assertThat(lp.ecosystem()).isEqualTo(Ecosystem.NPM);
        } finally {
            lp.close();
        }
    }

    // ================================================================
    // helpers
    // ================================================================

    private Path write(Path tempDir, String name, byte[] content) throws IOException {
        Path p = tempDir.resolve(name);
        Files.write(p, content);
        return p;
    }
}