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
package io.spicelabs.annatto.crates;

/**
 * Documents known quirks of the Crates.io ecosystem.
 *
 * <ul>
 *   <li>TOML format: {@code Cargo.toml} uses TOML as its configuration format, which
 *       requires a TOML parser for metadata extraction.</li>
 *   <li>Feature flags enabling conditional dependencies: dependencies may be gated behind
 *       feature flags, making them optional unless the feature is explicitly enabled.</li>
 *   <li>Build-dependencies: the {@code [build-dependencies]} section declares dependencies
 *       needed only at build time, separate from runtime dependencies.</li>
 *   <li>Renamed dependencies ({@code package = "actual-name"}): a dependency may be listed
 *       under an alias with the real crate name specified via the {@code package} key.</li>
 *   <li>{@code edition} field: the Rust edition (e.g., {@code 2018}, {@code 2021}) affects
 *       language semantics but does not directly affect dependency resolution.</li>
 *   <li>No {@code Cargo.lock} in published crates: library crates published to crates.io
 *       do not include a {@code Cargo.lock} file, so exact transitive dependency versions
 *       are not recorded in the artifact.</li>
 * </ul>
 */
public record CratesQuirks() {
}
