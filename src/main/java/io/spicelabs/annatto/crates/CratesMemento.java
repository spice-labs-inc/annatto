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
package io.spicelabs.annatto.crates;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Memento holding Crates.io-specific processing state extracted from Cargo.toml.
 * Created during {@link CratesHandler#doBegin} and not shared across threads.
 */
public final class CratesMemento extends BaseMemento {

    private final String rawCargoToml;

    /**
     * @param artifactFilename the filename of the crate artifact being processed
     * @param rawCargoToml     the raw Cargo.toml file content
     */
    public CratesMemento(@NotNull String artifactFilename, @NotNull String rawCargoToml) {
        super(EcosystemId.CRATES, artifactFilename);
        this.rawCargoToml = rawCargoToml;
    }

    /**
     * @param artifactFilename the filename of the crate artifact being processed
     */
    public CratesMemento(@NotNull String artifactFilename) {
        super(EcosystemId.CRATES, artifactFilename);
        this.rawCargoToml = "";
    }

    /**
     * @return the raw Cargo.toml text content
     */
    public @NotNull String rawCargoToml() {
        return rawCargoToml;
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
