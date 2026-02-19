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

import io.spicelabs.annatto.luarocks.LuaTokenizer.Token;
import io.spicelabs.annatto.luarocks.LuaTokenizer.TokenType;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a complete rockspec script, tracking variable assignments.
 *
 * <p>Handles the subset of Lua statements found in rockspec files:
 * global assignments ({@code name = expr}), local assignments
 * ({@code local name = expr}), and semicolons. Unrecognized statements
 * (function definitions, control flow) are skipped without throwing.</p>
 *
 * <p>Returns a {@link Map} of global variable assignments representing the
 * rockspec metadata (package, version, description, dependencies, etc.).</p>
 */
final class LuaRockspecEvaluator {

    private final List<Token> tokens;
    private final Map<String, Object> globals = new HashMap<>();
    private final Map<String, Object> locals = new HashMap<>();
    private int pos;

    private LuaRockspecEvaluator(@NotNull List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    /**
     * Evaluates a rockspec Lua source and returns global variable assignments.
     *
     * @param source the rockspec Lua source text
     * @return a map of global variable names to their evaluated values
     */
    static @NotNull Map<String, Object> evaluate(@NotNull String source) {
        List<Token> tokens = LuaTokenizer.tokenize(source);
        LuaRockspecEvaluator evaluator = new LuaRockspecEvaluator(tokens);
        evaluator.run();
        // Use HashMap copy since globals may contain null values (from Lua nil assignments)
        return new HashMap<>(evaluator.globals);
    }

    private void run() {
        while (peek().type() != TokenType.EOF) {
            int before = pos;
            try {
                if (!parseStatement()) {
                    skipUnrecognized();
                }
            } catch (RuntimeException e) {
                // Skip statements that fail to parse or evaluate (e.g., complex build
                // tables with function calls, null values in Map.copyOf). Important
                // metadata fields (package, version, description, dependencies) are
                // already captured in globals.
                if (pos == before) {
                    advance(); // ensure forward progress
                }
            }
        }
    }

    private boolean parseStatement() {
        Token token = peek();

        // Semicolons
        if (token.type() == TokenType.SYMBOL && token.value().equals(";")) {
            advance();
            return true;
        }

        // local name = expr
        if (token.type() == TokenType.NAME && token.value().equals("local")) {
            return parseLocal();
        }

        // name = expr (global assignment)
        if (token.type() == TokenType.NAME) {
            return parseAssignment();
        }

        return false;
    }

    private boolean parseLocal() {
        advance(); // consume 'local'
        Token nameToken = peek();
        if (nameToken.type() != TokenType.NAME) {
            return false;
        }
        String name = nameToken.value();

        // Check for "local function"
        if (name.equals("function")) {
            return false; // let skipUnrecognized handle it
        }

        advance(); // consume first name

        // Collect additional names for multi-assignment: local a, b, c = ...
        List<String> names = new java.util.ArrayList<>();
        names.add(name);
        while (peek().type() == TokenType.SYMBOL && peek().value().equals(",")) {
            advance(); // consume ','
            if (peek().type() != TokenType.NAME) {
                break;
            }
            names.add(peek().value());
            advance(); // consume name
        }

        if (peek().type() == TokenType.SYMBOL && peek().value().equals("=")) {
            advance(); // consume =
            // Evaluate first expression
            Object firstValue = evaluateExpression();
            locals.put(names.get(0), firstValue);

            // Evaluate remaining expressions for multi-assignment
            for (int i = 1; i < names.size(); i++) {
                if (peek().type() == TokenType.SYMBOL && peek().value().equals(",")) {
                    advance(); // consume ','
                    Object value = evaluateExpression();
                    locals.put(names.get(i), value);
                } else {
                    locals.put(names.get(i), null);
                }
            }
            return true;
        }

        // local name (no initializer) — valid Lua, just declare nil
        for (String n : names) {
            locals.put(n, null);
        }
        return true;
    }

    private boolean parseAssignment() {
        Token nameToken = peek();
        String name = nameToken.value();

        // Check if this is a keyword that starts a block — skip these
        if (isBlockKeyword(name)) {
            return false;
        }

        // Look ahead: must be NAME = expr or NAME.field.field = expr
        int saved = pos;
        advance(); // consume name

        // Handle dotted assignment: name.field = expr
        while (peek().type() == TokenType.SYMBOL && peek().value().equals(".")) {
            advance(); // consume .
            if (peek().type() != TokenType.NAME) {
                pos = saved;
                return false;
            }
            advance(); // consume field name
        }

        if (peek().type() != TokenType.SYMBOL || !peek().value().equals("=")) {
            pos = saved;
            return false;
        }

        advance(); // consume =

        // For simple global assignment: name = expr
        if (pos - saved == 2) {
            // Simple assignment: no dots
            Object value = evaluateExpression();
            globals.put(name, value);
            return true;
        }

        // For dotted assignment like description.summary = expr, handle specially
        // This is rare in rockspecs but could occur
        pos = saved;
        advance(); // re-consume name
        List<String> fields = new java.util.ArrayList<>();
        while (peek().type() == TokenType.SYMBOL && peek().value().equals(".")) {
            advance(); // consume .
            fields.add(peek().value());
            advance(); // consume field name
        }
        advance(); // consume =
        Object value = evaluateExpression();

        // Navigate into existing table or create one
        @SuppressWarnings("unchecked")
        Map<String, Object> table = globals.get(name) instanceof Map
                ? new HashMap<>((Map<String, Object>) globals.get(name))
                : new HashMap<>();
        Map<String, Object> current = table;
        for (int i = 0; i < fields.size() - 1; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> next = current.get(fields.get(i)) instanceof Map
                    ? new HashMap<>((Map<String, Object>) current.get(fields.get(i)))
                    : new HashMap<>();
            current.put(fields.get(i), next);
            current = next;
        }
        current.put(fields.get(fields.size() - 1), value);
        globals.put(name, table);
        return true;
    }

    private Object evaluateExpression() {
        Map<String, Object> combinedEnv = new HashMap<>(globals);
        combinedEnv.putAll(locals); // locals shadow globals
        LuaTableBuilder.ParseResult result = LuaTableBuilder.evaluateAt(tokens, combinedEnv, pos);
        pos = result.endPos();
        return result.value();
    }

    private void skipUnrecognized() {
        Token token = peek();

        // Skip function definitions (possibly including body)
        if (token.type() == TokenType.NAME && token.value().equals("function")) {
            skipBlock("function", "end");
            return;
        }

        // Skip if/for/while/repeat blocks
        if (token.type() == TokenType.NAME) {
            String kw = token.value();
            if (kw.equals("if") || kw.equals("for") || kw.equals("while") || kw.equals("do")) {
                skipBlock(kw, "end");
                return;
            }
            if (kw.equals("repeat")) {
                skipBlock("repeat", "until");
                return;
            }
            if (kw.equals("return")) {
                advance(); // consume 'return'
                // Skip expression until end-of-statement indicator
                skipToStatementEnd();
                return;
            }
        }

        // Skip any other unrecognized token
        advance();
    }

    private void skipBlock(@NotNull String openKeyword, @NotNull String closeKeyword) {
        advance(); // consume opening keyword
        int depth = 1;
        while (depth > 0 && peek().type() != TokenType.EOF) {
            Token t = peek();
            if (t.type() == TokenType.NAME) {
                if (t.value().equals("function") || t.value().equals("if")
                        || t.value().equals("for") || t.value().equals("while")
                        || t.value().equals("do")) {
                    depth++;
                } else if (t.value().equals(closeKeyword)) {
                    depth--;
                } else if (t.value().equals("end") && !closeKeyword.equals("end")) {
                    // for repeat..until blocks, 'end' from nested blocks
                } else if (t.value().equals("repeat") && openKeyword.equals("repeat")) {
                    depth++;
                }
            }
            advance();
        }
    }

    private void skipToStatementEnd() {
        while (peek().type() != TokenType.EOF) {
            Token t = peek();
            // Statement ends at a NAME token that could start a new statement
            if (t.type() == TokenType.NAME && (t.value().equals("local") || t.value().equals("function")
                    || t.value().equals("if") || t.value().equals("for") || t.value().equals("while")
                    || t.value().equals("return") || t.value().equals("end"))) {
                break;
            }
            // Or at the next assignment-like pattern: NAME =
            if (t.type() == TokenType.NAME && pos + 1 < tokens.size()
                    && tokens.get(pos + 1).type() == TokenType.SYMBOL
                    && tokens.get(pos + 1).value().equals("=")) {
                break;
            }
            if (t.type() == TokenType.SYMBOL && t.value().equals(";")) {
                advance();
                break;
            }
            advance();
        }
    }

    private boolean isBlockKeyword(@NotNull String name) {
        return name.equals("function") || name.equals("if") || name.equals("for")
                || name.equals("while") || name.equals("do") || name.equals("repeat")
                || name.equals("return") || name.equals("end") || name.equals("else")
                || name.equals("elseif") || name.equals("then") || name.equals("until")
                || name.equals("break") || name.equals("goto") || name.equals("in");
    }

    private @NotNull Token peek() {
        if (pos >= tokens.size()) {
            return new Token(TokenType.EOF, "");
        }
        return tokens.get(pos);
    }

    private void advance() {
        if (pos < tokens.size()) {
            pos++;
        }
    }
}
