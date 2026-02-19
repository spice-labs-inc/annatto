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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link LuaTokenizer}.
 *
 * <p>Each test documents the requirement section that motivated it and the
 * theory being verified.  Test corpus consists only of inline Lua source
 * strings that exercise a specific tokenization concern; no external files
 * are used.
 */
class LuaTokenizerTest {

    // -----------------------------------------------------------------------
    // String literal tests
    // -----------------------------------------------------------------------

    @Test
    void tokenize_singleQuotedString() {
        // Goal: Verify that a single-quoted Lua string literal is emitted as a
        //       STRING token whose value does not include the enclosing quotes.
        // Rationale: LuaRocks rockspec files use both quoting styles
        //            interchangeably; the extractor must handle both.
        List<Token> tokens = LuaTokenizer.tokenize("'hello'");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).isEqualTo("hello");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_doubleQuotedString() {
        // Goal: Verify that a double-quoted Lua string literal is emitted as a
        //       STRING token whose value does not include the enclosing quotes.
        // Rationale: Most rockspec string values (name, version, url) appear
        //            inside double quotes.
        List<Token> tokens = LuaTokenizer.tokenize("\"hello\"");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).isEqualTo("hello");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_longString() {
        // Goal: Verify that a level-0 long string literal [[...]] is emitted
        //       as a STRING token with the raw content between the brackets.
        // Rationale: Some rockspecs embed multi-line descriptions using long
        //            strings, which must be captured verbatim.
        List<Token> tokens = LuaTokenizer.tokenize("[[hello]]");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).isEqualTo("hello");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_longStringWithLevel() {
        // Goal: Verify that a level-2 long string [==[...]==] is parsed
        //       correctly and the content is returned without the delimiters.
        // Rationale: Rockspec authors occasionally use levelled long strings to
        //            embed text that itself contains [[ ]] sequences.
        List<Token> tokens = LuaTokenizer.tokenize("[==[hello]==]");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).isEqualTo("hello");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_escapedString() {
        // Goal: Verify that escape sequences inside a quoted string are
        //       interpreted and the resulting STRING value contains the
        //       unescaped character.
        // Rationale: Rockspec description or homepage fields may contain
        //            embedded newlines written as \n; the parser must expand
        //            these so downstream consumers receive the real text.
        List<Token> tokens = LuaTokenizer.tokenize("\"hello\\nworld\"");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).isEqualTo("hello\nworld");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_emptyString() {
        // Goal: Verify that an empty quoted string "" produces a STRING token
        //       with an empty value followed by EOF.
        // Rationale: Rockspec fields may legitimately be empty strings (e.g. an
        //            absent description set to ""); the tokenizer must not
        //            misinterpret the closing quote as the start of a new token.
        List<Token> tokens = LuaTokenizer.tokenize("\"\"");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).isEqualTo("");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    // -----------------------------------------------------------------------
    // Numeric literal tests
    // -----------------------------------------------------------------------

    @Test
    void tokenize_integerNumber() {
        // Goal: Verify that a plain decimal integer is emitted as a NUMBER
        //       token whose value is the original source text.
        // Rationale: Rockspec version strings may be constructed from numeric
        //            components; the tokenizer must recognise bare integers.
        List<Token> tokens = LuaTokenizer.tokenize("42");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.NUMBER);
        assertThat(tokens.get(0).value()).isEqualTo("42");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_floatNumber() {
        // Goal: Verify that a floating-point literal is emitted as a NUMBER
        //       token preserving the decimal representation.
        // Rationale: Some Lua numeric constants in rockspecs use decimal
        //            notation; the tokenizer must not truncate the fraction.
        List<Token> tokens = LuaTokenizer.tokenize("3.14");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.NUMBER);
        assertThat(tokens.get(0).value()).isEqualTo("3.14");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_hexNumber() {
        // Goal: Verify that a hexadecimal integer literal 0xFF is emitted as a
        //       NUMBER token with the original source text including the 0x prefix.
        // Rationale: Lua allows hex literals; they appear rarely in rockspecs
        //            but the tokenizer must not misclassify them as identifiers
        //            or produce an error.
        List<Token> tokens = LuaTokenizer.tokenize("0xFF");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.NUMBER);
        assertThat(tokens.get(0).value()).isEqualTo("0xFF");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    // -----------------------------------------------------------------------
    // Identifier / keyword tests
    // -----------------------------------------------------------------------

