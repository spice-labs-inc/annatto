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

package io.spicelabs.annatto.crates;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.testutil.TestCorpusDownloader;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactMemento;
import io.spicelabs.rodeocomponents.APIS.artifacts.Metadata;
import io.spicelabs.rodeocomponents.APIS.artifacts.MetadataTag;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoItemMarker;
import io.spicelabs.rodeocomponents.APIS.artifacts.WorkItem;
import io.spicelabs.rodeocomponents.APIS.purls.Purl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.spicelabs.rodeocomponents.APIS.artifacts.BackendStorage;
import io.spicelabs.rodeocomponents.APIS.artifacts.StringPair;
import io.spicelabs.rodeocomponents.APIS.artifacts.StringPairOptional;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tests for {@link CratesHandler} covering the full handler lifecycle:
 * begin -> getMetadata -> getPurls -> end.
 *
 * <p>Uses real crate packages downloaded from crates.io.
 * Package files (.crate) are in {@code test-corpus/crates/} (gitignored).</p>
 */
class CratesHandlerTest {

    /** Directory containing real .crate files (gitignored). */
    private static final Path CRATES_CORPUS = TestCorpusDownloader.corpusDir("crates");

    private CratesHandler handler;

    @BeforeAll
    static void ensureTestPackagesExist() {
        assumeThat(Files.isDirectory(CRATES_CORPUS))
                .as("crates test corpus directory must exist with real packages")
                .isTrue();
    }

    @BeforeEach
    void setUp() {
        handler = new CratesHandler();
    }

    // --- Lifecycle tests ---

    /**
     * Goal: Verify begin() returns a CratesMemento with extracted metadata.
     * Rationale: The handler must create a populated memento during begin.
     */
    @Test
    void begin_returnsCratesMementoWithMetadata() throws Exception {
        Path crate = CRATES_CORPUS.resolve("serde-1.0.195.crate");
        assumeThat(Files.exists(crate)).isTrue();

        try (FileInputStream fis = new FileInputStream(crate.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("serde-1.0.195.crate"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(CratesMemento.class);
            CratesMemento cratesMemento = (CratesMemento) memento;
            assertThat(cratesMemento.packageName()).hasValue("serde");
            assertThat(cratesMemento.packageVersion()).hasValue("1.0.195");
            assertThat(cratesMemento.rawCargoToml()).isNotEmpty();
        }
    }

    /**
     * Goal: Verify begin() with a malformed stream returns a memento with no metadata.
     * Rationale: The handler should gracefully handle corrupt archives without throwing.
     */
    @Test
    void begin_malformedStream_returnsMementoWithNoMetadata() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.crate"),
                stubWorkItem(), stubMarker());

        assertThat(memento).isInstanceOf(CratesMemento.class);
        CratesMemento cratesMemento = (CratesMemento) memento;
        assertThat(cratesMemento.packageName()).isEmpty();
        assertThat(cratesMemento.packageVersion()).isEmpty();
    }

    /**
     * Goal: Verify getMetadata returns populated metadata list.
     * Rationale: Metadata must be mapped from MetadataResult to rodeo Metadata format.
     */
    @Test
    void getMetadata_returnsPopulatedList() throws Exception {
        Path crate = CRATES_CORPUS.resolve("serde-1.0.195.crate");
        assumeThat(Files.exists(crate)).isTrue();

        try (FileInputStream fis = new FileInputStream(crate.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("serde-1.0.195.crate"),
                    stubWorkItem(), stubMarker());

            List<Metadata> metadata = handler.getMetadata(memento,
                    stubArtifact("serde-1.0.195.crate"),
                    stubWorkItem(), stubMarker());

            assertThat(metadata).isNotEmpty();
            assertThat(metadata.stream().map(Metadata::tag))
                    .contains(MetadataTag.NAME, MetadataTag.VERSION);

            String name = metadata.stream()
                    .filter(m -> m.tag() == MetadataTag.NAME)
                    .findFirst().map(Metadata::value).orElse(null);
            assertThat(name).isEqualTo("serde");
        }
    }

