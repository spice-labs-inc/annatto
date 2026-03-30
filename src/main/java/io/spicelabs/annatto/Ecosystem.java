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

/**
 * Supported language package ecosystems.
 *
 * <p>Each ecosystem maps to one or more MIME types and has specific
 * detection logic in EcosystemRouter.
 */
public enum Ecosystem {
    /** npm - JavaScript/Node.js packages (.tgz) */
    NPM,

    /** PyPI - Python packages (.whl, .tar.gz) */
    PYPI,

    /** Go Modules - Go packages (.zip) */
    GO,

    /** Crates.io - Rust packages (.crate) */
    CRATES,

    /** RubyGems - Ruby packages (.gem) */
    RUBYGEMS,

    /** Packagist - PHP packages (.zip) */
    PACKAGIST,

    /** Conda - Conda packages (.conda, .tar.bz2) */
    CONDA,

    /** CocoaPods - iOS/macOS packages (.podspec.json) */
    COCOAPODS,

    /** CPAN - Perl packages (.tar.gz) */
    CPAN,

    /** Hex - Elixir/Erlang packages (.tar) */
    HEX,

    /** LuaRocks - Lua packages (.rock, .rockspec) */
    LUAROCKS
}
