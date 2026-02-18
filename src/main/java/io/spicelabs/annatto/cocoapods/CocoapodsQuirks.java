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
 *
 * <ul>
 *   <li><b>.podspec is Ruby code (not static data)</b> — podspec files are executable Ruby,
 *       so they cannot be reliably parsed without evaluating Ruby. Prefer {@code .podspec.json}
 *       when available, as it provides the same metadata in a machine-readable JSON format.</li>
 *   <li><b>Prefer .podspec.json</b> — the JSON variant is a serialised representation of the
 *       evaluated podspec. When the CocoaPods trunk API is used, JSON is always available.</li>
 *   <li><b>Subspecs for nested components</b> — a single pod can declare subspecs, each with
 *       its own source files, dependencies, and platform requirements. These effectively act
 *       as sub-packages within a single podspec.</li>
 *   <li><b>Source field pointing to actual archive</b> — the {@code source} hash in the podspec
 *       indicates where the actual source archive lives (e.g. a Git repo, an HTTP tarball, or
 *       an SVN location). This is distinct from the CocoaPods specs repo entry.</li>
 *   <li><b>Platform requirements</b> — pods may declare minimum deployment targets for one or
 *       more Apple platforms (iOS, macOS, tvOS, watchOS, visionOS). A pod is not necessarily
 *       usable on all platforms.</li>
 * </ul>
 */
public record CocoapodsQuirks() {
}
