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
package io.spicelabs.annatto.packagist;

/**
 * Documents ecosystem-specific quirks for Packagist (Composer/PHP) artifacts.
 *
 * <h2>Q1: Version Absence</h2>
 * <p>The {@code version} field is almost always absent from {@code composer.json} because
 * Composer derives it from the git tag at release time. When version is absent, no PURL
 * can be generated — this is a legitimate limitation, not an error.</p>
 * <ul>
 *   <li>Tests: {@code extractVersion_matchesSourceOfTruth} (all 50 SoT packages),
 *       {@code getPurls_noVersion_returnsEmptyList},
 *       {@code buildMetadataResult_neverThrowsForValidJson}</li>
 * </ul>
 *
 * <h2>Q2: Vendor/Package Naming</h2>
 * <p>Packagist names always follow {@code vendor/package} format (e.g., {@code monolog/monolog}).
 * {@code simpleName} is the part after {@code /}. PURL namespace = vendor, name = package.</p>
 * <ul>
 *   <li>Tests: {@code extractSimpleName_vendorSlashName}, {@code extractSimpleName_noSlash},
 *       all 50 SoT name+simpleName tests,
 *       {@code extractSimpleName_alwaysNonEmpty} (property)</li>
 * </ul>
 *
 * <h2>Q3: require vs require-dev + Platform Filtering</h2>
 * <p>{@code require} maps to scope "runtime", {@code require-dev} maps to scope "dev".
 * Platform dependencies ({@code php}, {@code ext-*}, {@code lib-*}, {@code composer-plugin-api},
 * {@code composer-runtime-api}, {@code composer}) are excluded — they are not real packages.</p>
 * <ul>
 *   <li>Tests: {@code extractDependencies_requireIsRuntime},
 *       {@code extractDependencies_requireDevIsDev},
 *       {@code isPlatformDependency_php}, {@code isPlatformDependency_ext},
 *       {@code package_symfony_console_platformDepsFiltered},
 *       {@code extractDependencies_neverIncludesPlatformDeps} (property)</li>
 * </ul>
 *
 * <h2>Q4: replace/provide Ignored</h2>
 * <p>The {@code replace} and {@code provide} fields define virtual package relationships,
 * not actual dependencies. These are intentionally ignored.</p>
 * <ul>
 *   <li>Tests: implicit in all 50 SoT tests (no replace/provide deps appear in expected output)</li>
 * </ul>
 *
 * <h2>Q5: Metadata-Only Registry</h2>
 * <p>Packagist doesn't host archives directly — dist URLs point to VCS (typically GitHub
 * zipballs). The test corpus was downloaded from actual Packagist dist URLs.</p>
 * <ul>
 *   <li>Tests: implicit — test corpus downloaded from real dist URLs</li>
 * </ul>
 *
 * <h2>Q6: License Formats</h2>
 * <p>The license field can be a string ({@code "MIT"}) or an array ({@code ["MIT", "GPL-3.0"]}).
 * Arrays are joined with {@code " OR "}. Absent or empty license produces null.</p>
 * <ul>
 *   <li>Tests: {@code extractLicense_string}, {@code extractLicense_array},
 *       {@code extractLicense_null},
 *       {@code extractLicense_neverReturnsEmptyString} (property)</li>
 * </ul>
 */
public record PackagistQuirks() {
    // Intentionally empty — this record exists solely for its Javadoc documentation.
}
