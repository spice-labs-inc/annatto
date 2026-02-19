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

package io.spicelabs.annatto.luarocks;

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

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * Tests for {@link LuarocksHandler} covering the full handler lifecycle:
 * begin -> getMetadata -> getPurls -> end.
 *
 * <p>Uses real LuaRocks packages downloaded from luarocks.org.
 * Package files (.src.rock, .rockspec) are in {@code test-corpus/luarocks/} (gitignored).</p>
 */
class LuarocksHandlerTest {

    private static final Path LUAROCKS_CORPUS = TestCorpusDownloader.corpusDir("luarocks");

    private LuarocksHandler handler;

    @BeforeAll
    static void ensureTestPackagesExist() {
        assumeThat(Files.isDirectory(LUAROCKS_CORPUS))
                .as("luarocks test corpus directory must exist with real packages")
                .isTrue();
    }

    @BeforeEach
    void setUp() {
        handler = new LuarocksHandler();
    }

    // --- Lifecycle tests ---

    /**
     * Goal: Verify begin() returns a LuarocksMemento with metadata from .src.rock.
     * Rationale: Q1 - The handler must extract metadata from ZIP archives containing rockspecs.
     */
    @Test
    void begin_srcRock_returnsMementoWithMetadata() throws Exception {
        Path pkg = LUAROCKS_CORPUS.resolve("luasocket-3.1.0-1.src.rock");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("luasocket-3.1.0-1.src.rock"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(LuarocksMemento.class);
            LuarocksMemento luaMemento = (LuarocksMemento) memento;
            assertThat(luaMemento.packageName()).hasValue("LuaSocket");
            assertThat(luaMemento.packageVersion()).hasValue("3.1.0-1");
        }
    }

