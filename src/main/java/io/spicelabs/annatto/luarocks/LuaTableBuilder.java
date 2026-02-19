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

import io.spicelabs.annatto.luarocks.LuaTokenizer.LuaParseException;
import io.spicelabs.annatto.luarocks.LuaTokenizer.Token;
import io.spicelabs.annatto.luarocks.LuaTokenizer.TokenType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates Lua expressions into Java objects given an environment map.
 *
 * <p>Supports the subset of Lua expressions found in rockspec files:
 * string/number/boolean/nil literals, table constructors, string concatenation
 * ({@code ..}), variable references, parenthesized expressions, bracket-key syntax.</p>
 *
 * <p>Unsupported constructs (function calls, arithmetic, method calls) return null.</p>
 *
 * <p>Security limits: max nesting depth 20, max concatenated string length 1 MB.</p>
 */
final class LuaTableBuilder {

    static final int MAX_NESTING_DEPTH = 20;
    static final int MAX_STRING_LENGTH = 1024 * 1024; // 1 MB
    static final int MAX_TABLE_ELEMENTS = 10_000;

    private final List<Token> tokens;
    private final Map<String, Object> env;
    private int pos;
    private int depth;

    private LuaTableBuilder(@NotNull List<Token> tokens, @NotNull Map<String, Object> env) {
        this.tokens = tokens;
        this.env = env;
        this.pos = 0;
        this.depth = 0;
    }

    /**
     * Evaluates a Lua expression from a token list starting at position 0.
     *
     * @param tokens the token list
     * @param env    the variable environment
     * @return the evaluated expression value, or null if unsupported
     */
    static @Nullable Object evaluate(@NotNull List<Token> tokens, @NotNull Map<String, Object> env) {
        LuaTableBuilder builder = new LuaTableBuilder(tokens, env);
        return builder.parseExpression();
    }

    /**
     * Evaluates a Lua expression from a token list starting at a given position.
     * Returns the position after the expression via the result.
     *
     * @param tokens   the token list
     * @param env      the variable environment
     * @param startPos the starting position in the token list
     * @return the parse result containing value and end position
     */
    static @NotNull ParseResult evaluateAt(@NotNull List<Token> tokens,
            @NotNull Map<String, Object> env, int startPos) {
        LuaTableBuilder builder = new LuaTableBuilder(tokens, env);
        builder.pos = startPos;
        Object value = builder.parseExpression();
        return new ParseResult(value, builder.pos);
    }

    record ParseResult(@Nullable Object value, int endPos) {
    }

    private @Nullable Object parseExpression() {
        Object left = parsePrimary();
        // Check for method call: value:method(args)  — supports string.format pattern
        left = tryMethodCall(left);
        // Check for string concatenation (..)
        while (peek().type() == TokenType.SYMBOL && peek().value().equals("..")) {
            advance(); // consume ..
            Object right = parsePrimary();
            right = tryMethodCall(right);
            left = concatValues(left, right);
        }
        return left;
    }

    /**
     * Tries to parse a method call ({@code :method(args)}) on a value.
     * Currently supports {@code string:format(...)} which is common in rockspecs.
     */
    private @Nullable Object tryMethodCall(@Nullable Object value) {
        if (peek().type() != TokenType.SYMBOL || !peek().value().equals(":")) {
            return value;
        }
        advance(); // consume :
        if (peek().type() != TokenType.NAME) {
            return null;
        }
        String method = peek().value();
        advance(); // consume method name

        // Collect arguments
        if (peek().type() != TokenType.SYMBOL || !peek().value().equals("(")) {
            return null;
        }
        advance(); // consume (
        List<Object> args = new ArrayList<>();
        while (peek().type() != TokenType.EOF) {
            if (peek().type() == TokenType.SYMBOL && peek().value().equals(")")) {
                advance(); // consume )
                break;
            }
            if (peek().type() == TokenType.SYMBOL && peek().value().equals(",")) {
                advance();
                continue;
            }
            args.add(parseExpression());
        }

        // Apply known methods
        if ("format".equals(method) && value instanceof String fmt) {
            return applyStringFormat(fmt, args);
        }
        return null; // unsupported method
    }