    @Test
    void tokenize_identifier() {
        // Goal: Verify that a bare Lua identifier is emitted as a NAME token.
        // Rationale: Rockspec field names (package, version, dependencies, …)
        //            are plain Lua identifiers and must be tokenized as NAME so
        //            that the parser can dispatch on them.
        List<Token> tokens = LuaTokenizer.tokenize("package");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.NAME);
        assertThat(tokens.get(0).value()).isEqualTo("package");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_nil() {
        // Goal: Verify that the keyword `nil` is emitted as a NAME token (not
        //       a special type), consistent with how the Lua grammar treats it.
        // Rationale: A rockspec parser that uses the token stream must be able
        //            to compare the value string to "nil" itself; there is no
        //            need for a separate NIL token type.
        List<Token> tokens = LuaTokenizer.tokenize("nil");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.NAME);
        assertThat(tokens.get(0).value()).isEqualTo("nil");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_booleans() {
        // Goal: Verify that the keywords `true` and `false` are each emitted
        //       as NAME tokens so the parser can handle boolean field values.
        // Rationale: Some rockspec fields (e.g. `build.copy_directories`) may
        //            use boolean values; the tokenizer must not drop or mangle
        //            these tokens.
        List<Token> tokens = LuaTokenizer.tokenize("true false");
        assertThat(tokens).hasSize(3);
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.NAME, "true"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.NAME, "false"));
        assertThat(tokens.get(2).type()).isEqualTo(TokenType.EOF);
    }

    // -----------------------------------------------------------------------
    // Symbol and operator tests
    // -----------------------------------------------------------------------

    @Test
    void tokenize_assignment() {
        // Goal: Verify that `x = 1` produces NAME, SYMBOL("="), NUMBER, EOF in
        //       the correct order.
        // Rationale: Assignment is the backbone of rockspec syntax; every field
        //            definition is an assignment statement that the tokenizer
        //            must render faithfully.
        List<Token> tokens = LuaTokenizer.tokenize("x = 1");
        assertThat(tokens).hasSize(4);
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.NAME, "x"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.SYMBOL, "="));
        assertThat(tokens.get(2)).isEqualTo(new Token(TokenType.NUMBER, "1"));
        assertThat(tokens.get(3).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_tableConstructor() {
        // Goal: Verify that `{a=1}` is tokenized as SYMBOL("{"), NAME("a"),
        //       SYMBOL("="), NUMBER("1"), SYMBOL("}"), EOF.
        // Rationale: Table constructors are the primary data structure in
        //            rockspecs (dependencies lists, build tables, etc.); correct
        //            brace and equals tokenization is essential.
        List<Token> tokens = LuaTokenizer.tokenize("{a=1}");
        assertThat(tokens).hasSize(6);
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.SYMBOL, "{"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.NAME, "a"));
        assertThat(tokens.get(2)).isEqualTo(new Token(TokenType.SYMBOL, "="));
        assertThat(tokens.get(3)).isEqualTo(new Token(TokenType.NUMBER, "1"));
        assertThat(tokens.get(4)).isEqualTo(new Token(TokenType.SYMBOL, "}"));
        assertThat(tokens.get(5).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_stringConcat() {
        // Goal: Verify that the `..` concatenation operator is emitted as a
        //       single SYMBOL("..") token and is not split into two SYMBOL(".")
        //       tokens.
        // Rationale: Some rockspecs build version strings with `..',` and
        //            distinguishing `.` (field access) from `..` (concat) is
        //            critical for correct parsing.
        List<Token> tokens = LuaTokenizer.tokenize("\"a\" .. \"b\"");
        assertThat(tokens).hasSize(4);
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.STRING, "a"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.SYMBOL, ".."));
        assertThat(tokens.get(2)).isEqualTo(new Token(TokenType.STRING, "b"));
        assertThat(tokens.get(3).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_dotVsDotDot() {
        // Goal: Verify that a single `.` (field access) adjacent to `..`
        //       (concatenation) are each emitted as distinct SYMBOL tokens with
        //       the correct values.
        // Rationale: Rockspecs occasionally access sub-fields (e.g. `foo.bar`)
        //            and then concatenate strings in the same expression; the
        //            tokenizer must not merge or confuse the two operators.
        List<Token> tokens = LuaTokenizer.tokenize("a.b .. c");
        assertThat(tokens).hasSize(6);
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.NAME, "a"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.SYMBOL, "."));
        assertThat(tokens.get(2)).isEqualTo(new Token(TokenType.NAME, "b"));
        assertThat(tokens.get(3)).isEqualTo(new Token(TokenType.SYMBOL, ".."));
        assertThat(tokens.get(4)).isEqualTo(new Token(TokenType.NAME, "c"));
        assertThat(tokens.get(5).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_semicolons() {
        // Goal: Verify that a semicolon statement separator is emitted as a
        //       SYMBOL(";") token and does not discard surrounding tokens.
        // Rationale: While rare, some rockspecs use semicolons to separate
        //            statements; the tokenizer must not treat them as special
        //            line terminators or skip them.
        List<Token> tokens = LuaTokenizer.tokenize("x = 1; y = 2");
        assertThat(tokens)
            .anySatisfy(t -> {
                assertThat(t.type()).isEqualTo(TokenType.SYMBOL);
                assertThat(t.value()).isEqualTo(";");
            });
    }

    // -----------------------------------------------------------------------
    // Comment tests
    // -----------------------------------------------------------------------

    @Test
    void tokenize_lineComment() {
        // Goal: Verify that a `--` line comment is completely discarded and
        //       that tokens on the following line are emitted normally.
        // Rationale: Rockspecs frequently contain comment lines documenting
        //            fields; the tokenizer must skip them without consuming
        //            subsequent code.
        List<Token> tokens = LuaTokenizer.tokenize("x = 1 -- comment\ny = 2");
        // Expect NAME x, SYMBOL =, NUMBER 1, NAME y, SYMBOL =, NUMBER 2, EOF
        assertThat(tokens).hasSize(7);
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.NAME, "x"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.SYMBOL, "="));
        assertThat(tokens.get(2)).isEqualTo(new Token(TokenType.NUMBER, "1"));
        assertThat(tokens.get(3)).isEqualTo(new Token(TokenType.NAME, "y"));
        assertThat(tokens.get(4)).isEqualTo(new Token(TokenType.SYMBOL, "="));
        assertThat(tokens.get(5)).isEqualTo(new Token(TokenType.NUMBER, "2"));
        assertThat(tokens.get(6).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_blockComment() {
        // Goal: Verify that a `--[[ ... ]]` block comment is discarded in its
        //       entirety and the tokens after it are emitted correctly.
        // Rationale: Multi-line block comments appear at the tops of some
        //            rockspec files (license preambles, etc.); the tokenizer
        //            must skip them without emitting any spurious tokens.
        List<Token> tokens = LuaTokenizer.tokenize("--[[comment]] x = 1");
        assertThat(tokens).hasSize(4);
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.NAME, "x"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.SYMBOL, "="));
        assertThat(tokens.get(2)).isEqualTo(new Token(TokenType.NUMBER, "1"));
        assertThat(tokens.get(3).type()).isEqualTo(TokenType.EOF);
    }

    // -----------------------------------------------------------------------
    // Whitespace and encoding edge cases
    // -----------------------------------------------------------------------

    @Test
    void tokenize_multilineString() {
        // Goal: Verify that a long string spanning multiple lines preserves the
        //       embedded newline characters in the STRING token value.
        // Rationale: Description fields in rockspecs are often written as
        //            multi-line long strings; the extractor relies on the full
        //            text including newlines.
        List<Token> tokens = LuaTokenizer.tokenize("[[line1\nline2]]");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        assertThat(tokens.get(0).value()).contains("\n");
        assertThat(tokens.get(0).value()).isEqualTo("line1\nline2");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_bomHandled() {
        // Goal: Verify that a UTF-8 BOM (\uFEFF) at the start of the input is
        //       silently discarded and subsequent tokens are emitted normally.
        // Rationale: Some editors save Lua files with a UTF-8 BOM; rockspec
        //            files retrieved from registries may contain one.  The
        //            tokenizer must not emit it as a stray NAME or SYMBOL token.
        String input = "\uFEFFx = 1";
        List<Token> tokens = LuaTokenizer.tokenize(input);
        assertThat(tokens).hasSize(4);
        assertThat(tokens.get(0)).isEqualTo(new Token(TokenType.NAME, "x"));
        assertThat(tokens.get(1)).isEqualTo(new Token(TokenType.SYMBOL, "="));
        assertThat(tokens.get(2)).isEqualTo(new Token(TokenType.NUMBER, "1"));
        assertThat(tokens.get(3).type()).isEqualTo(TokenType.EOF);
    }

    @Test
    void tokenize_windowsLineEndings() {
        // Goal: Verify that a string containing Windows-style \r\n line endings
        //       is tokenized without error, either preserving or normalising the
        //       carriage return.
        // Rationale: A rockspec downloaded on Windows or from a registry that
        //            does not normalise line endings may contain \r\n; the
        //            tokenizer must not crash or emit a malformed token.
        List<Token> tokens = LuaTokenizer.tokenize("\"a\r\nb\"");
        assertThat(tokens).hasSize(2);
        assertThat(tokens.get(0).type()).isEqualTo(TokenType.STRING);
        // The value must contain the 'a' and 'b' characters; \r may be
        // present or normalised away — both are acceptable.
        assertThat(tokens.get(0).value()).contains("a").contains("b");
        assertThat(tokens.get(1).type()).isEqualTo(TokenType.EOF);
    }

    // -----------------------------------------------------------------------
    // Guard-rail / resource limit tests
    // -----------------------------------------------------------------------

    @Test
    void tokenize_maxSize_throws() {
        // Goal: Verify that an input string larger than MAX_INPUT_SIZE (1 MB)
        //       causes tokenize() to throw LuaParseException rather than
        //       attempting to process an arbitrarily large buffer.
        // Rationale: Attackers could supply a crafted rockspec or a mis-fetched
        //            binary blob of multi-megabyte size.  The tokenizer must
        //            reject it early to protect heap and CPU resources.
        String oversized = "x".repeat(LuaTokenizer.MAX_INPUT_SIZE + 1);
        assertThatThrownBy(() -> LuaTokenizer.tokenize(oversized))
            .isInstanceOf(LuaParseException.class);
    }

    @Test
    void tokenize_maxTokens_throws() {
        // Goal: Verify that input that would produce more than MAX_TOKEN_COUNT
        //       (50 000) tokens causes tokenize() to throw LuaParseException.
        // Rationale: A degenerate or adversarial rockspec could contain an
        //            enormous number of small tokens.  Enforcing a token-count
        //            ceiling prevents unbounded List growth and potential OOM.
        //
        //            We build a string of the form "a,a,a,..." where each pair
        //            "a," contributes 2 tokens; 26 000 pairs yields 52 000
        //            tokens (plus EOF), comfortably over the 50 000 limit while
        //            staying under the 1 MB size limit (each pair is 2 bytes).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 26_000; i++) {
            sb.append("a,");
        }
        assertThatThrownBy(() -> LuaTokenizer.tokenize(sb.toString()))
            .isInstanceOf(LuaParseException.class);
    }

    @Test
    void tokenize_binaryGarbage_doesNotThrow() {
        // Goal: Verify that a string containing arbitrary non-Lua byte values
        //       does not cause the tokenizer to loop infinitely or throw an
        //       unexpected exception type.
        // Rationale: During corpus processing, a file handler might
        //            inadvertently pass binary content to the Lua parser.  The
        //            tokenizer must terminate (possibly by throwing
        //            LuaParseException) rather than hanging or emitting a
        //            non-deterministic token stream.
        //
        //            We use a short sequence of unusual codepoints that are not
        //            valid Lua tokens.  Termination (with or without an
        //            exception) within a reasonable time is the requirement;
        //            the test itself provides the time bound via JUnit's normal
        //            test timeout.
        byte[] garbage = {0x00, 0x01, 0x02, (byte) 0xFE, (byte) 0xFF,
                          0x1B, 0x1C, 0x7F, (byte) 0x80, (byte) 0x90};
        String input = new String(garbage, java.nio.charset.StandardCharsets.ISO_8859_1);
        // Must either return a (possibly empty except for EOF) token list or
        // throw LuaParseException — any other exception is a bug.
        try {
            List<Token> tokens = LuaTokenizer.tokenize(input);
            // If it succeeds, the last token must be EOF.
            assertThat(tokens).isNotEmpty();
            assertThat(tokens.get(tokens.size() - 1).type()).isEqualTo(TokenType.EOF);
        } catch (LuaParseException e) {
            // Acceptable outcome — binary input is invalid Lua.
        }
    }
}
