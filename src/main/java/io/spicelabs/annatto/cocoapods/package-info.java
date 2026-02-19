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
 * CocoaPods ecosystem support for Annatto.
 *
 * <h2>Extraction Pipeline</h2>
 * <ol>
 *   <li>Read {@code .podspec.json} as plain JSON (Q1: only JSON supported)
 *       (tested by {@code AnnattoProcessFilterTest.detectEcosystem_podspecJson_isCocoapods})</li>
 *   <li>Parse with Gson</li>
 *   <li>Handle license/author/dependency polymorphism (Q2, Q3, Q4, Q5)
 *       (tested by {@code CocoapodsMetadataExtractorTest.extractLicense_stringDirect},
 *       {@code CocoapodsMetadataExtractorTest.extractLicense_objectType},
 *       {@code CocoapodsMetadataExtractorTest.extractPublisher_mapKeys},
 *       {@code CocoapodsMetadataExtractorTest.extractPublisher_string},
 *       {@code CocoapodsMetadataExtractorTest.extractPublisher_array})</li>
 *   <li>Build MetadataResult with subspec dependency aggregation
 *       (tested by {@code CocoapodsMetadataExtractorTest.package_moya_defaultSubspecs},
 *       {@code CocoapodsMetadataExtractorTest.package_firebase_manySubspecs})</li>
 * </ol>
 * <p>(pipeline tested by 9 parameterized SoT tests in {@code CocoapodsMetadataExtractorTest.extract*_matchesSourceOfTruth})</p>
 *
 * <h2>PURL Format</h2>
 * <p>{@code pkg:cocoapods/Name@version} — no namespace, case-sensitive name (Q7).
 * (tested by {@code PurlBuilderTest.forCocoapods_simple},
 * {@code CocoapodsMetadataExtractorTest.package_lottieIos_caseSensitive})</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link io.spicelabs.annatto.cocoapods.CocoapodsMetadataExtractor} — stateless extraction</li>
 *   <li>{@link io.spicelabs.annatto.cocoapods.CocoapodsHandler} — lifecycle handler</li>
 *   <li>{@link io.spicelabs.annatto.cocoapods.CocoapodsQuirks} — ecosystem-specific documentation</li>
 * </ul>
 */
package io.spicelabs.annatto.cocoapods;
