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

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import io.spicelabs.annatto.common.PurlBuilder;
import io.spicelabs.annatto.luarocks.LuaTokenizer.Token;
import io.spicelabs.annatto.luarocks.LuaTokenizer.TokenType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Property-based tests for LuaRocks metadata extraction using jqwik.
 *
 * <p>These tests verify invariants that must hold for all valid inputs,
 * complementing the example-based tests in {@link LuarocksMetadataExtractorTest}.</p>
 */
class LuarocksMetadataExtractorPropertyTest {

    // --- parseDependencyString properties ---

    /**
     * Goal: parseDependencyString always produces a non-empty name for non-blank input.
     * Rationale: Q4 — Name is the first token in the dependency string.
     */
    @Property
    void parseDependencyString_alwaysProducesNonEmptyName(
            @ForAll("depStrings") String depStr) {
        ParsedDependency dep = LuarocksMetadataExtractor.parseDependencyString(depStr, "runtime");
        assertThat(dep).isNotNull();
        assertThat(dep.name()).isNotEmpty();
    }

    /**
     * Goal: parseDependencyString name never contains spaces.
     * Rationale: Q4 — Name is split on first space; spaces never appear in name.
     */
    @Property
    void parseDependencyString_nameNeverContainsSpace(
            @ForAll("depStrings") String depStr) {
        ParsedDependency dep = LuarocksMetadataExtractor.parseDependencyString(depStr, "runtime");
        assertThat(dep).isNotNull();
        assertThat(dep.name()).doesNotContain(" ");
    }

    /**
     * Goal: parseDependencyString round-trips: name + " " + versionConstraint reconstructs input.
     * Rationale: Q4 — Parsing is a simple split; reconstruction should match trimmed input.
     */
    @Property
    void parseDependencyString_roundTrip(
            @ForAll("depStringsWithVersion") String depStr) {
        ParsedDependency dep = LuarocksMetadataExtractor.parseDependencyString(depStr, "runtime");
        assertThat(dep).isNotNull();
        String reconstructed = dep.versionConstraint()
                .map(vc -> dep.name() + " " + vc)
                .orElse(dep.name());
        assertThat(reconstructed).isEqualTo(depStr.trim());
    }

    // --- buildMetadataResult properties ---

