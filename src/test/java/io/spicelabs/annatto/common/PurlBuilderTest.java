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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link PurlBuilder} across all 11 ecosystems, building coordinates-library
 * {@link Purl} values.
 *
 * <p>LLM note: every builder method returns {@code Optional<Purl>} and never throws —
 * malformed input is downgraded to a WARN log and an empty Optional (verified by the
 * {@code *IsRejected*} tests below, which also assert no exception escapes).
 */
class PurlBuilderTest {

    /**
     * Goal: Verify npm PURL for unscoped package.
     * Rationale: Most npm packages are unscoped.
     */
    @Test
    void forNpm_unscopedPackage() {
        Purl purl = PurlBuilder.forNpm("express", "4.18.2").orElseThrow();
        assertThat(purl.type).isEqualTo("npm");
        assertThat(purl.namespace).isNull();
        assertThat(purl.name).isEqualTo("express");
        assertThat(purl.version).isEqualTo("4.18.2");
    }

    /**
     * Goal: Verify npm PURL for scoped package keeps the "@scope" namespace.
     * Rationale: The canonical purl-spec form keeps the "@" in the namespace and renders
     * it percent-encoded ("%40scope") in the canonical string.
     */
    @Test
    void forNpm_scopedPackage() {
        Purl purl = PurlBuilder.forNpm("@angular/core", "17.0.0").orElseThrow();
        assertThat(purl.type).isEqualTo("npm");
        assertThat(purl.namespace).isEqualTo("@angular");
        assertThat(purl.name).isEqualTo("core");
        assertThat(purl.version).isEqualTo("17.0.0");
        assertThat(purl.toCanonical()).isEqualTo("pkg:npm/%40angular/core@17.0.0");
    }

    /**
     * Goal: Verify PyPI PURL normalizes name to lowercase with hyphens.
     * Rationale: PyPI names are case-insensitive and normalize underscores/dots to hyphens.
     */
    @Test
    void forPypi_normalizesName() {
        Purl purl = PurlBuilder.forPypi("Flask_SocketIO", "5.3.0").orElseThrow();
        assertThat(purl.type).isEqualTo("pypi");
        assertThat(purl.name).isEqualTo("flask-socketio");
        assertThat(purl.version).isEqualTo("5.3.0");
    }

    /**
     * Goal: Verify Go PURL splits module path into namespace and name.
     * Rationale: Go module paths are URL-like and the last segment is the name.
     */
    @Test
    void forGo_splitsModulePath() {
        Purl purl = PurlBuilder.forGo("github.com/gin-gonic/gin", "v1.9.1").orElseThrow();
        assertThat(purl.type).isEqualTo("golang");
        assertThat(purl.namespace).isEqualTo("github.com/gin-gonic");
        assertThat(purl.name).isEqualTo("gin");
        assertThat(purl.version).isEqualTo("v1.9.1");
    }

    /**
     * Goal: Verify Go PURL for a single-segment module path uses the coordinates "~unknown" namespace
     * sentinel instead of being dropped.
     * Rationale: purl-spec requires a golang namespace; the sentinel keeps the identifier
     * present and recognizable downstream (agreed measure, mirrors the CPAN policy).
     */
    @Test
    void forGo_singleSegmentUsesUnknownNamespace() {
        Purl purl = PurlBuilder.forGo("somemodule", "v1.0.0").orElseThrow();
        assertThat(purl.type).isEqualTo("golang");
        assertThat(purl.namespace).isEqualTo(Purl.UNKNOWN_NAMESPACE);
        assertThat(purl.isNamespaceUnknown()).isTrue();
        assertThat(purl.name).isEqualTo("somemodule");
        assertThat(purl.toCanonical()).isEqualTo("pkg:golang/~unknown/somemodule@v1.0.0");
    }

    /**
     * Goal: Verify Crates PURL has no namespace.
     * Rationale: Crates.io has a flat namespace.
     */
    @Test
    void forCrates_flatNamespace() {
        Purl purl = PurlBuilder.forCrates("serde", "1.0.195").orElseThrow();
        assertThat(purl.type).isEqualTo("cargo");
        assertThat(purl.namespace).isNull();
        assertThat(purl.name).isEqualTo("serde");
        assertThat(purl.version).isEqualTo("1.0.195");
    }

    /**
     * Goal: Verify RubyGems PURL construction.
     * Rationale: Gems have a flat namespace.
     */
    @Test
    void forRubyGems_simple() {
        Purl purl = PurlBuilder.forRubyGems("rails", "7.1.2").orElseThrow();
        assertThat(purl.type).isEqualTo("gem");
        assertThat(purl.name).isEqualTo("rails");
        assertThat(purl.version).isEqualTo("7.1.2");
    }

