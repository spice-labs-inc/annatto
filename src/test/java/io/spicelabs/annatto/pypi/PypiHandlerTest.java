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

package io.spicelabs.annatto.pypi;

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
 * Tests for {@link PypiHandler} covering the full handler lifecycle:
 * begin -> getMetadata -> getPurls -> end.
 *
 * <p>Uses real PyPI packages downloaded from the PyPI registry.
 * Package files (.whl/.tar.gz) are in {@code test-corpus/pypi/} (downloaded from
 * public-test-data.spice-labs.dev, gitignored).</p>
 *
 * <p>Tests handler statefulness, PURL generation, metadata mapping,
 * and concurrent processing safety.</p>
 */
class PypiHandlerTest {

    /** Directory containing real PyPI package files (gitignored, downloaded from test data server). */
    private static final Path PYPI_CORPUS = TestCorpusDownloader.corpusDir("pypi");

    private PypiHandler handler;

    @BeforeAll
    static void ensureTestPackagesExist() {
        assumeThat(Files.isDirectory(PYPI_CORPUS))
                .as("pypi test corpus directory must exist with real packages")
                .isTrue();
    }

    @BeforeEach
    void setUp() {
        handler = new PypiHandler();
    }

    // --- Lifecycle tests ---

    /**
     * Goal: Verify begin() returns a PypiMemento with extracted metadata.
     * Rationale: The handler must create a populated memento during begin.
     */
    @Test
    void begin_returnsPypiMementoWithMetadata() throws Exception {
        Path whl = PYPI_CORPUS.resolve("requests-2.31.0-py3-none-any.whl");
        assumeThat(Files.exists(whl)).isTrue();

        try (FileInputStream fis = new FileInputStream(whl.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact("requests-2.31.0-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(PypiMemento.class);
            PypiMemento pypiMemento = (PypiMemento) memento;
            assertThat(pypiMemento.packageName()).hasValue("requests");
            assertThat(pypiMemento.packageVersion()).hasValue("2.31.0");
        }
    }

    /**
     * Goal: Verify begin() with a malformed stream returns a memento with no metadata.
     * Rationale: The handler should gracefully handle corrupt archives without throwing.
     */
    @Test
    void begin_malformedStream_returnsMementoWithNoMetadata() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.whl"),
                stubWorkItem(), stubMarker());

        assertThat(memento).isInstanceOf(PypiMemento.class);
        PypiMemento pypiMemento = (PypiMemento) memento;
        assertThat(pypiMemento.packageName()).isEmpty();
        assertThat(pypiMemento.packageVersion()).isEmpty();
    }

