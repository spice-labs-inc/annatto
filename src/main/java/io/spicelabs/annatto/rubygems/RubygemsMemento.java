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
package io.spicelabs.annatto.rubygems;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Memento holding RubyGems-specific processing state extracted from metadata.gz.
 * Created during {@link RubygemsHandler#doBegin} and not shared across threads.
 */
public final class RubygemsMemento extends BaseMemento {

    private final String rawYaml;

    /**
     * @param artifactFilename the filename of the gem artifact being processed
     * @param rawYaml          the raw YAML text from metadata.gz
     */
    public RubygemsMemento(@NotNull String artifactFilename, @NotNull String rawYaml) {
        super(EcosystemId.RUBYGEMS, artifactFilename);
        this.rawYaml = rawYaml;
    }

    /**
     * @param artifactFilename the filename of the gem artifact being processed
     */
    public RubygemsMemento(@NotNull String artifactFilename) {
        super(EcosystemId.RUBYGEMS, artifactFilename);
        this.rawYaml = "";
    }

    /**
     * @return the raw YAML text from metadata.gz
     */
    public @NotNull String rawYaml() {
        return rawYaml;
    }

    /**
     * @return the package name, if present
     */
    public @NotNull Optional<String> packageName() {
        return metadataResult().flatMap(r -> r.name());
    }

    /**
     * @return the package version, if present
     */
    public @NotNull Optional<String> packageVersion() {
        return metadataResult().flatMap(r -> r.version());
    }
}
