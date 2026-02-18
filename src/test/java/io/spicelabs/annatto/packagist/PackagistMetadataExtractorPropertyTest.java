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

package io.spicelabs.annatto.packagist;

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

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Property-based tests for {@link PackagistMetadataExtractor} using jqwik.
 *
 * <p>These tests verify invariants that must hold for all valid inputs,
 * complementing the example-based tests in {@link PackagistMetadataExtractorTest}.</p>
 */
class PackagistMetadataExtractorPropertyTest {

    private static final Set<String> VALID_SCOPES = Set.of("runtime", "dev");

    private static final Set<String> PLATFORM_PREFIXES = Set.of(
            "php", "php-64bit", "hhvm", "composer-plugin-api", "composer-runtime-api", "composer"
    );

    // --- isComposerJson properties ---

    /**
     * Goal: isComposerJson always rejects entries with path traversal.
     * Rationale: Security — entries with ".." must never be accepted.
     */
    @Property
    void isComposerJson_falseForPathTraversal(@ForAll("pathTraversalEntries") String entryName) {
        assertThat(PackagistMetadataExtractor.isComposerJson(entryName)).isFalse();
    }

    @Provide
    Arbitrary<String> pathTraversalEntries() {
        return Arbitraries.of(
                "../composer.json",
                "dir/../composer.json",
                "../../etc/composer.json",
                "..\\composer.json",
                "dir/..\\composer.json"
        );
    }

    // --- extractSimpleName properties ---

    /**
     * Goal: extractSimpleName always returns non-empty for valid vendor/name input.
     * Rationale: Q2 — simpleName must always be non-empty for valid names.
     */
    @Property
    void extractSimpleName_alwaysNonEmpty(@ForAll("validPackageNames") String fullName) {
        String simpleName = PackagistMetadataExtractor.extractSimpleName(fullName);
        assertThat(simpleName).isNotEmpty();
    }

    /**
     * Goal: extractSimpleName is idempotent.
     * Rationale: Applying extractSimpleName twice gives the same result.
     */
    @Property
    void extractSimpleName_idempotent(@ForAll("validPackageNames") String fullName) {
        String first = PackagistMetadataExtractor.extractSimpleName(fullName);
        String second = PackagistMetadataExtractor.extractSimpleName(first);
        assertThat(second).isEqualTo(first);
    }

    @Provide
    Arbitrary<String> validPackageNames() {
        Arbitrary<String> vendor = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('-', '_')
                .ofMinLength(1).ofMaxLength(15)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_"));
        Arbitrary<String> name = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('-', '_')
                .ofMinLength(1).ofMaxLength(15)
                .filter(s -> !s.startsWith("-") && !s.startsWith("_"));
        return Combinators.combine(vendor, name).as((v, n) -> v + "/" + n);
    }

    // --- extractLicense properties ---

    /**
     * Goal: extractLicense never returns an empty string.
     * Rationale: Q6 — result is either a non-empty string or Optional.empty().
     */
    @Property
    void extractLicense_neverReturnsEmptyString(@ForAll("licenseJsons") JsonObject json) {
        Optional<String> license = PackagistMetadataExtractor.extractLicense(json);
        license.ifPresent(l -> assertThat(l).isNotEmpty());
    }

    @Provide
    Arbitrary<JsonObject> licenseJsons() {
        Arbitrary<String> licenses = Arbitraries.of("MIT", "Apache-2.0", "GPL-3.0", "BSD-3-Clause");

        // String license
        Arbitrary<JsonObject> stringLicense = licenses.map(l -> {
            JsonObject json = new JsonObject();
            json.addProperty("license", l);
            return json;
        });

        // Array license
        Arbitrary<JsonObject> arrayLicense = licenses.list().ofMinSize(1).ofMaxSize(3).map(ls -> {
            JsonObject json = new JsonObject();
            JsonArray arr = new JsonArray();
            ls.forEach(arr::add);
            json.add("license", arr);
            return json;
        });

        // No license
        Arbitrary<JsonObject> noLicense = Arbitraries.just(new JsonObject());

        return Arbitraries.oneOf(stringLicense, arrayLicense, noLicense);
    }

    // --- extractDependencies properties ---

    /**
     * Goal: extractDependencies never includes platform dependencies.
     * Rationale: Q3 — php, ext-*, lib-*, composer-plugin-api must never appear in output.
     */
    @Property
    void extractDependencies_neverIncludesPlatformDeps(
            @ForAll("composerJsonWithPlatformDeps") JsonObject json) {
        List<ParsedDependency> deps = PackagistMetadataExtractor.extractDependencies(json);
        for (ParsedDependency dep : deps) {
            assertThat(PackagistMetadataExtractor.isPlatformDependency(dep.name()))
                    .as("dependency %s should not be a platform dep", dep.name())
                    .isFalse();
        }
    }

    @Provide
    Arbitrary<JsonObject> composerJsonWithPlatformDeps() {
        return Arbitraries.of(
                "php", "ext-json", "ext-mbstring", "lib-libxml",
                "composer-plugin-api", "composer-runtime-api"
        ).list().ofMinSize(1).ofMaxSize(3).map(platformDeps -> {
            JsonObject require = new JsonObject();
            for (String dep : platformDeps) {
                require.addProperty(dep, ">=8.0");
            }
            require.addProperty("monolog/monolog", "^3.0");
            JsonObject json = new JsonObject();
            json.add("require", require);
            return json;
        });
    }

