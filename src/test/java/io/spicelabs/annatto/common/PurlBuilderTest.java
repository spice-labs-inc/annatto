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
     * Goal: Verify Conda PURL includes channel as namespace.
     * Rationale: Channel is part of Conda package identity.
     */
    @Test
    void forConda_withChannel() throws MalformedPackageURLException {
        PackageURL purl = PurlBuilder.forConda(Optional.of("conda-forge"), "numpy", "1.26.0");
        assertThat(purl.getType()).isEqualTo("conda");
        assertThat(purl.getNamespace()).isEqualTo("conda-forge");
        assertThat(purl.getName()).isEqualTo("numpy");
        assertThat(purl.getVersion()).isEqualTo("1.26.0");
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
}
