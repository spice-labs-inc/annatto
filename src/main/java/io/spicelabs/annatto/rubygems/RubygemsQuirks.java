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
 * <ul>
 *   <li><b>metadata.gz is YAML with Ruby-specific tags</b> &mdash; The gemspec metadata uses
 *       custom YAML tags such as {@code !ruby/object:Gem::Requirement},
 *       {@code !ruby/object:Gem::Dependency}, and {@code !ruby/object:Gem::Version}.
 *       SnakeYAML must be configured to handle these gracefully (e.g. by using a custom
 *       constructor that ignores or safely resolves unknown tags) rather than rejecting them.</li>
 *   <li><b>Platform field</b> &mdash; Gems may specify a {@code platform} field (e.g.
 *       {@code ruby}, {@code java}, {@code x86_64-linux}). Platform-specific gems append the
 *       platform to the filename (e.g. {@code nokogiri-1.15.0-x86_64-linux.gem}) and may
 *       contain native extensions or precompiled shared libraries.</li>
 *   <li><b>add_dependency vs add_development_dependency</b> &mdash; Gemspec distinguishes
 *       between runtime dependencies ({@code add_dependency} / {@code add_runtime_dependency})
 *       and development-only dependencies ({@code add_development_dependency}). Only runtime
 *       dependencies should typically be reflected in the SBOM output.</li>
 * </ul>
 */
public record RubygemsQuirks() {
    // Intentionally empty — this record exists solely for its Javadoc documentation.
}
