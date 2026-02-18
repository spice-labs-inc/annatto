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

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link GoMetadataExtractor} using jqwik.
 *
 * <p>These tests verify invariants that must hold for all valid inputs,
 * complementing the example-based tests in {@link GoMetadataExtractorTest}.</p>
 */
class GoMetadataExtractorPropertyTest {

    // --- extractSimpleName properties ---

    /**
     * Goal: For any module path with slashes, simpleName is the last segment.
     * Rationale: Q1 - the simple name invariant must hold for all URL-like paths.
     */
    @Property
    void extractSimpleName_isLastSegment(@ForAll("modulePaths") String modulePath) {
        String result = GoMetadataExtractor.extractSimpleName(modulePath);

        // Result must be non-empty
        assertThat(result).isNotEmpty();

        // Result must not contain a slash
        assertThat(result).doesNotContain("/");

        // Result must be a suffix of the input
        assertThat(modulePath).endsWith(result);

        // If input has a slash, result equals everything after the last slash
        if (modulePath.contains("/")) {
            String expected = modulePath.substring(modulePath.lastIndexOf('/') + 1);
            assertThat(result).isEqualTo(expected);
        } else {
            // No slash means result equals input
            assertThat(result).isEqualTo(modulePath);
        }
    }

    @Provide
    Arbitrary<String> modulePaths() {
        Arbitrary<String> segment = Arbitraries.strings()
                .alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> host = Arbitraries.of(
                "github.com", "golang.org", "gopkg.in", "example.com", "k8s.io");
        return Combinators.combine(host, segment, segment)
                .as((h, org, name) -> h + "/" + org + "/" + name);
    }

    /**
     * Goal: extractSimpleName never returns empty for non-empty input.
     * Rationale: Every valid module path must have a simple name.
     */
    @Property
    void extractSimpleName_neverEmpty(
            @ForAll @StringLength(min = 1, max = 100) String input) {
        // Filter to valid-ish module path chars
        String clean = input.replaceAll("[^a-zA-Z0-9/._-]", "a");
        if (clean.isEmpty() || clean.equals("/")) return;
        // Remove trailing slashes
        while (clean.endsWith("/") && clean.length() > 1) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.isEmpty()) return;

