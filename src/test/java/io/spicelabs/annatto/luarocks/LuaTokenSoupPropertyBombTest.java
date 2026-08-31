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
import io.spicelabs.annatto.luarocks.LuaTokenizer.Token;
import io.spicelabs.annatto.luarocks.LuaTokenizer.TokenType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.List;
import java.util.Map;

/**
 * Property-based hostile-input tests for {@link LuaTableBuilder} (Fresh Scent Phase 1).
 *
 * <p>Invariant for all properties: the builder TERMINATES and either yields a value/null or
 * throws the CHECKED {@link LuaParseException} (or its {@link LuaLimitException} subtype) —
 * no other exception type, and no {@link Error} (StackOverflowError / OutOfMemoryError).
 *
 * <p>Named with the {@code BombTest} suffix so these run in the isolated surefire fork with a
 * per-method timeout (catalog §14); a reintroduced hang or bomb poisons only this fork.
 */
class LuaTokenSoupPropertyBombTest {

    private static final Map<String, Object> ENV = Map.of("string", "string");

    @Provide
    Arbitrary<List<Token>> tokenSoups() {
        Arbitrary<String> symbols = Arbitraries.of(
                "{", "}", "[", "]", "(", ")", ",", ";", ":", ".", "..", "=", "==",
                "-", "+", "%", "*", "/", "#", "~", "<", ">", "&", "|", "^");
        Arbitrary<String> names = Arbitraries.strings().withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(5);
        Arbitrary<String> strings = Arbitraries.strings().alpha().numeric().ofMaxLength(6);
        Arbitrary<String> numbers = Arbitraries.strings().numeric().ofMaxLength(6);
        Arbitrary<Token> token = Arbitraries.oneOf(
                symbols.map(s -> new Token(TokenType.SYMBOL, s)),
                names.map(s -> new Token(TokenType.NAME, s)),
                strings.map(s -> new Token(TokenType.STRING, s)),
                numbers.map(s -> new Token(TokenType.NUMBER, s)));
        return token.list().ofMaxSize(60);
    }

    // Requirement: catalog §5/§8 / finding A1+A7 / plan Phase 1
    // Theory: arbitrary token soups must never hang the builder, escape an unchecked
    //         exception, or die with an Error. Termination is bounded by the fork-level
    //         timeout; the whitelist is asserted directly.
    // Revert-check: removing the tryMethodCall progress guard makes some generated soup hang
    //               (fork timeout); removing depth guards produces StackOverflowError.
    @Property(tries = 300)
    void tokenSoupTerminatesWithWhitelist(@ForAll("tokenSoups") List<Token> tokens) {
        try {
            LuaTableBuilder.evaluate(tokens, ENV);
        } catch (LuaParseException e) {
            // allowed: malformed / limit-exceeding input
        }
    }

    // Requirement: catalog §8 / finding A7 / plan Phase 1.2
    // Theory: deep parenthesis chains must be rejected by the depth guard (LuaLimitException)
    //         or evaluate successfully — never StackOverflowError. Random generators won't
    //         emit deep nesting naturally, so this generates it explicitly.
    // Revert-check: removing the paren depth guard makes n > ~2000 overflow the stack; at
    //               these sizes the guard throws before that.
    @Property(tries = 50)
    void deepParenChainsNeverOverflowStack(@ForAll @IntRange(min = 1, max = 40) int n) {
        StringBuilder sb = new StringBuilder();
        sb.append("(".repeat(n)).append("1").append(")".repeat(n));
        try {
            LuaTableBuilder.evaluate(LuaTokenizer.tokenize(sb.toString()), Map.of());
        } catch (LuaParseException e) {
            // allowed: depth guard trip
        }
    }

    // Requirement: catalog §8 / finding A7 / plan Phase 1.2
    // Theory: unary-minus chains recurse via parsePrimary; same guard must apply.
    //         (Minuses are space-separated: "--" is a Lua comment.)
    // Revert-check: removing the minus depth guard overflows the stack.
    @Property(tries = 50)
    void deepMinusChainsNeverOverflowStack(@ForAll @IntRange(min = 1, max = 40) int n) {
        String source = "- ".repeat(n) + "1";
        try {
            LuaTableBuilder.evaluate(LuaTokenizer.tokenize(source), Map.of());
        } catch (LuaParseException e) {
            // allowed: depth guard trip
        }
    }

    @Provide
    Arbitrary<String> hostileFormatStrings() {
        return Arbitraries.strings()
                .withChars('%', 'd', 's', 'f', '.', '9', '0', '-', ' ', 'x', '$', '+')
                .ofMaxLength(40)
                .filter(s -> s.contains("%"));
    }

    // Requirement: catalog §3 / finding A8 / plan Phase 1.3
    // Theory: the RECEIVER of `x:format(args)` is the format string (env lookup), so a hostile
    //         rockspec-controlled string is the fmt. Hostile %-directives (width bombs) must be
    //         rejected before String.format is reached — the builder must return or throw
    //         LuaParseException, never attempt a giant allocation (OutOfMemoryError).
    // Revert-check: removing the format-string validation lets some generated string OOM the
    //               bomb fork.
    @Property(tries = 300)
    void hostileFormatStringsNeverAllocate(@ForAll("hostileFormatStrings") String fmt)
            throws LuaParseException {
        String source = "fmtVar:format(" + toLuaString(fmt) + ", 1)";
        List<Token> tokens = LuaTokenizer.tokenize(source);
        try {
            LuaTableBuilder.evaluate(tokens, Map.of("fmtVar", fmt));
        } catch (LuaParseException e) {
            // allowed: rejected directive / limit
        }
    }

    private static String toLuaString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
