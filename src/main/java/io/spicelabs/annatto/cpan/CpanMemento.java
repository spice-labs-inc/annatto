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
package io.spicelabs.annatto.cpan;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Memento capturing extracted state from a CPAN distribution tarball.
 * Stores the raw META.json or META.yml text for diagnostics.
 *
 * <p>Created during {@link CpanHandler#doBegin} and not shared across threads.</p>
 */
public final class CpanMemento extends BaseMemento {

    private final String rawMetaText;

    /**
     * Creates a memento for a successfully extracted CPAN distribution.
     *
     * @param artifactFilename the filename of the artifact being processed
     * @param rawMetaText      the raw META.json or META.yml content
     */
    public CpanMemento(@NotNull String artifactFilename, @NotNull String rawMetaText) {
        super(EcosystemId.CPAN, artifactFilename);
        this.rawMetaText = rawMetaText;
    }

    /**
     * Creates an empty memento for error cases.
     *
     * @param artifactFilename the filename of the artifact being processed
     */
    public CpanMemento(@NotNull String artifactFilename) {
        super(EcosystemId.CPAN, artifactFilename);
        this.rawMetaText = "";
    }

    /** @return the raw META.json or META.yml content */
    public @NotNull String rawMetaText() {
        return rawMetaText;
    }

    /** @return the package name, if present */
    public @NotNull Optional<String> packageName() {
        return metadataResult().flatMap(r -> r.name());
    }

    /** @return the package version, if present */
    public @NotNull Optional<String> packageVersion() {
        return metadataResult().flatMap(r -> r.version());
    }
}
