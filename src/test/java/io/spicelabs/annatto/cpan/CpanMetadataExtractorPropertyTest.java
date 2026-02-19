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

package io.spicelabs.annatto.cpan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.spicelabs.annatto.common.MetadataResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Property-based tests for {@link CpanMetadataExtractor} using jqwik.
 *
 * <p>These tests verify invariants that must hold for all valid inputs,
 * complementing the example-based tests in {@link CpanMetadataExtractorTest}.</p>
 */
class CpanMetadataExtractorPropertyTest {

    // --- buildMetadataResult properties ---

    /**
     * Goal: buildMetadataResult never throws for valid META.json.
     * Rationale: Any valid JSON with name/version should parse without error.
     */
    @Property
    void buildMetadataResult_neverThrowsForValidJson(
            @ForAll("cpanNames") String name,
            @ForAll("cpanVersions") String version) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("version", version);

        CpanMetadataExtractor.CpanArchiveData data =
                new CpanMetadataExtractor.CpanArchiveData(json.toString(), CpanMetadataExtractor.MetaFormat.JSON);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CpanMetadataExtractor.buildMetadataResult(data);
            assertThat(result).isNotNull();
            assertThat(result.name()).hasValue(name);
            assertThat(result.version()).hasValue(version);
        });
    }

    /**
     * Goal: publishedAt is always empty for CPAN.
     * Rationale: Q6 — No timestamp in META files.
     */
    @Property
    void buildMetadataResult_publishedAtAlwaysEmpty(
            @ForAll("cpanNames") String name,
            @ForAll("cpanVersions") String version) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("version", version);

        CpanMetadataExtractor.CpanArchiveData data =
                new CpanMetadataExtractor.CpanArchiveData(json.toString(), CpanMetadataExtractor.MetaFormat.JSON);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CpanMetadataExtractor.buildMetadataResult(data);
            assertThat(result.publishedAt()).isEmpty();
        });
    }

    /**
     * Goal: Dependencies list is never null.
     * Rationale: Dependencies must always be a non-null list (may be empty).
     */
    @Property
    void buildMetadataResult_dependenciesNeverNull(
            @ForAll @IntRange(min = 0, max = 10) int depCount) {
        JsonObject json = new JsonObject();
        json.addProperty("name", "Test-Dist");
        json.addProperty("version", "1.00");

        if (depCount > 0) {
            JsonObject prereqs = new JsonObject();
            JsonObject runtime = new JsonObject();
            JsonObject requires = new JsonObject();
            for (int i = 0; i < depCount; i++) {
                requires.addProperty("Module::Dep" + i, String.valueOf(i) + ".0");
            }
            runtime.add("requires", requires);
            prereqs.add("runtime", runtime);
            json.add("prereqs", prereqs);
        }

        CpanMetadataExtractor.CpanArchiveData data =
                new CpanMetadataExtractor.CpanArchiveData(json.toString(), CpanMetadataExtractor.MetaFormat.JSON);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CpanMetadataExtractor.buildMetadataResult(data);
            assertThat(result.dependencies()).isNotNull();
            assertThat(result.dependencies()).hasSize(depCount);
        });
    }

    /**
     * Goal: simpleName always equals name for CPAN.
     * Rationale: Q2 — No vendor/namespace concept in CPAN distribution names.
     */
    @Property
    void buildMetadataResult_simpleNameEqualsName(
            @ForAll("cpanNames") String name,
            @ForAll("cpanVersions") String version) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("version", version);

        CpanMetadataExtractor.CpanArchiveData data =
                new CpanMetadataExtractor.CpanArchiveData(json.toString(), CpanMetadataExtractor.MetaFormat.JSON);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CpanMetadataExtractor.buildMetadataResult(data);
            assertThat(result.simpleName()).isEqualTo(result.name());
        });
    }

    // --- extractLicense properties ---

    /**
     * Goal: ["unknown"] always maps to empty license.
     * Rationale: Q7 — Unknown license must normalize to absent.
     */
    @Property
    void extractLicense_unknownAlwaysEmpty(
            @ForAll("cpanNames") String ignoredName) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add("unknown");
        root.add("license", arr);
        assertThat(CpanMetadataExtractor.extractLicense(root)).isEmpty();
    }

    /**
     * Goal: Non-empty license array always produces a non-empty result.
     * Rationale: Q7 — Valid licenses must be preserved.
     */
    @Property
    void extractLicense_validLicenseAlwaysPresent(
            @ForAll("licenseIds") String license) {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        arr.add(license);
        root.add("license", arr);
        assertThat(CpanMetadataExtractor.extractLicense(root)).isPresent();
    }

    // --- extractDescription properties ---

    /**
     * Goal: "unknown" abstract always maps to empty.
     * Rationale: Placeholder abstracts must be filtered.
     */
    @Property
    void extractDescription_unknownAlwaysEmpty(
            @ForAll("cpanNames") String ignoredName) {
        JsonObject root = new JsonObject();
        root.addProperty("abstract", "unknown");
        assertThat(CpanMetadataExtractor.extractDescription(root)).isEmpty();
    }

    /**
     * Goal: Non-empty non-unknown abstract always present.
     * Rationale: Valid descriptions must be preserved.
     */
    @Property
    void extractDescription_validAbstractAlwaysPresent(
            @ForAll("descriptions") String desc) {
        JsonObject root = new JsonObject();
        root.addProperty("abstract", desc);
        Optional<String> result = CpanMetadataExtractor.extractDescription(root);
        if (!desc.isEmpty() && !desc.equalsIgnoreCase("unknown")) {
            assertThat(result).hasValue(desc);
        }
    }

    // --- Version constraint properties ---

    /**
     * Goal: Version "0" always maps to empty constraint.
     * Rationale: Q5 — "0" means any version.
     */
    @Property
    void versionZero_alwaysMapsToEmpty(
            @ForAll("cpanDepNames") String depName) {
        JsonObject root = new JsonObject();
        JsonObject prereqs = new JsonObject();
        JsonObject runtime = new JsonObject();
        JsonObject requires = new JsonObject();
        requires.addProperty(depName, "0");
        runtime.add("requires", requires);
        prereqs.add("runtime", runtime);
        root.add("prereqs", prereqs);

        var deps = CpanMetadataExtractor.extractDependencies(root);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).versionConstraint()).isEmpty();
    }

    /**
     * Goal: Non-zero version constraint is always preserved.
     * Rationale: Q5 — Versions other than "0" must be kept.
     */
    @Property
    void nonZeroVersion_alwaysPreserved(
            @ForAll("cpanDepNames") String depName,
            @ForAll("nonZeroVersions") String version) {
        JsonObject root = new JsonObject();
        JsonObject prereqs = new JsonObject();
        JsonObject runtime = new JsonObject();
        JsonObject requires = new JsonObject();
        requires.addProperty(depName, version);
        runtime.add("requires", requires);
        prereqs.add("runtime", runtime);
        root.add("prereqs", prereqs);

        var deps = CpanMetadataExtractor.extractDependencies(root);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).versionConstraint()).hasValue(version);
    }

    // --- Arbitrary providers ---

    @Provide
    Arbitrary<String> cpanNames() {
        return Arbitraries.of(
                "Moose", "Try-Tiny", "DateTime", "Test-Simple",
                "Module-Build", "Path-Tiny", "JSON", "DBI",
                "namespace-clean", "constant", "parent", "Encode");
    }

    @Provide
    Arbitrary<String> cpanVersions() {
        return Arbitraries.of(
                "1.00", "2.2207", "0.31", "1.302199",
                "0.4234", "0.146", "4.10", "20240903",
                "0.51", "0.001013", "1.643", "5.78");
    }

    @Provide
    Arbitrary<String> cpanDepNames() {
        return Arbitraries.of(
                "Carp", "Class::Load", "Test::More", "ExtUtils::MakeMaker",
                "Module::Runtime", "Scalar::Util", "File::Spec", "strict");
    }

    @Provide
    Arbitrary<String> licenseIds() {
        return Arbitraries.of(
                "perl_5", "mit", "apache_2_0", "bsd",
                "gpl_2", "lgpl_2_1", "artistic_2", "mozilla_1_1");
    }

    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '-')
                .ofMinLength(1).ofMaxLength(50)
                .filter(s -> !s.equalsIgnoreCase("unknown"));
    }

    @Provide
    Arbitrary<String> nonZeroVersions() {
        return Arbitraries.of(
                "1.00", "0.01", "0.001", "5.008001",
                "1.22", "0.980", "6.30", "0.09");
    }
}
