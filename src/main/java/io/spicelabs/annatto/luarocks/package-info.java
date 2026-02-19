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
 * LuaRocks ecosystem support for Annatto.
 *
 * <p>LuaRocks packages are described by {@code .rockspec} files, which are executable Lua
 * scripts defining package metadata, dependencies, and build instructions.
 * (tested by {@code AnnattoProcessFilterTest.detectEcosystem_rock_isLuarocks},
 * {@code AnnattoProcessFilterTest.detectEcosystem_rockspec_isLuarocks})
 * These files are parsed using a limited Lua subset evaluator rather than a full Lua VM.
 * (tested by {@code LuaRockspecEvaluatorTest}, {@code LuaTokenizerTest}, {@code LuaTableBuilderTest})
 * The evaluator supports simple assignments, table constructors, string concatenation,
 * local variables, multi-assignment, method calls (e.g., {@code :format()} for string
 * formatting), and comments.</p>
 *
 * <h2>Extraction pipeline</h2>
 * <ol>
 *   <li>{@code .src.rock} (ZIP) → find {@code .rockspec} at root → read Lua text</li>
 *   <li>{@code .rockspec} → read Lua text directly from InputStream</li>
 *   <li>Evaluate Lua text with {@link io.spicelabs.annatto.luarocks.LuaRockspecEvaluator}</li>
 *   <li>Extract fields: package, version, description, dependencies</li>
 *   <li>Build {@link io.spicelabs.annatto.common.MetadataResult}</li>
 * </ol>
 * <p>(pipeline tested by 9 parameterized SoT tests in {@code LuarocksMetadataExtractorTest.extract*_matchesSourceOfTruth})</p>
 *
 * <h2>PURL format</h2>
 * <p>{@code pkg:luarocks/name@version} — name is ASCII lowercased, no namespace.
 * (tested by {@code PurlBuilderTest.forLuaRocks_nameLowercased})</p>
 *
 * @see io.spicelabs.annatto.luarocks.LuarocksHandler
 * @see io.spicelabs.annatto.luarocks.LuarocksMetadataExtractor
 * @see io.spicelabs.annatto.luarocks.LuarocksQuirks
 */
package io.spicelabs.annatto.luarocks;
