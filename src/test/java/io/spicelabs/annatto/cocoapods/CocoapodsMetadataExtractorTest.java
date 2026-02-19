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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import io.spicelabs.annatto.testutil.SourceOfTruth;
import io.spicelabs.annatto.testutil.TestCorpusDownloader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Tests for {@link CocoapodsMetadataExtractor} comparing extraction results against
 * source-of-truth expected values produced by native Ruby tools in Docker.
 *
 * <p>All test packages are real CocoaPods podspec.json files from trunk.cocoapods.org.</p>
 *
 * <p>Covers {@link CocoapodsQuirks} Q1-Q7.</p>
 */
class CocoapodsMetadataExtractorTest {

    private static final Path COCOAPODS_CORPUS = TestCorpusDownloader.corpusDir("cocoapods");
    private static final Path COCOAPODS_EXPECTED = TestCorpusDownloader.expectedDir("cocoapods");
    private static final Gson GSON = new Gson();

    @BeforeAll
    static void ensureTestPackagesExist() {
        assumeThat(Files.isDirectory(COCOAPODS_CORPUS))
                .as("cocoapods test corpus directory must exist")
                .isTrue();
    }

    // --- Parameterized source-of-truth comparison ---

    static Stream<Arguments> realPackages() throws IOException {
        if (!Files.isDirectory(COCOAPODS_CORPUS)) {
            return Stream.empty();
        }
        return Files.list(COCOAPODS_CORPUS)
                .filter(p -> p.toString().endsWith(".podspec.json"))
                .sorted()
                .map(pkg -> {
                    String label = pkg.getFileName().toString();
                    String base = label.replace(".podspec.json", "");
                    Path expected = COCOAPODS_EXPECTED.resolve(base + "-expected.json");
                    return Arguments.of(label, pkg, expected);
                });
    }

