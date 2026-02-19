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

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizer for Erlang external term format used in Hex metadata.config files.
 *
 * <p>Handles:
 * <ul>
 *   <li>{@code <<"string">>} binary strings (with escape sequences)</li>
 *   <li>{@code true}, {@code false} atoms and bareword atoms</li>
 *   <li>Integer literals</li>
 *   <li>{@code {}} tuples, {@code []} lists</li>
 *   <li>{@code ,} comma, {@code .} dot separators</li>
 *   <li>{@code %} line comments</li>
 * </ul>
 *
 * <p>Security limits: max input 1 MB, max 50,000 tokens.</p>
 */
final class ErlangTermTokenizer {

    static final int MAX_INPUT_SIZE = 1024 * 1024; // 1 MB
    static final int MAX_TOKEN_COUNT = 50_000;

    enum TokenType {
        BINARY_OPEN,   // <<
        BINARY_CLOSE,  // >>
        STRING,        // "..."
        ATOM,          // true, false, bareword
        INTEGER,       // 123
        TUPLE_OPEN,    // {
        TUPLE_CLOSE,   // }
        LIST_OPEN,     // [
        LIST_CLOSE,    // ]
        COMMA,         // ,
        DOT,           // .
        EOF
    }

    record Token(@NotNull TokenType type, @NotNull String value) {
        @Override
        public String toString() {
            return type + "(" + value + ")";
        }
    }

    private final String input;
    private int pos;
    private final List<Token> tokens;

    ErlangTermTokenizer(@NotNull String input) {
        if (input.length() > MAX_INPUT_SIZE) {
            throw new ErlangTermException("Input exceeds maximum size of " + MAX_INPUT_SIZE + " bytes");
        }
        this.input = input;
        this.pos = 0;
        this.tokens = new ArrayList<>();
    }

    /**
     * Tokenizes the entire input and returns the list of tokens.
     *
     * @return immutable list of tokens (always ends with EOF)
     * @throws ErlangTermException if tokenization fails or limits exceeded
     */
    @NotNull List<Token> tokenize() {
        while (pos < input.length()) {
            skipWhitespaceAndComments();
            if (pos >= input.length()) break;

            char c = input.charAt(pos);

            if (c == '<' && peek(1) == '<') {
                addToken(TokenType.BINARY_OPEN, "<<");
                pos += 2;
            } else if (c == '>' && peek(1) == '>') {
                addToken(TokenType.BINARY_CLOSE, ">>");
                pos += 2;
            } else if (c == '"') {
                tokens.add(readString());
                checkTokenLimit();
            } else if (c == '{') {
                addToken(TokenType.TUPLE_OPEN, "{");
                pos++;
            } else if (c == '}') {
                addToken(TokenType.TUPLE_CLOSE, "}");
                pos++;
            } else if (c == '[') {
                addToken(TokenType.LIST_OPEN, "[");
                pos++;
            } else if (c == ']') {
                addToken(TokenType.LIST_CLOSE, "]");
                pos++;
            } else if (c == ',') {
                addToken(TokenType.COMMA, ",");
                pos++;
            } else if (c == '.') {
                addToken(TokenType.DOT, ".");
                pos++;
            } else if (c == '-' && pos + 1 < input.length() && Character.isDigit(input.charAt(pos + 1))) {
                tokens.add(readInteger());
                checkTokenLimit();
            } else if (Character.isDigit(c)) {
                tokens.add(readInteger());
                checkTokenLimit();
            } else if (Character.isLetter(c) || c == '_') {
                tokens.add(readAtom());
                checkTokenLimit();
            } else {
                throw new ErlangTermException("Unexpected character '" + c + "' at position " + pos);
            }
        }
        tokens.add(new Token(TokenType.EOF, ""));
        return List.copyOf(tokens);
    }

    private void skipWhitespaceAndComments() {
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (c == '%') {
                // Line comment: skip to end of line
                while (pos < input.length() && input.charAt(pos) != '\n') {
                    pos++;
                }
            } else {
                break;
            }
        }
    }

    private Token readString() {
        pos++; // skip opening "
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == '\\' && pos + 1 < input.length()) {
                char next = input.charAt(pos + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); pos += 2; }
                    case 't' -> { sb.append('\t'); pos += 2; }
                    case 'r' -> { sb.append('\r'); pos += 2; }
                    case '\\' -> { sb.append('\\'); pos += 2; }
                    case '"' -> { sb.append('"'); pos += 2; }
                    default -> { sb.append(next); pos += 2; }
                }
            } else if (c == '"') {
                pos++; // skip closing "
                // Handle Erlang binary type specifier: "string"/utf8
                skipBinaryTypeSpecifier();
                return new Token(TokenType.STRING, sb.toString());
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new ErlangTermException("Unterminated string starting near position " + pos);
    }

    /**
     * Skips an optional Erlang binary type specifier (e.g., /utf8) after a string.
     * In Erlang, <<"text"/utf8>> has a /utf8 encoding modifier.
     */
    private void skipBinaryTypeSpecifier() {
        if (pos < input.length() && input.charAt(pos) == '/') {
            pos++; // skip /
            // Read the specifier name (e.g., utf8, integer, binary, etc.)
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos))
                    || input.charAt(pos) == '_')) {
                pos++;
            }
        }
    }

    private Token readInteger() {
        int start = pos;
        if (input.charAt(pos) == '-') {
            pos++;
        }
        while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
            pos++;
        }
        return new Token(TokenType.INTEGER, input.substring(start, pos));
    }

    private Token readAtom() {
        int start = pos;
        while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos))
                || input.charAt(pos) == '_' || input.charAt(pos) == '@')) {
            pos++;
        }
        return new Token(TokenType.ATOM, input.substring(start, pos));
    }

    private char peek(int offset) {
        int idx = pos + offset;
        return idx < input.length() ? input.charAt(idx) : '\0';
    }

    private void addToken(TokenType type, String value) {
        tokens.add(new Token(type, value));
        checkTokenLimit();
    }

    private void checkTokenLimit() {
        if (tokens.size() > MAX_TOKEN_COUNT) {
            throw new ErlangTermException("Token count exceeds maximum of " + MAX_TOKEN_COUNT);
        }
    }
}
