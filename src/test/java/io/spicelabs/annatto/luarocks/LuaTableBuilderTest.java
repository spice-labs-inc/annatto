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
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link LuaTableBuilder#evaluate(List, Map)}.
 *
 * <p>Each test documents the requirement section that motivated it and the
 * theory being verified. All token lists are produced by calling
 * {@link LuaTokenizer#tokenize(String)} on an inline Lua expression string
 * so that the tokenizer and builder are tested together at the expression level.
 */
class LuaTableBuilderTest {

    // -----------------------------------------------------------------------
    // Helper: produce a token list (minus EOF) for a Lua expression string
    // -----------------------------------------------------------------------

    private static List<Token> tokens(String source) {
        return LuaTokenizer.tokenize(source);
    }

    private static Object eval(String source) {
        return LuaTableBuilder.evaluate(tokens(source), Map.of());
    }

    private static Object eval(String source, Map<String, Object> env) {
        return LuaTableBuilder.evaluate(tokens(source), env);
    }

    // -----------------------------------------------------------------------
    // Literal value tests
    // -----------------------------------------------------------------------

    @Test
    void evaluate_stringLiteral() {
        // Goal: Verify that a double-quoted string expression evaluates to the
        //       corresponding Java String with the quote delimiters stripped.
        // Requirement: LuaTableBuilder must return the unquoted string value for
        //              STRING tokens so that rockspec fields like package and version
        //              are correctly surfaced as Java String objects.
        // Rationale: The most common rockspec value type is a quoted string;
        //            correct handling is foundational to the entire extractor.
        Object result = eval("\"hello\"");
        assertThat(result).isInstanceOf(String.class);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void evaluate_numberLiteral() {
        // Goal: Verify that a bare integer literal evaluates to a Java Integer.
        // Requirement: LuaTableBuilder must parse NUMBER tokens and return them as
        //              numeric Java values so downstream code can inspect them.
        // Rationale: Some rockspec fields (e.g. build system parameters) are
        //            integers; the builder must not return them as raw strings.
        Object result = eval("42");
        assertThat(result).isInstanceOf(Integer.class);
        assertThat(result).isEqualTo(42);
    }

    @Test
    void evaluate_booleanTrue() {
        // Goal: Verify that the NAME token "true" evaluates to Boolean.TRUE.
        // Requirement: LuaTableBuilder must treat the Lua keyword "true" as the
        //              Java boolean true rather than returning it as a String.
        // Rationale: Rockspec fields such as build.copy_directories may use
        //            boolean literals; incorrect mapping would produce wrong metadata.
        Object result = eval("true");
        assertThat(result).isEqualTo(Boolean.TRUE);
    }

    @Test
    void evaluate_booleanFalse() {
        // Goal: Verify that the NAME token "false" evaluates to Boolean.FALSE.
        // Requirement: LuaTableBuilder must treat the Lua keyword "false" as the
        //              Java boolean false rather than a String or null.
        // Rationale: Symmetric with evaluate_booleanTrue; both keyword values must
        //            be handled to avoid asymmetric parsing errors.
        Object result = eval("false");
        assertThat(result).isEqualTo(Boolean.FALSE);
    }

    @Test
    void evaluate_nilLiteral() {
        // Goal: Verify that the NAME token "nil" evaluates to Java null.
        // Requirement: LuaTableBuilder must treat the Lua keyword "nil" as a null
        //              value so that absent optional fields can be represented as null
        //              in the result map.
        // Rationale: Rockspec files sometimes explicitly assign nil to reset a field;
        //            the extractor must not treat nil as the string "nil".
        Object result = eval("nil");
        assertThat(result).isNull();
    }

    // -----------------------------------------------------------------------
    // Table constructor tests
    // -----------------------------------------------------------------------

    @Test
    void evaluate_emptyTable() {
        // Goal: Verify that an empty table constructor {} evaluates to an empty List.
        // Requirement: LuaTableBuilder must return a List (not a Map) for a table
        //              with no entries, matching the Lua convention that {} is
        //              ordinarily used as an empty sequence.
        // Rationale: Dependencies tables in rockspecs may be empty; the extractor
        //            must not throw or return null for {}.
        Object result = eval("{}");
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).isEmpty();
    }

    @Test
    void evaluate_hashTable() {
        // Goal: Verify that a table with named fields evaluates to a Map whose
        //       keys match the field names and whose values are the evaluated
        //       expressions.
        // Requirement: LuaTableBuilder must return a Map<String, Object> for tables
        //              that use the name = value syntax.
        // Rationale: The description, source, and build tables in a rockspec are
        //            all hash-style tables; correct Map production is essential.
        Object result = eval("{name = \"test\", version = \"1.0\"}");
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("name", "test");
        assertThat(map).containsEntry("version", "1.0");
    }

    @Test
    void evaluate_arrayTable() {
        // Goal: Verify that a table with only positional elements evaluates to a
        //       List in insertion order.
        // Requirement: LuaTableBuilder must return List.of("a","b","c") for the
        //              expression {"a", "b", "c"}.
        // Rationale: The dependencies field in a rockspec is always an array-style
        //            table; the extractor depends on receiving a List so it can
        //            iterate the dependency strings.
        Object result = eval("{\"a\", \"b\", \"c\"}");
        assertThat(result).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) result;
        assertThat(list).containsExactly("a", "b", "c");
    }

    @Test
    void evaluate_nestedTable() {
        // Goal: Verify that a table containing another table as a field value
        //       produces a Map whose nested value is itself a Map.
        // Requirement: LuaTableBuilder must recurse into nested table constructors
        //              and return them as nested Java Map objects.
        // Rationale: Rockspecs have multi-level nesting (e.g. build = { modules =
        //            { ... } }); the extractor must surface these nested structures
        //            faithfully.
        Object result = eval("{inner = {x = 1}}");
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> outer = (Map<String, Object>) result;
        assertThat(outer).containsKey("inner");
        assertThat(outer.get("inner")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> inner = (Map<String, Object>) outer.get("inner");
        assertThat(inner).containsEntry("x", 1);
    }

    // -----------------------------------------------------------------------
    // String concatenation tests
    // -----------------------------------------------------------------------

    @Test
    void evaluate_stringConcat() {
        // Goal: Verify that two strings joined with the .. operator produce a
        //       single concatenated Java String.
        // Requirement: LuaTableBuilder must evaluate the .. operator between any two
        //              string-producing sub-expressions and return the joined result.
        // Rationale: Rockspecs often build the version field via concatenation (e.g.
        //            "1.0" .. "-" .. build_number); the extractor must resolve these.
        Object result = eval("\"hello\" .. \" \" .. \"world\"");
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void evaluate_concatChained() {
        // Goal: Verify that three strings chained with .. are all joined into
        //       a single result string.
        // Requirement: LuaTableBuilder must handle arbitrarily long chains of the
        //              .. operator, not just pairs.
        // Rationale: Rockspecs sometimes build strings from more than two parts;
        //            a two-operand-only implementation would silently truncate the
        //            result.
        Object result = eval("\"a\" .. \"b\" .. \"c\"");
        assertThat(result).isEqualTo("abc");
    }

    // -----------------------------------------------------------------------
    // Variable reference tests
    // -----------------------------------------------------------------------

    @Test
    void evaluate_variableReference() {
        // Goal: Verify that a bare NAME token whose value matches a key in the
        //       env map is resolved to the corresponding value.
        // Requirement: LuaTableBuilder must look up NAME tokens in the environment
        //              map and return the bound value.
        // Rationale: Rockspecs frequently define a local variable (e.g. version)
        //            and then reference it in later expressions; the builder must
        //            honour that binding.
        Map<String, Object> env = Map.of("x", "hello");
        Object result = eval("x", env);
        assertThat(result).isEqualTo("hello");
    }

    @Test
    void evaluate_concatWithVariable() {
        // Goal: Verify that .. between a string literal and an env variable
        //       resolves the variable before concatenating.
        // Requirement: LuaTableBuilder must resolve variable references on both
        //              sides of a .. expression before performing concatenation.
        // Rationale: Rockspec authors often write version = "v" .. ver where ver
        //            is a local variable; the extractor must produce "v1.0" not null.
        Map<String, Object> env = Map.of("ver", "1.0");
        Object result = eval("\"v\" .. ver", env);
        assertThat(result).isEqualTo("v1.0");
    }

    // -----------------------------------------------------------------------
    // Bracket key syntax test
    // -----------------------------------------------------------------------

    @Test
    void evaluate_bracketKey() {
        // Goal: Verify that a table field written with the bracket-key syntax
        //       ["key"] = value produces a Map entry with the string key.
        // Requirement: LuaTableBuilder must parse the [expr] = value form of table
        //              field specification and use the evaluated expression as the map
        //              key when it is a String.
        // Rationale: Some rockspec build tables use bracket keys for module paths
        //            that contain dots or hyphens which are not valid bare identifiers.
        Object result = eval("{[\"key\"] = \"val\"}");
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("key", "val");
    }

    // -----------------------------------------------------------------------
    // Mixed / edge-case table tests
    // -----------------------------------------------------------------------

    @Test
    void evaluate_mixedTable() {
        // Goal: Verify that a hash table containing entries of different value
        //       types (Number and String) produces a Map with correctly typed values.
        // Requirement: LuaTableBuilder must preserve the Java type of each evaluated
        //              field value rather than coercing everything to String.
        // Rationale: Rockspec tables such as description frequently mix string and
        //            numeric values; the extractor must not downgrade numbers to
        //            strings during collection.
        Object result = eval("{a = 1, b = \"two\"}");
        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map).containsEntry("a", 1);
        assertThat(map).containsEntry("b", "two");
    }

    @Test
    void evaluate_trailingComma() {
        // Goal: Verify that a trailing comma after the last array element is
        //       accepted and produces a List of the correct size (2, not 3).
        // Requirement: LuaTableBuilder must skip trailing separators without
        //              inserting a phantom null element at the end of the list.
        // Rationale: Trailing commas are idiomatic Lua style and appear in most
        //            real-world rockspec dependency tables; rejecting them would
        //            break corpus-wide extraction.
        Object result = eval("{\"a\", \"b\",}");
        assertThat(result).isInstanceOf(List.class);
        assertThat((List<?>) result).hasSize(2);
        @SuppressWarnings("unchecked")
        List<String> list2 = (List<String>) result;
        assertThat(list2).containsExactly("a", "b");
    }

    // -----------------------------------------------------------------------
    // Unsupported expression test
    // -----------------------------------------------------------------------

    @Test
    void evaluate_unsupportedExpression() {
        // Goal: Verify that a function-call expression (an unsupported construct)
        //       evaluates to null rather than throwing an exception.
        // Requirement: LuaTableBuilder must return null — not throw — when it
        //              encounters an expression form it cannot evaluate.
        // Rationale: Rockspecs may contain calls to utility functions (e.g.
        //            require("luarocks.core.util").version); the extractor must
        //            tolerate these gracefully and continue processing other fields.
        Object result = eval("foo()");
        assertThat(result).isNull();
    }
}
