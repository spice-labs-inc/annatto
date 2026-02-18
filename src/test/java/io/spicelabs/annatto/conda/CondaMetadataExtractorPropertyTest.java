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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Property-based tests for {@link CondaMetadataExtractor} using jqwik.
 *
 * <p>These tests verify invariants that must hold for all valid inputs,
 * complementing the example-based tests in {@link CondaMetadataExtractorTest}.</p>
 */
class CondaMetadataExtractorPropertyTest {

    // --- parseMatchSpec properties ---

    /**
     * Goal: parseMatchSpec always produces a non-empty name.
     * Rationale: Q6 - Name is always the first token in a match spec.
     */
    @Property
    void parseMatchSpec_alwaysProducesNonEmptyName(@ForAll("matchSpecs") String matchSpec) {
        ParsedDependency dep = CondaMetadataExtractor.parseMatchSpec(matchSpec);
        assertThat(dep.name()).isNotEmpty();
    }

    /**
     * Goal: parseMatchSpec name never contains spaces.
     * Rationale: Q6 - Name is split on first space; no spaces in name.
     */
    @Property
    void parseMatchSpec_nameNeverContainsSpaces(@ForAll("matchSpecs") String matchSpec) {
        ParsedDependency dep = CondaMetadataExtractor.parseMatchSpec(matchSpec);
        assertThat(dep.name()).doesNotContain(" ");
    }

    /**
     * Goal: parseMatchSpec scope is always "runtime".
     * Rationale: Q6 - All conda dependencies are scope "runtime".
     */
    @Property
    void parseMatchSpec_scopeAlwaysRuntime(@ForAll("matchSpecs") String matchSpec) {
        ParsedDependency dep = CondaMetadataExtractor.parseMatchSpec(matchSpec);
        assertThat(dep.scope()).hasValue("runtime");
    }

    /**
     * Goal: parseMatchSpec round-trips: name + " " + versionConstraint reconstructs input.
     * Rationale: Q6 - Parsing is a simple split; reconstruction should match.
     */
    @Property
    void parseMatchSpec_roundTrip(@ForAll("matchSpecsWithVersion") String matchSpec) {
        ParsedDependency dep = CondaMetadataExtractor.parseMatchSpec(matchSpec);
        String reconstructed = dep.versionConstraint()
                .map(vc -> dep.name() + " " + vc)
                .orElse(dep.name());
        assertThat(reconstructed).isEqualTo(matchSpec.trim());
    }

    // --- buildMetadataResult properties ---

