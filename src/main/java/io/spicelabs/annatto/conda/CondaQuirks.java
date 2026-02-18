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
package io.spicelabs.annatto.conda;

/**
 * Documents ecosystem-specific quirks for Conda artifacts.
 *
 * <h2>Known Quirks</h2>
 * <ul>
 *   <li><b>Two archive formats</b> &mdash; Conda has two distinct package formats:
 *       the legacy {@code .tar.bz2} format (a bzip2-compressed tar archive) and the modern
 *       {@code .conda} format (a zip container with zstd-compressed inner archives). The
 *       {@code .conda} format separates metadata from package data for faster extraction.
 *       Extractors must support both formats.</li>
 *   <li><b>Channel as identity context</b> &mdash; The channel (e.g. {@code conda-forge},
 *       {@code defaults}, {@code bioconda}) is a critical part of package identity. The same
 *       package name and version may exist in multiple channels with different contents. The
 *       channel should be included as the PURL namespace.</li>
 *   <li><b>Build string</b> &mdash; Conda packages include a build string (e.g.
 *       {@code py39h1234567_0}) that encodes the Python version, build hash, and build
 *       number. The build string disambiguates multiple builds of the same version and is
 *       part of the full package identifier.</li>
 *   <li><b>Subdir for platform</b> &mdash; The {@code subdir} field (e.g.
 *       {@code linux-64}, {@code osx-arm64}, {@code noarch}) identifies the target platform.
 *       {@code noarch} packages are platform-independent. The subdir affects where a package
 *       is indexed in the channel's repodata.</li>
 *   <li><b>Constrains field</b> &mdash; The {@code constrains} field in {@code index.json}
 *       specifies version constraints on other packages that are only enforced if those
 *       packages happen to be installed. Unlike {@code depends}, constrained packages are
 *       not pulled in automatically.</li>
 * </ul>
 */
public record CondaQuirks() {
    // Intentionally empty — this record exists solely for its Javadoc documentation.
}
