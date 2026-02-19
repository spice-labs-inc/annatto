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
 * Property-based tests for {@link CocoapodsMetadataExtractor} using jqwik.
 */
class CocoapodsMetadataExtractorPropertyTest {

    /**
     * Goal: buildMetadataResult never throws for valid podspec JSON.
     * Rationale: Any valid JSON with name/version should parse without error.
     */
    @Property
    void buildMetadataResult_neverThrowsForValidJson(
            @ForAll("podNames") String name,
            @ForAll("versions") String version) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("version", version);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CocoapodsMetadataExtractor.buildMetadataResult(json.toString());
            assertThat(result).isNotNull();
            assertThat(result.name()).hasValue(name);
            assertThat(result.version()).hasValue(version);
        });
    }

    /**
     * Goal: publishedAt is always empty.
     * Rationale: Q6 — No timestamp in podspec.json.
     */
    @Property
    void buildMetadataResult_publishedAtAlwaysEmpty(
            @ForAll("podNames") String name,
            @ForAll("versions") String version) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("version", version);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CocoapodsMetadataExtractor.buildMetadataResult(json.toString());
            assertThat(result.publishedAt()).isEmpty();
        });
    }

    /**
     * Goal: Dependencies list is never null.
     * Rationale: Dependencies must always be a non-null list.
     */
    @Property
    void buildMetadataResult_dependenciesNeverNull(
            @ForAll @IntRange(min = 0, max = 5) int depCount) {
        JsonObject json = new JsonObject();
        json.addProperty("name", "TestPod");
        json.addProperty("version", "1.0");

        if (depCount > 0) {
            JsonObject deps = new JsonObject();
            for (int i = 0; i < depCount; i++) {
                deps.add("Dep" + i, new JsonArray());
            }
            json.add("dependencies", deps);
        }

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CocoapodsMetadataExtractor.buildMetadataResult(json.toString());
            assertThat(result.dependencies()).isNotNull();
            assertThat(result.dependencies()).hasSize(depCount);
        });
    }

    /**
     * Goal: simpleName always equals name.
     * Rationale: No namespace concept in CocoaPods.
     */
    @Property
    void buildMetadataResult_simpleNameEqualsName(
            @ForAll("podNames") String name,
            @ForAll("versions") String version) {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("version", version);

        assertThatNoException().isThrownBy(() -> {
            MetadataResult result = CocoapodsMetadataExtractor.buildMetadataResult(json.toString());
            assertThat(result.simpleName()).isEqualTo(result.name());
        });
    }

    /**
     * Goal: String license is preserved.
     * Rationale: Q2 — Direct string licenses must be returned.
     */
    @Property
    void extractLicense_stringPreserved(@ForAll("licenses") String license) {
        JsonObject root = new JsonObject();
        root.addProperty("license", license);
        assertThat(CocoapodsMetadataExtractor.extractLicense(root)).hasValue(license);
    }

    /**
     * Goal: Object license type is extracted.
     * Rationale: Q2 — {"type": "MIT"} must extract "MIT".
     */
    @Property
    void extractLicense_objectTypeExtracted(@ForAll("licenses") String license) {
        JsonObject root = new JsonObject();
        JsonObject lic = new JsonObject();
        lic.addProperty("type", license);
        root.add("license", lic);
        assertThat(CocoapodsMetadataExtractor.extractLicense(root)).hasValue(license);
    }

    /**
     * Goal: Self-referencing deps always filtered.
     * Rationale: Q4 — Pod name and pod/subspec must never appear in results.
     */
    @Property
    void extractDeps_selfReferencingAlwaysFiltered(
            @ForAll("podNames") String podName,
            @ForAll("subspecNames") String subName) {
        JsonObject root = new JsonObject();
        root.addProperty("name", podName);
        JsonObject deps = new JsonObject();
        deps.add(podName + "/" + subName, new JsonArray());
        deps.add("ExternalDep", new JsonArray());
        root.add("dependencies", deps);

        var result = CocoapodsMetadataExtractor.extractDependencies(root);
        assertThat(result.stream().map(d -> d.name()))
                .doesNotContain(podName, podName + "/" + subName);
    }

    /**
     * Goal: Summary preferred over description.
     * Rationale: Summary is more concise and should take precedence.
     */
    @Property
    void extractDescription_summaryPreferred(
            @ForAll("descriptions") String summary,
            @ForAll("descriptions") String description) {
        JsonObject root = new JsonObject();
        root.addProperty("summary", summary);
        root.addProperty("description", description);
        assertThat(CocoapodsMetadataExtractor.extractDescription(root)).hasValue(summary);
    }

    // --- Arbitrary providers ---

    @Provide
    Arbitrary<String> podNames() {
        return Arbitraries.of(
                "Alamofire", "SnapKit", "AFNetworking", "SDWebImage",
                "RxSwift", "Kingfisher", "Charts", "Firebase",
                "lottie-ios", "BoringSSL-GRPC", "SwiftyJSON", "Realm");
    }

    @Provide
    Arbitrary<String> versions() {
        return Arbitraries.of(
                "1.0", "5.8.1", "4.0.1", "10.22.0",
                "0.0.32", "1.20240116.1", "3.2", "7.11.0");
    }

    @Provide
    Arbitrary<String> licenses() {
        return Arbitraries.of("MIT", "Apache-2.0", "BSD", "ISC",
                "LGPL-2.1", "MPL-2.0", "Zlib", "Unlicense");
    }

    @Provide
    Arbitrary<String> subspecNames() {
        return Arbitraries.of("Core", "Default", "Serialization",
                "Security", "UIKit", "NSURLSession");
    }

    @Provide
    Arbitrary<String> descriptions() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '-')
                .ofMinLength(1).ofMaxLength(50);
    }
}