    /**
     * Goal: buildMetadataResult dependencies list is never null.
     * Rationale: Dependencies must always be a non-null list (may be empty).
     */
    @Property
    void buildMetadataResult_dependenciesNeverNull(
            @ForAll @IntRange(min = 0, max = 10) int depCount) {
        JsonObject indexJson = new JsonObject();
        indexJson.addProperty("name", "test-pkg");
        indexJson.addProperty("version", "1.0.0");
        if (depCount > 0) {
            JsonArray deps = new JsonArray();
            for (int i = 0; i < depCount; i++) {
                deps.add("dep" + i + " >=" + i + ".0");
            }
            indexJson.add("depends", deps);
        }

        CondaMetadataExtractor.CondaArchiveData data =
                new CondaMetadataExtractor.CondaArchiveData(indexJson.toString(), null);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CondaMetadataExtractor.buildMetadataResult(data);
            assertThat(result.dependencies()).isNotNull();
            assertThat(result.dependencies()).hasSize(depCount);
        });
    }

    /**
     * Goal: buildMetadataResult never throws for valid index.json.
     * Rationale: Any valid JSON with name/version should parse without error.
     */
    @Property
    void buildMetadataResult_neverThrowsForValidIndexJson(
            @ForAll("condaNames") String name,
            @ForAll("condaVersions") String version) {
        JsonObject indexJson = new JsonObject();
        indexJson.addProperty("name", name);
        indexJson.addProperty("version", version);

        CondaMetadataExtractor.CondaArchiveData data =
                new CondaMetadataExtractor.CondaArchiveData(indexJson.toString(), null);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CondaMetadataExtractor.buildMetadataResult(data);
            assertThat(result).isNotNull();
            assertThat(result.name()).hasValue(name);
            assertThat(result.version()).hasValue(version);
        });
    }

    /**
     * Goal: extractDescription always prefers summary over description.
     * Rationale: Q7 - Summary takes priority when both present.
     */
    @Property
    void extractDescription_summaryAlwaysPreferred(
            @ForAll("descriptions") String summary,
            @ForAll("descriptions") String description) {
        JsonObject about = new JsonObject();
        about.addProperty("summary", summary);
        about.addProperty("description", description);

        Optional<String> result = CondaMetadataExtractor.extractDescription(about);
        // If summary is non-empty, it should be preferred
        if (!summary.isEmpty()) {
            assertThat(result).hasValue(summary);
        }
    }

    /**
     * Goal: Timestamp conversion always produces ISO 8601 format.
     * Rationale: Q8 - Output must match YYYY-MM-DDTHH:MM:SS+00:00 pattern.
     */
    @Property
    void timestampConversion_outputIsIso8601(
            @ForAll @IntRange(min = 0, max = 2000000000) int epochSeconds) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", (long) epochSeconds * 1000);
        Optional<String> result = CondaMetadataExtractor.convertTimestamp(json);
        assertThat(result).isPresent();
        assertThat(result.get()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\+00:00");
    }

    /**
     * Goal: Timestamp conversion never produces negative year for non-negative millis.
     * Rationale: Q8 - Non-negative timestamps should produce valid dates.
     */
    @Property
    void timestampConversion_neverNegativeYear(
            @ForAll @IntRange(min = 0, max = 2000000000) int epochSeconds) {
        JsonObject json = new JsonObject();
        json.addProperty("timestamp", (long) epochSeconds * 1000);
        Optional<String> result = CondaMetadataExtractor.convertTimestamp(json);
        assertThat(result).isPresent();
        assertThat(result.get()).doesNotStartWith("-");
    }

    /**
     * Goal: Publisher is always null in buildMetadataResult.
     * Rationale: Q2 - Conda metadata has no author field.
     */
    @Property
    void buildMetadataResult_publisherAlwaysNull(
            @ForAll("condaNames") String name,
            @ForAll("condaVersions") String version) {
        JsonObject indexJson = new JsonObject();
        indexJson.addProperty("name", name);
        indexJson.addProperty("version", version);

        CondaMetadataExtractor.CondaArchiveData data =
                new CondaMetadataExtractor.CondaArchiveData(indexJson.toString(), null);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CondaMetadataExtractor.buildMetadataResult(data);
            assertThat(result.publisher()).isEmpty();
        });
    }

    /**
     * Goal: detectFormat always determines format from extension.
     * Rationale: Q1 - .conda and .tar.bz2 are always correctly identified.
     */
    @Property
    void detectFormat_alwaysDeterminesFormatFromExtension(
            @ForAll("condaNames") String baseName) {
        assertThatNoException().isThrownBy(() -> {
            assertThat(CondaMetadataExtractor.detectFormat(baseName + ".conda"))
                    .isEqualTo(CondaMetadataExtractor.CondaFormat.CONDA);
            assertThat(CondaMetadataExtractor.detectFormat(baseName + ".tar.bz2"))
                    .isEqualTo(CondaMetadataExtractor.CondaFormat.TAR_BZ2);
        });
    }

    // --- Arbitrary providers ---

    @Provide
    Arbitrary<String> matchSpecs() {
        Arbitrary<String> nameOnly = condaDepNames();
        Arbitrary<String> nameAndVersion = Combinators.combine(condaDepNames(), condaConstraints())
                .as((n, v) -> n + " " + v);
        return Arbitraries.oneOf(nameOnly, nameAndVersion);
    }

    @Provide
    Arbitrary<String> matchSpecsWithVersion() {
        return Combinators.combine(condaDepNames(), condaConstraints())
                .as((n, v) -> n + " " + v);
    }

    @Provide
    Arbitrary<String> condaNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '_')
                .ofMinLength(1).ofMaxLength(30)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_") && !s.contains(".."));
    }

    @Provide
    Arbitrary<String> condaVersions() {
        return Arbitraries.of("0.1.0", "1.0.0", "1.26.4", "3.9.7", "42.0.0", "0.0.1");
    }

    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ')
                .ofMinLength(0).ofMaxLength(50);
    }

    private Arbitrary<String> condaDepNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '_')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_"));
    }

    private Arbitrary<String> condaConstraints() {
        return Arbitraries.of(
                ">=1.0", ">=3.8,<4.0", ">=1.0,<2.0a0",
                "3.10.* *_cp310", "0.1 conda_forge", ">=12",
                "1.0", ">=0", ">=3.9.0,<4.0a0"
        );
    }
}
