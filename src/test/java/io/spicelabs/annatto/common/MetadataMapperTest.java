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

package io.spicelabs.annatto.common;

import io.spicelabs.rodeocomponents.APIS.artifacts.Metadata;
import io.spicelabs.rodeocomponents.APIS.artifacts.MetadataTag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link MetadataMapper}.
 */
class MetadataMapperTest {

    /**
     * Goal: Verify that all present fields are mapped to Metadata entries.
     * Rationale: Each metadata tag must be correctly mapped for downstream consumers.
     */
    @Test
    void toMetadataList_mapsAllPresentFields() {
        var result = new MetadataResult(
                EcosystemId.NPM,
                Optional.of("@scope/name"),
                Optional.of("name"),
                Optional.of("1.0.0"),
                Optional.of("A package"),
                Optional.of("MIT"),
                Optional.of("Author"),
                Optional.of("2024-01-01T00:00:00Z"),
                List.of(new ParsedDependency("dep1", Optional.of("^2.0.0"), Optional.empty()))
        );

        List<Metadata> metadata = MetadataMapper.toMetadataList(result);

        assertThat(metadata).hasSize(8);
        assertThat(findByTag(metadata, MetadataTag.NAME)).isEqualTo("@scope/name");
        assertThat(findByTag(metadata, MetadataTag.SIMPLE_NAME)).isEqualTo("name");
        assertThat(findByTag(metadata, MetadataTag.VERSION)).isEqualTo("1.0.0");
        assertThat(findByTag(metadata, MetadataTag.DESCRIPTION)).isEqualTo("A package");
        assertThat(findByTag(metadata, MetadataTag.LICENSE)).isEqualTo("MIT");
        assertThat(findByTag(metadata, MetadataTag.PUBLISHER)).isEqualTo("Author");
        assertThat(findByTag(metadata, MetadataTag.PUBLICATION_DATE)).isEqualTo("2024-01-01T00:00:00Z");
        assertThat(findByTag(metadata, MetadataTag.DEPENDENCIES)).isEqualTo("dep1@^2.0.0");
    }

    /**
     * Goal: Verify that absent fields produce no Metadata entries.
     * Rationale: Pessimistic extraction means absent fields should not appear.
     */
    @Test
    void toMetadataList_skipsEmptyOptionals() {
        var result = new MetadataResult(
                EcosystemId.NPM,
                Optional.of("name"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of()
        );

        List<Metadata> metadata = MetadataMapper.toMetadataList(result);

        assertThat(metadata).hasSize(1);
        assertThat(metadata.get(0).tag()).isEqualTo(MetadataTag.NAME);
    }

    /**
     * Goal: Verify dependencies with no version constraint are formatted correctly.
     * Rationale: Some ecosystems may not specify version constraints.
     */
    @Test
    void toMetadataList_formatsDepWithoutVersion() {
        var result = new MetadataResult(
                EcosystemId.GO,
                Optional.of("module"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(new ParsedDependency("dep", Optional.empty(), Optional.empty()))
        );

        List<Metadata> metadata = MetadataMapper.toMetadataList(result);

        assertThat(findByTag(metadata, MetadataTag.DEPENDENCIES)).isEqualTo("dep");
    }

    private String findByTag(List<Metadata> metadata, MetadataTag tag) {
        return metadata.stream()
                .filter(m -> m.tag() == tag)
                .findFirst()
                .map(Metadata::value)
                .orElse(null);
    }
}
