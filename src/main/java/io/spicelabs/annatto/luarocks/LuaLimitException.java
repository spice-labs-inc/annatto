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
 * Thrown when a resource limit is exceeded while evaluating a rockspec's Lua subset.
 *
 * <p>Unlike plain {@link LuaTokenizer.LuaParseException} (syntax-level problems that the
 * best-effort evaluator may skip), limit violations indicate hostile or pathological input
 * and are rethrown through {@link LuaRockspecEvaluator} to the caller.
 *
 * <p>This is a CHECKED exception (extends {@code LuaParseException}, which is itself checked):
 * limit violations must fail loudly at an API boundary, never escape silently (code-smell
 * catalog §7).
 */
final class LuaLimitException extends LuaTokenizer.LuaParseException {
    LuaLimitException(String message) {
        super(message);
    }
}
