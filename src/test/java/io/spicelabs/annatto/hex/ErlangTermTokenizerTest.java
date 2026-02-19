/* Copyright 2026 Spice Labs, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

package io.spicelabs.annatto.hex;

import io.spicelabs.annatto.hex.ErlangTermTokenizer.Token;
import io.spicelabs.annatto.hex.ErlangTermTokenizer.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ErlangTermTokenizer}.
 */
class ErlangTermTokenizerTest {

    // --- Binary string tokens ---

    /**
     * Goal: Tokenize binary string delimiters.
     * Rationale: Q2 — binary strings are <<"...">> in Erlang term format.
     */
    @Test
    void tokenize_binaryString() {
        List<Token> tokens = new ErlangTermTokenizer("<<\"hello\">>").tokenize();
        assertThat(tokens).hasSize(4); // BINARY_OPEN, STRING, BINARY_CLOSE, EOF
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.BINARY_OPEN);
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(1).value()).isEqualTo("hello");
        assertThat(tokens.get(2).type()).isEqualTo(TokenType.BINARY_CLOSE);
        assertThat(tokens.get(3).type()).isEqualTo(TokenType.EOF);
    }

    /**
     * Goal: Tokenize string with escape sequences.
     * Rationale: Erlang binaries can contain escaped characters.
     */
    @Test
    void tokenize_stringWithEscapes() {
        List<Token> tokens = new ErlangTermTokenizer("<<\"he\\\"llo\\n\">>").tokenize();
        assertThat(tokens.get(1).value()).isEqualTo("he\"llo\n");
    }

    /**
     * Goal: Tokenize empty binary string.
     * Rationale: Empty binaries <<>> should tokenize to BINARY_OPEN, BINARY_CLOSE.
     */
    @Test
    void tokenize_emptyBinary() {
        List<Token> tokens = new ErlangTermTokenizer("<<>>").tokenize();
        assertThat(tokens).hasSize(3); // BINARY_OPEN, BINARY_CLOSE, EOF
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.BINARY_OPEN);
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.BINARY_CLOSE);
    }

    // --- Atoms ---

    /**
     * Goal: Tokenize boolean atoms.
     * Rationale: true/false are common in Hex metadata (e.g., optional field).
     */
    @Test
    void tokenize_booleanAtoms() {
        List<Token> tokens = new ErlangTermTokenizer("true false").tokenize();
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.ATOM, "true"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.ATOM, "false"));
    }

    /**
     * Goal: Tokenize bareword atoms.
     * Rationale: Atoms like 'hexpm' appear in requirement repositories.
     */
    @Test
    void tokenize_barewordAtom() {
        List<Token> tokens = new ErlangTermTokenizer("hexpm").tokenize();
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.ATOM, "hexpm"));
    }

    // --- Integers ---

    /**
     * Goal: Tokenize positive integers.
     * Rationale: Integer values may appear in metadata.
     */
    @Test
    void tokenize_positiveInteger() {
        List<Token> tokens = new ErlangTermTokenizer("42").tokenize();
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.INTEGER, "42"));
    }

    /**
     * Goal: Tokenize negative integers.
     * Rationale: Negative numbers should be handled correctly.
     */
    @Test
    void tokenize_negativeInteger() {
        List<Token> tokens = new ErlangTermTokenizer("-7").tokenize();
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.INTEGER, "-7"));
    }

    // --- Structural tokens ---

    /**
     * Goal: Tokenize tuple delimiters.
     * Rationale: Tuples {key, value} are the core structure in metadata.config.
     */
    @Test
    void tokenize_tupleDelimiters() {
        List<Token> tokens = new ErlangTermTokenizer("{,}").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.TUPLE_OPEN);
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.COMMA);
        assertThat(tokens.get(2).type()).isEqualTo(TokenType.TUPLE_CLOSE);
    }

    /**
     * Goal: Tokenize list delimiters.
     * Rationale: Lists [elem1, elem2] hold dependencies and licenses.
     */
    @Test
    void tokenize_listDelimiters() {
        List<Token> tokens = new ErlangTermTokenizer("[,]").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.LIST_OPEN);
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.COMMA);
        assertThat(tokens.get(2).type()).isEqualTo(TokenType.LIST_CLOSE);
    }

    /**
     * Goal: Tokenize dot terminators.
     * Rationale: Each top-level statement ends with a dot.
     */
    @Test
    void tokenize_dot() {
        List<Token> tokens = new ErlangTermTokenizer(".").tokenize();
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.DOT);
    }

    // --- Whitespace and comments ---

    /**
     * Goal: Skip whitespace between tokens.
     * Rationale: Metadata.config has varied whitespace formatting.
     */
    @Test
    void tokenize_skipsWhitespace() {
        List<Token> tokens = new ErlangTermTokenizer("  true   false  \n  42  ").tokenize();
        assertThat(tokens).hasSize(4); // true, false, 42, EOF
    }

    /**
     * Goal: Skip line comments.
     * Rationale: Erlang term files can have % line comments.
     */
    @Test
    void tokenize_skipsComments() {
        List<Token> tokens = new ErlangTermTokenizer("% this is a comment\ntrue").tokenize();
        assertThat(tokens).hasSize(2); // true, EOF
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.ATOM, "true"));
    }

    // --- Full statement ---

    /**
     * Goal: Tokenize a complete key-value statement.
     * Rationale: The basic unit in metadata.config is {<<"key">>, value}.
     */
    @Test
    void tokenize_fullStatement() {
        String input = "{<<\"name\">>,<<\"jason\">>}.";
        List<Token> tokens = new ErlangTermTokenizer(input).tokenize();
        assertThat(tokens.stream().map(Token::type).toList()).containsExactly(
                TokenType.TUPLE_OPEN,
                TokenType.BINARY_OPEN, TokenType.STRING, TokenType.BINARY_CLOSE,
                TokenType.COMMA,
                TokenType.BINARY_OPEN, TokenType.STRING, TokenType.BINARY_CLOSE,
                TokenType.TUPLE_CLOSE,
                TokenType.DOT,
                TokenType.EOF
        );
    }

    // --- Binary type specifier ---

    /**
     * Goal: Tokenize binary with /utf8 encoding specifier.
     * Rationale: Some Erlang binaries use <<"text"/utf8>> for Unicode content.
     */
    @Test
    void tokenize_binaryWithUtf8Specifier() {
        List<Token> tokens = new ErlangTermTokenizer("<<\"née\"/utf8>>").tokenize();
        assertThat(tokens).hasSize(4); // BINARY_OPEN, STRING, BINARY_CLOSE, EOF
        assertThat(tokens.get(1).value()).isEqualTo("née");
    }

    // --- Error handling ---

    /**
     * Goal: Reject input exceeding max size.
     * Rationale: Security — prevent DoS via oversized input.
     */
    @Test
    void tokenize_rejectsOversizedInput() {
        String huge = "a".repeat(ErlangTermTokenizer.MAX_INPUT_SIZE + 1);
        assertThatThrownBy(() -> new ErlangTermTokenizer(huge))
                .isInstanceOf(ErlangTermException.class)
                .hasMessageContaining("exceeds maximum size");
    }

    /**
     * Goal: Reject input producing too many tokens.
     * Rationale: Security — prevent DoS via inputs that generate excessive tokens.
     */
    @Test
    void tokenize_rejectsExcessiveTokenCount() {
        // Each "42," generates 2 tokens (INTEGER + COMMA); need > 50000 tokens
        StringBuilder sb = new StringBuilder("{<<\"k\">>,[");
        int pairsNeeded = (ErlangTermTokenizer.MAX_TOKEN_COUNT / 2) + 1;
        for (int i = 0; i < pairsNeeded; i++) {
            if (i > 0) sb.append(",");
            sb.append("42");
        }
        sb.append("]}.");
        String input = sb.toString();
        assertThatThrownBy(() -> new ErlangTermTokenizer(input).tokenize())
                .isInstanceOf(ErlangTermException.class)
                .hasMessageContaining("Token count exceeds maximum");
    }

    /**
     * Goal: Reject unexpected characters.
     * Rationale: Malformed input must produce a clear error.
     */
    @Test
    void tokenize_rejectsUnexpectedChar() {
        assertThatThrownBy(() -> new ErlangTermTokenizer("@").tokenize())
                .isInstanceOf(ErlangTermException.class)
                .hasMessageContaining("Unexpected character");
    }

    /**
     * Goal: Reject unterminated strings.
     * Rationale: A missing closing quote must produce an error.
     */
    @Test
    void tokenize_rejectsUnterminatedString() {
        assertThatThrownBy(() -> new ErlangTermTokenizer("<<\"hello>>").tokenize())
                .isInstanceOf(ErlangTermException.class)
                .hasMessageContaining("Unterminated string");
    }

    /**
     * Goal: Tokenize empty input.
     * Rationale: Empty input should produce only EOF.
     */
    @Test
    void tokenize_emptyInput() {
        List<Token> tokens = new ErlangTermTokenizer("").tokenize();
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.EOF);
    }

    // --- Real metadata.config snippet ---

    /**
     * Goal: Tokenize a real metadata.config fragment.
     * Rationale: Validates tokenizer against actual Hex package data.
     */
    @Test
    void tokenize_realMetadataFragment() {
        String input = """
                {<<"name">>,<<"jason">>}.
                {<<"version">>,<<"1.4.1">>}.
                {<<"licenses">>,[<<"Apache-2.0">>]}.
                """;
        List<Token> tokens = new ErlangTermTokenizer(input).tokenize();
        // Should not throw, and should end with EOF
        assertThat(tokens.getLast().type()).isEqualTo(TokenType.EOF);
        // Check we got the key strings
        List<String> strings = tokens.stream()
                .filter(t -> t.type() == TokenType.STRING)
                .map(Token::value)
                .toList();
        assertThat(strings).contains("name", "jason", "version", "1.4.1", "licenses", "Apache-2.0");
    }
}
