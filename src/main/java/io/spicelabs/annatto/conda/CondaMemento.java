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
package io.spicelabs.annatto.conda;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Memento capturing extracted state from a Conda artifact.
 * Stores raw JSON from {@code info/index.json} and {@code info/about.json},
 * plus build and subdir fields needed for PURL qualifier construction.
 *
 * <p>Created during {@link CondaHandler#doBegin} and not shared across threads.</p>
 */
public final class CondaMemento extends BaseMemento {

    private final String rawIndexJson;
    private final String rawAboutJson;
    private final String build;
    private final String subdir;

    /**
     * Creates a memento for a successfully extracted Conda artifact.
     *
     * @param artifactFilename the filename of the artifact being processed
     * @param rawIndexJson     the raw content of info/index.json
     * @param rawAboutJson     the raw content of info/about.json (may be null if absent)
     * @param build            the build string from index.json (may be null)
     * @param subdir           the subdir/platform from index.json (may be null)
     */
    public CondaMemento(@NotNull String artifactFilename, @NotNull String rawIndexJson,
            @Nullable String rawAboutJson, @Nullable String build, @Nullable String subdir) {
        super(EcosystemId.CONDA, artifactFilename);
        this.rawIndexJson = rawIndexJson;
        this.rawAboutJson = rawAboutJson;
        this.build = build;
        this.subdir = subdir;
    }

    /**
     * Creates an empty memento for error cases.
     *
     * @param artifactFilename the filename of the artifact being processed
     */
    public CondaMemento(@NotNull String artifactFilename) {
        super(EcosystemId.CONDA, artifactFilename);
        this.rawIndexJson = "";
        this.rawAboutJson = null;
        this.build = null;
        this.subdir = null;
    }

    /** @return the raw index.json content */
    public @NotNull String rawIndexJson() {
        return rawIndexJson;
    }

    /** @return the raw about.json content, or null if absent */
    public @Nullable String rawAboutJson() {
        return rawAboutJson;
    }

    /** @return the build string, if present */
    public @NotNull Optional<String> build() {
        return Optional.ofNullable(build).filter(s -> !s.isEmpty());
    }

    /** @return the subdir/platform, if present */
    public @NotNull Optional<String> subdir() {
        return Optional.ofNullable(subdir).filter(s -> !s.isEmpty());
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
