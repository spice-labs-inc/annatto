/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spicelabs.annatto.hex;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Memento for Hex package metadata extraction.
 *
 * <p>Holds the raw metadata.config text and parsed MetadataResult.</p>
 */
public final class HexMemento extends BaseMemento {

    private final @Nullable String rawMetadataConfig;

    /**
     * Creates a memento for a successfully parsed Hex package.
     */
    HexMemento(@NotNull String artifactFilename,
               @NotNull String rawMetadataConfig,
               @NotNull MetadataResult result) {
        super(EcosystemId.HEX, artifactFilename);
        this.rawMetadataConfig = rawMetadataConfig;
        setMetadataResult(result);
    }

    /**
     * Creates a memento for a failed extraction (no metadata).
     */
    HexMemento(@NotNull String artifactFilename) {
        super(EcosystemId.HEX, artifactFilename);
        this.rawMetadataConfig = null;
    }

    /**
     * Returns the raw metadata.config text, if available.
     */
    @NotNull Optional<String> rawMetadataConfig() {
        return Optional.ofNullable(rawMetadataConfig);
    }

    /**
     * Returns the package name from the metadata result.
     */
    @NotNull Optional<String> packageName() {
        return metadataResult().flatMap(MetadataResult::name);
    }

    /**
     * Returns the package version from the metadata result.
     */
    @NotNull Optional<String> packageVersion() {
        return metadataResult().flatMap(MetadataResult::version);
    }
}
