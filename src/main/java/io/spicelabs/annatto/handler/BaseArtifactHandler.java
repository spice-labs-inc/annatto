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

package io.spicelabs.annatto.handler;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataMapper;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandler;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactMemento;
import io.spicelabs.rodeocomponents.APIS.artifacts.BackendStorage;
import io.spicelabs.rodeocomponents.APIS.artifacts.Metadata;
import io.spicelabs.rodeocomponents.APIS.artifacts.ParentFrame;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoItemMarker;
import io.spicelabs.rodeocomponents.APIS.artifacts.WorkItem;
import io.spicelabs.rodeocomponents.APIS.purls.Purl;
import org.jetbrains.annotations.NotNull;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Abstract base class for all ecosystem-specific artifact handlers.
 * Provides default implementations for shared lifecycle methods
 * and delegates ecosystem-specific logic to subclasses.
 * (tested indirectly by all 11 ecosystem {@code *HandlerTest} classes)
 *
 * <p>Handlers are stateless; all per-artifact state is stored in the {@link BaseMemento}
 * created during {@link #begin}.
 * (tested by {@code *HandlerTest.begin_*} and {@code *HandlerTest.getPurls_*} across all ecosystems)</p>
 */
public abstract class BaseArtifactHandler implements ArtifactHandler {

    private final EcosystemId ecosystemId;

    /**
     * @param ecosystemId the ecosystem this handler processes
     */
    protected BaseArtifactHandler(@NotNull EcosystemId ecosystemId) {
        this.ecosystemId = ecosystemId;
    }

    /**
     * @return the ecosystem this handler is responsible for
     */
    public @NotNull EcosystemId ecosystemId() {
        return ecosystemId;
    }

    /**
     * Annatto handlers do not require file-based input; they work with InputStreams.
     *
     * @return {@code false}
     */
    @Override
    public boolean requiresFile() {
        return false;
    }

    /**
     * Begins processing an artifact from an InputStream.
     * Subclasses must implement {@link #doBegin(InputStream, RodeoArtifact, WorkItem, RodeoItemMarker)}
     * to perform ecosystem-specific parsing.
     *
     * @param stream   the artifact content stream
     * @param artifact the artifact being processed
     * @param item     the work item accumulating information
     * @param marker   the processing marker
     * @return a memento holding the processing state
     */
    @Override
    public @NotNull ArtifactMemento begin(@NotNull InputStream stream, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker) {
        return doBegin(stream, artifact, item, marker);
    }

    /**
     * Begins processing an artifact from a FileInputStream.
     * Delegates to the InputStream variant.
     *
     * @param stream   the artifact file stream
     * @param artifact the artifact being processed
     * @param item     the work item accumulating information
     * @param marker   the processing marker
     * @return a memento holding the processing state
     */
    @Override
    public @NotNull ArtifactMemento begin(@NotNull FileInputStream stream, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker) {
        return doBegin(stream, artifact, item, marker);
    }

    /**
     * Returns metadata extracted during {@link #begin}, mapped to rodeo-components {@link Metadata} format.
     *
     * @param memento  the processing memento
     * @param artifact the artifact being processed
     * @param item     the work item
     * @param marker   the processing marker
     * @return the list of metadata entries
     */
    @Override
    public @NotNull List<Metadata> getMetadata(@NotNull ArtifactMemento memento, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker) {
        if (memento instanceof BaseMemento base) {
            return base.metadataResult()
                    .map(MetadataMapper::toMetadataList)
                    .orElse(List.of());
        }
        return List.of();
    }

    /**
     * Returns PURLs generated from the extracted metadata.
     * Subclasses must implement {@link #doBuildPurls(BaseMemento)} to provide ecosystem-specific PURL construction.
     *
     * @param memento  the processing memento
     * @param artifact the artifact being processed
     * @param item     the work item
     * @param marker   the processing marker
     * @return the list of PURLs
     */
    @Override
    public @NotNull List<Purl> getPurls(@NotNull ArtifactMemento memento, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker) {
        if (memento instanceof BaseMemento base) {
            return doBuildPurls(base);
        }
        return List.of();
    }

    /**
     * Default augmentation does nothing. Subclasses may override.
     *
     * @param memento     the processing memento
     * @param artifact    the artifact being processed
     * @param item        the work item
     * @param parentFrame the parent frame context
     * @param store       the backend storage
     * @param marker      the processing marker
     * @return the work item, unchanged by default
     */
    @Override
    public @NotNull WorkItem augment(@NotNull ArtifactMemento memento, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull ParentFrame parentFrame,
            @NotNull BackendStorage store, @NotNull RodeoItemMarker marker) {
        return item;
    }

    /**
     * Default post-child processing does nothing. Subclasses may override.
     *
     * @param memento the processing memento
     * @param gitoids optional list of child gitoids
     * @param store   the backend storage
     * @param marker  the processing marker
     */
    @Override
    public void postChildProcessing(@NotNull ArtifactMemento memento,
            @NotNull Optional<List<String>> gitoids,
            @NotNull BackendStorage store, @NotNull RodeoItemMarker marker) {
        // No-op by default
    }

    /**
     * Default end does nothing. Subclasses may override for cleanup.
     *
     * @param memento the processing memento
     */
    @Override
    public void end(@NotNull ArtifactMemento memento) {
        // No-op by default
    }

    /**
     * Ecosystem-specific begin implementation.
     *
     * @param stream   the artifact content stream
     * @param artifact the artifact being processed
     * @param item     the work item
     * @param marker   the processing marker
     * @return a memento holding the processing state
     */
    protected abstract @NotNull BaseMemento doBegin(@NotNull InputStream stream, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker);

    /**
     * Ecosystem-specific PURL construction.
     *
     * @param memento the base memento containing extracted metadata
     * @return a list of PURLs for the artifact
     */
    protected abstract @NotNull List<Purl> doBuildPurls(@NotNull BaseMemento memento);
}
