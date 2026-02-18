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
 * <h2>Known Quirks</h2>
 * <ul>
 *   <li><b>Version often NOT in composer.json</b> &mdash; Many Composer packages omit the
 *       {@code version} field from {@code composer.json} entirely. The canonical version is
 *       derived from the git tag at release time. Extractors must be prepared to resolve the
 *       version from external context (e.g. the archive filename or registry metadata) when
 *       it is absent from the manifest.</li>
 *   <li><b>Vendor/package naming</b> &mdash; Packagist package names always follow the
 *       {@code vendor/package} convention (e.g. {@code monolog/monolog}). Both segments are
 *       required and case-insensitive. The PURL type {@code composer} mirrors this structure.</li>
 *   <li><b>require-dev vs require</b> &mdash; {@code composer.json} distinguishes between
 *       production dependencies ({@code require}) and development-only dependencies
 *       ({@code require-dev}). Only {@code require} entries should typically appear in SBOM
 *       output.</li>
 *   <li><b>replace/provide virtual packages</b> &mdash; The {@code replace} and {@code provide}
 *       fields allow a package to declare that it substitutes or satisfies another package.
 *       These create virtual package relationships that can complicate dependency resolution
 *       and SBOM generation.</li>
 *   <li><b>Packagist doesn't host archives directly</b> &mdash; Unlike most registries,
 *       Packagist is a metadata-only registry. The actual source archives are fetched from
 *       the VCS repository (typically GitHub). Distribution archives may come from
 *       {@code dist} URLs pointing to GitHub's zip download endpoints.</li>
 * </ul>
 */
public record PackagistQuirks() {
    // Intentionally empty — this record exists solely for its Javadoc documentation.
}