    /**
     * Applies Lua's string.format using Java's String.format.
     * Only handles %s and %d, which are the common specifiers in rockspecs.
     */
    private @Nullable String applyStringFormat(@NotNull String fmt, @NotNull List<Object> args) {
        try {
            Object[] javaArgs = args.stream()
                    .map(a -> a instanceof Number n ? (Object) n : String.valueOf(a))
                    .toArray();
            return String.format(fmt, javaArgs);
        } catch (Exception e) {
            return null;
        }
    }

    private @Nullable Object parsePrimary() {
        Token token = peek();
        return switch (token.type()) {
            case STRING -> {
                advance();
                yield token.value();
            }
            case NUMBER -> {
                advance();
                yield parseNumber(token.value());
            }
            case NAME -> parseNameExpr();
            case SYMBOL -> {
                if (token.value().equals("{")) {
                    yield parseTable();
                } else if (token.value().equals("(")) {
                    advance(); // consume (
                    Object inner = parseExpression();
                    if (peek().type() == TokenType.SYMBOL && peek().value().equals(")")) {
                        advance(); // consume )
                    }
                    yield inner;
                } else if (token.value().equals("-")) {
                    advance(); // consume -
                    Object operand = parsePrimary();
                    if (operand instanceof Number n) {
                        yield -n.doubleValue();
                    }
                    yield null;
                } else {
                    yield null;
                }
            }
            case EOF -> null;
        };
    }

