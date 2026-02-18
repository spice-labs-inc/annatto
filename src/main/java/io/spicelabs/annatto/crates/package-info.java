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

/**
 * Crates.io ecosystem support for Annatto.
 *
 * <p>Handles extraction of metadata from Crates.io artifacts ({@code .crate}
 * gzip tar archives containing {@code Cargo.toml}). Uses a two-step extraction
 * pattern:</p>
 * <ol>
 *   <li>{@link io.spicelabs.annatto.crates.CratesMetadataExtractor#extractCargoTomlFromCrate
 *       extractCargoTomlFromCrate} — extracts raw Cargo.toml text from the archive</li>
 *   <li>{@link io.spicelabs.annatto.crates.CratesMetadataExtractor#buildMetadataResult
 *       buildMetadataResult} — parses TOML and maps to normalized MetadataResult</li>
 * </ol>
 *
 * <p>Produces PURLs of the form {@code pkg:cargo/name@version}.</p>
 *
 * @see io.spicelabs.annatto.crates.CratesHandler
 * @see io.spicelabs.annatto.crates.CratesMetadataExtractor
 * @see io.spicelabs.annatto.crates.CratesQuirks
 */
package io.spicelabs.annatto.crates;
