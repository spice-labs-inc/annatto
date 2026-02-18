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
import io.spicelabs.annatto.handler.BaseArtifactHandler;
import io.spicelabs.annatto.handler.BaseMemento;
import io.spicelabs.rodeocomponents.APIS.artifacts.*;
import io.spicelabs.rodeocomponents.APIS.purls.Purl;
import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.util.List;

public final class RubygemsHandler extends BaseArtifactHandler {

    public RubygemsHandler() {
        super(EcosystemId.RUBYGEMS);
    }

    @Override
    protected @NotNull BaseMemento doBegin(
            @NotNull InputStream stream,
            @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item,
            @NotNull RodeoItemMarker marker) {
        throw new UnsupportedOperationException("Not yet implemented: RubygemsHandler.doBegin");
    }

    @Override
    protected @NotNull List<Purl> doBuildPurls(@NotNull BaseMemento memento) {
        throw new UnsupportedOperationException("Not yet implemented: RubygemsHandler.doBuildPurls");
    }
}
