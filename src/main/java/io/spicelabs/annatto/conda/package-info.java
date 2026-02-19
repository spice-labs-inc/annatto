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
 * Conda ecosystem support for Annatto.
 *
 * <h2>Extraction Pipeline</h2>
 * <ol>
 *   <li>Detect format from filename extension ({@code .conda} or {@code .tar.bz2})
 *       (tested by {@code AnnattoProcessFilterTest.detectEcosystem_conda_isConda},
 *       {@code AnnattoProcessFilterTest.detectEcosystem_tarBz2_isConda})</li>
 *   <li>Extract {@code info/index.json} (required) and {@code info/about.json} (optional)</li>
 *   <li>Parse identity from index.json: name, version, build, subdir, license, timestamp, depends</li>
 *   <li>Parse description from about.json: summary preferred, description fallback</li>
 *   <li>Build normalized {@link io.spicelabs.annatto.common.MetadataResult}</li>
 * </ol>
 * <p>(pipeline tested by 11 parameterized SoT tests in {@code CondaMetadataExtractorTest.extract*_matchesSourceOfTruth})</p>
 *
 * <h2>Artifact Formats</h2>
 * <ul>
 *   <li><b>Modern ({@code .conda})</b> &mdash; A ZIP container with zstd-compressed inner
 *       archives: {@code info-*.tar.zst} (metadata) and {@code pkg-*.tar.zst} (package data).
 *       (tested by {@code CondaMetadataExtractorTest.package_numpy_conda_manyDeps})</li>
 *   <li><b>Legacy ({@code .tar.bz2})</b> &mdash; A bzip2-compressed tar archive.
 *       (tested by {@code CondaMetadataExtractorTest.package_requests_tarBz2_moderateDeps})</li>
 * </ul>
 *
 * <h2>PURL Format</h2>
 * <p>{@code pkg:conda/name@version?build=<build>&subdir=<subdir>}
 * (tested by {@code PurlBuilderTest.forConda_withBothQualifiers})</p>
 * <p>No namespace (channel is external context, not in package file).
 * Build and subdir are qualifiers per purl-spec.
 * (tested by {@code PurlBuilderTest.forConda_noNamespace})</p>
 *
 * @see CondaMetadataExtractor
 * @see CondaHandler
 * @see CondaQuirks
 * @see <a href="https://docs.conda.io/projects/conda/en/latest/user-guide/concepts/packages.html">Conda Package Concepts</a>
 */
package io.spicelabs.annatto.conda;