    /**
     * Goal: Verify getMetadata returns empty list for malformed input.
     * Rationale: When begin fails to extract metadata, getMetadata should return empty, not throw.
     */
    @Test
    void getMetadata_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.crate"),
                stubWorkItem(), stubMarker());

        List<Metadata> metadata = handler.getMetadata(memento, stubArtifact("bad.crate"),
                stubWorkItem(), stubMarker());

        assertThat(metadata).isEmpty();
    }

    // --- PURL tests ---

    /**
     * Goal: Verify PURL generation for serde.
     * Rationale: PURL must be pkg:cargo/serde@1.0.195.
     */
    @Test
    void getPurls_serde_generatesCorrectPurl() throws Exception {
        Path crate = CRATES_CORPUS.resolve("serde-1.0.195.crate");
        assumeThat(Files.exists(crate)).isTrue();

        try (FileInputStream fis = new FileInputStream(crate.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("serde-1.0.195.crate"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("serde-1.0.195.crate"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:cargo/serde@1.0.195");
        }
    }

    /**
     * Goal: Verify PURL is empty for malformed input.
     * Rationale: When metadata extraction fails, no PURL should be generated.
     */
    @Test
    void getPurls_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.crate"),
                stubWorkItem(), stubMarker());

        List<Purl> purls = handler.getPurls(memento, stubArtifact("bad.crate"),
                stubWorkItem(), stubMarker());

        assertThat(purls).isEmpty();
    }

    // --- Handler properties ---

    /**
     * Goal: Verify the handler's ecosystem ID.
     * Rationale: Handler must identify itself as CRATES ecosystem.
     */
    @Test
    void ecosystemId_isCrates() {
        assertThat(handler.ecosystemId()).isEqualTo(EcosystemId.CRATES);
    }

    /**
     * Goal: Verify the handler does not require file input.
     * Rationale: Crates handler works with InputStreams, not files.
     */
    @Test
    void requiresFile_isFalse() {
        assertThat(handler.requiresFile()).isFalse();
    }

    /**
     * Goal: Verify end() does not throw.
     * Rationale: end() is a cleanup method that should be safe to call.
     */
    @Test
    void end_doesNotThrow() throws Exception {
        Path crate = CRATES_CORPUS.resolve("serde-1.0.195.crate");
        assumeThat(Files.exists(crate)).isTrue();

        try (FileInputStream fis = new FileInputStream(crate.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("serde-1.0.195.crate"),
                    stubWorkItem(), stubMarker());
            handler.end(memento);
        }
    }

    // --- Handler isolation test ---

    /**
     * Goal: Verify multiple packages can be processed without interference.
     * Rationale: Handler is stateless; each begin() creates an isolated memento.
     */
    @Test
    void handlerIsolation_noInterference() throws Exception {
        Path pkg1 = CRATES_CORPUS.resolve("serde-1.0.195.crate");
        Path pkg2 = CRATES_CORPUS.resolve("void-1.0.2.crate");
        assumeThat(Files.exists(pkg1)).isTrue();
        assumeThat(Files.exists(pkg2)).isTrue();

        CratesHandler handler1 = new CratesHandler();
        CratesHandler handler2 = new CratesHandler();

        ArtifactMemento memento1;
        ArtifactMemento memento2;

        try (FileInputStream fis1 = new FileInputStream(pkg1.toFile())) {
            memento1 = handler1.begin(fis1, stubArtifact("serde-1.0.195.crate"),
                    stubWorkItem(), stubMarker());
        }
        try (FileInputStream fis2 = new FileInputStream(pkg2.toFile())) {
            memento2 = handler2.begin(fis2, stubArtifact("void-1.0.2.crate"),
                    stubWorkItem(), stubMarker());
        }

        assertThat(memento1).isInstanceOf(CratesMemento.class);
        assertThat(memento2).isInstanceOf(CratesMemento.class);
        assertThat(((CratesMemento) memento1).packageName()).hasValue("serde");
        assertThat(((CratesMemento) memento2).packageName()).hasValue("void");

        List<Purl> purls1 = handler1.getPurls(memento1,
                stubArtifact("serde-1.0.195.crate"),
                stubWorkItem(), stubMarker());
        List<Purl> purls2 = handler2.getPurls(memento2,
                stubArtifact("void-1.0.2.crate"),
                stubWorkItem(), stubMarker());

        assertThat(purls1).hasSize(1);
        assertThat(purls2).hasSize(1);
        assertThat(purls1.get(0).toString()).isEqualTo("pkg:cargo/serde@1.0.195");
        assertThat(purls2.get(0).toString()).isEqualTo("pkg:cargo/void@1.0.2");
    }

    // --- Parameterized PURL test across all packages ---

    static Stream<Arguments> allPackages() throws IOException {
        if (!Files.isDirectory(CRATES_CORPUS)) {
            return Stream.empty();
        }
        return Files.list(CRATES_CORPUS)
                .filter(p -> p.toString().endsWith(".crate"))
                .sorted()
                .map(pkg -> Arguments.of(pkg.getFileName().toString(), pkg));
    }

    /**
     * Goal: Verify PURL generation succeeds for every real crate package.
     * Rationale: Every valid crate must produce a PURL or empty list, never throw.
     */
    @ParameterizedTest(name = "purl: {0}")
    @MethodSource("allPackages")
    void getPurls_neverThrows(String label, Path pkgPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(pkgPath.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact(label),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento, stubArtifact(label),
                    stubWorkItem(), stubMarker());

            // All test packages are valid, so they should produce exactly one PURL
            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).startsWith("pkg:cargo/");
        }
    }

    // --- Stub helpers ---

    private static RodeoArtifact stubArtifact(String filename) {
        return new RodeoArtifact() {
            @Override public String getPath() { return "/test/" + filename; }
            @Override public long getSize() { return 0; }
            @Override public String getMimeType() { return "application/octet-stream"; }
            @Override public boolean getIsRealFile() { return false; }
            @Override public String getUuid() { return "test-uuid"; }
            @Override public String getFilenameWithNoPath() { return filename; }
        };
    }

    private static WorkItem stubWorkItem() {
        return new WorkItem() {
            @Override public String getIdentifier() { return "test-id"; }
            @Override public Set<StringPair> getConnections() { return Set.of(); }
            @Override public WorkItem withNewConnection(String a, String b) { return this; }
            @Override public WorkItem merge(WorkItem other) { return this; }
            @Override public byte[] getMd5() { return new byte[16]; }
            @Override public boolean compareMd5(WorkItem other) { return false; }
            @Override public boolean isRootWorkItem() { return false; }
            @Override public List<StringPair> referencedFromAliasOrBuildOrContained() { return List.of(); }
            @Override public WorkItem createOrUpdateInStorage(BackendStorage store,
                    Function<WorkItem, String> fn) { return this; }
            @Override public WorkItem updateTheBackReferences(BackendStorage store,
                    io.spicelabs.rodeocomponents.APIS.artifacts.ParentFrame frame) { return this; }
            @Override public List<String> containedGitoids() { return List.of(); }
            @Override public WorkItem enhanceWithPurls(List<Purl> purls) { return this; }
            @Override public WorkItem enhanceWithMetadata(Optional<String> s,
                    Map<String, Set<StringPairOptional>> m,
                    List<String> l1, List<String> l2) { return this; }
        };
    }

    private static RodeoItemMarker stubMarker() {
        return new RodeoItemMarker() {};
    }
}