    private @Nullable Object parseNameExpr() {
        Token token = peek();
        advance();
        String name = token.value();

        // Handle Lua keywords
        return switch (name) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "nil" -> null;
            case "not" -> {
                parsePrimary(); // consume the operand
                yield null; // unsupported
            }
            default -> {
                // Check for function call: name(...)
                if (peek().type() == TokenType.SYMBOL && peek().value().equals("(")) {
                    // Function call — unsupported, skip and return null
                    skipFunctionCall();
                    yield null;
                }
                // Check for dotted access: name.field (table field read)
                if (peek().type() == TokenType.SYMBOL && peek().value().equals(".")) {
                    yield parseDottedAccess(name);
                }
                // Variable reference
                yield env.get(name);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private @Nullable Object parseDottedAccess(@NotNull String firstName) {
        Object current = env.get(firstName);
        while (peek().type() == TokenType.SYMBOL && peek().value().equals(".")) {
            advance(); // consume .
            if (peek().type() != TokenType.NAME) {
                return null;
            }
            String field = peek().value();
            advance();
            if (current instanceof Map<?, ?> map) {
                current = map.get(field);
            } else {
                return null;
            }
        }
        return current;
    }

    private void skipFunctionCall() {
        advance(); // consume (
        int depth = 1;
        while (depth > 0 && peek().type() != TokenType.EOF) {
            Token t = peek();
            advance();
            if (t.type() == TokenType.SYMBOL) {
                if (t.value().equals("(")) depth++;
                else if (t.value().equals(")")) depth--;
            }
        }
    }

    private @Nullable Object parseTable() {
        if (depth >= MAX_NESTING_DEPTH) {
            throw new LuaParseException("Table nesting depth exceeds maximum of " + MAX_NESTING_DEPTH);
        }
        depth++;
        advance(); // consume {

        Map<String, Object> hashPart = new LinkedHashMap<>();
        List<Object> arrayPart = new ArrayList<>();
        boolean isArray = true;

        while (peek().type() != TokenType.EOF) {
            if (arrayPart.size() + hashPart.size() > MAX_TABLE_ELEMENTS) {
                throw new LuaParseException("Table element count exceeds maximum of " + MAX_TABLE_ELEMENTS);
            }
            Token t = peek();
            if (t.type() == TokenType.SYMBOL && t.value().equals("}")) {
                advance(); // consume }
                depth--;
                return buildTableResult(hashPart, arrayPart, isArray);
            }

            // Skip separators
            if (t.type() == TokenType.SYMBOL && (t.value().equals(",") || t.value().equals(";"))) {
                advance();
                continue;
            }

            // Check for key = value
            if (t.type() == TokenType.NAME && lookahead(1).type() == TokenType.SYMBOL
                    && lookahead(1).value().equals("=")) {
                String key = t.value();
                advance(); // consume name
                advance(); // consume =
                Object value = parseExpression();
                hashPart.put(key, value);
                isArray = false;
            } else if (t.type() == TokenType.SYMBOL && t.value().equals("[")) {
                // Bracket key: [expr] = value
                advance(); // consume [
                Object key = parseExpression();
                if (peek().type() == TokenType.SYMBOL && peek().value().equals("]")) {
                    advance(); // consume ]
                }
                if (peek().type() == TokenType.SYMBOL && peek().value().equals("=")) {
                    advance(); // consume =
                }
                Object value = parseExpression();
                if (key instanceof String s) {
                    hashPart.put(s, value);
                } else if (key instanceof Number n) {
                    hashPart.put(String.valueOf(n.intValue()), value);
                }
                isArray = false;
            } else {
                // Array element
                int before = pos;
                Object value = parseExpression();
                if (pos == before) {
                    // parseExpression didn't consume any tokens — skip one to prevent infinite loop
                    advance();
                    continue;
                }
                if (value == null && peek().type() == TokenType.SYMBOL && peek().value().equals("}")) {
                    // null at the end of a table — skip
                } else {
                    arrayPart.add(value);
                }
            }
        }

        depth--;
        // End of tokens without closing brace
        return buildTableResult(hashPart, arrayPart, !arrayPart.isEmpty());
    }

    /**
     * Builds the final table result, filtering out null values.
     * Null values in Lua tables mean "key doesn't exist", and {@code Map.copyOf} /
     * {@code List.copyOf} reject nulls.
     */
    private @Nullable Object buildTableResult(@NotNull Map<String, Object> hashPart,
            @NotNull List<Object> arrayPart, boolean isArray) {
        if (isArray && !arrayPart.isEmpty()) {
            arrayPart.removeIf(java.util.Objects::isNull);
            return List.copyOf(arrayPart);
        }
        if (!hashPart.isEmpty()) {
            hashPart.values().removeIf(java.util.Objects::isNull);
            return Map.copyOf(hashPart);
        }
        if (isArray) {
            return List.of();
        }
        return Map.of();
    }

    private @Nullable Object concatValues(@Nullable Object left, @Nullable Object right) {
        if (left == null || right == null) {
            return null;
        }
        String l = objectToString(left);
        String r = objectToString(right);
        if (l == null || r == null) {
            return null;
        }
        if ((long) l.length() + r.length() > MAX_STRING_LENGTH) {
            throw new LuaParseException(
                    "Concatenated string exceeds maximum length of " + MAX_STRING_LENGTH);
        }
        return l + r;
    }

    private @Nullable String objectToString(@Nullable Object obj) {
        if (obj instanceof String s) {
            return s;
        }
        if (obj instanceof Number n) {
            if (n.doubleValue() == Math.floor(n.doubleValue()) && !Double.isInfinite(n.doubleValue())) {
                return String.valueOf(n.intValue());
            }
            return String.valueOf(n);
        }
        return null;
    }

    private @Nullable Number parseNumber(@NotNull String value) {
        try {
            if (value.startsWith("0x") || value.startsWith("0X")) {
                return Integer.parseInt(value.substring(2), 16);
            }
            if (value.contains(".") || value.contains("e") || value.contains("E")) {
                return Double.parseDouble(value);
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private @NotNull Token peek() {
        if (pos >= tokens.size()) {
            return new Token(TokenType.EOF, "");
        }
        return tokens.get(pos);
    }

    private @NotNull Token lookahead(int offset) {
        int idx = pos + offset;
        if (idx >= tokens.size()) {
            return new Token(TokenType.EOF, "");
        }
        return tokens.get(idx);
    }

    private void advance() {
        if (pos < tokens.size()) {
            pos++;
        }
    }
}
