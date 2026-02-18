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

package io.spicelabs.annatto;

import io.spicelabs.rodeocomponents.RodeoEnvironment;
import io.spicelabs.rodeocomponents.RodeoIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Tests for {@link AnnattoComponent} lifecycle and identity.
 */
class AnnattoComponentTest {

    /**
     * Goal: Verify the component identity returns expected name and publisher.
     * Rationale: The identity is used by the host to identify this component.
     */
    @Test
    void getIdentity_returnsCorrectNameAndPublisher() {
        var component = new AnnattoComponent();
        RodeoIdentity identity = component.getIdentity();

        assertThat(identity.name()).isEqualTo("Annatto");
        assertThat(identity.publisher()).isEqualTo("Spice Labs, Inc.");
    }

    /**
     * Goal: Verify the component version matches the current rodeo-components API version.
     * Rationale: Version mismatch would prevent the host from loading this component.
     */
    @Test
    void getComponentVersion_matchesRodeoEnvironment() {
        var component = new AnnattoComponent();
        assertThat(component.getComponentVersion()).isEqualTo(RodeoEnvironment.currentVersion());
    }

    /**
     * Goal: Verify initialize() does not throw.
     * Rationale: Initialization must succeed for the component to load.
     */
    @Test
    void initialize_doesNotThrow() {
        var component = new AnnattoComponent();
        assertThatCode(component::initialize).doesNotThrowAnyException();
    }

    /**
     * Goal: Verify onLoadingComplete() does not throw.
     * Rationale: Called after all imports; must not fail.
     */
    @Test
    void onLoadingComplete_doesNotThrow() {
        var component = new AnnattoComponent();
        assertThatCode(component::onLoadingComplete).doesNotThrowAnyException();
    }

    /**
     * Goal: Verify shutDown() is safe to call even without prior initialization.
     * Rationale: Shutdown must be idempotent and safe to call in any state.
     */
    @Test
    void shutDown_isSafeWithoutInitialization() {
        var component = new AnnattoComponent();
        assertThatCode(component::shutDown).doesNotThrowAnyException();
    }

    /**
     * Goal: Verify the ServiceLoader registration file is present and correct.
     * Rationale: Without this file, the host will not discover the component.
     */
    @Test
    void serviceLoaderRegistration_isPresent() throws Exception {
        var resources = getClass().getClassLoader()
                .getResources("META-INF/services/io.spicelabs.rodeocomponents.RodeoComponent");
        assertThat(resources.hasMoreElements()).isTrue();

        String content = new String(resources.nextElement().openStream().readAllBytes());
        assertThat(content.trim()).contains("io.spicelabs.annatto.AnnattoComponent");
    }
}
