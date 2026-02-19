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
package io.spicelabs.annatto.cocoapods;

/**
 * Documents CocoaPods ecosystem quirks that affect metadata extraction.
 * This is an intentionally empty record — it serves as living documentation.
 *
 * <ul>
 *   <li><b>Q1: .podspec is Ruby code → only .podspec.json supported</b> — Podspec files are
 *       executable Ruby DSL. We only process {@code .podspec.json} (the JSON serialization)
 *       which is always available from the CocoaPods trunk API.
 *       Tested by: {@code extractFromJson_validPodspec}.</li>
 *
 *   <li><b>Q2: License polymorphism</b> — The license field can be a plain string ({@code "MIT"})
 *       or an object ({@code {"type": "MIT", "text": "..."}). We extract the "type" key from
 *       objects.
 *       Tested by: {@code extractLicense_stringDirect}, {@code extractLicense_objectType}.</li>
 *
 *   <li><b>Q3: Authors polymorphism</b> — The authors field can be an object map
 *       ({@code {"Name": "email"}}), a string, or an array. We extract names from map keys
 *       or array/string values. Fallback to singular "author" field.
 *       Tested by: {@code extractPublisher_mapKeys}, {@code extractPublisher_string},
 *       {@code extractPublisher_array}.</li>
 *
 *   <li><b>Q4: Subspec dependency aggregation</b> — A pod may declare subspecs with their own
 *       dependencies. If {@code default_subspecs} is present, only those subspecs' deps are
 *       included; otherwise all subspecs are included. Self-referencing deps (pod/subspec)
 *       are filtered.
 *       Tested by: {@code extractDeps_subspecDefaultsOnly},
 *       {@code extractDeps_selfReferencingFiltered}.</li>
 *
 *   <li><b>Q5: Dependency version arrays</b> — Version constraints are arrays of strings.
 *       Empty array means "any version" (null versionConstraint). Multiple entries are joined
 *       with ", ".
 *       Tested by: {@code extractDeps_emptyVersionIsNull},
 *       {@code extractDeps_multipleConstraints}.</li>
 *
 *   <li><b>Q6: No publishedAt</b> — The podspec.json does not contain a publication timestamp.
 *       publishedAt is always null.
 *       Tested by: {@code extractPublishedAt_alwaysNull}.</li>
 *
 *   <li><b>Q7: PURL name preserves case</b> — CocoaPods pod names are case-sensitive and the
 *       PURL must preserve the original casing: {@code pkg:cocoapods/AFNetworking@4.0.1}.
 *       Tested by: {@code getPurls_preservesCase}.</li>
 * </ul>
 */
public record CocoapodsQuirks() {
}