    // --- isPlatformDependency properties ---

    /**
     * Goal: isPlatformDependency correctly identifies all platform dep types.
     * Rationale: Q3 — all platform deps must be caught.
     */
    @Property
    void isPlatformDependency_trueForPhpAndExtensions(@ForAll("platformDeps") String name) {
        assertThat(PackagistMetadataExtractor.isPlatformDependency(name)).isTrue();
    }

    @Provide
    Arbitrary<String> platformDeps() {
        Arbitrary<String> exact = Arbitraries.of(
                "php", "php-64bit", "hhvm", "composer-plugin-api", "composer-runtime-api", "composer"
        );
        Arbitrary<String> extDeps = Arbitraries.of(
                "ext-json", "ext-mbstring", "ext-curl", "ext-openssl", "ext-pdo"
        );
        Arbitrary<String> libDeps = Arbitraries.of(
                "lib-libxml", "lib-openssl", "lib-pcre"
        );
        return Arbitraries.oneOf(exact, extDeps, libDeps);
    }

    // --- buildMetadataResult properties ---

    /**
     * Goal: buildMetadataResult never throws for valid minimal composer.json.
     * Rationale: The extractor must gracefully handle any valid JSON.
     */
    @Property
    void buildMetadataResult_neverThrowsForValidJson(
            @ForAll("minimalComposerJsons") String composerJson) {
        PackagistMetadataExtractor.ComposerJsonData data =
                new PackagistMetadataExtractor.ComposerJsonData(composerJson);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = PackagistMetadataExtractor.buildMetadataResult(data);
            assertThat(result).isNotNull();
            assertThat(result.dependencies()).isNotNull();
        });
    }

    /**
     * Goal: buildMetadataResult dependencies list is never null.
     * Rationale: Dependencies must always be a non-null list.
     */
    @Property
    void buildMetadataResult_dependenciesNeverNull(
            @ForAll("minimalComposerJsons") String composerJson) throws Exception {
        PackagistMetadataExtractor.ComposerJsonData data =
                new PackagistMetadataExtractor.ComposerJsonData(composerJson);
        MetadataResult result = PackagistMetadataExtractor.buildMetadataResult(data);
        assertThat(result.dependencies()).isNotNull();
    }

    @Provide
    Arbitrary<String> minimalComposerJsons() {
        Arbitrary<String> vendor = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('-')
                .ofMinLength(2).ofMaxLength(10)
                .filter(s -> !s.startsWith("-") && !s.endsWith("-"));
        Arbitrary<String> name = Arbitraries.strings()
                .withCharRange('a', 'z').withChars('-')
                .ofMinLength(2).ofMaxLength(10)
                .filter(s -> !s.startsWith("-") && !s.endsWith("-"));

        return Combinators.combine(vendor, name)
                .as((v, n) -> String.format("""
                        {"name": "%s/%s", "description": "A test package"}
                        """, v, n));
    }

    // --- extractPublisher properties ---

    /**
     * Goal: extractPublisher never returns an empty string.
     * Rationale: Result is either a non-empty name or Optional.empty().
     */
    @Property
    void extractPublisher_neverReturnsEmptyString(@ForAll("authorJsons") JsonObject json) {
        Optional<String> publisher = PackagistMetadataExtractor.extractPublisher(json);
        publisher.ifPresent(p -> assertThat(p).isNotEmpty());
    }

    @Provide
    Arbitrary<JsonObject> authorJsons() {
        Arbitrary<String> names = Arbitraries.of(
                "Alice", "Bob", "Charlie", "PHP-FIG", "Sebastian Bergmann"
        );

        Arbitrary<JsonObject> withAuthor = names.map(n -> {
            JsonObject author = new JsonObject();
            author.addProperty("name", n);
            JsonArray authors = new JsonArray();
            authors.add(author);
            JsonObject json = new JsonObject();
            json.add("authors", authors);
            return json;
        });

        Arbitrary<JsonObject> noAuthors = Arbitraries.just(new JsonObject());

        return Arbitraries.oneOf(withAuthor, noAuthors);
    }

    // --- extractDependencies scope properties ---

    /**
     * Goal: Every dependency scope is "runtime" or "dev".
     * Rationale: Q3 — only two valid scopes for Packagist.
     */
    @Property
    void extractDependencies_scopeAlwaysValid(
            @ForAll("composerJsonWithDeps") JsonObject json) {
        List<ParsedDependency> deps = PackagistMetadataExtractor.extractDependencies(json);
        for (ParsedDependency dep : deps) {
            assertThat(dep.scope()).isPresent();
            assertThat(VALID_SCOPES).contains(dep.scope().get());
        }
    }

    @Provide
    Arbitrary<JsonObject> composerJsonWithDeps() {
        return Arbitraries.of("require", "require-dev").map(section -> {
            JsonObject sectionObj = new JsonObject();
            sectionObj.addProperty("some/package", "^1.0");
            JsonObject json = new JsonObject();
            json.add(section, sectionObj);
            return json;
        });
    }
}
