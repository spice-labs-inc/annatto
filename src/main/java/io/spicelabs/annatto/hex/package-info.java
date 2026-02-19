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

/**
 * Hex ecosystem support for Annatto.
 *
 * <h2>Extraction Pipeline</h2>
 * <ol>
 *   <li>Read {@code .tar} archive (plain tar, not gzip)
 *       (tested by {@code AnnattoProcessFilterTest.detectEcosystem_tar_isHex},
 *       {@code AnnattoProcessFilterTest.detectEcosystem_tarGz_notHex})</li>
 *   <li>Extract {@code metadata.config} from tar entries</li>
 *   <li>Tokenize Erlang term format with {@link io.spicelabs.annatto.hex.ErlangTermTokenizer}
 *       (tested by {@code ErlangTermTokenizerTest} — 16 tokenizer unit tests)</li>
 *   <li>Parse tokens into structured map with {@link io.spicelabs.annatto.hex.ErlangTermParser}
 *       (tested by {@code ErlangTermParserTest} — 16 parser unit tests)</li>
 *   <li>Build MetadataResult, handling both mix and rebar3 requirement formats
 *       (tested by {@code HexMetadataExtractorTest.package_jason},
 *       {@code HexMetadataExtractorTest.package_cowboy_erlangPackage})</li>
 * </ol>
 * <p>(pipeline tested by 9 parameterized SoT tests in {@code HexMetadataExtractorTest.sot_*})</p>
 *
 * <h2>PURL Format</h2>
 * <p>{@code pkg:hex/name@version} — no namespace, name lowercased (Q6).
 * (tested by {@code PurlBuilderTest.forHex_simple})</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link io.spicelabs.annatto.hex.HexMetadataExtractor} — stateless extraction</li>
 *   <li>{@link io.spicelabs.annatto.hex.HexHandler} — lifecycle handler</li>
 *   <li>{@link io.spicelabs.annatto.hex.ErlangTermTokenizer} — Erlang term tokenizer
 *       (security limits tested by {@code ErlangTermTokenizerTest.tokenize_rejectsOversizedInput},
 *       {@code ErlangTermTokenizerTest.tokenize_rejectsExcessiveTokenCount})</li>
 *   <li>{@link io.spicelabs.annatto.hex.ErlangTermParser} — Erlang term parser
 *       (security limits tested by {@code ErlangTermParserTest.parse_rejectsExcessiveNesting},
 *       {@code ErlangTermParserTest.parse_rejectsExcessiveElements})</li>
 *   <li>{@link io.spicelabs.annatto.hex.HexQuirks} — ecosystem-specific documentation</li>
 * </ul>
 */
package io.spicelabs.annatto.hex;
