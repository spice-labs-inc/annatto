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
package io.spicelabs.annatto.hex;

/**
 * Documents Hex ecosystem quirks that affect metadata extraction.
 *
 * <h2>Q1: Plain .tar archive (not .tar.gz)</h2>
 * <p>Hex packages are plain tar archives, NOT gzip-compressed. They contain
 * {@code VERSION}, {@code CHECKSUM}, {@code metadata.config}, and
 * {@code contents.tar.gz}.</p>
 * <p>Tested by: {@code HexHandlerTest.begin_returnsMementoWithMetadata},
 * {@code HexMetadataExtractorTest.package_jason}</p>
 *
 * <h2>Q2: Erlang term format (not JSON/YAML)</h2>
 * <p>The {@code metadata.config} file uses Erlang external term format:
 * {@code {<<"key">>, value}.} statements. A custom parser
 * ({@link ErlangTermTokenizer} + {@link ErlangTermParser}) handles this.</p>
 * <p>Tested by: {@code ErlangTermTokenizerTest}, {@code ErlangTermParserTest}</p>
 *
 * <h2>Q3: Licenses list joined with " OR "</h2>
 * <p>The {@code licenses} field is a list of strings. Multiple licenses are
 * joined with " OR ". Empty list → null.</p>
 * <p>Tested by: {@code HexMetadataExtractorTest.extractLicense_joinedWithOr},
 * {@code HexMetadataExtractorTest.extractLicense_emptyListReturnsEmpty}</p>
 *
 * <h2>Q4: No publisher or publishedAt</h2>
 * <p>Hex metadata.config has no author/maintainer or timestamp fields.
 * Both publisher and publishedAt are always null.</p>
 * <p>Tested by: {@code HexMetadataExtractorPropertyTest.buildMetadataResult_publisherAlwaysEmpty},
 * {@code HexMetadataExtractorPropertyTest.buildMetadataResult_publishedAtAlwaysEmpty}</p>
 *
 * <h2>Q5: Dependencies are proplists, all "runtime"</h2>
 * <p>Requirements are lists of proplists with name, requirement, optional, app,
 * repository fields. All dependencies are scope "runtime".</p>
 * <p>Tested by: {@code HexMetadataExtractorTest.extractDeps_allRuntime}</p>
 *
 * <h2>Q6: PURL name lowercased per purl-spec</h2>
 * <p>Package names in the PURL must be lowercased: {@code pkg:hex/name@version}.
 * No namespace component.</p>
 * <p>Tested by: {@code HexHandlerTest.getPurls_correctFormat},
 * {@code HexHandlerTest.getPurls_nameLowercased}</p>
 *
 * <h2>Q7: Erlang and Elixir packages coexist</h2>
 * <p>Hex serves both ecosystems. The {@code build_tools} field indicates
 * which tooling ({@code mix}, {@code rebar3}, {@code erlang.mk}) produced
 * the package. Metadata format is identical for both.</p>
 * <p>Tested by: {@code HexMetadataExtractorTest.package_cowboy_erlangPackage}</p>
 *
 * <h2>Q8: Two requirement formats (Elixir mix vs Erlang rebar3)</h2>
 * <p>Elixir packages use a list of proplists with "name" keys inside.
 * Erlang packages use a proplist where the dep name is the outer key.
 * Both formats are handled transparently.</p>
 * <p>Tested by: {@code HexMetadataExtractorTest.extractDeps_rebar3Format},
 * {@code HexMetadataExtractorTest.extractDeps_mixFormat}</p>
 */
public record HexQuirks() {
}
