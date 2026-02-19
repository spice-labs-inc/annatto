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
 * RubyGems ecosystem support for Annatto.
 *
 * <h2>Artifact Format</h2>
 * <p>A {@code .gem} file is a plain tar archive (NOT gzip-wrapped) containing:
 * (tested by {@code RubygemsMetadataExtractorTest.gem_rake_zeroDeps},
 * {@code AnnattoProcessFilterTest.detectEcosystem_gem_isRubygems})</p>
 * <ul>
 *   <li>{@code metadata.gz} &mdash; gzip-compressed YAML gemspec with package metadata
 *       (tested by {@code RubygemsMetadataExtractorTest.isMetadataGz_standardEntry})</li>
 *   <li>{@code data.tar.gz} &mdash; gzip-compressed tar of the actual library source files</li>
 *   <li>{@code checksums.yaml.gz} &mdash; gzip-compressed checksums</li>
 * </ul>
 *
 * <h2>Extraction Pipeline</h2>
 * <ol>
 *   <li>Open {@code .gem} as plain tar (no GZIPInputStream)</li>
 *   <li>Locate and decompress {@code metadata.gz} entry</li>
 *   <li>Strip Ruby YAML tags ({@code !ruby/\S+}) from raw YAML</li>
 *   <li>Parse with SnakeYAML {@code SafeConstructor}</li>
 *   <li>Map fields to normalized {@link io.spicelabs.annatto.common.MetadataResult}</li>
 * </ol>
 * <p>(pipeline tested by 9 parameterized SoT tests in {@code RubygemsMetadataExtractorTest.extract*_matchesSourceOfTruth})</p>
 *
 * <h2>PURL</h2>
 * <p>{@code pkg:gem/name@version}
 * (tested by {@code PurlBuilderTest.forRubyGems_simple})</p>
 *
 * @see RubygemsMetadataExtractor
 * @see RubygemsHandler
 * @see RubygemsQuirks
 * @see <a href="https://guides.rubygems.org/specification-reference/">RubyGems Specification Reference</a>
 */
package io.spicelabs.annatto.rubygems;
