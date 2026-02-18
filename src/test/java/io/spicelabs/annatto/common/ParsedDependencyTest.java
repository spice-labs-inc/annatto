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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ParsedDependency}.
 */
class ParsedDependencyTest {

    /**
     * Goal: Verify formatting with version constraint.
     * Rationale: Dependencies with constraints should show name@constraint.
     */
    @Test
    void toFormattedString_withVersion() {
        var dep = new ParsedDependency("lodash", Optional.of("^4.17.0"), Optional.empty());
        assertThat(dep.toFormattedString()).isEqualTo("lodash@^4.17.0");
    }

    /**
     * Goal: Verify formatting without version constraint.
     * Rationale: Dependencies without constraints should show just the name.
     */
    @Test
    void toFormattedString_withoutVersion() {
        var dep = new ParsedDependency("lodash", Optional.empty(), Optional.empty());
        assertThat(dep.toFormattedString()).isEqualTo("lodash");
    }
}
