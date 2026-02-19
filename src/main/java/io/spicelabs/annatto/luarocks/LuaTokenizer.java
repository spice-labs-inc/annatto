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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizes a Lua source string into a list of {@link Token}s.
 *
 * <p>Supports the subset of Lua syntax used in rockspec files:
 * strings (single/double-quoted, long brackets), numbers, identifiers/keywords,
 * operators and punctuation, comments (line and block).</p>
 *
 * <p>Security limits: max input 1 MB, max 50,000 tokens.</p>
 */
final class LuaTokenizer {

    static final int MAX_INPUT_SIZE = 1024 * 1024; // 1 MB
    static final int MAX_TOKEN_COUNT = 50_000;

    enum TokenType {
        STRING, NUMBER, NAME, SYMBOL, EOF
    }

    record Token(@NotNull TokenType type, @NotNull String value) {
        @Override
        public String toString() {
            return type + "(" + value + ")";
        }
    }

    private final String input;
    private int pos;
    private final List<Token> tokens = new ArrayList<>();

    private LuaTokenizer(@NotNull String input) {
        this.input = input;
        this.pos = 0;
    }

    /**
     * Tokenizes a Lua source string.
     *
     * @param source the Lua source text
     * @return list of tokens ending with EOF
     * @throws LuaParseException if the input exceeds size/token limits or contains
     *                           unterminated strings
     */
    static @NotNull List<Token> tokenize(@NotNull String source) {
        if (source.length() > MAX_INPUT_SIZE) {
            throw new LuaParseException("Input exceeds maximum size of " + MAX_INPUT_SIZE + " bytes");
        }
        LuaTokenizer tokenizer = new LuaTokenizer(stripBom(source));
        tokenizer.doTokenize();
        return List.copyOf(tokenizer.tokens);
    }

    private static @NotNull String stripBom(@NotNull String s) {
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            return s.substring(1);
        }
        return s;
    }

    private void doTokenize() {
        while (pos < input.length()) {
            if (tokens.size() >= MAX_TOKEN_COUNT) {
                throw new LuaParseException("Token count exceeds maximum of " + MAX_TOKEN_COUNT);
            }
            skipWhitespace();
            if (pos >= input.length()) {
                break;
            }
            char c = input.charAt(pos);

            if (c == '-' && pos + 1 < input.length() && input.charAt(pos + 1) == '-') {
                skipComment();
            } else if (c == '"' || c == '\'') {
                tokens.add(readQuotedString(c));
            } else if (c == '[' && pos + 1 < input.length()
                    && (input.charAt(pos + 1) == '[' || input.charAt(pos + 1) == '=')) {
                Token longStr = tryReadLongString();
                if (longStr != null) {
                    tokens.add(longStr);
                } else {
                    tokens.add(new Token(TokenType.SYMBOL, "["));
                    pos++;
                }
            } else if (Character.isDigit(c) || (c == '.' && pos + 1 < input.length()
                    && Character.isDigit(input.charAt(pos + 1)))) {
                tokens.add(readNumber());
            } else if (isNameStart(c)) {
                tokens.add(readName());
            } else if (c == '.' && pos + 1 < input.length() && input.charAt(pos + 1) == '.') {
                tokens.add(new Token(TokenType.SYMBOL, ".."));
                pos += 2;
            } else if (isSymbol(c)) {
                tokens.add(new Token(TokenType.SYMBOL, String.valueOf(c)));
                pos++;
            } else {
                // Skip unrecognized character
                pos++;
            }
        }
        tokens.add(new Token(TokenType.EOF, ""));
    }

    private void skipWhitespace() {
        while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
            pos++;
        }
    }

    private void skipComment() {
        // Already at '--'
        pos += 2;
        // Check for block comment --[[...]] or --[=[...]=]
        if (pos < input.length() && input.charAt(pos) == '[') {
            int level = countLongBracketLevel();
            if (level >= 0) {
                skipLongBracketContent(level);
                return;
            }
        }
        // Line comment: skip to end of line
        while (pos < input.length() && input.charAt(pos) != '\n') {
            pos++;
        }
    }

    private int countLongBracketLevel() {
        int saved = pos;
        if (pos >= input.length() || input.charAt(pos) != '[') {
            return -1;
        }
        pos++;
        int level = 0;
        while (pos < input.length() && input.charAt(pos) == '=') {
            level++;
            pos++;
        }
        if (pos < input.length() && input.charAt(pos) == '[') {
            pos++; // consume second '['
            return level;
        }
        pos = saved;
        return -1;
    }

    private void skipLongBracketContent(int level) {
        String closing = "]" + "=".repeat(level) + "]";
        int idx = input.indexOf(closing, pos);
        if (idx >= 0) {
            pos = idx + closing.length();
        } else {
            pos = input.length(); // unterminated block comment — skip rest
        }
    }

    private @NotNull Token readQuotedString(char quote) {
        pos++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (c == quote) {
                pos++;
                return new Token(TokenType.STRING, sb.toString());
            } else if (c == '\\') {
                sb.append(readEscape());
            } else if (c == '\n') {
                // Lua allows newlines in quoted strings (they become literal newlines)
                sb.append('\n');
                pos++;
            } else {
                sb.append(c);
                pos++;
            }
        }
        throw new LuaParseException("Unterminated string literal");
    }

    private char readEscape() {
        pos++; // skip backslash
        if (pos >= input.length()) {
            throw new LuaParseException("Unterminated escape sequence");
        }
        char c = input.charAt(pos);
        pos++;
        return switch (c) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case '\\' -> '\\';
            case '\'' -> '\'';
            case '"' -> '"';
            case 'a' -> '\u0007';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case '\n' -> '\n';
            default -> c;
        };
    }

    private @Nullable Token tryReadLongString() {
        int saved = pos;
        int level = countLongBracketLevel();
        if (level < 0) {
            pos = saved;
            return null;
        }
        String closing = "]" + "=".repeat(level) + "]";
        int idx = input.indexOf(closing, pos);
        if (idx < 0) {
            pos = saved;
            return null;
        }
        String content = input.substring(pos, idx);
        // Per Lua spec, a newline at the very start of a long string is ignored
        if (!content.isEmpty() && content.charAt(0) == '\n') {
            content = content.substring(1);
        } else if (content.startsWith("\r\n")) {
            content = content.substring(2);
        }
        pos = idx + closing.length();
        return new Token(TokenType.STRING, content);
    }

    private @NotNull Token readNumber() {
        int start = pos;
        // Handle hex numbers
        if (pos + 1 < input.length() && input.charAt(pos) == '0'
                && (input.charAt(pos + 1) == 'x' || input.charAt(pos + 1) == 'X')) {
            pos += 2;
            while (pos < input.length() && isHexDigit(input.charAt(pos))) {
                pos++;
            }
        } else {
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
            if (pos < input.length() && input.charAt(pos) == '.') {
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            // Exponent
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
        }
        return new Token(TokenType.NUMBER, input.substring(start, pos));
    }

    private @NotNull Token readName() {
        int start = pos;
        while (pos < input.length() && isNamePart(input.charAt(pos))) {
            pos++;
        }
        return new Token(TokenType.NAME, input.substring(start, pos));
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isSymbol(char c) {
        return "={}[](),;.:#<>~+-*/%^&|".indexOf(c) >= 0;
    }

    /**
     * Thrown when Lua tokenizing or parsing fails.
     */
    static final class LuaParseException extends RuntimeException {
        LuaParseException(@NotNull String message) {
            super(message);
        }
    }
}
