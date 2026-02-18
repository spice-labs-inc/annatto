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

package io.spicelabs.annatto.npm;

import com.google.gson.JsonObject;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseMemento;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Memento holding npm-specific processing state extracted from package.json.
 * Created during {@link NpmHandler#doBegin} and not shared across threads.
 */
public final class NpmMemento extends BaseMemento {

    private final JsonObject rawPackageJson;

    /**
     * @param artifactFilename the filename of the npm .tgz being processed
     * @param rawPackageJson   the raw parsed package.json content
     */
    public NpmMemento(@NotNull String artifactFilename, @NotNull JsonObject rawPackageJson) {
        super(EcosystemId.NPM, artifactFilename);
        this.rawPackageJson = rawPackageJson;
    }

    /**
     * @return the raw package.json content for ecosystem-specific queries
     */
    public @NotNull JsonObject rawPackageJson() {
        return rawPackageJson;
    }

    /**
     * @return the fully qualified npm package name, if present
     */
    public @NotNull Optional<String> packageName() {
        return metadataResult().flatMap(r -> r.name());
    }

    /**
     * @return the npm package version, if present
     */
    public @NotNull Optional<String> packageVersion() {
        return metadataResult().flatMap(r -> r.version());
    }
}
