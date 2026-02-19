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

package io.spicelabs.annatto.filter;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.handler.BaseArtifactHandler;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandler;
import io.spicelabs.rodeocomponents.APIS.artifacts.Pair;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoArtifact;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoItemMarker;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessFilter;
import io.spicelabs.rodeocomponents.APIS.artifacts.RodeoProcessItems;
import io.spicelabs.rodeocomponents.APIS.artifacts.Triple;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Process filter that claims artifacts belonging to Annatto's supported ecosystems
 * based on filename extension and MIME type. This filter is stateless and thread-safe.
 * (tested by {@code AnnattoProcessFilterTest} — 20+ extension routing tests)
 *
 * <p>For each claimed artifact, the filter creates a {@link RodeoProcessItems} that
 * provides the correct ecosystem-specific {@link ArtifactHandler}.
 * (tested by {@code AnnattoProcessFilterTest.getName_returnsAnnatto})</p>
 */
public final class AnnattoProcessFilter implements RodeoProcessFilter {

    private static final String FILTER_NAME = "Annatto";

    private final Map<EcosystemId, BaseArtifactHandler> handlers;

    /**
     * Extension-to-ecosystem mapping for file-based detection.
     */
    private static final Map<String, EcosystemId> EXTENSION_MAP = Map.ofEntries(
            // npm: .tgz files that are npm packages
            Map.entry(".tgz", EcosystemId.NPM),
            // Crates: .crate files
            Map.entry(".crate", EcosystemId.CRATES),
            // RubyGems: .gem files
            Map.entry(".gem", EcosystemId.RUBYGEMS),
            // Conda: .conda files (modern format)
            Map.entry(".conda", EcosystemId.CONDA),
            // LuaRocks: .rock and .rockspec files
            Map.entry(".rock", EcosystemId.LUAROCKS),
            Map.entry(".rockspec", EcosystemId.LUAROCKS),
            // CocoaPods: .podspec and .podspec.json files
            Map.entry(".podspec", EcosystemId.COCOAPODS),
            // PyPI: .whl files
            Map.entry(".whl", EcosystemId.PYPI)
    );

    /**
     * Creates a new process filter with the given ecosystem handlers.
     *
     * @param handlers map of ecosystem ID to handler implementation
     */
    public AnnattoProcessFilter(@NotNull Map<EcosystemId, BaseArtifactHandler> handlers) {
        this.handlers = Map.copyOf(handlers);
    }

    /**
     * @return the filter name
     */
    @Override
    public @NotNull String getName() {
        return FILTER_NAME;
    }

    /**
     * Filters artifacts by filename extension and MIME type, claiming those
     * that belong to supported ecosystems.
     *
     * @param byName map of artifact name to list of artifacts with that name
     * @return list of claimed artifacts with their handlers
     */
    @Override
    public @NotNull List<Triple<String, RodeoArtifact, RodeoProcessItems>> filterByName(
            @NotNull Map<String, List<RodeoArtifact>> byName) {
        var result = new ArrayList<Triple<String, RodeoArtifact, RodeoProcessItems>>();

        for (var entry : byName.entrySet()) {
            String name = entry.getKey();
            List<RodeoArtifact> artifacts = entry.getValue();

            Optional<EcosystemId> ecosystem = detectEcosystem(name);
            if (ecosystem.isEmpty()) {
                continue;
            }

            EcosystemId ecoId = ecosystem.get();
            BaseArtifactHandler handler = handlers.get(ecoId);
            if (handler == null) {
                continue;
            }

            for (RodeoArtifact artifact : artifacts) {
                RodeoProcessItems processItems = createProcessItems(artifact, handler);
                result.add(new Triple<>(name, artifact, processItems));
            }
        }

        return List.copyOf(result);
    }

