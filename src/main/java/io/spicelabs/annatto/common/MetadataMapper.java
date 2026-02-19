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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps a {@link MetadataResult} to a list of {@link Metadata} instances
 * using the rodeo-components {@link MetadataTag} enum.
 * (tested by {@code MetadataMapperTest} — 3 tests covering full mapping,
 * empty optionals, and dependency formatting)
 */
public final class MetadataMapper {

    private MetadataMapper() {
    }

    /**
     * Converts a normalized {@link MetadataResult} into a list of {@link Metadata}
     * entries suitable for the rodeo-components API.
     *
     * @param result the normalized metadata result
     * @return an immutable list of Metadata entries
     */
    public static @NotNull List<Metadata> toMetadataList(@NotNull MetadataResult result) {
        var list = new ArrayList<Metadata>();

        result.name().ifPresent(v -> list.add(new Metadata(MetadataTag.NAME, v)));
        result.simpleName().ifPresent(v -> list.add(new Metadata(MetadataTag.SIMPLE_NAME, v)));
        result.version().ifPresent(v -> list.add(new Metadata(MetadataTag.VERSION, v)));
        result.description().ifPresent(v -> list.add(new Metadata(MetadataTag.DESCRIPTION, v)));
        result.license().ifPresent(v -> list.add(new Metadata(MetadataTag.LICENSE, v)));
        result.publisher().ifPresent(v -> list.add(new Metadata(MetadataTag.PUBLISHER, v)));
        result.publishedAt().ifPresent(v -> list.add(new Metadata(MetadataTag.PUBLICATION_DATE, v)));

        if (!result.dependencies().isEmpty()) {
            String depsValue = result.dependencies().stream()
                    .map(ParsedDependency::toFormattedString)
                    .collect(Collectors.joining(", "));
            list.add(new Metadata(MetadataTag.DEPENDENCIES, depsValue));
        }

        return List.copyOf(list);
    }
}
