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
package io.spicelabs.annatto.pypi;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Memento holding PyPI-specific processing state extracted from METADATA/PKG-INFO.
 * Created during {@link PypiHandler#doBegin} and not shared across threads.
 */
public final class PypiMemento extends BaseMemento {

    private final Map<String, List<String>> rawHeaders;

    /**
     * @param artifactFilename the filename of the PyPI artifact being processed
     * @param rawHeaders       the raw RFC 822 headers from the metadata file
     */
    public PypiMemento(@NotNull String artifactFilename, @NotNull Map<String, List<String>> rawHeaders) {
        super(EcosystemId.PYPI, artifactFilename);
        this.rawHeaders = Map.copyOf(rawHeaders);
    }

    /**
     * @param artifactFilename the filename of the PyPI artifact being processed
     */
    public PypiMemento(@NotNull String artifactFilename) {
        super(EcosystemId.PYPI, artifactFilename);
        this.rawHeaders = Map.of();
    }

    /**
     * @return the raw RFC 822 headers for ecosystem-specific queries
     */
    public @NotNull Map<String, List<String>> rawHeaders() {
        return rawHeaders;
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
