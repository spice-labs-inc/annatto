/*
 * Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spicelabs.annatto.rubygems;

/**
 * Documents ecosystem-specific quirks for RubyGems artifacts.
 *
 * <h2>Known Quirks</h2>
 *
 * <p><b>Q1 — YAML with Ruby-specific tags.</b>
 * The gemspec metadata inside {@code metadata.gz} uses custom YAML tags such as
 * {@code !ruby/object:Gem::Requirement}, {@code !ruby/object:Gem::Dependency},
 * and {@code !ruby/object:Gem::Version}. These are stripped via regex before parsing
 * with SnakeYAML {@code SafeConstructor}.
 * Tests: {@code stripRubyYamlTags_removesAllGemTags}, all 50 SoT tests.</p>
 *
 * <p><b>Q2 — Description fallback (summary → description).</b>
 * The extractor prefers the {@code summary} field (short, one-line) as the description,
 * falling back to the longer {@code description} field if summary is nil or empty.
 * This matches the PyPI pattern of preferring the short summary.
 * Tests: {@code extractDescription_prefersSummary}, {@code extractDescription_fallsBackToDescription}.</p>
 *
 * <p><b>Q3 — Runtime vs development dependencies.</b>
 * Gemspec distinguishes between runtime dependencies ({@code :runtime}) and development
 * dependencies ({@code :development}). Both are included: runtime maps to scope "runtime",
 * development maps to scope "dev".
 * Tests: {@code mapDependencyType_runtime}, {@code mapDependencyType_development},
 * {@code gem_rspec_core_mixedDependencyScopes}.</p>
 *
 * <p><b>Q4 — Version constraint reconstruction.</b>
 * Ruby gem requirements are stored as arrays of {@code [operator, version]} pairs in YAML.
 * These are reconstructed into strings like {@code "~> 3.13.0"} or {@code ">= 2.0, < 3.2"}.
 * The default requirement {@code ">= 0"} maps to null (no constraint).
 * Tests: {@code reconstructVersionConstraint_tildeArrow},
 * {@code reconstructVersionConstraint_compound},
 * {@code reconstructVersionConstraint_defaultIsNull}.</p>
 *
 * <p><b>Q5 — Platform field ignored.</b>
 * Gems may specify a {@code platform} field (e.g., {@code ruby}, {@code java},
 * {@code x86_64-linux}). This field is not included in the metadata output.
 * Tests: implicit in all 50 SoT tests (platform not in schema).</p>
 *
 * <p><b>Q6 — License array joined with " OR ".</b>
 * Gems can have multiple licenses in their {@code licenses} array. These are joined
 * with {@code " OR "} to form an SPDX-like expression. An empty or null list
 * maps to null license.
 * Tests: {@code joinLicenses_single}, {@code joinLicenses_multiple},
 * {@code joinLicenses_empty_returnsNull}.</p>
 */
public record RubygemsQuirks() {
    // Intentionally empty — this record exists solely for its Javadoc documentation.
}
