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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-focused tests for {@link LuaRockspecEvaluator} (Fresh Scent Phase 1).
 *
 * <p>Named with the {@code BombTest} suffix so they run in the isolated surefire fork
 * (code-smell catalog §14).
 */
class LuaRockspecEvaluatorSecurityBombTest {

    // Requirement: catalog §5 / finding A1+A10 / plan Phase 1.4
    // Theory: a syntactically unsupported construct inside a statement (here: a SYMBOL in a
    //         method-call argument list) must be SKIPPED with forward progress — evaluate()
    //         terminates and still captures the surrounding valid assignments. This is the
    //         best-effort contract; the no-progress guard converts the former infinite loop
    //         into a skip.
    // Boundaries: hostile statement between two valid assignments.
    // Revert-check: removing the progress guard makes this test hang (fork timeout fails it).
    @Test
    @Timeout(10)
    void evaluateTerminatesAndSkipsHostileSymbolArg() throws LuaParseException {
        String source = "package = \"demo\"\nfoo = string:format(\"a\", ==)\nversion = \"1.0-1\"\n";
        Map<String, Object> globals = LuaRockspecEvaluator.evaluate(source);
        assertThat(globals).containsEntry("package", "demo");
        assertThat(globals).containsEntry("version", "1.0-1");
    }

    // Requirement: catalog §6 (silent wrong data) / finding A10 / plan Phase 1.4
    // Theory: resource-limit violations (here: parenthesis depth) are NOT skippable
    //         best-effort cases — they indicate hostile input and must propagate out of
    //         evaluate() as a checked LuaLimitException (subtype of LuaParseException)
    //         instead of yielding partial metadata with no error signal.
    // Boundaries: depth over MAX_NESTING_DEPTH.
    // Revert-check: re-introducing the blanket catch (RuntimeException) makes this test see a
    //               successful evaluation instead of an exception (red).
    @Test
    @Timeout(10)
    void limitViolationPropagatesFromEvaluate() {
        String source = "build = " + "(".repeat(LuaTableBuilder.MAX_NESTING_DEPTH + 10)
                + "1" + ")".repeat(LuaTableBuilder.MAX_NESTING_DEPTH + 10);
        assertThatThrownBy(() -> LuaRockspecEvaluator.evaluate(source))
                .isInstanceOf(LuaLimitException.class)
                .isInstanceOf(LuaParseException.class);
    }

    // Requirement: plan Phase 1.4 — the outcome record exposes the skipped-statement flag.
    // Theory: skippedStatements counts statements skipped as unsupported (not limit violations).
    // Revert-check: removing the counter makes this assert fail.
    @Test
    @Timeout(10)
    void outcomeRecordsSkippedStatements() throws LuaParseException {
        String source = "package = \"demo\"\n"
                + "if os.getenv(\"CI\") then\n  version = \"x\"\nend\n"
                + "version = \"1.0-1\"\n";
        LuaRockspecEvaluator.Outcome outcome = LuaRockspecEvaluator.evaluateWithOutcome(source);
        assertThat(outcome.skippedStatements()).isGreaterThanOrEqualTo(1);
        assertThat(outcome.globals()).containsEntry("package", "demo");
        assertThat(outcome.globals()).containsEntry("version", "1.0-1");
    }
}
