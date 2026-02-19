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

package io.spicelabs.annatto.hex;

import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Property-based tests for {@link HexMetadataExtractor} using jqwik.
 */
class HexMetadataExtractorPropertyTest {

    /**
     * Goal: buildMetadataResult never throws for valid Erlang term input.
     * Rationale: Any valid metadata.config with name/version should parse.
     */
    @Property
    void buildMetadataResult_neverThrowsForValidInput(
            @ForAll("hexNames") String name,
            @ForAll("versions") String version) {
        String input = buildMetadataConfig(name, version, "MIT", "A test package");

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = HexMetadataExtractor.buildMetadataResult(input);
            assertThat(result).isNotNull();
            assertThat(result.name()).hasValue(name);
            assertThat(result.version()).hasValue(version);
        });
    }

    /**
     * Goal: publishedAt is always empty.
     * Rationale: Q4 — No timestamp in metadata.config.
     */
    @Property
    void buildMetadataResult_publishedAtAlwaysEmpty(
            @ForAll("hexNames") String name,
            @ForAll("versions") String version) {
        String input = buildMetadataConfig(name, version, null, null);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = HexMetadataExtractor.buildMetadataResult(input);
            assertThat(result.publishedAt()).isEmpty();
        });
    }

    /**
     * Goal: publisher is always empty.
     * Rationale: Q4 — No maintainer field in metadata.config.
     */
    @Property
    void buildMetadataResult_publisherAlwaysEmpty(
            @ForAll("hexNames") String name,
            @ForAll("versions") String version) {
        String input = buildMetadataConfig(name, version, null, null);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = HexMetadataExtractor.buildMetadataResult(input);
            assertThat(result.publisher()).isEmpty();
        });
    }

    /**
     * Goal: simpleName always equals name.
     * Rationale: Hex has no namespace concept, simpleName == name.
     */
    @Property
    void buildMetadataResult_simpleNameEqualsName(
            @ForAll("hexNames") String name,
            @ForAll("versions") String version) {
        String input = buildMetadataConfig(name, version, null, null);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = HexMetadataExtractor.buildMetadataResult(input);
            assertThat(result.simpleName()).isEqualTo(result.name());
        });
    }

    /**
     * Goal: Dependencies list is never null.
     * Rationale: Dependencies must always be a non-null list.
     */
    @Property
    void buildMetadataResult_dependenciesNeverNull(
            @ForAll @IntRange(min = 0, max = 5) int depCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("{<<\"name\">>,<<\"test\">>}.\n");
        sb.append("{<<\"version\">>,<<\"1.0.0\">>}.\n");

        if (depCount > 0) {
            sb.append("{<<\"requirements\">>,[\n");
            for (int i = 0; i < depCount; i++) {
                if (i > 0) sb.append(",\n");
                sb.append("  [{<<\"name\">>,<<\"dep").append(i).append("\">>},");
                sb.append("{<<\"requirement\">>,<<\"~> 1.0\">>},");
                sb.append("{<<\"optional\">>,false},");
                sb.append("{<<\"app\">>,<<\"dep").append(i).append("\">>},");
                sb.append("{<<\"repository\">>,<<\"hexpm\">>}]");
            }
            sb.append("\n]}.\n");
        }

        String input = sb.toString();
        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = HexMetadataExtractor.buildMetadataResult(input);
            assertThat(result.dependencies()).isNotNull();
            assertThat(result.dependencies()).hasSize(depCount);
        });
    }

    /**
     * Goal: All deps always have scope "runtime".
     * Rationale: Q5 — Hex metadata.config has no scope distinction.
     */
    @Property
    void extractDeps_allScopesRuntime(
            @ForAll("hexNames") String depName,
            @ForAll("versionConstraints") String vc) {
        Map<String, Object> dep = new HashMap<>();
        dep.put("name", depName);
        dep.put("requirement", vc);
        Map<String, Object> meta = Map.of("requirements", List.of(dep));

        List<ParsedDependency> deps = HexMetadataExtractor.extractDependencies(meta);
        assertThat(deps).allSatisfy(d ->
                assertThat(d.scope()).hasValue("runtime"));
    }

    /**
     * Goal: License join is deterministic.
     * Rationale: Q3 — Multiple licenses always joined with " OR ".
     */
    @Property
    void extractLicense_multipleAlwaysJoined(
            @ForAll("licenses") String lic1,
            @ForAll("licenses") String lic2) {
        Map<String, Object> meta = Map.of("licenses", List.of(lic1, lic2));
        var result = HexMetadataExtractor.extractLicense(meta);
        assertThat(result).hasValue(lic1 + " OR " + lic2);
    }

    // --- Arbitrary providers ---

    @Provide
    Arbitrary<String> hexNames() {
        return Arbitraries.of(
                "jason", "phoenix", "ecto", "plug", "cowboy",
                "ranch", "decimal", "telemetry", "hackney", "jsx",
                "nimble_parsec", "phoenix_live_view", "ex_doc");
    }

    @Provide
    Arbitrary<String> versions() {
        return Arbitraries.of(
                "1.0.0", "1.4.1", "2.12.0", "0.7.1", "3.11.1",
                "0.20.4", "1.2.1", "0.31.1", "5.0.0");
    }

    @Provide
    Arbitrary<String> licenses() {
        return Arbitraries.of("MIT", "Apache-2.0", "ISC", "BSD-3-Clause",
                "MPL-2.0", "LGPL-2.1");
    }

    @Provide
    Arbitrary<String> versionConstraints() {
        return Arbitraries.of("~> 1.0", ">= 0.0.0", "~> 2.0 or ~> 3.0",
                "1.8.0", "~> 0.4 or ~> 1.0");
    }

    // --- Helper ---

    private String buildMetadataConfig(String name, String version,
                                        String license, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("{<<\"name\">>,<<\"").append(name).append("\">>}.\n");
        sb.append("{<<\"version\">>,<<\"").append(version).append("\">>}.\n");
        if (description != null) {
            sb.append("{<<\"description\">>,<<\"").append(description).append("\">>}.\n");
        }
        if (license != null) {
            sb.append("{<<\"licenses\">>,[<<\"").append(license).append("\">>]}.\n");
        }
        return sb.toString();
    }
}
