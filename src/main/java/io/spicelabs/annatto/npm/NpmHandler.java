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

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import io.spicelabs.annatto.AnnattoException.MalformedPackageException;
import io.spicelabs.annatto.AnnattoException.MetadataExtractionException;
import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.common.MetadataResult;
import io.spicelabs.annatto.common.PurlBuilder;
import io.spicelabs.annatto.handler.BaseArtifactHandler;
import io.spicelabs.annatto.handler.BaseMemento;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoItemMarker;
import io.spicelabs.rodeocomponents.APIS.artifacts.WorkItem;
import io.spicelabs.rodeocomponents.APIS.purls.Purl;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.List;

/**
 * Artifact handler for npm packages (.tgz containing package/package.json).
 *
 * <p>This handler is stateless and thread-safe. All per-artifact state is held
 * in the {@link NpmMemento} created during {@link #doBegin}.</p>
 */
public final class NpmHandler extends BaseArtifactHandler {

    private static final Logger logger = LoggerFactory.getLogger(NpmHandler.class);

    public NpmHandler() {
        super(EcosystemId.NPM);
    }

    /**
     * Parses the .tgz archive, extracts package.json, and creates an {@link NpmMemento}
     * populated with the extracted metadata.
     *
     * @param stream   the .tgz input stream
     * @param artifact the artifact being processed
     * @param item     the work item
     * @param marker   the processing marker
     * @return an NpmMemento containing the extracted state
     */
    @Override
    protected @NotNull BaseMemento doBegin(@NotNull InputStream stream, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker) {
        String filename = artifact.getFilenameWithNoPath();
        try {
            var packageJson = NpmMetadataExtractor.extractPackageJson(stream, filename);
            MetadataResult result = NpmMetadataExtractor.parsePackageJson(packageJson);

            NpmMemento memento = new NpmMemento(filename, packageJson);
            memento.setMetadataResult(result);
            return memento;
        } catch (MalformedPackageException | MetadataExtractionException e) {
            logger.warn("Failed to extract npm metadata from {}: {}", filename, e.getMessage());
            // Return a memento with no metadata; getMetadata/getPurls will return empty lists
            return new NpmMemento(filename, new com.google.gson.JsonObject());
        }
    }

    /**
     * Builds Package URLs for the npm package using the extracted name and version.
     *
     * @param memento the base memento containing extracted metadata
     * @return a list containing the npm PURL, or empty if name/version are absent
     */
    @Override
    protected @NotNull List<Purl> doBuildPurls(@NotNull BaseMemento memento) {
        return memento.metadataResult()
                .flatMap(result -> {
                    if (result.name().isEmpty() || result.version().isEmpty()) {
                        return java.util.Optional.empty();
                    }
                    try {
                        PackageURL purl = PurlBuilder.forNpm(result.name().get(), result.version().get());
                        return java.util.Optional.of(new NpmPurl(purl));
                    } catch (MalformedPackageURLException e) {
                        logger.warn("Failed to build npm PURL: {}", e.getMessage());
                        return java.util.Optional.empty();
                    }
                })
                .map(purl -> List.<Purl>of(purl))
                .orElse(List.of());
    }

    /**
     * Wraps a {@link PackageURL} to implement the rodeo-components {@link Purl} interface.
     */
    static final class NpmPurl implements Purl {
        private final PackageURL packageURL;

        NpmPurl(@NotNull PackageURL packageURL) {
            this.packageURL = packageURL;
        }

        @NotNull PackageURL packageURL() {
            return packageURL;
        }

        @Override
        public String toString() {
            return packageURL.canonicalize();
        }
    }
}