    /**
     * Goal: Verify begin() returns a LuarocksMemento with metadata from .rockspec.
     * Rationale: Q1 - The handler must extract metadata from standalone rockspec files.
     */
    @Test
    void begin_rockspec_returnsMementoWithMetadata() throws Exception {
        Path pkg = LUAROCKS_CORPUS.resolve("mediator_lua-1.1.2-0.rockspec");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("mediator_lua-1.1.2-0.rockspec"),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(LuarocksMemento.class);
            LuarocksMemento luaMemento = (LuarocksMemento) memento;
            assertThat(luaMemento.packageName()).hasValue("mediator_lua");
        }
    }

    /**
     * Goal: Verify begin() with a malformed stream returns a memento with no metadata.
     * Rationale: The handler should gracefully handle corrupt input without throwing.
     */
    @Test
    void begin_malformedStream_returnsMementoWithNoMetadata() {
        InputStream badStream = new ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream,
                stubArtifact("bad.src.rock"),
                stubWorkItem(), stubMarker());

        assertThat(memento).isInstanceOf(LuarocksMemento.class);
        LuarocksMemento luaMemento = (LuarocksMemento) memento;
        assertThat(luaMemento.packageName()).isEmpty();
        assertThat(luaMemento.packageVersion()).isEmpty();
    }

    /**
     * Goal: Verify getMetadata returns populated metadata list.
     * Rationale: Metadata must be mapped from MetadataResult to rodeo Metadata format.
     */
    @Test
    void getMetadata_returnsPopulatedList() throws Exception {
        Path pkg = LUAROCKS_CORPUS.resolve("luasocket-3.1.0-1.src.rock");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("luasocket-3.1.0-1.src.rock"),
                    stubWorkItem(), stubMarker());

            List<Metadata> metadata = handler.getMetadata(memento,
                    stubArtifact("luasocket-3.1.0-1.src.rock"),
                    stubWorkItem(), stubMarker());

            assertThat(metadata).isNotEmpty();
            assertThat(metadata.stream().map(Metadata::tag))
                    .contains(MetadataTag.NAME, MetadataTag.VERSION);
        }
    }

    /**
     * Goal: Verify getMetadata returns empty list for malformed input.
     * Rationale: When begin fails, getMetadata should return empty, not throw.
     */
    @Test
    void getMetadata_malformedInput_returnsEmptyList() {
        InputStream badStream = new ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream,
                stubArtifact("bad.src.rock"),
                stubWorkItem(), stubMarker());

        List<Metadata> metadata = handler.getMetadata(memento,
                stubArtifact("bad.src.rock"),
                stubWorkItem(), stubMarker());

        assertThat(metadata).isEmpty();
    }

    // --- PURL tests ---

    /**
     * Goal: Verify PURL generation for .src.rock with lowercased name (Q8).
     * Rationale: LuaRocks PURL names must be ASCII lowercased per purl-spec.
     */
    @Test
    void getPurls_nameLowercased() throws Exception {
        Path pkg = LUAROCKS_CORPUS.resolve("luafilesystem-1.8.0-1.src.rock");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("luafilesystem-1.8.0-1.src.rock"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("luafilesystem-1.8.0-1.src.rock"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            String purlStr = purls.get(0).toString();
            // Package name is "LuaFileSystem" but PURL should be lowercased
            assertThat(purlStr).startsWith("pkg:luarocks/luafilesystem@");
        }
    }

    /**
     * Goal: Verify PURL has no namespace.
     * Rationale: Q8 - LuaRocks has no namespace concept in PURL.
     */
    @Test
    void getPurls_noNamespace() throws Exception {
        Path pkg = LUAROCKS_CORPUS.resolve("luasocket-3.1.0-1.src.rock");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("luasocket-3.1.0-1.src.rock"),
                    stubWorkItem(), stubMarker());

            List<Purl> purls = handler.getPurls(memento,
                    stubArtifact("luasocket-3.1.0-1.src.rock"),
                    stubWorkItem(), stubMarker());

            assertThat(purls).hasSize(1);
            String purlStr = purls.get(0).toString();
            // No namespace means single slash after type
            assertThat(purlStr).startsWith("pkg:luarocks/luasocket@");
        }
    }

    /**
     * Goal: Verify PURL is empty for malformed input.
     * Rationale: When metadata extraction fails, no PURL should be generated.
     */
    @Test
    void getPurls_malformedInput_returnsEmptyList() {
        InputStream badStream = new ByteArrayInputStream(new byte[]{0, 1, 2, 3});
        ArtifactMemento memento = handler.begin(badStream,
                stubArtifact("bad.src.rock"),
                stubWorkItem(), stubMarker());

        List<Purl> purls = handler.getPurls(memento,
                stubArtifact("bad.src.rock"),
                stubWorkItem(), stubMarker());

        assertThat(purls).isEmpty();
    }

    // --- Handler properties ---

    /**
     * Goal: Verify the handler's ecosystem ID.
     * Rationale: Handler must identify itself as LUAROCKS ecosystem.
     */
    @Test
    void ecosystemId_isLuarocks() {
        assertThat(handler.ecosystemId()).isEqualTo(EcosystemId.LUAROCKS);
    }

    /**
     * Goal: Verify the handler does not require file input.
     * Rationale: LuaRocks handler works with InputStreams, not files.
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
        Path pkg = LUAROCKS_CORPUS.resolve("lpeg-1.1.0-1.src.rock");
        assumeThat(Files.exists(pkg)).isTrue();

        try (FileInputStream fis = new FileInputStream(pkg.toFile())) {
            ArtifactMemento memento = handler.begin(fis,
                    stubArtifact("lpeg-1.1.0-1.src.rock"),
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
        Path pkg1 = LUAROCKS_CORPUS.resolve("luasocket-3.1.0-1.src.rock");
        Path pkg2 = LUAROCKS_CORPUS.resolve("lpeg-1.1.0-1.src.rock");
        assumeThat(Files.exists(pkg1)).isTrue();
        assumeThat(Files.exists(pkg2)).isTrue();

        LuarocksHandler handler1 = new LuarocksHandler();
        LuarocksHandler handler2 = new LuarocksHandler();

        ArtifactMemento memento1;
        ArtifactMemento memento2;

        try (FileInputStream fis1 = new FileInputStream(pkg1.toFile())) {
            memento1 = handler1.begin(fis1,
                    stubArtifact("luasocket-3.1.0-1.src.rock"),
                    stubWorkItem(), stubMarker());
        }
        try (FileInputStream fis2 = new FileInputStream(pkg2.toFile())) {
            memento2 = handler2.begin(fis2,
                    stubArtifact("lpeg-1.1.0-1.src.rock"),
                    stubWorkItem(), stubMarker());
        }

        assertThat(((LuarocksMemento) memento1).packageName()).hasValue("LuaSocket");
        assertThat(((LuarocksMemento) memento2).packageName()).hasValue("LPeg");

        List<Purl> purls1 = handler1.getPurls(memento1,
                stubArtifact("luasocket-3.1.0-1.src.rock"),
                stubWorkItem(), stubMarker());
        List<Purl> purls2 = handler2.getPurls(memento2,
                stubArtifact("lpeg-1.1.0-1.src.rock"),
                stubWorkItem(), stubMarker());

        assertThat(purls1).hasSize(1);
        assertThat(purls2).hasSize(1);
        assertThat(purls1.get(0).toString()).startsWith("pkg:luarocks/luasocket@");
        assertThat(purls2.get(0).toString()).startsWith("pkg:luarocks/lpeg@");
    }

    // --- Parameterized begin + getPurls across all packages ---

    static Stream<Arguments> allPackages() throws IOException {
        if (!Files.isDirectory(LUAROCKS_CORPUS)) {
            return Stream.empty();
        }
        return Files.list(LUAROCKS_CORPUS)
                .filter(p -> {
                    String name = p.toString();
                    return name.endsWith(".src.rock") || name.endsWith(".all.rock")
                            || name.endsWith(".rockspec");
                })
                .sorted()
                .map(pkg -> Arguments.of(pkg.getFileName().toString(), pkg));
    }

    /**
     * Goal: Verify begin() succeeds for every real LuaRocks package.
     * Rationale: Q1 - Both .rock and .rockspec formats must be handled.
     */
    @ParameterizedTest(name = "begin: {0}")
    @MethodSource("allPackages")
    void begin_neverThrows(String label, Path pkgPath) throws Exception {
        try (FileInputStream fis = new FileInputStream(pkgPath.toFile())) {
            ArtifactMemento memento = handler.begin(fis, stubArtifact(label),
                    stubWorkItem(), stubMarker());

            assertThat(memento).isInstanceOf(LuarocksMemento.class);
            LuarocksMemento luaMemento = (LuarocksMemento) memento;
            assertThat(luaMemento.packageName()).isPresent();
        }
    }

    /**
     * Goal: Verify PURL generation succeeds for every real LuaRocks package.
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
            assertThat(purls.get(0).toString()).startsWith("pkg:luarocks/");
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
