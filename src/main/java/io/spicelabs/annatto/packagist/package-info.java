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
 * <p>A Packagist artifact is a {@code .zip} archive (typically a GitHub zipball) containing
 * a {@code composer.json} manifest at the root or within a single top-level directory.
 * (tested by {@code PackagistMetadataExtractorTest.package_monolog_runtimeDeps},
 * {@code AnnattoProcessFilterTest.detectEcosystem_zipWithoutAtV_isPackagist})</p>
 *
 * <h2>Extraction Pipeline</h2>
 * <ol>
 *   <li>Open {@code ZipInputStream} (JDK {@code java.util.zip})</li>
 *   <li>Scan zip entries for {@code composer.json} at root or one level deep
 *       ({@link io.spicelabs.annatto.packagist.PackagistMetadataExtractor#isComposerJson})</li>
 *   <li>Read entry to String (10 MB size limit, path traversal rejection)</li>
 *   <li>Parse JSON via Gson {@code JsonParser}</li>
 *   <li>Extract name, simpleName, version, description, license, publisher, dependencies</li>
 *   <li>Filter platform dependencies ({@code php}, {@code ext-*}, {@code lib-*}) from
 *       require/require-dev sections
 *       (tested by {@code PackagistMetadataExtractorTest.package_symfony_console_platformDepsFiltered})</li>
 * </ol>
 * <p>(pipeline tested by 9 parameterized SoT tests in {@code PackagistMetadataExtractorTest.extract*_matchesSourceOfTruth})</p>
 *
 * <h2>PURL</h2>
 * <p>{@code pkg:composer/vendor/name@version} — empty when version is absent from
 * {@code composer.json} (which is the common case; see {@link io.spicelabs.annatto.packagist.PackagistQuirks Q1}).
 * (tested by {@code PurlBuilderTest.forPackagist_splitsVendor},
 * {@code PackagistMetadataExtractorTest.package_version_absent})</p>
 *
 * @see io.spicelabs.annatto.packagist.PackagistHandler
 * @see io.spicelabs.annatto.packagist.PackagistMetadataExtractor
 * @see io.spicelabs.annatto.packagist.PackagistMemento
 * @see io.spicelabs.annatto.packagist.PackagistQuirks
 * @see <a href="https://getcomposer.org/doc/04-schema.md">Composer Schema Documentation</a>
 */
package io.spicelabs.annatto.packagist;