    /**
     * Detects which ecosystem a file belongs to based on its name.
     *
     * @param filename the artifact filename
     * @return the detected ecosystem, or empty if not recognized
     */
    @NotNull Optional<EcosystemId> detectEcosystem(@NotNull String filename) {
        String lower = filename.toLowerCase();

        // Check compound extensions first
        if (lower.endsWith(".podspec.json")) {
            return Optional.of(EcosystemId.COCOAPODS);
        }
        if (lower.endsWith(".tar.bz2")) {
            return Optional.of(EcosystemId.CONDA);
        }
        // PyPI sdist and Go module and CPAN: .tar.gz
        // These need additional heuristics - for now detect by pattern
        if (lower.endsWith(".tar.gz")) {
            return detectTarGzEcosystem(filename);
        }
        if (lower.endsWith(".zip")) {
            return detectZipEcosystem(filename);
        }
        // Hex: plain .tar (must check after .tar.gz and .tar.bz2)
        if (lower.endsWith(".tar")) {
            return Optional.of(EcosystemId.HEX);
        }

        // Simple extension match
        for (var ext : EXTENSION_MAP.entrySet()) {
            if (lower.endsWith(ext.getKey())) {
                return Optional.of(ext.getValue());
            }
        }

        return Optional.empty();
    }

    /**
     * Heuristic detection for .tar.gz files which could be PyPI sdist or CPAN.
     * Go modules use .zip format, not .tar.gz.
     *
     * <p>Heuristic: strip {@code .tar.gz}, find last hyphen-before-digit (version separator).
     * If the name portion contains any uppercase letter, route to CPAN (e.g., {@code Moose-2.2207}).
     * Otherwise route to PyPI (e.g., {@code requests-2.31.0}).
     * Known limitation: all-lowercase CPAN names (e.g., {@code namespace-clean}) route to PyPI.</p>
     */
    private @NotNull Optional<EcosystemId> detectTarGzEcosystem(@NotNull String filename) {
        String base = filename;
        if (base.toLowerCase().endsWith(".tar.gz")) {
            base = base.substring(0, base.length() - ".tar.gz".length());
        }

        // Find the last hyphen that is followed by a digit (version separator)
        String namePortion = base;
        for (int i = base.length() - 1; i >= 0; i--) {
            if (base.charAt(i) == '-' && i + 1 < base.length() && Character.isDigit(base.charAt(i + 1))) {
                namePortion = base.substring(0, i);
                break;
            }
        }

        // If name portion contains uppercase, it's CPAN (Perl naming convention)
        for (int i = 0; i < namePortion.length(); i++) {
            if (Character.isUpperCase(namePortion.charAt(i))) {
                return Optional.of(EcosystemId.CPAN);
            }
        }

        return Optional.of(EcosystemId.PYPI);
    }

    /**
     * Heuristic detection for .zip files which could be Go module, Packagist, or Hex.
     */
    private @NotNull Optional<EcosystemId> detectZipEcosystem(@NotNull String filename) {
        // Go module zips contain @v in the filename (e.g., module@v1.2.3.zip)
        if (filename.contains("@v")) {
            return Optional.of(EcosystemId.GO);
        }
        return Optional.of(EcosystemId.PACKAGIST);
    }

    private @NotNull RodeoProcessItems createProcessItems(@NotNull RodeoArtifact artifact,
            @NotNull ArtifactHandler handler) {
        AnnattoItemMarker marker = new AnnattoItemMarker();
        return new RodeoProcessItems() {
            @Override
            public void onCompletion(@NotNull RodeoArtifact completedArtifact) {
                // No-op
            }

            @Override
            public int length() {
                return 1;
            }

            @Override
            public @NotNull Pair<List<Pair<RodeoArtifact, RodeoItemMarker>>, ArtifactHandler> getItemsToProcess() {
                List<Pair<RodeoArtifact, RodeoItemMarker>> items = List.of(new Pair<>(artifact, marker));
                return new Pair<>(items, handler);
            }
        };
    }

    /**
     * Marker implementation for Annatto-processed items.
     */
    private static final class AnnattoItemMarker implements RodeoItemMarker {
    }
}
