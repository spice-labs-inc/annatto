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

package io.spicelabs.annatto.cpan;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.testutil.TestCorpusDownloader;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactMemento;
import io.spicelabs.rodeocomponents.APIS.artifacts.BackendStorage;
import io.spicelabs.rodeocomponents.APIS.artifacts.Metadata;
import io.spicelabs.rodeocomponents.APIS.artifacts.MetadataTag;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoItemMarker;
import io.spicelabs.rodeocomponents.APIS.artifacts.StringPair;
import io.spicelabs.rodeocomponents.APIS.artifacts.StringPairOptional;
import io.spicelabs.rodeocomponents.APIS.artifacts.WorkItem;
import io.spicelabs.rodeocomponents.APIS.purls.Purl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
 * Tests for {@link CpanHandler} covering the full handler lifecycle:
 * begin -> getMetadata -> getPurls -> end.
 *
 * <p>Uses real CPAN distributions downloaded from cpan.metacpan.org.</p>
 */
class CpanHandlerTest {

    private static final Path CPAN_CORPUS = TestCorpusDownloader.corpusDir("cpan");

    private CpanHandler handler;

    @BeforeAll
    static void ensureTestPackagesExist() {
        assumeThat(Files.isDirectory(CPAN_CORPUS))
                .as("cpan test corpus directory must exist with real packages")
                .isTrue();
    }

    @BeforeEach
    void setUp() {
        handler = new CpanHandler();
    }

    // --- Lifecycle tests ---

