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

package io.spicelabs.annatto.cocoapods;

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
 * Tests for {@link CocoapodsHandler} covering the full handler lifecycle.
 * Uses real CocoaPods podspec.json files from trunk.cocoapods.org.
 */
class CocoapodsHandlerTest {

    private static final Path COCOAPODS_CORPUS = TestCorpusDownloader.corpusDir("cocoapods");

    private CocoapodsHandler handler;

    @BeforeAll
    static void ensureTestPackagesExist() {
        assumeThat(Files.isDirectory(COCOAPODS_CORPUS))
                .as("cocoapods test corpus directory must exist")
                .isTrue();
    }

    @BeforeEach
    void setUp() {
        handler = new CocoapodsHandler();
    }

    // --- Lifecycle tests ---

    /**
     * Goal: Verify begin() returns a CocoapodsMemento with extracted metadata.
     * Rationale: Handler must create a populated memento from podspec.json.
     */
    @Test
    void begin_returnsMementoWithMetadata() throws Exception {
        Path pkg = COCOAPODS_CORPUS.resolve("Alamofire-5.8.1.podspec.json");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Alamofire-5.8.1.podspec.json"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(CocoapodsMemento.class);
            CocoapodsMemento cocoapodsMemento = (CocoapodsMemento) memento;
            assertThat(cocoapodsMemento.packageName()).hasValue("Alamofire");
            assertThat(cocoapodsMemento.packageVersion()).hasValue("5.8.1");
        }
    }

    /**
     * Goal: Verify begin() with malformed stream returns empty memento.
     * Rationale: Handler must gracefully handle corrupt input.
     */
    @Test
    void begin_malformedStream_returnsMementoWithNoMetadata() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.podspec.json"),
                stubWorkItem(), stubMarker());

        assertThat(memento).isInstanceOf(CocoapodsMemento.class);
        CocoapodsMemento cocoapodsMemento = (CocoapodsMemento) memento;
        assertThat(cocoapodsMemento.packageName()).isEmpty();
    }

    /**
     * Goal: Verify getMetadata returns populated metadata list.
     * Rationale: Metadata must map from MetadataResult to rodeo Metadata format.
     */
    @Test
    void getMetadata_returnsPopulatedList() throws Exception {
        Path pkg = COCOAPODS_CORPUS.resolve("Alamofire-5.8.1.podspec.json");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Alamofire-5.8.1.podspec.json"),
                    stubWorkItem(), stubMarker());

            List<Metadata> metadata = handler.getMetadata(memento,
                    stubArtifact("Alamofire-5.8.1.podspec.json"),
                    stubWorkItem(), stubMarker());

            assertThat(metadata).isNotEmpty();
            assertThat(metadata.stream().map(Metadata::tag))
                    .contains(MetadataTag.NAME, MetadataTag.VERSION);
        }
    }

    // --- PURL tests ---

    /**
     * Goal: Verify PURL format for CocoaPods.
     * Rationale: PURL must be pkg:cocoapods/Name@version.
     */
    @Test
    void getPurls_correctFormat() throws Exception {
        Path pkg = COCOAPODS_CORPUS.resolve("Alamofire-5.8.1.podspec.json");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Alamofire-5.8.1.podspec.json"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("Alamofire-5.8.1.podspec.json"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:cocoapods/Alamofire@5.8.1");
        }
    }

    /**
     * Goal: Verify PURL preserves case in name.
     * Rationale: Q7 — Pod names are case-sensitive.
     */
    @Test
    void getPurls_preservesCase() throws Exception {
        Path pkg = COCOAPODS_CORPUS.resolve("AFNetworking-4.0.1.podspec.json");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("AFNetworking-4.0.1.podspec.json"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("AFNetworking-4.0.1.podspec.json"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            assertThat(purls.get(0).toString()).isEqualTo("pkg:cocoapods/AFNetworking@4.0.1");
        }
    }

    /**
     * Goal: Verify PURL is empty for malformed input.
     * Rationale: When extraction fails, no PURL should be generated.
     */
    @Test
    void getPurls_malformedInput_returnsEmptyList() {
        InputStream badStream = new java.io.ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream, stubArtifact("bad.podspec.json"),
                stubWorkItem(), stubMarker());

        List<Purl> purls = handler.getPurls(memento, stubArtifact("bad.podspec.json"),
                stubWorkItem(), stubMarker());

        assertThat(purls).isEmpty();
    }

    // --- Handler properties ---

    /**
     * Goal: Verify the handler's ecosystem ID.
     * Rationale: Handler must identify itself as COCOAPODS.
     */
    @Test
    void ecosystemId_isCocoapods() {
        assertThat(handler.ecosystemId()).isEqualTo(EcosystemId.COCOAPODS);
    }

    /**
     * Goal: Verify the handler does not require file input.
     * Rationale: CocoaPods handler works with InputStreams.
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
        Path pkg = COCOAPODS_CORPUS.resolve("Alamofire-5.8.1.podspec.json");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("Alamofire-5.8.1.podspec.json"),
                    stubWorkItem(), stubMarker());
            handler.end(memento);
        }
    }

    // --- Parameterized begin + getPurls ---

    static Stream<Arguments> allPackages() throws IOException {
        if (!Files.isDirectory(COCOAPODS_CORPUS)) {
            return Stream.empty();
        }
        return Files.list(COCOAPODS_CORPUS)
                .filter(p -> p.toString().endsWith(".podspec.json"))
                .sorted()
                .map(pkg -> Arguments.of(pkg.getFileName().toString(), pkg));
    }

    /**
     * Goal: Verify begin() succeeds for every real CocoaPods package.
     * Rationale: All packages must be handled without throwing.
     */
    @ParameterizedTest(name = "begin: {0}")
    @MethodSource("allPackages")
    void begin_neverThrows(String label, Path pkgPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(pkgPath.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact(label),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(CocoapodsMemento.class);
            CocoapodsMemento cm = (CocoapodsMemento) memento;
            assertThat(cm.packageName()).isPresent();
        }
    }

    /**
     * Goal: Verify PURL generation succeeds for every real package.
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
            assertThat(purls.get(0).toString()).startsWith("pkg:cocoapods/");
        }
    }

    // --- Stub helpers ---

    private static RodeoArtifact stubArtifact(String filename) {
        return new RodeoArtifact() {
            @Override public String getPath() { return "/test/" + filename; }
            @Override public long getSize() { return 0; }
            @Override public String getMimeType() { return "application/json"; }
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
