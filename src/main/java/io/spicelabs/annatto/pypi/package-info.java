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
 * PyPI ecosystem support for Annatto.
 *
 * <p>Handles extraction of metadata from PyPI artifacts in two formats:</p>
 * <ul>
 *   <li>{@code .whl} — ZIP wheels containing {@code *.dist-info/METADATA}</li>
 *   <li>{@code .tar.gz} — gzip-compressed tar sdists containing {@code PKG-INFO}</li>
 * </ul>
 *
 * <p>Both metadata files use RFC 822 (email-style) header format with support for
 * multi-line continuation and repeated headers (e.g., {@code Requires-Dist},
 * {@code Classifier}).</p>
 *
 * <p>Produces PURLs of the form {@code pkg:pypi/normalized-name@version} where
 * the name is normalized per PEP 503 (lowercase, runs of [-_.] collapsed to single hyphen).</p>
 *
 * @see PypiMetadataExtractor
 * @see PypiHandler
 * @see PypiQuirks
 */
package io.spicelabs.annatto.pypi;