    /**
     * Goal: Verify begin() returns a CpanMemento with extracted metadata.
     * Rationale: Q1 — Handler must create a populated memento from META.json.
     */
    @Test
    void begin_returnsMementoWithMetadata() throws Exception {
        Path pkg = CPAN_CORPUS.resolve("Moose-2.2207.tar.gz");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Moose-2.2207.tar.gz"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(CpanMemento.class);
            CpanMemento cpanMemento = (CpanMemento) memento;
            assertThat(cpanMemento.packageName()).hasValue("Moose");
            assertThat(cpanMemento.packageVersion()).hasValue("2.2207");
        }
    }

    /**
     * Goal: Verify begin() with malformed stream returns empty memento.
     * Rationale: Handler must gracefully handle corrupt archives.
     */
    @Test
    void begin_malformedStream_returnsMementoWithNoMetadata() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.tar.gz"),
                stubWorkItem(), stubMarker());

        assertThat(memento).isInstanceOf(CpanMemento.class);
        CpanMemento cpanMemento = (CpanMemento) memento;
        assertThat(cpanMemento.packageName()).isEmpty();
        assertThat(cpanMemento.packageVersion()).isEmpty();
    }

    /**
     * Goal: Verify getMetadata returns populated metadata list.
     * Rationale: Metadata must map from MetadataResult to rodeo Metadata format.
     */
    @Test
    void getMetadata_returnsPopulatedList() throws Exception {
        Path pkg = CPAN_CORPUS.resolve("Moose-2.2207.tar.gz");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Moose-2.2207.tar.gz"),
                    stubWorkItem(), stubMarker());

            List<Metadata> metadata = handler.getMetadata(memento,
                    stubArtifact("Moose-2.2207.tar.gz"),
                    stubWorkItem(), stubMarker());

            assertThat(metadata).isNotEmpty();
            assertThat(metadata.stream().map(Metadata::tag))
                    .contains(MetadataTag.NAME, MetadataTag.VERSION);

            String name = metadata.stream()
                    .filter(m -> m.tag() == MetadataTag.NAME)
                    .findFirst().map(Metadata::value).orElse(null);
            assertThat(name).isEqualTo("Moose");
        }
    }

    /**
     * Goal: Verify getMetadata returns empty for malformed input.
     * Rationale: When begin fails, getMetadata should return empty.
     */
    @Test
    void getMetadata_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.tar.gz"),
                stubWorkItem(), stubMarker());

        List<Metadata> metadata = handler.getMetadata(memento, stubArtifact("bad.tar.gz"),
                stubWorkItem(), stubMarker());

        assertThat(metadata).isEmpty();
    }

    // --- PURL tests ---

    /**
     * Goal: Verify PURL format for CPAN distribution.
     * Rationale: PURL must be pkg:cpan/Name@version.
     */
    @Test
    void getPurls_correctFormat() throws Exception {
        Path pkg = CPAN_CORPUS.resolve("Moose-2.2207.tar.gz");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Moose-2.2207.tar.gz"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("Moose-2.2207.tar.gz"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:cpan/Moose@2.2207");
        }
    }

    /**
     * Goal: Verify PURL has no namespace.
     * Rationale: Q3 — PAUSE ID unavailable from tarball.
     */
    @Test
    void getPurls_noNamespace() throws Exception {
        Path pkg = CPAN_CORPUS.resolve("Try-Tiny-0.31.tar.gz");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Try-Tiny-0.31.tar.gz"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("Try-Tiny-0.31.tar.gz"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            // No double slash after type = no namespace
            assertThat(purls.get(0).toString()).startsWith("pkg:cpan/Try-Tiny@");
        }
    }

    /**
     * Goal: Verify PURL is empty for malformed input.
     * Rationale: When extraction fails, no PURL should be generated.
     */
    @Test
    void getPurls_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.tar.gz"),
                stubWorkItem(), stubMarker());

        List<Purl> purls = handler.getPurls(memento, stubArtifact("bad.tar.gz"),
                stubWorkItem(), stubMarker());

        assertThat(purls).isEmpty();
    }

    // --- Handler properties ---

    /**
     * Goal: Verify the handler's ecosystem ID.
     * Rationale: Handler must identify itself as CPAN.
     */
    @Test
    void ecosystemId_isCpan() {
        assertThat(handler.ecosystemId()).isEqualTo(EcosystemId.CPAN);
    }

    /**
     * Goal: Verify the handler does not require file input.
     * Rationale: CPAN handler works with InputStreams.
     */
    @Test
    void requiresFile_isFalse() {
        assertThat(handler.requiresFile()).isFalse();
    }

    /**
     * Goal: Verify end() does not throw.
     * Rationale: Cleanup method should be safe.
     */
    @Test
    void end_doesNotThrow() throws Exception {
        Path pkg = CPAN_CORPUS.resolve("Try-Tiny-0.31.tar.gz");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Try-Tiny-0.31.tar.gz"),
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
        Path pkg1 = CPAN_CORPUS.resolve("Moose-2.2207.tar.gz");
        Path pkg2 = CPAN_CORPUS.resolve("Try-Tiny-0.31.tar.gz");
        assumeThat(Files.exists(pkg1)).isTrue();
        assumeThat(Files.exists(pkg2)).isTrue();

        CpanHandler handler1 = new CpanHandler();
        CpanHandler handler2 = new CpanHandler();

        ArtifactMemento memento1;
        ArtifactMemento memento2;

        try (FileInputStream fis1 = new FileInputStream(pkg1.toFile())) {
            memento1 = handler1.begin(fis1, stubArtifact("Moose-2.2207.tar.gz"),
                    stubWorkItem(), stubMarker());
        }
        try (FileInputStream fis2 = new FileInputStream(pkg2.toFile())) {
            memento2 = handler2.begin(fis2, stubArtifact("Try-Tiny-0.31.tar.gz"),
                    stubWorkItem(), stubMarker());
        }

        assertThat(((CpanMemento) memento1).packageName()).hasValue("Moose");
        assertThat(((CpanMemento) memento2).packageName()).hasValue("Try-Tiny");

        List<Purl> purls1 = handler1.getPurls(memento1, stubArtifact("Moose-2.2207.tar.gz"),
                stubWorkItem(), stubMarker());
        List<Purl> purls2 = handler2.getPurls(memento2, stubArtifact("Try-Tiny-0.31.tar.gz"),
                stubWorkItem(), stubMarker());

        assertThat(purls1).hasSize(1);
        assertThat(purls2).hasSize(1);
        assertThat(purls1.get(0).toString()).isEqualTo("pkg:cpan/Moose@2.2207");
        assertThat(purls2.get(0).toString()).isEqualTo("pkg:cpan/Try-Tiny@0.31");
    }

    // --- Parameterized begin + getPurls across all packages ---

    static Stream<Arguments> allPackages() throws IOException {
        if (!Files.isDirectory(CPAN_CORPUS)) {
            return Stream.empty();
        }
        return Files.list(CPAN_CORPUS)
                .filter(p -> p.toString().endsWith(".tar.gz"))
                .sorted()
                .map(pkg -> Arguments.of(pkg.getFileName().toString(), pkg));
    }

    /**
     * Goal: Verify begin() succeeds for every real CPAN package.
     * Rationale: Q1 — All packages must be handled.
     */
    @ParameterizedTest(name = "begin: {0}")
    @MethodSource("allPackages")
    void begin_neverThrows(String label, Path pkgPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(pkgPath.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact(label),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(CpanMemento.class);
            CpanMemento cpanMemento = (CpanMemento) memento;
            assertThat(cpanMemento.packageName()).isPresent();
        }
    }

    /**
     * Goal: Verify PURL generation succeeds for every real CPAN package.
     * Rationale: Every valid package must produce a PURL.
     */
    @ParameterizedTest(name = "purl: {0}")
    @MethodSource("allPackages")
    void getPurls_neverThrows(String label, Path pkgPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(pkgPath.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact(label),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento, stubArtifact(label),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).startsWith("pkg:cpan/");
        }
    }

    // --- Stub helpers ---

    private static RodeoArtifact stubArtifact(String filename) {
        return new RodeoArtifact() {
            @Override public String getPath() { return "/test/" + filename; }
            @Override public long getSize() { return 0; }
            @Override public String getMimeType() { return "application/gzip"; }
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