    /**
     * Goal: Verify extracted name matches source-of-truth.
     * Rationale: Name is case-sensitive (Q7).
     */
    @ParameterizedTest(name = "name: {0}")
    @MethodSource("realPackages")
    void extractName_matchesSourceOfTruth(String label, Path pkgPath, Path expectedPath) throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        JsonObject expected = SourceOfTruth.loadExpected(expectedPath);
        assertThat(result.name()).isEqualTo(SourceOfTruth.getString(expected, "name"));
    }

    /**
     * Goal: Verify simpleName equals name.
     * Rationale: No vendor/namespace concept in CocoaPods.
     */
    @ParameterizedTest(name = "simpleName: {0}")
    @MethodSource("realPackages")
    void extractSimpleName_matchesSourceOfTruth(String label, Path pkgPath, Path expectedPath) throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        JsonObject expected = SourceOfTruth.loadExpected(expectedPath);
        assertThat(result.simpleName()).isEqualTo(SourceOfTruth.getString(expected, "simpleName"));
    }

    /**
     * Goal: Verify extracted version matches source-of-truth.
     * Rationale: Version must be exact from podspec.json.
     */
    @ParameterizedTest(name = "version: {0}")
    @MethodSource("realPackages")
    void extractVersion_matchesSourceOfTruth(String label, Path pkgPath, Path expectedPath) throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        JsonObject expected = SourceOfTruth.loadExpected(expectedPath);
        assertThat(result.version()).isEqualTo(SourceOfTruth.getString(expected, "version"));
    }

    /**
     * Goal: Verify extracted description matches source-of-truth.
     * Rationale: Summary preferred, description fallback.
     */
    @ParameterizedTest(name = "description: {0}")
    @MethodSource("realPackages")
    void extractDescription_matchesSourceOfTruth(String label, Path pkgPath, Path expectedPath) throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        JsonObject expected = SourceOfTruth.loadExpected(expectedPath);
        assertThat(result.description()).isEqualTo(SourceOfTruth.getString(expected, "description"));
    }

    /**
     * Goal: Verify extracted license matches source-of-truth.
     * Rationale: Q2 — License can be string or object with "type".
     */
    @ParameterizedTest(name = "license: {0}")
    @MethodSource("realPackages")
    void extractLicense_matchesSourceOfTruth(String label, Path pkgPath, Path expectedPath) throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        JsonObject expected = SourceOfTruth.loadExpected(expectedPath);
        assertThat(result.license()).isEqualTo(SourceOfTruth.getString(expected, "license"));
    }

    /**
     * Goal: Verify extracted publisher matches source-of-truth.
     * Rationale: Q3 — Authors can be map, string, or array.
     */
    @ParameterizedTest(name = "publisher: {0}")
    @MethodSource("realPackages")
    void extractPublisher_matchesSourceOfTruth(String label, Path pkgPath, Path expectedPath) throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        JsonObject expected = SourceOfTruth.loadExpected(expectedPath);
        assertThat(result.publisher()).isEqualTo(SourceOfTruth.getString(expected, "publisher"));
    }

    /**
     * Goal: Verify publishedAt is always null.
     * Rationale: Q6 — No timestamp in podspec.json.
     */
    @ParameterizedTest(name = "publishedAt: {0}")
    @MethodSource("realPackages")
    void extractPublishedAt_alwaysNull(String label, Path pkgPath, Path expectedPath) throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        assertThat(result.publishedAt()).isEmpty();
    }

    /**
     * Goal: Verify extracted dependency count matches source-of-truth.
     * Rationale: Q4, Q5 — All dependencies including subspecs must be counted.
     */
    @ParameterizedTest(name = "depCount: {0}")
    @MethodSource("realPackages")
    void extractDependencyCount_matchesSourceOfTruth(String label, Path pkgPath, Path expectedPath)
            throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        JsonObject expected = SourceOfTruth.loadExpected(expectedPath);
        int expectedCount = expected.getAsJsonArray("dependencies").size();
        assertThat(result.dependencies()).as("dependency count for %s", label).hasSize(expectedCount);
    }

    /**
     * Goal: Verify extracted dependency details (name, version, scope) match source-of-truth.
     * Rationale: Q4, Q5 — Dependency name, version constraint, and scope must match native Ruby extraction.
     */
    @ParameterizedTest(name = "deps: {0}")
    @MethodSource("realPackages")
    void extractDependencies_matchSourceOfTruth(String label, Path pkgPath, Path expectedPath) throws Exception {
        MetadataResult result = extractFromPackage(pkgPath);
        JsonObject expected = SourceOfTruth.loadExpected(expectedPath);

        JsonArray expectedDeps = expected.getAsJsonArray("dependencies");
        List<DepTuple> expectedTuples = new java.util.ArrayList<>();
        for (var elem : expectedDeps) {
            JsonObject depObj = elem.getAsJsonObject();
            String name = depObj.get("name").getAsString();
            String vc = depObj.get("versionConstraint").isJsonNull() ? null
                    : depObj.get("versionConstraint").getAsString();
            String scope = depObj.get("scope").getAsString();
            expectedTuples.add(new DepTuple(name, vc, scope));
        }

        List<DepTuple> actualTuples = result.dependencies().stream()
                .map(d -> new DepTuple(d.name(), d.versionConstraint().orElse(null), d.scope().orElse(null)))
                .toList();

        assertThat(actualTuples).as("dependencies for %s", label)
                .containsExactlyInAnyOrderElementsOf(expectedTuples);
    }

    // --- Named package tests ---

    /**
     * Goal: Verify Alamofire extraction (zero deps, simple structure).
     * Rationale: Basic pod with no dependencies.
     */
    @Test
    void package_alamofire_zeroDeps() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("Alamofire-5.8.1.podspec.json"));

        assertThat(result.name()).hasValue("Alamofire");
        assertThat(result.version()).hasValue("5.8.1");
        assertThat(result.license()).hasValue("MIT");
        assertThat(result.description()).hasValue("Elegant HTTP Networking in Swift");
        assertThat(result.dependencies()).isEmpty();
    }

    /**
     * Goal: Verify AFNetworking extraction (subspecs, self-referencing deps).
     * Rationale: Q4 — Subspec deps that reference parent pod must be filtered.
     */
    @Test
    void package_afNetworking_selfReferencingFiltered() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("AFNetworking-4.0.1.podspec.json"));

        assertThat(result.name()).hasValue("AFNetworking");
        assertThat(result.version()).hasValue("4.0.1");
        // All deps are self-referencing (AFNetworking/Serialization etc.) so none survive
        assertThat(result.dependencies()).isEmpty();
    }

    /**
     * Goal: Verify RxCocoa extraction (simple external deps).
     * Rationale: Pod with clear external dependencies.
     */
    @Test
    void package_rxCocoa_externalDeps() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("RxCocoa-6.7.1.podspec.json"));

        assertThat(result.name()).hasValue("RxCocoa");
        assertThat(result.dependencies()).isNotEmpty();
        assertThat(result.dependencies().stream().map(ParsedDependency::name))
                .contains("RxSwift", "RxRelay");
    }

    /**
     * Goal: Verify Moya extraction (default_subspecs).
     * Rationale: Q4 — Only default subspecs' dependencies should be included.
     */
    @Test
    void package_moya_defaultSubspecs() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("Moya-15.0.0.podspec.json"));

        assertThat(result.name()).hasValue("Moya");
        assertThat(result.version()).hasValue("15.0.0");
        assertThat(result.dependencies().stream().map(ParsedDependency::name))
                .contains("Alamofire");
    }

    /**
     * Goal: Verify Reachability extraction (BSD license as object with type).
     * Rationale: Q2 — License object with "type" field.
     */
    @Test
    void package_reachability_licenseObject() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("Reachability-3.2.podspec.json"));

        assertThat(result.name()).hasValue("Reachability");
        assertThat(result.license()).hasValue("BSD");
    }

    /**
     * Goal: Verify Firebase extraction (many subspecs with deps).
     * Rationale: Complex pod with many subspecs and default_subspecs.
     */
    @Test
    void package_firebase_manySubspecs() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("Firebase-10.22.0.podspec.json"));

        assertThat(result.name()).hasValue("Firebase");
        assertThat(result.version()).hasValue("10.22.0");
        assertThat(result.license()).hasValue("Apache-2.0");
    }

    /**
     * Goal: Verify abseil (long version, many subspecs).
     * Rationale: Version edge case — datestamp version with patch.
     */
    @Test
    void package_abseil_longVersion() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("abseil-1.20240116.1.podspec.json"));

        assertThat(result.name()).hasValue("abseil");
        assertThat(result.version()).hasValue("1.20240116.1");
    }

    /**
     * Goal: Verify case sensitivity in pod name.
     * Rationale: Q7 — PURL must preserve original casing.
     */
    @Test
    void package_lottieIos_caseSensitive() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("lottie-ios-4.4.1.podspec.json"));

        assertThat(result.name()).hasValue("lottie-ios");
    }

    /**
     * Goal: Verify BoringSSL-GRPC extraction (large podspec).
     * Rationale: Stress test for large podspec with many subspecs.
     */
    @Test
    void package_boringSSL_largePodspec() throws Exception {
        MetadataResult result = extractFromPackage(findPackage("BoringSSL-GRPC-0.0.32.podspec.json"));

        assertThat(result.name()).hasValue("BoringSSL-GRPC");
        assertThat(result.version()).hasValue("0.0.32");
    }

    // --- Unit tests for extractLicense ---

    /**
     * Goal: Verify string license extraction.
     * Rationale: Q2 — Direct string license.
     */
    @Test
    void extractLicense_stringDirect() {
        JsonObject root = new JsonObject();
        root.addProperty("license", "MIT");
        assertThat(CocoapodsMetadataExtractor.extractLicense(root)).hasValue("MIT");
    }

    /**
     * Goal: Verify object license extraction.
     * Rationale: Q2 — License as {"type": "MIT"}.
     */
    @Test
    void extractLicense_objectType() {
        JsonObject root = new JsonObject();
        JsonObject lic = new JsonObject();
        lic.addProperty("type", "Apache-2.0");
        lic.addProperty("text", "Full license text...");
        root.add("license", lic);
        assertThat(CocoapodsMetadataExtractor.extractLicense(root)).hasValue("Apache-2.0");
    }

    /**
     * Goal: Verify absent license.
     * Rationale: Q2 — Missing license field.
     */
    @Test
    void extractLicense_absent() {
        JsonObject root = new JsonObject();
        assertThat(CocoapodsMetadataExtractor.extractLicense(root)).isEmpty();
    }

    // --- Unit tests for extractPublisher ---

    /**
     * Goal: Verify map-style authors.
     * Rationale: Q3 — Authors as {"Name": "email"} map.
     */
    @Test
    void extractPublisher_mapKeys() {
        JsonObject root = new JsonObject();
        JsonObject authors = new JsonObject();
        authors.addProperty("Alice", "alice@example.com");
        authors.addProperty("Bob", "bob@example.com");
        root.add("authors", authors);
        // Preserves JSON insertion order
        assertThat(CocoapodsMetadataExtractor.extractPublisher(root)).hasValue("Alice, Bob");
    }

    /**
     * Goal: Verify string-style author.
     * Rationale: Q3 — Author as a plain string.
     */
    @Test
    void extractPublisher_string() {
        JsonObject root = new JsonObject();
        root.addProperty("authors", "Charlie");
        assertThat(CocoapodsMetadataExtractor.extractPublisher(root)).hasValue("Charlie");
    }

    /**
     * Goal: Verify array-style authors.
     * Rationale: Q3 — Authors as an array of strings.
     */
    @Test
    void extractPublisher_array() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add("Alice");
        arr.add("Bob");
        root.add("authors", arr);
        assertThat(CocoapodsMetadataExtractor.extractPublisher(root)).hasValue("Alice, Bob");
    }

    /**
     * Goal: Verify fallback to singular "author" field.
     * Rationale: Q3 — Some pods use "author" instead of "authors".
     */
    @Test
    void extractPublisher_fallbackToAuthor() {
        JsonObject root = new JsonObject();
        root.addProperty("author", "Dave");
        assertThat(CocoapodsMetadataExtractor.extractPublisher(root)).hasValue("Dave");
    }

    // --- Unit tests for extractDependencies ---

    /**
     * Goal: Verify empty version array maps to null constraint.
     * Rationale: Q5 — Empty array means "any version".
     */
    @Test
    void extractDeps_emptyVersionIsNull() {
        JsonObject root = new JsonObject();
        root.addProperty("name", "TestPod");
        JsonObject deps = new JsonObject();
        deps.add("OtherPod", new JsonArray());
        root.add("dependencies", deps);

        List<ParsedDependency> result = CocoapodsMetadataExtractor.extractDependencies(root);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).versionConstraint()).isEmpty();
    }

    /**
     * Goal: Verify multiple version constraints are joined.
     * Rationale: Q5 — Multiple constraints joined with ", ".
     */
    @Test
    void extractDeps_multipleConstraints() {
        JsonObject root = new JsonObject();
        root.addProperty("name", "TestPod");
        JsonObject deps = new JsonObject();
        JsonArray constraints = new JsonArray();
        constraints.add(">= 1.0");
        constraints.add("< 3.0");
        deps.add("OtherPod", constraints);
        root.add("dependencies", deps);

        List<ParsedDependency> result = CocoapodsMetadataExtractor.extractDependencies(root);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).versionConstraint()).hasValue(">= 1.0, < 3.0");
    }

    /**
     * Goal: Verify self-referencing deps are filtered.
     * Rationale: Q4 — Pod/subspec self-references must be excluded.
     */
    @Test
    void extractDeps_selfReferencingFiltered() {
        JsonObject root = new JsonObject();
        root.addProperty("name", "MyPod");
        JsonObject deps = new JsonObject();
        deps.add("MyPod/Core", new JsonArray());
        deps.add("OtherPod", new JsonArray());
        root.add("dependencies", deps);

        List<ParsedDependency> result = CocoapodsMetadataExtractor.extractDependencies(root);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("OtherPod");
    }

    /**
     * Goal: Verify subspec deps are included when no default_subspecs.
     * Rationale: Q4 — All subspecs included when no defaults specified.
     */
    @Test
    void extractDeps_subspecDefaultsOnly() {
        JsonObject root = new JsonObject();
        root.addProperty("name", "MyPod");

        JsonArray subspecs = new JsonArray();

        JsonObject sub1 = new JsonObject();
        sub1.addProperty("name", "Core");
        JsonObject sub1Deps = new JsonObject();
        sub1Deps.add("CoreDep", new JsonArray());
        sub1.add("dependencies", sub1Deps);
        subspecs.add(sub1);

        JsonObject sub2 = new JsonObject();
        sub2.addProperty("name", "Extra");
        JsonObject sub2Deps = new JsonObject();
        sub2Deps.add("ExtraDep", new JsonArray());
        sub2.add("dependencies", sub2Deps);
        subspecs.add(sub2);

        root.add("subspecs", subspecs);

        // With default_subspecs = ["Core"], only Core deps included
        root.addProperty("default_subspecs", "Core");
        List<ParsedDependency> result = CocoapodsMetadataExtractor.extractDependencies(root);
        assertThat(result.stream().map(ParsedDependency::name)).containsExactly("CoreDep");
    }

    /**
     * Goal: Verify all scope is "runtime".
     * Rationale: CocoaPods doesn't distinguish scopes; all are runtime.
     */
    @Test
    void extractDeps_allRuntime() {
        JsonObject root = new JsonObject();
        root.addProperty("name", "TestPod");
        JsonObject deps = new JsonObject();
        deps.add("Dep1", new JsonArray());
        deps.add("Dep2", new JsonArray());
        root.add("dependencies", deps);

        List<ParsedDependency> result = CocoapodsMetadataExtractor.extractDependencies(root);
        assertThat(result).allMatch(d -> d.scope().equals(Optional.of("runtime")));
    }

    // --- Description tests ---

    /**
     * Goal: Verify summary preferred over description.
     * Rationale: Summary is more concise.
     */
    @Test
    void extractDescription_summaryPreferred() {
        JsonObject root = new JsonObject();
        root.addProperty("summary", "Short summary");
        root.addProperty("description", "Long description text");
        assertThat(CocoapodsMetadataExtractor.extractDescription(root)).hasValue("Short summary");
    }

    /**
     * Goal: Verify description fallback when summary absent.
     * Rationale: Use description if no summary.
     */
    @Test
    void extractDescription_fallbackToDescription() {
        JsonObject root = new JsonObject();
        root.addProperty("description", "  Fallback description  ");
        assertThat(CocoapodsMetadataExtractor.extractDescription(root)).hasValue("Fallback description");
    }

    // --- Error handling ---

    /**
     * Goal: Verify corrupt JSON throws.
     * Rationale: Invalid input must be reported.
     */
    @Test
    void buildMetadataResult_corruptJson() {
        assertThatThrownBy(() -> CocoapodsMetadataExtractor.buildMetadataResult("{invalid"))
                .isInstanceOf(Exception.class);
    }

    /**
     * Goal: Verify valid JSON from real podspec.
     * Rationale: Q1 — JSON-only path must work.
     */
    @Test
    void extractFromJson_validPodspec() throws Exception {
        String json = """
                {"name":"TestPod","version":"1.0","summary":"A test","license":"MIT"}
                """;
        MetadataResult result = CocoapodsMetadataExtractor.buildMetadataResult(json);
        assertThat(result.name()).hasValue("TestPod");
        assertThat(result.version()).hasValue("1.0");
        assertThat(result.license()).hasValue("MIT");
    }

    // --- Helpers ---

    private MetadataResult extractFromPackage(Path pkgPath) throws Exception {
        String rawJson = Files.readString(pkgPath);
        return CocoapodsMetadataExtractor.buildMetadataResult(rawJson);
    }

    private Path findPackage(String filename) {
        Path pkg = COCOAPODS_CORPUS.resolve(filename);
        assumeThat(Files.exists(pkg)).isTrue();
        return pkg;
    }

    private record DepTuple(String name, String versionConstraint, String scope) {
    }
}
