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
 * Packagist (Composer/PHP) ecosystem support for Annatto.
 *
 * <h2>Artifact Format</h2>
 * <p>A Packagist artifact is a {@code .zip} archive containing the package source, with a
 * {@code composer.json} manifest at the root (or within a single top-level directory).</p>
 *
 * <h2>PURL</h2>
 * <p>{@code pkg:composer/vendor/name@version}</p>
 *
 * @see <a href="https://getcomposer.org/doc/04-schema.md">Composer Schema Documentation</a>
 */
package io.spicelabs.annatto.packagist;
