/* Copyright 2026 Spice Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.spicelabs.annatto.luarocks;

import io.spicelabs.annatto.luarocks.LuaTokenizer.LuaParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-focused tests for {@link LuaTableBuilder} (Fresh Scent Phase 1).
 *
 * <p>These tests drive the builder DIRECTLY (it is package-private) because the
 * {@link LuaRockspecEvaluator} best-effort layer swallows parse failures; driving
 * {@code evaluate()} would not pin the builder's own contract.
 *
 * <p>Named with the {@code BombTest} suffix so they run in the isolated surefire fork
 * (a reintroduced hang or OOM here must not poison the main suite — code-smell catalog §14).
 */
class LuaTableBuilderSecurityBombTest {

    // Requirement: catalog §5 (zero-progress read loops) / finding A1 / plan Phase 1.1
    // Theory: parseExpression() returns null WITHOUT consuming a token for tokens it does
    //         not understand (e.g. SYMBOL "=" in a method-call argument position); without
    //         a progress guard the argument loop in tryMethodCall spins forever. The guard
    //         must turn the no-progress case into a checked LuaParseException.
    // Boundaries: symbol-at-arg-start, symbol-after-comma, EOF inside args.
    // Revert-check: removing the `pos == before` guard in tryMethodCall makes this test hang
    //               (the fork-level timeout fails it).
    @Test
    @Timeout(10)
    void progressGuardRejectsSymbolInArgList() throws LuaParseException {
        var tokens = LuaTokenizer.tokenize("string:format(\"x\", ==)");
        assertThatThrownBy(() -> LuaTableBuilder.evaluate(tokens, Map.of("string", "string")))
                .isInstanceOf(LuaParseException.class);
    }

    // Requirement: catalog §8 (recursion without caps) / finding A7 / plan Phase 1.2
    // Theory: "(" nesting recurses parseExpression → parsePrimary → parseExpression with no
    //         table boundary; the depth guard must reject at MAX_NESTING_DEPTH with a checked
    //         LuaLimitException before the stack overflows.
    // Boundaries: exactly-at-limit vs over-limit; unary-minus chains via parsePrimary.
    // Revert-check: removing the paren depth guard makes this test die with StackOverflowError.
    @Test
    @Timeout(10)
    void depthGuardRejectsParenChain() throws LuaParseException {
        String source = "(".repeat(LuaTableBuilder.MAX_NESTING_DEPTH + 10)
                + "1" + ")".repeat(LuaTableBuilder.MAX_NESTING_DEPTH + 10);
        var tokens = LuaTokenizer.tokenize(source);
        assertThatThrownBy(() -> LuaTableBuilder.evaluate(tokens, Map.of()))
                .isInstanceOf(LuaLimitException.class);
    }

    // Requirement: catalog §8 / finding A7 / plan Phase 1.2
    // Theory: unary-minus chains recurse parsePrimary → parsePrimary with no table boundary;
    //         the same depth guard must apply. (Minuses are space-separated: "--" is a Lua
    //         comment and would be skipped by the tokenizer.)
    // Boundaries: over-limit minus chain.
    // Revert-check: removing the minus depth guard makes this test die with StackOverflowError.
    @Test
    @Timeout(10)
    void depthGuardRejectsMinusChain() throws LuaParseException {
        String source = "- ".repeat(LuaTableBuilder.MAX_NESTING_DEPTH + 10) + "1";
        var tokens = LuaTokenizer.tokenize(source);
        assertThatThrownBy(() -> LuaTableBuilder.evaluate(tokens, Map.of()))
                .isInstanceOf(LuaLimitException.class);
    }

    // Requirement: catalog §3 (unvalidated sizes driving allocations) / finding A8 / plan Phase 1.3
    // Theory: the RECEIVER of `x:format(args)` is the format string (env lookup). A hostile
    //         `x = "%9999999999d"` makes String.format attempt a ~10 GB allocation (OOM, an
    //         Error no catch(Exception) can contain). The format-string validation must reject
    //         the oversized width BEFORE formatting, as a checked LuaLimitException.
    // Boundaries: 6-digit width OK, 7-digit width rejected (MAX_FORMAT_FIELD_DIGITS).
    // Revert-check: removing the validation makes this test OOM the bomb fork.
    @Test
    @Timeout(10)
    void formatBombRejected() throws LuaParseException {
        var tokens = LuaTokenizer.tokenize("fmt:format(1)");
        assertThatThrownBy(() -> LuaTableBuilder.evaluate(tokens, Map.of("fmt", "%9999999999d")))
                .isInstanceOf(LuaLimitException.class);
    }

    // Requirement: positive control / plan Phase 1.3
    // Theory: legitimate width/precision directives used by real rockspecs (e.g. %5d, %.2f)
    //         must keep working — the validation must not over-reject.
    // Boundaries: reasonable widths within MAX_FORMAT_FIELD_DIGITS.
    // Revert-check: n/a (guards the fix against over-rejection).
    @Test
    @Timeout(10)
    void reasonableFormatsStillWork() throws LuaParseException {
        var tokens = LuaTokenizer.tokenize("fmt:format(7)");
        assertThatNoException().isThrownBy(() -> {
            Object result = LuaTableBuilder.evaluate(tokens, Map.of("fmt", "%5d"));
            assertThat(result).isEqualTo("    7");
        });
    }
}