    /**
     * Goal: Verify Packagist PURL splits vendor/package into namespace and name.
     * Rationale: Composer uses vendor/package naming.
     */
    @Test
    void forPackagist_splitsVendor() {
        Purl purl = PurlBuilder.forPackagist("laravel/framework", "10.0.0").orElseThrow();
        assertThat(purl.type).isEqualTo("composer");
        assertThat(purl.namespace).isEqualTo("laravel");
        assertThat(purl.name).isEqualTo("framework");
        assertThat(purl.version).isEqualTo("10.0.0");
    }

    /**
     * Goal: Verify Packagist PURL for a vendor-less name uses the coordinates "~unknown" namespace
     * sentinel instead of being dropped.
     * Rationale: purl-spec requires a composer namespace; the sentinel keeps the
     * identifier present (agreed measure, mirrors the CPAN policy).
     */
    @Test
    void forPackagist_missingVendorUsesUnknownNamespace() {
        Purl purl = PurlBuilder.forPackagist("justapackage", "1.0.0").orElseThrow();
        assertThat(purl.type).isEqualTo("composer");
        assertThat(purl.namespace).isEqualTo(Purl.UNKNOWN_NAMESPACE);
        assertThat(purl.isNamespaceUnknown()).isTrue();
        assertThat(purl.toCanonical()).isEqualTo("pkg:composer/~unknown/justapackage@1.0.0");
    }

