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

package io.spicelabs.annatto.conda;

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
 * Tests for {@link CondaHandler} covering the full handler lifecycle:
 * begin -> getMetadata -> getPurls -> end.
 *
 * <p>Uses real conda packages downloaded from conda-forge.
 * Package files (.conda, .tar.bz2) are in {@code test-corpus/conda/} (gitignored).</p>
 */
class CondaHandlerTest {

    private static final Path CONDA_CORPUS = TestCorpusDownloader.corpusDir("conda");

    private CondaHandler handler;

    @BeforeAll
    static void ensureTestPackagesExist() {
        assumeThat(Files.isDirectory(CONDA_CORPUS))
                .as("conda test corpus directory must exist with real packages")
                .isTrue();
    }

    @BeforeEach
    void setUp() {
        handler = new CondaHandler();
    }

    // --- Lifecycle tests ---

    /**
     * Goal: Verify begin() returns a CondaMemento with extracted metadata from .conda format.
     * Rationale: Q1 - The handler must create a populated memento for .conda archives.
     */
    @Test
    void begin_condaFormat_returnsMementoWithMetadata() throws Exception {
        Path pkg = CONDA_CORPUS.resolve("numpy-1.26.4-py310hb13e2d6_0.conda");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(CondaMemento.class);
            CondaMemento condaMemento = (CondaMemento) memento;
            assertThat(condaMemento.packageName()).hasValue("numpy");
            assertThat(condaMemento.packageVersion()).hasValue("1.26.4");
            assertThat(condaMemento.build()).hasValue("py310hb13e2d6_0");
            assertThat(condaMemento.subdir()).hasValue("linux-64");
        }
    }

    /**
     * Goal: Verify begin() returns a CondaMemento with extracted metadata from .tar.bz2 format.
     * Rationale: Q1 - The handler must create a populated memento for .tar.bz2 archives.
     */
    @Test
    void begin_tarBz2Format_returnsMementoWithMetadata() throws Exception {
        Path pkg = CONDA_CORPUS.resolve("six-1.16.0-pyh6c4a22f_0.tar.bz2");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("six-1.16.0-pyh6c4a22f_0.tar.bz2"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(CondaMemento.class);
            CondaMemento condaMemento = (CondaMemento) memento;
            assertThat(condaMemento.packageName()).hasValue("six");
            assertThat(condaMemento.packageVersion()).hasValue("1.16.0");
        }
    }

    /**
     * Goal: Verify begin() with a malformed stream returns a memento with no metadata.
     * Rationale: The handler should gracefully handle corrupt archives without throwing.
     */
    @Test
    void begin_malformedStream_returnsMementoWithNoMetadata() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.conda"),
                stubWorkItem(), stubMarker());

        assertThat(memento).isInstanceOf(CondaMemento.class);
        CondaMemento condaMemento = (CondaMemento) memento;
        assertThat(condaMemento.packageName()).isEmpty();
        assertThat(condaMemento.packageVersion()).isEmpty();
    }

    /**
     * Goal: Verify getMetadata returns populated metadata list.
     * Rationale: Metadata must be mapped from MetadataResult to rodeo Metadata format.
     */
    @Test
    void getMetadata_returnsPopulatedList() throws Exception {
        Path pkg = CONDA_CORPUS.resolve("numpy-1.26.4-py310hb13e2d6_0.conda");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                    stubWorkItem(), stubMarker());

            List<Metadata> metadata = handler.getMetadata(memento,
                    stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                    stubWorkItem(), stubMarker());

            assertThat(metadata).isNotEmpty();
            assertThat(metadata.stream().map(Metadata::tag))
                    .contains(MetadataTag.NAME, MetadataTag.VERSION);

            String name = metadata.stream()
                    .filter(m -> m.tag() == MetadataTag.NAME)
                    .findFirst().map(Metadata::value).orElse(null);
            assertThat(name).isEqualTo("numpy");
        }
    }

    /**
     * Goal: Verify getMetadata returns empty list for malformed input.
     * Rationale: When begin fails, getMetadata should return empty, not throw.
     */
    @Test
    void getMetadata_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.conda"),
                stubWorkItem(), stubMarker());

        List<Metadata> metadata = handler.getMetadata(memento, stubArtifact("bad.conda"),
                stubWorkItem(), stubMarker());

        assertThat(metadata).isEmpty();
    }

    // --- PURL tests ---

    /**
     * Goal: Verify PURL generation for .conda format includes build and subdir qualifiers.
     * Rationale: Q3, Q4 - Build and subdir must be PURL qualifiers.
     */
    @Test
    void getPurls_condaFormat_includesBuildQualifier() throws Exception {
        Path pkg = CONDA_CORPUS.resolve("numpy-1.26.4-py310hb13e2d6_0.conda");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            String purlStr = purls.get(0).toString();
            assertThat(purlStr).startsWith("pkg:conda/numpy@1.26.4");
            assertThat(purlStr).contains("build=py310hb13e2d6_0");
            assertThat(purlStr).contains("subdir=linux-64");
        }
    }

    /**
     * Goal: Verify PURL has no namespace.
     * Rationale: Q2 - Channel is external context; no namespace in PURL.
     */
    @Test
    void getPurls_noNamespace() throws Exception {
        Path pkg = CONDA_CORPUS.resolve("numpy-1.26.4-py310hb13e2d6_0.conda");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            // PURL should not have a namespace (no double slash after type)
            String purlStr = purls.get(0).toString();
            assertThat(purlStr).startsWith("pkg:conda/numpy@");
        }
    }

    /**
     * Goal: Verify PURL is empty for malformed input.
     * Rationale: When metadata extraction fails, no PURL should be generated.
     */
    @Test
    void getPurls_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.conda"),
                stubWorkItem(), stubMarker());

        List<Purl> purls = handler.getPurls(memento, stubArtifact("bad.conda"),
                stubWorkItem(), stubMarker());

        assertThat(purls).isEmpty();
    }

    // --- Handler properties ---

    /**
     * Goal: Verify the handler's ecosystem ID.
     * Rationale: Handler must identify itself as CONDA ecosystem.
     */
    @Test
    void ecosystemId_isConda() {
        assertThat(handler.ecosystemId()).isEqualTo(EcosystemId.CONDA);
    }

    /**
     * Goal: Verify the handler does not require file input.
     * Rationale: Conda handler works with InputStreams, not files.
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
        Path pkg = CONDA_CORPUS.resolve("six-1.16.0-pyhd8ed1ab_1.conda");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("six-1.16.0-pyhd8ed1ab_1.conda"),
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
        Path pkg1 = CONDA_CORPUS.resolve("numpy-1.26.4-py310hb13e2d6_0.conda");
        Path pkg2 = CONDA_CORPUS.resolve("six-1.16.0-pyh6c4a22f_0.tar.bz2");
        assumeThat(Files.exists(pkg1)).isTrue();
        assumeThat(Files.exists(pkg2)).isTrue();

        CondaHandler handler1 = new CondaHandler();
        CondaHandler handler2 = new CondaHandler();

        ArtifactMemento memento1;
        ArtifactMemento memento2;

        try (FileInputStream fis1 = new FileInputStream(pkg1.toFile())) {
            memento1 = handler1.begin(fis1,
                    stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                    stubWorkItem(), stubMarker());
        }
        try (FileInputStream fis2 = new FileInputStream(pkg2.toFile())) {
            memento2 = handler2.begin(fis2,
                    stubArtifact("six-1.16.0-pyh6c4a22f_0.tar.bz2"),
                    stubWorkItem(), stubMarker());
        }

        assertThat(((CondaMemento) memento1).packageName()).hasValue("numpy");
        assertThat(((CondaMemento) memento2).packageName()).hasValue("six");

        List<Purl> purls1 = handler1.getPurls(memento1,
                stubArtifact("numpy-1.26.4-py310hb13e2d6_0.conda"),
                stubWorkItem(), stubMarker());
        List<Purl> purls2 = handler2.getPurls(memento2,
                stubArtifact("six-1.16.0-pyh6c4a22f_0.tar.bz2"),
                stubWorkItem(), stubMarker());

        assertThat(purls1).hasSize(1);
        assertThat(purls2).hasSize(1);
        assertThat(purls1.get(0).toString()).startsWith("pkg:conda/numpy@1.26.4");
        assertThat(purls2.get(0).toString()).startsWith("pkg:conda/six@1.16.0");
    }

    // --- Parameterized begin + getPurls across all packages ---

    static Stream<Arguments> allPackages() throws IOException {
        if (!Files.isDirectory(CONDA_CORPUS)) {
            return Stream.empty();
        }
        return Files.list(CONDA_CORPUS)
                .filter(p -> p.toString().endsWith(".conda") || p.toString().endsWith(".tar.bz2"))
                .sorted()
                .map(pkg -> Arguments.of(pkg.getFileName().toString(), pkg));
    }

    /**
     * Goal: Verify begin() succeeds for every real conda package.
     * Rationale: Q1 - Both .conda and .tar.bz2 formats must be handled.
     */
    @ParameterizedTest(name = "begin: {0}")
    @MethodSource("allPackages")
    void begin_neverThrows(String label, Path pkgPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(pkgPath.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact(label),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(CondaMemento.class);
            CondaMemento condaMemento = (CondaMemento) memento;
            assertThat(condaMemento.packageName()).isPresent();
        }
    }

    /**
     * Goal: Verify PURL generation succeeds for every real conda package.
     * Rationale: Every valid package must produce a PURL or empty list, never throw.
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
            assertThat(purls.get(0).toString()).startsWith("pkg:conda/");
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
