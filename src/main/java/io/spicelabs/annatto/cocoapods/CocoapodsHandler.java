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
package io.spicelabs.annatto.cocoapods;

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
 * Handler for CocoaPods {@code .podspec.json} files.
 *
 * <p>Lifecycle: begin reads the JSON stream and extracts metadata;
 * getPurls builds a {@code pkg:cocoapods/Name@version} PURL.</p>
 *
 * <p>Covers {@link CocoapodsQuirks} Q1 (JSON only), Q7 (case-sensitive name).</p>
 */
public final class CocoapodsHandler extends BaseArtifactHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CocoapodsHandler.class);

    public CocoapodsHandler() {
        super(EcosystemId.COCOAPODS);
    }

    @Override
    protected @NotNull BaseMemento doBegin(
            @NotNull InputStream stream,
            @NotNull RodeoArtifact artifact,
            @NotNull WorkItem item,
            @NotNull RodeoItemMarker marker) {
        String filename = artifact.getFilenameWithNoPath();
        try {
            String rawJson = CocoapodsMetadataExtractor.readJson(stream, filename);
            MetadataResult result = CocoapodsMetadataExtractor.buildMetadataResult(rawJson);
            CocoapodsMemento memento = new CocoapodsMemento(filename, rawJson);
            memento.setMetadataResult(result);
            return memento;
        } catch (Exception e) {
            LOG.warn("Failed to extract CocoaPods metadata from {}: {}", filename, e.getMessage());
            return new CocoapodsMemento(filename);
        }
    }

    @Override
    protected @NotNull List<Purl> doBuildPurls(@NotNull BaseMemento memento) {
        if (!(memento instanceof CocoapodsMemento cocoapodsMemento)) {
            return List.of();
        }
        Optional<String> name = cocoapodsMemento.packageName();
        Optional<String> version = cocoapodsMemento.packageVersion();
        if (name.isEmpty() || version.isEmpty()) {
            return List.of();
        }
        try {
            // Name preserves case (Q7)
            PackageURL purl = PurlBuilder.forCocoapods(name.get(), version.get());
            return List.of(new CocoapodsPurl(purl));
        } catch (MalformedPackageURLException e) {
            LOG.warn("Failed to build CocoaPods PURL for {}@{}: {}", name.get(), version.get(), e.getMessage());
            return List.of();
        }
    }

    /** CocoaPods PURL implementation. */
    private record CocoapodsPurl(PackageURL purl) implements Purl {
        @Override
        public String toString() {
            return purl.canonicalize();
        }
    }
}
