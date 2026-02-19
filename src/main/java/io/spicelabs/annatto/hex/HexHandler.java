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
package io.spicelabs.annatto.hex;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
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
 * Handler for Hex {@code .tar} packages.
 *
 * <p>Lifecycle: begin reads the tar stream and extracts metadata.config;
 * getPurls builds a {@code pkg:hex/name@version} PURL (name lowercased, Q6).</p>
 */
public final class HexHandler extends BaseArtifactHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HexHandler.class);

    public HexHandler() {
        super(EcosystemId.HEX);
    }

    @Override
    protected @NotNull BaseMemento doBegin(
            @NotNull InputStream stream,
            @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item,
            @NotNull RodeoItemMarker marker) {
        String filename = artifact.getFilenameWithNoPath();
        try {
            String rawConfig = HexMetadataExtractor.extractMetadataConfig(stream, filename);
            MetadataResult result = HexMetadataExtractor.buildMetadataResult(rawConfig);
            return new HexMemento(filename, rawConfig, result);
        } catch (Exception e) {
            LOG.warn("Failed to extract Hex metadata from {}: {}", filename, e.getMessage());
            return new HexMemento(filename);
        }
    }

    @Override
    protected @NotNull List<Purl> doBuildPurls(@NotNull BaseMemento memento) {
        if (!(memento instanceof HexMemento hexMemento)) {
            return List.of();
        }
        Optional<String> name = hexMemento.packageName();
        Optional<String> version = hexMemento.packageVersion();
        if (name.isEmpty() || version.isEmpty()) {
            return List.of();
        }
        try {
            // Name is lowercased per purl-spec (Q6)
            PackageURL purl = PurlBuilder.forHex(name.get().toLowerCase(), version.get());
            return List.of(new HexPurl(purl));
        } catch (MalformedPackageURLException e) {
            LOG.warn("Failed to build Hex PURL for {}@{}: {}",
                    name.get(), version.get(), e.getMessage());
            return List.of();
        }
    }

    /** Hex PURL implementation. */
    private record HexPurl(PackageURL purl) implements Purl {
        @Override
        public String toString() {
            return purl.canonicalize();
        }
    }
}
