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
 * CPAN ecosystem support for Annatto.
 *
 * <h2>Extraction Pipeline</h2>
 * <ol>
 *   <li>Decompress .tar.gz (GZIPInputStream + TarArchiveInputStream)</li>
 *   <li>Find META.json (preferred) or META.yml at top level</li>
 *   <li>Parse with Gson (JSON) or SnakeYAML SafeConstructor (YAML)</li>
 *   <li>Build MetadataResult with dependencies from prereqs structure</li>
 * </ol>
 *
 * <h2>PURL Format</h2>
 * <p>{@code pkg:cpan/Distribution-Name@version} — no namespace (PAUSE ID unavailable).</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link io.spicelabs.annatto.cpan.CpanMetadataExtractor} — stateless extraction</li>
 *   <li>{@link io.spicelabs.annatto.cpan.CpanHandler} — lifecycle handler</li>
 *   <li>{@link io.spicelabs.annatto.cpan.CpanQuirks} — ecosystem-specific documentation</li>
 * </ul>
 */
package io.spicelabs.annatto.cpan;
