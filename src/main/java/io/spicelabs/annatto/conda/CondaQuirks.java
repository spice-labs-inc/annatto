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
 * <h2>Quirks</h2>
 *
 * <p><b>Q1: Two archive formats (.conda vs .tar.bz2)</b> &mdash; Conda has two distinct
 * package formats: the legacy {@code .tar.bz2} format (a bzip2-compressed tar archive) and
 * the modern {@code .conda} format (a ZIP container with zstd-compressed inner archives).
 * The {@code .conda} format separates metadata ({@code info-*.tar.zst}) from package data
 * ({@code pkg-*.tar.zst}) for faster extraction. Both formats contain {@code info/index.json}
 * as the primary metadata file. Tests: {@code detectFormat_*}, all 50 SoT tests,
 * {@code package_numpy_conda_manyDeps}, {@code package_requests_tarBz2_moderateDeps},
 * property {@code detectFormat_alwaysDeterminesFormatFromExtension}.</p>
 *
 * <p><b>Q2: Channel not available from package file</b> &mdash; The channel (e.g.
 * {@code conda-forge}, {@code defaults}) is external context not embedded in the package
 * archive. Per purl-spec, Conda PURLs have no namespace (no channel). Publisher is always
 * null (no author field in conda metadata). Tests: {@code forConda_noNamespace},
 * {@code buildMetadataResult_publisherAlwaysNull}.</p>
 *
 * <p><b>Q3: Build string as disambiguation (PURL qualifier)</b> &mdash; Conda packages
 * include a build string (e.g. {@code py312hc5e2394_0}) that disambiguates multiple builds
 * of the same version. It is included as a PURL qualifier {@code ?build=...}. Tests:
 * {@code extractBuild_matchesSourceOfTruth}, {@code forConda_withBuildQualifier},
 * {@code getPurls_condaFormat_includesBuildQualifier}.</p>
 *
 * <p><b>Q4: Subdir/platform targeting</b> &mdash; The {@code subdir} field (e.g.
 * {@code linux-64}, {@code osx-arm64}, {@code noarch}) identifies the target platform.
 * It is included as a PURL qualifier {@code ?subdir=...}. Tests:
 * {@code extractSubdir_matchesSourceOfTruth}, {@code forConda_withSubdirQualifier},
 * {@code package_noarch_python}, {@code package_linux64_platformSpecific}.</p>
 *
 * <p><b>Q5: Constrains vs depends (constrains ignored)</b> &mdash; The {@code constrains}
 * field in {@code index.json} specifies version constraints on other packages that are only
 * enforced if those packages happen to be installed. Unlike {@code depends}, constrained
 * packages are not pulled in automatically. Annatto ignores constrains entirely. Tests:
 * {@code package_constrains_ignored}, implicit in all 50 SoT tests.</p>
 *
 * <p><b>Q6: Match spec dependency format</b> &mdash; Dependencies in {@code index.json}
 * are match spec strings: {@code "name version_constraint [build_string]"}. The name is the
 * first token, version constraint is everything after the first space (build string included
 * by native tools). All conda dependencies are scope "runtime" (no dev deps). Tests:
 * {@code parseMatchSpec_*}, {@code extractDependencies_matchSourceOfTruth},
 * properties {@code parseMatchSpec_*}.</p>
 *
 * <p><b>Q7: Description from about.json (summary preferred, may be absent)</b> &mdash;
 * Description comes from {@code info/about.json}: {@code summary} field preferred, fallback
 * to {@code description}. If about.json is absent, description is null. Tests:
 * {@code extractDescription_*}, {@code package_noAboutJson},
 * property {@code extractDescription_summaryAlwaysPreferred}.</p>
 *
 * <p><b>Q8: Timestamp in milliseconds</b> &mdash; The {@code timestamp} field in
 * {@code index.json} is milliseconds since epoch. Converted to ISO 8601 for output.
 * Absent in some packages. Tests: {@code timestampConversion_*},
 * {@code extractPublishedAt_matchesSourceOfTruth},
 * property {@code timestampConversion_outputIsIso8601}.</p>
 */
public record CondaQuirks() {
    // Intentionally empty — this record exists solely for its Javadoc documentation.
}
