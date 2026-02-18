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

package io.spicelabs.annatto.rubygems;

import io.spicelabs.annatto.common.MetadataResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Property-based tests for {@link RubygemsMetadataExtractor} using jqwik.
 *
 * <p>These tests verify invariants that must hold for all valid inputs,
 * complementing the example-based tests in {@link RubygemsMetadataExtractorTest}.</p>
 */
class RubygemsMetadataExtractorPropertyTest {

    private static final Set<String> VALID_SCOPES = Set.of("runtime", "dev");

    // --- stripRubyYamlTags properties ---

    /**
     * Goal: stripRubyYamlTags never leaves partial !ruby/ tags.
     * Rationale: Q1 — after stripping, no !ruby/ prefix should remain.
     */
    @Property
    void stripRubyYamlTags_neverLeavesPartialTags(@ForAll("yamlWithRubyTags") String input) {
        String result = RubygemsMetadataExtractor.stripRubyYamlTags(input);
        assertThat(result).doesNotContain("!ruby/");
    }

    /**
     * Goal: stripRubyYamlTags is idempotent.
     * Rationale: Applying the strip twice should produce the same result.
     */
    @Property
    void stripRubyYamlTags_isIdempotent(@ForAll("yamlWithRubyTags") String input) {
        String once = RubygemsMetadataExtractor.stripRubyYamlTags(input);
        String twice = RubygemsMetadataExtractor.stripRubyYamlTags(once);
        assertThat(twice).isEqualTo(once);
    }

    /**
     * Goal: stripRubyYamlTags only removes tag content, not surrounding text.
     * Rationale: The strip should not corrupt other YAML content.
     */
    @Property
    void stripRubyYamlTags_preservesNonTagContent(@ForAll("gemNames") String name) {
        String input = "name: " + name + "\nversion: !ruby/object:Gem::Version\n  version: 1.0";
        String result = RubygemsMetadataExtractor.stripRubyYamlTags(input);
        assertThat(result).contains("name: " + name);
    }

    @Provide
    Arbitrary<String> yamlWithRubyTags() {
        Arbitrary<String> tagType = Arbitraries.of(
                "!ruby/object:Gem::Specification",
                "!ruby/object:Gem::Version",
                "!ruby/object:Gem::Requirement",
                "!ruby/object:Gem::Dependency",
                "!ruby/hash:Gem::StubSpecification"
        );
        Arbitrary<String> content = Arbitraries.of(
                "name: test", "version: 1.0", "summary: a gem", "dependencies: []"
        );
        return Combinators.combine(tagType, content)
                .as((tag, c) -> "--- " + tag + "\n" + c);
    }

