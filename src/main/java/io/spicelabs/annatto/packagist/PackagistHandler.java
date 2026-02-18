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

package io.spicelabs.annatto.packagist;

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
 * Artifact handler for Packagist (Composer/PHP) packages (.zip containing composer.json).
 *
 * <p>This handler is stateless and thread-safe. All per-artifact state is held
 * in the {@link PackagistMemento} created during {@link #doBegin}.</p>
 */
public final class PackagistHandler extends BaseArtifactHandler {

    private static final Logger logger = LoggerFactory.getLogger(PackagistHandler.class);

    public PackagistHandler() {
        super(EcosystemId.PACKAGIST);
    }

    /**
     * Extracts the composer.json from the .zip archive, parses metadata, and creates
     * a {@link PackagistMemento} populated with the extracted metadata.
     *
     * @param stream   the artifact input stream
     * @param artifact the artifact being processed
     * @param item     the work item
     * @param marker   the processing marker
     * @return a PackagistMemento containing the extracted state
     */
    @Override
    protected @NotNull BaseMemento doBegin(@NotNull InputStream stream, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker) {
        String filename = artifact.getFilenameWithNoPath();
        try {
            PackagistMetadataExtractor.ComposerJsonData composerJsonData =
                    PackagistMetadataExtractor.extractComposerJsonFromZip(stream, filename);
            MetadataResult result = PackagistMetadataExtractor.buildMetadataResult(composerJsonData);

            PackagistMemento memento = new PackagistMemento(filename, composerJsonData.rawJson());
            memento.setMetadataResult(result);
            return memento;
        } catch (MalformedPackageException | MetadataExtractionException e) {
            logger.warn("Failed to extract Packagist metadata from {}: {}", filename, e.getMessage());
            return new PackagistMemento(filename);
        }
    }

    /**
     * Builds Package URLs for the Packagist package using the extracted name and version.
     * Returns empty list if version is absent (Q1 — version often missing from composer.json).
     *
     * @param memento the base memento containing extracted metadata
     * @return a list containing the composer PURL, or empty if name/version are absent
     */
    @Override
    protected @NotNull List<Purl> doBuildPurls(@NotNull BaseMemento memento) {
        return memento.metadataResult()
                .flatMap(result -> {
                    if (result.name().isEmpty() || result.version().isEmpty()) {
                        return java.util.Optional.empty();
                    }
                    try {
                        PackageURL purl = PurlBuilder.forPackagist(result.name().get(), result.version().get());
                        return java.util.Optional.of(new PackagistPurl(purl));
                    } catch (MalformedPackageURLException e) {
                        logger.warn("Failed to build Packagist PURL: {}", e.getMessage());
                        return java.util.Optional.empty();
                    }
                })
                .map(purl -> List.<Purl>of(purl))
                .orElse(List.of());
    }

    /**
     * Wraps a {@link PackageURL} to implement the rodeo-components {@link Purl} interface.
     */
    static final class PackagistPurl implements Purl {
        private final PackageURL packageURL;

        PackagistPurl(@NotNull PackageURL packageURL) {
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
