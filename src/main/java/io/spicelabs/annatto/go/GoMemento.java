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
package io.spicelabs.annatto.go;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Memento holding Go module-specific processing state extracted from go.mod.
 * Created during {@link GoHandler#doBegin} and not shared across threads.
 */
public final class GoMemento extends BaseMemento {

    private final String rawGoMod;

    /**
     * @param artifactFilename the filename of the Go module artifact being processed
     * @param rawGoMod         the raw go.mod file content
     */
    public GoMemento(@NotNull String artifactFilename, @NotNull String rawGoMod) {
        super(EcosystemId.GO, artifactFilename);
        this.rawGoMod = rawGoMod;
    }

    /**
     * @param artifactFilename the filename of the Go module artifact being processed
     */
    public GoMemento(@NotNull String artifactFilename) {
        super(EcosystemId.GO, artifactFilename);
        this.rawGoMod = "";
    }

    /**
     * @return the raw go.mod text content
     */
    public @NotNull String rawGoMod() {
        return rawGoMod;
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
