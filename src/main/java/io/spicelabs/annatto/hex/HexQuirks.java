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
 * <ul>
 *   <li><b>Erlang term format (not JSON/YAML) needs simple parser</b> — the
 *       {@code metadata.config} file inside a Hex package is encoded as Erlang external term
 *       format (key-value tuples). A dedicated parser is required because this is neither JSON
 *       nor YAML.</li>
 *   <li><b>Packages can be Erlang or Elixir</b> — Hex serves both the Erlang and Elixir
 *       ecosystems. The {@code build_tools} field indicates which tooling produced the package
 *       (e.g. {@code mix}, {@code rebar3}, {@code erlang.mk}).</li>
 *   <li><b>build_tools field</b> — a list of strings declaring which build tools the package
 *       supports. Common values are {@code "mix"} for Elixir and {@code "rebar3"} for
 *       Erlang.</li>
 *   <li><b>Requirements is map of name to {requirement, optional, app}</b> — each dependency
 *       is expressed as a map entry where the key is the package name and the value contains
 *       a version requirement string, an optional flag, and the OTP application name.</li>
 *   <li><b>Archive format</b> — a Hex package is a {@code .tar} containing {@code VERSION},
 *       {@code metadata.config}, {@code contents.tar.gz}, and {@code CHECKSUM}. The actual
 *       source code lives inside the nested {@code contents.tar.gz}.</li>
 * </ul>
 */
public record HexQuirks() {
}