    @Provide
    Arbitrary<String> gemNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '_')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_"));
    }

    // --- reconstructVersionConstraint properties ---

    /**
     * Goal: reconstructVersionConstraint never returns ">= 0".
     * Rationale: Q4 — the default ">= 0" must be mapped to null.
     */
    @Property
    void reconstructVersionConstraint_neverReturnsDefault(@ForAll("versionRequirements") Map<String, Object> req) {
        String result = RubygemsMetadataExtractor.reconstructVersionConstraint(req);
        if (result != null) {
            assertThat(result).isNotEqualTo(">= 0");
        }
    }

    /**
     * Goal: reconstructVersionConstraint output is well-formed when non-null.
     * Rationale: Q4 — result should contain an operator and version.
     */
    @Property
    void reconstructVersionConstraint_wellFormedOutput(@ForAll("nonDefaultRequirements") Map<String, Object> req) {
        String result = RubygemsMetadataExtractor.reconstructVersionConstraint(req);
        if (result != null) {
            assertThat(result).matches(".*\\d.*"); // contains at least one digit
        }
    }

    @Provide
    Arbitrary<Map<String, Object>> versionRequirements() {
        Arbitrary<String> op = Arbitraries.of(">=", "~>", "<", "<=", "=", "!=");
        Arbitrary<String> ver = Arbitraries.of("0", "1.0", "2.3.4", "0.1.0");
        return Combinators.combine(op, ver).as((o, v) -> {
            Map<String, Object> versionMap = Map.of("version", v);
            List<Object> pair = List.of(o, versionMap);
            return Map.<String, Object>of("requirements", List.of(pair));
        });
    }

    @Provide
    Arbitrary<Map<String, Object>> nonDefaultRequirements() {
        Arbitrary<String> op = Arbitraries.of("~>", "<", "<=", "=", "!=");
        Arbitrary<String> ver = Arbitraries.of("1.0", "2.3.4", "0.1.0", "3.0");
        return Combinators.combine(op, ver).as((o, v) -> {
            Map<String, Object> versionMap = Map.of("version", v);
            List<Object> pair = List.of(o, versionMap);
            return Map.<String, Object>of("requirements", List.of(pair));
        });
    }

    // --- mapDependencyType properties ---

    /**
     * Goal: mapDependencyType always returns "runtime" or "dev".
     * Rationale: Q3 — only two valid scopes exist for RubyGems.
     */
    @Property
    void mapDependencyType_alwaysReturnsValidScope(@ForAll("dependencyTypes") Object type) {
        String result = RubygemsMetadataExtractor.mapDependencyType(type);
        assertThat(VALID_SCOPES).contains(result);
    }

    @Provide
    Arbitrary<Object> dependencyTypes() {
        return Arbitraries.of(":runtime", ":development", "runtime", "development", null, "unknown");
    }

    // --- buildMetadataResult properties ---

    /**
     * Goal: buildMetadataResult never throws for valid minimal YAML.
     * Rationale: The extractor must gracefully handle any parseable gemspec YAML.
     */
    @Property
    void buildMetadataResult_neverThrowsForValidYaml(@ForAll("minimalGemYamls") String yaml) {
        RubygemsMetadataExtractor.GemMetadataData data =
                new RubygemsMetadataExtractor.GemMetadataData(yaml);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = RubygemsMetadataExtractor.buildMetadataResult(data);
            assertThat(result).isNotNull();
            assertThat(result.dependencies()).isNotNull();
        });
    }

    /**
     * Goal: buildMetadataResult dependencies list is never null.
     * Rationale: Dependencies should always be an empty list, never null.
     */
    @Property
    void buildMetadataResult_dependenciesNeverNull(@ForAll("minimalGemYamls") String yaml) throws Exception {
        RubygemsMetadataExtractor.GemMetadataData data =
                new RubygemsMetadataExtractor.GemMetadataData(yaml);
        MetadataResult result = RubygemsMetadataExtractor.buildMetadataResult(data);

        assertThat(result.dependencies()).isNotNull();
    }

    private static final Set<String> YAML_KEYWORDS = Set.of(
            "yes", "no", "true", "false", "on", "off", "null", "y", "n");

    @Provide
    Arbitrary<String> minimalGemYamls() {
        Arbitrary<String> name = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('-', '_')
                .ofMinLength(2).ofMaxLength(20)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_")
                        && !YAML_KEYWORDS.contains(s));
        Arbitrary<String> version = Arbitraries.of("0.1.0", "1.0.0", "2.3.4", "0.0.1");

        return Combinators.combine(name, version)
                .as((n, v) -> String.format("---\nname: %s\nversion:\n  version: %s\n", n, v));
    }

    // --- isMetadataGz properties ---

    /**
     * Goal: isMetadataGz returns false for any entry with path traversal.
     * Rationale: Security — entries with ".." must always be rejected.
     */
    @Property
    void isMetadataGz_falseForPathTraversal(@ForAll("pathTraversalEntries") String entry) {
        assertThat(RubygemsMetadataExtractor.isMetadataGz(entry)).isFalse();
    }

    @Provide
    Arbitrary<String> pathTraversalEntries() {
        return Arbitraries.of(
                "../metadata.gz", "../../metadata.gz", "foo/../metadata.gz",
                "../etc/passwd", "..\\metadata.gz");
    }

    // --- joinLicenses properties ---

    /**
     * Goal: joinLicenses with single element has no separator.
     * Rationale: Q6 — single license should not contain " OR ".
     */
    @Property
    void joinLicenses_singleHasNoSeparator(@ForAll("licenseNames") String license) {
        String result = RubygemsMetadataExtractor.joinLicenses(List.of(license));
        assertThat(result).doesNotContain(" OR ");
        assertThat(result).isEqualTo(license);
    }

    /**
     * Goal: joinLicenses with multiple elements has separator.
     * Rationale: Q6 — multiple licenses must be joined with " OR ".
     */
    @Property
    void joinLicenses_multipleHasSeparator(
            @ForAll("licenseNames") String license1,
            @ForAll("licenseNames") String license2) {
        String result = RubygemsMetadataExtractor.joinLicenses(List.of(license1, license2));
        assertThat(result).contains(" OR ");
    }

    @Provide
    Arbitrary<String> licenseNames() {
        return Arbitraries.of("MIT", "Apache-2.0", "GPL-3.0", "BSD-2-Clause", "ISC", "Ruby");
    }

    // --- extractDescription properties ---

    /**
     * Goal: extractDescription always returns summary when non-empty.
     * Rationale: Q2 — summary is always preferred over description.
     */
    @Property
    void extractDescription_summaryAlwaysPreferred(
            @ForAll("nonEmptyStrings") String summary,
            @ForAll("nonEmptyStrings") String description) {
        Map<String, Object> data = new HashMap<>();
        data.put("summary", summary);
        data.put("description", description);
        String result = RubygemsMetadataExtractor.extractDescription(data);
        assertThat(result).isEqualTo(summary);
    }

    @Provide
    Arbitrary<String> nonEmptyStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ')
                .ofMinLength(3).ofMaxLength(50)
                .filter(s -> !s.trim().isEmpty());
    }
}