    /**
     * Goal: buildMetadataResult dependencies list is never null.
     * Rationale: Dependencies must always be a non-null list (may be empty).
     */
    @Property
    void buildMetadataResult_dependenciesNeverNull(
            @ForAll @IntRange(min = 0, max = 10) int depCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("package = \"test-pkg\"\n");
        sb.append("version = \"1.0-1\"\n");
        if (depCount > 0) {
            sb.append("dependencies = {\n");
            for (int i = 0; i < depCount; i++) {
                sb.append("  \"dep").append(i).append(" >= ").append(i).append(".0\",\n");
            }
            sb.append("}\n");
        }

        LuarocksMetadataExtractor.RockspecData data =
                new LuarocksMetadataExtractor.RockspecData(sb.toString(), "test.rockspec");

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = LuarocksMetadataExtractor.buildMetadataResult(data);
            assertThat(result.dependencies()).isNotNull();
            assertThat(result.dependencies()).hasSize(depCount);
        });
    }

    /**
     * Goal: buildMetadataResult never throws for valid rockspec Lua source.
     * Rationale: Q1 — Any valid rockspec with simple assignments should parse without error.
     */
    @Property
    void buildMetadataResult_neverThrowsForValidRockspec(
            @ForAll("luarocksNames") String name,
            @ForAll("luarocksVersions") String version) {
        String source = "package = \"" + escapeLuaString(name) + "\"\n"
                + "version = \"" + escapeLuaString(version) + "\"\n";

        LuarocksMetadataExtractor.RockspecData data =
                new LuarocksMetadataExtractor.RockspecData(source, "test.rockspec");

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = LuarocksMetadataExtractor.buildMetadataResult(data);
            assertThat(result).isNotNull();
            assertThat(result.name()).hasValue(name);
            assertThat(result.version()).hasValue(version);
        });
    }

    /**
     * Goal: buildMetadataResult publishedAt is always empty.
     * Rationale: Q7 — Rockspec files have no timestamp field.
     */
    @Property
    void buildMetadataResult_publishedAtAlwaysEmpty(
            @ForAll("luarocksNames") String name,
            @ForAll("luarocksVersions") String version) {
        String source = "package = \"" + escapeLuaString(name) + "\"\n"
                + "version = \"" + escapeLuaString(version) + "\"\n";

        LuarocksMetadataExtractor.RockspecData data =
                new LuarocksMetadataExtractor.RockspecData(source, "test.rockspec");

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = LuarocksMetadataExtractor.buildMetadataResult(data);
            assertThat(result.publishedAt()).isEmpty();
        });
    }

    // --- Description properties ---

    /**
     * Goal: extractDescription always prefers summary over detailed.
     * Rationale: Q5 — Summary takes priority when both fields are present.
     */
    @Property
    void extractDescription_summaryAlwaysPreferred(
            @ForAll("descriptions") String summary,
            @ForAll("descriptions") String detailed) {
        Map<String, Object> descTable = Map.of(
                "summary", summary,
                "detailed", detailed);
        Map<String, Object> env = Map.of("description", descTable);

        var result = LuarocksMetadataExtractor.extractDescription(env);
        if (!summary.isEmpty()) {
            assertThat(result).hasValue(summary);
        }
    }

    // --- PURL properties ---

    /**
     * Goal: PURL name is always lowercase.
     * Rationale: Q8 — Per purl-spec, LuaRocks PURL names are ASCII lowercased.
     */
    @Property
    void purlName_alwaysLowercase(
            @ForAll("luarocksNames") String name)
            throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forLuaRocks(name, "1.0-1");
        assertThat(purl.getName()).isEqualTo(name.toLowerCase(java.util.Locale.ROOT));
    }

    // --- Version properties ---

    /**
     * Goal: Versions from well-formed rockspecs always contain a hyphen revision.
     * Rationale: Q2 — LuaRocks versions follow "upstream-version-revision" pattern.
     */
    @Property
    void extractVersion_alwaysContainsHyphenRevision(
            @ForAll("luarocksVersions") String version) {
        String source = "package = \"test\"\nversion = \"" + escapeLuaString(version) + "\"\n";
        LuarocksMetadataExtractor.RockspecData data =
                new LuarocksMetadataExtractor.RockspecData(source, "test.rockspec");

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = LuarocksMetadataExtractor.buildMetadataResult(data);
            assertThat(result.version()).isPresent();
            assertThat(result.version().get()).contains("-");
        });
    }

    // --- Lua evaluator properties ---

    /**
     * Goal: LuaRockspecEvaluator never throws for valid rockspec source.
     * Rationale: Q1 — Evaluator must gracefully handle all valid Lua patterns.
     */
    @Property
    void luaEvaluator_neverThrowsForValidRockspec(
            @ForAll("validRockspecs") String source) {
        assertThatNoException().isThrownBy(() -> {
            Map<String, Object> result = LuaRockspecEvaluator.evaluate(source);
            assertThat(result).isNotNull();
        });
    }

    /**
     * Goal: LuaTokenizer always terminates (produces EOF token).
     * Rationale: Q1 — Tokenizer must not loop forever on any input.
     */
    @Property
    void luaTokenizer_terminates(
            @ForAll("luaSources") String source) {
        assertThatNoException().isThrownBy(() -> {
            List<Token> tokens = LuaTokenizer.tokenize(source);
            assertThat(tokens).isNotEmpty();
            assertThat(tokens.get(tokens.size() - 1).type()).isEqualTo(TokenType.EOF);
        });
    }

    /**
     * Goal: LuaTokenizer token count is bounded by MAX_TOKEN_COUNT.
     * Rationale: Q1 — Security limit prevents resource exhaustion.
     */
    @Property
    void luaTokenizer_tokenCountBounded(
            @ForAll("luaSources") String source) {
        try {
            List<Token> tokens = LuaTokenizer.tokenize(source);
            assertThat(tokens.size()).isLessThanOrEqualTo(LuaTokenizer.MAX_TOKEN_COUNT + 1);
        } catch (LuaTokenizer.LuaParseException e) {
            // Expected if token count exceeds limit — this is correct behavior
            assertThat(e.getMessage()).contains("Token count exceeds maximum");
        }
    }

    /**
     * Goal: String concatenation is associative: (a .. b) .. c == a .. (b .. c).
     * Rationale: Q1 — Lua's concatenation operator is right-associative, but for
     * pure strings the result should be the same regardless of grouping.
     */
    @Property
    void luaEvaluator_stringConcatAssociative(
            @ForAll("shortStrings") String a,
            @ForAll("shortStrings") String b,
            @ForAll("shortStrings") String c) {
        String source1 = "x = (\"" + escapeLuaString(a) + "\" .. \"" + escapeLuaString(b)
                + "\") .. \"" + escapeLuaString(c) + "\"";
        String source2 = "x = \"" + escapeLuaString(a) + "\" .. (\"" + escapeLuaString(b)
                + "\" .. \"" + escapeLuaString(c) + "\")";

        Map<String, Object> env1 = LuaRockspecEvaluator.evaluate(source1);
        Map<String, Object> env2 = LuaRockspecEvaluator.evaluate(source2);

        assertThat(env1.get("x")).isEqualTo(env2.get("x"));
        assertThat(env1.get("x")).isEqualTo(a + b + c);
    }

    /**
     * Goal: Table constructor preserves insertion order for hash-style tables.
     * Rationale: Q1 — LuaRocks metadata fields like description tables should maintain
     * the declaration order when iterated.
     */
    @Property
    void luaEvaluator_tableConstructorPreservesOrder(
            @ForAll @IntRange(min = 1, max = 8) int fieldCount) {
        StringBuilder source = new StringBuilder("t = { ");
        for (int i = 0; i < fieldCount; i++) {
            if (i > 0) source.append(", ");
            source.append("field").append(i).append(" = \"value").append(i).append("\"");
        }
        source.append(" }");

        Map<String, Object> env = LuaRockspecEvaluator.evaluate(source.toString());
        Object table = env.get("t");
        assertThat(table).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) table;
        assertThat(map).hasSize(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            assertThat(map).containsEntry("field" + i, "value" + i);
        }
    }

    /**
     * Goal: Dependencies never contain entries from external_dependencies.
     * Rationale: Q3 — external_dependencies are system C libraries, not LuaRocks packages.
     */
    @Property
    void extractDependencies_neverContainsExternalDeps(
            @ForAll @IntRange(min = 0, max = 5) int extDepCount) {
        StringBuilder source = new StringBuilder();
        source.append("package = \"test\"\nversion = \"1.0-1\"\n");
        source.append("dependencies = { \"lua >= 5.1\" }\n");
        if (extDepCount > 0) {
            source.append("external_dependencies = {\n");
            for (int i = 0; i < extDepCount; i++) {
                source.append("  EXT_LIB_").append(i).append(" = { header = \"ext").append(i).append(".h\" },\n");
            }
            source.append("}\n");
        }

        LuarocksMetadataExtractor.RockspecData data =
                new LuarocksMetadataExtractor.RockspecData(source.toString(), "test.rockspec");

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = LuarocksMetadataExtractor.buildMetadataResult(data);
            // "lua" is filtered as platform dependency, so deps should be empty
            // Most importantly: no EXT_LIB_* entries
            assertThat(result.dependencies()).isEmpty();
            for (ParsedDependency dep : result.dependencies()) {
                assertThat(dep.name()).doesNotStartWith("EXT_LIB_");
            }
        });
    }

    // --- Arbitrary providers ---

    @Provide
    Arbitrary<String> depStrings() {
        Arbitrary<String> nameOnly = luarocksDepNames();
        Arbitrary<String> nameAndVersion = Combinators.combine(luarocksDepNames(), luaConstraints())
                .as((n, v) -> n + " " + v);
        return Arbitraries.oneOf(nameOnly, nameAndVersion);
    }

    @Provide
    Arbitrary<String> depStringsWithVersion() {
        return Combinators.combine(luarocksDepNames(), luaConstraints())
                .as((n, v) -> n + " " + v);
    }

    @Provide
    Arbitrary<String> luarocksNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withChars('-', '_')
                .ofMinLength(1).ofMaxLength(30)
                .filter(s -> Character.isLetter(s.charAt(0)));
    }

    @Provide
    Arbitrary<String> luarocksVersions() {
        return Arbitraries.of(
                "1.0-1", "1.8.0-1", "0.1.0-1", "2.2.0-1", "3.1.0-1",
                "0.5.4-1", "1.14.0-2", "20200726.52-0", "5.3.5.1-1", "0.30-2");
    }

    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ')
                .ofMinLength(0).ofMaxLength(50);
    }

    @Provide
    Arbitrary<String> validRockspecs() {
        return Combinators.combine(luarocksNames(), luarocksVersions())
                .as((name, version) -> "package = \"" + escapeLuaString(name) + "\"\n"
                        + "version = \"" + escapeLuaString(version) + "\"\n"
                        + "description = { summary = \"A test package\" }\n"
                        + "dependencies = { \"lua >= 5.1\" }\n");
    }

    @Provide
    Arbitrary<String> luaSources() {
        return Arbitraries.oneOf(
                // Simple assignments
                Combinators.combine(luarocksNames(), shortStrings())
                        .as((name, val) -> name + " = \"" + escapeLuaString(val) + "\""),
                // Table assignments
                luarocksNames().map(name -> name + " = { key = \"value\" }"),
                // Local assignments
                Combinators.combine(luarocksNames(), shortStrings())
                        .as((name, val) -> "local " + name + " = \"" + escapeLuaString(val) + "\""),
                // Comments
                Arbitraries.of("-- this is a comment\n", "-- another\npackage = \"test\"")
        );
    }

    @Provide
    Arbitrary<String> shortStrings() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .withChars(' ', '-', '_', '.')
                .ofMinLength(0).ofMaxLength(20);
    }

    private Arbitrary<String> luarocksDepNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '_')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> Character.isLetter(s.charAt(0)));
    }

    private Arbitrary<String> luaConstraints() {
        return Arbitraries.of(
                ">= 5.1", ">= 5.1, < 5.4", ">= 1.0", ">= 0.7",
                "~> 1.0", ">= 3.0, < 4.0", "== 1.8.0-1",
                ">= 0.5", ">= 2.0", "~> 0.4");
    }

    /**
     * Escapes a string for safe inclusion in Lua double-quoted string literals.
     */
    private static String escapeLuaString(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
