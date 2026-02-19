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
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AnnattoProcessFilter} ecosystem detection logic.
 */
class AnnattoProcessFilterTest {

    private final AnnattoProcessFilter filter = new AnnattoProcessFilter(Map.of());

    /**
     * Goal: Verify .tgz files are detected as npm.
     * Rationale: npm packages are distributed as .tgz archives.
     */
    @Test
    void detectEcosystem_tgz_isNpm() {
        assertThat(filter.detectEcosystem("express-4.18.2.tgz"))
                .isEqualTo(Optional.of(EcosystemId.NPM));
    }

    /**
     * Goal: Verify .whl files are detected as PyPI.
     * Rationale: Python wheels are the standard binary distribution format.
     */
    @Test
    void detectEcosystem_whl_isPypi() {
        assertThat(filter.detectEcosystem("Flask-3.0.0-py3-none-any.whl"))
                .isEqualTo(Optional.of(EcosystemId.PYPI));
    }

    /**
     * Goal: Verify .crate files are detected as Crates.io.
     * Rationale: Rust crates use the .crate extension.
     */
    @Test
    void detectEcosystem_crate_isCrates() {
        assertThat(filter.detectEcosystem("serde-1.0.195.crate"))
                .isEqualTo(Optional.of(EcosystemId.CRATES));
    }

    /**
     * Goal: Verify .gem files are detected as RubyGems.
     * Rationale: Ruby gems use the .gem extension.
     */
    @Test
    void detectEcosystem_gem_isRubygems() {
        assertThat(filter.detectEcosystem("rails-7.1.2.gem"))
                .isEqualTo(Optional.of(EcosystemId.RUBYGEMS));
    }

    /**
     * Goal: Verify .conda files are detected as Conda.
     * Rationale: Modern Conda packages use the .conda extension.
     */
    @Test
    void detectEcosystem_conda_isConda() {
        assertThat(filter.detectEcosystem("numpy-1.26.0-py311h64a7726_0.conda"))
                .isEqualTo(Optional.of(EcosystemId.CONDA));
    }

    /**
     * Goal: Verify .tar.bz2 files are detected as Conda (legacy format).
     * Rationale: Legacy Conda packages use .tar.bz2 format.
     */
    @Test
    void detectEcosystem_tarBz2_isConda() {
        assertThat(filter.detectEcosystem("numpy-1.26.0-py311h64a7726_0.tar.bz2"))
                .isEqualTo(Optional.of(EcosystemId.CONDA));
    }

    /**
     * Goal: Verify .rock files are detected as LuaRocks.
     * Rationale: LuaRocks packages use the .rock extension.
     */
    @Test
    void detectEcosystem_rock_isLuarocks() {
        assertThat(filter.detectEcosystem("luasocket-3.1.0-1.rock"))
                .isEqualTo(Optional.of(EcosystemId.LUAROCKS));
    }

    /**
     * Goal: Verify .rockspec files are detected as LuaRocks.
     * Rationale: LuaRocks spec files use the .rockspec extension.
     */
    @Test
    void detectEcosystem_rockspec_isLuarocks() {
        assertThat(filter.detectEcosystem("luasocket-3.1.0-1.rockspec"))
                .isEqualTo(Optional.of(EcosystemId.LUAROCKS));
    }

    /**
     * Goal: Verify .podspec files are detected as CocoaPods.
     * Rationale: CocoaPods uses .podspec files.
     */
    @Test
    void detectEcosystem_podspec_isCocoapods() {
        assertThat(filter.detectEcosystem("Alamofire.podspec"))
                .isEqualTo(Optional.of(EcosystemId.COCOAPODS));
    }

    /**
     * Goal: Verify .podspec.json files are detected as CocoaPods.
     * Rationale: CocoaPods also supports JSON podspec format.
     */
    @Test
    void detectEcosystem_podspecJson_isCocoapods() {
        assertThat(filter.detectEcosystem("Alamofire.podspec.json"))
                .isEqualTo(Optional.of(EcosystemId.COCOAPODS));
    }

