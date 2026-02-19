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
package io.spicelabs.annatto.luarocks;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * Memento capturing extracted state from a LuaRocks artifact.
 * Stores the raw rockspec text for potential re-evaluation.
 *
 * <p>Created during {@link LuarocksHandler#doBegin} and not shared across threads.</p>
 */
public final class LuarocksMemento extends BaseMemento {

    private final String rawRockspec;

    /**
     * Creates a memento for a successfully extracted LuaRocks artifact.
     *
     * @param artifactFilename the filename of the artifact being processed
     * @param rawRockspec      the raw rockspec Lua text
     */
    public LuarocksMemento(@NotNull String artifactFilename, @NotNull String rawRockspec) {
        super(EcosystemId.LUAROCKS, artifactFilename);
        this.rawRockspec = rawRockspec;
    }

    /**
     * Creates an empty memento for error cases.
     *
     * @param artifactFilename the filename of the artifact being processed
     */
    public LuarocksMemento(@NotNull String artifactFilename) {
        super(EcosystemId.LUAROCKS, artifactFilename);
        this.rawRockspec = "";
    }

    /** @return the raw rockspec text */
    public @NotNull String rawRockspec() {
        return rawRockspec;
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
