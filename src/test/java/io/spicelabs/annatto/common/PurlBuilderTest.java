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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PurlBuilder} across all 11 ecosystems.
 */
class PurlBuilderTest {

    /**
     * Goal: Verify npm PURL for unscoped package.
     * Rationale: Most npm packages are unscoped.
     */
    @Test
    void forNpm_unscopedPackage() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forNpm("express", "4.18.2");
        assertThat(purl.getType()).isEqualTo("npm");
        assertThat(purl.getNamespace()).isNull();
        assertThat(purl.getName()).isEqualTo("express");
        assertThat(purl.getVersion()).isEqualTo("4.18.2");
    }

    /**
     * Goal: Verify npm PURL for scoped package extracts namespace correctly.
     * Rationale: Scoped packages use @scope/name format.
     */
    @Test
    void forNpm_scopedPackage() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forNpm("@angular/core", "17.0.0");
        assertThat(purl.getType()).isEqualTo("npm");
        assertThat(purl.getNamespace()).isEqualTo("@angular");
        assertThat(purl.getName()).isEqualTo("core");
        assertThat(purl.getVersion()).isEqualTo("17.0.0");
    }

    /**
     * Goal: Verify PyPI PURL normalizes name to lowercase with hyphens.
     * Rationale: PyPI names are case-insensitive and normalize underscores/dots to hyphens.
     */
    @Test
    void forPypi_normalizesName() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forPypi("Flask_SocketIO", "5.3.0");
        assertThat(purl.getType()).isEqualTo("pypi");
        assertThat(purl.getName()).isEqualTo("flask-socketio");
        assertThat(purl.getVersion()).isEqualTo("5.3.0");
    }

    /**
     * Goal: Verify Go PURL splits module path into namespace and name.
     * Rationale: Go module paths are URL-like and the last segment is the name.
     */
    @Test
    void forGo_splitsModulePath() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forGo("github.com/gin-gonic/gin", "v1.9.1");
        assertThat(purl.getType()).isEqualTo("golang");
        assertThat(purl.getNamespace()).isEqualTo("github.com/gin-gonic");
        assertThat(purl.getName()).isEqualTo("gin");
        assertThat(purl.getVersion()).isEqualTo("v1.9.1");
    }

    /**
     * Goal: Verify Crates PURL has no namespace.
     * Rationale: Crates.io has a flat namespace.
     */
    @Test
    void forCrates_flatNamespace() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forCrates("serde", "1.0.195");
        assertThat(purl.getType()).isEqualTo("cargo");
        assertThat(purl.getNamespace()).isNull();
        assertThat(purl.getName()).isEqualTo("serde");
        assertThat(purl.getVersion()).isEqualTo("1.0.195");
    }

    /**
     * Goal: Verify RubyGems PURL construction.
     * Rationale: Gems have a flat namespace.
     */
    @Test
    void forRubyGems_simple() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forRubyGems("rails", "7.1.2");
        assertThat(purl.getType()).isEqualTo("gem");
        assertThat(purl.getName()).isEqualTo("rails");
        assertThat(purl.getVersion()).isEqualTo("7.1.2");
    }

    /**
     * Goal: Verify Packagist PURL splits vendor/package into namespace and name.
     * Rationale: Composer uses vendor/package naming.
     */
    @Test
    void forPackagist_splitsVendor() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forPackagist("laravel/framework", "10.0.0");
        assertThat(purl.getType()).isEqualTo("composer");
        assertThat(purl.getNamespace()).isEqualTo("laravel");
        assertThat(purl.getName()).isEqualTo("framework");
        assertThat(purl.getVersion()).isEqualTo("10.0.0");
    }

    /**
     * Goal: Verify Conda PURL with name and version only.
     * Rationale: Basic Conda PURL has no namespace, no qualifiers.
     */
    @Test
    void forConda_nameAndVersion() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.empty(), Optional.empty());
        assertThat(purl.getType()).isEqualTo("conda");
        assertThat(purl.getNamespace()).isNull();
        assertThat(purl.getName()).isEqualTo("numpy");
        assertThat(purl.getVersion()).isEqualTo("1.26.4");
        assertThat(purl.getQualifiers()).isNull();
    }

    /**
     * Goal: Verify Conda PURL includes build qualifier.
     * Rationale: Q3 - Build string disambiguates multiple builds; it's a PURL qualifier.
     */
    @Test
    void forConda_withBuildQualifier() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.of("py312hc5e2394_0"), Optional.empty());
        assertThat(purl.toString()).contains("build=py312hc5e2394_0");
        assertThat(purl.getQualifiers()).containsEntry("build", "py312hc5e2394_0");
    }

    /**
     * Goal: Verify Conda PURL includes subdir qualifier.
     * Rationale: Q4 - Subdir identifies target platform; it's a PURL qualifier.
     */
    @Test
    void forConda_withSubdirQualifier() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.empty(), Optional.of("linux-64"));
        assertThat(purl.toString()).contains("subdir=linux-64");
        assertThat(purl.getQualifiers()).containsEntry("subdir", "linux-64");
    }

    /**
     * Goal: Verify Conda PURL includes both build and subdir qualifiers.
     * Rationale: Full Conda PURL has both qualifiers together.
     */
    @Test
    void forConda_withBothQualifiers() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.of("py312hc5e2394_0"), Optional.of("linux-64"));
        assertThat(purl.getType()).isEqualTo("conda");
        assertThat(purl.getName()).isEqualTo("numpy");
        assertThat(purl.getVersion()).isEqualTo("1.26.4");
        assertThat(purl.getQualifiers()).containsEntry("build", "py312hc5e2394_0");
        assertThat(purl.getQualifiers()).containsEntry("subdir", "linux-64");
    }

    /**
     * Goal: Verify Conda PURL has no namespace.
     * Rationale: Q2 - Channel is external context; namespace is always null per purl-spec.
     */
    @Test
    void forConda_noNamespace() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.of("py312hc5e2394_0"), Optional.of("linux-64"));
        assertThat(purl.getNamespace()).isNull();
    }

    /**
     * Goal: Verify CocoaPods PURL construction.
     * Rationale: CocoaPods has a flat namespace.
     */
    @Test
    void forCocoapods_simple() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forCocoapods("Alamofire", "5.8.0");
        assertThat(purl.getType()).isEqualTo("cocoapods");
        assertThat(purl.getName()).isEqualTo("Alamofire");
        assertThat(purl.getVersion()).isEqualTo("5.8.0");
    }

    /**
     * Goal: Verify CPAN PURL with PAUSE ID namespace.
     * Rationale: PAUSE ID identifies the author on CPAN.
     */
    @Test
    void forCpan_withPauseId() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forCpan("Moose", "2.2207", Optional.of("ETHER"));
        assertThat(purl.getType()).isEqualTo("cpan");
        assertThat(purl.getNamespace()).isEqualTo("ETHER");
        assertThat(purl.getName()).isEqualTo("Moose");
        assertThat(purl.getVersion()).isEqualTo("2.2207");
    }

    /**
     * Goal: Verify Hex PURL construction.
     * Rationale: Hex has a flat namespace.
     */
    @Test
    void forHex_simple() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forHex("phoenix", "1.7.10");
        assertThat(purl.getType()).isEqualTo("hex");
        assertThat(purl.getName()).isEqualTo("phoenix");
        assertThat(purl.getVersion()).isEqualTo("1.7.10");
    }

    /**
     * Goal: Verify LuaRocks PURL construction.
     * Rationale: LuaRocks has a flat namespace.
     */
    @Test
    void forLuaRocks_simple() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forLuaRocks("luasocket", "3.1.0-1");
        assertThat(purl.getType()).isEqualTo("luarocks");
        assertThat(purl.getName()).isEqualTo("luasocket");
        assertThat(purl.getVersion()).isEqualTo("3.1.0-1");
    }

    /**
     * Goal: Verify LuaRocks PURL lowercases mixed-case names (Q8).
     * Rationale: purl-spec requires ASCII lowercased names for LuaRocks.
     */
    @Test
    void forLuaRocks_nameLowercased() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forLuaRocks("LuaFileSystem", "1.8.0-1");
        assertThat(purl.getType()).isEqualTo("luarocks");
        assertThat(purl.getName()).isEqualTo("luafilesystem");
        assertThat(purl.getVersion()).isEqualTo("1.8.0-1");
        assertThat(purl.getNamespace()).isNull();
    }

    /**
     * Goal: Verify LuaRocks PURL preserves version with revision suffix.
     * Rationale: Q2 - LuaRocks versions include a revision suffix (e.g., 1.8.0-1).
     */
    @Test
    void forLuaRocks_versionWithRevision() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forLuaRocks("lpeg", "1.1.0-1");
        assertThat(purl.getVersion()).isEqualTo("1.1.0-1");
    }
}