    /**
     * Goal: Verify lowercase .tar.gz files are detected as PyPI sdist.
     * Rationale: PyPI packages typically use all-lowercase names.
     */
    @Test
    void detectEcosystem_tarGz_lowercaseIsPypi() {
        assertThat(filter.detectEcosystem("requests-2.31.0.tar.gz"))
                .isEqualTo(Optional.of(EcosystemId.PYPI));
    }

    /**
     * Goal: Verify uppercase .tar.gz files are detected as CPAN.
     * Rationale: Q8 — CPAN distributions use CamelCase names (e.g., Moose-2.2207.tar.gz).
     */
    @Test
    void detectTarGzEcosystem_cpanUppercase() {
        assertThat(filter.detectEcosystem("Moose-2.2207.tar.gz"))
                .isEqualTo(Optional.of(EcosystemId.CPAN));
    }

    /**
     * Goal: Verify lowercase CPAN name routes to PyPI (known limitation).
     * Rationale: Q8 — All-lowercase CPAN names (namespace-clean) cannot be distinguished from PyPI.
     */
    @Test
    void detectTarGzEcosystem_lowercaseCpanRoutesToPypi() {
        assertThat(filter.detectEcosystem("namespace-clean-0.27.tar.gz"))
                .isEqualTo(Optional.of(EcosystemId.PYPI));
    }

    /**
     * Goal: Verify hyphenated CPAN names with capitals route to CPAN.
     * Rationale: Q8 — Distribution names like Try-Tiny contain uppercase.
     */
    @Test
    void detectTarGzEcosystem_cpanHyphenatedUppercase() {
        assertThat(filter.detectEcosystem("Try-Tiny-0.31.tar.gz"))
                .isEqualTo(Optional.of(EcosystemId.CPAN));
    }

    /**
     * Goal: Verify constant (lowercase CPAN) routes to PyPI.
     * Rationale: Q8 — Known limitation for all-lowercase CPAN names.
     */
    @Test
    void detectTarGzEcosystem_constantRoutesToPypi() {
        assertThat(filter.detectEcosystem("constant-1.33.tar.gz"))
                .isEqualTo(Optional.of(EcosystemId.PYPI));
    }

    /**
     * Goal: Verify .zip files with @v are detected as Go Modules.
     * Rationale: Go module zips from proxy.golang.org contain @v in the filename.
     */
    @Test
    void detectEcosystem_zipWithAtV_isGo() {
        assertThat(filter.detectEcosystem("github.com_gin-gonic_gin@v1.9.1.zip"))
                .isEqualTo(Optional.of(EcosystemId.GO));
    }

    /**
     * Goal: Verify .zip files without @v are detected as Packagist.
     * Rationale: Non-Go zip files are routed to Packagist; if no composer.json found, handler
     * returns empty memento gracefully.
     */
    @Test
    void detectEcosystem_zipWithoutAtV_isPackagist() {
        assertThat(filter.detectEcosystem("some-package-1.0.0.zip"))
                .isEqualTo(Optional.of(EcosystemId.PACKAGIST));
    }

    /**
     * Goal: Verify plain .tar files are detected as Hex.
     * Rationale: Q1 — Hex packages are plain .tar archives.
     */
    @Test
    void detectEcosystem_tar_isHex() {
        assertThat(filter.detectEcosystem("jason-1.4.1.tar"))
                .isEqualTo(Optional.of(EcosystemId.HEX));
    }

    /**
     * Goal: Verify .tar detection doesn't interfere with .tar.gz.
     * Rationale: .tar.gz must still route to PyPI/CPAN, not Hex.
     */
    @Test
    void detectEcosystem_tarGz_notHex() {
        assertThat(filter.detectEcosystem("requests-2.31.0.tar.gz"))
                .isNotEqualTo(Optional.of(EcosystemId.HEX));
    }

    /**
     * Goal: Verify unrecognized extensions return empty.
     * Rationale: Unknown files should not be claimed.
     */
    @Test
    void detectEcosystem_unknownExtension_isEmpty() {
        assertThat(filter.detectEcosystem("unknown.txt")).isEmpty();
    }

    /**
     * Goal: Verify the filter name is "Annatto".
     * Rationale: The name identifies this filter in the host.
     */
    @Test
    void getName_returnsAnnatto() {
        assertThat(filter.getName()).isEqualTo("Annatto");
    }
}
