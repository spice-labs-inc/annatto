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
package io.spicelabs.annatto.luarocks;

/**
 * Documents LuaRocks ecosystem quirks that affect metadata extraction.
 *
 * <p><b>Q1: Rockspec is Lua code (not static data).</b>
 * Rockspec files are valid Lua scripts that are executed to produce metadata tables.
 * We use a limited Lua subset evaluator ({@link LuaTokenizer}, {@link LuaTableBuilder},
 * {@link LuaRockspecEvaluator}) that handles simple assignments, table constructors,
 * string concatenation, local variables, multi-assignment ({@code local a, b = x, y}),
 * method calls ({@code :format()} for string formatting), and comments. The table
 * parser handles null values from unsupported expressions by filtering them before
 * {@code Map.copyOf}/{@code List.copyOf}. The evaluator catches {@link RuntimeException}
 * to skip failed statements (e.g., complex build tables with function calls, null
 * values from unsupported constructs) while preserving already-captured metadata.
 * Tests: {@code luaTokenizer_*}, {@code luaEvaluator_*}, all 50 SoT tests,
 * property {@code luaEvaluator_neverThrowsForValidRockspec}</p>
 *
 * <p><b>Q2: Version includes revision suffix.</b>
 * LuaRocks versions follow the pattern {@code <upstream-version>-<revision>}, e.g.,
 * {@code 1.8.0-1}. The revision is incremented when the rockspec changes.
 * Tests: {@code extractVersion_matchesSourceOfTruth},
 * {@code forLuaRocks_versionWithRevision},
 * property {@code extractVersion_alwaysContainsHyphenRevision}</p>
 *
 * <p><b>Q3: external_dependencies filtered.</b>
 * Rockspecs can declare {@code external_dependencies} that reference system-level C
 * libraries (e.g., OpenSSL). These are not LuaRocks packages and are filtered out.
 * Tests: {@code extractDependencies_externalDepsFiltered},
 * {@code package_luasec_externalDeps},
 * property {@code extractDependencies_neverContainsExternalDeps}</p>
 *
 * <p><b>Q4: Dependency string format.</b>
 * Dependencies are strings like {@code "lua >= 5.1"} or {@code "luasocket"}.
 * Split on first space: name = first token, versionConstraint = rest.
 * Tests: {@code parseDependencyString_*},
 * {@code extractDependencies_matchSourceOfTruth},
 * properties {@code parseDependencyString_*}</p>
 *
 * <p><b>Q5: Description fallback.</b>
 * Description is extracted from {@code description.summary} (preferred),
 * falling back to {@code description.detailed}, then null.
 * Tests: {@code extractDescription_*},
 * property {@code extractDescription_summaryAlwaysPreferred}</p>
 *
 * <p><b>Q6: Publisher from maintainer.</b>
 * Publisher is extracted from {@code description.maintainer}, null if absent.
 * Tests: {@code extractPublisher_*}, all 50 SoT publisher tests</p>
 *
 * <p><b>Q7: No publishedAt.</b>
 * Rockspec files have no timestamp field; {@code publishedAt} is always null.
 * Tests: {@code extractPublishedAt_matchesSourceOfTruth},
 * property {@code buildMetadataResult_publishedAtAlwaysEmpty}</p>
 *
 * <p><b>Q8: PURL name lowercasing.</b>
 * Per purl-spec, LuaRocks PURL names are ASCII lowercased. The original case
 * is preserved in the {@code name} and {@code simpleName} metadata fields.
 * Tests: {@code forLuaRocks_nameLowercased},
 * {@code getPurls_nameLowercased},
 * property {@code purlName_alwaysLowercase}</p>
 */
public record LuarocksQuirks() {
}
