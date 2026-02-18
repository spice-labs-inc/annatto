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
 * <ul>
 *   <li><b>.rockspec is Lua code (not static data)</b> — rockspec files are valid Lua scripts
 *       that are executed to produce metadata tables. They cannot be reliably parsed without
 *       a Lua evaluator or a carefully written subset parser.</li>
 *   <li><b>Version includes revision suffix (1.0.0-1)</b> — LuaRocks versions follow the
 *       pattern {@code <upstream-version>-<revision>}, e.g. {@code 1.0.0-1}. The revision
 *       is incremented when the rockspec changes but the upstream source does not.</li>
 *   <li><b>external_dependencies for C libraries</b> — rockspecs can declare
 *       {@code external_dependencies} that reference system-level C libraries (e.g. OpenSSL).
 *       These are not LuaRocks packages and cannot be resolved through the LuaRocks registry.</li>
 *   <li><b>Some rockspecs use Lua string concatenation</b> — because rockspecs are Lua code,
 *       authors sometimes use string concatenation ({@code ..}) or variables to construct
 *       values dynamically, making static extraction unreliable for those fields.</li>
 * </ul>
 */
public record LuarocksQuirks() {
}
