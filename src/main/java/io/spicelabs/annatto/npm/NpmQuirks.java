/* Copyright 2026 Spice Labs, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.annatto.npm;

/**
 * Documents known quirks and edge cases in the npm package format.
 * Each quirk is verified by corresponding tests in {@code NpmMetadataExtractorTest}.
 *
 * <h3>Q1: Scoped Packages</h3>
 * <p>Scoped packages use {@code @scope/name} format. The scope (including {@code @}) becomes
 * the PURL namespace. The simple name is the part after the slash. Both scoped and unscoped
 * packages are valid. See: {@code NpmMetadataExtractorTest#extractSimpleName_*}</p>
 *
 * <h3>Q2: Author Field Formats</h3>
 * <p>The {@code author} field can be:</p>
 * <ul>
 *   <li>A string: {@code "Barney Rubble <b@rubble.com> (http://barnyrubble.tumblr.com/)"}</li>
 *   <li>An object: {@code {"name": "Barney Rubble", "email": "b@rubble.com", "url": "..."}}</li>
 *   <li>Absent entirely (fall back to {@code maintainers} or {@code contributors})</li>
 * </ul>
 * <p>String format parsing extracts just the name before any {@code <} or {@code (} characters.
 * See: {@code NpmMetadataExtractorTest#extractAuthor_*}</p>
 *
 * <h3>Q3: License Field Formats</h3>
 * <p>The {@code license} field can be:</p>
 * <ul>
 *   <li>Modern SPDX string: {@code "MIT"}, {@code "(MIT OR Apache-2.0)"}</li>
 *   <li>Object: {@code {"type": "MIT", "url": "..."}}</li>
 *   <li>Legacy {@code licenses} array: {@code [{"type": "MIT", "url": "..."}, ...]}</li>
 * </ul>
 * <p>Multiple licenses in the legacy array are joined with " OR ".
 * See: {@code NpmMetadataExtractorTest#extractLicense_*}</p>
 *
 * <h3>Q4: Dependency Types</h3>
 * <p>npm has four dependency fields, each with different semantics:</p>
 * <ul>
 *   <li>{@code dependencies} - required at runtime (scope: "runtime")</li>
 *   <li>{@code devDependencies} - only for development (scope: "dev")</li>
 *   <li>{@code peerDependencies} - expected to be provided by the consumer (scope: "peer")</li>
 *   <li>{@code optionalDependencies} - installed if possible, ignored on failure (scope: "optional")</li>
 * </ul>
 * <p>{@code bundledDependencies} (or {@code bundleDependencies}) lists dependency names
 * that are packed inside the tarball itself; these appear as regular entries in the tar.
 * See: {@code NpmMetadataExtractorTest#extractDependencies_*}</p>
 *
 * <h3>Q5: Registry-Added Fields</h3>
 * <p>Fields prefixed with {@code _} (e.g., {@code _id}, {@code _from}, {@code _resolved},
 * {@code _integrity}) are added by the npm registry or CLI and are not part of the
 * original package.json. These are ignored during extraction.</p>
 *
 * <h3>Q6: Archive Structure</h3>
 * <p>npm packages are gzip-compressed tar archives. Files are placed under a single directory,
 * typically named {@code package/}, so the metadata file is at {@code package/package.json}.
 * Some packages may use a different directory name (the package name itself). The extractor
 * matches any entry ending in {@code /package.json} at the first directory level.</p>
 *
 * <h3>Q7: Non-ASCII Content</h3>
 * <p>Package names, descriptions, and author names may contain non-ASCII characters
 * (including CJK, emoji, accented characters). All text is read as UTF-8.</p>
 *
 * <h3>Q8: Empty/Minimal Packages</h3>
 * <p>A valid npm package.json needs only {@code name} and {@code version}. All other fields
 * are optional. The extractor returns {@code Optional.empty()} for absent fields.</p>
 */
public record NpmQuirks() {
}
