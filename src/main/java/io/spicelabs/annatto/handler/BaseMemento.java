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

package io.spicelabs.annatto.handler;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactMemento;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Base memento holding common fields shared across all ecosystem handlers.
 * Each ecosystem extends this with additional state as needed.
 * Mementos are created per-artifact in {@code begin()} and are not shared across threads.
 */
public class BaseMemento implements ArtifactMemento {

    private final EcosystemId ecosystemId;
    private final String artifactFilename;
    private volatile MetadataResult metadataResult;

    /**
     * @param ecosystemId      the ecosystem this artifact belongs to
     * @param artifactFilename the filename of the artifact being processed
     */
    public BaseMemento(@NotNull EcosystemId ecosystemId, @NotNull String artifactFilename) {
        this.ecosystemId = ecosystemId;
        this.artifactFilename = artifactFilename;
    }

    /**
     * @return the ecosystem identifier
     */
    public @NotNull EcosystemId ecosystemId() {
        return ecosystemId;
    }

    /**
     * @return the artifact filename
     */
    public @NotNull String artifactFilename() {
        return artifactFilename;
    }

    /**
     * @return the extracted metadata result, if available
     */
    public @NotNull Optional<MetadataResult> metadataResult() {
        return Optional.ofNullable(metadataResult);
    }

    /**
     * Sets the extracted metadata result.
     *
     * @param result the metadata result
     */
    public void setMetadataResult(@NotNull MetadataResult result) {
        this.metadataResult = result;
    }
}
