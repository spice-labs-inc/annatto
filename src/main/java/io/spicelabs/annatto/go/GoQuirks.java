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
package io.spicelabs.annatto.go;

/**
 * Documents known quirks of the Go module ecosystem.
 *
 * <ul>
 *   <li><b>Q1: URL-like module paths</b> — module paths resemble URLs (e.g.,
 *       {@code github.com/user/repo}) and are used as unique identifiers. The module
 *       path is the package name, and the last path segment is the simple name.
 *       Tests: {@code GoMetadataExtractorTest#extractName_matchesSourceOfTruth},
 *       {@code GoMetadataExtractorTest#extractSimpleName_standardModule}</li>
 *
 *   <li><b>Q2: +incompatible suffix</b> — modules at major version 2+ that lack a
 *       {@code go.mod} file may use the {@code +incompatible} version suffix
 *       (e.g., {@code v3.0.0+incompatible}). The extractor preserves this suffix
 *       in version constraints.
 *       Tests: parameterized source-of-truth tests on packages with +incompatible deps</li>
 *
 *   <li><b>Q3: Pseudo-versions</b> — non-tagged commits use pseudo-versions of the form
 *       {@code v0.0.0-yyyymmddhhmmss-abcdefabcdef}. The extractor preserves these
 *       as-is in version constraints.
 *       Tests: parameterized source-of-truth tests on deps with pseudo-versions</li>
 *
 *   <li><b>Q4: replace/exclude directives</b> — {@code go.mod} may contain
 *       {@code replace} and {@code exclude} directives that alter dependency resolution.
 *       The extractor ignores these and reads only {@code require} directives.
 *       Tests: {@code GoMetadataExtractorTest#parseGoMod_ignoresReplace},
 *       {@code GoMetadataExtractorTest#parseGoMod_ignoresExclude}</li>
 *
 *   <li><b>Q5: Major version suffixes /vN</b> — modules at major version 2 or higher must
 *       include the major version as a path suffix (e.g., {@code example.com/mod/v2}).
 *       The simple name for such modules is {@code v2} (the last path segment),
 *       consistent with PURL namespace/name splitting in
 *       {@link io.spicelabs.annatto.common.PurlBuilder#forGo}.
 *       Tests: {@code GoMetadataExtractorTest#extractSimpleName_majorVersionModule},
 *       {@code GoHandlerTest#getPurls_majorVersionModule}</li>
 *
 *   <li><b>Q6: Retracted versions</b> — {@code go.mod} can declare {@code retract}
 *       directives that mark specific versions as withdrawn. The extractor ignores
 *       {@code retract} directives entirely.
 *       Tests: {@code GoMetadataExtractorTest#parseGoMod_ignoresRetract}</li>
 * </ul>
 */
public record GoQuirks() {
}
