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
 *   <li><b>Q1: TOML format</b> — {@code Cargo.toml} uses TOML as its configuration format,
 *       requiring a TOML parser (tomlj) for metadata extraction. Published crates use a
 *       "normalized" Cargo.toml with dotted table headers (e.g., {@code [dependencies.serde]}).
 *       Tests: all 50 parameterized source-of-truth tests in
 *       {@code CratesMetadataExtractorTest#extractName_matchesSourceOfTruth},
 *       {@code CratesMetadataExtractorTest#parseDependencies_simpleVersionString}</li>
 *
 *   <li><b>Q2: Feature flags / optional dependencies</b> — dependencies may be gated behind
 *       feature flags via {@code optional = true}. Optional dependencies retain scope
 *       {@code "runtime"} in the extracted metadata.
 *       Tests: {@code CratesMetadataExtractorTest#crate_serde_optionalDependency},
 *       {@code CratesMetadataExtractorTest#parseDependencies_optionalScopeIsRuntime}</li>
 *
 *   <li><b>Q3: Build-dependencies</b> — the {@code [build-dependencies]} section declares
 *       dependencies needed only at build time, extracted with scope {@code "build"}.
 *       Tests: {@code CratesMetadataExtractorTest#crate_openssl_sys_buildDeps},
 *       {@code CratesMetadataExtractorTest#parseDependencies_buildDeps}</li>
 *
 *   <li><b>Q4: Renamed dependencies</b> — a dependency may use
 *       {@code package = "actual-name"} to specify the real crate name when the TOML
 *       key is an alias. The extractor uses the {@code package} value as the name.
 *       Tests: {@code CratesMetadataExtractorTest#crate_reqwest_renamedDependencies},
 *       {@code CratesMetadataExtractorTest#parseDependencies_renamedPackage}</li>
 *
 *   <li><b>Q5: Edition field</b> — the Rust edition (e.g., {@code 2018}, {@code 2021})
 *       affects language semantics but does not affect dependency resolution. It is
 *       not included in the extracted metadata.
 *       Tests: implicit in all 50 source-of-truth tests (edition is ignored correctly)</li>
 *
 *   <li><b>Q6: No Cargo.lock</b> — library crates published to crates.io do not include
 *       a {@code Cargo.lock} file, so exact transitive dependency versions are not
 *       recorded in the artifact. Only direct dependencies from Cargo.toml are extracted.
 *       Tests: implicit in all 50 source-of-truth tests (no lock-file dependencies appear)</li>
 * </ul>
 */
public record CratesQuirks() {
}
