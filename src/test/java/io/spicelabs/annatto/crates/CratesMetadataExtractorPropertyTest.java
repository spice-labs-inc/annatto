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

package io.spicelabs.annatto.crates;

import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.ParsedDependency;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Property-based tests for {@link CratesMetadataExtractor} using jqwik.
 *
 * <p>These tests verify invariants that must hold for all valid inputs,
 * complementing the example-based tests in {@link CratesMetadataExtractorTest}.</p>
 */
class CratesMetadataExtractorPropertyTest {

    private static final Set<String> VALID_SCOPES = Set.of("runtime", "dev", "build");

    // --- isCargoToml properties ---

    /**
     * Goal: isCargoToml returns true for well-formed crate entries.
     * Rationale: All published crates have entries like name-version/Cargo.toml.
     */
    @Property
    void isCargoToml_trueForWellFormedEntries(@ForAll("crateEntryNames") String entryName) {
        assertThat(CratesMetadataExtractor.isCargoToml(entryName)).isTrue();
    }

    /**
     * Goal: isCargoToml returns false for non-Cargo.toml files.
     * Rationale: Only Cargo.toml should match, not other files at the same depth.
     */
    @Property
    void isCargoToml_falseForNonCargoTomlFiles(
            @ForAll("crateNames") String crateName,
            @ForAll("nonCargoTomlFiles") String filename) {
        String entryName = crateName + "/" + filename;
        assertThat(CratesMetadataExtractor.isCargoToml(entryName)).isFalse();
    }

    /**
     * Goal: isCargoToml returns false for deeper Cargo.toml entries.
     * Rationale: Cargo.toml in subdirectories should not match.
     */
    @Property
    void isCargoToml_falseForDeepEntries(
            @ForAll("crateNames") String crateName,
            @ForAll("subdirectories") String subdir) {
        String entryName = crateName + "/" + subdir + "/Cargo.toml";
        assertThat(CratesMetadataExtractor.isCargoToml(entryName)).isFalse();
    }

    @Provide
    Arbitrary<String> crateEntryNames() {
        return crateNames().map(name -> name + "/Cargo.toml");
    }

