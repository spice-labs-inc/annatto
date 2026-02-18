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

package io.spicelabs.annatto;

import io.spicelabs.annatto.common.EcosystemId;
import io.spicelabs.annatto.filter.AnnattoProcessFilter;
import io.spicelabs.annatto.crates.CratesHandler;
import io.spicelabs.annatto.go.GoHandler;
import io.spicelabs.annatto.handler.BaseArtifactHandler;
import io.spicelabs.annatto.npm.NpmHandler;
import io.spicelabs.annatto.pypi.PypiHandler;
import io.spicelabs.annatto.rubygems.RubygemsHandler;
import io.spicelabs.rodeocomponents.APIFactoryReceiver;
import io.spicelabs.rodeocomponents.APIFactorySource;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactConstants;
import io.spicelabs.rodeocomponents.APIS.artifacts.ArtifactHandlerRegistrar;
import io.spicelabs.rodeocomponents.RodeoComponent;
import io.spicelabs.rodeocomponents.RodeoEnvironment;
import io.spicelabs.rodeocomponents.RodeoIdentity;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The main Annatto component that integrates with the Goat Rodeo plugin system.
 * Registers ecosystem-specific artifact handlers for 11 package ecosystems.
 *
 * <p>This class is thread-safe. Mutable fields are managed via {@link AtomicReference}
 * and are only set during the well-ordered lifecycle methods.</p>
 *
 * <p>Discovered via {@link java.util.ServiceLoader} from
 * {@code META-INF/services/io.spicelabs.rodeocomponents.RodeoComponent}.</p>
 */
public final class AnnattoComponent implements RodeoComponent {

    private static final Logger logger = LoggerFactory.getLogger(AnnattoComponent.class);

    private final AnnattoIdentity identity = new AnnattoIdentity();
    private final AtomicReference<ArtifactHandlerRegistrar> registrarRef = new AtomicReference<>();

    /**
     * Validates the runtime environment.
     *
     * @throws Exception if the environment is not suitable
     */
    @Override
    public void initialize() throws Exception {
        logger.info("Annatto component initializing");
    }

    /**
     * @return the identity of this component
     */
    @Override
    public @NotNull RodeoIdentity getIdentity() {
        return identity;
    }

    /**
     * @return the version of the component API this component uses
     */
    @Override
    public @NotNull Runtime.Version getComponentVersion() {
        return RodeoEnvironment.currentVersion();
    }

    /**
     * Exports API factories. Annatto does not currently export any APIs.
     *
     * @param receiver the factory receiver
     */
    @Override
    public void exportAPIFactories(@NotNull APIFactoryReceiver receiver) {
        // Annatto does not export APIs; it only consumes the ArtifactHandlerRegistrar
    }

    /**
     * Imports the {@link ArtifactHandlerRegistrar} API and registers the
     * {@link AnnattoProcessFilter} with all ecosystem handlers.
     *
     * @param source the API factory source
     */
    @Override
    public void importAPIFactories(@NotNull APIFactorySource source) {
        var factoryOpt = source.getAPIFactory(
                ArtifactConstants.NAME, this, ArtifactHandlerRegistrar.class);

        if (factoryOpt.isEmpty()) {
            logger.warn("ArtifactHandlerRegistrar not available; Annatto handlers will not be registered");
            return;
        }

        ArtifactHandlerRegistrar registrar = factoryOpt.get().buildAPI(this);
        registrarRef.set(registrar);

        // Build handler map with implemented ecosystem handlers
        Map<EcosystemId, BaseArtifactHandler> handlers = Map.of(
                EcosystemId.NPM, new NpmHandler(),
                EcosystemId.PYPI, new PypiHandler(),
                EcosystemId.GO, new GoHandler(),
                EcosystemId.CRATES, new CratesHandler(),
                EcosystemId.RUBYGEMS, new RubygemsHandler()
        );

        AnnattoProcessFilter filter = new AnnattoProcessFilter(handlers);
        registrar.registerProcessFilter(filter);

        logger.info("Annatto process filter registered with {} ecosystem handlers", handlers.size());
    }

    /**
     * Final validation after all imports are complete.
     */
    @Override
    public void onLoadingComplete() {
        logger.info("Annatto component loading complete");
    }

    /**
     * Releases the acquired {@link ArtifactHandlerRegistrar}.
     */
    @Override
    public void shutDown() {
        ArtifactHandlerRegistrar registrar = registrarRef.getAndSet(null);
        if (registrar != null) {
            registrar.release();
        }
        logger.info("Annatto component shut down");
    }
}
