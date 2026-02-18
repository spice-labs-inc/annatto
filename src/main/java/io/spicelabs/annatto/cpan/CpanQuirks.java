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
 *
 * <ul>
 *   <li><b>Distribution name vs module name</b> — CPAN identifies packages by distribution
 *       name (e.g. {@code Moose}), which is distinct from the module names contained within
 *       it (e.g. {@code Moose::Role}). The distribution name uses hyphens where module names
 *       use double-colons.</li>
 *   <li><b>META.json vs META.yml preference</b> — modern CPAN distributions ship both
 *       {@code META.json} (CPAN::Meta::Spec v2) and {@code META.yml} (v1.x). Prefer
 *       {@code META.json} when both are present, as it is the newer and richer format.</li>
 *   <li><b>Old dists may have neither</b> — very old distributions on CPAN may lack both
 *       META files entirely. In such cases metadata must be inferred from Makefile.PL,
 *       Build.PL, or the distribution filename itself.</li>
 *   <li><b>Prereqs structure (configure/build/test/runtime)</b> — dependencies are organised
 *       into phases (configure, build, test, runtime) and relationships (requires, recommends,
 *       suggests, conflicts). Each combination must be handled independently.</li>
 *   <li><b>PAUSEID</b> — each CPAN author is identified by a unique PAUSE ID (e.g.
 *       {@code ETHER}). The upload path encodes this as
 *       {@code authors/id/E/ET/ETHER/Distribution-Name-1.00.tar.gz}.</li>
 *   <li><b>Perl version conventions (v1.2.3 vs 1.002003)</b> — Perl supports both dotted
 *       decimal versions ({@code v1.2.3}) and decimal versions ({@code 1.002003}) where each
 *       group of three digits after the decimal represents a sub-version. These are numerically
 *       equivalent but textually different.</li>
 * </ul>
 */
public record CpanQuirks() {
}