    /**
     * Goal: Verify Conda PURL with name and version only.
     * Rationale: Basic Conda PURL has no namespace, no qualifiers.
     */
    @Test
    void forConda_nameAndVersion() {
        Purl purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.empty(), Optional.empty()).orElseThrow();
        assertThat(purl.type).isEqualTo("conda");
        assertThat(purl.namespace).isNull();
        assertThat(purl.name).isEqualTo("numpy");
        assertThat(purl.version).isEqualTo("1.26.4");
        assertThat(purl.qualifiers).isEmpty();
    }

    /**
     * Goal: Verify Conda PURL includes build qualifier.
     * Rationale: Q3 - Build string disambiguates multiple builds; it's a PURL qualifier.
     */
    @Test
    void forConda_withBuildQualifier() {
        Purl purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.of("py312hc5e2394_0"), Optional.empty()).orElseThrow();
        assertThat(purl.toCanonical()).contains("build=py312hc5e2394_0");
        assertThat(purl.qualifiers).containsEntry("build", "py312hc5e2394_0");
    }

    /**
     * Goal: Verify Conda PURL includes subdir qualifier.
     * Rationale: Q4 - Subdir identifies target platform; it's a PURL qualifier.
     */
    @Test
    void forConda_withSubdirQualifier() {
        Purl purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.empty(), Optional.of("linux-64")).orElseThrow();
        assertThat(purl.toCanonical()).contains("subdir=linux-64");
        assertThat(purl.qualifiers).containsEntry("subdir", "linux-64");
    }

    /**
     * Goal: Verify Conda PURL includes both build and subdir qualifiers.
     * Rationale: Full Conda PURL has both qualifiers together.
     */
    @Test
    void forConda_withBothQualifiers() {
        Purl purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.of("py312hc5e2394_0"), Optional.of("linux-64")).orElseThrow();
        assertThat(purl.type).isEqualTo("conda");
        assertThat(purl.name).isEqualTo("numpy");
        assertThat(purl.version).isEqualTo("1.26.4");
        assertThat(purl.qualifiers).containsEntry("build", "py312hc5e2394_0");
        assertThat(purl.qualifiers).containsEntry("subdir", "linux-64");
    }

    /**
     * Goal: Verify Conda PURL has no namespace.
     * Rationale: Q2 - Channel is external context; namespace is always null per purl-spec.
     */
    @Test
    void forConda_noNamespace() {
        Purl purl = PurlBuilder.forConda("numpy", "1.26.4",
                Optional.of("py312hc5e2394_0"), Optional.of("linux-64")).orElseThrow();
        assertThat(purl.namespace).isNull();
    }

    /**
     * Goal: Verify CocoaPods PURL construction.
     * Rationale: CocoaPods has a flat namespace.
     */
    @Test
    void forCocoapods_simple() {
        Purl purl = PurlBuilder.forCocoapods("Alamofire", "5.8.0").orElseThrow();
        assertThat(purl.type).isEqualTo("cocoapods");
        assertThat(purl.name).isEqualTo("Alamofire");
        assertThat(purl.version).isEqualTo("5.8.0");
    }

    /**
     * Goal: Verify CPAN PURL with PAUSE ID namespace.
     * Rationale: PAUSE ID identifies the author on CPAN.
     */
    @Test
    void forCpan_withPauseId() {
        Purl purl = PurlBuilder.forCpan("Moose", "2.2207", Optional.of("ETHER")).orElseThrow();
        assertThat(purl.type).isEqualTo("cpan");
        assertThat(purl.namespace).isEqualTo("ETHER");
        assertThat(purl.name).isEqualTo("Moose");
        assertThat(purl.version).isEqualTo("2.2207");
    }

    /**
     * Goal: Verify CPAN PURL without a PAUSE ID uses the coordinates "~unknown" namespace sentinel.
     * Rationale: purl-spec requires a cpan namespace; the distribution tarball does not
     * carry the PAUSE id (CpanQuirks Q3), so the sentinel keeps the identifier present.
     */
    @Test
    void forCpan_missingPauseIdUsesUnknownNamespace() {
        Purl purl = PurlBuilder.forCpan("App-cpanminus", "1.7047", Optional.empty()).orElseThrow();
        assertThat(purl.type).isEqualTo("cpan");
        assertThat(purl.namespace).isEqualTo(Purl.UNKNOWN_NAMESPACE);
        assertThat(purl.isNamespaceUnknown()).isTrue();
        assertThat(purl.toCanonical()).isEqualTo("pkg:cpan/~unknown/App-cpanminus@1.7047");
    }

    /**
     * Goal: Verify a CPAN module-style name ("::" form) yields an empty Optional, not an
     * exception.
     * Rationale: purl-spec requires the distribution name, not the module name; the
     * coordinates library rejects "::" in cpan names and the builder must downgrade that
     * to WARN + empty (never throw).
     */
    @Test
    void forCpan_moduleNameIsRejected() {
        assertThat(PurlBuilder.forCpan("CPAN::Meta", "2.150010", Optional.empty())).isEmpty();
    }

    /**
     * Goal: Verify Hex PURL construction.
     * Rationale: Hex has a flat namespace.
     */
    @Test
    void forHex_simple() {
        Purl purl = PurlBuilder.forHex("phoenix", "1.7.10").orElseThrow();
        assertThat(purl.type).isEqualTo("hex");
        assertThat(purl.name).isEqualTo("phoenix");
        assertThat(purl.version).isEqualTo("1.7.10");
    }

    /**
     * Goal: Verify LuaRocks PURL construction.
     * Rationale: LuaRocks has a flat namespace.
     */
    @Test
    void forLuaRocks_simple() {
        Purl purl = PurlBuilder.forLuaRocks("luasocket", "3.1.0-1").orElseThrow();
        assertThat(purl.type).isEqualTo("luarocks");
        assertThat(purl.name).isEqualTo("luasocket");
        assertThat(purl.version).isEqualTo("3.1.0-1");
    }

    /**
     * Goal: Verify LuaRocks PURL lowercases mixed-case names (Q8).
     * Rationale: purl-spec requires ASCII lowercased names for LuaRocks; the coordinates
     * library applies that rule during normalize.
     */
    @Test
    void forLuaRocks_nameLowercased() {
        Purl purl = PurlBuilder.forLuaRocks("LuaFileSystem", "1.8.0-1").orElseThrow();
        assertThat(purl.type).isEqualTo("luarocks");
        assertThat(purl.name).isEqualTo("luafilesystem");
        assertThat(purl.version).isEqualTo("1.8.0-1");
        assertThat(purl.namespace).isNull();
    }

    /**
     * Goal: Verify LuaRocks PURL preserves version with revision suffix.
     * Rationale: Q2 - LuaRocks versions include a revision suffix (e.g., 1.8.0-1).
     */
    @Test
    void forLuaRocks_versionWithRevision() {
        Purl purl = PurlBuilder.forLuaRocks("lpeg", "1.1.0-1").orElseThrow();
        assertThat(purl.version).isEqualTo("1.1.0-1");
    }

    /**
     * Goal: Verify an empty name yields an empty Optional without throwing.
     * Rationale: Incomplete metadata must never produce a pURL; the builder's guard
     * backs the ecosystem-level empty-name/version guard.
     */
    @Test
    void forNpm_emptyNameIsEmpty() {
        assertThat(PurlBuilder.forNpm("", "1.0.0")).isEmpty();
    }
}
