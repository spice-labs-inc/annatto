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

import io.spicelabs.annatto.Dependency;
import io.spicelabs.annatto.PackageMetadata;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Normalized metadata extraction result that is common across all ecosystems.
 * All fields use {@link Optional} for values that may be absent rather than returning null.
 * (tested by {@code MetadataMapperTest.toMetadataList_mapsAllPresentFields},
 * {@code MetadataMapperTest.toMetadataList_skipsEmptyOptionals})
 *
 * @param ecosystem    the ecosystem this metadata was extracted from
 * @param name         the fully qualified package name (e.g., {@code @scope/name}, {@code vendor/package})
 * @param simpleName   the unqualified short name
 * @param version      the package version as-is from the ecosystem
 * @param description  the package description or summary
 * @param license      the license identifier, normalized to SPDX where possible
 * @param publisher    the first author or maintainer name
 * @param publishedAt  the publication date in ISO 8601 format, if available
 * @param dependencies the list of parsed dependencies
 */
public record MetadataResult(
        @NotNull EcosystemId ecosystem,
        @NotNull Optional<String> name,
        @NotNull Optional<String> simpleName,
        @NotNull Optional<String> version,
        @NotNull Optional<String> description,
        @NotNull Optional<String> license,
        @NotNull Optional<String> publisher,
        @NotNull Optional<String> publishedAt,
        @NotNull List<ParsedDependency> dependencies
) {

    /**
     * Creates a MetadataResult with defensively copied dependency list.
     */
    public MetadataResult {
        dependencies = List.copyOf(dependencies);
    }

    /**
     * Converts this result to the {@link PackageMetadata} exposed by the
     * {@link io.spicelabs.annatto.LanguagePackage} API, so that both APIs report the same
     * values (issue #14). An absent name or version becomes the empty string.
     *
     * @param raw ecosystem-specific raw values to carry alongside the normalized fields
     * @return the equivalent package metadata
     */
    public @NotNull PackageMetadata toPackageMetadata(@NotNull Map<String, Object> raw) {
        List<Dependency> deps = dependencies.stream()
                .map(dep -> new Dependency(dep.name(), dep.scope(), dep.versionConstraint().orElse("")))
                .toList();
        return new PackageMetadata(
                name.orElse(""),
                version.orElse(""),
                description,
                license,
                publisher,
                Optional.empty(),
                deps,
                raw
        );
    }
}
