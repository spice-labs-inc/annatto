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
 * Go module ecosystem support for Annatto.
 *
 * <p>Handles extraction of metadata from Go module artifacts: {@code .zip} archives
 * from the Go module proxy containing {@code module@version/go.mod} and source files.
 * Produces PURLs of the form {@code pkg:golang/namespace/name@version}.</p>
 *
 * <p>The extraction pipeline:</p>
 * <ol>
 *   <li>Open ZIP archive via {@link java.util.zip.ZipInputStream}</li>
 *   <li>Find root-level {@code go.mod} entry (immediate child of {@code module@version/})</li>
 *   <li>Extract version from zip entry name prefix</li>
 *   <li>Parse {@code go.mod} for module path and require directives</li>
 *   <li>Map to normalized {@link io.spicelabs.annatto.common.MetadataResult}</li>
 * </ol>
 *
 * @see io.spicelabs.annatto.go.GoHandler
 * @see io.spicelabs.annatto.go.GoMetadataExtractor
 * @see io.spicelabs.annatto.go.GoQuirks
 */
package io.spicelabs.annatto.go;
