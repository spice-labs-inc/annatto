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

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * A package dependency.
 *
 * @param name dependency name (e.g., "lodash", "requests")
 * @param scope optional scope/namespace (e.g., "@types", "dev")
 * @param versionConstraint version constraint string (e.g., "^1.0.0", ">=2.0,<3.0")
 */
public record Dependency(
        @NotNull String name,
        @NotNull Optional<String> scope,
        @NotNull String versionConstraint
) {
    /**
     * Convenience constructor for unscoped dependencies.
     */
    public Dependency(@NotNull String name, @NotNull String versionConstraint) {
        this(name, Optional.empty(), versionConstraint);
    }
}
