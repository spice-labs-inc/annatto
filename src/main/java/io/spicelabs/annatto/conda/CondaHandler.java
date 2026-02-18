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
package io.spicelabs.annatto.conda;

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
 * Artifact handler for Conda packages ({@code .conda} and {@code .tar.bz2} formats).
 *
 * <p>This handler is stateless and thread-safe. All per-artifact state is held
 * in the {@link CondaMemento} created during {@link #doBegin}.</p>
 */
public final class CondaHandler extends BaseArtifactHandler {

    private static final Logger logger = LoggerFactory.getLogger(CondaHandler.class);

    public CondaHandler() {
        super(EcosystemId.CONDA);
    }

    /**
     * Extracts metadata from the Conda archive, parses info/index.json and info/about.json,
     * and creates a {@link CondaMemento} populated with the extracted metadata.
     *
     * @param stream   the artifact input stream
     * @param artifact the artifact being processed
     * @param item     the work item
     * @param marker   the processing marker
     * @return a CondaMemento containing the extracted state
     */
    @Override
    protected @NotNull BaseMemento doBegin(@NotNull InputStream stream, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker) {
        String filename = artifact.getFilenameWithNoPath();
        try {
            CondaMetadataExtractor.CondaArchiveData archiveData =
                    CondaMetadataExtractor.extractFromArchive(stream, filename);
            MetadataResult result = CondaMetadataExtractor.buildMetadataResult(archiveData);

            String build = CondaMetadataExtractor.extractBuild(archiveData).orElse(null);
            String subdir = CondaMetadataExtractor.extractSubdir(archiveData).orElse(null);

            CondaMemento memento = new CondaMemento(
                    filename, archiveData.indexJson(), archiveData.aboutJson(), build, subdir);
            memento.setMetadataResult(result);
            return memento;
        } catch (MalformedPackageException | MetadataExtractionException e) {
            logger.warn("Failed to extract Conda metadata from {}: {}", filename, e.getMessage());
            return new CondaMemento(filename);
        }
    }

    /**
     * Builds Package URLs for the Conda package using name, version, build, and subdir.
     * Per purl-spec, Conda PURLs have no namespace; build and subdir are qualifiers.
     *
     * @param memento the base memento containing extracted metadata
     * @return a list containing the conda PURL, or empty if name/version are absent
     */
    @Override
    protected @NotNull List<Purl> doBuildPurls(@NotNull BaseMemento memento) {
        if (!(memento instanceof CondaMemento condaMemento)) {
            return List.of();
        }

        return condaMemento.metadataResult()
                .flatMap(result -> {
                    if (result.name().isEmpty() || result.version().isEmpty()) {
                        return java.util.Optional.empty();
                    }
                    try {
                        PackageURL purl = PurlBuilder.forConda(
                                result.name().get(),
                                result.version().get(),
                                condaMemento.build(),
                                condaMemento.subdir());
                        return java.util.Optional.of(new CondaPurl(purl));
                    } catch (MalformedPackageURLException e) {
                        logger.warn("Failed to build Conda PURL: {}", e.getMessage());
                        return java.util.Optional.empty();
                    }
                })
                .map(purl -> List.<Purl>of(purl))
                .orElse(List.of());
    }

    /**
     * Wraps a {@link PackageURL} to implement the rodeo-components {@link Purl} interface.
     */
    static final class CondaPurl implements Purl {
        private final PackageURL packageURL;

        CondaPurl(@NotNull PackageURL packageURL) {
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
