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
package io.spicelabs.annatto.cpan;

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
import java.util.Optional;

/**
 * Artifact handler for CPAN distributions ({@code .tar.gz} containing META.json/yml).
 *
 * <p>This handler is stateless and thread-safe. All per-artifact state is held
 * in the {@link CpanMemento} created during {@link #doBegin}.</p>
 */
public final class CpanHandler extends BaseArtifactHandler {

    private static final Logger logger = LoggerFactory.getLogger(CpanHandler.class);

    public CpanHandler() {
        super(EcosystemId.CPAN);
    }

    @Override
    protected @NotNull BaseMemento doBegin(@NotNull InputStream stream, @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item, @NotNull RodeoItemMarker marker) {
        String filename = artifact.getFilenameWithNoPath();
        try {
            CpanMetadataExtractor.CpanArchiveData archiveData =
                    CpanMetadataExtractor.extractFromArchive(stream, filename);
            MetadataResult result = CpanMetadataExtractor.buildMetadataResult(archiveData);

            CpanMemento memento = new CpanMemento(filename, archiveData.rawText());
            memento.setMetadataResult(result);
            return memento;
        } catch (MalformedPackageException | MetadataExtractionException e) {
            logger.warn("Failed to extract CPAN metadata from {}: {}", filename, e.getMessage());
            return new CpanMemento(filename);
        }
    }

    /**
     * Builds Package URLs for the CPAN distribution.
     * Per purl-spec, CPAN PURLs have no namespace (PAUSE ID unavailable from tarball, Q3).
     */
    @Override
    protected @NotNull List<Purl> doBuildPurls(@NotNull BaseMemento memento) {
        if (!(memento instanceof CpanMemento cpanMemento)) {
            return List.of();
        }

        return cpanMemento.metadataResult()
                .flatMap(result -> {
                    if (result.name().isEmpty() || result.version().isEmpty()) {
                        return Optional.empty();
                    }
                    try {
                        PackageURL purl = PurlBuilder.forCpan(
                                result.name().get(),
                                result.version().get(),
                                Optional.empty()); // Q3: no PAUSE ID from tarball
                        return Optional.of(new CpanPurl(purl));
                    } catch (MalformedPackageURLException e) {
                        logger.warn("Failed to build CPAN PURL: {}", e.getMessage());
                        return Optional.empty();
                    }
                })
                .map(purl -> List.<Purl>of(purl))
                .orElse(List.of());
    }

    /**
     * Wraps a {@link PackageURL} to implement the rodeo-components {@link Purl} interface.
     */
    static final class CpanPurl implements Purl {
        private final PackageURL packageURL;

        CpanPurl(@NotNull PackageURL packageURL) {
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
