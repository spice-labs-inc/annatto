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

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Represents a single dependency extracted from a package manifest.
 * (tested by {@code ParsedDependencyTest.toFormattedString_withVersion},
 * {@code ParsedDependencyTest.toFormattedString_withoutVersion})
 *
 * @param name              the dependency name
 * @param versionConstraint the version constraint string, if present
 * @param scope             the dependency scope (e.g., "runtime", "dev", "build"), if present
 */
public record ParsedDependency(
        @NotNull String name,
        @NotNull Optional<String> versionConstraint,
        @NotNull Optional<String> scope
) {

    /**
     * Formats this dependency as {@code name@versionConstraint} or just {@code name}
     * if no version constraint is present.
     *
     * @return the formatted dependency string
     */
    public @NotNull String toFormattedString() {
        return versionConstraint
                .map(vc -> name + "@" + vc)
                .orElse(name);
    }
}