    @Provide
    Arbitrary<String> crateNames() {
        Arbitrary<String> name = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '_')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_")
                        && !s.contains(".."));
        Arbitrary<String> version = Arbitraries.of(
                "0.1.0", "1.0.0", "1.0.195", "0.2.153", "2.4.2", "0.99.17");
        return Combinators.combine(name, version)
                .as((n, v) -> n + "-" + v);
    }

    @Provide
    Arbitrary<String> nonCargoTomlFiles() {
        return Arbitraries.of("Cargo.lock", "build.rs", "src/lib.rs", "README.md",
                "LICENSE", "Cargo.toml.orig", ".cargo-checksum.json");
    }

    @Provide
    Arbitrary<String> subdirectories() {
        return Arbitraries.of("src", "tests", "benches", "examples", "internal");
    }

    // --- parseDependencies properties ---

    /**
     * Goal: parseDependencies never returns null for any valid Cargo.toml.
     * Rationale: The parser must always return a non-null list.
     */
    @Property
    void parseDependencies_neverReturnsNull(
            @ForAll @IntRange(min = 0, max = 10) int depCount) {
        StringBuilder toml = new StringBuilder("[package]\nname = \"test\"\nversion = \"0.1.0\"\n\n");
        if (depCount > 0) {
            toml.append("[dependencies]\n");
            for (int i = 0; i < depCount; i++) {
                toml.append("dep").append(i).append(" = \"").append(i).append(".0\"\n");
            }
        }

        TomlParseResult parsed = Toml.parse(toml.toString());
        List<ParsedDependency> deps = CratesMetadataExtractor.parseDependencies(parsed);

        assertThat(deps).isNotNull();
        assertThat(deps).hasSize(depCount);
    }

    /**
     * Goal: All dependency scopes are one of runtime, dev, build.
     * Rationale: No other scope values should be produced.
     */
    @Property
    void parseDependencies_scopesAlwaysValid(
            @ForAll("depSections") DepSection section) {
        StringBuilder toml = new StringBuilder("[package]\nname = \"test\"\nversion = \"0.1.0\"\n\n");
        toml.append("[").append(section.tomlSection()).append("]\n");
        toml.append("some-dep = \"1.0\"\n");

        TomlParseResult parsed = Toml.parse(toml.toString());
        List<ParsedDependency> deps = CratesMetadataExtractor.parseDependencies(parsed);

        assertThat(deps).isNotEmpty();
        for (ParsedDependency dep : deps) {
            assertThat(dep.scope()).isPresent();
            assertThat(VALID_SCOPES).contains(dep.scope().get());
        }
    }

    @Provide
    Arbitrary<DepSection> depSections() {
        return Arbitraries.of(
                new DepSection("dependencies", "runtime"),
                new DepSection("dev-dependencies", "dev"),
                new DepSection("build-dependencies", "build")
        );
    }

    record DepSection(String tomlSection, String expectedScope) {
    }

    /**
     * Goal: Renamed deps use the package field, not the key.
     * Rationale: Q4 - for any alias with package field, name must be the package value.
     */
    @Property
    void parseDependencies_renamedUsesPackageField(
            @ForAll("crateDepNames") String alias,
            @ForAll("crateDepNames") String realName) {
        if (alias.equals(realName)) return; // skip trivial case

        String toml = String.format("""
                [package]
                name = "test"
                version = "0.1.0"

                [dependencies.%s]
                version = "1.0"
                package = "%s"
                """, alias, realName);

        TomlParseResult parsed = Toml.parse(toml);
        List<ParsedDependency> deps = CratesMetadataExtractor.parseDependencies(parsed);

        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo(realName);
    }

    @Provide
    Arbitrary<String> crateDepNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('-', '_')
                .ofMinLength(2).ofMaxLength(15)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_"));
    }

    // --- buildMetadataResult properties ---

    /**
     * Goal: buildMetadataResult never throws for valid TOML input.
     * Rationale: The extractor must gracefully handle any parseable Cargo.toml.
     */
    @Property
    void buildMetadataResult_neverThrowsForValidToml(
            @ForAll("minimalCargoTomls") String cargoToml) {
        CratesMetadataExtractor.CargoTomlData data =
                new CratesMetadataExtractor.CargoTomlData(cargoToml);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CratesMetadataExtractor.buildMetadataResult(data);
            assertThat(result).isNotNull();
            assertThat(result.dependencies()).isNotNull();
        });
    }

    @Provide
    Arbitrary<String> minimalCargoTomls() {
        Arbitrary<String> name = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('-', '_')
                .ofMinLength(1).ofMaxLength(20)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_"));
        Arbitrary<String> version = Arbitraries.of("0.1.0", "1.0.0", "2.3.4", "0.0.1");

        return Combinators.combine(name, version)
                .as((n, v) -> String.format("""
                        [package]
                        name = "%s"
                        version = "%s"
                        """, n, v));
    }

    /**
     * Goal: Dependency count matches the number of entries across all sections.
     * Rationale: No dependencies should be lost or duplicated during parsing.
     */
    @Property
    void parseDependencies_countMatchesInputEntries(
            @ForAll @IntRange(min = 0, max = 5) int runtimeCount,
            @ForAll @IntRange(min = 0, max = 5) int devCount,
            @ForAll @IntRange(min = 0, max = 5) int buildCount) {
        StringBuilder toml = new StringBuilder("[package]\nname = \"test\"\nversion = \"0.1.0\"\n\n");

        if (runtimeCount > 0) {
            toml.append("[dependencies]\n");
            for (int i = 0; i < runtimeCount; i++) {
                toml.append("rt").append(i).append(" = \"1.0\"\n");
            }
        }
        if (devCount > 0) {
            toml.append("[dev-dependencies]\n");
            for (int i = 0; i < devCount; i++) {
                toml.append("dv").append(i).append(" = \"1.0\"\n");
            }
        }
        if (buildCount > 0) {
            toml.append("[build-dependencies]\n");
            for (int i = 0; i < buildCount; i++) {
                toml.append("bd").append(i).append(" = \"1.0\"\n");
            }
        }

        TomlParseResult parsed = Toml.parse(toml.toString());
        List<ParsedDependency> deps = CratesMetadataExtractor.parseDependencies(parsed);

        assertThat(deps).hasSize(runtimeCount + devCount + buildCount);
    }
}