    /**
     * Goal: Verify getMetadata returns populated metadata list.
     * Rationale: Metadata must be mapped from MetadataResult to rodeo Metadata format.
     */
    @Test
    void getMetadata_returnsPopulatedList() throws Exception {
        Path whl = PYPI_CORPUS.resolve("requests-2.31.0-py3-none-any.whl");
        assumeThat(Files.exists(whl)).isTrue();

        try (FileInputStream fis = new FileInputStream(whl.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact("requests-2.31.0-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());

            List<Metadata> metadata = handler.getMetadata(memento, stubArtifact("requests-2.31.0-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());

            assertThat(metadata).isNotEmpty();
            assertThat(metadata.stream().map(Metadata::tag))
                    .contains(MetadataTag.NAME, MetadataTag.VERSION, MetadataTag.LICENSE, MetadataTag.PUBLISHER);

            String name = metadata.stream()
                    .filter(m -> m.tag() == MetadataTag.NAME)
                    .findFirst().map(Metadata::value).orElse(null);
            assertThat(name).isEqualTo("requests");
        }
    }

    /**
     * Goal: Verify getMetadata returns empty list for malformed input.
     * Rationale: When begin fails to extract metadata, getMetadata should return empty, not throw.
     */
    @Test
    void getMetadata_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.whl"),
                stubWorkItem(), stubMarker());

        List<Metadata> metadata = handler.getMetadata(memento, stubArtifact("bad.whl"),
                stubWorkItem(), stubMarker());

        assertThat(metadata).isEmpty();
    }

    // --- PURL tests ---

    /**
     * Goal: Verify PURL generation for a wheel package.
     * Rationale: PURL must be pkg:pypi/name@version with normalized name.
     */
    @Test
    void getPurls_wheelPackage_generatesCorrectPurl() throws Exception {
        Path whl = PYPI_CORPUS.resolve("requests-2.31.0-py3-none-any.whl");
        assumeThat(Files.exists(whl)).isTrue();

        try (FileInputStream fis = new FileInputStream(whl.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact("requests-2.31.0-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento, stubArtifact("requests-2.31.0-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:pypi/requests@2.31.0");
        }
    }

    /**
     * Goal: Verify PURL generation for an sdist package.
     * Rationale: sdist packages must also produce correct PURLs.
     */
    @Test
    void getPurls_sdistPackage_generatesCorrectPurl() throws Exception {
        Path tarGz = PYPI_CORPUS.resolve("cffi-1.16.0.tar.gz");
        assumeThat(Files.exists(tarGz)).isTrue();

        try (FileInputStream fis = new FileInputStream(tarGz.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact("cffi-1.16.0.tar.gz"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento, stubArtifact("cffi-1.16.0.tar.gz"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:pypi/cffi@1.16.0");
        }
    }

    /**
     * Goal: Verify PURL name normalization for Flask-SocketIO.
     * Rationale: Q1 - PEP 503 normalization: Flask-SocketIO -> flask-socketio.
     */
    @Test
    void getPurls_nameNormalization_flaskSocketIO() throws Exception {
        Path whl = PYPI_CORPUS.resolve("Flask_SocketIO-5.3.6-py3-none-any.whl");
        assumeThat(Files.exists(whl)).isTrue();

        try (FileInputStream fis = new FileInputStream(whl.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact("Flask_SocketIO-5.3.6-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento, stubArtifact("Flask_SocketIO-5.3.6-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:pypi/flask-socketio@5.3.6");
        }
    }

    /**
     * Goal: Verify PURL is empty for malformed input.
     * Rationale: When metadata extraction fails, no PURL should be generated.
     */
    @Test
    void getPurls_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.whl"),
                stubWorkItem(), stubMarker());

        List<Purl> purls = handler.getPurls(memento, stubArtifact("bad.whl"),
                stubWorkItem(), stubMarker());

        assertThat(purls).isEmpty();
    }

    // --- Handler properties ---

    /**
     * Goal: Verify the handler's ecosystem ID.
     * Rationale: Handler must identify itself as PYPI ecosystem.
     */
    @Test
    void ecosystemId_isPypi() {
        assertThat(handler.ecosystemId()).isEqualTo(EcosystemId.PYPI);
    }

    /**
     * Goal: Verify the handler does not require file input.
     * Rationale: PyPI handler works with InputStreams, not files.
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
        Path whl = PYPI_CORPUS.resolve("requests-2.31.0-py3-none-any.whl");
        assumeThat(Files.exists(whl)).isTrue();

        try (FileInputStream fis = new FileInputStream(whl.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact("requests-2.31.0-py3-none-any.whl"),
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
        Path pkg1 = PYPI_CORPUS.resolve("requests-2.31.0-py3-none-any.whl");
        Path pkg2 = PYPI_CORPUS.resolve("flask-3.0.0-py3-none-any.whl");
        assumeThat(Files.exists(pkg1)).isTrue();
        assumeThat(Files.exists(pkg2)).isTrue();

        PypiHandler handler1 = new PypiHandler();
        PypiHandler handler2 = new PypiHandler();

        ArtifactMemento memento1;
        ArtifactMemento memento2;

        try (FileInputStream fis1 = new FileInputStream(pkg1.toFile())) {
            memento1 = handler1.begin(fis1, stubArtifact("requests-2.31.0-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());
        }
        try (FileInputStream fis2 = new FileInputStream(pkg2.toFile())) {
            memento2 = handler2.begin(fis2, stubArtifact("flask-3.0.0-py3-none-any.whl"),
                    stubWorkItem(), stubMarker());
        }

        assertThat(memento1).isInstanceOf(PypiMemento.class);
        assertThat(memento2).isInstanceOf(PypiMemento.class);
        assertThat(((PypiMemento) memento1).packageName()).hasValue("requests");
        assertThat(((PypiMemento) memento2).packageName()).hasValue("Flask");

        List<Purl> purls1 = handler1.getPurls(memento1, stubArtifact("requests-2.31.0-py3-none-any.whl"),
                stubWorkItem(), stubMarker());
        List<Purl> purls2 = handler2.getPurls(memento2, stubArtifact("flask-3.0.0-py3-none-any.whl"),
                stubWorkItem(), stubMarker());

        assertThat(purls1).hasSize(1);
        assertThat(purls2).hasSize(1);
        assertThat(purls1.get(0).toString()).isEqualTo("pkg:pypi/requests@2.31.0");
        assertThat(purls2.get(0).toString()).isEqualTo("pkg:pypi/flask@3.0.0");
    }

    // --- Parameterized PURL test across all packages ---

    static Stream<Arguments> allPackages() throws IOException {
        if (!Files.isDirectory(PYPI_CORPUS)) {
            return Stream.empty();
        }
        return Files.list(PYPI_CORPUS)
                .filter(p -> p.toString().endsWith(".whl") || p.toString().endsWith(".tar.gz"))
                .sorted()
                .map(pkg -> Arguments.of(pkg.getFileName().toString(), pkg));
    }

    /**
     * Goal: Verify PURL generation succeeds for every real PyPI package.
     * Rationale: Every valid PyPI package must produce a PURL or empty list, never throw.
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
            assertThat(purls.get(0).toString()).startsWith("pkg:pypi/");
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
