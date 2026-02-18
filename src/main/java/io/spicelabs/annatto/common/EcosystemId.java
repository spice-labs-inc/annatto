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

import org.jetbrains.annotations.NotNull;

/**
 * Identifies the 11 package ecosystems supported by Annatto.
 * Each ecosystem has a display name and a PURL type string.
 */
public enum EcosystemId {

    NPM("npm", "npm"),
    PYPI("PyPI", "pypi"),
    GO("Go Modules", "golang"),
    CRATES("Crates.io", "cargo"),
    RUBYGEMS("RubyGems", "gem"),
    PACKAGIST("Packagist", "composer"),
    CONDA("Conda", "conda"),
    COCOAPODS("CocoaPods", "cocoapods"),
    CPAN("CPAN", "cpan"),
    HEX("Hex", "hex"),
    LUAROCKS("LuaRocks", "luarocks");

    private final String displayName;
    private final String purlType;

    EcosystemId(@NotNull String displayName, @NotNull String purlType) {
        this.displayName = displayName;
        this.purlType = purlType;
    }

    /**
     * @return the human-readable name for this ecosystem
     */
    public @NotNull String displayName() {
        return displayName;
    }

    /**
     * @return the PURL type string for this ecosystem (e.g., "npm", "pypi", "cargo")
     */
    public @NotNull String purlType() {
        return purlType;
    }
}
