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

package io.spicelabs.annatto.common;

import io.spicelabs.coordinates.Purl;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds {@link Purl} instances (from the coordinates library) for each supported
 * ecosystem. All methods are stateless pure functions that never throw: a package that
 * cannot produce a spec-conforming pURL is logged at WARN and yields
 * {@link Optional#empty()} so a single malformed package cannot abort Annatto's
 * metadata discovery.
 *
 * <p>Namespace policy: purl-spec makes the namespace required for {@code cpan},
 * {@code golang}, and {@code composer}. When a package file carries no namespace we
 * substitute {@link #UNKNOWN_NAMESPACE} rather than dropping the pURL entirely — the
 * identifier stays present and downstream consumers can recognize the sentinel.
 *
 * <p>(tested by {@code PurlBuilderTest} — method-level tests covering all 11 ecosystems
 * plus the malformed-input and sentinel-namespace paths)
 */
public final class PurlBuilder {

    private static final Logger logger = LoggerFactory.getLogger(PurlBuilder.class);

    /**
     * Placeholder namespace used when an ecosystem's purl-spec type requires a namespace
     * (cpan, golang, composer) but the package file does not carry one.
     * (tested by {@code PurlBuilderTest.forGo_singleSegmentUsesUnknownNamespace},
     * {@code PurlBuilderTest.forPackagist_missingVendorUsesUnknownNamespace},
     * {@code PurlBuilderTest.forCpan_missingPauseIdUsesUnknownNamespace})
     */
    public static final String UNKNOWN_NAMESPACE = "unknown";

    private PurlBuilder() {
    }

    /**
     * The single construction path: normalize/validate through the coordinates library
     * and downgrade any violation to a WARN log with an empty result. Never throws.
     *
     * @param ecosystem human-readable ecosystem label for the log message
     * @param type      the purl-spec type string (e.g., {@code "npm"})
     * @param namespace the namespace (may be null when the type allows none)
     * @param name      the package name
     * @param version   the package version (may be null)
     * @param qualifiers sorted qualifier map (may be null when unused)
     * @return the validated Purl, or empty when the coordinates library rejects it
     */
    private static @NotNull Optional<Purl> build(@NotNull String ecosystem, @NotNull String type,
            String namespace, @NotNull String name, String version,
            TreeMap<String, String> qualifiers) {
        if (name.isEmpty()) {
            logger.warn("Failed to build purl for {}: name is empty (version={})",
                    ecosystem, version);
            return Optional.empty();
        }
        try {
            return Optional.of(Purl.normalize(new Purl(type, namespace, name, version, qualifiers, null)));
        } catch (Purl.PurlException e) {
            logger.warn("Failed to build purl for {}: name={}, version={}, reason: {}",
                    ecosystem, name, version, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Builds a PURL for an npm package. Scoped packages keep the {@code @scope} namespace
     * (the canonical purl-spec form, rendered percent-encoded as {@code %40scope}).
     * (tested by {@code PurlBuilderTest.forNpm_unscopedPackage},
     * {@code PurlBuilderTest.forNpm_scopedPackage})
     *
     * @param name    the package name (may include {@code @scope/} prefix)
     * @param version the package version
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forNpm(@NotNull String name, @NotNull String version) {
        String namespace = null;
        String pkgName = name;
        if (name.startsWith("@") && name.contains("/")) {
            int slashIdx = name.indexOf('/');
            namespace = name.substring(0, slashIdx);
            pkgName = name.substring(slashIdx + 1);
        }
        return build("npm", "npm", namespace, pkgName, version, null);
    }

    /**
     * Builds a PURL for a PyPI package. Name is PEP 503 normalized (lowercase, runs of
     * {@code [-_.]} collapsed to a single hyphen); the coordinates library re-applies
     * its own pypi name/version folding as belt-and-braces.
     * (tested by {@code PurlBuilderTest.forPypi_normalizesName})
     *
     * @param name    the package name
     * @param version the package version
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forPypi(@NotNull String name, @NotNull String version) {
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
        return build("PyPI", "pypi", null, normalized, version, null);
    }

    /**
     * Builds a PURL for a Go module. The last path segment becomes the name; single-segment
     * module paths (which purl-spec rejects without a namespace) receive
     * {@link #UNKNOWN_NAMESPACE}.
     * (tested by {@code PurlBuilderTest.forGo_splitsModulePath},
     * {@code PurlBuilderTest.forGo_singleSegmentUsesUnknownNamespace})
     *
     * @param modulePath the full Go module path (e.g., {@code github.com/user/repo})
     * @param version    the module version
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forGo(@NotNull String modulePath, @NotNull String version) {
        int lastSlash = modulePath.lastIndexOf('/');
        String namespace = lastSlash > 0 ? modulePath.substring(0, lastSlash) : UNKNOWN_NAMESPACE;
        String name = lastSlash > 0 ? modulePath.substring(lastSlash + 1) : modulePath;
        return build("Go", "golang", namespace, name, version, null);
    }

    /**
     * Builds a PURL for a Crates.io package (type {@code cargo}, flat namespace).
     * (tested by {@code PurlBuilderTest.forCrates_flatNamespace})
     *
     * @param name    the crate name
     * @param version the crate version
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forCrates(@NotNull String name, @NotNull String version) {
        return build("crates.io", "cargo", null, name, version, null);
    }

    /**
     * Builds a PURL for a RubyGems package (type {@code gem}, flat namespace).
     * (tested by {@code PurlBuilderTest.forRubyGems_simple})
     *
     * @param name    the gem name
     * @param version the gem version
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forRubyGems(@NotNull String name, @NotNull String version) {
        return build("RubyGems", "gem", null, name, version, null);
    }

    /**
     * Builds a PURL for a Packagist (Composer) package from a {@code vendor/package} name.
     * A vendor-less name (purl-spec requires a composer namespace) receives
     * {@link #UNKNOWN_NAMESPACE}.
     * (tested by {@code PurlBuilderTest.forPackagist_splitsVendor},
     * {@code PurlBuilderTest.forPackagist_missingVendorUsesUnknownNamespace})
     *
     * @param vendorAndName the full name in {@code vendor/package} format
     * @param version       the package version
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forPackagist(@NotNull String vendorAndName,
            @NotNull String version) {
        int slashIdx = vendorAndName.indexOf('/');
        if (slashIdx < 0) {
            return build("Packagist", "composer", UNKNOWN_NAMESPACE, vendorAndName, version, null);
        }
        return build("Packagist", "composer", vendorAndName.substring(0, slashIdx),
                vendorAndName.substring(slashIdx + 1), version, null);
    }

    /**
     * Builds a PURL for a Conda package. Per purl-spec, Conda PURLs have no namespace
     * (channel is not available from the package file). Build and subdir are qualifiers.
     * (tested by {@code PurlBuilderTest.forConda_nameAndVersion},
     * {@code PurlBuilderTest.forConda_withBuildQualifier},
     * {@code PurlBuilderTest.forConda_withSubdirQualifier},
     * {@code PurlBuilderTest.forConda_withBothQualifiers},
     * {@code PurlBuilderTest.forConda_noNamespace})
     *
     * @param name    the package name
     * @param version the package version
     * @param build   the build string qualifier (e.g., {@code py312hc5e2394_0}), if present
     * @param subdir  the subdir/platform qualifier (e.g., {@code linux-64}), if present
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forConda(@NotNull String name, @NotNull String version,
            @NotNull Optional<String> build, @NotNull Optional<String> subdir) {
        TreeMap<String, String> qualifiers = new TreeMap<>();
        build.ifPresent(b -> qualifiers.put("build", b));
        subdir.ifPresent(s -> qualifiers.put("subdir", s));
        return build("Conda", "conda", null, name, version,
                qualifiers.isEmpty() ? null : qualifiers);
    }

    /**
     * Builds a PURL for a CocoaPods podspec (flat namespace).
     * (tested by {@code PurlBuilderTest.forCocoapods_simple})
     *
     * @param name    the pod name
     * @param version the pod version
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forCocoapods(@NotNull String name, @NotNull String version) {
        return build("CocoaPods", "cocoapods", null, name, version, null);
    }

    /**
     * Builds a PURL for a CPAN distribution. Per purl-spec the namespace is the PAUSE
     * author id; when the distribution metadata carries none (it is not present in the
     * tarball, see {@code CpanQuirks} Q3) the {@link #UNKNOWN_NAMESPACE} sentinel is used.
     * The name must be the distribution name — module-style names containing {@code ::}
     * are rejected by the coordinates library and yield empty.
     * (tested by {@code PurlBuilderTest.forCpan_withPauseId},
     * {@code PurlBuilderTest.forCpan_missingPauseIdUsesUnknownNamespace},
     * {@code PurlBuilderTest.forCpan_moduleNameIsRejected})
     *
     * @param name    the distribution name
     * @param version the distribution version
     * @param pauseId the PAUSE ID (optional namespace)
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forCpan(@NotNull String name, @NotNull String version,
            @NotNull Optional<String> pauseId) {
        String namespace = pauseId.filter(id -> !id.isEmpty()).orElse(UNKNOWN_NAMESPACE);
        return build("CPAN", "cpan", namespace, name, version, null);
    }

    /**
     * Builds a PURL for a Hex package. The coordinates library lowercases hex names per
     * purl-spec, so mixed-case input still produces a canonical Purl.
     * (tested by {@code PurlBuilderTest.forHex_simple})
     *
     * @param name    the package name
     * @param version the package version
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forHex(@NotNull String name, @NotNull String version) {
        return build("Hex", "hex", null, name, version, null);
    }

    /**
     * Builds a PURL for a LuaRocks package. The coordinates library lowercases LuaRocks
     * names per purl-spec, so mixed-case input still produces a canonical Purl.
     * (tested by {@code PurlBuilderTest.forLuaRocks_simple},
     * {@code PurlBuilderTest.forLuaRocks_nameLowercased},
     * {@code PurlBuilderTest.forLuaRocks_versionWithRevision})
     *
     * @param name    the rock name
     * @param version the rock version (may include revision suffix)
     * @return the validated Purl, or empty when malformed (WARN logged)
     */
    public static @NotNull Optional<Purl> forLuaRocks(@NotNull String name, @NotNull String version) {
        return build("LuaRocks", "luarocks", null, name, version, null);
    }
}
