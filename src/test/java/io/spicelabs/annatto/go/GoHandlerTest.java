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

package io.spicelabs.annatto.go;

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
 * Tests for {@link GoHandler} covering the full handler lifecycle:
 * begin -> getMetadata -> getPurls -> end.
 *
 * <p>Uses real Go module packages downloaded from proxy.golang.org.
 * Package files (.zip) are in {@code test-corpus/go/} (downloaded from
 * public-test-data.spice-labs.dev, gitignored).</p>
 *
 * <p>Tests handler statefulness, PURL generation, metadata mapping,
 * and concurrent processing safety.</p>
 */
class GoHandlerTest {

    /** Directory containing real Go module zip files (gitignored, downloaded from test data server). */
    private static final Path GO_CORPUS = TestCorpusDownloader.corpusDir("go");

    private GoHandler handler;

    @BeforeAll
    static void ensureTestPackagesExist() {
        assumeThat(Files.isDirectory(GO_CORPUS))
                .as("go test corpus directory must exist with real packages")
                .isTrue();
    }

    @BeforeEach
    void setUp() {
        handler = new GoHandler();
    }

    // --- Lifecycle tests ---

    /**
     * Goal: Verify begin() returns a GoMemento with extracted metadata.
     * Rationale: The handler must create a populated memento during begin.
     */
    @Test
    void begin_returnsGoMementoWithMetadata() throws Exception {
        Path zip = GO_CORPUS.resolve("github.com_gin-gonic_gin@v1.9.1.zip");
        assumeThat(Files.exists(zip)).isTrue();

        try (FileInputStream fis = new FileInputStream(zip.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("github.com_gin-gonic_gin@v1.9.1.zip"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(GoMemento.class);
            GoMemento goMemento = (GoMemento) memento;
            assertThat(goMemento.packageName()).hasValue("github.com/gin-gonic/gin");
            assertThat(goMemento.packageVersion()).hasValue("v1.9.1");
            assertThat(goMemento.rawGoMod()).isNotEmpty();
        }
    }

    /**
     * Goal: Verify begin() with a malformed stream returns a memento with no metadata.
     * Rationale: The handler should gracefully handle corrupt archives without throwing.
     */
    @Test
    void begin_malformedStream_returnsMementoWithNoMetadata() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.zip"),
                stubWorkItem(), stubMarker());

        assertThat(memento).isInstanceOf(GoMemento.class);
        GoMemento goMemento = (GoMemento) memento;
        assertThat(goMemento.packageName()).isEmpty();
        assertThat(goMemento.packageVersion()).isEmpty();
    }

    /**
     * Goal: Verify getMetadata returns populated metadata list.
     * Rationale: Metadata must be mapped from MetadataResult to rodeo Metadata format.
     */
    @Test
    void getMetadata_returnsPopulatedList() throws Exception {
        Path zip = GO_CORPUS.resolve("github.com_gin-gonic_gin@v1.9.1.zip");
        assumeThat(Files.exists(zip)).isTrue();

        try (FileInputStream fis = new FileInputStream(zip.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("github.com_gin-gonic_gin@v1.9.1.zip"),
                    stubWorkItem(), stubMarker());

            List<Metadata> metadata = handler.getMetadata(memento,
                    stubArtifact("github.com_gin-gonic_gin@v1.9.1.zip"),
                    stubWorkItem(), stubMarker());

            assertThat(metadata).isNotEmpty();
            assertThat(metadata.stream().map(Metadata::tag))
                    .contains(MetadataTag.NAME, MetadataTag.VERSION);

            String name = metadata.stream()
                    .filter(m -> m.tag() == MetadataTag.NAME)
                    .findFirst().map(Metadata::value).orElse(null);
            assertThat(name).isEqualTo("github.com/gin-gonic/gin");
        }
    }

