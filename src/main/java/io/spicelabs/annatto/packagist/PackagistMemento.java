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
package io.spicelabs.annatto.packagist;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Memento holding Packagist-specific processing state extracted from composer.json.
 * Created during {@link PackagistHandler#doBegin} and not shared across threads.
 */
public final class PackagistMemento extends BaseMemento {

    private final String rawJson;

    /**
     * @param artifactFilename the filename of the zip artifact being processed
     * @param rawJson          the raw composer.json file content
     */
    public PackagistMemento(@NotNull String artifactFilename, @NotNull String rawJson) {
        super(EcosystemId.PACKAGIST, artifactFilename);
        this.rawJson = rawJson;
    }

    /**
     * @param artifactFilename the filename of the zip artifact being processed
     */
    public PackagistMemento(@NotNull String artifactFilename) {
        super(EcosystemId.PACKAGIST, artifactFilename);
        this.rawJson = "";
    }

    /**
     * @return the raw composer.json text content
     */
    public @NotNull String rawJson() {
        return rawJson;
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
