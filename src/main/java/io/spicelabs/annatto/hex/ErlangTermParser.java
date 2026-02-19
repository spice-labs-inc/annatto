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

import io.spicelabs.annatto.hex.ErlangTermTokenizer.Token;
import io.spicelabs.annatto.hex.ErlangTermTokenizer.TokenType;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser for Erlang external term format used in Hex metadata.config files.
 *
 * <p>Parses a sequence of {@code {<<"key">>, value}.} statements into a
 * {@code Map<String, Object>} where values can be:</p>
 * <ul>
 *   <li>{@link String} — from binary strings {@code <<"...">>}</li>
 *   <li>{@link Boolean} — from atoms {@code true}/{@code false}</li>
 *   <li>{@link Long} — from integer literals</li>
 *   <li>{@link List} — from Erlang lists {@code [...]}</li>
 *   <li>{@link Map} — from proplists (list of 2-tuples)</li>
 * </ul>
 *
 * <p>Security limits: max nesting depth 10, max 10,000 elements.</p>
 */
final class ErlangTermParser {

    static final int MAX_NESTING_DEPTH = 10;
    static final int MAX_ELEMENTS = 10_000;

    private final List<Token> tokens;
    private int pos;
    private int elementCount;

    ErlangTermParser(@NotNull List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
        this.elementCount = 0;
    }

    /**
     * Parses the full metadata.config content: a sequence of
     * {@code {Key, Value}.} top-level statements.
     *
     * @return map of key → parsed value
     * @throws ErlangTermException if parsing fails or limits exceeded
     */
    @NotNull Map<String, Object> parseMetadataConfig() {
        Map<String, Object> result = new LinkedHashMap<>();

        while (current().type() != TokenType.EOF) {
            // Each top-level statement is {Key, Value}.
            expect(TokenType.TUPLE_OPEN);
            Object key = parseValue(0);
            expect(TokenType.COMMA);
            Object value = parseValue(0);
            expect(TokenType.TUPLE_CLOSE);
            expect(TokenType.DOT);

            if (key instanceof String keyStr) {
                result.put(keyStr, value);
            }
        }

        return result;
    }

    /**
     * Parses a single Erlang term value at the given nesting depth.
     */
    private Object parseValue(int depth) {
        if (depth > MAX_NESTING_DEPTH) {
            throw new ErlangTermException("Nesting depth exceeds maximum of " + MAX_NESTING_DEPTH);
        }
        incrementElementCount();

        Token tok = current();

        return switch (tok.type()) {
            case BINARY_OPEN -> parseBinaryString();
            case STRING -> {
                advance();
                yield tok.value();
            }
            case ATOM -> {
                advance();
                yield switch (tok.value()) {
                    case "true" -> Boolean.TRUE;
                    case "false" -> Boolean.FALSE;
                    case "nil" -> null;
                    default -> tok.value(); // other atoms as strings
                };
            }
            case INTEGER -> {
                advance();
                yield Long.parseLong(tok.value());
            }
            case LIST_OPEN -> parseList(depth);
            case TUPLE_OPEN -> parseTuple(depth);
            default -> throw new ErlangTermException(
                    "Unexpected token " + tok + " at position " + pos);
        };
    }

    /**
     * Parses a binary string: {@code <<"content">>}.
     */
    private String parseBinaryString() {
        expect(TokenType.BINARY_OPEN);
        Token strTok = current();
        if (strTok.type() == TokenType.BINARY_CLOSE) {
            // Empty binary: <<>>
            advance();
            return "";
        }
        if (strTok.type() != TokenType.STRING) {
            throw new ErlangTermException("Expected string inside binary at position " + pos);
        }
        advance();
        expect(TokenType.BINARY_CLOSE);
        return strTok.value();
    }

    /**
     * Parses a list: {@code [elem1, elem2, ...]}.
     * If the list looks like a proplist (all elements are 2-tuples with string keys),
     * converts to a Map.
     */
    private Object parseList(int depth) {
        expect(TokenType.LIST_OPEN);
        List<Object> items = new ArrayList<>();

        if (current().type() != TokenType.LIST_CLOSE) {
            items.add(parseValue(depth + 1));
            while (current().type() == TokenType.COMMA) {
                advance();
                if (current().type() == TokenType.LIST_CLOSE) break; // trailing comma
                items.add(parseValue(depth + 1));
            }
        }

        expect(TokenType.LIST_CLOSE);

        // Check if this is a proplist (list of 2-tuples with string keys)
        if (isProplist(items)) {
            return proplistToMap(items);
        }

        return items;
    }

    /**
     * Parses a tuple: {@code {elem1, elem2, ...}}.
     * Returns as a list internally (tuples don't have a Java equivalent).
     */
    private Object parseTuple(int depth) {
        expect(TokenType.TUPLE_OPEN);
        List<Object> items = new ArrayList<>();

        if (current().type() != TokenType.TUPLE_CLOSE) {
            items.add(parseValue(depth + 1));
            while (current().type() == TokenType.COMMA) {
                advance();
                if (current().type() == TokenType.TUPLE_CLOSE) break;
                items.add(parseValue(depth + 1));
            }
        }

        expect(TokenType.TUPLE_CLOSE);

        // 2-tuples are returned as-is (list of 2 elements)
        return items;
    }

    /**
     * Checks if a list is a proplist: every element is a 2-element list
     * (parsed from tuple) with a String first element.
     */
    @SuppressWarnings("unchecked")
    private boolean isProplist(List<Object> items) {
        if (items.isEmpty()) return false;
        for (Object item : items) {
            if (!(item instanceof List<?> tuple)) return false;
            if (tuple.size() != 2) return false;
            if (!(tuple.get(0) instanceof String)) return false;
        }
        return true;
    }

    /**
     * Converts a proplist (list of 2-element tuples) to a Map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> proplistToMap(List<Object> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Object item : items) {
            List<Object> tuple = (List<Object>) item;
            String key = (String) tuple.get(0);
            map.put(key, tuple.get(1));
        }
        return map;
    }

    private Token current() {
        return pos < tokens.size() ? tokens.get(pos) : new Token(TokenType.EOF, "");
    }

    private void advance() {
        pos++;
    }

    private void expect(TokenType type) {
        Token tok = current();
        if (tok.type() != type) {
            throw new ErlangTermException(
                    "Expected " + type + " but got " + tok.type() + " at position " + pos);
        }
        advance();
    }

    private void incrementElementCount() {
        elementCount++;
        if (elementCount > MAX_ELEMENTS) {
            throw new ErlangTermException("Element count exceeds maximum of " + MAX_ELEMENTS);
        }
    }
}