    /**
     * Goal: Verify getMetadata returns empty list for malformed input.
     * Rationale: When begin fails to extract metadata, getMetadata should return empty, not throw.
     */
    @Test
    void getMetadata_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.zip"),
                stubWorkItem(), stubMarker());

        List<Metadata> metadata = handler.getMetadata(memento, stubArtifact("bad.zip"),
                stubWorkItem(), stubMarker());

        assertThat(metadata).isEmpty();
    }

    // --- PURL tests ---

    /**
     * Goal: Verify PURL generation for a standard Go module.
     * Rationale: PURL must be pkg:golang/namespace/name@version.
     */
    @Test
    void getPurls_standardModule_generatesCorrectPurl() throws Exception {
        Path zip = GO_CORPUS.resolve("github.com_gin-gonic_gin@v1.9.1.zip");
        assumeThat(Files.exists(zip)).isTrue();

        try (FileInputStream fis = new FileInputStream(zip.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("github.com_gin-gonic_gin@v1.9.1.zip"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("github.com_gin-gonic_gin@v1.9.1.zip"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:golang/github.com/gin-gonic/gin@v1.9.1");
        }
    }

    /**
     * Goal: Verify PURL generation for a golang.org/x module.
     * Rationale: Q1 - golang.org/x modules have non-github URL paths.
     */
    @Test
    void getPurls_golangOrgXModule() throws Exception {
        Path zip = GO_CORPUS.resolve("golang.org_x_text@v0.14.0.zip");
        assumeThat(Files.exists(zip)).isTrue();

        try (FileInputStream fis = new FileInputStream(zip.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("golang.org_x_text@v0.14.0.zip"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("golang.org_x_text@v0.14.0.zip"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:golang/golang.org/x/text@v0.14.0");
        }
    }

    /**
     * Goal: Verify PURL generation for a /vN major version module.
     * Rationale: Q5 - /vN is part of the namespace in PURL.
     */
    @Test
    void getPurls_majorVersionModule() throws Exception {
        Path zip = GO_CORPUS.resolve("github.com_go-chi_chi_v5@v5.0.11.zip");
        assumeThat(Files.exists(zip)).isTrue();

        try (FileInputStream fis = new FileInputStream(zip.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("github.com_go-chi_chi_v5@v5.0.11.zip"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("github.com_go-chi_chi_v5@v5.0.11.zip"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:golang/github.com/go-chi/chi/v5@v5.0.11");
        }
    }

    /**
     * Goal: Verify PURL is empty for malformed input.
     * Rationale: When metadata extraction fails, no PURL should be generated.
     */
    @Test
    void getPurls_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.zip"),
                stubWorkItem(), stubMarker());

        List<Purl> purls = handler.getPurls(memento, stubArtifact("bad.zip"),
                stubWorkItem(), stubMarker());

        assertThat(purls).isEmpty();
    }

    // --- Handler properties ---

    /**
     * Goal: Verify the handler's ecosystem ID.
     * Rationale: Handler must identify itself as GO ecosystem.
     */
    @Test
    void ecosystemId_isGo() {
        assertThat(handler.ecosystemId()).isEqualTo(EcosystemId.GO);
    }

    /**
     * Goal: Verify the handler does not require file input.
     * Rationale: Go handler works with InputStreams, not files.
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
        Path zip = GO_CORPUS.resolve("github.com_gin-gonic_gin@v1.9.1.zip");
        assumeThat(Files.exists(zip)).isTrue();

        try (FileInputStream fis = new FileInputStream(zip.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("github.com_gin-gonic_gin@v1.9.1.zip"),
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
        Path pkg1 = GO_CORPUS.resolve("github.com_gin-gonic_gin@v1.9.1.zip");
        Path pkg2 = GO_CORPUS.resolve("golang.org_x_text@v0.14.0.zip");
        assumeThat(Files.exists(pkg1)).isTrue();
        assumeThat(Files.exists(pkg2)).isTrue();

        GoHandler handler1 = new GoHandler();
        GoHandler handler2 = new GoHandler();

        ArtifactMemento memento1;
        ArtifactMemento memento2;

        try (FileInputStream fis1 = new FileInputStream(pkg1.toFile())) {
            memento1 = handler1.begin(fis1, stubArtifact("github.com_gin-gonic_gin@v1.9.1.zip"),
                    stubWorkItem(), stubMarker());
        }
        try (FileInputStream fis2 = new FileInputStream(pkg2.toFile())) {
            memento2 = handler2.begin(fis2, stubArtifact("golang.org_x_text@v0.14.0.zip"),
                    stubWorkItem(), stubMarker());
        }

        assertThat(memento1).isInstanceOf(GoMemento.class);
        assertThat(memento2).isInstanceOf(GoMemento.class);
        assertThat(((GoMemento) memento1).packageName()).hasValue("github.com/gin-gonic/gin");
        assertThat(((GoMemento) memento2).packageName()).hasValue("golang.org/x/text");

        List<Purl> purls1 = handler1.getPurls(memento1,
                stubArtifact("github.com_gin-gonic_gin@v1.9.1.zip"),
                stubWorkItem(), stubMarker());
        List<Purl> purls2 = handler2.getPurls(memento2,
                stubArtifact("golang.org_x_text@v0.14.0.zip"),
                stubWorkItem(), stubMarker());

        assertThat(purls1).hasSize(1);
        assertThat(purls2).hasSize(1);
        assertThat(purls1.get(0).toString()).isEqualTo("pkg:golang/github.com/gin-gonic/gin@v1.9.1");
        assertThat(purls2.get(0).toString()).isEqualTo("pkg:golang/golang.org/x/text@v0.14.0");
    }

    // --- Parameterized PURL test across all packages ---

    static Stream<Arguments> allPackages() throws IOException {
        if (!Files.isDirectory(GO_CORPUS)) {
            return Stream.empty();
        }
        return Files.list(GO_CORPUS)
                .filter(p -> p.toString().endsWith(".zip"))
                .sorted()
                .map(pkg -> Arguments.of(pkg.getFileName().toString(), pkg));
    }

    /**
     * Goal: Verify PURL generation succeeds for every real Go module package.
     * Rationale: Every valid Go module must produce a PURL or empty list, never throw.
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
            assertThat(purls.get(0).toString()).startsWith("pkg:golang/");
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
