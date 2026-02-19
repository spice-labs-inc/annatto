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
package io.spicelabs.annatto.cpan;

/**
 * Documents CPAN ecosystem quirks that affect metadata extraction.
 * This is an intentionally empty record — it serves as living documentation.
 *
 * <ul>
 *   <li><b>Q1: META.json preferred over META.yml</b> — Modern CPAN distributions ship both
 *       {@code META.json} (CPAN::Meta::Spec v2) and {@code META.yml} (v1.x). We prefer JSON
 *       when both are present. META v1 YAML format uses flat prereqs keys (requires,
 *       build_requires, configure_requires) and v1 license identifiers (perl → perl_5).
 *       Tested by: {@code extractFromArchive_prefersMetaJson},
 *       {@code extractFromArchive_fallsBackToMetaYml},
 *       {@code package_yamlTiny_metaYmlOnly}.</li>
 *
 *   <li><b>Q2: Distribution name vs module name</b> — CPAN identifies packages by distribution
 *       name (e.g. {@code Moose}), which uses hyphens, while module names use double-colons
 *       (e.g. {@code Moose::Role}). We use the distribution name as both name and simpleName.
 *       Tested by: {@code extractName_matchesSourceOfTruth}.</li>
 *
 *   <li><b>Q3: PAUSE ID unavailable from tarball</b> — The PAUSE author ID is encoded in
 *       the CPAN upload path but is not present in the distribution metadata itself. We pass
 *       {@link java.util.Optional#empty()} for the PURL namespace.
 *       Tested by: {@code getPurls_noNamespace}.</li>
 *
 *   <li><b>Q4: Prereqs = phases x relationships; only "requires" extracted</b> — CPAN prereqs
 *       are organized into phases (runtime, test, build, configure, develop) and relationships
 *       (requires, recommends, suggests, conflicts). We only extract "requires".
 *       Tested by: {@code extractDependencies_matchSourceOfTruth}, {@code extractDeps_onlyRequires}.</li>
 *
 *   <li><b>Q5: Version constraint "0" maps to null</b> — A version requirement of "0" means
 *       "any version" and is normalized to null versionConstraint.
 *       Tested by: {@code parseVersionConstraint_zeroMapsToNull}.</li>
 *
 *   <li><b>Q6: No publishedAt in META.json</b> — CPAN metadata does not include a publication
 *       timestamp. publishedAt is always null.
 *       Tested by: {@code extractPublishedAt_matchesSourceOfTruth}.</li>
 *
 *   <li><b>Q7: License array; ["unknown"] maps to null</b> — The license field is an array of
 *       CPAN license identifiers joined with " OR ". A single-element {@code ["unknown"]}
 *       array maps to null.
 *       Tested by: {@code extractLicense_matchesSourceOfTruth}, {@code extractLicense_unknownMapsToNull}.</li>
 *
 *   <li><b>Q8: .tar.gz disambiguation with PyPI</b> — Both CPAN and PyPI use .tar.gz format.
 *       The filter uses a heuristic: if the name portion (before version) contains an uppercase
 *       letter, it routes to CPAN; otherwise to PyPI.
 *       Tested by: {@code detectTarGzEcosystem_cpanUppercase},
 *       {@code detectEcosystem_tarGz_lowercaseIsPypi}.</li>
 * </ul>
 */
public record CpanQuirks() {
}