        String result = GoMetadataExtractor.extractSimpleName(clean);
        assertThat(result).isNotEmpty();
    }

    // --- extractVersionFromEntryName properties ---

    /**
     * Goal: For well-formed entry names module@version/go.mod, the extracted version
     * matches the version between @ and the next /.
     * Rationale: Version extraction must be exact for all valid entry name patterns.
     */
    @Property
    void extractVersionFromEntryName_matchesVersion(
            @ForAll("goModEntryNames") GoModEntry entry) {
        String result = GoMetadataExtractor.extractVersionFromEntryName(entry.entryName());

        assertThat(result).isEqualTo(entry.version());
    }

    /**
     * Goal: For well-formed entry names, extracted module path matches the path before @.
     * Rationale: Module path extraction must be exact for all valid entries.
     */
    @Property
    void extractModulePathFromEntryName_matchesPath(
            @ForAll("goModEntryNames") GoModEntry entry) {
        String result = GoMetadataExtractor.extractModulePathFromEntryName(entry.entryName());

        assertThat(result).isEqualTo(entry.modulePath());
    }

    /**
     * Goal: isGoMod returns true for all well-formed root-level go.mod entries.
     * Rationale: The go.mod detection must work for all valid Go module zip entry patterns.
     */
    @Property
    void isGoMod_trueForWellFormedEntries(
            @ForAll("goModEntryNames") GoModEntry entry) {
        assertThat(GoMetadataExtractor.isGoMod(entry.entryName())).isTrue();
    }

    /**
     * Goal: isGoMod returns false for non-go.mod files in module zips.
     * Rationale: Only go.mod should match, not go.sum, main.go, etc.
     */
    @Property
    void isGoMod_falseForNonGoModFiles(
            @ForAll("goModEntryNames") GoModEntry entry,
            @ForAll("nonGoModSuffixes") String suffix) {
        String nonGoModEntry = entry.modulePath() + "@" + entry.version() + "/" + suffix;
        assertThat(GoMetadataExtractor.isGoMod(nonGoModEntry)).isFalse();
    }

    @Provide
    Arbitrary<String> nonGoModSuffixes() {
        return Arbitraries.of("go.sum", "main.go", "README.md", "LICENSE",
                "internal/go.mod", "sub/dir/go.mod");
    }

    @Provide
    Arbitrary<GoModEntry> goModEntryNames() {
        Arbitrary<String> host = Arbitraries.of(
                "github.com", "golang.org", "gopkg.in", "example.com");
        Arbitrary<String> org = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(15);
        Arbitrary<String> name = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(15);
        Arbitrary<String> version = Arbitraries.of("v1.0.0", "v2.3.1", "v0.1.0",
                "v0.0.0-20240102182953-50ed04b92917", "v3.0.0+incompatible",
                "v1.2.3-rc.1", "v10.0.0");

        return Combinators.combine(host, org, name, version)
                .as((h, o, n, v) -> {
                    String modulePath = h + "/" + o + "/" + n;
                    String entryName = modulePath + "@" + v + "/go.mod";
                    return new GoModEntry(modulePath, v, entryName);
                });
    }

    record GoModEntry(String modulePath, String version, String entryName) {
    }

    // --- parseGoMod properties ---

    /**
     * Goal: parseGoMod always returns a non-null result with a non-null requires list.
     * Rationale: The parser must never return null or a null list, regardless of input.
     */
    @Property
    void parseGoMod_neverReturnsNullRequires(
            @ForAll @StringLength(max = 500) String input) {
        GoMetadataExtractor.ParsedGoMod result = GoMetadataExtractor.parseGoMod(input);

        assertThat(result).isNotNull();
        assertThat(result.requires()).isNotNull();
    }

    /**
     * Goal: For generated go.mod text with a known module path, parsing extracts that path.
     * Rationale: Module path extraction must be correct for all valid module directives.
     */
    @Property
    void parseGoMod_extractsModulePath(@ForAll("modulePaths") String modulePath) {
        String goMod = "module " + modulePath + "\n\ngo 1.21\n";
        GoMetadataExtractor.ParsedGoMod result = GoMetadataExtractor.parseGoMod(goMod);

        assertThat(result.modulePath()).isEqualTo(modulePath);
    }

    /**
     * Goal: For generated go.mod text with N require directives, parsing returns exactly N dependencies.
     * Rationale: No dependencies should be lost or duplicated during parsing.
     */
    @Property
    void parseGoMod_extractsCorrectDependencyCount(
            @ForAll @IntRange(min = 0, max = 20) int depCount) {
        StringBuilder goMod = new StringBuilder("module example.com/test\n\ngo 1.21\n\n");
        if (depCount > 0) {
            goMod.append("require (\n");
            for (int i = 0; i < depCount; i++) {
                goMod.append("\tgithub.com/dep").append(i).append("/pkg v1.").append(i).append(".0\n");
            }
            goMod.append(")\n");
        }

        GoMetadataExtractor.ParsedGoMod result = GoMetadataExtractor.parseGoMod(goMod.toString());

        assertThat(result.requires()).hasSize(depCount);
    }

    /**
     * Goal: replace/exclude/retract blocks never contribute to the dependency list.
     * Rationale: Q4/Q6 - only require directives should produce dependencies.
     */
    @Property
    void parseGoMod_ignoredDirectivesNeverAddDeps(
            @ForAll("ignoredDirectives") String directive,
            @ForAll @IntRange(min = 1, max = 5) int blockSize) {
        StringBuilder goMod = new StringBuilder("module example.com/test\n\ngo 1.21\n\n");
        goMod.append("require github.com/real/dep v1.0.0\n\n");
        goMod.append(directive).append(" (\n");
        for (int i = 0; i < blockSize; i++) {
            goMod.append("\tgithub.com/ignored").append(i).append("/pkg v0.").append(i).append(".0\n");
        }
        goMod.append(")\n");

        GoMetadataExtractor.ParsedGoMod result = GoMetadataExtractor.parseGoMod(goMod.toString());

        assertThat(result.requires()).hasSize(1);
        assertThat(result.requires().get(0).name()).isEqualTo("github.com/real/dep");
    }

    @Provide
    Arbitrary<String> ignoredDirectives() {
        return Arbitraries.of("replace", "exclude", "retract");
    }
}
