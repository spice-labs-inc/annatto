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
package io.spicelabs.annatto.pypi;

/**
 * Documents known quirks and edge cases in the PyPI package format.
 * Each quirk is verified by corresponding tests in {@code PypiMetadataExtractorTest}.
 *
 * <h3>Q1: Name Normalization (PEP 503)</h3>
 * <p>PyPI package names are case-insensitive and treat hyphens ({@code -}), underscores
 * ({@code _}), and periods ({@code .}) as equivalent. Runs of these characters are collapsed
 * to a single hyphen. For example, {@code Flask-SocketIO}, {@code flask_socketio}, and
 * {@code Flask.SocketIO} all normalize to {@code flask-socketio}.
 * See: {@code PypiMetadataExtractorTest#normalizeName_*}</p>
 *
 * <h3>Q2: Sdist vs Wheel Metadata Locations</h3>
 * <p>Wheel ({@code .whl}) archives are ZIP files containing metadata at
 * {@code <name>-<version>.dist-info/METADATA}. Sdist ({@code .tar.gz}) archives are
 * gzip-compressed tars containing metadata at {@code <name>-<version>/PKG-INFO}.
 * Both files use the same RFC 822 header format.
 * See: {@code PypiMetadataExtractorTest#isDistInfoMetadata_*},
 * {@code PypiMetadataExtractorTest#isPkgInfo_*}</p>
 *
 * <h3>Q3: pyproject.toml (PEP 621)</h3>
 * <p>Modern projects declare metadata in the {@code [project]} table of {@code pyproject.toml}
 * instead of {@code setup.py}. However, published archives (both wheels and sdists) always
 * include pre-built {@code METADATA} / {@code PKG-INFO} files, so the extractor reads those
 * directly and never needs to parse {@code pyproject.toml}.
 * Verified by all parameterized source-of-truth tests in
 * {@code PypiMetadataExtractorTest#extractName_matchesSourceOfTruth} etc. —
 * all 50 packages are parsed successfully using only METADATA/PKG-INFO.</p>
 *
 * <h3>Q4: RFC 822 Header Format with Multi-line Continuation</h3>
 * <p>Core metadata files use RFC 822 (email-style) header format. Lines starting with
 * whitespace are continuation lines of the previous header. Headers like
 * {@code Requires-Dist} and {@code Classifier} appear multiple times.
 * See: {@code PypiMetadataExtractorTest#parseRfc822Headers_*}</p>
 *
 * <h3>Q5: License Classifiers as Secondary Source</h3>
 * <p>License information has three sources with decreasing priority:
 * {@code License-Expression} header (SPDX), {@code License} header (skip "UNKNOWN"),
 * and {@code Classifier: License :: OSI Approved :: ...} entries (joined with " OR ").
 * See: {@code PypiMetadataExtractorTest#extractLicense_*}</p>
 *
 * <h3>Q6: Author-email Combined "Name &lt;email&gt;" Format</h3>
 * <p>The {@code Author-email} header may contain combined name and email in the format
 * {@code "Name <email>"}. When the {@code Author} header is absent or "UNKNOWN",
 * the name part is extracted from this combined format as a fallback.
 * See: {@code PypiMetadataExtractorTest#extractNameFromEmailField_*}</p>
 *
 * <h3>Q7: Requires-Dist with Environment Markers</h3>
 * <p>Dependencies in {@code Requires-Dist} may include environment markers after a
 * semicolon (e.g., {@code foo>=1.0 ; python_version>="3.8"}). Extras may appear in
 * brackets (e.g., {@code requests[security]}). Both are stripped during extraction;
 * all dependencies are scoped as "runtime".
 * See: {@code PypiMetadataExtractorTest#parseRequiresDist_*}</p>
 *
 * <h3>Q8: Minimal Packages</h3>
 * <p>Only {@code Metadata-Version}, {@code Name}, and {@code Version} are required
 * in PyPI metadata. All other fields are optional. The extractor returns
 * {@code Optional.empty()} for absent fields. The sentinel value "UNKNOWN" is
 * treated as absent.
 * See: parameterized source-of-truth tests on minimal-metadata packages (colorama, wcwidth,
 * wrapt, decorator) and {@code PypiMetadataExtractorTest#extractPublisher_noPublisherReturnsEmpty},
 * {@code PypiMetadataExtractorTest#extractLicense_noLicenseReturnsEmpty},
 * {@code PypiMetadataExtractorTest#extractLicense_unknownSkipped}</p>
 */
public record PypiQuirks() {
}
