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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link EcosystemId} enum.
 */
class EcosystemIdTest {

    /**
     * Goal: Verify all 11 ecosystems are defined.
     * Rationale: Missing ecosystems would break handler registration.
     */
    @Test
    void allElevenEcosystemsDefined() {
        assertThat(EcosystemId.values()).hasSize(11);
    }

    /**
     * Goal: Verify each ecosystem has a non-empty display name.
     * Rationale: Display names are used in logging and error messages.
     */
    @Test
    void allEcosystemsHaveDisplayName() {
        for (EcosystemId id : EcosystemId.values()) {
            assertThat(id.displayName()).isNotBlank();
        }
    }

    /**
     * Goal: Verify each ecosystem has a non-empty PURL type.
     * Rationale: PURL types are required for PackageURL construction.
     */
    @Test
    void allEcosystemsHavePurlType() {
        for (EcosystemId id : EcosystemId.values()) {
            assertThat(id.purlType()).isNotBlank();
        }
    }

    /**
     * Goal: Verify specific PURL type mappings are correct.
     * Rationale: These must match the purl-spec for correct interop.
     */
    @Test
    void purlTypeMappingsAreCorrect() {
        assertThat(EcosystemId.NPM.purlType()).isEqualTo("npm");
        assertThat(EcosystemId.PYPI.purlType()).isEqualTo("pypi");
        assertThat(EcosystemId.GO.purlType()).isEqualTo("golang");
        assertThat(EcosystemId.CRATES.purlType()).isEqualTo("cargo");
        assertThat(EcosystemId.RUBYGEMS.purlType()).isEqualTo("gem");
        assertThat(EcosystemId.PACKAGIST.purlType()).isEqualTo("composer");
        assertThat(EcosystemId.CONDA.purlType()).isEqualTo("conda");
        assertThat(EcosystemId.COCOAPODS.purlType()).isEqualTo("cocoapods");
        assertThat(EcosystemId.CPAN.purlType()).isEqualTo("cpan");
        assertThat(EcosystemId.HEX.purlType()).isEqualTo("hex");
        assertThat(EcosystemId.LUAROCKS.purlType()).isEqualTo("luarocks");
    }
}
