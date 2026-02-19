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

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Builds {@link PackageURL} instances for each supported ecosystem.
 * All methods are stateless pure functions.
 */
public final class PurlBuilder {

    private PurlBuilder() {
    }

    /**
     * Builds a PURL for an npm package.
     *
     * @param name    the package name (may include {@code @scope/} prefix)
     * @param version the package version
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forNpm(@NotNull String name, @NotNull String version)
            throws MalformedPackageURLException {
        String namespace = null;
        String pkgName = name;
        if (name.startsWith("@") && name.contains("/")) {
            int slashIdx = name.indexOf('/');
            namespace = name.substring(0, slashIdx);
            pkgName = name.substring(slashIdx + 1);
        }
        return new PackageURL("npm", namespace, pkgName, version, null, null);
    }

    /**
     * Builds a PURL for a PyPI package. Name is lowercased and hyphens normalized per PyPI conventions.
     *
     * @param name    the package name
     * @param version the package version
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forPypi(@NotNull String name, @NotNull String version)
            throws MalformedPackageURLException {
        String normalized = name.toLowerCase().replaceAll("[-_.]+", "-");
        return new PackageURL("pypi", null, normalized, version, null, null);
    }

    /**
     * Builds a PURL for a Go module.
     *
     * @param modulePath the full Go module path (e.g., {@code github.com/user/repo})
     * @param version    the module version
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forGo(@NotNull String modulePath, @NotNull String version)
            throws MalformedPackageURLException {
        int lastSlash = modulePath.lastIndexOf('/');
        String namespace = lastSlash > 0 ? modulePath.substring(0, lastSlash) : null;
        String name = lastSlash > 0 ? modulePath.substring(lastSlash + 1) : modulePath;
        return new PackageURL("golang", namespace, name, version, null, null);
    }

    /**
     * Builds a PURL for a Crates.io package.
     *
     * @param name    the crate name
     * @param version the crate version
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forCrates(@NotNull String name, @NotNull String version)
            throws MalformedPackageURLException {
        return new PackageURL("cargo", null, name, version, null, null);
    }

    /**
     * Builds a PURL for a RubyGems package.
     *
     * @param name    the gem name
     * @param version the gem version
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forRubyGems(@NotNull String name, @NotNull String version)
            throws MalformedPackageURLException {
        return new PackageURL("gem", null, name, version, null, null);
    }

    /**
     * Builds a PURL for a Packagist (Composer) package.
     *
     * @param vendorAndName the full name in {@code vendor/package} format
     * @param version       the package version
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forPackagist(@NotNull String vendorAndName, @NotNull String version)
            throws MalformedPackageURLException {
        int slashIdx = vendorAndName.indexOf('/');
        if (slashIdx < 0) {
            return new PackageURL("composer", null, vendorAndName, version, null, null);
        }
        String vendor = vendorAndName.substring(0, slashIdx);
        String name = vendorAndName.substring(slashIdx + 1);
        return new PackageURL("composer", vendor, name, version, null, null);
    }

    /**
     * Builds a PURL for a Conda package. Per purl-spec, Conda PURLs have no namespace
     * (channel is not available from the package file). Build and subdir are qualifiers.
     *
     * @param name    the package name
     * @param version the package version
     * @param build   the build string qualifier (e.g., {@code py312hc5e2394_0}), if present
     * @param subdir  the subdir/platform qualifier (e.g., {@code linux-64}), if present
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forConda(@NotNull String name, @NotNull String version,
            @NotNull Optional<String> build, @NotNull Optional<String> subdir)
            throws MalformedPackageURLException {
        TreeMap<String, String> qualifiers = new TreeMap<>();
        build.ifPresent(b -> qualifiers.put("build", b));
        subdir.ifPresent(s -> qualifiers.put("subdir", s));
        return new PackageURL("conda", null, name, version,
                qualifiers.isEmpty() ? null : qualifiers, null);
    }

    /**
     * Builds a PURL for a CocoaPods podspec.
     *
     * @param name    the pod name
     * @param version the pod version
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forCocoapods(@NotNull String name, @NotNull String version)
            throws MalformedPackageURLException {
        return new PackageURL("cocoapods", null, name, version, null, null);
    }

    /**
     * Builds a PURL for a CPAN distribution.
     *
     * @param name    the distribution name
     * @param version the distribution version
     * @param pauseId the PAUSE ID (optional namespace)
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forCpan(@NotNull String name, @NotNull String version,
            @NotNull Optional<String> pauseId) throws MalformedPackageURLException {
        return new PackageURL("cpan", pauseId.orElse(null), name, version, null, null);
    }

    /**
     * Builds a PURL for a Hex package.
     *
     * @param name    the package name
     * @param version the package version
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forHex(@NotNull String name, @NotNull String version)
            throws MalformedPackageURLException {
        return new PackageURL("hex", null, name, version, null, null);
    }

    /**
     * Builds a PURL for a LuaRocks package.
     *
     * @param name    the rock name
     * @param version the rock version (may include revision suffix)
     * @return the constructed PackageURL
     * @throws MalformedPackageURLException if the PURL cannot be constructed
     */
    public static @NotNull PackageURL forLuaRocks(@NotNull String name, @NotNull String version)
            throws MalformedPackageURLException {
        return new PackageURL("luarocks", null, name.toLowerCase(Locale.ROOT), version, null, null);
    }
}
