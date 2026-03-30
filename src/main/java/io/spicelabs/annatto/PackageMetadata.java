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

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Normalized package metadata across all ecosystems.
 *
 * <p>Claims:
 * <ul>
 *   <li>All fields are non-null (use Optional or empty collections) (verified by LanguagePackageContractTest)</li>
 *   <li>dependencies list is immutable (verified by LanguagePackageContractTest)</li>
 *   <li>raw map is immutable (verified by LanguagePackageContractTest)</li>
 * </ul>
 *
 * <p>Extensibility (ADR-003):
 * Use {@link #raw()} to access ecosystem-specific fields not covered by standard fields.
 */
public record PackageMetadata(
        @NotNull String name,
        @NotNull String version,
        @NotNull Optional<String> description,
        @NotNull Optional<String> license,
        @NotNull Optional<String> publisher,
        @NotNull Optional<Instant> publishedAt,
        @NotNull List<Dependency> dependencies,
        @NotNull Map<String, Object> raw
) {

    /**
     * Compact constructor ensures immutability.
     * Null raw map is converted to empty map; null values in raw are filtered out.
     */
    public PackageMetadata {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        raw = raw != null
            ? Map.copyOf(raw.entrySet().stream()
                .filter(e -> e.getKey() != null && e.getValue() != null)
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey, Map.Entry::getValue)))
            : Map.of();
    }

    /**
     * Builder-style constructor with defaults.
     */
    public PackageMetadata(
            @NotNull String name,
            @NotNull String version,
            @NotNull Optional<String> description,
            @NotNull Optional<String> license,
            @NotNull Optional<String> publisher,
            @NotNull Optional<Instant> publishedAt,
            @NotNull List<Dependency> dependencies
    ) {
        this(name, version, description, license, publisher, publishedAt, dependencies, Collections.emptyMap());
    }

    /**
     * Empty metadata for failed extraction.
     */
    public static PackageMetadata empty() {
        return new PackageMetadata(
                "", "",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), Map.of()
        );
    }
}
